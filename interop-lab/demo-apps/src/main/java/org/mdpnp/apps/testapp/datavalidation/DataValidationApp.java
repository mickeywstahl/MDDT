package org.mdpnp.apps.testapp.datavalidation;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Callback;

import org.mdpnp.apps.fxbeans.SampleArrayFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.guis.waveform.javafx.JavaFXWaveformPane;
import org.springframework.context.ApplicationContext;

/**
 * DataValidationApp
 * =================
 * JavaFX Controller for the Data Validation OpenICE application.
 *
 * All three waveform panes are driven by a single AnimationTimer that reads
 * from rolling sample buffers, so they all update at the display frame rate
 * without depending on WaveformSource / WaveformIterator.
 *
 * Pane 1 (teal)   -- raw PPG; during artifact simulation draws the corrupted
 *                    signal so the viewer can see what is being injected
 * Pane 2 (yellow) -- SQI step trace updated on each 5s window result
 * Pane 3 (teal)   -- cleaned PPG: full signal on ACCEPT, flat on REJECT
 */
public class DataValidationApp {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(DataValidationApp.class);

    // -- FXML bindings --------------------------------------------------------
    @FXML private ComboBox<SampleArrayFx> waveformSelector;
    @FXML private JavaFXWaveformPane rawWaveformPane;
    @FXML private JavaFXWaveformPane validationSignalPane;
    @FXML private JavaFXWaveformPane validatedWaveformPane;

    @FXML private Label       verdictLabel;
    @FXML private Label       sqiLabel;
    @FXML private ProgressBar sqiBar;
    @FXML private Label       perfLabel;
    @FXML private Label       specLabel;
    @FXML private Label       autocorrLabel;
    @FXML private Label       latencyLabel;
    @FXML private Label       statusLabel;
    @FXML private Rectangle   statusIndicator;
    @FXML private Button      simulateArtifactBtn;
    @FXML private Label       artifactStatusLabel;
    @FXML private Label       recordingLabel;

    // -- New controls ---------------------------------------------------------
    @FXML private ToggleButton fullRecordToggle;
    @FXML private ToggleButton rollingWindowToggle;
    @FXML private Spinner<Integer> windowMinutesSpinner;
    @FXML private Slider   thresholdSlider;
    @FXML private Label    thresholdValueLabel;

    // -- State ----------------------------------------------------------------
    private SampleArrayFxList sampleList;
    private PpgSqiBridge      bridge;
    private SampleArrayFx     activeSa;

    // Window-aligned storage -- one entry per completed SQI window.
    // All three panes share the same x-axis: each column = one 5s window.
    private static final int   SQI_CAP      = 120;    // 10 min at 5s windows
    private static final int   SAMPLES_PER_WIN = 1250; // 5s * 250Hz max

    // Per-window SQI metadata
    private final double[]     sqiBuf       = new double[SQI_CAP];
    private final boolean[]    sqiAccept    = new boolean[SQI_CAP];
    private int                sqiWrite     = 0;
    private int                sqiCount     = 0;

    // Per-window raw sample snapshots (one float[] per completed window)
    @SuppressWarnings("unchecked")
    private final float[][]    winSamples   = new float[SQI_CAP][];
    private final boolean[]    winArtifact  = new boolean[SQI_CAP]; // was simulating?

    // Accumulator for the current (in-progress) window
    private final java.util.ArrayList<Float> currentWin =
        new java.util.ArrayList<>(SAMPLES_PER_WIN);

    // Last SQI verdict for cleaned PPG
    private volatile boolean   lastAccept   = true;

    // AnimationTimer driving all three panes
    private AnimationTimer     renderTimer;

    // -- Screen recorder ------------------------------------------------------
    private MjpegAviRecorder recorder;
    private static final int    ARTIFACT_DURATION_S = 10;
    private static final double ARTIFACT_AMPLITUDE  = 3.0;
    private volatile boolean    simulatingArtifact  = false;
    private volatile long       artifactEndTime     = 0;
    // Set to true as soon as any artifact sample enters the current window;
    // cleared after each window is snapshotted into winArtifact[].
    private volatile boolean    winHadArtifact      = false;

    // -- DDS listener ---------------------------------------------------------
    private final ChangeListener<Number[]> valuesListener = (obs, oldV, newV) -> {
        if (activeSa == null || newV == null || bridge == null) return;

        float[] floats = new float[newV.length];
        if (simulatingArtifact && System.currentTimeMillis() < artifactEndTime) {
            // Inject broadband Gaussian noise + 12 Hz sinusoidal interference
            winHadArtifact = true;  // mark current window as contaminated
            java.util.Random rng = new java.util.Random();
            double sigAmp   = Math.abs(newV.length > 0 ? newV[0].floatValue() : 1.0f);
            double noiseAmp = Math.max(sigAmp, 0.5) * ARTIFACT_AMPLITUDE;
            double fs       = activeSa.getFrequency() > 0 ? activeSa.getFrequency() : 100.0;
            for (int i = 0; i < newV.length; i++) {
                floats[i] = (float)(newV[i].floatValue()
                            + noiseAmp * rng.nextGaussian()
                            + noiseAmp * 0.5 * Math.sin(2 * Math.PI * 12.0 * i / fs));
            }
        } else {
            for (int i = 0; i < newV.length; i++) floats[i] = newV[i].floatValue();
            if (simulatingArtifact) {
                simulatingArtifact = false;
                Platform.runLater(() -> {
                    if (simulateArtifactBtn  != null) simulateArtifactBtn.setDisable(false);
                    if (artifactStatusLabel  != null) artifactStatusLabel.setText("");
                });
            }
        }

        // Accumulate raw samples (post-artifact-injection) into current window
        synchronized (currentWin) {
            for (float s : floats) {
                if (currentWin.size() < SAMPLES_PER_WIN) currentWin.add(s);
            }
        }

        double fs = activeSa.getFrequency() > 0 ? activeSa.getFrequency() : 100.0;
        bridge.onSamples(floats, fs);
    };

    // -- Lifecycle ------------------------------------------------------------

    public void set(SampleArrayFxList sampleList) {
        this.sampleList = sampleList;
        waveformSelector.setItems(sampleList);
    }

    public void initialize() {
        bridge = new PpgSqiBridge((sqi, verdict, perfusion, spectral, autocorr, computeMs) -> {
            boolean accept = "ACCEPT".equals(verdict);
            lastAccept = accept;

            // A window is marked as artifact if simulation was active at ANY point
            // during sample accumulation. We track this via a per-window flag that
            // gets set as soon as the first artifact sample arrives in valuesListener.
            boolean wasArtifact = winHadArtifact;
            winHadArtifact = false;  // reset for next window
            // Snapshot the raw samples accumulated during this window
            float[] windowSnap;
            synchronized (currentWin) {
                windowSnap = new float[currentWin.size()];
                for (int i = 0; i < windowSnap.length; i++)
                    windowSnap[i] = currentWin.get(i);
                currentWin.clear();
            }

            // Store window data and SQI result at the same slot
            synchronized (sqiBuf) {
                int slot = sqiWrite % SQI_CAP;
                sqiBuf   [slot] = sqi;
                sqiAccept[slot] = accept;
                winSamples [slot] = windowSnap;
                winArtifact[slot] = wasArtifact;
                sqiWrite++;
                if (sqiCount < SQI_CAP) sqiCount++;
            }

            Platform.runLater(() -> {
                verdictLabel.setText(verdict);
                sqiLabel.setText(String.format("%.3f", sqi));
                sqiBar.setProgress(sqi);
                perfLabel.setText(String.format("%.3f", perfusion));
                specLabel.setText(String.format("%.3f", spectral));
                autocorrLabel.setText(String.format("%.3f", autocorr));
                latencyLabel.setText(computeMs + " ms");
                if (accept) {
                    verdictLabel.setStyle(
                        "-fx-text-fill: #2EA44F; -fx-font-size: 20px; -fx-font-weight: bold;");
                    sqiBar.setStyle("-fx-accent: #2EA44F;");
                } else {
                    verdictLabel.setStyle(
                        "-fx-text-fill: #E06C1A; -fx-font-size: 20px; -fx-font-weight: bold;");
                    sqiBar.setStyle("-fx-accent: #E06C1A;");
                }
            });
        });
        bridge.start();

        setupSelector();
        startRenderTimer();
        wireWindowControl();
        wireThresholdControl();
    }

    // -- Window count helper --------------------------------------------------

    /**
     * Returns how many of the most-recent SQI windows to display.
     * "Full Record" = all stored windows (up to SQI_CAP).
     * "Rolling"     = at most windowMinutes * 12 windows (12 windows/min at 5s each).
     */
    private int displayWindowCount(int totalStored) {
        if (fullRecordToggle != null && fullRecordToggle.isSelected()) {
            return totalStored;
        }
        int minutes = (windowMinutesSpinner != null)
            ? windowMinutesSpinner.getValue() : 2;
        int maxWins = minutes * 12;   // 60s/min ÷ 5s/window = 12 windows/min
        return Math.min(totalStored, maxWins);
    }

    // -- Wire new controls ----------------------------------------------------

    private void wireWindowControl() {
        if (windowMinutesSpinner != null) {
            windowMinutesSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, 2));
            // disable spinner when Full Record is selected
            if (fullRecordToggle != null) {
                fullRecordToggle.selectedProperty().addListener(
                    (obs, o, selected) ->
                        windowMinutesSpinner.setDisable(selected));
            }
        }
        // Both toggles trigger a repaint automatically via the AnimationTimer —
        // no explicit handler needed; displayWindowCount() is called each frame.
    }

    private void wireThresholdControl() {
        if (thresholdSlider == null) return;
        thresholdSlider.setMin(0.10);
        thresholdSlider.setMax(0.90);
        thresholdSlider.setValue(0.40);
        thresholdSlider.setMajorTickUnit(0.10);
        thresholdSlider.setMinorTickCount(1);
        thresholdSlider.setSnapToTicks(false);
        if (thresholdValueLabel != null)
            thresholdValueLabel.setText("0.40");
        thresholdSlider.valueProperty().addListener((obs, oldV, newV) -> {
            double t = Math.round(newV.doubleValue() * 100.0) / 100.0;
            if (bridge != null) bridge.setThreshold(t);
            if (thresholdValueLabel != null)
                thresholdValueLabel.setText(String.format("%.2f", t));
        });
    }

    public void activate(ApplicationContext context) {
        if (renderTimer != null) renderTimer.start();

        // Register Ctrl+R shortcut for screen recording on the scene
        javafx.scene.Scene scene = rawWaveformPane.getScene();
        if (scene != null) {
            scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
                this::toggleRecording);
        }
    }

    /** Toggle screen recording on/off. Bound to Ctrl+R. */
    private void toggleRecording() {
        if (recorder == null) {
            recorder = new MjpegAviRecorder(rawWaveformPane.getScene());
        }
        if (recorder.isRecording()) {
            recorder.stopRecording();
            String path = recorder.getLastOutputPath();
            updateRecordingLabel(false, null);
            if (statusLabel != null)
                statusLabel.setText("Recording saved: " + path);
            log.info("Recording stopped: {}", path);
        } else {
            try {
                String path = recorder.startRecording();
                updateRecordingLabel(true, path);
                log.info("Recording started: {}", path);
            } catch (java.io.IOException e) {
                if (statusLabel != null)
                    statusLabel.setText("Recording error: " + e.getMessage());
                log.error("Failed to start recording: {}", e.getMessage());
            }
        }
    }

    private void updateRecordingLabel(boolean active, String path) {
        if (recordingLabel == null) return;
        if (active) {
            recordingLabel.setText("  REC");
            recordingLabel.setStyle(
                "-fx-text-fill: #ff3333; -fx-font-weight: bold; " +
                "-fx-font-size: 11px; -fx-background-color: #3a0000; " +
                "-fx-padding: 2 6 2 6; -fx-background-radius: 3;");
        } else {
            recordingLabel.setText("");
            recordingLabel.setStyle("");
        }
    }

    public void stop() {
        if (renderTimer != null) renderTimer.stop();
    }

    public void destroy() {
        stop();
        if (recorder != null && recorder.isRecording()) recorder.stopRecording();
        if (bridge != null) bridge.shutdown();
    }

    // -- Signal selector ------------------------------------------------------

    private void setupSelector() {
        waveformSelector.setCellFactory(new Callback<ListView<SampleArrayFx>,
                ListCell<SampleArrayFx>>() {
            @Override
            public ListCell<SampleArrayFx> call(ListView<SampleArrayFx> p) {
                return new ListCell<SampleArrayFx>() {
                    @Override
                    protected void updateItem(SampleArrayFx item, boolean empty) {
                        super.updateItem(item, empty);
                        setText((empty || item == null) ? null
                            : item.getMetric_id() + " [" + item.getUnique_device_identifier() + "]");
                    }
                };
            }
        });
        waveformSelector.setButtonCell(waveformSelector.getCellFactory().call(null));

        waveformSelector.getSelectionModel().selectedItemProperty()
            .addListener(new ChangeListener<SampleArrayFx>() {
                @Override
                public void changed(ObservableValue<? extends SampleArrayFx> obs,
                                    SampleArrayFx oldVal, SampleArrayFx newVal) {
                    if (oldVal != null) oldVal.valuesProperty().removeListener(valuesListener);

                    activeSa = newVal;

                    // Reset accumulators when signal changes
                    synchronized (currentWin) { currentWin.clear(); }
                    synchronized (sqiBuf)     { sqiWrite = 0; sqiCount = 0; }

                    if (newVal != null && sampleList != null) {
                        newVal.valuesProperty().addListener(valuesListener);
                        if (statusLabel    != null)
                            statusLabel.setText("Active - monitoring " + newVal.getMetric_id());
                        if (statusIndicator != null)
                            statusIndicator.setFill(Color.valueOf("#2EA44F"));
                    } else {
                        if (statusLabel     != null) statusLabel.setText("No signal selected.");
                        if (statusIndicator != null) statusIndicator.setFill(Color.valueOf("#D3D3D3"));
                        if (verdictLabel    != null) { verdictLabel.setText("--");
                            verdictLabel.setStyle("-fx-text-fill: #484f58; -fx-font-size: 20px; -fx-font-weight: bold;"); }
                        if (sqiLabel        != null) sqiLabel.setText("--");
                        if (sqiBar          != null) sqiBar.setProgress(0);
                        if (perfLabel       != null) perfLabel.setText("--");
                        if (specLabel       != null) specLabel.setText("--");
                        if (autocorrLabel   != null) autocorrLabel.setText("--");
                        if (latencyLabel    != null) latencyLabel.setText("-- ms");
                    }
                }
            });
    }

    // -- Canvas rendering -----------------------------------------------------

    private static final Color BG = Color.web("#2b2b2b");

    private void startRenderTimer() {
        renderTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                renderRawPane();
                renderSqiPane();
                renderCleanedPane();
            }
        };
        renderTimer.start();
    }

    /** Shared helper: read current window snapshot arrays in display order. */
    private Object[] getWindowSnapshots() {
        synchronized (sqiBuf) {
            int stored = Math.min(sqiCount, SQI_CAP);
            int count  = displayWindowCount(stored);
            double[]   sqi    = new double[count];
            boolean[]  accept = new boolean[count];
            float[][]  raw    = new float[count][];
            boolean[]  art    = new boolean[count];
            // Take the most-recent `count` windows from the ring
            int start = ((sqiWrite - count) % SQI_CAP + SQI_CAP) % SQI_CAP;
            for (int i = 0; i < count; i++) {
                int slot   = (start + i) % SQI_CAP;
                sqi   [i]  = sqiBuf    [slot];
                accept[i]  = sqiAccept [slot];
                raw   [i]  = winSamples[slot];
                art   [i]  = winArtifact[slot];
            }
            return new Object[]{sqi, accept, raw, art, count};
        }
    }

    /** Compute x boundaries for a window slot given total count and canvas width. */
    private double sliceWidth(int count, double w) {
        return w / Math.max(count, 12);
    }

    private double sliceX(int i, int count, double w) {
        double sw = sliceWidth(count, w);
        return w - count * sw + i * sw;  // right-aligned: newest on right
    }

    private void renderRawPane() {
        Canvas c = rawWaveformPane.getCanvas();
        double w = c.getWidth(), h = c.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(BG); gc.fillRect(0, 0, w, h);

        Object[] snaps = getWindowSnapshots();
        int count = (int) snaps[4];
        if (count == 0) {
            gc.setFill(Color.web("#484f58"));
            gc.setFont(javafx.scene.text.Font.font(11));
            gc.fillText("Waiting for first 5s window...", w * 0.3, h / 2);
            return;
        }

        float[][]  raw    = (float[][]) snaps[2];
        boolean[]  art    = (boolean[]) snaps[3];
        boolean[]  accept = (boolean[]) snaps[1];
        double sw = sliceWidth(count, w);
        double margin = h * 0.075, drawH = h - 2 * margin;

        for (int i = 0; i < count; i++) {
            float[] samples = raw[i];
            if (samples == null || samples.length < 2) continue;
            double x1 = sliceX(i, count, w);
            double x2 = x1 + sw;

            // Background tint for artifact windows
            if (art[i]) {
                gc.setFill(Color.web("#E06C1A", 0.08));
                gc.fillRect(x1, 0, sw, h);
            }

            // Find min/max for this window
            float yMin = samples[0], yMax = samples[0];
            for (float s : samples) {
                if (s < yMin) yMin = s; if (s > yMax) yMax = s;
            }
            float range = yMax - yMin;
            if (range < 1e-6f) continue;

            // Draw waveform clipped to this window's x-slice
            Color stroke = art[i] ? Color.web("#E06C1A") : Color.valueOf("#008080");
            gc.setStroke(stroke);
            gc.setLineWidth(1.2);
            gc.save();
            gc.beginPath(); gc.rect(x1, 0, sw, h); gc.clip();
            gc.beginPath();
            int n = samples.length;
            for (int j = 0; j < n; j++) {
                double x = x1 + sw * j / (n - 1);
                double y = margin + drawH * (1.0 - (samples[j] - yMin) / range);
                if (j == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
            }
            gc.stroke();
            gc.restore();

            // Window separator
            gc.setStroke(Color.web("#383838"));
            gc.setLineWidth(0.5);
            gc.strokeLine(x1, 0, x1, h);
        }
    }

    private void renderSqiPane() {
        Canvas c = validationSignalPane.getCanvas();
        double w = c.getWidth(), h = c.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(BG); gc.fillRect(0, 0, w, h);

        double margin  = h * 0.075;
        double drawH   = h - 2 * margin;
        double liveThreshold = (thresholdSlider != null) ? thresholdSlider.getValue() : 0.40;
        double yThresh = margin + drawH * (1.0 - liveThreshold);

        // Threshold line
        gc.setStroke(Color.web("#484f58"));
        gc.setLineWidth(0.5);
        gc.setLineDashes(4, 4);
        gc.strokeLine(0, yThresh, w, yThresh);
        gc.setLineDashes();
        gc.setFill(Color.web("#484f58"));
        gc.setFont(javafx.scene.text.Font.font(9));
        gc.fillText("1.0",  3, margin + 9);
        gc.fillText(String.format("%.2f", liveThreshold), 3, yThresh - 2);
        gc.fillText("0.0",  3, h - margin);

        Object[] snaps = getWindowSnapshots();
        int count = (int) snaps[4];
        if (count == 0) {
            gc.setFill(Color.web("#484f58"));
            gc.setFont(javafx.scene.text.Font.font(11));
            gc.fillText("Waiting for first 5-second window...", w * 0.25, h / 2);
            return;
        }

        double[]  sqi    = (double[])  snaps[0];
        boolean[] accept = (boolean[]) snaps[1];
        double sw = sliceWidth(count, w);

        for (int i = 0; i < count; i++) {
            double x1  = sliceX(i, count, w);
            double x2  = x1 + sw;
            double yPx = margin + drawH * (1.0 - sqi[i]);

            // Color is determined solely by the SQI algorithm verdict
            gc.setFill(accept[i] ? Color.web("#2EA44F", 0.18) : Color.web("#E06C1A", 0.18));
            gc.fillRect(x1, yPx, sw, h - margin - yPx);

            gc.setStroke(accept[i] ? Color.web("#2EA44F") : Color.web("#E06C1A"));
            gc.setLineWidth(2.5);
            gc.strokeLine(x1, yPx, x2, yPx);

            // Vertical connector to previous step
            if (i > 0) {
                double prevY = margin + drawH * (1.0 - sqi[i - 1]);
                gc.setLineWidth(1.0);
                gc.strokeLine(x1, prevY, x1, yPx);
            }

            // Window separator
            gc.setStroke(Color.web("#383838"));
            gc.setLineWidth(0.5);
            gc.strokeLine(x1, 0, x1, h);
        }
    }

    private void renderCleanedPane() {
        Canvas c = validatedWaveformPane.getCanvas();
        double w = c.getWidth(), h = c.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(BG); gc.fillRect(0, 0, w, h);

        Object[] snaps = getWindowSnapshots();
        int count = (int) snaps[4];
        if (count == 0) return;

        float[][]  raw    = (float[][]) snaps[2];
        boolean[]  accept = (boolean[]) snaps[1];
        double sw = sliceWidth(count, w);
        double margin = h * 0.075, drawH = h - 2 * margin;

        for (int i = 0; i < count; i++) {
            double x1 = sliceX(i, count, w);

            // Window separator
            gc.setStroke(Color.web("#383838"));
            gc.setLineWidth(0.5);
            gc.strokeLine(x1, 0, x1, h);

            // Suppress window based solely on the SQI algorithm verdict
            if (!accept[i]) {
                // REJECT -- flat midline in dark teal
                gc.setStroke(Color.web("#1A4A5A"));
                gc.setLineWidth(1.5);
                gc.strokeLine(x1, h / 2, x1 + sw, h / 2);
                continue;
            }

            float[] samples = raw[i];
            if (samples == null || samples.length < 2) continue;
            float yMin = samples[0], yMax = samples[0];
            for (float s : samples) {
                if (s < yMin) yMin = s; if (s > yMax) yMax = s;
            }
            float range = yMax - yMin;
            if (range < 1e-6f) continue;

            gc.setStroke(Color.valueOf("#008080"));
            gc.setLineWidth(1.2);
            gc.save();
            gc.beginPath(); gc.rect(x1, 0, sw, h); gc.clip();
            gc.beginPath();
            int n = samples.length;
            for (int j = 0; j < n; j++) {
                double x = x1 + sw * j / (n - 1);
                double y = margin + drawH * (1.0 - (samples[j] - yMin) / range);
                if (j == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
            }
            gc.stroke();
            gc.restore();
        }
    }

    // -- Button handler -------------------------------------------------------

    @FXML
    private void onSimulateArtifact() {
        if (activeSa == null) {
            if (artifactStatusLabel != null)
                artifactStatusLabel.setText("Select a signal first.");
            return;
        }
        simulatingArtifact = true;
        artifactEndTime    = System.currentTimeMillis()
                            + (long)(ARTIFACT_DURATION_S * 1000);
        if (simulateArtifactBtn != null)  simulateArtifactBtn.setDisable(true);
        if (artifactStatusLabel != null)
            artifactStatusLabel.setText(
                "Injecting " + ARTIFACT_DURATION_S + "s of motion artifact...");
    }
}
