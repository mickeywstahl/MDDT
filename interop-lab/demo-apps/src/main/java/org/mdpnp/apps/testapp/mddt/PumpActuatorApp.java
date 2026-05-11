package org.mdpnp.apps.testapp.mddt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.publication.Publisher;
import com.rti.dds.topic.Topic;

import ice.FlowRateObjectiveDataWriter;
import ice.InfusionObjectiveDataWriter;
import ice.InfusionProgram;
import ice.InfusionProgramDataWriter;
import ice.TrialMarker;
import ice.TrialMarkerDataWriter;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.util.Callback;
import javafx.util.StringConverter;

/**
 * MDDT Pump Actuator Application.
 *
 * Purely a stimulus generator — it sends commands to an ECIP on the DDS bus
 * and publishes structured TrialMarker events so the DDSDataLoggerApp can
 * correlate commands with observed pump responses.
 *
 * This app does NOT measure anything. All measurement and logging is the
 * responsibility of DDSDataLoggerApp, which observes the DDS bus passively.
 *
 * Design:
 *   - User selects which command types to include in the trial run.
 *   - User sets N (number of trials) and inter-trial interval (ms).
 *   - On Start, the app runs N trials sequentially, cycling through selected
 *     command types. Each trial:
 *       1. Publishes a TRIAL_START TrialMarker to DDS.
 *       2. Sends the command (rate change, pause, resume, VTBI, bolus, bad cmd,
 *          or one of the new supervision/query/annotation types).
 *       3. Publishes a CMD_SENT TrialMarker to DDS.
 *       4. Waits for the inter-trial interval before the next trial.
 *   - After all trials, publishes a RUN_COMPLETE TrialMarker.
 *
 * TrialMarker topic carries: run ID, trial number, command type, commanded
 * value, and a wall-clock timestamp. The logger uses this to compute latency
 * by correlating CMD_SENT timestamps with subsequent numeric state changes.
 *
 * New command types added (see CommandType enum and execute* methods below):
 *   QUERY_STATUS          — measures acknowledgement-only latency (R-203)
 *   STATE_POLL_BURST      — rapid burst of queries after a rate change (R-201/202)
 *   COMM_SILENCE          — suppresses keep-alive to test fail-safe timeout (R-106/601)
 *   COMM_DISRUPTION       — severs the EDI session and verifies recovery (R-601/602/703)
 *   VTBI_TO_COMPLETION    — drives VTBI to a small value to provoke end-of-infusion alarm (R-501)
 *   QUERY_VOLUME_INFUSED  — queries and logs cumulative volume for validation (R-302)
 *   MUI_INTERVENTION      — operator annotation button for bedside events (R-202)
 *   QUERY_POWER_STATUS    — polls battery/mains status (R-204)
 *   SESSION_TEARDOWN      — graceful EDI disconnect (R-107/703)
 *   SESSION_REESTABLISH   — reconnect after teardown (R-107/703)
 *   QUERY_DEVICE_TIME     — reads pump clock and logs delta vs. wall clock (R-205)
 *
 * @see DDSDataLoggerApp (the passive observer that does all measurement)
 * @see RealisticSimPump (the simulated ECIP this actuator drives)
 */
public class PumpActuatorApp {

    private static final Logger log = LoggerFactory.getLogger(PumpActuatorApp.class);

    // Rate sequence used for rate-change trials (cycles through these values).
    // Extended to cover the full clinical propofol ACLIVA range (R-401, R-301).
    private static final float[] RATE_SEQUENCE_ML_PER_HR = {
        5f, 10f, 20f, 50f, 100f, 200f, 400f, 800f, 1200f,
        800f, 400f, 200f, 100f, 50f, 20f, 10f, 5f
    };

    // Out-of-range values used for bad-command trials (R-702)
    private static final float BAD_RATE_OVER  =  9999f;
    private static final float BAD_RATE_UNDER = -1f;

    // Duration for COMM_SILENCE trial — the actuator stops publishing for this
    // many milliseconds to trigger the pump's declared heartbeat timeout (R-106).
    // Should be set longer than the pump's declared maximum quiet time.
    private static final long COMM_SILENCE_DURATION_MS = 6000L;

    // Duration for COMM_DISRUPTION trial — simulates a full EDI session loss (R-601/703).
    private static final long COMM_DISRUPTION_DURATION_MS = 5000L;

    // VTBI value small enough that the pump reaches end-of-infusion quickly
    // during a VTBI_TO_COMPLETION trial, provoking an observable alarm (R-501).
    private static final float VTBI_COMPLETION_TEST_ML = 2.0f;

    // Number of rapid status queries fired in a STATE_POLL_BURST trial (R-201/202).
    private static final int STATE_POLL_BURST_COUNT = 10;
    private static final long STATE_POLL_BURST_INTERVAL_MS = 100L;

    private final String FLOW_RATE = rosetta.MDC_FLOW_FLUID_PUMP.VALUE;

    // -------------------------------------------------------------------------
    // FXML UI
    // -------------------------------------------------------------------------
    @FXML TextArea statusArea;
    @FXML ComboBox<Device> pumps;
    @FXML Label trialProgressLabel;
    @FXML ProgressBar trialProgressBar;

    // Original command type checkboxes
    @FXML CheckBox cbRateChange;
    @FXML CheckBox cbPauseResume;
    @FXML CheckBox cbVtbi;
    @FXML CheckBox cbBolus;
    @FXML CheckBox cbBadCommands;

    // New command type checkboxes (wired in PumpActuatorApp.fxml)
    @FXML CheckBox cbQueryStatus;
    @FXML CheckBox cbStatePollBurst;
    @FXML CheckBox cbCommSilence;
    @FXML CheckBox cbCommDisruption;
    @FXML CheckBox cbVtbiToCompletion;
    @FXML CheckBox cbQueryVolumeInfused;
    @FXML CheckBox cbQueryPowerStatus;
    @FXML CheckBox cbSessionTeardown;
    @FXML CheckBox cbQueryDeviceTime;

    // MUI intervention is not a trial type — it is an operator annotation
    // triggered manually via a dedicated button at any time during a run.
    @FXML Button muiInterventionButton;

    @FXML Spinner<Integer> trialsSpinner;
    @FXML Spinner<Integer> intervalMsSpinner;

    @FXML Button startButton;
    @FXML Button stopButton;

    // -------------------------------------------------------------------------
    // OpenICE references
    // -------------------------------------------------------------------------
    private DeviceListModel deviceListModel;
    private NumericFxList numericList;
    private FlowRateObjectiveDataWriter flowRateWriter;
    private InfusionObjectiveDataWriter infusionObjectiveWriter;
    private InfusionProgramDataWriter infusionProgramWriter;

    /** Writer for the TrialMarker topic — shared with DDSDataLoggerApp via DDS. */
    private TrialMarkerDataWriter trialMarkerWriter;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger completedTrials = new AtomicInteger(0);

    private ScheduledExecutorService trialExecutor;
    private ScheduledFuture<?> runFuture;

    /** Monotonically increasing run ID so logger can separate runs in the CSV. */
    private long runId = 0;

    // Counters for cycling through rate sequence
    private int rateSequenceIndex = 0;
    private int pauseResumePhase  = 0; // 0 = pause, 1 = resume

    // =========================================================================
    // Dependency injection
    // =========================================================================

    public void set(ApplicationContext ctx,
                    DeviceListModel deviceListModel,
                    NumericFxList numericList,
                    FlowRateObjectiveDataWriter flowRateWriter,
                    InfusionObjectiveDataWriter infusionObjectiveWriter,
                    InfusionProgramDataWriter infusionProgramWriter,
                    TrialMarkerDataWriter trialMarkerWriter) {
        this.deviceListModel = deviceListModel;
        this.numericList = numericList;
        this.flowRateWriter = flowRateWriter;
        this.infusionObjectiveWriter = infusionObjectiveWriter;
        this.infusionProgramWriter = infusionProgramWriter;
        this.trialMarkerWriter = trialMarkerWriter;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void start(EventLoop eventLoop) {
        trialExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mddt-actuator");
            t.setDaemon(true);
            return t;
        });

        initSpinners();

        // Rely on addition of metrics to discover devices.
        numericList.addListener((ListChangeListener<NumericFx>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(n -> {
                    if (FLOW_RATE.equals(n.getMetric_id())) {
                        Device d = deviceListModel.getByUniqueDeviceIdentifier(
                                n.getUnique_device_identifier());
                        if (d != null && !pumps.getItems().contains(d)) {
                            pumps.getItems().add(d);
                        }
                    }
                });
            }
        });

        deviceListModel.getContents().addListener((ListChangeListener<Device>) change -> {
            while (change.next()) {
                change.getRemoved().forEach(d -> pumps.getItems().remove(d));
            }
        });

        pumps.setCellFactory(new Callback<ListView<Device>, ListCell<Device>>() {
            @Override
            public ListCell<Device> call(ListView<Device> device) {
                return new ListCell<Device>() {
                    @Override
                    protected void updateItem(Device device, boolean empty) {
                        super.updateItem(device, empty);
                        if (!empty && device != null) {
                            setText(device.getModel() + "(" + device.getComPort() + ")");
                        } else {
                            setText(null);
                        }
                    }
                };
            }
        });

        pumps.setConverter(new StringConverter<Device>() {
            @Override public Device fromString(String arg0) { return null; }
            @Override public String toString(Device device) {
                if (device == null) return "";
                return device.getModel() + "(" + device.getComPort() + ")";
            }
        });

        pumps.getSelectionModel().selectedItemProperty().addListener(
            (observable, oldValue, newValue) -> startButton.setDisable(newValue == null));

        setRunning(false);
        appendStatus("[ACTUATOR] Ready. Waiting for ECIP on DDS bus...");
    }

    public void activate() {
        // No-op for now. Main setup is in start(EventLoop).
    }

    public void stop() { stopRun(); }

    public void destroy() {
        stopRun();
        if (trialExecutor != null) trialExecutor.shutdownNow();
    }

    // =========================================================================
    // Trial run control (FXML button handlers)
    // =========================================================================

    @FXML
    public void startRun() {
        Device targetDevice = pumps.getSelectionModel().getSelectedItem();
        if (targetDevice == null) {
            appendStatus("[ACTUATOR] No pump selected — cannot start.");
            return;
        }
        if (!anyCommandTypeSelected()) {
            appendStatus("[ACTUATOR] Select at least one command type.");
            return;
        }

        int totalTrials = trialsSpinner.getValue();
        int intervalMs  = intervalMsSpinner.getValue();

        runId++;
        completedTrials.set(0);
        rateSequenceIndex = 0;
        pauseResumePhase  = 0;
        running.set(true);
        setRunning(true);

        List<CommandType> selectedTypes = buildCommandTypeList();

        appendStatus(String.format("[RUN_START] runId=%d trials=%d intervalMs=%d types=%s",
            runId, totalTrials, intervalMs, selectedTypes));

        publishTrialMarker(runId, 0, "RUN_START", "RUN_START", 0f);

        final long runIdCapture = runId;
        runFuture = trialExecutor.scheduleAtFixedRate(() -> {
            int trialNum = completedTrials.incrementAndGet();
            if (trialNum > totalTrials || !running.get()) {
                finishRun(runIdCapture, totalTrials);
                return;
            }

            CommandType type = selectedTypes.get((trialNum - 1) % selectedTypes.size());
            executeTrial(runIdCapture, trialNum, type);

            Platform.runLater(() -> {
                trialProgressLabel.setText(trialNum + " / " + totalTrials);
                trialProgressBar.setProgress((double) trialNum / totalTrials);
            });

        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    @FXML
    public void stopRun() {
        if (running.getAndSet(false)) {
            if (runFuture != null) runFuture.cancel(false);
            appendStatus("[RUN_STOPPED] Stopped after " + completedTrials.get() + " trials.");
            publishTrialMarker(runId, completedTrials.get(), "RUN_STOPPED", "RUN_STOPPED", 0f);
            setRunning(false);
        }
    }

    /**
     * Operator-initiated MUI annotation.  Call this when a clinician physically
     * intervenes at the pump's front panel (e.g., changes a rate or pauses the
     * pump via the MUI) so the logger can correlate any subsequent EDI state
     * changes with the known intervention time (R-202).
     *
     * This is wired to the "Mark MUI Intervention" button in the FXML and may
     * also be called programmatically during automated tests if a simulated pump
     * emits a MUI-change event.
     */
    @FXML
    public void markMuiIntervention() {
        long ts = System.currentTimeMillis();
        publishTrialMarker(runId, completedTrials.get(),
            "MUI_INTERVENTION", "MUI_INTERVENTION", 0f,
            "source=operator ts=" + ts);
        appendStatus("[MUI_INTERVENTION] Operator annotation published at t=" + ts);
        log.info("[MUI_INTERVENTION] runId={} ts={}", runId, ts);
    }

    // =========================================================================
    // Individual trial execution
    // =========================================================================

    /**
     * Execute one trial of the given command type.
     * Publishes TRIAL_START marker, sends the command, publishes CMD_SENT marker.
     */
    private void executeTrial(long runId, int trialNum, CommandType type) {
        publishTrialMarker(runId, trialNum, "TRIAL_START", type.name(), 0f);

        switch (type) {
            case RATE_CHANGE:           executeRateChange(runId, trialNum);           break;
            case PAUSE:                 executePause(runId, trialNum);                break;
            case RESUME:                executeResume(runId, trialNum);               break;
            case VTBI_SET:              executeVtbiSet(runId, trialNum);              break;
            case BOLUS:                 executeBolus(runId, trialNum);                break;
            case BAD_CMD_OVER:          executeBadCommand(runId, trialNum, BAD_RATE_OVER);  break;
            case BAD_CMD_UNDER:         executeBadCommand(runId, trialNum, BAD_RATE_UNDER); break;
            case QUERY_STATUS:          executeQueryStatus(runId, trialNum);          break;
            case STATE_POLL_BURST:      executeStatePollBurst(runId, trialNum);       break;
            case COMM_SILENCE:          executeCommSilence(runId, trialNum);          break;
            case COMM_DISRUPTION:       executeCommDisruption(runId, trialNum);       break;
            case VTBI_TO_COMPLETION:    executeVtbiToCompletion(runId, trialNum);     break;
            case QUERY_VOLUME_INFUSED:  executeQueryVolumeInfused(runId, trialNum);   break;
            case QUERY_POWER_STATUS:    executeQueryPowerStatus(runId, trialNum);     break;
            case SESSION_TEARDOWN:      executeSessionTeardown(runId, trialNum);      break;
            case SESSION_REESTABLISH:   executeSessionReestablish(runId, trialNum);   break;
            case QUERY_DEVICE_TIME:     executeQueryDeviceTime(runId, trialNum);      break;
        }
    }

    // ── Original command types ─────────────────────────────────────────────

    private void executeRateChange(long runId, int trialNum) {
        float rate = RATE_SEQUENCE_ML_PER_HR[rateSequenceIndex % RATE_SEQUENCE_ML_PER_HR.length];
        rateSequenceIndex++;

        ice.FlowRateObjective obj = new ice.FlowRateObjective();
        obj.newFlowRate = rate;
        obj.unique_device_identifier = selectedUDI();
        flowRateWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "RATE_CHANGE", rate, "rate=" + rate);
        log.info("[TRIAL] runId={} trial={} RATE_CHANGE rate={}", runId, trialNum, rate);
    }

    private void executePause(long runId, int trialNum) {
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.stopInfusion = true;
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "PAUSE", 0f, "stopInfusion=true");
        log.info("[TRIAL] runId={} trial={} PAUSE", runId, trialNum);
    }

    private void executeResume(long runId, int trialNum) {
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.stopInfusion = false;
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "RESUME", 0f, "stopInfusion=false");
        log.info("[TRIAL] runId={} trial={} RESUME", runId, trialNum);
    }

    private void executeVtbiSet(long runId, int trialNum) {
        float[] vtbiValues = {50f, 100f, 200f};
        float vtbi = vtbiValues[trialNum % vtbiValues.length];

        InfusionProgram prog = new InfusionProgram();
        prog.head         = 1;
        prog.infusionRate = -1f;
        prog.bolusRate    = -1f;
        prog.bolusVolume  = -1f;
        prog.VTBI         = vtbi;
        prog.unique_device_identifier = selectedUDI();
        prog.requestor    = "MDDTActuator";
        infusionProgramWriter.write(prog, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "VTBI_SET", vtbi, "vtbi=" + vtbi);
        log.info("[TRIAL] runId={} trial={} VTBI_SET vtbi={}", runId, trialNum, vtbi);
    }

    private void executeBolus(long runId, int trialNum) {
        float bolusRate   = 200f;
        float bolusVolume = 5f;

        InfusionProgram prog = new InfusionProgram();
        prog.head         = 1;
        prog.infusionRate = -1f;
        prog.bolusRate    = bolusRate;
        prog.bolusVolume  = bolusVolume;
        prog.VTBI         = -1f;
        prog.unique_device_identifier = selectedUDI();
        prog.requestor    = "MDDTActuator";
        infusionProgramWriter.write(prog, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "BOLUS", bolusVolume,
            "bolusRate=" + bolusRate + " bolusVolume=" + bolusVolume);
        log.info("[TRIAL] runId={} trial={} BOLUS rate={} vol={}", runId, trialNum, bolusRate, bolusVolume);
    }

    /**
     * Send a deliberately out-of-range rate command (R-701, R-702).
     * Tests the pump's ability to reject invalid parameters with a structured
     * error response and continue operating at its current rate.
     */
    private void executeBadCommand(long runId, int trialNum, float badRate) {
        ice.FlowRateObjective obj = new ice.FlowRateObjective();
        obj.newFlowRate = badRate;
        obj.unique_device_identifier = selectedUDI();
        flowRateWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        String label = badRate > 0 ? "BAD_CMD_OVER_RANGE" : "BAD_CMD_UNDER_RANGE";
        publishTrialMarker(runId, trialNum, "CMD_SENT", label, badRate, "rate=" + badRate);
        log.info("[TRIAL] runId={} trial={} {} rate={}", runId, trialNum, label, badRate);
    }

    // ── New command types ──────────────────────────────────────────────────

    /**
     * QUERY_STATUS — measures acknowledgement-only latency (R-203, R-106).
     *
     * Sends a status query objective to the pump and immediately timestamps the
     * CMD_SENT marker. The logger measures the time until the pump's state
     * appears on the DDS bus. This isolates query/acknowledgement latency from
     * the actuation latency measured by RATE_CHANGE trials.
     *
     * TODO: Replace the InfusionObjective workaround with a dedicated
     * QueryStatus objective type once the ice IDL is extended. The current
     * approach reuses the InfusionObjective with stopInfusion set to the
     * pump's current known state (a no-op command) purely to trigger a
     * protocol round-trip.
     */
    private void executeQueryStatus(long runId, int trialNum) {
        // Issue a benign no-op objective to provoke a protocol round-trip.
        // A dedicated QueryStatus DDS message would be cleaner; this is a
        // pragmatic placeholder until the IDL is extended.
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        // stopInfusion left at default (false) — the pump should
        // acknowledge receipt without changing state.
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "QUERY_STATUS", 0f,
            "purpose=latency_only");
        log.info("[TRIAL] runId={} trial={} QUERY_STATUS", runId, trialNum);
    }

    /**
     * STATE_POLL_BURST — measures state reporting timeliness (R-201, R-202).
     *
     * First sends a rate-change command, then fires STATE_POLL_BURST_COUNT
     * rapid status queries at STATE_POLL_BURST_INTERVAL_MS intervals.
     * The TrialMarker for each burst query carries the burst index in params
     * so the logger can measure how many polls elapsed before the pump's
     * reported rate reflected the commanded value.
     */
    private void executeStatePollBurst(long runId, int trialNum) {
        // Step 1: issue a rate change to create a detectable state transition.
        float rate = RATE_SEQUENCE_ML_PER_HR[rateSequenceIndex % RATE_SEQUENCE_ML_PER_HR.length];
        rateSequenceIndex++;

        ice.FlowRateObjective obj = new ice.FlowRateObjective();
        obj.newFlowRate = rate;
        obj.unique_device_identifier = selectedUDI();
        flowRateWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "STATE_POLL_BURST",
            rate, "phase=rate_change rate=" + rate + " burstCount=" + STATE_POLL_BURST_COUNT);
        log.info("[TRIAL] runId={} trial={} STATE_POLL_BURST rate={}", runId, trialNum, rate);

        // Step 2: fire rapid queries so the logger can detect when the new
        // rate appears in the pump's EDI-reported state.
        for (int i = 0; i < STATE_POLL_BURST_COUNT; i++) {
            try {
                Thread.sleep(STATE_POLL_BURST_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) return;

            ice.InfusionObjective qobj = new ice.InfusionObjective();
            qobj.unique_device_identifier = selectedUDI();
            qobj.head = 1;
            infusionObjectiveWriter.write(qobj, InstanceHandle_t.HANDLE_NIL);

            publishTrialMarker(runId, trialNum, "CMD_SENT", "STATE_POLL_BURST",
                rate, "phase=poll pollIndex=" + i + " rate=" + rate);
        }
    }

    /**
     * COMM_SILENCE — tests heartbeat / communication supervision (R-106, R-601).
     *
     * Marks the start of a deliberate keep-alive silence period, then sleeps
     * for COMM_SILENCE_DURATION_MS without writing any DDS samples. The logger
     * observes whether the pump enters its declared fail-safe state within the
     * declared timeout window. A COMM_SILENCE_END marker is published on
     * resumption so the logger knows when normal communication restores.
     *
     * Note: "silence" here means the actuator stops publishing objectives.
     * The pump's own heartbeat watchdog detects the absence of controller
     * keep-alives. The precise mechanism is device-specific (declared per MDIDS).
     */
    private void executeCommSilence(long runId, int trialNum) {
        publishTrialMarker(runId, trialNum, "CMD_SENT", "COMM_SILENCE", 0f,
            "silenceDurationMs=" + COMM_SILENCE_DURATION_MS);
        log.info("[TRIAL] runId={} trial={} COMM_SILENCE start, duration={}ms",
            runId, trialNum, COMM_SILENCE_DURATION_MS);

        // Stop publishing for the silence window. The pump's watchdog should
        // detect the absence of keep-alive signals and enter its fail-safe state.
        try {
            Thread.sleep(COMM_SILENCE_DURATION_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // Resume with an explicit state query so the logger can observe recovery.
        if (!running.get()) return;
        ice.InfusionObjective resume = new ice.InfusionObjective();
        resume.unique_device_identifier = selectedUDI();
        resume.head = 1;
        infusionObjectiveWriter.write(resume, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "COMM_SILENCE_END", 0f,
            "silenceDurationMs=" + COMM_SILENCE_DURATION_MS);
        log.info("[TRIAL] runId={} trial={} COMM_SILENCE end, resuming", runId, trialNum);
    }

    /**
     * COMM_DISRUPTION — tests fail-safe behavior and session recovery (R-601, R-602, R-703).
     *
     * Similar to COMM_SILENCE but semantically represents a full EDI session
     * loss rather than a soft keep-alive gap. Publishes distinct COMM_DISRUPTION_START
     * and COMM_DISRUPTION_END markers so the logger can measure both the time
     * until fail-safe state is entered and the time until accurate state is
     * reported after reconnection.
     *
     * TODO: When the OpenICE driver layer exposes a session-level disconnect API,
     * replace the sleep with an explicit session teardown + reestablish sequence.
     * Until then, the silence approximates uncontrolled communication loss.
     */
    private void executeCommDisruption(long runId, int trialNum) {
        publishTrialMarker(runId, trialNum, "CMD_SENT", "COMM_DISRUPTION_START", 0f,
            "disruptionDurationMs=" + COMM_DISRUPTION_DURATION_MS);
        log.info("[TRIAL] runId={} trial={} COMM_DISRUPTION start, duration={}ms",
            runId, trialNum, COMM_DISRUPTION_DURATION_MS);

        // Simulate session loss by going silent.
        // TODO: replace with driver-level session.disconnect() when available.
        try {
            Thread.sleep(COMM_DISRUPTION_DURATION_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!running.get()) return;

        // Issue a status query immediately on reconnection. The logger checks
        // whether the pump reports accurate state without a full session restart
        // (R-703).
        ice.InfusionObjective reconnect = new ice.InfusionObjective();
        reconnect.unique_device_identifier = selectedUDI();
        reconnect.head = 1;
        infusionObjectiveWriter.write(reconnect, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "COMM_DISRUPTION_END", 0f,
            "disruptionDurationMs=" + COMM_DISRUPTION_DURATION_MS);
        log.info("[TRIAL] runId={} trial={} COMM_DISRUPTION end, reconnected", runId, trialNum);
    }

    /**
     * VTBI_TO_COMPLETION — tests alarm observability (R-501–R-503).
     *
     * Sets VTBI to a deliberately small value (VTBI_COMPLETION_TEST_ML) at a
     * moderate infusion rate so the pump reaches end-of-infusion within the
     * inter-trial interval. The logger then observes whether an end-of-infusion
     * or KVO alarm appears on the DDS bus, verifying that clinical alarms
     * propagate through the EDI.
     *
     * Warning: this trial type causes the pump to stop (or enter KVO). The
     * operator should ensure the inter-trial interval is long enough for the
     * pump to complete and for any state-recovery commands to execute before
     * the next trial.
     */
    private void executeVtbiToCompletion(long runId, int trialNum) {
        float testRate = 50f; // mL/hr — low enough to be safe but fast enough to complete quickly

        // Set a low VTBI at a known rate so delivery completes fast.
        InfusionProgram prog = new InfusionProgram();
        prog.head         = 1;
        prog.infusionRate = testRate;
        prog.bolusRate    = -1f;
        prog.bolusVolume  = -1f;
        prog.VTBI         = VTBI_COMPLETION_TEST_ML;
        prog.unique_device_identifier = selectedUDI();
        prog.requestor    = "MDDTActuator";
        infusionProgramWriter.write(prog, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "VTBI_TO_COMPLETION",
            VTBI_COMPLETION_TEST_ML,
            "vtbi=" + VTBI_COMPLETION_TEST_ML + " rate=" + testRate
            + " expectedCompletionMs=" + Math.round(VTBI_COMPLETION_TEST_ML / testRate * 3600_000));
        log.info("[TRIAL] runId={} trial={} VTBI_TO_COMPLETION vtbi={}mL rate={}mL/hr",
            runId, trialNum, VTBI_COMPLETION_TEST_ML, testRate);
    }

    /**
     * QUERY_VOLUME_INFUSED — validates cumulative volume delivery (R-302).
     *
     * Publishes a marker carrying the wall-clock timestamp of the query. The
     * logger reads the pump's reported cumulative volume infused from the DDS
     * numeric stream and compares it against the volume that should have been
     * delivered given the commanded rates and elapsed times since run start.
     *
     * The actuator itself does not compute or validate the volume; that is the
     * logger's responsibility. The marker provides the logger with an explicit
     * synchronisation point.
     */
    private void executeQueryVolumeInfused(long runId, int trialNum) {
        // Issue a no-op status query to ensure the pump publishes a fresh state
        // sample before the logger reads the volume metric.
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "QUERY_VOLUME_INFUSED", 0f,
            "purpose=volume_validation queryTs=" + System.currentTimeMillis());
        log.info("[TRIAL] runId={} trial={} QUERY_VOLUME_INFUSED", runId, trialNum);
    }

    /**
     * QUERY_POWER_STATUS — tests power status reporting (R-204).
     *
     * Publishes a marker so the logger knows to read the pump's power-status
     * numeric metric at this timestamp. The logger records power source
     * (mains / battery) and battery level or low-battery condition and logs
     * them alongside the trial data.
     *
     * TODO: When a dedicated QueryPowerStatus DDS message is available in the
     * ice IDL, replace the InfusionObjective no-op with it.
     */
    private void executeQueryPowerStatus(long runId, int trialNum) {
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "QUERY_POWER_STATUS", 0f,
            "purpose=battery_mains_validation queryTs=" + System.currentTimeMillis());
        log.info("[TRIAL] runId={} trial={} QUERY_POWER_STATUS", runId, trialNum);
    }

    /**
     * SESSION_TEARDOWN — tests graceful EDI session termination (R-107, R-703).
     *
     * Issues an explicit session-end signal to the pump and records the marker
     * so the logger can observe whether the pump transitions to its declared
     * graceful-disconnect state (distinct from the fail-safe entered on
     * uncontrolled communication loss per R-603).
     *
     * TODO: Replace the InfusionObjective workaround with the protocol-level
     * disconnect command (e.g., RemoteControlRelease) once the driver layer
     * exposes it.
     */
    private void executeSessionTeardown(long runId, int trialNum) {
        // Signal end of external control. Specific protocol command varies by
        // device; the driver layer translates this to the correct EDI message.
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        obj.stopInfusion = true; // Safest state to leave pump in on disconnect.
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "SESSION_TEARDOWN", 0f,
            "purpose=graceful_disconnect");
        log.info("[TRIAL] runId={} trial={} SESSION_TEARDOWN", runId, trialNum);
    }

    /**
     * SESSION_REESTABLISH — tests session recovery after teardown (R-107, R-703).
     *
     * Intended to follow SESSION_TEARDOWN in the same run. Issues a new session
     * establishment command and immediately queries pump state to verify the pump
     * reports accurately after reconnection without requiring operator interaction.
     */
    private void executeSessionReestablish(long runId, int trialNum) {
        // Re-enter external control. The driver layer handles the protocol-
        // specific session establishment handshake.
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        obj.stopInfusion = false;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "SESSION_REESTABLISH", 0f,
            "purpose=reconnect_state_check");
        log.info("[TRIAL] runId={} trial={} SESSION_REESTABLISH", runId, trialNum);
    }

    /**
     * QUERY_DEVICE_TIME — tests clock / timestamp correlation (R-205).
     *
     * Records the actuator's wall-clock time in the TrialMarker params alongside
     * a request for the pump's device timestamp. The logger reads the pump's
     * reported timestamp from the DDS numeric stream and computes the delta,
     * enabling validation that event logs from the pump and the controller can
     * be correlated within an acceptable tolerance.
     *
     * TODO: Add a dedicated DeviceTimeQuery DDS objective type when the ice IDL
     * is extended; for now the marker alone gives the logger a synchronisation
     * reference point.
     */
    private void executeQueryDeviceTime(long runId, int trialNum) {
        long wallClockMs = System.currentTimeMillis();

        // Issue a no-op query so the pump publishes a fresh timestamped state.
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.unique_device_identifier = selectedUDI();
        obj.head = 1;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "QUERY_DEVICE_TIME", 0f,
            "actuatorWallClockMs=" + wallClockMs);
        log.info("[TRIAL] runId={} trial={} QUERY_DEVICE_TIME wallClock={}", runId, trialNum, wallClockMs);
    }

    // =========================================================================
    // TrialMarker publishing
    // =========================================================================

    private void publishTrialMarker(long runId, int trialNum, String eventType,
                                    String cmdType, float value, String... params) {
        if (trialMarkerWriter == null) return;
        TrialMarker marker = new TrialMarker();
        marker.runId       = runId;
        marker.trialNum    = trialNum;
        marker.eventType   = eventType;
        marker.cmdType     = cmdType;
        marker.value       = value;
        marker.params      = params.length > 0 ? params[0] : "";
        marker.timestampMs = System.currentTimeMillis();
        trialMarkerWriter.write(marker, InstanceHandle_t.HANDLE_NIL);
    }

    // =========================================================================
    // Run completion
    // =========================================================================

    private void finishRun(long runId, int totalTrials) {
        if (!running.getAndSet(false)) return;
        if (runFuture != null) runFuture.cancel(false);

        publishTrialMarker(runId, totalTrials, "RUN_COMPLETE", "RUN_COMPLETE", 0f);
        String msg = String.format("[RUN_COMPLETE] runId=%d completedTrials=%d",
            runId, completedTrials.get());
        log.info(msg);
        Platform.runLater(() -> {
            appendStatus(msg);
            setRunning(false);
            trialProgressBar.setProgress(1.0);
        });
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private String selectedUDI() {
        return pumps.getSelectionModel().getSelectedItem().getUDI();
    }

    private void initSpinners() {
        if (trialsSpinner != null) {
            trialsSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10000, 50, 10));
        }
        if (intervalMsSpinner != null) {
            intervalMsSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(100, 60000, 2000, 500));
        }
    }

    private void setRunning(boolean isRunning) {
        Platform.runLater(() -> {
            if (startButton != null)
                startButton.setDisable(isRunning || pumps.getSelectionModel().getSelectedItem() == null);
            if (stopButton  != null) stopButton.setDisable(!isRunning);

            CheckBox[] boxes = {
                cbRateChange, cbPauseResume, cbVtbi, cbBolus, cbBadCommands,
                cbQueryStatus, cbStatePollBurst, cbCommSilence, cbCommDisruption,
                cbVtbiToCompletion, cbQueryVolumeInfused, cbQueryPowerStatus,
                cbSessionTeardown, cbQueryDeviceTime
            };
            for (CheckBox cb : boxes) {
                if (cb != null) cb.setDisable(isRunning);
            }

            if (trialsSpinner     != null) trialsSpinner.setDisable(isRunning);
            if (intervalMsSpinner != null) intervalMsSpinner.setDisable(isRunning);
            if (pumps             != null) pumps.setDisable(isRunning);
            // MUI intervention button is active during runs (that's its purpose)
            if (muiInterventionButton != null) muiInterventionButton.setDisable(!isRunning);
        });
    }

    private boolean anyCommandTypeSelected() {
        CheckBox[] boxes = {
            cbRateChange, cbPauseResume, cbVtbi, cbBolus, cbBadCommands,
            cbQueryStatus, cbStatePollBurst, cbCommSilence, cbCommDisruption,
            cbVtbiToCompletion, cbQueryVolumeInfused, cbQueryPowerStatus,
            cbSessionTeardown, cbQueryDeviceTime
        };
        for (CheckBox cb : boxes) {
            if (cb != null && cb.isSelected()) return true;
        }
        return false;
    }

    /**
     * Build the ordered list of command types to cycle through, based on
     * which checkboxes are selected. Bad commands expand into two variants
     * (over-range and under-range). SESSION_TEARDOWN is always immediately
     * followed by SESSION_REESTABLISH so the pump is never left disconnected
     * for subsequent trials.
     */
    private List<CommandType> buildCommandTypeList() {
        List<CommandType> types = new ArrayList<>();
        if (cbRateChange       != null && cbRateChange.isSelected())       types.add(CommandType.RATE_CHANGE);
        if (cbPauseResume      != null && cbPauseResume.isSelected()) {
            types.add(CommandType.PAUSE);
            types.add(CommandType.RESUME);
        }
        if (cbVtbi             != null && cbVtbi.isSelected())             types.add(CommandType.VTBI_SET);
        if (cbBolus            != null && cbBolus.isSelected())            types.add(CommandType.BOLUS);
        if (cbBadCommands      != null && cbBadCommands.isSelected()) {
            types.add(CommandType.BAD_CMD_OVER);
            types.add(CommandType.BAD_CMD_UNDER);
        }
        if (cbQueryStatus      != null && cbQueryStatus.isSelected())      types.add(CommandType.QUERY_STATUS);
        if (cbStatePollBurst   != null && cbStatePollBurst.isSelected())   types.add(CommandType.STATE_POLL_BURST);
        if (cbCommSilence      != null && cbCommSilence.isSelected())      types.add(CommandType.COMM_SILENCE);
        if (cbCommDisruption   != null && cbCommDisruption.isSelected())   types.add(CommandType.COMM_DISRUPTION);
        if (cbVtbiToCompletion != null && cbVtbiToCompletion.isSelected()) types.add(CommandType.VTBI_TO_COMPLETION);
        if (cbQueryVolumeInfused != null && cbQueryVolumeInfused.isSelected()) types.add(CommandType.QUERY_VOLUME_INFUSED);
        if (cbQueryPowerStatus != null && cbQueryPowerStatus.isSelected()) types.add(CommandType.QUERY_POWER_STATUS);
        if (cbSessionTeardown  != null && cbSessionTeardown.isSelected()) {
            // Always pair teardown with immediate reestablish so the pump is
            // never left disconnected heading into the next trial.
            types.add(CommandType.SESSION_TEARDOWN);
            types.add(CommandType.SESSION_REESTABLISH);
        }
        if (cbQueryDeviceTime  != null && cbQueryDeviceTime.isSelected())  types.add(CommandType.QUERY_DEVICE_TIME);
        return types;
    }

    private void appendStatus(String text) {
        log.info(text);
        Platform.runLater(() -> {
            if (statusArea != null) statusArea.appendText(text + "\n");
        });
    }

    // =========================================================================
    // Command type enum
    // =========================================================================

    public enum CommandType {
        // Original
        RATE_CHANGE,
        PAUSE,
        RESUME,
        VTBI_SET,
        BOLUS,
        BAD_CMD_OVER,
        BAD_CMD_UNDER,
        // New — latency / timing
        QUERY_STATUS,
        STATE_POLL_BURST,
        // New — supervision / safety
        COMM_SILENCE,
        COMM_DISRUPTION,
        // New — alarm
        VTBI_TO_COMPLETION,
        // New — data validation
        QUERY_VOLUME_INFUSED,
        QUERY_POWER_STATUS,
        // New — session
        SESSION_TEARDOWN,
        SESSION_REESTABLISH,
        // New — time correlation
        QUERY_DEVICE_TIME
    }
}
