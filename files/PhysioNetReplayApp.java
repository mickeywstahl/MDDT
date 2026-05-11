package org.mdpnp.apps.testapp.physionet;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

/**
 * PhysioNetReplayApp
 * ==================
 * JavaFX controller for the PhysioNet Replay OpenICE app.
 *
 * Layout:
 *   Left   - scrollable list of downloaded WFDB records (from physionet_data/)
 *   Center - signal info for the selected record (with MDC mapping badges)
 *   Bottom - transport controls: play/pause/stop, speed (1?-16?), loop,
 *             progress bar with time readout
 *
 * The controller drives a PhysioNetReplayDevice instance. The device owns
 * the DDS writers and runs the playback scheduler. The controller only
 * handles UI state.
 */
public class PhysioNetReplayApp implements PhysioNetReplayDevice.ReplayListener {

    private static final Logger log =
        LoggerFactory.getLogger(PhysioNetReplayApp.class);

    // -- Speed steps available in the UI -----------------------------------
    private static final double[] SPEEDS = { 0.5, 1.0, 2.0, 4.0, 8.0, 16.0 };
    private static final String[] SPEED_LABELS =
        { "0.5x", "1x", "2x", "4x", "8x", "16x" };
    private int speedIndex = 1; // default 1x

    // -- FXML bindings ------------------------------------------------------
    @FXML private ListView<RecordEntry> recordListView;
    @FXML private Label     recordCountLabel;
    @FXML private Button    refreshBtn;

    @FXML private Label     recordNameLabel;
    @FXML private Label     recordMetaLabel;
    @FXML private TextArea  signalInfoArea;

    @FXML private Button    playBtn;
    @FXML private Button    pauseBtn;
    @FXML private Button    stopBtn;

    @FXML private Button    speedDownBtn;
    @FXML private Label     speedLabel;
    @FXML private Button    speedUpBtn;

    @FXML private CheckBox  loopCheckBox;

    @FXML private ProgressBar playbackProgress;
    @FXML private Label       timeLabel;
    @FXML private Label       statusLabel;

    // -- State --------------------------------------------------------------
    private PhysioNetReplayDevice device;
    private ApplicationContext    springContext;

    private final ExecutorService ioExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "replay-io");
            t.setDaemon(true);
            return t;
        });

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void setDevice(PhysioNetReplayDevice device) {
        this.device = device;
        device.setListener(this);
        device.setLoop(true);
    }

    public void initialize() {
        setupListView();
        setupTransportButtons();
        setupSpeedControls();
        updateSpeedLabel();
        playbackProgress.setProgress(0);
        timeLabel.setText("0:00 / 0:00");
        statusLabel.setText("No record loaded.");
        refreshRecordList();
    }

    public void activate(ApplicationContext ctx) {
        this.springContext = ctx;
        refreshRecordList();
    }

    public void stop() {
        if (device != null) device.pause();
    }

    public void destroy() {
        ioExecutor.shutdownNow();
        if (device != null) device.shutdown();
    }

    // -----------------------------------------------------------------------
    // Record list
    // -----------------------------------------------------------------------

    private void setupListView() {
        recordListView.getSelectionModel().selectedItemProperty()
            .addListener((o, ov, nv) -> {
                if (nv != null) onRecordSelected(nv);
            });
    }

    @FXML
    private void onRefresh() {
        refreshRecordList();
    }

    private void refreshRecordList() {
        List<File> heaFiles = PhysioNetReplayDevice.scanAvailableRecords();
        recordListView.getItems().clear();
        for (File f : heaFiles) {
            recordListView.getItems().add(new RecordEntry(f));
        }
        int count = heaFiles.size();
        recordCountLabel.setText(count + " record" + (count != 1 ? "s" : "")
            + " in physionet_data/");
        if (count == 0) {
            statusLabel.setText("No records found. Use the PhysioNet Browser "
                + "to download records first.");
        }
    }

    private void onRecordSelected(RecordEntry entry) {
        if (device != null) device.stop();
        statusLabel.setText("Loading " + entry.recordName + "?");
        signalInfoArea.clear();
        recordNameLabel.setText(entry.recordName);
        recordMetaLabel.setText(entry.dbPath);
        playbackProgress.setProgress(0);
        timeLabel.setText("0:00 / 0:00");

        ioExecutor.submit(() -> {
            try {
                device.loadRecord(entry.heaFile);
                WfdbReader.WfdbRecord rec = device.getCurrentRecord();
                String info = buildSignalInfo(rec);
                double dur  = rec.durationSeconds();

                Platform.runLater(() -> {
                    signalInfoArea.setText(info);
                    timeLabel.setText("0:00 / " + formatTime(dur));
                    statusLabel.setText("Ready - " + rec.header.recordName
                        + "  [" + rec.header.nSig + " ch @ "
                        + (int) rec.header.fs + " Hz]");
                    updateTransportState(false);
                });
            } catch (Exception e) {
                log.error("Failed to load record", e);
                Platform.runLater(() ->
                    statusLabel.setText("Error loading record: " + e.getMessage()));
            }
        });
    }

    private String buildSignalInfo(WfdbReader.WfdbRecord rec) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Record  : %s%n", rec.header.recordName));
        sb.append(String.format("Fs      : %.0f Hz%n", rec.header.fs));
        sb.append(String.format("Signals : %d%n", rec.header.nSig));
        sb.append(String.format("Length  : %d samples  (%.1f s / %.2f min)%n",
            rec.header.sigLen, rec.durationSeconds(), rec.durationSeconds() / 60));
        if (!rec.header.baseTime.isEmpty())
            sb.append(String.format("Start   : %s  %s%n",
                rec.header.baseTime, rec.header.baseDate));
        sb.append("\n");
        sb.append("--- Channels ------------------------------------------\n");
        for (int ch = 0; ch < rec.header.nSig; ch++) {
            String name = ch < rec.header.sigNames.size()
                ? rec.header.sigNames.get(ch) : "ch" + ch;
            String unit = ch < rec.header.units.size()
                ? rec.header.units.get(ch) : "";
            Optional<WfdbMdcMapper.MdcMapping> m = WfdbMdcMapper.lookup(name);
            String mdcLine = m.map(WfdbMdcMapper.MdcMapping::displayLabel)
                              .orElse("-  no MDC mapping - will not publish");
            sb.append(String.format("  ch %d  %-10s [%s]%n", ch, name, unit));
            sb.append(String.format("         %s%n", mdcLine));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Transport controls
    // -----------------------------------------------------------------------

    private void setupTransportButtons() {
        playBtn.setOnAction(e  -> onPlay());
        pauseBtn.setOnAction(e -> onPause());
        stopBtn.setOnAction(e  -> onStop());
        loopCheckBox.selectedProperty().addListener((o, ov, nv) -> {
            if (device != null) device.setLoop(nv);
        });
        loopCheckBox.setSelected(true);
    }

    @FXML private void onPlay() {
        if (device == null || device.getCurrentRecord() == null) return;
        device.play();
        updateTransportState(true);
        statusLabel.setText("Playing  " + speedLabel.getText());
    }

    @FXML private void onPause() {
        if (device == null) return;
        device.pause();
        updateTransportState(false);
        statusLabel.setText("Paused");
    }

    @FXML private void onStop() {
        if (device == null) return;
        device.stop();
        playbackProgress.setProgress(0);
        WfdbReader.WfdbRecord rec = device.getCurrentRecord();
        String dur = rec != null ? formatTime(rec.durationSeconds()) : "0:00";
        timeLabel.setText("0:00 / " + dur);
        updateTransportState(false);
        statusLabel.setText("Stopped");
    }

    private void updateTransportState(boolean isPlaying) {
        playBtn.setDisable(isPlaying);
        pauseBtn.setDisable(!isPlaying);
    }

    // -----------------------------------------------------------------------
    // Speed controls
    // -----------------------------------------------------------------------

    private void setupSpeedControls() {
        speedDownBtn.setOnAction(e -> changeSpeed(-1));
        speedUpBtn.setOnAction(e   -> changeSpeed(+1));
    }

    private void changeSpeed(int delta) {
        speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, speedIndex + delta));
        updateSpeedLabel();
        if (device != null) device.setSpeed(SPEEDS[speedIndex]);
        speedDownBtn.setDisable(speedIndex == 0);
        speedUpBtn.setDisable(speedIndex == SPEEDS.length - 1);
        if (device != null && device.isPlaying()) {
            statusLabel.setText("Playing  " + SPEED_LABELS[speedIndex]);
        }
    }

    private void updateSpeedLabel() {
        speedLabel.setText(SPEED_LABELS[speedIndex]);
        speedDownBtn.setDisable(speedIndex == 0);
        speedUpBtn.setDisable(speedIndex == SPEEDS.length - 1);
    }

    // -----------------------------------------------------------------------
    // ReplayListener (called on replay thread - must dispatch to FX thread)
    // -----------------------------------------------------------------------

    @Override
    public void onProgress(int sampleIndex, int totalSamples) {
        WfdbReader.WfdbRecord rec = device.getCurrentRecord();
        if (rec == null) return;
        double fraction = totalSamples > 0 ? (double) sampleIndex / totalSamples : 0;
        double elapsed  = rec.header.fs > 0 ? sampleIndex / rec.header.fs : 0;
        double duration = rec.durationSeconds();
        String timeStr  = formatTime(elapsed) + " / " + formatTime(duration);

        Platform.runLater(() -> {
            playbackProgress.setProgress(fraction);
            timeLabel.setText(timeStr);
        });
    }

    @Override
    public void onPlaybackComplete() {
        Platform.runLater(() -> {
            updateTransportState(false);
            statusLabel.setText("Playback complete.");
            playbackProgress.setProgress(1.0);
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String formatTime(double seconds) {
        int s = (int) seconds;
        int m = s / 60;
        int h = m / 60;
        m %= 60;
        s %= 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    /** Wraps a .hea file for display in the record ListView. */
    static final class RecordEntry {
        final File   heaFile;
        final String recordName;
        final String dbPath;

        RecordEntry(File heaFile) {
            this.heaFile    = heaFile;
            this.recordName = heaFile.getName().replace(".hea", "");
            // Show the database subfolder as context
            this.dbPath     = heaFile.getParentFile() != null
                ? heaFile.getParentFile().getName() : "";
        }

        @Override
        public String toString() {
            return dbPath.isEmpty()
                ? recordName
                : "["  + dbPath + "]  " + recordName;
        }
    }
}
