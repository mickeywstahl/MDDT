package org.mdpnp.devices.medsteer.bis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

public class BisSimulator extends AbstractSerialDevice {
	
	private static final Logger log = LoggerFactory.getLogger(BisSimulator.class);
	
	//FAKE VALUE FOR NOW
	private boolean initDone=true;
	
	private BufferedOutputStream toDevice;
	private BufferedInputStream fromDevice;
	
	byte commandByte[]=new byte[1];
	
	/**
	 * Flag to tell the DataThread thread to send data.
	 */
	private boolean keepGoing;

	/**
	 * Flag to tell the DataThread thread to send a header record.
	 */
	private boolean emitHeaderRecord;
	
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
			.appendValue(ChronoField.SECOND_OF_MINUTE)
			.toFormatter();
	
	private boolean binary;
	
	public BisSimulator(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,1);
	}

	public BisSimulator(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
        writeDeviceIdentity();
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
		System.err.println("doInitCommands called");
		reportConnected("Fake connection in doInitCommands for now");
	}

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		System.err.println("process called");
		toDevice=new BufferedOutputStream(outputStream);
		fromDevice=new BufferedInputStream(inputStream);
		System.err.println("Created buffered to and from device");
		while(true) {
			if(!initDone) {
				System.err.println("process does not have initDone yet");
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				//System.err.println("Calling handle commands");
				//handleCommands();
				System.err.println("Starting data thread");
				sendHeaderThenData();
				try {
					Thread.sleep(5000);
				} catch (InterruptedException ie) {
					ie.printStackTrace();
				}
			}
		}

	}
	
	class DataThread extends Thread {

		@Override
		public void run() {
			while(keepGoing) {
				if(emitHeaderRecord) {
					String shdr="S_HDR3             |SYS 3.30|        |        |        |        |        |        |Ch. 1   |        |        |        |        |        |        |        |        |Ch. 2   |        |        |        |        |        |        |        |        |Ch. 12  |        |        |        |        |        |        |        |        |\r\n";
					String time="TIME               |DSC     |PIC     |Filters |Alarm   |Lo-Limit|Hi-Limit|Silence |SR12    |SEF08   |BISBIT00|BIS     |TOTPOW08|EMGLOW01|SQI10   |IMPEDNCE|ARTF2   |SR12    |SEF08   |BISBIT00|BIS     |TOTPOW08|EMGLOW01|SQI10   |IMPEDNCE|ARTF2   |SR12    |SEF08   |BISBIT00|BIS     |TOTPOW08|EMGLOW01|SQI10   |IMPEDNCE|ARTF2   |\r\n";
					try {
						synchronized (toDevice) {
							System.err.println("writing shdr bytes");
							toDevice.write(shdr.getBytes());
							toDevice.flush();
							//TODO: Check the duration of this pause.
							sleep(50);
							System.err.println("writing time bytes");
							toDevice.write(time.getBytes());
							toDevice.flush();
						}
					} catch (IOException ioe) {
						log.error("Failed to write header bytes in data thread", ioe);
						ioe.printStackTrace();
					} catch (InterruptedException e) {
						if(!keepGoing) {
							//Asked to stop.
							return;
						}
					}
				}
				//Emit data lines, in a loop
				LocalDateTime ldt=LocalDateTime.now();
				String dataLine=formatter.format(ldt)+"|      13|       0|On      |None    |Off     |Off     |No      |     0.0|     0.0|    8000|     0.0|     0.0|     0.0|     0.0|       1|06001000|     0.0|     0.0|    8000|     0.0|     0.0|     0.0|     0.0|       1|06001000|     0.0|     0.0|    8000|     0.0|     0.0|     0.0|     0.0|       0|06001000|\r\n";
				synchronized (toDevice) {
					try {
						System.err.println("Writing data line "+dataLine);
						toDevice.write(dataLine.getBytes());
						toDevice.flush();
					} catch (IOException ioe) {
						log.error("Failed to data bytes in data thread", ioe);
					}
				}
				try {
					sleep(5000);
				} catch (InterruptedException e) {
					if(!keepGoing) {
						//Asked to stop.
						return;
					}
				}
			}
		}
		
	}
	
	/**
	 * An instance of the dataThread, that can be created/started/stopped from the various methods
	 */
	DataThread dataThread;

	/**
	 * Handle commands that are normally sent to the BIS, using the ASCII protocol implementation.
	 * @throws IOException 
	 */
	private void handleCommands() throws IOException {
		System.err.println("About to read");
		int numRead=fromDevice.read(commandByte);
		switch (commandByte[0]) {
		case 'C':
			clearOutput();
			break;
		case 'D':
			sendHeaderThenData();
			break;
		case 'E':
			errorRecordsOn();
			break;
		case 'e':
			errorRecordsOff();
			break;
		case 'N':
			eventRecordsOn();
			break;
		case 'n':
			eventRecordsOff();
			break;
		case 'U':
			updateOutputOn();
			break;
		case 'V':
			versionRequest();
			break;
		case 'Z':
			impendanceRecordsOn();
			break;
		case 'z':
			impedanceRecordsOff();
			break;
		default:
			break;
		}
		
	}

	private void impedanceRecordsOff() {
		log.warn("Client asked for impedance records off - this is unimplemented");
	}

	private void impendanceRecordsOn() {
		log.warn("Client asked for impedance records off - this is unimplemented");
	}

	private void versionRequest() throws IOException {
		LocalDateTime ldt=LocalDateTime.now();
		String ver="VERSION |"+formatter.format(ldt)+"|    3.30|    3.30|    1.25|    1.08|    3.15|    3.00|C008800\r\n";
		synchronized (toDevice) {
			toDevice.write(ver.getBytes());
		}
	}

	private void updateOutputOn() {
		//TODO: It is not clear what the difference is between this and D.
		/*
		 * So we will assume that this is supposed to start sending data without
		 * sending a header.
		 */
		emitHeaderRecord=false;
		if(dataThread==null) {
			dataThread=new DataThread();
			dataThread.start();
		}
		
	}

	private void eventRecordsOff() {
		log.warn("Client asked for event records off - this is unimplemented");
		
	}

	private void eventRecordsOn() {
		log.warn("Client asked for event records on - this is unimplemented");
		
	}

	private void errorRecordsOff() {
		log.warn("Client asked for error records off - this is unimplemented");
		
	}

	private void errorRecordsOn() {
		log.warn("Client asked for error records on - this is unimplemented");
		
	}

	private void sendHeaderThenData() {
		/*
		 * Set the emit header indicator to true.  If the thread is already running,
		 * that will cause another header record to be sent.
		 */
		emitHeaderRecord=true;
		/*
		 * If the data thread isn't running, create one and start it.
		 */
		if(dataThread==null) {
			keepGoing=true;
			dataThread=new DataThread();
			dataThread.start();
		} else {
			System.err.println("data thread already running");
		}
		
	}

	private void clearOutput() {
		if(dataThread!=null) {
			keepGoing=false;
			dataThread.interrupt();
			dataThread=null;
		}
	}
	
	@Override
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider serialProvider=super.getSerialProvider(idx);
		if(binary) {
			serialProvider.setDefaultSerialSettings(57600, DataBits.Eight , Parity.None, StopBits.One, FlowControl.None);
		} else {
			//Ascii mode.
			serialProvider.setDefaultSerialSettings(9600, DataBits.Eight , Parity.None, StopBits.One, FlowControl.None);
		}
		return serialProvider;
	}

	@Override
	/**
	 * Since we don't know how long a device might go without asking for any data,
	 * we just set a large max quiet time....
	 */
	protected long getMaximumQuietTime(int idx) {
		return Integer.MAX_VALUE;	//no idea yet if this is sensible.
	}
}
