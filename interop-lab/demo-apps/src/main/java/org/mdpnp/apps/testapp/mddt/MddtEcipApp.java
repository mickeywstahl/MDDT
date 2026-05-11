package org.mdpnp.apps.testapp.mddt;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

import org.mdpnp.apps.fxbeans.AlertFx;
import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.subscription.Subscriber;

import ice.InfusionObjectiveDataWriter;
import ice.InfusionProgramDataWriter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

/**
 * Controller for the MDDT ECIP application.
 *
 * <p>Implements the first MDDT attribute cluster: <b>Command and Timing</b>.
 * Specifically:
 * <ul>
 *   <li>Communication response time – time from command sent to acknowledgment
 *       received (rate change, pause, resume)</li>
 *   <li>ECIP command sequence logging</li>
 *   <li>Communication health / heartbeat tracking</li>
 *   <li>Basic error handling observation</li>
 * </ul>
 *
 * <p>All events are recorded in a timestamped log table. Derived latency is
 * shown where a command/response pair can be matched. The log can be exported
 * to CSV for offline analysis.
 */
public class MddtEcipApp implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(MddtEcipApp.class);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // -----------------------------------------------------------------------
    // Injected OpenICE resources
    // -----------------------------------------------------------------------

    private DeviceListModel deviceListModel;
    private NumericFxList numericList;
    private SampleArrayFxList sampleList;
    private AlertFxList alertList;
    private InfusionObjectiveDataWriter infusionObjectiveWriter;
    private InfusionProgramDataWriter infusionProgramWriter;
    private MDSHandler mdsHandler;
    private EventLoop eventLoop;
    private Subscriber subscriber;

    // -----------------------------------------------------------------------
    // FXML controls
    // -----------------------------------------------------------------------

    @FXML private ComboBox<Device> deviceCombo;
    @FXML private Label connectionStatusLabel;

    @FXML private TableView<MddtEvent> eventTable;
    @FXML private TableColumn<MddtEvent, String> colTimestamp;
    @FXML private TableColumn<MddtEvent, String> colEventType;
    @FXML private TableColumn<MddtEvent, String> colMetric;
    @FXML private TableColumn<MddtEvent, String> colValue;
    @FXML private TableColumn<MddtEvent, String> colLatencyMs;
    @FXML private TableColumn<MddtEvent, String> colNotes;

    // Test controls
    @FXML private TextField flowRateField;
    @FXML private Button sendRateChangeBtn;
    @FXML private Button sendPauseBtn;
    @FXML private Button sendResumeBtn;
    @FXML private Button sendHeartbeatBtn;
    @FXML private Button sendOutOfRangeBtn;
    @FXML private Button clearLogBtn;
    @FXML private Button exportCsvBtn;

    // Summary labels
    @FXML private Label totalCommandsLabel;
    @FXML private Label avgLatencyLabel;
    @FXML private Label missedResponsesLabel;
    @FXML private Label alarmCountLabel;

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    private final ObservableList<MddtEvent> events = FXCollections.observableArrayList();

    /** Timestamp of the most recently sent command, for latency pairing. */
    private volatile long lastCommandSentMs = -1;
    /** Type label of the most recently sent command. */
    private volatile String lastCommandType = null;

    private int totalCommands = 0;
    private int missedResponses = 0;
    private int alarmCount = 0;
    private long totalLatencyMs = 0;
    private int latencySamples = 0;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Called by the factory after FXML loading to inject all OpenICE beans.
     */
    public void set(DeviceListModel deviceListModel,
                    NumericFxList numericList,
                    SampleArrayFxList sampleList,
                    AlertFxList alertList,
                    InfusionObjectiveDataWriter infusionObjectiveWriter,
                    InfusionProgramDataWriter infusionProgramWriter,
                    MDSHandler mdsHandler) {
        this.deviceListModel = deviceListModel;
        this.numericList = numericList;
        this.sampleList = sampleList;
        this.alertList = alertList;
        this.infusionObjectiveWriter = infusionObjectiveWriter;
        this.infusionProgramWriter = infusionProgramWriter;
        this.mdsHandler = mdsHandler;
    }

    /**
     * Called by the factory to start DDS listening on the EventLoop thread.
     */
    public void start(EventLoop eventLoop, Subscriber subscriber) {
        this.eventLoop = eventLoop;
        this.subscriber = subscriber;
        attachListeners();
    }

    public void activate() {
        // Called when the tab becomes visible – nothing extra needed yet.
    }

    public void stop() {
        detachListeners();
    }

    public void destroy() {
        // Nothing to destroy beyond stop() for now.
    }

    // -----------------------------------------------------------------------
    // FXML Initialization
    // -----------------------------------------------------------------------

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if(colTimestamp != null) {
            // Wire up the event table columns
            colTimestamp.setCellValueFactory(
                    cd -> new SimpleStringProperty(cd.getValue().timestamp));
            colEventType.setCellValueFactory(
                    cd -> new SimpleStringProperty(cd.getValue().eventType));
            colMetric.setCellValueFactory(
                    cd -> new SimpleStringProperty(cd.getValue().metric));
            colValue.setCellValueFactory(
                    cd -> new SimpleStringProperty(cd.getValue().value));
            colLatencyMs.setCellValueFactory(
                    cd -> new SimpleStringProperty(cd.getValue().latencyMs));
            colNotes.setCellValueFactory(
                    cd -> new SimpleStringProperty(cd.getValue().notes));

            eventTable.setItems(events);
        }

        if(deviceCombo != null) {
            // Device combo: populated once deviceListModel is injected (in start)
            deviceCombo.getSelectionModel().selectedItemProperty().addListener(
                    (obs, oldDev, newDev) -> onDeviceSelected(newDev));
        }

        // Disable test buttons until a device is selected
        setTestButtonsDisabled(true);

        if(sendRateChangeBtn != null) {
            // Button actions
            sendRateChangeBtn.setOnAction(e -> onSendRateChange());
            sendPauseBtn.setOnAction(e -> onSendPause());
            sendResumeBtn.setOnAction(e -> onSendResume());
            sendHeartbeatBtn.setOnAction(e -> onSendHeartbeat());
            sendOutOfRangeBtn.setOnAction(e -> onSendOutOfRange());
            clearLogBtn.setOnAction(e -> onClearLog());
            exportCsvBtn.setOnAction(e -> onExportCsv());
        }
    }

    // -----------------------------------------------------------------------
    // Device selection
    // -----------------------------------------------------------------------

    private void onDeviceSelected(Device device) {
        if (device == null) {
            if(connectionStatusLabel != null) connectionStatusLabel.setText("No device selected");
            setTestButtonsDisabled(true);
            return;
        }
        if(connectionStatusLabel != null) connectionStatusLabel.setText("Connected: " + device.toString());
        setTestButtonsDisabled(false);
        logEvent("DEVICE", "Selected", device.toString(), "", "");
    }

    private void setTestButtonsDisabled(boolean disabled) {
        if(sendRateChangeBtn != null) {
            sendRateChangeBtn.setDisable(disabled);
            sendPauseBtn.setDisable(disabled);
            sendResumeBtn.setDisable(disabled);
            sendHeartbeatBtn.setDisable(disabled);
            sendOutOfRangeBtn.setDisable(disabled);
        }
    }

    // -----------------------------------------------------------------------
    // OpenICE listener attachment
    // -----------------------------------------------------------------------

    /**
     * Attach JavaFX list listeners to the shared numeric and alert lists.
     * These fire on the FX thread (the FxBeans wrappers handle thread dispatch).
     */
    private void attachListeners() {
        if(numericList != null) {
            numericList.addListener(numericChangeListener);
        }
        if(alertList != null) {
            alertList.addListener(alertChangeListener);
        }

        // Populate device combo from deviceListModel
        Platform.runLater(() -> {
            if(deviceCombo != null && deviceListModel != null) {
                deviceCombo.setItems(deviceListModel.getContents());
            }
        });
    }

    private void detachListeners() {
        if(numericList != null) numericList.removeListener(numericChangeListener);
        if(alertList != null) alertList.removeListener(alertChangeListener);
    }

    /**
     * Listens for any new or updated numeric values published by the selected
     * ECIP and records them in the event log.
     */
    private final ListChangeListener<NumericFx> numericChangeListener = change -> {
        Device selected = deviceCombo != null ? deviceCombo.getSelectionModel().getSelectedItem() : null;
        if (selected == null) return;

        while (change.next()) {
            if (change.wasAdded() || change.wasUpdated()) {
                for (NumericFx bean : change.getAddedSubList()) {
                    if (!selected.getUDI().equals(bean.getUnique_device_identifier())) continue;

                    long now = System.currentTimeMillis();
                    String latency = "";

                    // If a command was sent recently, calculate response latency
                    if (lastCommandSentMs > 0 && lastCommandType != null) {
                        long diff = now - lastCommandSentMs;
                        latency = String.valueOf(diff);
                        recordLatency(diff);
                        lastCommandSentMs = -1;
                        lastCommandType = null;
                    }

                    logEvent("NUMERIC",
                            bean.getMetric_id(),
                            String.format("%.4f %s", bean.getValue(), bean.getUnit_id()),
                            latency,
                            "");
                }
            }
        }
    };

    /**
     * Listens for alerts (alarms) published by the selected ECIP.
     */
    private final ListChangeListener<AlertFx> alertChangeListener = change -> {
        Device selected = deviceCombo != null ? deviceCombo.getSelectionModel().getSelectedItem() : null;
        if (selected == null) return;

        while (change.next()) {
            if (change.wasAdded()) {
                for (AlertFx bean : change.getAddedSubList()) {
                    if (!selected.getUDI().equals(bean.getUnique_device_identifier())) continue;
                    alarmCount++;
                    updateSummary();
                    logEvent("ALARM",
                            bean.getIdentifier(),
                            bean.getText(),
                            "",
                            "Alarm received from device");
                }
            }
        }
    };

    // -----------------------------------------------------------------------
    // Manual test actions
    // -----------------------------------------------------------------------

    /**
     * TEST: Send a flow rate change command and record the send timestamp.
     * Latency will be filled in when the next numeric update arrives from
     * this device.
     */
    private void onSendRateChange() {
        Device device = deviceCombo.getSelectionModel().getSelectedItem();
        if (device == null || infusionObjectiveWriter == null) return;

        String rateText = flowRateField.getText().trim();
        float rate;
        try {
            rate = Float.parseFloat(rateText);
        } catch (NumberFormatException ex) {
            logEvent("ERROR", "RATE_CHANGE", "Invalid input: " + rateText, "", "Parse error – enter a numeric value");
            return;
        }

        try {
            ice.InfusionProgram obj = new ice.InfusionProgram();
            obj.unique_device_identifier = device.getUDI();
            obj.infusionRate = rate;
            infusionProgramWriter.write(obj, com.rti.dds.infrastructure.InstanceHandle_t.HANDLE_NIL);

            recordCommandSent("RATE_CHANGE");
            totalCommands++;
            updateSummary();
            logEvent("CMD_SENT", "RATE_CHANGE",
                    String.format("%.4f mL/hr", rate), "", "Awaiting numeric acknowledgment");
        } catch (Exception ex) {
            log.error("Failed to send rate change", ex);
            logEvent("ERROR", "RATE_CHANGE", ex.getMessage(), "", "Write failed");
        }
    }

    /**
     * TEST: Send a pause (stop) infusion objective.
     */
    private void onSendPause() {
        Device device = deviceCombo.getSelectionModel().getSelectedItem();
        if (device == null || infusionObjectiveWriter == null) return;

        try {
            ice.InfusionObjective obj = new ice.InfusionObjective();
            obj.unique_device_identifier = device.getUDI();
            obj.stopInfusion = true;
            infusionObjectiveWriter.write(obj, com.rti.dds.infrastructure.InstanceHandle_t.HANDLE_NIL);

            recordCommandSent("PAUSE");
            totalCommands++;
            updateSummary();
            logEvent("CMD_SENT", "PAUSE", "stopInfusion=true", "", "Awaiting acknowledgment");
        } catch (Exception ex) {
            log.error("Failed to send pause", ex);
            logEvent("ERROR", "PAUSE", ex.getMessage(), "", "Write failed");
        }
    }

    /**
     * TEST: Send a resume infusion objective (using last entered flow rate).
     */
    private void onSendResume() {
        Device device = deviceCombo.getSelectionModel().getSelectedItem();
        if (device == null || infusionObjectiveWriter == null) return;

        String rateText = flowRateField.getText().trim();
        float rate;
        try {
            rate = Float.parseFloat(rateText);
            if (rate <= 0) throw new NumberFormatException("Rate must be > 0 to resume");
        } catch (NumberFormatException ex) {
            logEvent("ERROR", "RESUME", ex.getMessage(), "", "Set a positive flow rate first");
            return;
        }

        try {
            ice.InfusionObjective obj = new ice.InfusionObjective();
            obj.unique_device_identifier = device.getUDI();
            obj.stopInfusion = false;
            infusionObjectiveWriter.write(obj, com.rti.dds.infrastructure.InstanceHandle_t.HANDLE_NIL);

            recordCommandSent("RESUME");
            totalCommands++;
            updateSummary();
            logEvent("CMD_SENT", "RESUME", "stopInfusion=false", "", "Awaiting acknowledgment");
        } catch (Exception ex) {
            log.error("Failed to send resume", ex);
            logEvent("ERROR", "RESUME", ex.getMessage(), "", "Write failed");
        }
    }

    /**
     * TEST: Log a manual heartbeat/keep-alive probe entry.
     * In a future iteration this will send an actual keep-alive and measure
     * round-trip time. For now it logs the probe timestamp so the operator
     * can verify device responsiveness manually.
     */
    private void onSendHeartbeat() {
        recordCommandSent("HEARTBEAT");
        totalCommands++;
        updateSummary();
        logEvent("CMD_SENT", "HEARTBEAT", "keep-alive probe", "", "Manual heartbeat probe");
    }

    /**
     * TEST: Send an out-of-range flow rate to evaluate ECIP error handling.
     * The value 99999.0 is intentionally beyond any physical pump's hard limit.
     */
    private void onSendOutOfRange() {
        Device device = deviceCombo.getSelectionModel().getSelectedItem();
        if (device == null || infusionProgramWriter == null) return;

        try {
            ice.InfusionProgram obj = new ice.InfusionProgram();
            obj.unique_device_identifier = device.getUDI();
            obj.infusionRate = 99999.0f;
            if(infusionProgramWriter != null) {
                infusionProgramWriter.write(obj, com.rti.dds.infrastructure.InstanceHandle_t.HANDLE_NIL);
            }

            recordCommandSent("OUT_OF_RANGE");
            totalCommands++;
            updateSummary();
            logEvent("CMD_SENT", "OUT_OF_RANGE", "flow_rate=99999.0 (intentional)", "", "Error handling test – expect rejection or alarm");
        } catch (Exception ex) {
            log.error("Failed to send out-of-range command", ex);
            logEvent("ERROR", "OUT_OF_RANGE", ex.getMessage(), "", "Write failed");
        }
    }

    // -----------------------------------------------------------------------
    // Log management
    // -----------------------------------------------------------------------

    private void onClearLog() {
        events.clear();
        totalCommands = 0;
        missedResponses = 0;
        alarmCount = 0;
        totalLatencyMs = 0;
        latencySamples = 0;
        lastCommandSentMs = -1;
        lastCommandType = null;
        updateSummary();
    }

    private void onExportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export MDDT Event Log");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        chooser.setInitialFileName("mddt_ecip_log_" +
                Instant.now().toString().replace(":", "-").replace(".", "-") + ".csv");

        java.io.File file = chooser.showSaveDialog(eventTable.getScene().getWindow());
        if (file == null) return;

        try (FileWriter fw = new FileWriter(file)) {
            fw.write("Timestamp,EventType,Metric,Value,LatencyMs,Notes\n");
            for (MddtEvent e : events) {
                fw.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                        e.timestamp, e.eventType, e.metric,
                        e.value, e.latencyMs, e.notes));
            }
            logEvent("EXPORT", "CSV", file.getName(), "", "Log exported successfully");
        } catch (IOException ex) {
            log.error("CSV export failed", ex);
            logEvent("ERROR", "EXPORT", ex.getMessage(), "", "Export failed");
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void recordCommandSent(String commandType) {
        lastCommandSentMs = System.currentTimeMillis();
        lastCommandType = commandType;
    }

    private void recordLatency(long latencyMs) {
        totalLatencyMs += latencyMs;
        latencySamples++;
    }

    /**
     * Add a row to the event log. Safe to call from any thread.
     */
    private void logEvent(String eventType, String metric, String value,
                          String latencyMs, String notes) {
        String ts = TIME_FMT.format(Instant.now());
        MddtEvent event = new MddtEvent(ts, eventType, metric, value, latencyMs, notes);
        Platform.runLater(() -> {
            events.add(event);
            if(eventTable != null) {
                // Auto-scroll to bottom
                eventTable.scrollTo(events.size() - 1);
            }
        });
        log.info("[MDDT] {} | {} | {} | {} | latency={}ms | {}",
                ts, eventType, metric, value, latencyMs, notes);
    }

    private void updateSummary() {
        Platform.runLater(() -> {
            if(totalCommandsLabel != null) totalCommandsLabel.setText(String.valueOf(totalCommands));
            if(missedResponsesLabel != null) missedResponsesLabel.setText(String.valueOf(missedResponses));
            if(alarmCountLabel != null) alarmCountLabel.setText(String.valueOf(alarmCount));
            if(avgLatencyLabel != null) {
                if (latencySamples > 0) {
                    avgLatencyLabel.setText(
                            String.format("%.1f ms", (double) totalLatencyMs / latencySamples));
                } else {
                    avgLatencyLabel.setText("—");
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Inner class: Event log row model
    // -----------------------------------------------------------------------

    /**
     * A single row in the MDDT event log table.
     */
    public static class MddtEvent {
        public final String timestamp;
        public final String eventType;
        public final String metric;
        public final String value;
        public final String latencyMs;
        public final String notes;

        public MddtEvent(String timestamp, String eventType, String metric,
                         String value, String latencyMs, String notes) {
            this.timestamp = timestamp;
            this.eventType = eventType;
            this.metric = metric;
            this.value = value;
            this.latencyMs = latencyMs;
            this.notes = notes;
        }
    }
}
