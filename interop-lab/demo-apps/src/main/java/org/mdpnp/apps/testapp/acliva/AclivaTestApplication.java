package org.mdpnp.apps.testapp.acliva;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.chart.Chart;
import org.mdpnp.apps.testapp.chart.DateAxis;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.patient.PatientInfo;
import org.mdpnp.apps.testapp.vital.Vital;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.apps.testapp.vital.VitalModelImpl;
import org.mdpnp.apps.testapp.vital.VitalSign;
import org.mdpnp.devices.AbstractDevice;
import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.DeviceDriverProvider;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSEvent;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSListener;
import org.mdpnp.devices.MDSHandler.Patient.PatientEvent;
import org.mdpnp.devices.MDSHandler.Patient.PatientListener;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.sql.SQLLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.SubscriberQos;

import ice.FlowRateObjective;
import ice.FlowRateObjectiveDataWriter;
import ice.InfusionObjective;
import ice.InfusionObjectiveDataWriter;
import ice.InfusionProgram;
import ice.InfusionProgramDataWriter;
import ice.MDSConnectivity;
import ice.Patient;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Callback;
import javafx.util.Duration;
import javafx.util.StringConverter;
import javafx.util.converter.NumberStringConverter;

public class AclivaTestApplication {

    private ApplicationContext parentContext;
	private Subscriber assignedSubscriber;
	private DeviceListModel dlm;
	private NumericFxList numeric;
	private SampleArrayFxList samples;
	private FlowRateObjectiveDataWriter writer;
	private InfusionObjectiveDataWriter infusionObjectiveWriter;
	private InfusionProgramDataWriter infusionProgramWriter;
	private MDSHandler mdsHandler;
	private VitalModel vitalModel;
	private EMRFacade emr;

    // ── FXML injected fields ────────────────────────────────────────────────

    @FXML private Spinner<Integer> ageSpinner;
    @FXML private Spinner<Integer> weightSpinner;
    @FXML private Spinner<Integer> heightSpinner;
    @FXML private ComboBox<String> sexCombo;

    @FXML private ComboBox<Device> pumps;

    @FXML private Label lbmLabel;
    @FXML private Label v1Label;
    @FXML private Label v2Label;
    @FXML private Label cl1Label;

    @FXML private Spinner<Integer> maintenanceSpinner;

    @FXML private ToggleGroup speedGroup;
    @FXML private ToggleButton speed1x;
    @FXML private ToggleButton speed2x;
    @FXML private ToggleButton speed4x;
    @FXML private ToggleButton speed10x;
    @FXML private ToggleButton speedMax;

    @FXML private TextArea fixedParamsArea;

    @FXML private Button runButton;
    @FXML private Button resetButton;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Button bolusButton;

    @FXML private Label bisValueLabel;
    @FXML private Label rateValueLabel;
    @FXML private Label ceValueLabel;
    @FXML private Label phaseValueLabel;
    @FXML private Label elapsedValueLabel;
    @FXML private Label speedValueLabel;
    @FXML private Label statusPillLabel;

    @FXML private StackPane bisChartPane;
    @FXML private Canvas bisCanvas;
    @FXML private Label bisPlaceholder;

    @FXML private StackPane rateChartPane;
    @FXML private Canvas rateCanvas;
    @FXML private Label ratePlaceholder;

    private final String FLOW_RATE=rosetta.MDC_FLOW_FLUID_PUMP.VALUE;

    // ── Simulation state ────────────────────────────────────────────────────

    // Data lists written by simulation thread, read by FX thread for drawing.
    // Access pattern: simulation thread appends; FX thread reads for repaint.
    // No concurrent structural modifications occur simultaneously, so ArrayList
    // is safe here (one writer, reads only happen during Platform.runLater).
    private final List<double[]> bisData  = new ArrayList<>();
    private final List<double[]> rateData = new ArrayList<>();

    private Thread          simulationThread = null;
    private volatile int    speedMultiplier  = 1;

    // Repaint throttle timestamps (milliseconds)
    private volatile long lastRepaintMs = 0;
    private volatile long lastStatusMs  = 0;
    private static final long REPAINT_INTERVAL_MS = 100;

    // Initialization with required application context and data providers
    public void set(ApplicationContext parentContext, DeviceListModel dlm, NumericFxList numeric, SampleArrayFxList samples,
			FlowRateObjectiveDataWriter writer, InfusionObjectiveDataWriter infusionObjectiveWriter, InfusionProgramDataWriter infusionProgramWriter, MDSHandler mdsHandler, VitalModel vitalModel, Subscriber subscriber, EMRFacade emr) {
		this.parentContext=parentContext;
		this.dlm=dlm;
		this.numeric=numeric;
		this.samples=samples;
		this.writer=writer;
		this.infusionObjectiveWriter = infusionObjectiveWriter;
		this.infusionProgramWriter = infusionProgramWriter;
		this.mdsHandler=mdsHandler;
		this.vitalModel=vitalModel;
		this.assignedSubscriber=subscriber;
		this.emr=emr;
	}

    // ── Initialisation ──────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        // Combo box
        sexCombo.setItems(FXCollections.observableArrayList("Male", "Female"));
        sexCombo.getSelectionModel().select("Male");

        // Spinners
        ageSpinner.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 120, 45));
        weightSpinner.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 300, 90));
        heightSpinner.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(50, 250, 180));
        maintenanceSpinner.setValueFactory(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 300, 60));

        // Fixed parameters read-only text area
        fixedParamsArea.setText(
            "Controller    ACLIVA PID (Liu 2011)\n" +
            "Kp            0.8\n"                   +
            "Ki            0.02\n"                  +
            "Kd            0 (disabled)\n"          +
            "BIS target    50\n"                    +
            "Dead band     \u00b15 BIS units (45-55)\n" +
            "Induction     120 mL/hr fixed (2 min)\n" +
            "Maint rate    60 mL/hr (on) / 0 (off)\n" +
            "Drug          Propofol 1%\n"           +
            "Concentration 10 mg/mL");
        fixedParamsArea.setEditable(false);
        fixedParamsArea.setFocusTraversable(false);

        // Button actions
        runButton.setOnAction(e -> runSimulation());
        resetButton.setOnAction(e -> resetSimulation());
        pauseButton.setOnAction(e -> sendInfusionObjective(true));
        resumeButton.setOnAction(e -> sendInfusionObjective(false));
        bolusButton.setOnAction(e -> sendBolus());

        // Speed toggle buttons
        speed1x.setOnAction(e  -> { speedMultiplier = 1;  updateSpeedStyle(); });
        speed2x.setOnAction(e  -> { speedMultiplier = 2;  updateSpeedStyle(); });
        speed4x.setOnAction(e  -> { speedMultiplier = 4;  updateSpeedStyle(); });
        speed10x.setOnAction(e -> { speedMultiplier = 10; updateSpeedStyle(); });
        speedMax.setOnAction(e -> { speedMultiplier = 0;  updateSpeedStyle(); });
        updateSpeedStyle();

        // Patient field change listeners — recompute Schnider and redraw axes
        ChangeListener<Object> recalcListener = (obs, oldVal, newVal) -> recomputeSchnider();
        ageSpinner.valueProperty().addListener(recalcListener);
        weightSpinner.valueProperty().addListener(recalcListener);
        heightSpinner.valueProperty().addListener(recalcListener);
        sexCombo.valueProperty().addListener(recalcListener);

        // Maintenance duration change — just redraw chart axes
        maintenanceSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            drawBisChart(bisData);
            drawRateChart(rateData);
        });

        // Bind canvas size to pane size and redraw on resize
        bisCanvas.widthProperty().bind(bisChartPane.widthProperty());
        bisCanvas.heightProperty().bind(bisChartPane.heightProperty());
        bisCanvas.widthProperty().addListener((obs, o, n)  -> drawBisChart(bisData));
        bisCanvas.heightProperty().addListener((obs, o, n) -> drawBisChart(bisData));

        rateCanvas.widthProperty().bind(rateChartPane.widthProperty());
        rateCanvas.heightProperty().bind(rateChartPane.heightProperty());
        rateCanvas.widthProperty().addListener((obs, o, n)  -> drawRateChart(rateData));
        rateCanvas.heightProperty().addListener((obs, o, n) -> drawRateChart(rateData));

        recomputeSchnider();

        pumps.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            boolean pumpSelected = newValue != null;
            runButton.setDisable(!pumpSelected);
            pauseButton.setDisable(!pumpSelected);
            resumeButton.setDisable(!pumpSelected);
            bolusButton.setDisable(!pumpSelected);
        });

        runButton.setDisable(true);
        pauseButton.setDisable(true);
        resumeButton.setDisable(true);
        bolusButton.setDisable(true);

        Platform.runLater(() -> {
            drawBisChart(bisData);
            drawRateChart(rateData);
        });
    }

    // ── Speed toggle styling ────────────────────────────────────────────────

    private void updateSpeedStyle() {
        ToggleButton[] btns = { speed1x, speed2x, speed4x, speed10x, speedMax };
        for (ToggleButton btn : btns) {
            if (btn.isSelected()) {
                btn.setStyle("-fx-background-color: #0D1B3E; -fx-text-fill: white;");
                speedValueLabel.setText(btn.getText());
            } else {
                btn.setStyle("-fx-background-color: white; -fx-text-fill: grey;");
            }
        }
    }

    // ── Schnider PK computation ─────────────────────────────────────────────

    private void recomputeSchnider() {
        int    age    = ageSpinner.getValue();
        int    weight = weightSpinner.getValue();
        int    height = heightSpinner.getValue();
        String sex    = sexCombo.getValue();

        double lbm = "Male".equals(sex)
            ? 1.1  * weight - 128.0 * Math.pow((double) weight / height, 2)
            : 1.07 * weight - 148.0 * Math.pow((double) weight / height, 2);

        double v1  = 4.27;
        double v2  = 18.9 - 0.391 * (age - 53);
        double cl1 = 1.89 + 0.0456 * (weight - 77)
                          - 0.0681 * (lbm    - 59)
                          + 0.0264 * (height - 177);

        lbmLabel.setText(String.format("%.2f", lbm));
        v1Label .setText(String.format("%.2f", v1));
        v2Label .setText(String.format("%.2f", v2));
        cl1Label.setText(String.format("%.2f", cl1));

        drawBisChart(bisData);
        drawRateChart(rateData);
    }

    // ── Public data-append methods (FX thread only) ─────────────────────────

    /**
     * Append a BIS data point and repaint. Call only from the FX thread.
     */
    public void addBisPoint(double timeMin, double bis) {
        bisData.add(new double[]{ timeMin, bis });
        drawBisChart(bisData);
    }

    /**
     * Append a rate data point and repaint. Call only from the FX thread.
     */
    public void addRatePoint(double timeMin, double rate) {
        rateData.add(new double[]{ timeMin, rate });
        drawRateChart(rateData);
    }

    /**
     * Update all status bar labels. Call only from the FX thread.
     */
    public void updateStatus(double bis, double rate, double ce,
                             String phase, String elapsed) {
        bisValueLabel  .setText(String.format("%.1f", bis));
        rateValueLabel .setText(String.format("%.1f", rate));
        ceValueLabel   .setText(String.format("%.2f", ce));
        phaseValueLabel.setText(phase);
        elapsedValueLabel.setText(elapsed);
    }

    public void start(EventLoop eventLoop, Subscriber subscriber) {

    //Rely on addition of metrics to add devices...
		numeric.addListener(new ListChangeListener<NumericFx>() {   
			@Override
			public void onChanged(Change<? extends NumericFx> change) {
                System.err.println("!!!Inside onChanged - Step 2");
				while(change.next()) {
                    System.err.println("!!!Inside change.next");
					change.getAddedSubList().forEach( n -> {
                        System.err.println("!!!!!!!!Trying to print metric id");
                        System.err.println(n.getMetric_id());
						if(n.getMetric_id().equals(FLOW_RATE)) {
							pumps.getItems().add(dlm.getByUniqueDeviceIdentifier(n.getUnique_device_identifier()));
						}

					});
				}
			}
		});
		
		//...and removal of devices to remove devices.
		dlm.getContents().addListener(new ListChangeListener<Device>() {
			@Override
			public void onChanged(Change<? extends Device> change) {
				while(change.next()) {
					change.getRemoved().forEach( d-> {
						pumps.getItems().remove(d);
					});
				}
			}
		});

        pumps.setCellFactory(new Callback<ListView<Device>,ListCell<Device>>() {

			@Override
			public ListCell<Device> call(ListView<Device> device) {
				return new DeviceListCell();
			}
			
		});
		
		pumps.setConverter(new StringConverter<Device>() {

			@Override
			public Device fromString(String arg0) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public String toString(Device device) {
				// TODO Auto-generated method stub
				return device.getModel()+"("+device.getComPort()+")";
			}
			
		});

   }
   
    NumericFx[] flowRateFromSelectedPump=new NumericFx[1];
    
    // ── Simulation control ──────────────────────────────────────────────────
    private void runSimulation() {
        if (simulationThread != null && simulationThread.isAlive()) {
            return; // Already running — ignore
        }

        bisData.clear();
        rateData.clear();
        lastRepaintMs = 0;
        lastStatusMs  = 0;

        Platform.runLater(() -> {
            statusPillLabel.setText("\u25cf Running");
            runButton.setDisable(true);
            drawBisChart(bisData);
            drawRateChart(rateData);
        });

        final int    age             = ageSpinner.getValue();
        final int    weight          = weightSpinner.getValue();
        final int    height          = heightSpinner.getValue();
        final String sex             = sexCombo.getValue();
        final int    maintenanceMins = maintenanceSpinner.getValue();

        // Create session via factory — swap AclivaSessionFactory.createSession()
        // to switch between simulation and real hardware. Nothing else changes.
        final AclivaSessionFactory.AclivaSession session =
            AclivaSessionFactory.createSession(age, weight, height, sex);
        final BisSource   bisSource   = session.bisSource;
        final PumpAdapter pumpAdapter = session.pumpAdapter;
        
        Device pump=pumps.getSelectionModel().getSelectedItem();

        // ACLIVA closed-loop PI controller (Liu 2011).
        // Sees ONLY the noisy BIS output — no model internals.
        final AclivaController controller = new AclivaController(0.8, 0.02, 0.0, 50.0);

        final double INDUCTION_RATE   = 120.0;
        final double MAINTENANCE_RATE = pumpAdapter.getMaxRate() * 0.5; // 60 mL/hr

        simulationThread = new Thread(() -> {
            final double dt           = AclivaSessionFactory.DT_MINUTES; // 1 second
            final double inductionEnd = 2.0;
            final double maintEnd     = inductionEnd + maintenanceMins;
            final double simEnd       = maintEnd + 10.0;

            double  timeMin           = 0.0;
            double  currentBis        = 93.0;
            double  currentRate       = 0.0;   // FIX: running rate accumulated by PID
            boolean inductionComplete = false;

            try {
                while (!Thread.currentThread().isInterrupted() && timeMin <= simEnd) {

                    final String phase;

                    if (timeMin < inductionEnd) {
                        // Fixed induction bolus — no feedback
                        phase = "Induction";
                        pumpAdapter.setRate(INDUCTION_RATE);        // FIX: keeps simulator in sync
                        setFlowRate((float)(INDUCTION_RATE));       // also commands real pump via DDS

                    } else if (timeMin < maintEnd) {
                        if (!inductionComplete) {
                            inductionComplete = true;
                            controller.reset();
                            currentRate = MAINTENANCE_RATE;         // FIX: seed running rate
                        }
                        phase = "Maintenance";
                        // Dead band ±5 BIS units — ignore noise, prevent micro-switching
                        double bisError = currentBis - 50.0;
                        if (Math.abs(bisError) > 5.0) {
                            // FIX: accumulate PID delta onto running rate (was sign-check only)
                            double delta = controller.calculateDeltaRate(currentBis, dt);
                            currentRate += delta;
                            currentRate = Math.max(pumpAdapter.getMinRate(),
                                         Math.min(pumpAdapter.getMaxRate(), currentRate));
                            pumpAdapter.setRate(currentRate);       // FIX: keeps simulator in sync
                            setFlowRate((float) currentRate);       // also commands real pump via DDS
                        }
                        // Inside dead band — hold current rate unchanged

                    } else {
                        phase = "Recovery";
                        pumpAdapter.setRate(0.0);                   // FIX: keeps simulator in sync
                        setFlowRate((float)(0.0));                  // also commands real pump via DDS
                    }

                    numeric.forEach( n -> {
                    	if( n.getUnique_device_identifier().equals(pump.getUDI()) && n.getMetric_id().equals(FLOW_RATE)) {
                    		flowRateFromSelectedPump[0]=n;
                    	}
                    });
                    // Advance patient model (sim) or read real monitor (live)
                    currentBis = bisSource.getBIS();
                    final double ce          = bisSource.getCe();
                    final double displayRate = pumpAdapter.getLastCommandedRate(); // FIX: was reading DDS numeric (always stale/zero)

                    final double t = timeMin;
                    final double b = currentBis;
                    final double r = displayRate;
                    final double c = ce;
                    final String p = phase;

                    bisData .add(new double[]{ t, b });
                    rateData.add(new double[]{ t, r });

                    long now = System.currentTimeMillis();
                    if (now - lastRepaintMs >= REPAINT_INTERVAL_MS) {
                        lastRepaintMs = now;
                        Platform.runLater(() -> {
                            drawBisChart(bisData);
                            drawRateChart(rateData);
                        });
                    }

                    if (now - lastStatusMs >= REPAINT_INTERVAL_MS) {
                        lastStatusMs = now;
                        int    m       = (int) t;
                        int    s       = (int) ((t - m) * 60);
                        String elapsed = String.format("%d:%02d", m, s);
                        Platform.runLater(() -> updateStatus(b, r, c, p, elapsed));
                    }

                    int mult = speedMultiplier;
                    if (mult > 0) {
                        long sleepMs = (long) (dt * 60.0 * 1000.0 / mult);
                        if (sleepMs > 0) Thread.sleep(sleepMs);
                    }

                    timeMin += dt;
                }
                
                numeric.forEach( n -> {
                	if( n.getUnique_device_identifier().equals(pump.getUDI()) && n.getMetric_id().equals(FLOW_RATE)) {
                		flowRateFromSelectedPump[0]=n;
                	}
                });

                final double finalBis  = currentBis;
                final double finalRate = pumpAdapter.getLastCommandedRate(); // FIX: was reading DDS numeric
                final double finalCe   = bisSource.getCe();
                final int    fm        = (int) timeMin;
                final int    fs        = (int) ((timeMin - fm) * 60);
                final String finalElapsed = String.format("%d:%02d", fm, fs);

                Platform.runLater(() -> {
                    drawBisChart(bisData);
                    drawRateChart(rateData);
                    updateStatus(finalBis, finalRate, finalCe, "Complete", finalElapsed);
                    statusPillLabel.setText("\u25cf Complete");
                    runButton.setDisable(false);
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        simulationThread.setDaemon(true);
        simulationThread.start();
    }

    private void resetSimulation() {
        if (simulationThread != null) {
            simulationThread.interrupt();
            simulationThread = null;
        }

        bisData.clear();
        rateData.clear();
        lastRepaintMs = 0;
        lastStatusMs  = 0;

        Platform.runLater(() -> {
            bisValueLabel   .setText("\u2014");
            rateValueLabel  .setText("\u2014");
            ceValueLabel    .setText("\u2014");
            phaseValueLabel .setText("\u2014");
            elapsedValueLabel.setText("0:00");
            statusPillLabel .setText("\u25cf Idle");
            runButton.setDisable(false);

            drawBisChart(bisData);
            drawRateChart(rateData);
        });
    }
    
    private void setFlowRate(float newRate) {
        Device selectedPump = pumps.getSelectionModel().getSelectedItem();
        if (selectedPump == null) return;

        FlowRateObjective objective = new FlowRateObjective();
        objective.unique_device_identifier = selectedPump.getUDI();
        objective.newFlowRate = newRate;
        writer.write(objective, InstanceHandle_t.HANDLE_NIL);
	}

    private void sendInfusionObjective(boolean stop) {
        Device selectedPump = pumps.getSelectionModel().getSelectedItem();
        if (selectedPump == null) return;

        InfusionObjective objective = new InfusionObjective();
        objective.unique_device_identifier = selectedPump.getUDI();
        objective.head = 1;
        objective.stopInfusion = stop;
        infusionObjectiveWriter.write(objective, InstanceHandle_t.HANDLE_NIL);
    }

    private void sendBolus() {
        Device selectedPump = pumps.getSelectionModel().getSelectedItem();
        if (selectedPump == null) return;

        InfusionProgram program = new InfusionProgram();
        program.unique_device_identifier = selectedPump.getUDI();
        program.head = 1;
        program.requestor = "AclivaTestApp";
        program.bolusRate = 200f;  // mL/hr
        program.bolusVolume = 5f;    // mL
        program.infusionRate = -1f; // Unchanged
        program.VTBI = -1f;         // Unchanged
        infusionProgramWriter.write(program, InstanceHandle_t.HANDLE_NIL);
    }

    // ── Chart drawing ───────────────────────────────────────────────────────

    private void drawBisChart(List<double[]> data) {
        GraphicsContext gc = bisCanvas.getGraphicsContext2D();
        double w = bisCanvas.getWidth();
        double h = bisCanvas.getHeight();

        gc.clearRect(0, 0, w, h);

        if (data.isEmpty()) {
            bisPlaceholder.setVisible(true);
            return;
        }
        bisPlaceholder.setVisible(false);

        double maxTime = maintenanceSpinner.getValue() + 12.0; // Induction + Maint + Recovery
        double maxBis  = 100.0;

        double leftMargin = 40, rightMargin = 20, topMargin = 30, bottomMargin = 30;
        double cw = w - leftMargin - rightMargin;
        double ch = h - topMargin - bottomMargin;

        // Background
        gc.setFill(Color.web("#F8F9FA"));
        gc.fillRect(leftMargin, topMargin, cw, ch);

        // Induction phase background
        double inductionEndPos = (2.0 / maxTime) * cw;
        gc.setFill(Color.web("#E8F5E9"));
        gc.fillRect(leftMargin, topMargin, inductionEndPos, ch);

        // Maintenance phase background
        double maintStartPos = inductionEndPos;
        double maintEndPos   = ((2.0 + maintenanceSpinner.getValue()) / maxTime) * cw;
        gc.setFill(Color.web("#FFFDE7"));
        gc.fillRect(leftMargin + maintStartPos, topMargin, maintEndPos - maintStartPos, ch);

        // Grid lines
        gc.setStroke(Color.web("#E0E0E0"));
        gc.setLineWidth(0.5);
        for (int i = 0; i <= 10; i++) { // Y-axis grid
            double y = topMargin + (i / 10.0) * ch;
            gc.strokeLine(leftMargin, y, leftMargin + cw, y);
        }
        for (int i = 0; i <= maxTime / 2; i++) { // X-axis grid (every 2 mins)
            double x = leftMargin + ((i * 2.0) / maxTime) * cw;
            gc.strokeLine(x, topMargin, x, topMargin + ch);
        }

        // Target BIS range (40-60)
        double y40 = topMargin + (1 - 40.0 / maxBis) * ch;
        double y60 = topMargin + (1 - 60.0 / maxBis) * ch;
        gc.setStroke(Color.web("#E53935"));
        gc.setLineDashes(5, 3);
        gc.strokeLine(leftMargin, y40, leftMargin + cw, y40);
        gc.strokeLine(leftMargin, y60, leftMargin + cw, y60);
        gc.setLineDashes(null);

        // Axes
        gc.setStroke(Color.web("#B0BEC5"));
        gc.setLineWidth(1.0);
        gc.strokeLine(leftMargin, topMargin, leftMargin, topMargin + ch); // Y-axis
        gc.strokeLine(leftMargin, topMargin + ch, leftMargin + cw, topMargin + ch); // X-axis

        // Axis ticks and labels
        gc.setStroke(Color.web("#90A4AE"));
        gc.setFill(Color.web("#37474F"));

        // Chart title
        gc.setFill(Color.web("#1C2B3A"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 13));
        gc.fillText("BIS Index", leftMargin + cw / 2 - 30, topMargin - 8);

        // Y-axis labels
        gc.save();
        gc.translate(leftMargin - 8, topMargin + ch / 2);
        gc.rotate(-90);
        gc.setFill(Color.web("#37474F"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        gc.fillText("BIS", -12, 0);
        gc.restore();

        gc.setFill(Color.web("#37474F"));
        gc.setFont(Font.font("System", 11));
        for (int i = 0; i <= 10; i++) {
            double y = topMargin + (i / 10.0) * ch;
            int bisVal = 100 - i * 10;
            if (bisVal % 20 == 0) {
                gc.strokeLine(leftMargin - 4, y, leftMargin, y);
                gc.fillText(String.valueOf(bisVal), leftMargin - 30, y + 4);
            }
        }

        // X-axis labels
        gc.setFill(Color.web("#37474F"));
        gc.setFont(Font.font("System", 11));
        for (int i = 0; i <= maxTime / 2; i++) {
            double x = leftMargin + ((i * 2.0) / maxTime) * cw;
            gc.strokeLine(x, topMargin + ch, x, topMargin + ch + 4);
            gc.fillText(String.valueOf(i * 2), x - (i > 0 ? 5 : 0), topMargin + ch + 18);
        }
        // ── X-axis label "Time (min)" centred below ───────────────────────
        gc.setFont(Font.font("System", 11));
        gc.fillText("Time (min)", leftMargin + cw / 2 - 28, topMargin + ch + 26);

        // Plot data
        gc.setStroke(Color.web("#1565C0"));
        gc.setLineWidth(1.5);
        gc.beginPath();
        boolean first = true;
        for (double[] point : data) {
            double x = leftMargin + (point[0] / maxTime) * cw;
            double y = topMargin + (1 - point[1] / maxBis) * ch;
            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void drawRateChart(List<double[]> data) {
        GraphicsContext gc = rateCanvas.getGraphicsContext2D();
        double w = rateCanvas.getWidth();
        double h = rateCanvas.getHeight();

        gc.clearRect(0, 0, w, h);

        if (data.isEmpty()) {
            ratePlaceholder.setVisible(true);
            return;
        }
        ratePlaceholder.setVisible(false);

        double maxTime = maintenanceSpinner.getValue() + 12.0;
        double maxRate = 150.0;

        double leftMargin = 40, rightMargin = 20, topMargin = 30, bottomMargin = 30;
        double cw = w - leftMargin - rightMargin;
        double ch = h - topMargin - bottomMargin;

        // Background
        gc.setFill(Color.web("#F8F9FA"));
        gc.fillRect(leftMargin, topMargin, cw, ch);

        // Grid lines
        gc.setStroke(Color.web("#E0E0E0"));
        gc.setLineWidth(0.5);
        for (int i = 0; i <= 6; i++) { // Y-axis grid
            double y = topMargin + (i / 6.0) * ch;
            gc.strokeLine(leftMargin, y, leftMargin + cw, y);
        }
        for (int i = 0; i <= maxTime / 2; i++) { // X-axis grid
            double x = leftMargin + ((i * 2.0) / maxTime) * cw;
            gc.strokeLine(x, topMargin, x, topMargin + ch);
        }

        // Axes
        gc.setStroke(Color.web("#B0BEC5"));
        gc.setLineWidth(1.0);
        gc.strokeLine(leftMargin, topMargin, leftMargin, topMargin + ch); // Y-axis
        gc.strokeLine(leftMargin, topMargin + ch, leftMargin + cw, topMargin + ch); // X-axis

        // Axis ticks and labels
        gc.setStroke(Color.web("#90A4AE"));
        gc.setFill(Color.web("#37474F"));

        // Chart title
        gc.setFill(Color.web("#1C2B3A"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 13));
        gc.fillText("Infusion Rate (mL/hr)", leftMargin + cw / 2 - 68, topMargin - 8);

        // Y-axis labels
        gc.save();
        gc.translate(leftMargin - 8, topMargin + ch / 2);
        gc.rotate(-90);
        gc.setFill(Color.web("#37474F"));
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        gc.fillText("mL/hr", -18, 0);
        gc.restore();

        gc.setFill(Color.web("#37474F"));
        gc.setFont(Font.font("System", 11));
        for (int i = 0; i <= 6; i++) {
            double y = topMargin + (i / 6.0) * ch;
            int rateVal = (int) (maxRate - i * (maxRate / 6.0));
            if (rateVal % 50 == 0) {
                gc.strokeLine(leftMargin - 4, y, leftMargin, y);
                gc.fillText(String.valueOf(rateVal), leftMargin - 30, y + 4);
            }
        }

        // X-axis labels
        gc.setFill(Color.web("#37474F"));
        gc.setFont(Font.font("System", 11));
        for (int i = 0; i <= maxTime / 2; i++) {
            double x = leftMargin + ((i * 2.0) / maxTime) * cw;
            gc.strokeLine(x, topMargin + ch, x, topMargin + ch + 4);
            gc.fillText(String.valueOf(i * 2), x - (i > 0 ? 5 : 0), topMargin + ch + 18);
        }
        // ── X-axis label "Time (min)" ─────────────────────────────────────
        gc.setFont(Font.font("System", 11));
        gc.fillText("Time (min)", leftMargin + cw / 2 - 28, topMargin + ch + 26);

        // Plot data
        gc.setStroke(Color.web("#00695C"));
        gc.setLineWidth(1.5);
        gc.beginPath();
        boolean first = true;
        for (double[] point : data) {
            double x = leftMargin + (point[0] / maxTime) * cw;
            double y = topMargin + (1 - point[1] / maxRate) * ch;
            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }
    
    private static class DeviceListCell extends ListCell<Device> {
        @Override
        protected void updateItem(Device device, boolean empty) {
            super.updateItem(device, empty);
            if (!empty && device != null) {
                setText(device.getModel()+"("+device.getComPort()+")");
            } else {
                setText(null);
            }
        }
    }
}