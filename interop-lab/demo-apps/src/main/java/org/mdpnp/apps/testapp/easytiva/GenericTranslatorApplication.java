package org.mdpnp.apps.testapp.easytiva;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.mdpnp.apps.fxbeans.AlertFx;
import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceFactory;
import org.mdpnp.apps.testapp.DeviceFactory.AP4000Provider;
import org.mdpnp.apps.testapp.DeviceFactory.AlarisSerialProvider;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.devices.AbstractDevice;
import org.mdpnp.devices.DeviceDriverProvider;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSEvent;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSListener;
import org.mdpnp.devices.MDSHandler.Patient.PatientEvent;
import org.mdpnp.devices.MDSHandler.Patient.PatientListener;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.devices.alaris.Asena;
import org.mdpnp.devices.connected.AbstractConnectedDevice;
import org.mdpnp.devices.neurowave.AP4000;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialProviderFactory;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.SubscriberQos;

import ice.InfusionProgram;
import ice.InfusionProgramDataWriter;
import ice.MDSConnectivity;
import ice.Patient;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import rosetta.MDC_FLOW_FLUID_PUMP;

/**
 * An app that receives generic pump control statements from EasyTiva protocol,
 * and emits InfusionProgram instances to control the relevant pumps.
 * @author Simon Kelly, Braga Aroulmozhi
 *
 */
public class GenericTranslatorApplication {

	private ApplicationContext parentContext;
	private DeviceListModel deviceListModel;
	private NumericFxList numericList;
	private SampleArrayFxList sampleList;
	private InfusionProgramDataWriter infusionProgramWriter;
	private MDSHandler mdsHandler;
	private VitalModel vitalModel;
	private Subscriber subscriber;
	private EMRFacade emr;
	
	@FXML
	ComboBox<String> remiCombo;
	@FXML
	ComboBox<String> propCombo;
	
	@FXML
	ComboBox<String> propPortCombo;
	
	@FXML
	ComboBox<String> remiPortCombo;
	
	@FXML
	ComboBox<String> easyPortCombo;
	
	@FXML
	TextField propChannel;
	
	@FXML
	TextField remiChannel;
	
	@FXML
	TextArea logArea;
	
	@FXML
	Label remiRateLabel;
	
	@FXML
	Label propRateLabel;
	
	private static final String ALARIS="Alaris";
	private static final String NEUROWAVE="Neurowave";
	
	private static final String FLOW_RATE=rosetta.MDC_FLOW_FLUID_PUMP.VALUE;
	
	private long lastSetRateTime;
	
	private boolean listenerPresent;
	
	private Patient currentPatient;
	
	private AbstractConnectedDevice remiDevice,propDevice,tivaDevice;
	
	private DeviceDriverProvider.DeviceAdapter easyTivaDeviceAdapter, propDeviceAdapter, remiDeviceAdapter;
	
	private int remiChannelNum,propChannelNum;
	
	/**
	 * Bitmask position for drug not set alarm
	 */
	private static final int DRUG_NOT_SET=0;
	
	/**
	 * Bitmask position for syringe not validated alarm
	 */
	private static final int SYRINGE_NOT_VALIDATED=1;
	
	/**
	 * Bitmask position for clamp open alarm
	 */
	private static final int CLAMP_OPEN=2;
	
	/**
	 * Bitmask position for plunger open alarm
	 */
	private static final int PLUNGER_OPEN=3;
	
	/**
	 * Bitmask position for occlusion alarm
	 */
	private static final int OCCLUSION=4;
	
	/**
	 * Bitmask position for end of infusion alarm
	 */
	private static final int END_OF_INFUSION=5;
	
	/**
	 * Bitmask position for device not ready alarm
	 */
	private static final int DEVICE_NOT_READY=6;
	
	private String remiDeviceUDI, propDeviceUDI;
	
	private float currentRemiRate, currentPropRate;
	
	private float infCount=0;
	
	private AlertFxList patientAlertList;
	private AlertFxList technicalAlertList;
	
	private int currentRemiAlarmVal=0, currentPropAlarmVal=0;

	public void set(ApplicationContext parentContext, DeviceListModel deviceListModel, NumericFxList numericList,
			SampleArrayFxList sampleList, InfusionProgramDataWriter infusionProgramWriter, MDSHandler mdsHandler,
			VitalModel vitalModel, Subscriber subscriber, EMRFacade emr, AlertFxList patientAlertList, AlertFxList technicalAlertList) {
		this.parentContext=parentContext;
		this.deviceListModel=deviceListModel;
		this.numericList=numericList;
		this.sampleList=sampleList;
		this.infusionProgramWriter=infusionProgramWriter;
		this.mdsHandler=mdsHandler;
		this.vitalModel=vitalModel;
		this.subscriber=subscriber;
		this.emr=emr;
		this.patientAlertList=patientAlertList;
		this.technicalAlertList=technicalAlertList;
	}

	public void start(EventLoop eventLoop, Subscriber subscriber2) {

		//Rely on addition of metrics to add devices...
//				numericList.addListener(new ListChangeListener<NumericFx>() {
//					@Override
//					public void onChanged(Change<? extends NumericFx> change) {
//						while(change.next()) {
//							change.getAddedSubList().forEach( n -> {
//								if( (n.getMetric_id().startsWith("MDC_FLOW_FLUID_PUMP_2") || n.getMetric_id().startsWith(FLOW_RATE)) && n.getUnique_device_identifier().equals(remiDevice.getUniqueDeviceIdentifier())) {
//									addRemiSpeedListener(n);
//								}
//								if((n.getMetric_id().startsWith("MDC_FLOW_FLUID_PUMP_1") || n.getMetric_id().startsWith(FLOW_RATE))  && n.getUnique_device_identifier().equals(propDevice.getUniqueDeviceIdentifier())) {
//									addPropSpeedListener(n);
//								}
//							});
//						}
//					}
//
//				});
				
				//...and removal of devices to remove devices.
				deviceListModel.getContents().addListener(new ListChangeListener<Device>() {
					@Override
					public void onChanged(Change<? extends Device> change) {
						while(change.next()) {
							change.getRemoved().forEach( d-> {
								//icepumps.getItems().remove(d);
								//removePumpFromMainPanel(d);
							});
						}
					}
				});
				
				listenerPresent=true;
				
				technicalAlertList.addListener( new ListChangeListener<AlertFx>() {

					@Override
					public void onChanged(Change<? extends AlertFx> c) {
						while(c.next()) {
							c.getAddedSubList().forEach( a -> {
							
								if(a.getUnique_device_identifier().equals(remiDeviceUDI)) {
									//Remi alarm
									String ident=a.getIdentifier();
									String text=a.getText();
									if(ident.equals("OCCLUSION")) {
										currentRemiAlarmVal = currentRemiAlarmVal | (int) Math.pow(2, OCCLUSION);
									} 
									if (ident.equals("CLAMP_OPEN")) {
										currentRemiAlarmVal = currentRemiAlarmVal | (int) Math.pow(2, CLAMP_OPEN);
									}
									if (ident.equals("PLUNGER_OPEN")) {
										currentRemiAlarmVal = currentRemiAlarmVal | (int) Math.pow(2, PLUNGER_OPEN);
									}
									if (ident.equals("END_OF_INFUSION")) {
										currentRemiAlarmVal = currentRemiAlarmVal | (int) Math.pow(2, END_OF_INFUSION);
									}
									if (ident.equals("DEVICE_NOT_READY")) {
										currentRemiAlarmVal = currentRemiAlarmVal | (int) Math.pow(2, DEVICE_NOT_READY);
									}
								}
								if(a.getUnique_device_identifier().equals(propDeviceUDI)) {
									//Prop alarm
									String ident=a.getIdentifier();
									String text=a.getText();
									if(ident.equals("OCCLUSION")) {
										currentPropAlarmVal = currentPropAlarmVal | (int) Math.pow(2, OCCLUSION);
									} 
									if (ident.equals("CLAMP_OPEN")) {
										currentPropAlarmVal = currentPropAlarmVal | (int) Math.pow(2, CLAMP_OPEN);
									}
									if (ident.equals("PLUNGER_OPEN")) {
										currentPropAlarmVal = currentPropAlarmVal | (int) Math.pow(2, PLUNGER_OPEN);
									}
									if (ident.equals("END_OF_INFUSION")) {
										currentPropAlarmVal = currentPropAlarmVal | (int) Math.pow(2, END_OF_INFUSION);
									}
									if (ident.equals("DEVICE_NOT_READY")) {
										currentPropAlarmVal = currentPropAlarmVal | (int) Math.pow(2, DEVICE_NOT_READY);
									}
								}
							});
							
							c.getRemoved().forEach( a -> {
								if(a.getUnique_device_identifier().equals(remiDeviceUDI)) {
									//Remi alarm remove
									String ident=a.getIdentifier();
									String text=a.getText();
									if(ident.equals("OCCLUSION")) {
										currentRemiAlarmVal = currentRemiAlarmVal ^ (int) Math.pow(2, OCCLUSION);
									}else if (ident.equals("CLAMP_OPEN")) {
										currentRemiAlarmVal = currentRemiAlarmVal ^ (int) Math.pow(2, CLAMP_OPEN);
									}else if (ident.equals("PLUNGER_OPEN")) {
										currentRemiAlarmVal = currentRemiAlarmVal ^ (int) Math.pow(2, PLUNGER_OPEN);
									}else if (ident.equals("END_OF_INFUSION")) {
										currentRemiAlarmVal = currentRemiAlarmVal ^ (int) Math.pow(2, END_OF_INFUSION);
									}else if (ident.equals("DEVICE_NOT_READY")) {
										currentRemiAlarmVal = currentRemiAlarmVal ^ (int) Math.pow(2, DEVICE_NOT_READY);
									}else {
										System.err.println("No Alarm Triggered");
									}
								}
								if(a.getUnique_device_identifier().equals(propDeviceUDI)) {
									//Prop alarm
									String ident=a.getIdentifier();
									String text=a.getText();
									if(ident.equals("OCCLUSION")) {
										currentPropAlarmVal = currentPropAlarmVal ^ (int) Math.pow(2, OCCLUSION);
									}else if (ident.equals("CLAMP_OPEN")) {
										currentPropAlarmVal = currentPropAlarmVal ^ (int) Math.pow(2, CLAMP_OPEN);
									}else if (ident.equals("PLUNGER_OPEN")) {
										currentPropAlarmVal = currentPropAlarmVal ^ (int) Math.pow(2, PLUNGER_OPEN);
									}else if (ident.equals("END_OF_INFUSION")) {
										currentPropAlarmVal = currentPropAlarmVal ^ (int) Math.pow(2, END_OF_INFUSION);
									}else if (ident.equals("DEVICE_NOT_READY")) {
										currentPropAlarmVal = currentPropAlarmVal ^ (int) Math.pow(2, DEVICE_NOT_READY);
									}else {
										System.err.println("No Alarm Triggered");
									}
								}
									
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

				List<String> ports=SerialProviderFactory.getDefaultProvider().getPortNames();
				ports.sort((Comparator<? super String>) new Comparator<String>() {

					@Override
					public int compare(String o1, String o2) {
						int port1=Integer.parseInt(o1.replaceAll("[^0-9]", ""));
						int port2=Integer.parseInt(o2.replaceAll("[^0-9]", ""));
						if(port1==port2) return 0;	//Unlikely but not impossible
						return port1<port2 ? -1 : 1;
					}
					
				});
				remiPortCombo.getItems().addAll(ports);
				propPortCombo.getItems().addAll(ports);
				easyPortCombo.getItems().addAll(ports);
				
				String[] pumpTypes=new String[] { ALARIS, NEUROWAVE };
				remiCombo.getItems().addAll(pumpTypes);
				propCombo.getItems().addAll(pumpTypes);
				
				propCombo.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {

					@Override
					public void changed(ObservableValue<? extends String> observable, String oldValue,
							String newValue) {
						System.err.println("newValue is "+newValue);
						if(ALARIS.equals(newValue)) {
							propChannel.setVisible(false);
						} else {
							propChannel.setVisible(true);
						}
					}
				});
				
				remiCombo.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {

					@Override
					public void changed(ObservableValue<? extends String> observable, String oldValue,
							String newValue) {
						System.err.println("newValue is "+newValue);
						if(ALARIS.equals(newValue)) {
							remiChannel.setVisible(false);
						} else {
							remiChannel.setVisible(true);
						}
					}
				});
						
	}

	protected void addPropSpeedListener(NumericFx n) {
		n.presentation_timeProperty().addListener(new ChangeListener<Date>() {
			@Override
			public void changed(ObservableValue<? extends Date> observable, Date oldValue, Date newValue) {
				propRateLabel.setText("Propofol rate:" +n.getValue());
				currentPropRate=n.getValue();
			}
		});
	}

	protected void addRemiSpeedListener(NumericFx n) {
		n.presentation_timeProperty().addListener(new ChangeListener<Date>() {
			@Override
			public void changed(ObservableValue<? extends Date> observable, Date oldValue, Date newValue) {
				remiRateLabel.setText("Remifentanyl rate:" +n.getValue());
				currentRemiRate=n.getValue();
			}
		});
	}

	public void activate() {	
	}

	public void stop() {
		// TODO Auto-generated method stub
		
	}

	public void destroy() {
		// TODO Auto-generated method stub
		
	}
	
	public void startProcess() {
		System.err.println("Start...");
		String remiPumpType=remiCombo.getSelectionModel().getSelectedItem();
		String propPumpType=propCombo.getSelectionModel().getSelectedItem();
		String remiCom=remiPortCombo.getSelectionModel().getSelectedItem();
		String propCom=propPortCombo.getSelectionModel().getSelectedItem();
		if(remiPumpType.equals("Alaris") && propPumpType.equals("Alaris")) {
			//Check they aren't using the same comPort.
			if(remiCom.equals(propCom)) {
				System.err.println("Error - same port for both Alaris pumps");
				//TODO: Error popup
				return;
			}
		}
		//TODO: More port mapping validation
		String tivaCom=easyPortCombo.getSelectionModel().getSelectedItem();
		if(tivaCom.equals(remiCom) || tivaCom.equals(propCom)) {
			System.err.println("Error - same port used for pump and EasyTiva");
			//TODO: Error popup
			return;
		}
		
		if(remiPumpType.equals(ALARIS)) {
			remiDeviceAdapter=createAlarisDeviceAdapter();
			remiDevice=createAlarisDevice(remiDeviceAdapter);
			remiDeviceUDI=remiDevice.getDeviceIdentity().unique_device_identifier;
			ScheduledExecutorService service=Executors.newSingleThreadScheduledExecutor();
			remiDevice.setExecutor(service);
			remiDevice.connect(remiCom);
			
			numericList.addListener(new ListChangeListener<NumericFx>() {
				@Override
				public void onChanged(Change<? extends NumericFx> change) {
					while(change.next()) {
						change.getAddedSubList().forEach( n -> {
							if(n.getUnique_device_identifier().equals(remiDeviceUDI) && n.getMetric_id().equals(MDC_FLOW_FLUID_PUMP.VALUE)) {
								addRemiSpeedListener(n);
							}
						});
					}
				}

			});
			
		}
		if(propPumpType.equals(ALARIS)) {
			propDeviceAdapter=createAlarisDeviceAdapter();
			propDevice=createAlarisDevice(propDeviceAdapter);
			propDeviceUDI=propDevice.getDeviceIdentity().unique_device_identifier;
			ScheduledExecutorService service=Executors.newSingleThreadScheduledExecutor();
			propDevice.setExecutor(service);
			propDevice.connect(propCom);
			
			numericList.addListener(new ListChangeListener<NumericFx>() {
				@Override
				public void onChanged(Change<? extends NumericFx> change) {
					while(change.next()) {
						change.getAddedSubList().forEach( n -> {
							if(n.getUnique_device_identifier().equals(propDeviceUDI) && n.getMetric_id().equals(MDC_FLOW_FLUID_PUMP.VALUE)) {
								addPropSpeedListener(n);
							}
						});
					}
				}

			});
			
		}
		//Could be possible that the two pumps are separate for Neurowave.  First deal with shared pump.
		if(remiPumpType.equals(NEUROWAVE) && propPumpType.equals(NEUROWAVE) && remiCom.equals(propCom)) {
			remiDeviceAdapter=createNeurowaveDeviceAdapter();
			remiDevice=propDevice=createNeurowaveDevice(remiDeviceAdapter);
			remiChannelNum=Integer.parseInt(remiChannel.getText());
			propChannelNum=Integer.parseInt(propChannel.getText());
			ScheduledExecutorService service=Executors.newSingleThreadScheduledExecutor();
			remiDevice.setExecutor(service);
			remiDevice.connect(remiCom);
			
			numericList.addListener(new ListChangeListener<NumericFx>() {
				@Override
				public void onChanged(Change<? extends NumericFx> change) {
					while(change.next()) {
						change.getAddedSubList().forEach( n -> {
							if( n.getMetric_id().startsWith("MDC_FLOW_FLUID_PUMP_2")) {
								addRemiSpeedListener(n);
							}
							if( n.getMetric_id().startsWith("MDC_FLOW_FLUID_PUMP_1")) {
								addPropSpeedListener(n);
							}
						});
					}
				}

			});
			
		}
		
		createEasyTiva();
		
	}
	
	public void stopProcess() {
		if(remiDevice!=null) {
			remiDevice.shutdown();
			remiDeviceAdapter.stop();
			remiDevice=null;
		}
		if(propDevice!=null) {
			propDevice.shutdown();
			propDeviceAdapter.stop();
			propDevice=null;
		}
		if(tivaDevice!=null) {
			tivaDevice.disconnect();
			tivaDevice.shutdown();
			easyTivaDeviceAdapter.stop();
		}
	}
	
	private DeviceDriverProvider.DeviceAdapter createAlarisDeviceAdapter() {
		DeviceDriverProvider.SpringLoadedDriver df = new DeviceDriverProvider.SpringLoadedDriver() {
            @Override
            public DeviceType getDeviceType() {
                return new DeviceType(ice.ConnectionType.Serial, "ICE", "AlarisTranslator", "AlarisTranslator", 1);
            }

            @Override
            public AbstractDevice newInstance(AbstractApplicationContext context) throws Exception {
                EventLoop eventLoop = context.getBean("eventLoop", EventLoop.class);
                Subscriber subscriber = context.getBean("subscriber", Subscriber.class);
                Publisher publisher = context.getBean("publisher", Publisher.class);
                return new Asena(subscriber, publisher, eventLoop);
            }
        };
        
        try {
        	DeviceDriverProvider.DeviceAdapter da = df.create((AbstractApplicationContext) parentContext);

            // TODO Make this more elegant
            List<String> strings = new ArrayList<String>();
            SubscriberQos qos = new SubscriberQos();
            subscriber.get_qos(qos);

            for (int i = 0; i < qos.partition.name.size(); i++) {
                strings.add((String) qos.partition.name.get(i));
            }

            da.setPartition(strings.toArray(new String[0]));
            return da;

        }
        catch(Exception ex) {
            throw new RuntimeException("Failed to create a driver", ex);
        }
	}
	
	private DeviceDriverProvider.DeviceAdapter createNeurowaveDeviceAdapter() {
		DeviceDriverProvider.SpringLoadedDriver df = new DeviceDriverProvider.SpringLoadedDriver() {
            @Override
            public DeviceType getDeviceType() {
                return new DeviceType(ice.ConnectionType.Serial, "ICE", "NeurowaveTranslator", "NeurowaveTranslator", 1);
            }

            @Override
            public AbstractDevice newInstance(AbstractApplicationContext context) throws Exception {
                EventLoop eventLoop = context.getBean("eventLoop", EventLoop.class);
                Subscriber subscriber = context.getBean("subscriber", Subscriber.class);
                Publisher publisher = context.getBean("publisher", Publisher.class);
                return new AP4000(subscriber, publisher, eventLoop);
            }
        };
        
        try {
        	DeviceDriverProvider.DeviceAdapter da = df.create((AbstractApplicationContext) parentContext);

            // TODO Make this more elegant
            List<String> strings = new ArrayList<String>();
            SubscriberQos qos = new SubscriberQos();
            subscriber.get_qos(qos);

            for (int i = 0; i < qos.partition.name.size(); i++) {
                strings.add((String) qos.partition.name.get(i));
            }

            da.setPartition(strings.toArray(new String[0]));
            return da;

        }
        catch(Exception ex) {
            throw new RuntimeException("Failed to create a driver", ex);
        }
	}
	
	private Asena createAlarisDevice(DeviceDriverProvider.DeviceAdapter adapter) {
		Asena alarisDevice=(Asena)adapter.getDevice();
		if(currentPatient!=null) {
			adapter.setPartition(new String[] {PartitionAssignmentController.toPartition(currentPatient.mrn)});
			String partitionToAssociate=PartitionAssignmentController.toPartition(currentPatient.mrn);
	        MDSConnectivity connectivity=new MDSConnectivity();
	        connectivity.partition=partitionToAssociate;
	        connectivity.unique_device_identifier=tivaDevice.getUniqueDeviceIdentifier();
	        mdsHandler.publish(connectivity);
		}
		return alarisDevice;
	}
	
	private AP4000 createNeurowaveDevice(DeviceDriverProvider.DeviceAdapter adapter) {
		AP4000 neurowaveDevice=(AP4000)adapter.getDevice();
		if(currentPatient!=null) {
			adapter.setPartition(new String[] {PartitionAssignmentController.toPartition(currentPatient.mrn)});
			String partitionToAssociate=PartitionAssignmentController.toPartition(currentPatient.mrn);
	        MDSConnectivity connectivity=new MDSConnectivity();
	        connectivity.partition=partitionToAssociate;
	        connectivity.unique_device_identifier=tivaDevice.getUniqueDeviceIdentifier();
	        mdsHandler.publish(connectivity);
		}
		return neurowaveDevice;
	}
	
	private void createEasyTiva() {
		if(easyTivaDeviceAdapter==null) {
			DeviceDriverProvider.SpringLoadedDriver df = new DeviceDriverProvider.SpringLoadedDriver() {
	            @Override
	            public DeviceType getDeviceType() {
	                return new DeviceType(ice.ConnectionType.Serial, "ICE", "EasyTivaTranslator", "EasyTivaTranslator", 1);
	            }
	
	            @Override
	            public AbstractDevice newInstance(AbstractApplicationContext context) throws Exception {
	                EventLoop eventLoop = context.getBean("eventLoop", EventLoop.class);
	                Subscriber subscriber = context.getBean("subscriber", Subscriber.class);
	                Publisher publisher = context.getBean("publisher", Publisher.class);
	                return new GenericTranslator(subscriber, publisher, eventLoop);
	            }
	            
	            
	        };
	        
	        try {
	        	easyTivaDeviceAdapter = df.create((AbstractApplicationContext) parentContext);

                // TODO Make this more elegant
                List<String> strings = new ArrayList<String>();
                SubscriberQos qos = new SubscriberQos();
//                System.err.println("assignedSubscriber is "+assignedSubscriber);
                subscriber.get_qos(qos);

                for (int i = 0; i < qos.partition.name.size(); i++) {
                    strings.add((String) qos.partition.name.get(i));
                }

                easyTivaDeviceAdapter.setPartition(strings.toArray(new String[0]));

            }
            catch(Exception ex) {
                throw new RuntimeException("Failed to create a driver", ex);
            }
		}
		tivaDevice=(GenericTranslator)easyTivaDeviceAdapter.getDevice();
		System.err.println("+++ CREATED tivaDevice ++++");
		if(currentPatient!=null) {
			easyTivaDeviceAdapter.setPartition(new String[] {PartitionAssignmentController.toPartition(currentPatient.mrn)});
			String partitionToAssociate=PartitionAssignmentController.toPartition(currentPatient.mrn);
	        MDSConnectivity connectivity=new MDSConnectivity();
	        connectivity.partition=partitionToAssociate;
	        connectivity.unique_device_identifier=tivaDevice.getUniqueDeviceIdentifier();
	        mdsHandler.publish(connectivity);
		}
		System.err.println("+++ About to connect tivaDevice +++");
		tivaDevice.connect(easyPortCombo.getSelectionModel().getSelectedItem());
		System.err.println("+++ Connected tivaDevice +++");
		
	}
	
	/**
	 * A pump property holder.  Very simple.
	 * @author HPWorkStation
	 *
	 */
	class SyringeProperties {
		/**
		 * The device manufacturer, for example Neurowave
		 */
		String manufacturer;
		/**
		 * The device model, for example AP4000
		 */
		String model;
		/**
		 * The volume of drug the pump (or the syringe, bag etc.) can deliver
		 */
		float volume;
		/**
		 * The name of the drug the pump is infusing, for example REMI or PROP 
		 */
		String drug;
		
		/**
		 * Construct a new instance of the properties holder.  Note - no validation of the
		 * parameters takes place yet.
		 * 
		 * @param manufacturer
		 * @param model
		 * @param volume
		 * @param drug
		 */
		public SyringeProperties(String manufacturer, String model, float volume, String drug) {
			super();
			this.manufacturer = manufacturer;
			this.model = model;
			this.volume = volume;
			this.drug = drug;
		}
	}
	
	class GenericTranslator extends AbstractSerialDevice {
		
		private BufferedReader fromRequestor, fromDevice;
		private BufferedWriter toRequestor, toDevice;
		
		private SyringeProperties[] pumpArray=new SyringeProperties[2];
		//TODO: Should we just add AbstractDevice as a field in PumpProperties?
		private AbstractDevice[] deviceArray=new AbstractDevice[2];

		public GenericTranslator(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
			super(subscriber, publisher, eventLoop);
			deviceIdentity.manufacturer="OpenICE";
			deviceIdentity.model="GPC Translator App";
			AbstractSimulatedDevice.randomUDI(deviceIdentity);
			super.writeDeviceIdentity();
			System.err.println("Create new instance of GenericTranslator");
		}

		private float infRatePROP, infRateREMI;
		private boolean connected;

		@Override
		protected void doInitCommands(int idx) throws IOException {
			System.err.println("doInitCommands for GenericTranslator");
			
			infRatePROP = (float) 0.0;
			infRateREMI = (float) 0.0;
			lastSetRateTime = System.currentTimeMillis();
			
			//TODO: Do these writes need a sleep in between setsyr and setdrug?
			
//			String cmds[]=new String[4];
//			
//			if(propDevice instanceof AP4000) {
//				SyringeProperties props=new SyringeProperties("Neurowave","AP4000",999,"prop");
//				/*
//				 * As far as I understand it right now, the EasyTiva can be told via the GCP
//				 * which drug/pump is which, using just an index of 0/1, and then it takes care
//				 * of the mapping of pump/drug and just uses that index for subsequent commands.
//				 * 
//				 * So we can assign the PumpProperties object to pumpArray arbitrarily, and all
//				 * subsequent commands can just address the correct pump according to that index. 
//				 * 
//				 * Not sure if we ever need the info again, but maybe we do and storing it in a
//				 * tiny class isn't a problem.
//				 * 
//				 *  In fact, lots of the commands like INF_SET_RATE assume the prop is channel 0
//				 *  and remi is channel 1 - so we can fix those.
//				 * 
//				 */
//				pumpArray[0]=props;
//				deviceArray[0]=propDevice;
//				cmds[0]="!INFO_GET_SYRINGE_INFO^0^Neurowave^AP4000^50.000000^0|0000\r";
//				cmds[1]="!INFO_GET_DRUG_INFO^0^N01AX10^Propofol^PROPOFOL^10.000000^mg/ml^0|0000\r";
//			}
//			
//			if(remiDevice instanceof AP4000) {
//				SyringeProperties props=new SyringeProperties("Neurowave","AP4000",999,"remi");
//				/*
//				 * As far as I understand it right now, the EasyTiva can be told via the GCP
//				 * which drug/pump is which, using just an index of 0/1, and then it takes care
//				 * of the mapping of pump/drug and just uses that index for subsequent commands.
//				 * 
//				 * So we can assign the PumpProperties object to pumpArray arbitrarily, and all
//				 * subsequent commands can just address the correct pump according to that index.  
//				 * 
//				 */
//				pumpArray[1]=props;
//				deviceArray[1]=remiDevice;
//				cmds[2]="!INFO_GET_SYRINGE_INFO^1^Neurowave^AP4000^50.000000^0|0000\r";
//				cmds[3]="!INFO_GET_DRUG_INFO^1^N01AH06^Remifentanil^REMIFENTANIL^20.000000^ug/ml^0|0000\r";
//			}
//			
//			if(propDevice instanceof Asena) {
//				SyringeProperties props=new SyringeProperties("Alaris","Asena",60,"prop");
//				pumpArray[0]=props;
//				deviceArray[0]=propDevice;
//				cmds[0]="!INFO_GET_SYRINGE_INFO^0^Alaris^Asena^50.000000^0|0000\r";
//				cmds[1]="!INFO_GET_DRUG_INFO^0^N01AX10^Propofol^PROPOFOL^10.000000^mg/ml^0|0000\r";
//			}
//			
//			if(remiDevice instanceof Asena) {
//				SyringeProperties props=new SyringeProperties("Alaris","Asena",60,"remi");
//				pumpArray[1]=props;
//				deviceArray[0]=propDevice;
//				cmds[2]="!INFO_GET_SYRINGE_INFO^1^Alaris^Asena^50.000000^0|0000\r";
//				cmds[3]="!INFO_GET_DRUG_INFO^1^N01AH06^Remifentanil^REMIFENTANIL^20.000000^ug/ml^0|0000\r";
//			}
//			
//			if(cmds[0]==null || cmds[1]==null || cmds[2]==null || cmds[3]==null) {
//				throw new IOException("Init commands not set - unknown device types?");
//			}
//			
//			for(int i=0;i<cmds.length;i++) {
//				System.err.println("Writing init command "+cmds[i]+" to EasyTiva");
//				logArea.appendText(cmds[i]+"\n");
//				toRequestor.write(cmds[i]);
//				try {
//					Thread.sleep(500);
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}	//Do we need the sleep or not?
//			}
			
			reportConnected("Init commands sent for idx - "+ idx);
			connected =true;
		}

		@Override
		protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
			System.err.println("process for GenericTranslator");
			//Must create fromRequestor and toRequestor before we loop on !connected, because initCommands
			//needs the output stream to write the INFO_GET_SYRINGE_INFO commands.
			fromRequestor = new BufferedReader(new InputStreamReader (inputStream));
			toRequestor = new BufferedWriter(new OutputStreamWriter (outputStream));
			System.err.println("Created fromRequestor and toRequestor on port " +getPortIdentifier()+ " for idx " + idx);
			
			while(!connected) {
				System.err.println("Waiting to connect");
				try {
					Thread.sleep(1000L);
				}catch(InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			String inputCommand=null;
			System.err.println("Reading from TIVA algorithm -----");
			while(true) {
				if ( (inputCommand = fromRequestor.readLine() )!=null) {
					System.err.println(getPortIdentifier(idx) + ": Received GCP Command:  " + inputCommand);
					String translatedResponse = executeGCPCommand(inputCommand);
					System.err.println(getPortIdentifier(idx) + ": Response from GCP Translator is :  " + translatedResponse);
					toRequestor.write(translatedResponse);
					toRequestor.flush();
					System.err.println(getPortIdentifier(idx) + ": Written to GCP Requestor");
					
				} else {
					System.err.println("Did not read lines from TIVA");
				}
			}
			
		}

		@Override
		public SerialProvider getSerialProvider(int idx) {
			// TODO Auto-generated method stub
			SerialProvider provider=super.getSerialProvider(idx);
			provider.setDefaultSerialSettings(115200, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
			return provider;
		}
		
		@Override
		protected long getMaximumQuietTime(int idx) {
			// TODO Auto-generated method stub
			//return 30_000L;
			return Long.MAX_VALUE;
		}
		
		@Override
		protected long getNegotiateInterval(int idx) {
			// TODO Auto-generated method stub
			return 60_000L;
		}

		@Override
		protected String iconResourceName() {
			return "gen_pump1.png";
		}
		
		private String executeGCPCommand(String inputCommand) throws IOException {
			String response = null;
			String defaultCRC = "|0000\r";
			if(inputCommand.startsWith("!INFO_GET_SN")) {
				response = "!INFO_GET_SN^AGG001" +defaultCRC;
				return response;
			}
			
			if(inputCommand.startsWith("!INFO_GET_MAX_RESP_TIME")) {
				response = "!INFO_GET_MAX_RESP_TIME^500" +defaultCRC;
				return response;
			}
			
			if(inputCommand.startsWith("!INFO_GET_MAX_RATE")) {
				String fields[] = inputCommand.split("\\|");
				response = fields[0] + "^1200^ml/h^0" + defaultCRC;
				return response;
			}
			
			if(inputCommand.startsWith("!INFO_GET_DEVICE_NB")) {
				response = "!INFO_GET_DEVICE_NB^2" +defaultCRC;
				return response;
			}
			
			if(inputCommand.startsWith("(!INFO_GET_DEVICE_SN")) {
				response = inputCommand+"^0000^0";
				return response;
			}
			
			if(inputCommand.startsWith("!INF_STOP^")) {
				response = inputCommand+"^0";
				return response;
			}
			
			if(inputCommand.startsWith("!INF_CLEAR_IV")) {
				String fields[] = inputCommand.split("\\^");
				String moduleNumber = fields[1].substring(0,1);
				
				if(moduleNumber.startsWith("0")) {
					if(propDevice instanceof Asena) {
						
					}else if (propDevice instanceof AP4000) {
						//code this part
					}else {
						System.err.println("Invalid pump type selected");
						}
				} else if (moduleNumber.startsWith("1")) {
					if(propDevice instanceof Asena) {
						
					}else if (propDevice instanceof AP4000) {
						//code this part
					}else {
						System.err.println("Invalid pump type selected");
						}
				}else {
					System.err.println("Invalid pump or pump module selected");
					}
				response = "!INF_CLEAR_IV^" + moduleNumber + "^0" + defaultCRC;
				return response;
			}
			
			if(inputCommand.startsWith("!INFO_GET_DEVICE_INFO")) {
				/*
				infCount++;
				if(infCount>9 && infCount<50) {
					int alarmVal=(int)Math.pow(2, OCCLUSION);
					response = "!INFO_GET_DEVICE_INFO"+
							"^0^" + currentPropRate + "^ml/h^0.00000^ml^0^" + (alarmVal) +	//First channel
							"^1^" + currentRemiRate + "^ml/h^0.00000^ml^0^0" + 	//Second channel
							defaultCRC;
				} else {
				*/
					response = "!INFO_GET_DEVICE_INFO"+
							"^0^" + currentPropRate + "^ml/h^0.00000^ml^0^" + currentPropAlarmVal +	//First channel
							"^1^" + currentRemiRate + "^ml/h^0.00000^ml^0^" + currentRemiAlarmVal +	//Second channel
							defaultCRC;
				//}
					
				return response;
			}
			
			if(inputCommand.startsWith("!INF_SET_RATE")) {
				
				String fields[] = inputCommand.split("\\^");
				String moduleNumber = fields[1];
				String pendingInfRate=null;
				
				if(moduleNumber.equals("0")) {
					pendingInfRate=fields[2];
					infRatePROP = Float.parseFloat(fields[2]);
					InfusionProgram program=new InfusionProgram();
					program.bolusRate=-1;
					program.bolusVolume=-1;
					if(infRatePROP<0.5) {
						System.err.println("Setting infusion rate to 0.5 instead of "+infRatePROP);
						program.infusionRate=0.5f;
					} else {
						program.infusionRate=infRatePROP;
					}
					program.requestor="EasyTiva Generic translation";
					program.seconds=-1;
					program.unique_device_identifier=propDevice.getUniqueDeviceIdentifier();
					if(propDevice instanceof Asena) {
						program.head=1;
					}
					if(propDevice instanceof AP4000) {
						program.head=propChannelNum;
					}
					
					// Check if less than 100 msec since last rate command
					if( propDevice instanceof AP4000 && ((System.currentTimeMillis() - lastSetRateTime) < 100) ) {
						try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						infusionProgramWriter.write(program, null);
						lastSetRateTime = System.currentTimeMillis();
					}else {
						infusionProgramWriter.write(program, null);
						lastSetRateTime = System.currentTimeMillis();
					}
//					infusionProgramWriter.write(program, null);
					System.err.println("Published infusion program - "+ System.currentTimeMillis() +program);
					logArea.appendText(program.toString()+"\n");
				}else if(moduleNumber.equals("1")) {
					pendingInfRate=fields[2];
					infRateREMI = Float.parseFloat(fields[2]);
					InfusionProgram program=new InfusionProgram();
					program.bolusRate=-1;
					program.bolusVolume=-1;
					if(infRateREMI<0.5) {
						System.err.println("Setting infusion rate to 0.5 instead of "+infRateREMI);
						program.infusionRate=0.5f;
					} else {
						program.infusionRate=infRateREMI;
					}
					program.requestor="EasyTiva Generic translation";
					program.seconds=-1;
					program.unique_device_identifier=remiDevice.getUniqueDeviceIdentifier();
					if(remiDevice instanceof Asena) {
						program.head=1;
					}
					if(remiDevice instanceof AP4000) {
						program.head=remiChannelNum;
					}
					
					// Check if less than 100 msec since last rate command
					if(  remiDevice instanceof AP4000 && ((System.currentTimeMillis() - lastSetRateTime) < 100) ) {
						try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						infusionProgramWriter.write(program, null);
						lastSetRateTime = System.currentTimeMillis();
					}else {
						infusionProgramWriter.write(program, null);
						lastSetRateTime = System.currentTimeMillis();
					}
					
//					infusionProgramWriter.write(program, null);
					System.err.println("Published infusion program - "+ System.currentTimeMillis() +program);
					logArea.appendText(program.toString()+"\n");
				}else {
					System.err.println("Invalid pump or pump module selected");
				}
				
				response = "!INF_SET_RATE^" + moduleNumber + "^" + pendingInfRate + "^ml/h^0" +defaultCRC;
				return response;
			}
			
			if(inputCommand.startsWith("!INFO_GET_SYRINGE_INFO^")) {
				String fields[] = inputCommand.split("\\^");
				String moduleNumber = fields[1].substring(0,1);
				if(propDevice instanceof AP4000) {
					response = "!INFO_GET_SYRINGE_INFO^"+moduleNumber+"^Neurowave^AP4000^50.000000^0|0000\r";
					return response;
				}
				if(remiDevice instanceof AP4000) {
					response = "!INFO_GET_SYRINGE_INFO^"+moduleNumber+"^Neurowave^AP4000^50.000000^0|0000\r";
					return response;
				}
				if(propDevice instanceof Asena) {
					response = "!INFO_GET_SYRINGE_INFO^"+moduleNumber+"^Alaris^Asena^50.000000^0|0000\r";
					return response;
				}
				if(remiDevice instanceof Asena) {
					response = "!INFO_GET_SYRINGE_INFO^"+moduleNumber+"^Alaris^Asena^50.000000^0|0000\r";
					return response;
				}
			}
			
			if(inputCommand.startsWith("!INFO_GET_DRUG_INFO^")) {
				String fields[] = inputCommand.split("\\^");
				String moduleNumber = fields[1].substring(0,1);
				if(moduleNumber.equals("0")) {
					response = "!INFO_GET_DRUG_INFO^0^N01AX10^Propofol^PROPOFOL^10.000000^mg/ml^0|0000\r";
					return response;
				}
				if(moduleNumber.equals("1")) {
					response = "!INFO_GET_DRUG_INFO^1^N01AH06^Remifentanil^REMIFENTANIL^20.000000^ug/ml^0|0000\r";
					return response;
				}
			}
			
//			if(inputCommand.startsWith("!INFO_GET_DRUG_INFO")) {
//				String fields[] = inputCommand.split("\\^");
//				String moduleNumber = fields[1].substring(0,1);
//				String drugUUID, drugName, drugLabel, drugConc, drugConcUnit;
//				drugUUID =drugName =drugLabel =drugConc =drugConcUnit ="";
////				System.err.println("Inside get drug info for " + module);
//				
//				if(moduleNumber.startsWith("0")) {
//					if(propDevice instanceof Asena) {
//						drugUUID = "N01AX10";
//						drugName = "Propofol";
//						drugLabel = "PROPOFOL";
//						drugConc = "10.000";
//						drugConcUnit = "mg/ml";
//					}else if (propDevice instanceof AP4000) {
//						//code this part
//					}else {
//						System.err.println("Invalid pump type selected");
//						}
//				} else if (moduleNumber.startsWith("1")) {
//					if(propDevice instanceof Asena) {
//						drugUUID = "N01AH06";
//						drugName = "Remifentanil";
//						drugLabel = "REMIFENTANIL";
//						drugConc = "20.000";
//						drugConcUnit = "ug/ml";
//					}else if (propDevice instanceof AP4000) {
//						//code this part
//					}else {
//						System.err.println("Invalid pump type selected");
//						}
//				}else {
//					System.err.println("Invalid pump or pump module selected");
//					}
//				response = "!INFO_GET_DRUG_INFO^" + moduleNumber +"^"+ drugUUID +"^"+ drugName +"^"+ 
//					drugLabel +"^"+ drugConc +"^"+ drugConcUnit + "^0" + defaultCRC;
//				return response;
//			}
			
			if(inputCommand.startsWith("!SETUP_RCTL_ENABLE")) {
				response = "!SETUP_RCTL_ENABLE^0000" + defaultCRC;
				return response;
			}
			
			if(inputCommand.startsWith("!SETUP_RCTL_DISABLE")) {
				response = "!SETUP_RCTL_DISABLE^0000" + defaultCRC;
				return response;
			}
			
			System.err.println("Unhandled request from GCP Requestor: "+ inputCommand);
			return response;
		}
		
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

}
