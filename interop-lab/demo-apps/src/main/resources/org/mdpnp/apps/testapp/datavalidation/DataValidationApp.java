package org.mdpnp.apps.testapp.datavalidation;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.paint.Color;
import javafx.util.Callback;

import org.mdpnp.apps.fxbeans.SampleArrayFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.guis.waveform.SampleArrayWaveformSource;
import org.mdpnp.guis.waveform.WaveformSource;
import org.mdpnp.guis.waveform.javafx.JavaFXWaveformPane;
import org.springframework.context.ApplicationContext;

import javafx.collections.ListChangeListener;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * DataValidationApp
 * =================
 * JavaFX controller for the Data Validation OpenICE application.
 *
 * Panel 1 (GREEN)  -- raw PPG waveform from DDS via SampleArrayWaveformSource
 * Panel 2 (YELLOW) -- SQI step trace: drawn on canvas directly each time a new
 *                     SQI result arrives from PpgSqiBridge
 * Panel 3 (CYAN)   -- cleaned PPG: also drawn on canvas directly, showing the
 *                     raw PPG with zeroed sections where SQI was below threshold
 *
 * Panes 2 and 3 use direct canvas drawing (scrolling strip chart) triggered
 * by onSqiResult() on the JavaFX thread, avoiding the WaveformSource dependency.
 */
public class DataValidationApp implements PpgSqiBridge.SqiListener {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Color BG_COLOR = Color.web("#0d1117");

    // -- FXML bindings --------------------------------------------------------
    @FXML private ComboBox<SampleArrayFx> waveformSelector;

    @FXML private JavaFXWaveformPane rawWaveformPane;
    @FXML private JavaFXWaveformPane validationSignalPane;
    @FXML private JavaFXWaveformPane validatedWaveformPane;

    @FXML private Label       sqiLabel;
    @FXML private Label       verdictLabel;
    @FXML private ProgressBar sqiBar;
    @FXML private Label       perfLabel;
    @FXML private Label       specLabel;
    @FXML private Label       autocorrLabel;
    @FXML private Label       latencyLabel;
    @FXML private Label       statusLabel;

    // -- State ----------------------------------------------------------------
    private SampleArrayFxList         sampleList;
    private SampleArrayWaveformSource rawSource;
    private PpgSqiBridge              bridge;
    private SampleArrayFx             selectedSa;

    // Rolling sample history for canvas drawing (last N raw PPG values)
    private final Deque<Float> rawHistory = new ArrayDeque<>(2000);
    private static final int   HISTORY    = 1500;

    // Last SQI verdict for cleaned PPG zeroing
    private volatile boolean lastAccept = true;

    private final ChangeListener<Number[]> valuesListener =
        (obs, oldV, newV) -> {
            if (newV == null || selectedSa == null) return;
            float[] samples = new float[newV.length];
            for (int i = 0; i < newV.length; i++) {
                samples[i] = newV[i] == null ? 0f : newV[i].floatValue();
            }
            double fs = selectedSa.getFrequency() > 0
                        ? selectedSa.getFrequency() : 100.0;
            // Buffer raw samples for cleaned PPG display
            synchronized (rawHistory) {
                for (float s : samples) {
                    rawHistory.addLast(s);
                    while (rawHistory.size() > HISTORY) rawHistory.removeFirst();
                }
            }
            bridge.onSamples(samples, fs);
        };

    // -- Lifecycle ------------------------------------------------------------

    public void set(SampleArrayFxList sampleList) {
        this.sampleList = sampleList;
        waveformSelector.setItems(sampleList);
    }

    public void initialize() {
        bridge = new PpgSqiBridge(this);
        setupSelector();
        setupWaveformPanes();
        updateStatusLabel("Select a PPG signal. SQI updates every 5 seconds -- no Python required.");
    }

    public void activate(ApplicationContext context) {
        bridge.start();
        rawWaveformPane.start();
        // Panes 2 and 3 render on demand via onSqiResult -- no start() needed
    }

    public void stop() {
        detachSelectedListener();
        rawWaveformPane.stop();
        validationSignalPane.stop();
        validatedWaveformPane.stop();
    }

    public void destroy() {
        stop();
        if (bridge != null) bridge.shutdown();
    }

    // -- Signal selector ------------------------------------------------------

    private void setupSelector() {
        waveformSelector.setCellFactory(
            new Callback<ListView<SampleArrayFx>, ListCell<SampleArrayFx>>() {
                @Override
                public ListCell<SampleArrayFx> call(ListView<SampleArrayFx> param) {
                    return new ListCell<SampleArrayFx>() {
                        @Override
                        protected void updateItem(SampleArrayFx item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty || item == null) {
                                setText(null);
                            } else {
                                setText(item.getMetric_id()
                                    + "  [" + item.getUnique_device_identifier() + "]");
                            }
                        }
                    };
                }
            });
        waveformSelector.setButtonCell(
            waveformSelector.getCellFactory().call(null));
        waveformSelector.getSelectionModel().selectedItemProperty()
            .addListener(new ChangeListener<SampleArrayFx>() {
                @Override
                public void changed(ObservableValue<? extends SampleArrayFx> obs,
                                    SampleArrayFx old, SampleArrayFx newVal) {
                    onSignalSelected(newVal);
                }
            });
    }

    private void onSignalSelected(SampleArrayFx sa) {
        detachSelectedListener();
        if (sa != null && sampleList != null) {
            ice.SampleArray keyHolder = new ice.SampleArray();
            sampleList.getReader().get_key_value(keyHolder, sa.getHandle());
            rawSource = new SampleArrayWaveformSource(
                sampleList.getReader(), keyHolder);
            rawWaveformPane.setSource(rawSource);
            selectedSa = sa;
            sa.valuesProperty().addListener(valuesListener);
            synchronized (rawHistory) { rawHistory.clear(); }
            updateStatusLabel("Streaming: " + sa.getMetric_id()
                + " at " + (int) sa.getFrequency() + " Hz");
        } else {
            rawWaveformPane.setSource(null);
            selectedSa = null;
            rawSource  = null;
        }
    }

    private void detachSelectedListener() {
        if (selectedSa != null) {
            selectedSa.valuesProperty().removeListener(valuesListener);
            selectedSa = null;
        }
    }

    // -- Waveform pane setup --------------------------------------------------

    private void setupWaveformPanes() {
        rawWaveformPane.getCanvas().getGraphicsContext2D().setStroke(Color.LIMEGREEN);
        rawWaveformPane.getCanvas().getGraphicsContext2D().setLineWidth(1.5);
        rawWaveformPane.setOverwrite(false);
        rawWaveformPane.start();

        // Clear panes 2 and 3 to dark background -- they render on demand
        clearCanvas(validationSignalPane.getCanvas());
        clearCanvas(validatedWaveformPane.getCanvas());
    }

    private void clearCanvas(Canvas c) {
        GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(BG_COLOR);
        gc.fillRect(0, 0, c.getWidth(), c.getHeight());
    }

    // -- Canvas rendering for panes 2 and 3 -----------------------------------

    /**
     * Draw the SQI step trace on pane 2.
     * Called on the JavaFX thread each time a new SQI result is available.
     * The SQI value (0-1) is drawn as a flat horizontal bar across the full
     * width of the pane, providing a clear step-change visualization.
     */
    private void drawSqiStep(double sqi, boolean accept) {
        Canvas c  = validationSignalPane.getCanvas();
        double w  = c.getWidth();
        double h  = c.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = c.getGraphicsContext2D();

        // Scroll left by a fixed strip width (representing one 5s window)
        double stripW = Math.max(4, w / 30.0);
        gc.drawImage(c.snapshot(null, null),
            stripW, 0, w - stripW, h,
            0, 0, w - stripW, h);

        // Clear the right strip
        gc.setFill(BG_COLOR);
        gc.fillRect(w - stripW, 0, stripW, h);

        // Draw the SQI level as a horizontal line in the right strip
        double yPx    = h - sqi * h * 0.85 - h * 0.075;
        Color  stroke = accept ? Color.web("#2EA44F") : Color.web("#E06C1A");
        gc.setStroke(stroke);
        gc.setLineWidth(3.0);
        gc.strokeLine(w - stripW, yPx, w, yPx);

        // Draw thin guide lines at 0.0 and threshold (0.4)
        gc.setStroke(Color.web("#30363d"));
        gc.setLineWidth(0.5);
        double yThresh = h - 0.40 * h * 0.85 - h * 0.075;
        gc.strokeLine(0, yThresh, w, yThresh);
    }

    /**
     * Draw the cleaned PPG on pane 3.
     * Shows the raw PPG history from the last window -- zeroed if REJECT.
     */
    private void drawCleanedPpg(boolean accept) {
        Canvas c  = validatedWaveformPane.getCanvas();
        double w  = c.getWidth();
        double h  = c.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(BG_COLOR);
        gc.fillRect(0, 0, w, h);

        float[] history;
        synchronized (rawHistory) {
            history = new float[rawHistory.size()];
            int i = 0;
            for (float v : rawHistory) history[i++] = v;
        }

        if (history.length < 2) return;

        // Find min/max for scaling
        float yMin = history[0], yMax = history[0];
        for (float v : history) {
            if (v < yMin) yMin = v;
            if (v > yMax) yMax = v;
        }
        float range = yMax - yMin;
        if (range < 1e-6f) return;

        double margin = 0.075 * h;
        double drawH  = h - 2 * margin;
        double xStep  = w / (double)(history.length - 1);

        gc.setStroke(accept ? Color.CYAN : Color.web("#1A4A5A"));
        gc.setLineWidth(1.5);
        gc.beginPath();
        for (int i = 0; i < history.length; i++) {
            double x = i * xStep;
            double y;
            if (!accept) {
                // REJECT: zero the signal (flat line at midpoint)
                y = h / 2.0;
            } else {
                y = margin + drawH * (1.0 - (history[i] - yMin) / range);
            }
            if (i == 0) gc.moveTo(x, y);
            else        gc.lineTo(x, y);
        }
        gc.stroke();
    }

    // -- SqiListener ----------------------------------------------------------

    @Override
    public void onSqiResult(double sqi, String verdict,
                             double perfusion, double spectral,
                             double autocorr, double ms) {
        final boolean accept = "ACCEPT".equals(verdict);
        lastAccept = accept;

        Platform.runLater(() -> {
            // Update status labels
            if (sqiLabel     != null) sqiLabel.setText(String.format("%.1f%%", sqi * 100));
            if (verdictLabel != null) {
                verdictLabel.setText(verdict);
                verdictLabel.setStyle(accept
                    ? "-fx-text-fill: #2EA44F; -fx-font-weight: bold;"
                    : "-fx-text-fill: #E06C1A; -fx-font-weight: bold;");
            }
            if (sqiBar != null) {
                sqiBar.setProgress(sqi);
                sqiBar.setStyle(accept ? "-fx-accent: #2EA44F;" : "-fx-accent: #E06C1A;");
            }
            if (perfLabel     != null) perfLabel.setText(String.format("%.2f", perfusion));
            if (specLabel     != null) specLabel.setText(String.format("%.2f", spectral));
            if (autocorrLabel != null) autocorrLabel.setText(String.format("%.2f", autocorr));
            if (latencyLabel  != null) latencyLabel.setText(String.format("%.0f ms", ms));

            updateStatusLabel(String.format("[%s] SQI=%.1f%%  %s  (perf=%.2f spec=%.2f autocorr=%.2f)",
                LocalTime.now().format(TIME_FMT), sqi * 100, verdict,
                perfusion, spectral, autocorr));

            // Render panes 2 and 3 on the FX thread
            drawSqiStep(sqi, accept);
            drawCleanedPpg(accept);
        });
    }

    @Override
    public void onServiceUnavailable(String reason) {
        Platform.runLater(() -> updateStatusLabel("ERROR: " + reason));
    }

    private void updateStatusLabel(String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
    }
}
