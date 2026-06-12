package org.mdpnp.apps.testapp.mddt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mdpnp.apps.fxbeans.AlertFx;
import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.mdpnp.rtiapi.data.EventLoop.ConditionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.infrastructure.Condition;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.subscription.DataReader;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.ReadCondition;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.Topic;

import ice.TrialMarker;
import ice.TrialMarkerDataReader;
import ice.TrialMarkerSeq;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

/**
 * MDDT DDS Data Logger Application.
 *
 * A purely passive observer of the OpenICE DDS bus. Subscribes to all topics
 * relevant to the Q-Submission Table 1 ECIP hazard attribute themes and logs
 * every event to a raw CSV file for offline statistical analysis.
 *
 * This app is deliberately decoupled from PumpActuatorApp and from the specific
 * ECIP being tested. It works identically whether the publisher is:
 *   - RealisticSimPump (during MDDT tool development and validation)
 *   - A real ECIP such as BD Alaris (during actual MDDT qualification runs)
 *   - The ACLIVA application or any other OpenICE participant
 *
 * === Q-Submission Table 1 coverage ===
 *
 * Attribute Theme          | What this logger records
 * -------------------------|------------------------------------------------------
 * Pump command handling    | TrialMarker CMD_SENT events + subsequent Numeric changes
 * Latency                  | Wall-clock delta: CMD_SENT marker -> observed rate change
 * Safety interlocks        | InfusionObjective writes + resulting infusion state
 * Communication integrity  | Dropped responses (CMD_SENT with no subsequent change)
 * Alarms                   | Alert topic publications (occlusion, low vol, line clamp)
 * Interoperability         | Device identity publications (manufacturer, model, UDI)
 * Data transmission        | Numeric publication timestamps, inter-sample intervals
 * Connectivity             | Heartbeat sequence numbers, gaps, intervals
 * Power status             | BATTERY_LEVEL numeric publications
 *
 * === CSV schema ===
 *
 * Every row represents one discrete event. Columns:
 *
 *   log_timestamp_ms   Wall-clock time this row was written (ms since epoch)
 *   log_timestamp_iso  Human-readable ISO-8601 timestamp
 *   run_id             Run ID from PumpActuatorApp (0 if no actuator present)
 *   trial_num          Trial number within run (0 if not in a trial)
 *   event_category     Top-level category (NUMERIC, ALARM, TRIAL_MARKER, HEARTBEAT,
 *                        DEVICE_IDENTITY, LATENCY, HEARTBEAT_GAP, CMD_TIMEOUT)
 *   event_type         Specific event (RATE_CHANGE, PAUSE, OCCLUSION, etc.)
 *   device_udi         UDI of the publishing device
 *   metric_id          Metric/topic identifier
 *   commanded_value    Value that was commanded (from TrialMarker; blank if N/A)
 *   observed_value     Value observed on DDS (blank if N/A)
 *   latency_ms         Command-to-observation latency (blank if not yet resolved)
 *   inter_sample_ms    Time since previous sample for this metric (blank if first)
 *   notes              Free-form details (alarm text, error description, etc.)
 *
 * === Offline analysis ===
 *
 * The raw CSV contains one row per event. The Evidence Report Generator
 * (a separate app) reads this file and computes per-parameter statistics:
 * mean, variance, min, max, percentiles, and pass/fail against acceptance
 * criteria defined in the MDDT qualification package.
 *
 * @see PumpActuatorApp  (the stimulus generator this logger is designed to work with)
 * @see RealisticSimPump (the simulated ECIP)
 */
public class DDSDataLoggerApp {

    private static final Logger log = LoggerFactory.getLogger(DDSDataLoggerApp.class);

    // Metric IDs matching RealisticSimPump and real pump adapters
    private static final String METRIC_FLOW_RATE   = rosetta.MDC_FLOW_FLUID_PUMP.VALUE;
    private static final String METRIC_VOL_INFUSED = "VOLUME_INFUSED";
    private static final String METRIC_VTBI        = "VTBI";
    private static final String METRIC_BATTERY     = "BATTERY_LEVEL";
    private static final String METRIC_HEARTBEAT   = "HEARTBEAT";

    // Timeout after which a CMD_SENT with no observed response is logged as dropped
    private static final long CMD_TIMEOUT_MS = 10_000;

    // -------------------------------------------------------------------------
    // FXML UI
    // -------------------------------------------------------------------------
    @FXML TextArea eventDisplay;
    @FXML Label logFileLabel;
    @FXML Label statusLabel;
    @FXML Label eventCountLabel;
    @FXML TextField deviceFilterField;
    @FXML Button startLoggingButton;
    @FXML Button stopLoggingButton;
    @FXML Button flushButton;

    // -------------------------------------------------------------------------
    // OpenICE references (injected by factory)
    // -------------------------------------------------------------------------
    private NumericFxList numericList;
    private AlertFxList alertList;
    private DeviceListModel deviceListModel;

    // TrialMarker subscription (direct DDS, not FX bean — no UI binding needed)
    private TrialMarkerDataReader trialMarkerReader;
    private Topic trialMarkerTopic;

    // -------------------------------------------------------------------------
    // Logging state
    // -------------------------------------------------------------------------
    private PrintWriter csvWriter;
    private File csvFile;
    private volatile boolean loggingActive = false;
    private long totalEventsLogged = 0;

    // Timestamp format for ISO column
    private static final SimpleDateFormat ISO_FMT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");

    // -------------------------------------------------------------------------
    // Per-metric state (for inter-sample interval and latency computation)
    // -------------------------------------------------------------------------

    /**
     * Most recent publication timestamp (ms) per device+metric, used to
     * compute inter-sample intervals (data transmission regularity).
     * Key: udi + "|" + metric_id
     */
    private final Map<String, Long> lastSampleTimeMs = new ConcurrentHashMap<>();

    /**
     * Most recent observed value per device+metric.
     * Used to detect state changes corresponding to commands.
     */
    private final Map<String, Float> lastObservedValue = new ConcurrentHashMap<>();

    /**
     * Pending commands: maps TrialMarker sequence key to PendingCmd record.
     * Key: runId + "_" + trialNum + "_" + cmdType
     * A pending command is resolved when the observed value on DDS matches
     * the commanded value; it times out after CMD_TIMEOUT_MS.
     */
    private final Map<String, PendingCmd> pendingCmds = new ConcurrentHashMap<>();

    /** Current run ID and trial number from the most recent TrialMarker. */
    private volatile long currentRunId  = 0;
    private volatile int  currentTrial  = 0;

    /** Last heartbeat sequence seen per device UDI. */
    private final Map<String, Long> lastHeartbeatSeq = new ConcurrentHashMap<>();
    /** Last heartbeat arrival time per device UDI (for interval measurement). */
    private final Map<String, Long> lastHeartbeatTimeMs = new ConcurrentHashMap<>();

    // =========================================================================
    // Dependency injection
    // =========================================================================

    public void set(ApplicationContext ctx,
                    DeviceListModel deviceListModel,
                    NumericFxList numericList,
                    AlertFxList alertList,
                    TrialMarkerDataReader trialMarkerReader) {
        this.deviceListModel  = deviceListModel;
        this.numericList      = numericList;
        this.alertList        = alertList;
        this.trialMarkerReader = trialMarkerReader;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void start(EventLoop eventLoop, Subscriber subscriber) {
        attachNumericListeners();
        attachAlertListeners();
        subscribeToTrialMarkers(eventLoop, subscriber);

        updateStatus("Idle — press Start Logging to begin recording.");
        if (startLoggingButton != null) startLoggingButton.setDisable(false);
        if (stopLoggingButton  != null) stopLoggingButton.setDisable(true);
    }

    public void stop()    { stopLogging(); }
    public void destroy() { stopLogging(); }
    public void activate() {}

    // =========================================================================
    // Logging control (FXML button handlers)
    // =========================================================================

    @FXML
    public void startLogging() {
        if (loggingActive) return;
        openCsvFile();
        loggingActive = true;
        updateStatus("Logging active → " + (csvFile != null ? csvFile.getName() : "?"));
        Platform.runLater(() -> {
            if (startLoggingButton != null) startLoggingButton.setDisable(true);
            if (stopLoggingButton  != null) stopLoggingButton.setDisable(false);
        });
        appendDisplay("[LOGGER] Logging started.");
    }

    @FXML
    public void stopLogging() {
        if (!loggingActive) return;
        loggingActive = false;
        closeCsvFile();
        updateStatus("Logging stopped. " + totalEventsLogged + " events written.");
        Platform.runLater(() -> {
            if (startLoggingButton != null) startLoggingButton.setDisable(false);
            if (stopLoggingButton  != null) stopLoggingButton.setDisable(true);
        });
        appendDisplay("[LOGGER] Logging stopped. " + totalEventsLogged + " events written.");
    }

    @FXML
    public void flushLog() {
        if (csvWriter != null) { synchronized (csvWriter) { csvWriter.flush(); } }
        appendDisplay("[LOGGER] Log flushed.");
    }

    // =========================================================================
    // Numeric topic listener
    // Covers: pump command handling, latency, data transmission, connectivity,
    //         power status (battery), heartbeat
    // =========================================================================

    private void attachNumericListeners() {
        numericList.addListener((ListChangeListener<NumericFx>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(this::hookNumeric);
            }
        });
        numericList.forEach(this::hookNumeric);
    }

    private void hookNumeric(NumericFx n) {
        n.presentation_timeProperty().addListener((obs, oldVal, newVal) -> {
            if (!loggingActive) return;
            onNumericUpdate(n);
        });
    }

    private void onNumericUpdate(NumericFx n) {
        String udi    = n.getUnique_device_identifier();
        String metric = n.getMetric_id();
        float  value  = n.getValue();
        long   nowMs  = System.currentTimeMillis();
        String key    = udi + "|" + metric;

        // --- Inter-sample interval (data transmission regularity) ---
        Long prevTimeMs = lastSampleTimeMs.put(key, nowMs);
        long interSampleMs = (prevTimeMs != null) ? (nowMs - prevTimeMs) : -1;

        // --- Heartbeat gap and interval detection ---
        if (METRIC_HEARTBEAT.equals(metric)) {
            processHeartbeat(udi, (long) value, nowMs, interSampleMs);
            return; // heartbeat has its own row format
        }

        // --- Latency resolution: check if this update resolves a pending command ---
        float  prevValue = lastObservedValue.getOrDefault(key, Float.NaN);
        String latencyMs = "";
        String resolvedCmdType = "";

        if (value != prevValue) {
            // Value changed — attempt to match against a pending command
            PendingCmd resolved = resolvePendingCommand(udi, metric, value, nowMs);
            if (resolved != null) {
                latencyMs = String.valueOf(nowMs - resolved.sentTimeMs);
                resolvedCmdType = resolved.cmdType;
                // Write a dedicated LATENCY row for easy filtering
                writeRow(nowMs,
                    currentRunId, resolved.trialNum,
                    "LATENCY", resolved.cmdType,
                    udi, metric,
                    String.valueOf(resolved.commandedValue),
                    String.valueOf(value),
                    latencyMs,
                    String.valueOf(interSampleMs),
                    "runId=" + resolved.runId + " trial=" + resolved.trialNum);
            }
        }
        lastObservedValue.put(key, value);

        // --- Main numeric row ---
        writeRow(nowMs,
            currentRunId, currentTrial,
            "NUMERIC", metric,
            udi, metric,
            "",                        // commanded value — filled in LATENCY row
            String.valueOf(value),
            latencyMs,                 // blank unless this sample resolved a command
            interSampleMs >= 0 ? String.valueOf(interSampleMs) : "",
            resolvedCmdType.isEmpty() ? "" : "resolves=" + resolvedCmdType);
    }

    // =========================================================================
    // Heartbeat processing
    // Covers: connectivity, ECIP communication health
    // =========================================================================

    private void processHeartbeat(String udi, long seq, long nowMs, long interSampleMs) {
        Long prevSeq     = lastHeartbeatSeq.put(udi, seq);
        Long prevTimeMs  = lastHeartbeatTimeMs.put(udi, nowMs);
        long intervalMs  = (prevTimeMs != null) ? (nowMs - prevTimeMs) : -1;

        String gapNotes = "";
        String category = "HEARTBEAT";

        if (prevSeq != null && seq != prevSeq + 1) {
            long gap = seq - prevSeq - 1;
            gapNotes = "GAP expected=" + (prevSeq + 1) + " got=" + seq + " missed=" + gap;
            category = "HEARTBEAT_GAP";
            appendDisplay("[HEARTBEAT_GAP] udi=" + udi + " " + gapNotes);
        }

        writeRow(nowMs,
            currentRunId, currentTrial,
            category, "HEARTBEAT",
            udi, METRIC_HEARTBEAT,
            "", String.valueOf(seq),
            "",
            intervalMs >= 0 ? String.valueOf(intervalMs) : "",
            gapNotes);
    }

    // =========================================================================
    // Alert topic listener
    // Covers: alarm communication (occlusion, low volume, line clamp)
    // =========================================================================

    private void attachAlertListeners() {
        alertList.addListener((ListChangeListener<AlertFx>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(this::hookAlert);
            }
        });
        alertList.forEach(this::hookAlert);
    }

    private void hookAlert(AlertFx a) {
        a.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!loggingActive) return;
            onAlertUpdate(a, newVal);
        });
    }

    private void onAlertUpdate(AlertFx a, String text) {
        long nowMs = System.currentTimeMillis();
        String udi = a.getUnique_device_identifier();
        String id  = a.getIdentifier();

        // Classify alarm type for the event_type column
        String alarmType = classifyAlarm(id);

        writeRow(nowMs,
            currentRunId, currentTrial,
            "ALARM", alarmType,
            udi, id,
            "", text,
            "", "",
            "identifier=" + id);

        appendDisplay("[ALARM] " + alarmType + " udi=" + abbrev(udi) + " text=" + text);
    }

    private String classifyAlarm(String identifier) {
        if (identifier == null) return "UNKNOWN_ALARM";
        String id = identifier.toUpperCase();
        if (id.contains("OCCLUSION"))   return "OCCLUSION_ALARM";
        if (id.contains("LOW_VOLUME") || id.contains("LOWVOLUME")) return "LOW_VOLUME_ALARM";
        if (id.contains("CLAMP") || id.contains("LINE_CLAMP"))    return "LINE_CLAMP_ALARM";
        if (id.contains("BATTERY"))     return "BATTERY_ALARM";
        if (id.contains("CONNECT"))     return "CONNECTIVITY_ALARM";
        return "ALARM_" + id;
    }

    // =========================================================================
    // TrialMarker topic subscription
    // =========================================================================

    /**
     * Subscribe to the TrialMarker topic published by PumpActuatorApp.
     * Each CMD_SENT marker creates a PendingCmd record that will be resolved
     * when the corresponding numeric state change is observed.
     *
     * This is the mechanism by which the logger computes command-response
     * latency without any direct coupling to the actuator app.
     */
    private void subscribeToTrialMarkers(EventLoop eventLoop, Subscriber subscriber) {
        ice.TrialMarkerTypeSupport.register_type(subscriber.get_participant(), ice.TrialMarkerTypeSupport.get_type_name());
        trialMarkerTopic = TopicUtil.findOrCreateTopic(subscriber.get_participant(), ice.TrialMarkerTopic.VALUE, ice.TrialMarkerTypeSupport.class);
        trialMarkerReader = (TrialMarkerDataReader) subscriber.create_datareader_with_profile(trialMarkerTopic, QosProfiles.ice_library,
                QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);

        ReadCondition rc = trialMarkerReader.create_readcondition(
            SampleStateKind.NOT_READ_SAMPLE_STATE,
            ViewStateKind.ANY_VIEW_STATE,
            InstanceStateKind.ALIVE_INSTANCE_STATE);

        // The TrialMarkerDataReader is currently a stub whose create_readcondition()
        // returns null. Guard against this so we do not crash the EventLoop by
        // passing a null condition to WaitSet.attach_condition(). Once the
        // TrialMarker IDL is defined and the real DataReader is generated,
        // this guard becomes unreachable and can be removed.
        if (rc == null) {
            log.warn("TrialMarkerDataReader.create_readcondition() returned null " +
                     "(stub phase) — latency computation disabled. EventLoop not affected.");
            appendDisplay("[LOGGER] TrialMarker ReadCondition is null (stub) — " +
                          "latency computation disabled.");
            return;
        }

        eventLoop.addHandler(rc, new ConditionHandler() {
            private final TrialMarkerSeq data_seq = new TrialMarkerSeq();
            private final SampleInfoSeq  info_seq = new SampleInfoSeq();

            @Override
            public void conditionChanged(Condition condition) {
                for (;;) {
                    try {
                        trialMarkerReader.read_w_condition(data_seq, info_seq,
                            ResourceLimitsQosPolicy.LENGTH_UNLIMITED, (ReadCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo si = (SampleInfo) info_seq.get(i);
                            if (si.valid_data) {
                                onTrialMarker((TrialMarker) data_seq.get(i));
                            }
                        }
                    } catch (RETCODE_NO_DATA nd) {
                        break;
                    } finally {
                        trialMarkerReader.return_loan(data_seq, info_seq);
                    }
                }
            }
        });
    }

    private void onTrialMarker(TrialMarker marker) {
        if (!loggingActive) return;
        long nowMs = System.currentTimeMillis();

        currentRunId = marker.runId;
        currentTrial = marker.trialNum;

        // Log the marker itself
        writeRow(nowMs,
            marker.runId, marker.trialNum,
            "TRIAL_MARKER", marker.eventType,
            "", marker.cmdType,
            String.valueOf(marker.value), "",
            "", "",
            "params=" + marker.params + " actuatorTs=" + marker.timestampMs);

        // For CMD_SENT markers, register a pending command for latency tracking
        if ("CMD_SENT".equals(marker.eventType)) {
            String pendingKey = marker.runId + "_" + marker.trialNum + "_" + marker.cmdType;
            pendingCmds.put(pendingKey,
                new PendingCmd(marker.runId, marker.trialNum, marker.cmdType,
                               marker.value, nowMs));
        }

        String shortType = marker.eventType;
        if ("CMD_SENT".equals(shortType)) {
            appendDisplay(String.format("[TRIAL] run=%d trial=%d %s value=%.2f",
                marker.runId, marker.trialNum, marker.cmdType, marker.value));
        } else if ("RUN_START".equals(shortType) || "RUN_COMPLETE".equals(shortType)) {
            appendDisplay("[" + shortType + "] runId=" + marker.runId);
        }
    }

    // =========================================================================
    // Pending command resolution and timeout sweep
    // =========================================================================

    /**
     * Try to resolve a pending command against an observed value.
     * A rate-change command is resolved when the observed flow rate is within
     * 0.01 mL/hr of the commanded rate.
     * Pause is resolved when rate drops to 0. Resume is resolved when rate > 0.
     */
    private PendingCmd resolvePendingCommand(String udi, String metric, float observed, long nowMs) {
        if (!METRIC_FLOW_RATE.equals(metric)) return null; // only rate changes tracked this way

        for (Map.Entry<String, PendingCmd> entry : pendingCmds.entrySet()) {
            PendingCmd cmd = entry.getValue();
            boolean resolved = false;

            switch (cmd.cmdType) {
                case "RATE_CHANGE":
                    resolved = Math.abs(observed - cmd.commandedValue) < 0.01f;
                    break;
                case "PAUSE":
                    resolved = observed < 0.01f;
                    break;
                case "RESUME":
                    resolved = observed > 0.01f;
                    break;
                default:
                    // VTBI_SET, BOLUS etc. are not directly observable via flow rate
                    break;
            }

            if (resolved) {
                pendingCmds.remove(entry.getKey());
                return cmd;
            }
        }
        return null;
    }

    /**
     * Periodically sweep pending commands for timeouts.
     * Any command not resolved within CMD_TIMEOUT_MS is logged as dropped.
     * This detects the "dropped response" fault mode from pump-sim.properties.
     */
    private void scheduleCommandTimeoutSweep() {
        // Run sweep every 2 seconds on the JavaFX thread (safe for ConcurrentHashMap)
        javafx.animation.Timeline sweeper = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(2),
                e -> sweepTimedOutCommands()
            )
        );
        sweeper.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sweeper.play();
    }

    private void sweepTimedOutCommands() {
        if (!loggingActive) return;
        long nowMs = System.currentTimeMillis();
        pendingCmds.entrySet().removeIf(entry -> {
            PendingCmd cmd = entry.getValue();
            if (nowMs - cmd.sentTimeMs > CMD_TIMEOUT_MS) {
                long timeoutMs = nowMs - cmd.sentTimeMs;
                writeRow(nowMs,
                    cmd.runId, cmd.trialNum,
                    "CMD_TIMEOUT", cmd.cmdType,
                    "", "",
                    String.valueOf(cmd.commandedValue), "",
                    String.valueOf(timeoutMs), "",
                    "no_response_after=" + timeoutMs + "ms");
                appendDisplay(String.format("[CMD_TIMEOUT] run=%d trial=%d %s after %dms",
                    cmd.runId, cmd.trialNum, cmd.cmdType, timeoutMs));
                return true;
            }
            return false;
        });
    }

    // =========================================================================
    // CSV file management
    // =========================================================================

    private static final String CSV_HEADER =
        "log_timestamp_ms,log_timestamp_iso,run_id,trial_num," +
        "event_category,event_type,device_udi,metric_id," +
        "commanded_value,observed_value,latency_ms,inter_sample_ms,notes";

    private void openCsvFile() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            csvFile = new File("mddt_dds_log_" + ts + ".csv");
            csvWriter = new PrintWriter(new BufferedWriter(new FileWriter(csvFile)));
            csvWriter.println(CSV_HEADER);
            totalEventsLogged = 0;
            log.info("MDDT DDS log opened: {}", csvFile.getAbsolutePath());
            Platform.runLater(() -> {
                if (logFileLabel != null) logFileLabel.setText(csvFile.getAbsolutePath());
            });
        } catch (IOException ioe) {
            log.error("Failed to open CSV log file", ioe);
            appendDisplay("[ERROR] Could not open CSV log: " + ioe.getMessage());
        }
    }

    /**
     * Write one row to the CSV. Thread-safe via synchronization on csvWriter.
     * All string fields are quoted to handle commas in metric IDs or notes.
     */
    private void writeRow(long timestampMs,
                          long runId, int trialNum,
                          String eventCategory, String eventType,
                          String deviceUdi, String metricId,
                          String commandedValue, String observedValue,
                          String latencyMs, String interSampleMs,
                          String notes) {
        if (!loggingActive || csvWriter == null) return;

        String isoTs = ISO_FMT.format(new Date(timestampMs));

        synchronized (csvWriter) {
            csvWriter.printf("%d,%s,%d,%d,%s,%s,\"%s\",\"%s\",%s,%s,%s,%s,\"%s\"%n",
                timestampMs,
                isoTs,
                runId,
                trialNum,
                eventCategory,
                eventType,
                esc(deviceUdi),
                esc(metricId),
                commandedValue,
                observedValue,
                latencyMs,
                interSampleMs,
                esc(notes));
            totalEventsLogged++;
        }

        Platform.runLater(() -> {
            if (eventCountLabel != null)
                eventCountLabel.setText(String.valueOf(totalEventsLogged));
        });
    }

    private void closeCsvFile() {
        if (csvWriter != null) {
            synchronized (csvWriter) {
                csvWriter.flush();
                csvWriter.close();
            }
            log.info("MDDT DDS log closed: {} ({} events)",
                csvFile != null ? csvFile.getAbsolutePath() : "?", totalEventsLogged);
        }
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private void appendDisplay(String text) {
        Platform.runLater(() -> {
            if (eventDisplay != null) {
                eventDisplay.appendText(ISO_FMT.format(new Date()) + "  " + text + "\n");
            }
        });
    }

    private void updateStatus(String text) {
        Platform.runLater(() -> {
            if (statusLabel != null) statusLabel.setText(text);
        });
    }

    /** Abbreviate a UDI for display (last 8 chars). */
    private static String abbrev(String udi) {
        if (udi == null || udi.length() <= 8) return udi;
        return "..." + udi.substring(udi.length() - 8);
    }

    /** Escape double-quotes in CSV string fields. */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "\"\"");
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    /**
     * Record of a command sent by the actuator, waiting for an observed
     * response on the DDS bus.
     */
    private static class PendingCmd {
        final long   runId;
        final int    trialNum;
        final String cmdType;
        final float  commandedValue;
        final long   sentTimeMs;

        PendingCmd(long runId, int trialNum, String cmdType,
                   float commandedValue, long sentTimeMs) {
            this.runId          = runId;
            this.trialNum       = trialNum;
            this.cmdType        = cmdType;
            this.commandedValue = commandedValue;
            this.sentTimeMs     = sentTimeMs;
        }
    }
}
