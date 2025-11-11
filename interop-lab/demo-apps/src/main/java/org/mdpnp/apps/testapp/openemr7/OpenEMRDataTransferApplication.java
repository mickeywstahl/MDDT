package org.mdpnp.apps.testapp.openemr7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;

import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.patient.OpenEMRImpl;
import org.mdpnp.apps.testapp.patient.PatientInfo;
import org.mdpnp.apps.testapp.patient.OpenEMRImpl.PatientEncounter;
import org.mdpnp.apps.testapp.patient.OpenEMRPatientInfo;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSEvent;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSListener;
import org.mdpnp.devices.MDSHandler.Patient.PatientEvent;
import org.mdpnp.devices.MDSHandler.Patient.PatientListener;

import ice.MDSConnectivity;
import ice.Patient;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

public class OpenEMRDataTransferApplication {
	
	/**
	 * The "current" patient, used to pass back to the EMRFacade to get encounters etc.
	 */
	private Patient currentPatient;
	
	private EMRFacade emr;
	private OpenEMRImpl openEMR;
	private MDSHandler mdsHandler; 
	
	@FXML
	private ComboBox<PatientEncounter> patientEncounters;
	
	@FXML
	private ComboBox<String> openEMRMetrics;
	
	@FXML
	private Label patientNameLabel;
	@FXML
	private VBox newEncounterDetails, newVitalDetails;
	@FXML
	private TextField newEncounterDate,newEncounterReason,metricValue;
	
	public OpenEMRDataTransferApplication() {
		// TODO Auto-generated constructor stub
	}
	
	public void set(MDSHandler mdsHandler, EMRFacade emr) {
		this.mdsHandler=mdsHandler;
		this.emr=emr;
	}
	
	public void start() {
		if( ! (emr instanceof OpenEMRImpl)) {
			return;
		}
		
		openEMR=(OpenEMRImpl)emr;
		
		mdsHandler.addPatientListener(new PatientListener() {

			@Override
			public void handlePatientChange(PatientEvent evt) {
				ice.Patient icePatient=(ice.Patient)evt.getSource();
				System.err.println("OpenEMRDataTransfer handlePatientChange mrn is "+icePatient.mrn);
			}
			
		});
		
		mdsHandler.addConnectivityListener(new MDSListener() {

			@Override
			public void handleConnectivityChange(MDSEvent evt) {
		        ice.MDSConnectivity c = (MDSConnectivity) evt.getSource();
//		        System.err.println("CLC.handleConnectivity Partition is "+c.partition);

		        String mrnPartition = PartitionAssignmentController.findMRNPartition(c.partition);

		        if(mrnPartition != null) {
		            //log.info("udi " + c.unique_device_identifier + " is MRN=" + mrnPartition);

		            Patient p = new Patient();
		            p.mrn = PartitionAssignmentController.toMRN(mrnPartition);
		            
		            if(currentPatient==null) {
		            	/*
		            	 * The patient has definitely changed - even if the selected patient is "Unassigned",
		            	 * then that "Patient" has an ID
		            	 */
		            	currentPatient=p;
                        setPatientNameLabel();
                        
                        System.err.println("OpenEMRDataTransfer new current patient is is mrn "+p.mrn);
                        try {
                        	refreshEncounterList();
                		} catch (Exception e) {
                			e.printStackTrace();
                		}
		            	return;	//Nothing else to do.
		            }
		            if( ! currentPatient.mrn.equals(p.mrn) ) {
		            	System.err.println("OpenEMRDataTransfer new current patient is mrn "+p.mrn);
		            	//Patient has changed
		            	currentPatient=p;
                        setPatientNameLabel();
                        //Get encounters for the selected patient
                        try {
                			ArrayList<PatientEncounter> encounters=openEMR.getEncountersForPatient( getCurrentPatientInfo() );
                			patientEncounters.getItems().addAll(encounters);
                		} catch (Exception e) {
                			e.printStackTrace();
                		}
		            }
		        }
		    }
		});
		
		//Initially hide the new encounter contents
		newEncounterDetails.setVisible(false);
		newEncounterDetails.setManaged(false);
		//Initially hide the new vital contents
		newVitalDetails.setVisible(false);
		newVitalDetails.setManaged(false);
		
		populateVitalChoices();
		
		patientEncounters.getSelectionModel().selectedItemProperty().addListener( 
			new ChangeListener<>() {
				@Override
				public void changed(ObservableValue<? extends PatientEncounter> observable, PatientEncounter oldValue,
						PatientEncounter newValue) {
					if(newValue!=null) {
						//Enable the vital signs section
						newVitalDetails.setVisible(true);
						newVitalDetails.setManaged(true);
					} else {
						//It doesn't seem to be possible to reselect the "prompt" contents after selecting anything.  But just in case
						newVitalDetails.setVisible(false);
						newVitalDetails.setManaged(false);
					}
				}
			}
		);
		
	}
	
	private void populateVitalChoices() {
		//TODO: Make this a key value pair with a more human readable description
		openEMRMetrics.getItems().addAll(
			"bps",
			"bpd",
			"weight",
			"height",
			"temperature",
			"temp_method",
		    "pulse",
			"respiration",
			"note",
			"waist_circ",
			"head_circ",
			"oxygen_saturation"
		);
	}
	
	private OpenEMRPatientInfo getCurrentPatientInfo() {
		ObservableList<PatientInfo> patientInfos=emr.getPatients();
        FilteredList<PatientInfo> onlyPatient=patientInfos.filtered(new Predicate<PatientInfo>() {

			@Override
			public boolean test(PatientInfo t) {
				if(t.getMrn().equals(currentPatient.mrn)) {
					return true;
				}
				return false;
			}

        });
        PatientInfo pi=onlyPatient.get(0);
        return (OpenEMRPatientInfo)pi;
	}
	
	private void setPatientNameLabel() {
		try {
            PatientInfo pi=getCurrentPatientInfo();
            patientNameLabel.setText("Current Patient: "+pi.getFirstName()+" "+pi.getLastName());
            patientNameLabel.setFont(Font.font(24));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
	
	public void newEncounter() {
		newEncounterDetails.setVisible(true);
		newEncounterDetails.setManaged(true);
	}
	
	public void saveNewEncounter() {
		String date=newEncounterDate.getText();
		String reason=newEncounterReason.getText();
		try {
			//openEMR.addNewEncounter(getCurrentPatientInfo().getUUID(),date, reason);
			PatientEncounter newEncounter=openEMR.addNewEncounter(getCurrentPatientInfo().getUUID(),date, reason);
			patientEncounters.getItems().add(newEncounter);
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		newEncounterDetails.setVisible(false);
		newEncounterDetails.setManaged(false);
	}
	
	public void saveNewVital() {
		//Right now we can only make one vital anyway.
		String patientId=getCurrentPatientInfo().getMrn();
		String encounterId=String.valueOf(patientEncounters.getSelectionModel().getSelectedItem().eid);
		HashMap<String,String> vitalsMap=new HashMap<>();
		String selectedMetric=openEMRMetrics.getSelectionModel().getSelectedItem();
		String metricVal=metricValue.getText();
		vitalsMap.put(selectedMetric, metricVal);
		//TODO: Make multiple options in one request.  Needs the rows to be cloneable
		try {
			openEMR.addVitalSign(patientId,encounterId,vitalsMap);
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
	}

	private void refreshEncounterList() throws Exception {
		patientEncounters.getItems().clear();
		ArrayList<PatientEncounter> encounters=openEMR.getEncountersForPatient( getCurrentPatientInfo() );
		patientEncounters.getItems().addAll(encounters);
	}

}
