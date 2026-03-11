package org.mdpnp.apps.testapp.poclab;

import static org.mdpnp.apps.testapp.poclab.ASTMUtils.ASTMChecksum;
import static org.mdpnp.apps.testapp.poclab.ASTMUtils.ETX;
import static org.mdpnp.apps.testapp.poclab.ASTMUtils.STX;
import static org.mdpnp.apps.testapp.poclab.ASTMUtils.transmit;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.function.Predicate;

import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.patient.OpenEMRImpl;
import org.mdpnp.apps.testapp.patient.OpenEMRPatientInfo;
import org.mdpnp.apps.testapp.patient.PatientInfo;
import org.mdpnp.apps.testapp.poclab.ASTMServer.DataCallback;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSEvent;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSListener;
import org.mdpnp.devices.MDSHandler.Patient.PatientEvent;
import org.mdpnp.devices.MDSHandler.Patient.PatientListener;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.subscription.Subscriber;

import ice.MDSConnectivity;
import ice.Patient;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * An Abaxis Piccolo Xpress point-of-care lab device simulator...
 * 
 * @author Braga Aroulmozhi
 *
 */
public class PiccoloXpressSimulator {
	
	private static final Logger log = LoggerFactory.getLogger(PiccoloXpressSimulator.class);

	private EMRFacade emr;
	private OpenEMRImpl openEMR;

	/**
	 * The "current" patient, used to pass back to the EMRFacade to get encounters
	 * etc.
	 */
	private Patient currentPatient;

	private MDSHandler mdsHandler;

	@FXML
	private ComboBox<String> openEMROrders;

	public Button startButton;
	public TextField mdsIDField;

	@FXML
	Label instrumentqcLabel, chemistryqcLabel, hemolysisLabel, lipemiaLabel;

	@FXML
	CheckBox notfastedLabel, qcfailurelabel, diabetesLabel, renalfailureLabel;
	
	final TextField astmHostInput=new TextField("localhost");	//Default value
	final TextField astmPortInput=new TextField("1182");		//Default value
	
	@FXML
	TextField localServerPortNumber;
	
	@FXML
	VBox resultsHolderBox;
	
	@FXML
	VBox exportHolderBox;
	
	@FXML
	VBox labOrdersHolder;
	
	/*
	 * For all the checkboxes above, we create a separate, accessible property
	 * that we can then use from the test for this class to manipulate the status,
	 * without needing the actual controls to exist.  More importantly we can also
	 * check the property in the code without the label existing.
	 */
	final SimpleBooleanProperty noFastedProperty=new SimpleBooleanProperty();
	final SimpleBooleanProperty qcFailureProperty=new SimpleBooleanProperty();
	final SimpleBooleanProperty diabetesProperty=new SimpleBooleanProperty();
	final SimpleBooleanProperty renalFailureProperty=new SimpleBooleanProperty();
	
	/*
	 * For all the labels above, we create a separate, accessible property
	 * that we can then use from the test for this class to manipulate the text,
	 * without needing the actual controls to exist.  This allows the test to run
	 * headless, because the code in this class can set the property, and if the
	 * label is null, the bind does nothing.
	 */
	final SimpleStringProperty instrumentqcLabelText=new SimpleStringProperty();
	final SimpleStringProperty chemistryqcLabelText=new SimpleStringProperty();
	final SimpleStringProperty hemolysisLabelText=new SimpleStringProperty();
	final SimpleStringProperty lipemiaLabelText=new SimpleStringProperty();

	@FXML
	TextArea orderContents;

	@FXML
	VBox main;
	
	private ToggleGroup exportTargetsGroup;
	private RadioButton openEMROption;
	private RadioButton piccoloOption;
	
	private final PiccoloResults piccoloResults=new PiccoloResults();

	final PiccoloResultModel bunModel=piccoloResults.getBunModel();
	final PiccoloResultModel potassiumModel=piccoloResults.getPotassiumModel();
	final PiccoloResultModel creatinineModel=piccoloResults.getCreatinineModel();
	final PiccoloResultModel glucoseModel=piccoloResults.getGlucoseModel();
	final PiccoloResultModel sodiumModel=piccoloResults.getSodiumModel();
	final PiccoloResultModel co2Model=piccoloResults.getCo2Model();
	final PiccoloResultModel chlorideModel=piccoloResults.getChlorideModel();
	final PiccoloResultModel calciumModel=piccoloResults.getCalciumModel();
	final PiccoloResultModel alpModel=piccoloResults.getAlpModel();
	final PiccoloResultModel altModel=piccoloResults.getAltModel();
	final PiccoloResultModel astModel=piccoloResults.getAstModel();
	final PiccoloResultModel bilirubinModel=piccoloResults.getBilirubinModel();
	final PiccoloResultModel albuminModel=piccoloResults.getAlbuminModel();
	final PiccoloResultModel proteinModel=piccoloResults.getProteinModel();
	
	final ObservableList<PiccoloResultModel> allMeasurements=piccoloResults.getAllMeasurements();
	
	private ASTMServer astmServer;
	
	public void set(EMRFacade emr, MDSHandler mdsHandler) {
		this.emr = emr;
		if (emr instanceof OpenEMRImpl) {
			openEMR = (OpenEMRImpl) emr;
		}
		this.mdsHandler = mdsHandler;
	}

	public void start(EventLoop eventLoop, Subscriber subscriber) {
		
		/*
		 * If the openEMR facade is not valid, hide the EMR related controls
		 */
		main.getChildren().remove(labOrdersHolder);
		
		qcfailurelabel.selectedProperty().bindBidirectional(qcFailureProperty);
		notfastedLabel.selectedProperty().bindBidirectional(noFastedProperty);
		diabetesLabel.selectedProperty().bindBidirectional(diabetesProperty);
		renalfailureLabel.selectedProperty().bindBidirectional(renalFailureProperty);
		
		instrumentqcLabel.textProperty().bindBidirectional(instrumentqcLabelText);
		chemistryqcLabel.textProperty().bindBidirectional(chemistryqcLabelText);
		hemolysisLabel.textProperty().bindBidirectional(hemolysisLabelText);
		lipemiaLabel.textProperty().bindBidirectional(lipemiaLabelText);
		
		mdsHandler.addPatientListener(new PatientListener() {

			@Override
			public void handlePatientChange(PatientEvent evt) {
				ice.Patient icePatient = (ice.Patient) evt.getSource();
				//System.err.println("PiccoloXpressSimulator handlePatientChange mrn is " + icePatient.mrn);
				refreshOrderListAndCache();
			}

		});

		mdsHandler.addConnectivityListener(new MDSListener() {

			@Override
			public void handleConnectivityChange(MDSEvent evt) {
				ice.MDSConnectivity c = (MDSConnectivity) evt.getSource();
//		        System.err.println("CLC.handleConnectivity Partition is "+c.partition);

				String mrnPartition = PartitionAssignmentController.findMRNPartition(c.partition);

				if (mrnPartition != null) {
					// log.info("udi " + c.unique_device_identifier + " is MRN=" + mrnPartition);

					Patient p = new Patient();
					p.mrn = PartitionAssignmentController.toMRN(mrnPartition);

					if (currentPatient == null) {
						/*
						 * The patient has definitely changed - even if the selected patient is
						 * "Unassigned", then that "Patient" has an ID
						 */
						currentPatient = p;

						//System.err.println("PiccoloXpressSimulator new current patient is is mrn " + p.mrn);
						refreshOrderListAndCache();
						return; // Nothing else to do.
					}
					if (!currentPatient.mrn.equals(p.mrn)) {
						//System.err.println("PiccoloXpressSimulator new current patient is mrn " + p.mrn);
						// Patient has changed
						currentPatient = p;
						// Get encounters for the selected patient
						refreshOrderListAndCache();
					}
				}
			}
		});

		initPiccoloTable();

	}

	public void activate() {
		// TODO Auto-generated method stub

	}

	public void stop() {
		// TODO Auto-generated method stub

	}

	public void destroy() {
		// TODO Auto-generated method stub

	}

	public void startProcess() {
//		System.err.println("We just created a new sample");
		// Check for QC Failure
		if (qcFailureProperty.get()) {
			instrumentqcLabelText.set("Instrument QC: FAIL");
			chemistryqcLabelText.set("Chemistry QC: FAIL");
			hemolysisLabelText.set("");
			lipemiaLabelText.set("");
			return;
		} else {
			instrumentqcLabelText.set("Instrument QC: OK");
			chemistryqcLabelText.set("Chemistry QC: OK");
		}

		// HEM value
		float hemoIndex = randomValueGenerator(0f, 20f);
		hemolysisLabelText.set("Hemolysis Index: " + Float.toString(hemoIndex));

		// Check for patient fasted status
		float lipemiaIndex = 0f;
		if (noFastedProperty.get()) {
			lipemiaIndex = randomValueGenerator(35f, 100f);
			lipemiaLabelText.set("Lipemia Index: " + Float.toString(lipemiaIndex) + " (Moderate - High)");
		} else {
			lipemiaIndex = randomValueGenerator(1f, 35f);
			lipemiaLabelText.set("Lipemia Index: " + Float.toString(lipemiaIndex) + " (Normal)");
		}

		// Check for renal failure
		float BUN = 0f;
		float creatinine = 0f;
		float potassium = 0f;
		if (renalFailureProperty.get()) {
			BUN = randomValueGenerator(22f, 50f);
			bunModel.setValue(BUN);
			potassium = randomValueGenerator(5.1f, 12f);
			potassiumModel.setValue(potassium);
			creatinine = randomValueGenerator(1.2f, 3f);
			creatinineModel.setValue(creatinine);
		} else {
			BUN = randomValueGenerator(7f, 22f);
			bunModel.setValue(BUN);
			potassium = randomValueGenerator(3.6f, 5.1f);
			potassiumModel.setValue(potassium);
			creatinine = randomValueGenerator(0.6f, 1.2f);
			creatinineModel.setValue(creatinine);
		}

		// Check for Diabetes
		float glucose = 0f;
		if (diabetesProperty.get()) {
			glucose = randomValueGenerator(120f, 170f);
			glucoseModel.setValue(glucose);
		} else {
			glucose = randomValueGenerator(73f, 118f);
			glucoseModel.setValue(glucose);
		}

		// Set other result values
		float sodium = randomValueGenerator(128f, 145f);
		sodiumModel.setValue(sodium);

		float co2 = randomValueGenerator(18f, 33f);
		co2Model.setValue(co2);

		float chloride = randomValueGenerator(98f, 108f);
		chlorideModel.setValue(chloride);

		float calcium = randomValueGenerator(8.0f, 10.3f);
		calciumModel.setValue(calcium);

		/*
		 * SK Must have missed this! float gfr = randomValueGenerator(70f, 100f);
		 * gfrLabel.setText(Float.toString(gfr));
		 */

		float alp = randomValueGenerator(42f, 141f);
		alpModel.setValue(alp);

		float alt = randomValueGenerator(10f, 47f);
		altModel.setValue(alt);

		float ast = randomValueGenerator(11f, 38f);
		astModel.setValue(ast);

		float bilirubin = randomValueGenerator(0.2f, 1.6f);
		bilirubinModel.setValue(bilirubin);

		float albumin = randomValueGenerator(3.3f, 5.5f);
		albuminModel.setValue(albumin);

		float protein = randomValueGenerator(6.4f, 8.1f);
		proteinModel.setValue(protein);

		/*
		 * List of variables to potentially push to EMR:
		 * 
		 * sodium potassium co2 chloride glucose calcium BUN creatinine gfr alp alt ast
		 * bilirubin albumin protein
		 * 
		 * hemoIndex lipemiaIndex
		 */
	}
	
	public void resetScreen() {
		allMeasurements.forEach( measurement -> {
			measurement.abnormalProperty().set("");
			measurement.valueProperty().set(-1f);
		});
		noFastedProperty.set(false);
		qcFailureProperty.set(false);
		diabetesProperty.set(false);
		renalFailureProperty.set(false);
		
		instrumentqcLabelText.set("Instrument QC: ");
		chemistryqcLabelText.set("Chemistry QC: ");
		hemolysisLabelText.set("Hemolysis Index: ");
		lipemiaLabelText.set("Lipemia Index: ");
	}

	public static float randomValueGenerator(float min, float max) {
		float randFloat = (float) (Math.round((min + (max - min) * Math.random()) * 10.0) / 10.0);

		return randFloat;

	}

	public void checkForOrders() {
		//System.err.println("Need to check for orders...");
		if (openEMR == null || !openEMR.isValid()) {
			return; // We can't do anything.
		}
		try {
			ArrayList<String> orders = openEMR.getCurrentOrders();
			orders.forEach(s -> {
				filterAndAddOrder(s);
			});
		} catch (Exception e) {
			// TODO Auto-generated catch block
			log.error("Failed to get current orders", e);
		}
	}

	private void refreshOrderListAndCache() {
		if (openEMR == null || !openEMR.isValid()) {
			return; // We can't do anything.
		}
		try {
			// Clear the current list
			ordersForPatient.clear();
			ArrayList<String> orders = openEMR.getCurrentOrders();
			//System.err.println("refreshOrderListAndCache has " + orders.size() + " orders");
			orders.forEach(s -> {
				filterAndAddOrder(s);
			});
		} catch (Exception e) {
			log.error("Error while refreshing order list for patient", e);
		}
	}

	private Hashtable<String, String> ordersForPatient = new Hashtable<>();

	private void filterAndAddOrder(String filename) {
		try {
			if (openEMR == null || !openEMR.isValid()) {
				return; // We can't do anything.
			}
			String thisOrder = openEMR.getOrderContents(filename);
			String[] lines = thisOrder.split("[\r\n]");
			//System.err.println("order " + filename + " has " + lines.length + " lines");
			for (String line : lines) {
				// Do better parsing later...
				if (line.startsWith("PID|")) {
					//System.err.println("PID Line is " + line);
					String[] segments = line.split("\\|");
					if (currentPatient.mrn.equals(segments[2])) {
						ordersForPatient.put(filename, thisOrder);
						openEMROrders.getItems().add(filename);
					}
				}
			}
		} catch (Exception e) {
			log.error("Error while getting contents of order",e);
		}

	}

	/**
	 * We need this to get the contents, to preview which order is which.
	 * 
	 * @return
	 */
	public void viewSelectedOrder() {
		if (openEMR == null || !openEMR.isValid()) {
			return; // We can't do anything.
		}
		String filename = openEMROrders.getSelectionModel().getSelectedItem();
		if(filename==null) {
			Alert noOrder=new Alert(AlertType.ERROR, "No order is selected", new ButtonType[] {ButtonType.OK});
			noOrder.show();
			return;
		}
		String contents = ordersForPatient.get(filename);
		//System.err.println("Piccolo got order contents as " + contents);
		String[] lines = contents.split("[\r\n]");
		StringBuilder sb = new StringBuilder();
		String newLine = System.getProperty("line.separator");
		for (String line : lines) {
			sb.append(line).append(newLine);
		}
		orderContents.setText(sb.toString());
	}

	private OpenEMRPatientInfo getCurrentPatientInfo() {
		ObservableList<PatientInfo> patientInfos = emr.getPatients();
		FilteredList<PatientInfo> onlyPatient = patientInfos.filtered(new Predicate<PatientInfo>() {

			@Override
			public boolean test(PatientInfo t) {
				if (t.getMrn().equals(currentPatient.mrn)) {
					return true;
				}
				return false;
			}

		});
		PatientInfo pi = onlyPatient.get(0);
		//System.err.println("OpenEMRDataTransfer current patient is " + pi.toString());
		return (OpenEMRPatientInfo) pi;
	}

	public void createHL7ResultsForOpenEMR() {
		
		//When this has something like MDPNP-0012 for the order number, we need to replace MDPNP- so that the order number is numeric.

		try {
			//We are not far away from being able to not need this template - we should be switching to APIs soon.
			BufferedInputStream bis = new BufferedInputStream(
					PiccoloXpressSimulator.class.getResourceAsStream("piccolo-hl7-template.unix"));
			String result = new String(bis.readAllBytes());
//			final String result=new String(templateSource);
			String filename = openEMROrders.getSelectionModel().getSelectedItem();
			if(filename==null) {
				Alert noOrder=new Alert(AlertType.ERROR, "There is no order selected so a result cannot be exported",new ButtonType[] {ButtonType.OK});
				noOrder.show();
				return;
			}
			String contents = ordersForPatient.get(filename);
			//System.err.println("Piccolo got order contents as " + contents);
			String[] lines = contents.split("[\r\n]");
			String pidLine = null, pv1Line = null, obrLine = null;
			//We need a date in a couple of places, so use the same one.
			Date reportDate=new Date();
			SimpleDateFormat sdf = new SimpleDateFormat("YYYYMMddHHmmss:SSS");
			for (String line : lines) {
				if (line.startsWith("PID|")) {
					pidLine = line;
					log.debug("pidLine is "+pidLine);
					//System.err.println("Got pidLine "+pidLine);
				}
				if (line.startsWith("PV1")) {
					pv1Line = line;
					log.debug("pv1Line is "+pv1Line);
					//System.err.println("Got pv1Line "+pv1Line);
				}
				if (line.startsWith("OBR")) {
					log.debug("Initial obrLine is "+line);
					String[] parts=line.split("\\|");
					//Remove everything except numbers from the order field - OpenEMR expects a number only field here.
					parts[2]=parts[2].replaceAll("[^0-9]", "");
					//OpenEMR expects the report date to be in field 22
					parts[22]=sdf.format(reportDate);
					//Also need to manipulate 7?
					parts[7]=sdf.format(reportDate);
					obrLine = String.join("|", parts);
					log.debug("Final obrLine is "+obrLine);
					//System.err.println("Got obrLine "+obrLine);
				}
			}
			if (pidLine == null || pv1Line == null || obrLine == null) {
				System.err.println("No PID/PV1/OBR found in order - this doesn't seem valid");
				log.error("Input order request for generated results did not include one or more of PID/PV1/OBR");
				Alert alert=new Alert(AlertType.ERROR,"The input order is missing one or more of PID/PV1/OBR lines",new ButtonType[] {ButtonType.OK});
				alert.show();	//We don't care about the result of the alert - we are just stopping.
				return;
			} else {
				result = result.replace("<PIDLINE>", pidLine).replace("<PV1LINE>", pv1Line).replace("<OBRLINE>",
						obrLine);
				//System.err.println("result after replace statements is "+result);
				log.debug("Initial HL7 result is "+result);
			}
			
			result = result.replace("<CREATION_TIMESTAMP>", sdf.format(reportDate));
			
			/*
			 * We use this Instant to start from the current date/time.
			 * Then as we are producing the results, we move the time
			 * for that sample back by one minute.  Apparently the
			 * Piccolo takes about 12 minutes to produce a real sample,
			 * so this is vaguely accurate.  The fact that the results
			 * appear to be produced in "reverse order" doesn't matter
			 * at all for our purposes.
			 */
			Instant resultProduction=new Date().toInstant();

			StringBuilder resultsBuilder = new StringBuilder("\n");
			int count=1;
			int delta=1;
			for(PiccoloResultModel testResult : allMeasurements) {
				if(testResult.getValue()==-1) {
					//Result not generated
					Alert noResult=new Alert(AlertType.ERROR, "Results have not yet been generated (Collect New Sample first!)", new ButtonType[] {ButtonType.OK});
					noResult.show();
					return;
				}
				Instant instantForThisSample=resultProduction.minusSeconds(delta++ * 60);
				Date dateForThisSample=new Date(instantForThisSample.getEpochSecond()*1000);
				resultsBuilder.append("OBX|").append(count++)
				.append("|").append(testResult.toString())
				.append("|||").append(sdf.format(dateForThisSample))
				.append("\n");
			};
			result+=resultsBuilder.toString();
			
			//System.out.println("HL7 response is " + result);
			log.debug("HL7 response is "+result);
			
			result=result.replaceAll("\n","\r");	//For whatever reason, this seems to be the expected format...
			
			postHL7(result);
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			log.error("Error while sending HL7 result file to OpenEMR",e);
		}
	}
	
	public ArrayList<byte[]> createHL7ResultsForPiccolo() {
		//List of the lines in the results.
		ArrayList<String> lines=new ArrayList<>();
		
		Date reportDate=new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("YYYYMMddHHmmss");
		
		//We get the results first, to check if they have been generated.  If not, we don't continue;
		ArrayList<String> testResults=getResultLinesForPiccolo();
		if(testResults.size()==0) {
			return null;
		}
		
		//HEADER LINE FIRST- starts with H
		String headerLine="H|\\^&|||ABAXIS, INC.^piccolo xpress^3.1.37^0000P21592|||||||P|E 1394-97|"+sdf.format(reportDate);
		lines.add(headerLine);
		
		//PATIENT LINE NEXT - starts with P
		lines.add(getPatientIDLineForPiccolo());
		
		//ORDER LINE NEXT - starts with O
		lines.add(getOrderLineForPiccolo());
		
		lines.addAll(getLeadingCommentsForPiccolo(-2, 198, 1));
		
		lines.addAll(testResults);
		
		lines.addAll(getQualityControlReportForPiccolo());
		
		//List of byte arrays, each single bytes array constitutes a fully formed "line" including start, end, checksum etc.
		ArrayList<byte[]> byteArrays=new ArrayList<>();
		
		try {
			/*
			 * We now need to produce the final output consisting of lines like this
			 *  <STX><Frame Data><CR><ETX><2 CHARACTER CHECKSUM><CR><LF>
			 * 
			 * 1.  Take each line in lines.
			 * 2.  Prefix that line with STX
			 * 3.  Prefix that line with Octal sequence number
			 * 4.  Add the line content
			 * 5.  Add a \r
			 * 6.  Suffix the line with ETX
			 * 7.  Generate a two byte checksum and add that
			 * 8.  Add a \r\n
			 */
			
			int octalSeq=0;
			
			for(int i=0;i<lines.size();i++) {
				String thisLine=lines.get(i);
				ByteArrayOutputStream baos=new ByteArrayOutputStream();
				baos.write(STX);
				baos.write( (""+((octalSeq++) % 8) ).getBytes() );
				baos.write(thisLine.getBytes());
				baos.write('\r');
				baos.write(ETX);
				String checksum=ASTMChecksum(baos.toByteArray());
				baos.write(checksum.getBytes());
				baos.write("\r\n".getBytes());
				byteArrays.add(baos.toByteArray());	//Store to use for sending.
			}
			
//			if(transmit) {
//				transmit("localhost", 1182, byteArrays);
//			}
			
			//baos.write(new byte[] { RER, EOT, RER });	//0x05, 0x04, 0x05
			
			//baos.write(finalOutput.getBytes());
			//Files.write(new File("/tmp/piccolo.bin").toPath(), baos.toByteArray(), new OpenOption[0]);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return byteArrays;
		
	}
	
	
	
	private String getPatientIDLineForPiccolo() {
		//PATIENT LINE NEXT - starts with P
		//This use of a String array here is entirely unnecessary - it just allows us to add comments on the fields.
		
		/*
		 * Unless we are connected to open emr, patient will definitely be null.  Create a dummy patient
		 */
		if(currentPatient==null) {
			currentPatient=new Patient();
			currentPatient.family_name="Test";
			currentPatient.given_name="Piccolo";
			currentPatient.mrn="579404";
		}
		
		
		String[] patientFields=new String[36];
		patientFields[0]="";	//We are treating this as a dummy so we can have the remaining index numbers matching the document numbers
		patientFields[1]="P";	//P to identify patient line
		patientFields[2]="1";	//1 for first patient
		patientFields[3]=currentPatient.mrn;	//Unique patient id - here, obtained from the current patient
		patientFields[4]="";	//Lab assigned patient id
		patientFields[5]="";	//Optional additional identifier information
		patientFields[6]=
				currentPatient.family_name
				+"^"
				+currentPatient.given_name
				+"^"
				+""	//middle name or inital
				+"^"
				+""	//suffix
				+"^"
				+""	//title
				;
		patientFields[7]="";	//Mother's maiden name
		patientFields[8]="";	//Birthdate
		patientFields[9]="F";	//Sex
		patientFields[10]="W";	//Ethnic origin
		patientFields[11]="";	//Street address
		patientFields[12]="";	//Reserved for future expansion
		patientFields[13]="";	//Telephone number
		patientFields[14]="";	//Attending physician id.
		patientFields[15]="^46 Yrs.^";	//Optional text field for vendor use - appears to contain age in our case
		patientFields[16]="Patient";	//Optional text field for vendor use - appears to contain the word patient in our case
		/*
		 * All remaining fields in the samples SK has seen from Piccolo are empty - indeed, it only ever contains 26 fields in the first place,
		 * rather than the 35 that are defined in the specification.  So maybe we will want to shrink this to a smaller array.
		 */
		for(int i=17;i<patientFields.length;i++) {
			patientFields[i]="";
		}
		/*
		 * Above, we added a dummy field in index 0, with the array being one field longer than actually necessary,
		 * i.e. 36 when only 35 fields are required.  All that so we can use 1 for the first field, 2 for the second
		 * etc. to keep the numbers matching the field numbers in the documentation.  Now we shift everything back one
		 */
		System.arraycopy(patientFields, 1, patientFields, 0, patientFields.length-1);	//Shift them all back one place
		String finalPatientLine=String.join("|", patientFields);
		return finalPatientLine;
	}

	private String getOrderLineForPiccolo() {
		//This use of a String array here is entirely unnecessary - it just allows us to add comments on the fields.

		Date requestDate=new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("YYYYMMddHHmmss");

		String[] orderFields=new String[32];
		orderFields[0]="";	//We are treating this as a dummy so we can have the remaining index numbers matching the document numbers
		orderFields[1]="O";	//O is for order...
		orderFields[2]="1";	//Order sequence number - Piccolo appears to start with 1...
		orderFields[3]="";	//Specimin ID - Piccolo seems to leave this blank
		orderFields[4]="";	//Instrument specimin ID - Piccolo seems to leave this blank
		orderFields[5]=
			""	//This field currently unused
			+"^"
			+""	//Part 2
			+"^"
			+""	//Part 3
			+"^"
			+"Comprehensive Metabolic: 5125AA1";	//There are many more fields in the standard that the Piccolo appears to ignore.
		orderFields[6]="";		//Priority - Piccolo seems to leave this blank
		orderFields[7]=sdf.format(requestDate);	//Requested/Ordered date time - note that we are using "now" here.  We might need a more accurate value.
		//Piccolo appears to leave fields 8-25 inclusive blank
		for(int i=8;i<26;i++) {
			orderFields[i]="";
		}
		orderFields[26]="F";	//Report type - F is final results
		//Piccolo does not appear to even have fields 27-31 in its output, so we may need to trim the initial array.
		for(int i=27;i<32;i++) {
			orderFields[i]="";
		}
		
		/*
		 * Above, we added a dummy field in index 0, with the array being one field longer than actually necessary,
		 * i.e. 32 when only 31 fields are required.  All that so we can use 1 for the first field, 2 for the second
		 * etc. to keep the numbers matching the field numbers in the documentation.  Now we shift everything back one
		 */
		System.arraycopy(orderFields, 1, orderFields, 0, orderFields.length-1);	//Shift them all back one place
		String finalOrderLine=String.join("|", orderFields);
		return finalOrderLine;
	}
	
	/**
	 * Each Piccolo results tranmission seems to contain a number of comments just after the order line,
	 * the purpose of which isn't completely clear to Simon at the moment.  The three variables in the
	 * method are used because one of the lines does appear to vary, so for now we at least take them
	 * as values so we can vary them later.
	 * @return
	 */
	private ArrayList<String> getLeadingCommentsForPiccolo(int hem, int lip, int ict) {
		ArrayList<String> results=new ArrayList<>();
		
		results.add("C|1|I|^^INST QC: OK    CHEM QC: OK|G");
		results.add("C|2|I|^^HEM: "+hem+"  LIP: "+lip+"  ICT: "+ict+"|G");
		results.add("C|3|I|^^|G");
		results.add("C|4|I|^^|G");
		results.add("C|5|I|^^|G");
		
		return results;
	}
	
	private ArrayList<String> getResultLinesForPiccolo() {
		/*
		 * There seems to be a few odd things about this, but we are aiming to get all the result lines
		 * in the format the Piccolo produces.
		 */
		ArrayList<String> results=new ArrayList<>();
		int sequence=1;
		int octalSeq=1;
		for(PiccoloResultModel testResult : allMeasurements) {
			if(testResult.getValue()==-1) {
				//Result not generated
				Alert noResult=new Alert(AlertType.ERROR, "Results have not yet been generated (Collect New Sample first!)", new ButtonType[] {ButtonType.OK});
				noResult.show();
				return results;
			}
			StringBuilder resultBuilder=new StringBuilder("R|")
			.append(sequence++)
			.append("|").append(testResult.toStringPiccolo());
			results.add(resultBuilder.toString());
		};
		
		return results;
	}
	
	/**
	 * Quality control report for Piccolo.  At the moment, the contents of this is fixed,
	 * but it might need to be parameterized in the future.  The date is generated, but this will
	 * probably execute within a fraction of a second from the rest of the report, so the date might
	 * well be the same as in the header.  We can parameterize that if required to simulate a "delay"
	 * 
	 * @return an array of lines comprising the QC report
	 */
	private ArrayList<String> getQualityControlReportForPiccolo() {
		ArrayList<String> results=new ArrayList<>();
		
		Date qcDate=new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("YYYYMMddHHmmss");
		
		results.add("O|2|||^^^* QUALITY CONTROL REPORT *: 5125AA1||"+sdf.format(qcDate)+"|||||||||||||||||||F");
		results.add("C|1|I|^^CHEMISTRY QC:        102|G");
		results.add("C|2|I|^^ACCEPTABLE MINIMUM:  50|G");
		results.add("R|1|^^^LEVEL 1: IQC 1|92||90 to 110|||");
		results.add("R|2|^^^LEVEL 1: IQC 2|107||90 to 110|||");
		results.add("R|3|^^^LEVEL 1: IQC 3|91||90 to 110|||");
		results.add("R|4|^^^LEVEL 1: IQC 4|92||90 to 110|||");
		results.add("R|5|^^^LEVEL 1: IQC 5|103||90 to 110|||");
		results.add("R|6|^^^LEVEL 1: IQC 6|90||90 to 110|||");
		results.add("R|7|^^^LEVEL 1: IQC 7|106||90 to 110|||");
		results.add("R|8|^^^LEVEL 1: IQC 8|93||90 to 110|||");
		results.add("R|9|^^^LEVEL 2: PRE|100||95 to 105|||");
		results.add("R|10|^^^LEVEL 2: 340 nm|100||95 to 105|||");
		results.add("R|11|^^^LEVEL 2: 405 nm|100||95 to 105|||");
		results.add("R|12|^^^LEVEL 2: 467 nm|100||95 to 105|||");
		results.add("R|13|^^^LEVEL 2: 500 nm|100||95 to 105|||");
		results.add("R|14|^^^LEVEL 2: 515 nm|100||95 to 105|||");
		results.add("R|15|^^^LEVEL 2: 550 nm|100||95 to 105|||");
		results.add("R|16|^^^LEVEL 2: 600 nm|100||95 to 105|||");
		results.add("R|17|^^^LEVEL 2: 630 nm|100||95 to 105|||");
		results.add("L|1|N");
		
		return results;
	}
	
	private void postHL7(String result) throws IOException {
		//We mirror the source file name for the order as the result file name
		String filename = openEMROrders.getSelectionModel().getSelectedItem();
		try {
			openEMR.postHL7(filename, result);
		} catch (Exception e) {
			throw new IOException("Failed to submit results to server",e);
		}
	}

	private void initPiccoloTable() {
		TableView<PiccoloResultModel> tableView=piccoloResults.getPiccoloResultsTable();
		resultsHolderBox.getChildren().add(tableView);
		
		HBox exportBox=new HBox();

		exportTargetsGroup=new ToggleGroup();
		
		if(openEMR != null && openEMR.isValid()) {
			openEMROption=new RadioButton("OpenEMR");
			openEMROption.setTooltip(new Tooltip("Export the results to OpenEMR"));
			openEMROption.setToggleGroup(exportTargetsGroup);
			openEMROption.setTooltip(new Tooltip("Export the results to a device expecting to receive them from a real Piccolo"));
		}
		
		piccoloOption=new RadioButton("ASTM");
		piccoloOption.setToggleGroup(exportTargetsGroup);
		
		Label astmHostLabel=new Label("Target ASTM Server");
		astmHostLabel.setPadding(new Insets(0,0,0,10));
		
		Label astmPortLabel=new Label("Target ASTM Port");
				
		// Add the export results button
		Button exportButton = new Button("Export results");
		exportButton.setOnAction(exportResults());

		if(openEMROption!=null) {
			exportBox.getChildren().addAll(openEMROption, piccoloOption, astmHostLabel, astmHostInput, astmPortLabel, astmPortInput, exportButton);
		} else {
			exportBox.getChildren().addAll(piccoloOption, astmHostLabel, astmHostInput, astmPortLabel, astmPortInput, exportButton);
		}
		piccoloOption.setSelected(true);
		HBox.setMargin(exportButton, new Insets(0,0,0,20));
		
		exportHolderBox.getChildren().add(exportBox);

	}

	private EventHandler<ActionEvent> exportResults() {
		return new EventHandler<ActionEvent>() {

			@Override
			public void handle(ActionEvent event) {
				RadioButton selectedToggle=(RadioButton)exportTargetsGroup.getSelectedToggle();
				if(selectedToggle==null) {
					Alert noSelection=new Alert(AlertType.ERROR, "Please select an export destination", ButtonType.OK);
					noSelection.show();
					return;
				}
				if(selectedToggle==openEMROption) {
					createHL7ResultsForOpenEMR();
				}
				if(selectedToggle==piccoloOption) {
					ArrayList<byte[]> results=createHL7ResultsForPiccolo();
					if(results==null) {
						return;
					}
					try {
						String hostname=astmHostInput.getText();
						int port=1182;	//Is this some sort of actual default well known number?
						try {
							port=Integer.parseInt(astmPortInput.getText());
						} catch (NumberFormatException nfe) {
							//just keep 1182.
						}
						transmit(hostname, port, results);
					} catch (Exception e) {
						Alert failedToTransmit=new Alert(AlertType.ERROR, "Failed to transmit results", ButtonType.OK);
						failedToTransmit.show();
						return;
					}
				}
			}

		};
	}

	public void startASTMServer() {
		int port=Integer.parseInt(localServerPortNumber.getText());
		astmServer=new ASTMServer(port);
		astmServer.addCallback(new DataCallback() {
			@Override
			public void dataReceived(String line) {
				
				//System.err.println("CLIENT CALLBACK "+line);
				String noOctal=line.substring(1);
				if(noOctal.startsWith("R")) {
					//System.err.println("Poss result line");
					//Result line
					String[] fields=line.split("\\|");
					String possResult=fields[2];
					char testChar=possResult.charAt(0);
					if(testChar>='0' && testChar<='9') {
						//This is a result line
						//System.err.println("Actual results line");
						String[] subFields=fields[2].split("\\^");
						System.err.println("Checking for LOINC code "+subFields[0]);
						for(PiccoloResultModel measurement : allMeasurements) {
							if(measurement.loinc.equals(subFields[0])) {
								//This is the measurement for this LOINC
								/*
								 * We need to remove any non-numeric part of this,
								 * because fields[3] could look like this
								 * "8.3 *"
								 * and that won't parse.
								 */
								fields[3]=fields[3].replaceAll("[^0-9\\.]","");
								measurement.setValue(Float.parseFloat(fields[3]));
							}
						}
					}
				}
			}
		});
		try {
			astmServer.startServer();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void stopASTMServer() {
		astmServer.stopServer();
	}
}
