package org.mdpnp.apps.testapp.easytiva;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFileChooser;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.devices.AbstractDevice;
import org.mdpnp.devices.DeviceDriverProvider;
import org.mdpnp.devices.DeviceIdentityBuilder;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialProviderFactory;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.SubscriberQos;

import ice.FlowRateObjectiveDataWriter;
import ice.MDSConnectivity;
import ice.Patient;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.text.Font;

public class EasyTivaSimulatorApplication {
	
	@FXML
	private TextField pFileField;
	@FXML
	private TextField rFileField;
	@FXML
	private ComboBox<String> pSerialPortsCombo;
	@FXML
	private ComboBox<String> rSerialPortsCombo;
	@FXML
	TextArea loggingArea;
	
	private ApplicationContext parentContext;
	private Subscriber subscriber;
	
	/**
	 * The "current" patient, used to determine if the patient has changed
	 */
	private Patient currentPatient;
	
	private MDSHandler mdsHandler;

	public EasyTivaSimulatorApplication() {
		// TODO Auto-generated constructor stub
	}

	public void set(ApplicationContext parentContext, DeviceListModel deviceListModel, NumericFxList numericList,
			SampleArrayFxList sampleList, FlowRateObjectiveDataWriter objectiveWriter, MDSHandler mdsHandler,
			VitalModel vitalModel, Subscriber subscriber, EMRFacade emr) {
		this.parentContext=parentContext;
		this.subscriber=subscriber;
		this.mdsHandler=mdsHandler;
		// TODO Auto-generated method stub
		
	}

	public void start(EventLoop eventLoop, Subscriber subscriber) {
		List<String> l = SerialProviderFactory.getDefaultProvider().getPortNames();
		ObservableList<String> ports=FXCollections.unmodifiableObservableList(FXCollections.observableArrayList(l));
		rSerialPortsCombo.setItems(ports);
		pSerialPortsCombo.setItems(ports);
		loggingArea.setFont(Font.font("monospace"));
		loggingArea.setEditable(false);
		
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
	
	public void chooseRFile() {
		JFileChooser chooser=new JFileChooser();
		int res=chooser.showOpenDialog(null);
		if(res==JFileChooser.APPROVE_OPTION) {
			File f=chooser.getSelectedFile();
			rFileField.setText(f.getAbsolutePath());
		}
	}
	
	public void choosePFile() {
		JFileChooser chooser=new JFileChooser();
		int res=chooser.showOpenDialog(null);
		if(res==JFileChooser.APPROVE_OPTION) {
			File f=chooser.getSelectedFile();
			pFileField.setText(f.getAbsolutePath());
		}
	}
	
	public void runEasyTiva() {
		createSimEasyTivaDevice();
	}
	
	public void stopEasyTiva() {
		destroyEasyTivaDevice();
	}
	
	private DeviceDriverProvider.DeviceAdapter simEasyTivaDeviceAdapter;
	private SimulatedEasyTivaDevice simEasyTivaDevice;
	
	private void createSimEasyTivaDevice() {
		if(simEasyTivaDeviceAdapter==null) {
			DeviceDriverProvider.SpringLoadedDriver df = new DeviceDriverProvider.SpringLoadedDriver() {
	            @Override
	            public DeviceType getDeviceType() {
	                return new DeviceType(ice.ConnectionType.Serial, "Simulated BIS", "SimBis", "SimBis", 1);
	            }
	
	            @Override
	            public AbstractDevice newInstance(AbstractApplicationContext context) throws Exception {
	                EventLoop eventLoop = context.getBean("eventLoop", EventLoop.class);
	                Subscriber subscriber = context.getBean("subscriber", Subscriber.class);
	                Publisher publisher = context.getBean("publisher", Publisher.class);
	                return new SimulatedEasyTivaDevice(subscriber, publisher, eventLoop,1);
	            }
	            
	            
	        };
	        
	        try {
	        	simEasyTivaDeviceAdapter = df.create((AbstractApplicationContext) parentContext);

                // TODO Make this more elegant
                List<String> strings = new ArrayList<String>();
                SubscriberQos qos = new SubscriberQos();
//                System.err.println("assignedSubscriber is "+assignedSubscriber);
                subscriber.get_qos(qos);

                for (int i = 0; i < qos.partition.name.size(); i++) {
                    strings.add((String) qos.partition.name.get(i));
                }

                simEasyTivaDeviceAdapter.setPartition(strings.toArray(new String[0]));

            }
            catch(Exception ex) {
                throw new RuntimeException("Failed to create a driver", ex);
            }
		}
		simEasyTivaDevice=(SimulatedEasyTivaDevice)simEasyTivaDeviceAdapter.getDevice();
		if(currentPatient!=null) {
			simEasyTivaDeviceAdapter.setPartition(new String[] {PartitionAssignmentController.toPartition(currentPatient.mrn)});
			String partitionToAssociate=PartitionAssignmentController.toPartition(currentPatient.mrn);
	        MDSConnectivity connectivity=new MDSConnectivity();
	        connectivity.partition=partitionToAssociate;
	        connectivity.unique_device_identifier=simEasyTivaDevice.getUniqueDeviceIdentifier();
	        mdsHandler.publish(connectivity);
		}
		String rSelectedPort=rSerialPortsCombo.getSelectionModel().getSelectedItem();
		String pSelectedPort=pSerialPortsCombo.getSelectionModel().getSelectedItem();
		if(rSelectedPort!=null && pSelectedPort!=null) {
			//Both ports selected.
			simEasyTivaDevice.connect(rSelectedPort+","+pSelectedPort);
		} else if(rSelectedPort!=null) {
			//Remifentanil only
			simEasyTivaDevice.connect(rSelectedPort);
		} else {
			simEasyTivaDevice.connect(pSelectedPort);
		}
	}
	
	private void destroyEasyTivaDevice() {
		if(simEasyTivaDevice!=null) {
			simEasyTivaDevice.shutdown();
			simEasyTivaDevice=null;
			simEasyTivaDeviceAdapter.stop();
			simEasyTivaDeviceAdapter=null;
		}
	}
	
	/**
	 * A simulated BIS device.
	 * @author simon
	 *
	 */
	class SimulatedEasyTivaDevice extends AbstractSerialDevice {
		
		List<String> allLines;
		int currentLineIndex=0;
		private BufferedReader fromPump;
		private BufferedWriter toPump;
		boolean connected=false;
		/**
		 * Elapsed time through the process, used to track how long to sleep before sending the next line.
		 */
		long elapsed=0;

		public SimulatedEasyTivaDevice(Subscriber subscriber, Publisher publisher, EventLoop eventLoop,
				int countSerialPorts) {
			super(subscriber, publisher, eventLoop, countSerialPorts);
			deviceIdentity.unique_device_identifier=DeviceIdentityBuilder.randomUDI();
			deviceIdentity.manufacturer="ICE";
			deviceIdentity.model="Simulated BIS";
			writeDeviceIdentity();
		}
		
		@Override
		public SerialProvider getSerialProvider(int idx) {
			SerialProvider serialProvider=super.getSerialProvider(idx);
			//These settings are the ones for an Alaris pump, because that's what we'll be writing to/reading from
			serialProvider.setDefaultSerialSettings(38400, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
			return serialProvider;
		}

		@Override
		protected void doInitCommands(int idx) throws IOException {
			if(idx==0) {
				reportConnected("Sim ET running");
			}
		}

		@Override
		protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
			System.err.println("process starts for idx "+idx);
			if(idx==0) {
				//Remifentanil
				allLines=Files.readAllLines(new File(rFileField.getText()).toPath());
			} else {
				allLines=Files.readAllLines(new File(pFileField.getText()).toPath());
			}
			
			fromPump=new BufferedReader(new InputStreamReader(inputStream));
			toPump=new BufferedWriter(new OutputStreamWriter(outputStream));
			
			String nextLine;
			while( (nextLine=allLines.get(currentLineIndex++)) !=null ) {
				if(nextLine.length()==0) {
					continue;	//Blank line - just steam on to the next one.
				}
				System.err.println("Calling processLine for "+nextLine);
				elapsed=processLine(elapsed, nextLine);
			}
		}
			
		long processLine(long elapsed, String nextLine) {
			String[] parts=nextLine.split("\t");
			long nextTime=Long.valueOf(parts[0]);
			long howLongToSleep=nextTime-elapsed;
			System.err.println("Need to sleep for "+howLongToSleep);
			try {
				Thread.sleep(howLongToSleep);
				toPump.write(nextLine+"\r");
				toPump.flush();
				loggingArea.appendText(">>> "+nextLine+"\n");
				String response=fromPump.readLine();
				loggingArea.appendText("<<< "+response+"\n");
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			return nextTime;
		}

		@Override
		public boolean connect(String address) {
			System.err.println("connect called with address "+address+" - need to replace with something");
			return super.connect(address);
		}

		@Override
		protected void connect(int idx) {
			// TODO Auto-generated method stub
			super.connect(idx);
		}

		@Override
		protected long getMaximumQuietTime(int idx) {
			return Integer.MAX_VALUE;
		}

		@Override
		protected String iconResourceName() {
			return "bis.jpg";
		}
		
	}

}
