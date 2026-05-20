package org.mdpnp.apps.testapp.datavalidation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PpgSqiBridge
 * ============
 * Buffers incoming PPG samples from the OpenICE DDS bus, periodically
 * computes Signal Quality Index via PpgSqiEngine (pure Java -- no Python,
 * no external service, no network calls), and maintains two ring buffers
 * for the DataValidationApp waveform panes:
 *
 *   SQI trace   -- flat step value per window scaled to the PPG amplitude
 *                  range so it displays correctly on the same y-axis as the
 *                  raw PPG signal
 *   Cleaned PPG -- original samples where SQI >= threshold, zeroed otherwise
 *
 * Computation runs every WINDOW_SECONDS on a single background thread.
 */
public class PpgSqiBridge {

    private static final Logger log =
        LoggerFactory.getLogger(PpgSqiBridge.class);

    // -- Configuration -------------------------------------------------------
    private static final double WINDOW_SECONDS = 5.0;
    private static final int    RING_CAPACITY  = 7500; // 30s at 250 Hz

    // -- State ---------------------------------------------------------------
    private final List<Float>  inputBuffer   = new ArrayList<>(2000);
    private volatile double    sourceFs      = 100.0;
    private volatile double    lastSqi       = Double.NaN;
    private volatile String    lastVerdict   = "UNKNOWN";

    private final float[]      sqiRing       = new float[RING_CAPACITY];
    private final float[]      cleanedRing   = new float[RING_CAPACITY];
    private volatile int       sqiWritePos   = 0;
    private volatile int       cleanWritePos = 0;

    private final PpgSqiEngine engine        = new PpgSqiEngine();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ppg-sqi-bridge");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> taskFuture;

    // -- Listener ------------------------------------------------------------

    public interface SqiListener {
        void onSqiResult(double sqi, String verdict,
                         double perfusion, double spectral,
                         double autocorr, long computeMs);
    }

    private volatile SqiListener listener;

    // -- Constructor / lifecycle ---------------------------------------------

    public PpgSqiBridge(SqiListener listener) {
        this.listener = listener;
    }

    public void start() {
        long intervalMs = (long)(WINDOW_SECONDS * 1000);
        taskFuture = scheduler.scheduleAtFixedRate(
            this::processWindow, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("PPG SQI bridge started (pure Java). Window={}s", WINDOW_SECONDS);
    }

    public void shutdown() {
        if (taskFuture != null) taskFuture.cancel(false);
        scheduler.shutdownNow();
    }

    // -- Public API ----------------------------------------------------------

    /**
     * Forward new PPG samples from the DDS bus into the bridge.
     * Thread-safe.
     */
    public synchronized void onSamples(float[] samples, double fs) {
        sourceFs = fs;
        for (float s : samples) {
            inputBuffer.add(s);
        }
        // Write raw samples into cleaned ring immediately;
        // zeroed out on REJECT after SQI is computed.
        for (float s : samples) {
            cleanedRing[cleanWritePos % RING_CAPACITY] = s;
            cleanWritePos++;
        }
    }

    public double  getLastSqi()      { return lastSqi;     }
    public String  getLastVerdict()  { return lastVerdict; }
    public float[] getSqiRing()      { return sqiRing;     }
    public float[] getCleanedRing()  { return cleanedRing; }
    public int     getSqiWritePos()  { return sqiWritePos; }
    public int     getCleanWritePos(){ return cleanWritePos; }
    public int     getRingCapacity() { return RING_CAPACITY; }

    /** Adjust the ACCEPT/REJECT threshold at runtime (0.0–1.0). */
    public void setThreshold(double t) { engine.setThreshold(t); }

    /** Expose the engine so callers can read the current threshold. */
    public PpgSqiEngine getEngine() { return engine; }

    // -- Window processing ---------------------------------------------------

    private void processWindow() {
        float[] snapshot;
        double  fs;
        int     cleanStart;

        synchronized (this) {
            if (inputBuffer.isEmpty()) return;
            snapshot = new float[inputBuffer.size()];
            for (int i = 0; i < snapshot.length; i++) {
                snapshot[i] = inputBuffer.get(i);
            }
            inputBuffer.clear();
            fs         = sourceFs;
            cleanStart = cleanWritePos - snapshot.length;
        }

        // Pure Java computation -- no I/O, no blocking
        PpgSqiEngine.SqiResult r = engine.compute(snapshot, fs);

        lastSqi     = r.sqi;
        lastVerdict = r.verdict;

        log.info("SQI={} ({}) perf={} spec={} autocorr={} {}ms",
            String.format("%.3f", r.sqi), r.verdict,
            String.format("%.2f", r.perfusion),
            String.format("%.2f", r.spectral),
            String.format("%.2f", r.autocorr),
            r.computeMs);

        // Scale SQI to PPG amplitude range for display on same y-axis
        float ppgMin = Float.MAX_VALUE, ppgMax = -Float.MAX_VALUE;
        for (float s : snapshot) {
            if (s < ppgMin) ppgMin = s;
            if (s > ppgMax) ppgMax = s;
        }
        float ppgRange  = Math.max(ppgMax - ppgMin, 0.01f);
        float sqiScaled = ppgMin + (float)(r.sqi * ppgRange);

        synchronized (this) {
            // Write flat SQI level into SQI ring for this window
            for (int i = 0; i < snapshot.length; i++) {
                sqiRing[sqiWritePos % RING_CAPACITY] = sqiScaled;
                sqiWritePos++;
            }

            // Zero out cleaned ring for REJECT windows
            if ("REJECT".equals(r.verdict)) {
                for (int i = 0; i < snapshot.length; i++) {
                    int pos = ((cleanStart + i) % RING_CAPACITY
                               + RING_CAPACITY) % RING_CAPACITY;
                    cleanedRing[pos] = 0.0f;
                }
            }
        }

        if (listener != null) {
            listener.onSqiResult(r.sqi, r.verdict,
                r.perfusion, r.spectral, r.autocorr, r.computeMs);
        }
    }
}
