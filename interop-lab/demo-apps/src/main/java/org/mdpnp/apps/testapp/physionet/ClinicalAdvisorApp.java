package org.mdpnp.apps.testapp.physionet;

import com.fasterxml.jackson.databind.JsonNode;
import ice.SampleArray;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.mdpnp.apps.fxbeans.SampleArrayFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.springframework.context.ApplicationContext;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * ClinicalAdvisorApp
 * ==================
 * JavaFX controller for the Clinical Advisor display.
 *
 * Subscribes to ice.SampleArray on the OpenICE DDS bus via SampleArrayFxList.
 * When ECG lead samples arrive from PhysioNetReplayDevice, they are forwarded
 * to ECGAdvisorBridge, which buffers them and calls the Python inference
 * service every 5 seconds to produce a diagnosis.
 *
 * DDS wiring pattern follows MddtEcipApp / DDSDataLoggerApp:
 *   - Factory resolves "sampleList" Spring bean (SampleArrayFxList)
 *   - Factory calls set(sampleList) before returning the IceApp
 *   - activate() attaches a ListChangeListener to the live FX list
 *   - stop() detaches the listener
 */
public class ClinicalAdvisorApp implements ECGAdvisorBridge.DiagnosisListener {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss");

    // -- FXML bindings --------------------------------------------------------
    @FXML private Label       primaryDiagnosisLabel;
    @FXML private Label       primaryConfidenceLabel;
    @FXML private ProgressBar primaryConfidenceBar;
    @FXML private Label       primaryStatusLabel;

    @FXML private VBox        probBarsContainer;

    @FXML private Label       lbl1dAVb;
    @FXML private ProgressBar bar1dAVb;
    @FXML private Label       pct1dAVb;

    @FXML private Label       lblRBBB;
    @FXML private ProgressBar barRBBB;
    @FXML private Label       pctRBBB;

    @FXML private Label       lblLBBB;
    @FXML private ProgressBar barLBBB;
    @FXML private Label       pctLBBB;

    @FXML private Label       lblSB;
    @FXML private ProgressBar barSB;
    @FXML private Label       pctSB;

    @FXML private Label       lblAF;
    @FXML private ProgressBar barAF;
    @FXML private Label       pctAF;

    @FXML private Label       lblST;
    @FXML private ProgressBar barST;
    @FXML private Label       pctST;

    @FXML private Label       inferenceTimeLabel;
    @FXML private Label       latencyLabel;
    @FXML private Label       modelLabel;
    @FXML private TextArea    logArea;
    @FXML private Label       statusLabel;
    @FXML private Rectangle   statusIndicator;

    // -- DDS subscription state -----------------------------------------------
    private SampleArrayFxList sampleList;
    private final ListChangeListener<SampleArrayFx> sampleListener =
        this::onSampleChange;

    // -- Inference bridge -----------------------------------------------------
    private ECGAdvisorBridge bridge;

    // -- Lifecycle ------------------------------------------------------------

    /**
     * Called by the factory after FXML load to inject the shared DDS list.
     * Must be called before initialize().
     */
    public void set(SampleArrayFxList sampleList) {
        this.sampleList = sampleList;
    }

    /**
     * Called by the factory after set(). Creates the bridge and sets initial
     * UI state. Does NOT attach the DDS listener yet -- that happens in activate().
     */
    public void initialize() {
        bridge = new ECGAdvisorBridge(this);
        modelLabel.setText(
            "Ribeiro et al. (Nature Comms 2020)  |  ResNet  |  6 classes");
        updateStatus("Waiting for ECG data from PhysioNet Replay...", false);
        appendLog("Clinical Advisor initialized.");
        appendLog("Model: Ribeiro ResNet -- 6-class ECG abnormality classifier");
        appendLog("Waiting for 12-lead ECG from PhysioNet Replay device...");
        appendLog("Ensure ecg_inference_service.py is running on port 7891.");
    }

    /**
     * Called by the IceApp when the app tab becomes visible.
     * Attaches the SampleArray listener and sweeps any already-present entries
     * so we pick up data from a device that was already playing before this
     * app was opened.
     */
    public void activate(ApplicationContext ctx) {
        if (sampleList != null) {
            sampleList.addListener(sampleListener);
            appendLog("DDS listener attached -- monitoring ice.SampleArray.");
            updateStatus("Listening for ECG data...", false);

            // Sweep entries already in the list -- device may already be playing
            int existing = 0;
            for (SampleArrayFx sa : sampleList) {
                if (sa.getMetric_id() != null && sa.getMetric_id().startsWith("MDC_ECG_ELEC_POTL_")) {
                    sa.valuesProperty().addListener((obs, oldV, newV) -> forwardToBridge(sa));
                    forwardToBridge(sa);
                    existing++;
                }
            }
            if (existing > 0) {
                appendLog("Found " + existing
                    + " existing SampleArray entries -- forwarding to bridge.");
            }
        } else {
            appendLog("WARNING: sampleList not injected -- no DDS subscription.");
        }
    }

    /**
     * Called when the app tab is closed. Detaches the listener.
     */
    public void stop() {
        if (sampleList != null) {
            sampleList.removeListener(sampleListener);
        }
    }

    public void destroy() {
        stop();
        if (bridge != null) bridge.shutdown();
    }

    // -- DDS listener ---------------------------------------------------------

    /**
     * Called on the JavaFX thread whenever the SampleArrayFxList is updated.
     * The SampleArrayFxList maintains one slot per (UDI, metric_id) pair and
     * updates that slot in place on each new sample. Both wasAdded and
     * wasUpdated must be handled -- and they can both be true simultaneously,
     * so we use independent checks rather than else-if.
     */
    private void onSampleChange(
            ListChangeListener.Change<? extends SampleArrayFx> change) {
        while (change.next()) {
            if (change.wasAdded()) {
                for (SampleArrayFx sa : change.getAddedSubList()) {
                    if (sa.getMetric_id() != null && sa.getMetric_id().startsWith("MDC_ECG_ELEC_POTL_")) {
                        sa.valuesProperty().addListener((obs, oldV, newV) -> forwardToBridge(sa));
                        forwardToBridge(sa);
                    }
                }
            }
            // wasUpdated() won't fire for valuesProperty changes because the list
            // isn't created with an extractor, so we rely on the direct valuesProperty listeners above.
        }
    }

    private void forwardToBridge(SampleArrayFx sa) {
        if (sa == null || sa.getMetric_id() == null || sa.getValues() == null) return;

        // Accept only ECG leads -- filter out PLETH, ABP, RESP etc.
        if (!sa.getMetric_id().startsWith("MDC_ECG_ELEC_POTL_")) return;

        int n = sa.getValues().length;
        if (n == 0) return;

        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            samples[i] = sa.getValues()[i].floatValue();
        }

        // sa.frequency is in Hz; fall back to 500 if not set
        double fs = (sa.getFrequency() > 0) ? sa.getFrequency() : 500.0;

        bridge.onSample(sa.getMetric_id(), samples, fs);
    }

    // -- DiagnosisListener implementation ------------------------------------

    @Override
    public void onDiagnosis(JsonNode result) {
        Platform.runLater(() -> updateDisplay(result));
    }

    @Override
    public void onServiceUnavailable(String reason) {
        Platform.runLater(() -> {
            updateStatus("Inference service unavailable", true);
            appendLog("ERROR: " + reason);
        });
    }

    // -- Display update -------------------------------------------------------

    private void updateDisplay(JsonNode result) {
        if (!"ok".equals(result.path("status").asText())) {
            updateStatus(
                "Inference error: " + result.path("message").asText(), true);
            return;
        }

        String topName = result.path("top_name").asText("Normal Sinus Rhythm");
        double topProb = result.path("top_prob").asDouble(0.0);
        boolean isNormal = result.path("top").isNull() ||
                           result.path("top").isMissingNode();

        primaryDiagnosisLabel.setText(topName);
        primaryDiagnosisLabel.setStyle(isNormal
            ? "-fx-text-fill: #2EA44F; -fx-font-size: 22px; -fx-font-weight: bold;"
            : "-fx-text-fill: #E06C1A; -fx-font-size: 22px; -fx-font-weight: bold;");
        primaryConfidenceLabel.setText(String.format("%.1f%%", topProb * 100));
        primaryConfidenceBar.setProgress(topProb);
        primaryStatusLabel.setText(
            isNormal ? "No abnormality detected" : "Abnormality detected");

        JsonNode probs = result.path("probs");
        ProgressBar[] bars = {bar1dAVb, barRBBB, barLBBB, barSB, barAF, barST};
        Label[]       pcts = {pct1dAVb, pctRBBB, pctLBBB, pctSB, pctAF, pctST};
        for (int i = 0; i < 6; i++) {
            double p = probs.path(i).asDouble(0.0);
            bars[i].setProgress(p);
            pcts[i].setText(String.format("%.1f%%", p * 100));
            bars[i].setStyle(p >= 0.5
                ? "-fx-accent: #E06C1A;"
                : "-fx-accent: #1A7A6E;");
        }

        String timestamp = LocalTime.now().format(TIME_FMT);
        double ms = result.path("inference_ms").asDouble(0.0);
        inferenceTimeLabel.setText("Last inference: " + timestamp);
        latencyLabel.setText(String.format("Inference time: %.0f ms", ms));

        updateStatus("Active -- inference running", false);
        appendLog(String.format("[%s] %s (%.1f%%)  |  %.0f ms",
            timestamp, topName, topProb * 100, ms));
    }

    private void updateStatus(String message, boolean isError) {
        statusLabel.setText(message);
        statusIndicator.setFill(
            isError ? Color.valueOf("#E06C1A") : Color.valueOf("#2EA44F"));
    }

    private void appendLog(String line) {
        logArea.appendText(line + "\n");
    }
}
