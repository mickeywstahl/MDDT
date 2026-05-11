package org.mdpnp.apps.testapp.physionet;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ECGAdvisorBridge
 * ================
 * Buffers 12-lead ECG samples arriving on the OpenICE DDS bus from the
 * PhysioNetReplayDevice, then periodically calls the Python ECG inference
 * microservice to get a diagnosis.
 *
 * Protocol with Python service (localhost:7891):
 *   Send: 4-byte big-endian length + UTF-8 JSON
 *     { "ecg": [[lead0_s0..s4095], [lead1_s0..s4095], ...12 leads],
 *       "fs": 500.0 }
 *   Recv: 4-byte big-endian length + UTF-8 JSON
 *     { "status": "ok", "labels": [...], "probs": [...],
 *       "top": "AF", "top_name": "Atrial Fibrillation",
 *       "top_prob": 0.94, "inference_ms": 42.3 }
 *
 * Usage:
 *   ECGAdvisorBridge bridge = new ECGAdvisorBridge(listener);
 *   // Call onSample() each time a new ECG SampleArray arrives from DDS
 *   bridge.onSample("MDC_ECG_ELEC_POTL_II", samples, fs);
 *   bridge.shutdown();
 *
 * The bridge accumulates 10.24 seconds of data (4096 samples at 400 Hz,
 * accepting input at any source rate and resampling in Python).
 * When all 12 leads have a full buffer, inference is triggered automatically.
 * After each inference the buffers are cleared for the next window.
 */
public class ECGAdvisorBridge {

    private static final Logger log =
        LoggerFactory.getLogger(ECGAdvisorBridge.class);

    // -- Configuration -----------------------------------------------------
    private static final String PYTHON_HOST = "127.0.0.1";
    private static final int    PYTHON_PORT = 7891;

    /** Minimum seconds of data to accumulate before running inference.
     *  At 100 Hz this fills in 5s; at 500 Hz in 5s. Python resamples to 400Hz/4096. */
    private static final double BUFFER_SECONDS = 5.0;

    /** Minimum interval between inference calls in milliseconds. */
    private static final long MIN_INFERENCE_INTERVAL_MS = 5_000;

    // -- MDC metric ID to lead index mapping (Ribeiro lead order) ----------
    // Ribeiro expects: I, II, III, aVR, aVL, aVF, V1, V2, V3, V4, V5, V6
    private static final Map<String, Integer> LEAD_INDEX = new HashMap<>();
    static {
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_I",   0);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_II",  1);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_III", 2);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_AVR", 3);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_AVL", 4);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_AVF", 5);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_V1",  6);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_V2",  7);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_V3",  8);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_V4",  9);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_V5",  10);
        LEAD_INDEX.put("MDC_ECG_ELEC_POTL_V6",  11);
    }

    // -- State -------------------------------------------------------------
    /** Per-lead sample buffer. Index = Ribeiro lead index 0-11. */
    private final List<float[]>[] buffers;
    private volatile double sourceFs = 500.0;
    private volatile long lastInferenceTime = 0;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService inferenceExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ecg-inference");
            t.setDaemon(true);
            return t;
        });

    // -- Listener interface -------------------------------------------------
    public interface DiagnosisListener {
        /**
         * Called on the inference thread when a new diagnosis is available.
         * @param result  The full JSON result node from the Python service
         */
        void onDiagnosis(JsonNode result);

        /**
         * Called if the Python service is unreachable.
         */
        void onServiceUnavailable(String reason);
    }

    private final DiagnosisListener listener;

    // -- Constructor --------------------------------------------------------

    @SuppressWarnings("unchecked")
    public ECGAdvisorBridge(DiagnosisListener listener) {
        this.listener = listener;
        this.buffers  = new List[12];
        for (int i = 0; i < 12; i++) {
            buffers[i] = new ArrayList<>(5000);
        }
    }

    // -- Public API --------------------------------------------------------

    /**
     * Called by the DDS subscriber each time a new ECG SampleArray arrives.
     *
     * @param metricId  MDC metric ID of the lead (e.g. "MDC_ECG_ELEC_POTL_II")
     * @param samples   Float samples in physical units (mV from ice.SampleArray)
     * @param fs        Sampling frequency of this signal in Hz
     */
    public synchronized void onSample(String metricId,
                                       float[] samples,
                                       double fs) {
        Integer leadIdx = LEAD_INDEX.get(metricId);
        if (leadIdx == null) return;  // not an ECG lead we care about

        sourceFs = fs;
        // Buffer size in samples = BUFFER_SECONDS * fs, clamped 500..5000
        int bufferSamples = (int) Math.max(500,
            Math.min(5000, Math.ceil(BUFFER_SECONDS * fs)));

        List<float[]> buf = buffers[leadIdx];

        // Append new samples (store as individual floats)
        for (float s : samples) {
            if (buf.size() < bufferSamples) {
                buf.add(new float[]{s});

            }
        }

        // Check if all 12 leads have enough data
        if (isBufferFull() && canRunInference()) {
            triggerInference();
        }
    }

    public void shutdown() {
        inferenceExecutor.shutdownNow();
    }

    // -- Private methods ----------------------------------------------------

    private boolean isBufferFull() {
        int bufferSamples = (int) Math.max(500,
            Math.min(5000, Math.ceil(BUFFER_SECONDS * sourceFs)));
        for (List<float[]> buf : buffers) {
            if (buf.size() < bufferSamples) return false;
        }
        return true;
    }

    private boolean canRunInference() {
        long now = System.currentTimeMillis();
        return (now - lastInferenceTime) >= MIN_INFERENCE_INTERVAL_MS;
    }

    private void triggerInference() {
        lastInferenceTime = System.currentTimeMillis();
        int bufferSamples = (int) Math.max(500,
            Math.min(5000, Math.ceil(BUFFER_SECONDS * sourceFs)));

        // Snapshot and clear buffers
        float[][] snapshot = new float[12][bufferSamples];
        for (int lead = 0; lead < 12; lead++) {
            List<float[]> buf = buffers[lead];
            for (int s = 0; s < bufferSamples && s < buf.size(); s++) {
                snapshot[lead][s] = buf.get(s)[0];
            }
            buf.clear();
        }
        double fs = sourceFs;

        // Run inference off the calling thread
        inferenceExecutor.submit(() -> runInference(snapshot, fs));
    }

    private void runInference(float[][] ecgData, double fs) {
        try {
            // Build JSON request
            ObjectNode request = json.createObjectNode();
            ArrayNode ecgNode = request.putArray("ecg");
            for (int lead = 0; lead < 12; lead++) {
                ArrayNode leadNode = ecgNode.addArray();
                for (float sample : ecgData[lead]) {
                    // Convert mV (ice.SampleArray) to -V (Python expects -V)
                    leadNode.add(sample * 1000.0f);
                }
            }
            request.put("fs", fs);

            byte[] requestBytes = json.writeValueAsBytes(request);

            // Send to Python service
            try (Socket socket = new Socket(PYTHON_HOST, PYTHON_PORT)) {
                socket.setSoTimeout(10_000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream  in  = new DataInputStream(socket.getInputStream());

                // Length-prefixed send
                out.writeInt(requestBytes.length);
                out.write(requestBytes);
                out.flush();

                // Length-prefixed receive
                int responseLen = in.readInt();
                byte[] responseBytes = new byte[responseLen];
                in.readFully(responseBytes);

                JsonNode result = json.readTree(responseBytes);
                if (listener != null) {
                    listener.onDiagnosis(result);
                }
            }

        } catch (java.net.ConnectException e) {
            log.warn("ECG inference service not reachable on {}:{} - " +
                     "start ecg_inference_service.py", PYTHON_HOST, PYTHON_PORT);
            if (listener != null) {
                listener.onServiceUnavailable(
                    "Python inference service not running on port " + PYTHON_PORT +
                    ".\nStart: python ecg_inference_service.py");
            }
        } catch (Exception e) {
            log.error("Inference error: {}", e.getMessage());
            if (listener != null) {
                listener.onServiceUnavailable("Inference error: " + e.getMessage());
            }
        }
    }
}
