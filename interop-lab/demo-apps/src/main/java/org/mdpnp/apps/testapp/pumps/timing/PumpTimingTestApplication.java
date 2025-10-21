package org.mdpnp.apps.testapp.pumps.timing;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSEvent;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSListener;
import org.mdpnp.devices.MDSHandler.Patient.PatientEvent;
import org.mdpnp.devices.MDSHandler.Patient.PatientListener;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.sql.SQLLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.subscription.Subscriber;

import ice.InfusionProgramDataWriter;
import ice.MDSConnectivity;
import ice.Patient;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

public class PumpTimingTestApplication {
	
	private DeviceListModel dlm;
	private NumericFxList numeric;
	private InfusionProgramDataWriter programWriter;
	private MDSHandler mdsHandler;
	
	@FXML VBox pumps;
			
	private final String FLOW_RATE=rosetta.MDC_FLOW_FLUID_PUMP.VALUE;
	private final String NEURO_FLOW="MDC_FLOW_FLUID_PUMP_1";
	
	private static final Logger log = LoggerFactory.getLogger(PumpTimingTestApplication.class);
	
	private HashMap<String, Parent> udiToPump=new HashMap<>();
	
	/**
	 * The "current" patient, used to determine if the patient has changed
	 */
	private Patient currentPatient;
	
	private Connection dbconn;
	
	public void set(DeviceListModel dlm, NumericFxList numeric, InfusionProgramDataWriter programWriter, MDSHandler mdsHandler) {
		this.dlm=dlm;
		this.numeric=numeric;
		this.programWriter=programWriter;
		this.mdsHandler=mdsHandler;
	}
	
	public void stop() {
		//TODO: Stop listening to the BP waveform for efficiency?
	}
	
	public void destroy() {
		if(dbconn!=null) {
			try {
				dbconn.close();
			} catch (SQLException e) {
				log.error("Could not cleanly close SQL Connection",e);
			}
		}
	}
	
	public void activate() {

		log.info("QCT.activate does nothing at the moment");
		System.err.println("In PumpControllerTestApplication.activate");

	}
	
	public void start(EventLoop eventLoop, Subscriber subscriber) {
		
		//Rely on addition of metrics to add devices...
		numeric.addListener(new ListChangeListener<NumericFx>() {
			@Override
			public void onChanged(Change<? extends NumericFx> change) {
				while(change.next()) {
					change.getAddedSubList().forEach( n -> {
						if(n.getMetric_id().equals(FLOW_RATE) || n.getMetric_id().equals(NEURO_FLOW)) {
							//Flow rate published - add to panel.  addPumpToMainPanel avoids duplication of devices anyway,
							//so just call it here.
							addPumpToMainPanel(dlm.getByUniqueDeviceIdentifier(n.getUnique_device_identifier()));
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
						removePumpFromMainPanel(d);
					});
				}
			}
		});
		
		mdsHandler.addPatientListener(new PatientListener() {

			@Override
			public void handlePatientChange(PatientEvent evt) {
				
			}
			
		});
		
		mdsHandler.addConnectivityListener(new MDSListener() {

			@Override
			public void handleConnectivityChange(MDSEvent evt) {
		        ice.MDSConnectivity c = (MDSConnectivity) evt.getSource();

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
		            	return;	//Nothing else to do.
		            }
		            if( ! currentPatient.mrn.equals(p.mrn) ) {
		            	//Patient has changed
		            	currentPatient=p;
		            }
		            
		            //deviceUdiToPatientMRN.put(c.unique_device_identifier, p);
		        }
		    }
			
		});
		
    	dbconn = SQLLogging.getConnection();
	}
	
	private void addPumpToMainPanel(Device d) {
		if(!udiToPump.containsKey(d.getUDI()) && numeric!=null) {
			FXMLLoader loader = new FXMLLoader(PumpWithProgramListener.class.getResource("PumpWithListener.fxml"));
			try {
		        final Parent ui = loader.load();
		        
		        final PumpWithProgramListener controller = ((PumpWithProgramListener) loader.getController());
		        controller.setPump(d,numeric,programWriter, dbconn);
		        pumps.getChildren().add(ui);
		        udiToPump.put(d.getUDI(), ui);
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}
	}
	
	private void removePumpFromMainPanel(Device d) {
		pumps.getChildren().remove(udiToPump.get(d.getUDI()));
	}
	
	class DeviceListCell extends ListCell<Device> {
        @Override protected void updateItem(Device device, boolean empty) {
            super.updateItem(device, empty);
            if (!empty && device != null) {
                setText(device.getModel()+"("+device.getComPort()+")");
            } else {
                setText(null);
            }
        }
    }

	public void refresh() {
		int childCount=pumps.getChildren().size();
		pumps.getChildren().remove(0, childCount);
		activate();
	}
	
	public void startPumps() {
		pumps.getChildren().forEach(n -> {
			PumpWithProgramListener pwl=(PumpWithProgramListener)n.getUserData();
			try {
				pwl.startSettingSpeeds();
			} catch (IOException ioe) {
				log.error("Error running pump ", ioe);
			}
		});
	}
	
	/**
	 * It barely seems worth a class, but it is one...
	 * @author Simon
	 *
	 */
	class TimeAndRate {
		/**
		 * How long to sleep before asking for the given rate
		 */
		long interval;
		/**
		 * The rate to ask for.
		 */
		float rate;
	}
	
}
