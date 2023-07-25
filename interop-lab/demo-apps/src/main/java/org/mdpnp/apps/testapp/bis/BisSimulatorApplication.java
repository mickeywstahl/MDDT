package org.mdpnp.apps.testapp.bis;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFileChooser;

import org.apache.commons.math3.random.RandomDataGenerator;
import org.apache.commons.math3.util.Precision;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.devices.AbstractDevice;
import org.mdpnp.devices.DeviceDriverProvider;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.devices.medsteer.bis.BisMonitor;
import org.mdpnp.devices.DeviceIdentityBuilder;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialProviderFactory;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.SubscriberQos;

import ice.FlowRateObjectiveDataWriter;
import ice.MDSConnectivity;
import ice.Patient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.text.Font;

/**
 * An application for simulating the responses of a BIS monitor to any client that speaks the BIS protocol.
 * Starting the app will create a serial device, but the device is tied to this application, rather than being
 * created through the OpenICE device creation UI.  The reason for that is that we want to be able to exert a
 * <em>lot</em> of control over the device, and it's much easier to do that when the app can call methods directly
 * on the device, as opposed to needing many DDS objectives to implement that control.
 * @author simon
 *
 */
public class BisSimulatorApplication {
	
	private static final Logger log = LoggerFactory.getLogger(BisMonitor.class);
	private static final Logger easyTivaLog = LoggerFactory.getLogger("easy.tiva");
	
	/**
	 * Template string to create a version string from.  The %s placeholder is for a date and time string.
	 */
	private static final String versionTemplate="VERSION |%s|    3.30|    3.30|    1.25|    1.08|    3.15|    3.00|C008800\r\n";
	
	/**
	 * A &quot;constant&quot; string representing a header line to be sent to the EasyTiva.  It represents the high level fields.
	 */
	private static final String S_HDR3="S_HDR3             |SYS 3.30|        |        |        |        |        |        |Ch. 1   |        |        |        |        |        |        |        |        |Ch. 2   |        |        |        |        |        |        |        |        |Ch. 12  |        |        |        |        |        |        |        |        |\r\n";
	
	/**
	 * A &quot;constant&quot; string representing a header line to be sent to the EasyTiva.  It represents the low level fields.
	 */
	private static final String TIME=  "TIME               |DSC     |PIC     |Filters |Alarm   |Lo-Limit|Hi-Limit|Silence |SR      |SEF     |BISBIT  |BIS     |TOTPOW  |EMGLOW  |SQI     |IMPEDNCE|ARTF2   |SR      |SEF     |BISBIT  |BIS     |TOTPOW  |EMGLOW  |SQI     |IMPEDNCE|ARTF2   |SR      |SEF     |BISBIT  |BIS     |TOTPOW  |EMGLOW  |SQI     |IMPEDNCE|ARTF2   |\r\n";
	
	private static final String bisLineTemplate="%19s|      13|      57|On      |None    |Off     |Off     |No      |%8s|    16.3|    0600|%8s|    59.3|%8s|%8s|    3277|20000000|%8s|    16.3|    0600|%8s|    59.3|%8s|%8s|    3277|20000000|%8s|    18.5|    0600|%8s|    53.6|%8s|%8s|       0|20000000|\r\n";
	//                                          "TIME               |DSC     |PIC     |Filters |Alarm   |Lo-Limit|Hi-Limit|Silence |SR      |SEF     |BISBIT  |BIS     |TOTPOW  |EMGLOW  |SQI     |IMPEDNCE|ARTF2   |SR      |SEF     |BISBIT  |BIS     |TOTPOW  |EMGLOW  |SQI     |IMPEDNCE|ARTF2   |SR      |SEF     |BISBIT  |BIS     |TOTPOW  |EMGLOW  |SQI     |IMPEDNCE|ARTF2   |\r\n";
	/**
	 * A date/time formatter that formats according to what is required in
	 * VERSION and data lines.
	 */
	DateTimeFormatter formatter=new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendValue(ChronoField.MONTH_OF_YEAR,2)
			.appendLiteral('/')
			.appendValue(ChronoField.DAY_OF_MONTH,2)
			.appendLiteral('/')
			.appendValue(ChronoField.YEAR,4)
			.appendLiteral(' ')
			.appendValue(ChronoField.HOUR_OF_DAY,2)
			.appendLiteral(':')
			.appendValue(ChronoField.MINUTE_OF_HOUR,2)
			.appendLiteral(':')
			.appendValue(ChronoField.SECOND_OF_MINUTE,2)
			.toFormatter();
	
	
	private BufferedReader fromDevice;
	private InputStream fromRequestor;
	private BufferedOutputStream toRequestor;
	private Thread dataThread;
	
	private boolean keepGoing;
	
	private boolean connected;
	
	@FXML
	ComboBox<String> serialPortsCombo;
	
	@FXML
	TextField sourceFileField;
	
	@FXML
	TextArea loggingArea;
	
	@FXML
	Label bis1;
	
	@FXML
	Label bis2;
	
	@FXML
	Label bis12;
	
	@FXML
	CheckBox bisNoiseEnabled;
	
	@FXML
	CheckBox emgNoiseEnabled;
	
	@FXML
	TextField bisNoiseAmplitude;
	
	@FXML
	TextField bisNoiseStdDev;
	
	@FXML
	TextField emgNoiseAmplitude;
	
	@FXML
	TextField emgNoiseStdDev;
	
	@FXML
	CheckBox continuousLoop;
	
	private ApplicationContext parentContext;
	private Subscriber subscriber;
	
	/**
	 * The "current" patient, used to determine if the patient has changed
	 */
	private Patient currentPatient;
	
	private MDSHandler mdsHandler;
	
	/**
	 * A count of how many times the EasyTiva has requested the data thread.
	 */
	private int easyTivaCount;

	public BisSimulatorApplication() {
		// TODO Auto-generated constructor stub
	}

	public void set(ApplicationContext parentContext, DeviceListModel deviceListModel, NumericFxList numericList,
			SampleArrayFxList sampleList, FlowRateObjectiveDataWriter objectiveWriter, MDSHandler mdsHandler,
			VitalModel vitalModel, Subscriber subscriber, EMRFacade emr) {
		this.parentContext=parentContext;
		this.subscriber=subscriber;
		this.mdsHandler=mdsHandler;
	}

	public void start(EventLoop eventLoop, Subscriber subscriber) {
		List<String> l = SerialProviderFactory.getDefaultProvider().getPortNames();
		ObservableList<String> ports=FXCollections.unmodifiableObservableList(FXCollections.observableArrayList(l));
		serialPortsCombo.setItems(ports);
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
	
	private DeviceDriverProvider.DeviceAdapter simBisDeviceAdapter;
	private SimulatedBisDevice simBisDevice;
	
	private void createSimBisDevice() {
		if(simBisDeviceAdapter==null) {
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
	                return new SimulatedBisDevice(subscriber, publisher, eventLoop,1);
	            }
	            
	            
	        };
	        
	        try {
	        	simBisDeviceAdapter = df.create((AbstractApplicationContext) parentContext);

                // TODO Make this more elegant
                List<String> strings = new ArrayList<String>();
                SubscriberQos qos = new SubscriberQos();
//                System.err.println("assignedSubscriber is "+assignedSubscriber);
                subscriber.get_qos(qos);

                for (int i = 0; i < qos.partition.name.size(); i++) {
                    strings.add((String) qos.partition.name.get(i));
                }

                simBisDeviceAdapter.setPartition(strings.toArray(new String[0]));

            }
            catch(Exception ex) {
                throw new RuntimeException("Failed to create a driver", ex);
            }
		}
		simBisDevice=(SimulatedBisDevice)simBisDeviceAdapter.getDevice();
		if(currentPatient!=null) {
			simBisDeviceAdapter.setPartition(new String[] {PartitionAssignmentController.toPartition(currentPatient.mrn)});
			String partitionToAssociate=PartitionAssignmentController.toPartition(currentPatient.mrn);
	        MDSConnectivity connectivity=new MDSConnectivity();
	        connectivity.partition=partitionToAssociate;
	        connectivity.unique_device_identifier=simBisDevice.getUniqueDeviceIdentifier();
	        mdsHandler.publish(connectivity);
		}
		String selectedPort=serialPortsCombo.getSelectionModel().getSelectedItem();
		simBisDevice.connect(selectedPort);
		
	}
	
	private void destroyBisDevice() {
		if(simBisDevice!=null) {
			simBisDevice.shutdown();
			simBisDevice=null;
			simBisDeviceAdapter.stop();
			simBisDeviceAdapter=null;
		}
	}
	
	/**
	 * A simulated BIS device.
	 * @author simon
	 *
	 */
	class SimulatedBisDevice extends AbstractSerialDevice {

		public SimulatedBisDevice(Subscriber subscriber, Publisher publisher, EventLoop eventLoop,
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
			//We only suppose ascii mode, and the settings are the same on both channels.
			serialProvider.setDefaultSerialSettings(9600, DataBits.Eight , Parity.None, StopBits.One, FlowControl.None);
			return serialProvider;
		}

		@Override
		protected void doInitCommands(int idx) throws IOException {
			
			
		}

		@Override
		protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
			System.err.println("process starts for idx "+idx);
			//fromRequestor=new BufferedInputStream(inputStream);
			fromRequestor=inputStream;
			toRequestor=new BufferedOutputStream(outputStream);
			System.err.println("Created in and out for requestor "+idx);
			byte oneByteCommand[]=new byte[1];
			while(true) {
				if(idx==0) {
					System.err.println("Receiving from requestor idx "+idx);
					//Commands from requestor will be single bytes;
					int read=fromRequestor.read(oneByteCommand);
					System.err.println("BisSimulator Read one byte command FROM REQUESTOR - "+(char)oneByteCommand[0]);
					if(read==1) {
						if(!connected) {
							reportConnected("Received a command from requestor");
						}
						//Forward the one byte command to the BIS.
						//And handle any response.
						switch (oneByteCommand[0]) {
						case 'C':
							log.info("BisSimulator Not expecting response from BIS for command 'C'");
							keepGoing=false;	//Tell any existing data thread to stop.
							if(dataThread!=null) {
								dataThread.interrupt();
							}
							break;
						case 'V':
							if( ! continuousLoop.isSelected() && easyTivaCount>0) {
								//We have already looped once.
								System.err.println("easyTivaCount is "+easyTivaCount+" and continuous looping is not enabled");
								break;
							}
							if(dataThread==null || !dataThread.isAlive()) {
								if(fromDevice!=null) {
									//Previously open.  Close, so it can be re-opened.
									fromDevice.close();
								}
								//Create reader from source file.
								fromDevice=new BufferedReader(new InputStreamReader(new FileInputStream(sourceFileField.getText())));
								System.err.println("BisSimulator Created in and out for device "+idx);
								//We assume that the first line of the file will be a version string.
								
								LocalDateTime ldt=LocalDateTime.now();
								String dateStr=formatter.format(ldt);
								String versionLine=String.format(versionTemplate, dateStr);
								System.err.print("BisSimulator got version line from template - "+versionLine);
								toRequestor.write(versionLine.getBytes());
								toRequestor.flush();
								loggingArea.appendText(versionLine);
							} else {
								//This seems an unlikely situation that didn't happen once we got everything working.
								System.err.println("Skipping version request as we are already sending data");
							}
							break;
						case 'U':
							if( ! continuousLoop.isSelected() && easyTivaCount>0) {
								//We have already looped once.
								System.err.println("easyTivaCount is "+easyTivaCount+" and continuous looping is not enabled");
								break;
							}
							System.err.println("BisSimulator Starting data thread");
							log.info("Starting data thread for command 'U'");
							startDataThread();
							break;
						default:
							System.err.println("BisSimulator Unexpected command "+(byte)oneByteCommand[0]+" from EasyTIVA to BIS");
							log.error("Unexpected command "+(byte)oneByteCommand[0]+" from EasyTIVA to BIS");
							break;
						}
					}
				}
			}			
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
	
	private void startDataThread() {
		if(dataThread==null) {
			dataThread=new DataThread();
			keepGoing=true;
			dataThread.setName("BIS reader thread");
			dataThread.start();
			System.err.println("Started the EasyTIVA thread");
			return;
		}
		//If we get here , data thread was not null.  This shouldn't really happen.
		log.error("startDataThread called but dataThread already not null");
		//Flag the existing one to stop.
		keepGoing=false;
		//And interrupt it - not really useful.
		dataThread.interrupt();
		//Make a new one
		dataThread=new DataThread();
		dataThread.setName("BIS reader thread");
		//Reset keepGoing
		keepGoing=true;
		//And start it.
		dataThread.start();
	}
	
	class DataThread extends Thread {
		
		public DataThread() {
			super();
			System.err.println("Constructor for DataThread...");
		}

		@Override
		public void run() {
			//The data file should already have read the VERSION line.
			//Now we send the next two lines.
			try {
				toRequestor.write(S_HDR3.getBytes());
				toRequestor.flush();
				loggingArea.appendText(S_HDR3);
				sleep(95);
				toRequestor.write(TIME.getBytes());
				toRequestor.flush();
				loggingArea.appendText(TIME);
			} catch (IOException ioe) {
				log.error("Could not write to EasyTiva");
			} catch (InterruptedException ie) {
				// TODO Auto-generated catch block
				log.error("Unexpected interruption",ie);
			}
			RandomDataGenerator rdg=new RandomDataGenerator();
			String nextLine;
			while(keepGoing && !isInterrupted()) {
				try {
					nextLine=fromDevice.readLine();
//					if(nextLine==null || nextLine.length()==0) {
//						keepGoing=false;
//						System.err.println("Probably ran out of lines");
//						loggingArea.appendText("!!!!!!! END OF INPUT FILE "+sourceFileField.getText()+" !!!!!!!");
//						easyTivaCount++;
//						interrupt();
//					}
					if((nextLine==null || nextLine.length()==0) && continuousLoop.isSelected())  {
						fromDevice=new BufferedReader(new InputStreamReader(new FileInputStream(sourceFileField.getText())));
						nextLine=fromDevice.readLine();
						nextLine=fromDevice.readLine();
						easyTivaCount++;
						if (easyTivaCount  == 10) {
							keepGoing=false;
							System.err.println("Probably ran out of lines");
							loggingArea.appendText("!!!!!!! END OF INPUT FILE "+sourceFileField.getText()+" !!!!!!!");
							interrupt();
						}
						continue;
					}else if(nextLine==null || nextLine.length()==0) {
						keepGoing=false;
						System.err.println("Probably ran out of lines");
						loggingArea.appendText("!!!!!!! END OF INPUT FILE "+sourceFileField.getText()+" !!!!!!!");
						easyTivaCount++;
						interrupt();
					}
					String[] csvFields=nextLine.split(",");
					LocalDateTime ldt=LocalDateTime.now();
					String dateStr=formatter.format(ldt);
					String bis=csvFields[1];
					String emg=csvFields[3];
					if(bisNoiseEnabled.isSelected()) {
						System.err.print("BIS without noise is "+bis);
						//Add noise to bis.
						double amp=Double.parseDouble(bisNoiseAmplitude.getText());
						double sigma=Double.parseDouble(bisNoiseStdDev.getText());
						double nextGauss=rdg.nextGaussian(0,sigma)*amp;
						double dBis=Double.parseDouble(bis);
						dBis+=nextGauss;
						dBis=Precision.round(dBis, 1);
						bis=Double.toString(dBis);
						System.err.println(", with noise is "+bis);
					}
					if(emgNoiseEnabled.isSelected()) {
						System.err.print("EMG without noise is "+emg);
						//Add noise to bis.
						double amp=Double.parseDouble(emgNoiseAmplitude.getText());
						double sigma=Double.parseDouble(emgNoiseStdDev.getText());
						double nextGauss=rdg.nextGaussian(0,sigma)*amp;
						double dEmg=Double.parseDouble(emg);
						dEmg+=nextGauss;
						dEmg=Precision.round(dEmg, 1);
						emg=Double.toString(dEmg);
						System.err.println(", with noise is "+emg);
					}
					String fullLine=String.format(bisLineTemplate, dateStr, csvFields[4], bis, emg, csvFields[2], csvFields[4], bis, emg, csvFields[2], csvFields[4], bis, emg, csvFields[2]);
					toRequestor.write(fullLine.getBytes());
					toRequestor.flush();
					loggingArea.appendText(fullLine);
					Platform.runLater(new Runnable() {
						public void run() {
							bis1.setText(csvFields[1]);
							bis2.setText(csvFields[1]);
							bis12.setText(csvFields[1]);
						}
					});
					System.err.println("Wrote "+fullLine+" to the EasyTIVA");
					easyTivaLog.trace(fullLine);
					sleep(4995);
				} catch (IOException ioe) {
					log.error("Failed to read from the BIS in data thread");
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			//If we get here, keepGoing is no longer true.
			log.info("data thread asked to stop or ran out of lines and set keepGoing to false.");
			return;
		}
	}
	
	public void runBis() {
		createSimBisDevice();
	}
	
	public void stopBis() {
		destroyBisDevice();
		
	}
	
	public void chooseFile() {
		JFileChooser chooser=new JFileChooser();
		int res=chooser.showOpenDialog(null);
		if(res==JFileChooser.APPROVE_OPTION) {
			File f=chooser.getSelectedFile();
			sourceFileField.setText(f.getAbsolutePath());
		}
	}

}
