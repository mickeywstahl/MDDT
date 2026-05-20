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
 *       2. Sends the command (rate change, pause, resume, VTBI, bolus, bad cmd).
 *       3. Publishes a CMD_SENT TrialMarker to DDS.
 *       4. Waits for the inter-trial interval before the next trial.
 *   - After all trials, publishes a RUN_COMPLETE TrialMarker.
 *
 * TrialMarker topic carries: run ID, trial number, command type, commanded
 * value, and a wall-clock timestamp. The logger uses this to compute latency
 * by correlating CMD_SENT timestamps with subsequent numeric state changes.
 *
 * @see DDSDataLoggerApp (the passive observer that does all measurement)
 * @see RealisticSimPump (the simulated ECIP this actuator drives)
 */
public class PumpActuatorApp {

    private static final Logger log = LoggerFactory.getLogger(PumpActuatorApp.class);

    // Rate sequence used for rate-change trials (cycles through these values)
    private static final float[] RATE_SEQUENCE_ML_PER_HR = {
        5f, 10f, 20f, 50f, 100f, 200f, 50f, 20f, 10f, 5f
    };

    // Out-of-range value used for bad-command trials
    private static final float BAD_RATE_OVER  =  9999f;
    private static final float BAD_RATE_UNDER = -1f;

    private final String FLOW_RATE = rosetta.MDC_FLOW_FLUID_PUMP.VALUE;

    // -------------------------------------------------------------------------
    // FXML UI
    // -------------------------------------------------------------------------
    @FXML TextArea statusArea;
    @FXML ComboBox<Device> pumps;
    @FXML Label trialProgressLabel;
    @FXML ProgressBar trialProgressBar;

    @FXML CheckBox cbRateChange;
    @FXML CheckBox cbPauseResume;
    @FXML CheckBox cbVtbi;
    @FXML CheckBox cbBolus;
    @FXML CheckBox cbBadCommands;

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
        
        //Rely on addition of metrics to add devices...
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
		
		//...and removal of devices to remove devices.
		deviceListModel.getContents().addListener((ListChangeListener<Device>) change -> {
			while (change.next()) {
				change.getRemoved().forEach(d -> {
					pumps.getItems().remove(d);
				});
			}
		});

        pumps.setCellFactory(new Callback<ListView<Device>,ListCell<Device>>() {
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
			@Override
			public Device fromString(String arg0) {
				return null;
			}

			@Override
			public String toString(Device device) {
                if (device == null) {
                    return "";
                }
				return device.getModel()+"("+device.getComPort()+")";
			}
		});

        pumps.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            startButton.setDisable(newValue == null);
        });

        setRunning(false);
        appendStatus("[ACTUATOR] Ready. Waiting for ECIP on DDS bus...");
    }
    
    public void activate() {
        // No-op for now.  We do our main setup in start(EventLoop)
    }

    public void stop() {
        stopRun();
    }

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

        // Schedule trials one by one with fixed delay between them
        final long runIdCapture = runId;
        runFuture = trialExecutor.scheduleAtFixedRate(() -> {
            int trialNum = completedTrials.incrementAndGet();
            if (trialNum > totalTrials || !running.get()) {
                finishRun(runIdCapture, totalTrials);
                return;
            }

            // Cycle through selected command types
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

    // =========================================================================
    // Individual trial execution
    // =========================================================================

    /**
     * Execute one trial of the given command type.
     * Publishes TRIAL_START marker, sends the command, publishes CMD_SENT marker.
     * This method must not block longer than the inter-trial interval.
     */
    private void executeTrial(long runId, int trialNum, CommandType type) {
        publishTrialMarker(runId, trialNum, "TRIAL_START", type.name(), 0f);

        switch (type) {
            case RATE_CHANGE:    executeRateChange(runId, trialNum);    break;
            case PAUSE:          executePause(runId, trialNum);         break;
            case RESUME:         executeResume(runId, trialNum);        break;
            case VTBI_SET:       executeVtbiSet(runId, trialNum);       break;
            case BOLUS:          executeBolus(runId, trialNum);         break;
            case BAD_CMD_OVER:   executeBadCommand(runId, trialNum, BAD_RATE_OVER);  break;
            case BAD_CMD_UNDER:  executeBadCommand(runId, trialNum, BAD_RATE_UNDER); break;
        }
    }

    private void executeRateChange(long runId, int trialNum) {
        float rate = RATE_SEQUENCE_ML_PER_HR[rateSequenceIndex % RATE_SEQUENCE_ML_PER_HR.length];
        rateSequenceIndex++;

        ice.FlowRateObjective obj = new ice.FlowRateObjective();
        obj.newFlowRate = rate;
        obj.unique_device_identifier = pumps.getSelectionModel().getSelectedItem().getUDI();
        flowRateWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "RATE_CHANGE",
            rate, "rate=" + rate);
        log.info("[TRIAL] runId={} trial={} RATE_CHANGE rate={}", runId, trialNum, rate);
    }

    private void executePause(long runId, int trialNum) {
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.stopInfusion = true;
        obj.unique_device_identifier = pumps.getSelectionModel().getSelectedItem().getUDI();
        obj.head = 1;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "PAUSE", 0f, "stopInfusion=true");
        log.info("[TRIAL] runId={} trial={} PAUSE", runId, trialNum);
    }

    private void executeResume(long runId, int trialNum) {
        ice.InfusionObjective obj = new ice.InfusionObjective();
        obj.stopInfusion = false;
        obj.unique_device_identifier = pumps.getSelectionModel().getSelectedItem().getUDI();
        obj.head = 1;
        infusionObjectiveWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "RESUME", 0f, "stopInfusion=false");
        log.info("[TRIAL] runId={} trial={} RESUME", runId, trialNum);
    }

    private void executeVtbiSet(long runId, int trialNum) {
        // Cycle through VTBI values: 50, 100, 200 mL
        float[] vtbiValues = {50f, 100f, 200f};
        float vtbi = vtbiValues[trialNum % vtbiValues.length];

        InfusionProgram prog = new InfusionProgram();
        prog.head = 1;
        prog.infusionRate = -1f;   // unchanged
        prog.bolusRate    = -1f;   // unchanged
        prog.bolusVolume  = -1f;   // unchanged
        prog.VTBI = vtbi;
        prog.unique_device_identifier = pumps.getSelectionModel().getSelectedItem().getUDI();
        prog.requestor = "MDDTActuator";
        infusionProgramWriter.write(prog, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "VTBI_SET", vtbi, "vtbi=" + vtbi);
        log.info("[TRIAL] runId={} trial={} VTBI_SET vtbi={}", runId, trialNum, vtbi);
    }

    private void executeBolus(long runId, int trialNum) {
        float bolusRate   = 200f;  // mL/hr
        float bolusVolume = 5f;    // mL

        InfusionProgram prog = new InfusionProgram();
        prog.head = 1;
        prog.infusionRate = -1f;   // unchanged
        prog.bolusRate    = bolusRate;
        prog.bolusVolume  = bolusVolume;
        prog.VTBI         = -1f;   // unchanged
        prog.unique_device_identifier = pumps.getSelectionModel().getSelectedItem().getUDI();
        prog.requestor = "MDDTActuator";
        infusionProgramWriter.write(prog, InstanceHandle_t.HANDLE_NIL);

        publishTrialMarker(runId, trialNum, "CMD_SENT", "BOLUS", bolusVolume,
            "bolusRate=" + bolusRate + " bolusVolume=" + bolusVolume);
        log.info("[TRIAL] runId={} trial={} BOLUS rate={} vol={}", runId, trialNum, bolusRate, bolusVolume);
    }

    /**
     * Send a deliberately out-of-range rate command.
     * Tests the pump's ability to handle and report invalid parameters
     * (MDDT attribute: ECIP error handling).
     */
    private void executeBadCommand(long runId, int trialNum, float badRate) {
        ice.FlowRateObjective obj = new ice.FlowRateObjective();
        obj.newFlowRate = badRate;
        obj.unique_device_identifier = pumps.getSelectionModel().getSelectedItem().getUDI();
        flowRateWriter.write(obj, InstanceHandle_t.HANDLE_NIL);

        String label = badRate > 0 ? "BAD_CMD_OVER_RANGE" : "BAD_CMD_UNDER_RANGE";
        publishTrialMarker(runId, trialNum, "CMD_SENT", label, badRate, "rate=" + badRate);
        log.info("[TRIAL] runId={} trial={} {} rate={}", runId, trialNum, label, badRate);
    }

    // =========================================================================
    // TrialMarker publishing
    // =========================================================================

    /**
     * Publish a TrialMarker event to DDS. The DDSDataLoggerApp subscribes to
     * this topic and uses it to correlate actuator commands with observed
     * pump responses, enabling latency computation without any direct
     * coupling between the two apps.
     *
     * @param runId      Monotonic ID for this run (incremented each time Start is pressed)
     * @param trialNum   Trial number within the run (1-based)
     * @param eventType  RUN_START | TRIAL_START | CMD_SENT | RUN_COMPLETE | RUN_STOPPED
     * @param cmdType    The command type string (RATE_CHANGE, PAUSE, etc.)
     * @param value      The primary commanded value (rate, VTBI, etc.)
     * @param params     Additional key=value parameters for the logger
     */
    private void publishTrialMarker(long runId, int trialNum, String eventType,
                                    String cmdType, float value, String... params) {
        if (trialMarkerWriter == null) return;
        TrialMarker marker = new TrialMarker();
        marker.runId      = runId;
        marker.trialNum   = trialNum;
        marker.eventType  = eventType;
        marker.cmdType    = cmdType;
        marker.value      = value;
        marker.params     = params.length > 0 ? params[0] : "";
        marker.timestampMs = System.currentTimeMillis();
        trialMarkerWriter.write(marker, InstanceHandle_t.HANDLE_NIL);
    }

    // =========================================================================
    // Run completion
    // =========================================================================

    private void finishRun(long runId, int totalTrials) {
        if (!running.getAndSet(false)) return; // already stopped
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
            if (startButton != null) startButton.setDisable(isRunning || pumps.getSelectionModel().getSelectedItem() == null);
            if (stopButton  != null) stopButton.setDisable(!isRunning);
            if (cbRateChange   != null) cbRateChange.setDisable(isRunning);
            if (cbPauseResume  != null) cbPauseResume.setDisable(isRunning);
            if (cbVtbi         != null) cbVtbi.setDisable(isRunning);
            if (cbBolus        != null) cbBolus.setDisable(isRunning);
            if (cbBadCommands  != null) cbBadCommands.setDisable(isRunning);
            if (trialsSpinner  != null) trialsSpinner.setDisable(isRunning);
            if (intervalMsSpinner != null) intervalMsSpinner.setDisable(isRunning);
            if (pumps != null) pumps.setDisable(isRunning);
        });
    }

    private boolean anyCommandTypeSelected() {
        return (cbRateChange  != null && cbRateChange.isSelected())
            || (cbPauseResume != null && cbPauseResume.isSelected())
            || (cbVtbi        != null && cbVtbi.isSelected())
            || (cbBolus       != null && cbBolus.isSelected())
            || (cbBadCommands != null && cbBadCommands.isSelected());
    }

    /**
     * Build the ordered list of command types to cycle through, based on
     * which checkboxes are selected. Bad commands expand into two variants
     * (over-range and under-range) so both edge cases are tested.
     */
    private List<CommandType> buildCommandTypeList() {
        List<CommandType> types = new ArrayList<>();
        if (cbRateChange  != null && cbRateChange.isSelected())  types.add(CommandType.RATE_CHANGE);
        if (cbPauseResume != null && cbPauseResume.isSelected()) {
            types.add(CommandType.PAUSE);
            types.add(CommandType.RESUME);
        }
        if (cbVtbi        != null && cbVtbi.isSelected())        types.add(CommandType.VTBI_SET);
        if (cbBolus       != null && cbBolus.isSelected())       types.add(CommandType.BOLUS);
        if (cbBadCommands != null && cbBadCommands.isSelected()) {
            types.add(CommandType.BAD_CMD_OVER);
            types.add(CommandType.BAD_CMD_UNDER);
        }
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
        RATE_CHANGE, PAUSE, RESUME, VTBI_SET, BOLUS, BAD_CMD_OVER, BAD_CMD_UNDER
    }
}
