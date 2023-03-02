package org.mdpnp.devices.alaris;

import java.io.BufferedInputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.lang3.ArrayUtils;
import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.rtiapi.data.EventLoop;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

import ice.Numeric;

public class Asena_backup extends AbstractSerialDevice {
	
private boolean initDone;
	
	private BufferedInputStream fromDevice;
	private BufferedOutputStream toDevice;
	
	private DeviceClock.WallClock wallClock;
	
	private String InstSerial;
	private String ConnectionStatus;
	private String AlarmNotification;
	private String InfMode;
	private String InfRate;
	private String InfRateUnit;
	private String VolumeInf;
	private String InfTimeRemaining;
	
	private final InstanceHolder<Numeric> flowRateHolderHead;
	
	private static final String INFSTATUS = "!INF|4961\r";
	private static final String COMMSPROTOCOL = "!COMMS_PROTOCOL|E8DA\r";
	private static final String INSTSERIAL = "!INST_SERIALNO|457D\r";
	private static final String REMOTEENABLE = "!REMOTE_CTRL^ENABLED^3CC1|B1F1\r";
	
	private static final String REMQUERYDEACTIVATE = "!REMQUERY^DEACT|F295\r";
	private static final String REMOTEDISABLE = "!REMOTE_CTRL^DISABLED|EF95\r";
	
	private static final String INFRATESET = "!INF_RATE^3.50^ml/h|3885\r";
	private static final String INFRATESET_BASE = "!INF_RATE^0.10^ml/h|D407\r";
	
	private static final String INFSTART = "!INF_START|38F3\r";
	private static final String INFSTOP = "!INF_STOP|CD57\r";
	
	private static final int MAX_RESPONSE_LEN = 15000;
	
	public Asena_backup(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,1);
	}
	
	public Asena_backup(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		deviceIdentity.manufacturer = "Alaris";
		deviceIdentity.model = "Asena";
		deviceIdentity.operating_system="OS";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
		flowRateHolderHead = createNumericInstance("MDC_FLOW_FLUID_PUMP", "DEV_STATUS_INFRATE_ACTUAL");
		wallClock = new DeviceClock.WallClock();
		// TODO Auto-generated constructor stub 
	}
	
	
	@Override
	protected long getMaximumQuietTime(int idx) {
		// TODO Auto-generated method stub
		return 10000L;
	}
	
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider provider = super.getSerialProvider(idx);
		provider.setDefaultSerialSettings(38400, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
		return provider;
	}

	@Override
	protected void doInitCommands(int idx) throws IOException{
		
		initDone = false;
		// write first command INSTSERIAL
		byte[] initBytes = INSTSERIAL.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("INSTSERIAL command written in doInitCommands");
		byte[] responseBytes = new byte[64];
		System.err.println("Reading for response");
		int bytesRead = 0;
		
		while(bytesRead <31 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
			
		}
		System.err.println(ArrayUtils.toString(responseBytes));
//		
		System.err.println("bytesRead in response is "+ bytesRead);
		String response = new String(responseBytes,0,bytesRead);
		System.err.println("bytesRead 0 is " + responseBytes[0]);
		System.err.println("doInitCommans response is " + response);
		
		// write second command COMMS PROTOCOL	
		initBytes = COMMSPROTOCOL.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("COMMSPROTOCOL command written in doInitCommands");
		responseBytes = new byte[64];
		System.err.println("Reading for response");
		bytesRead = 0;
		
		while(bytesRead <37 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
			
		}
		System.err.println(ArrayUtils.toString(responseBytes));
//		
		System.err.println("bytesRead in response is "+ bytesRead);
		response = new String(responseBytes,0,bytesRead);
		System.err.println("bytesRead 0 is " + responseBytes[0]);
		System.err.println("doInitCommans response is " + response); 
		
		// write third command REMOTE CONTROL ENABLE
		initBytes = REMOTEENABLE.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("REMOTEENABLE command written in doInitCommands");
		responseBytes = new byte[64];
		System.err.println("Reading for response");
		bytesRead = 0;
		
		while(bytesRead <43 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
			
		}
		System.err.println(ArrayUtils.toString(responseBytes));
		
		System.err.println("bytesRead in response is "+ bytesRead);
		response = new String(responseBytes,0,bytesRead);
		System.err.println("bytesRead 0 is " + responseBytes[0]);
		System.err.println("doInitCommans response is " + response);
		
		
		
		String parts[] = response.split("\\^");
		if(!parts[0].equals("!REMOTE_CTRL")) {
			System.err.println("Bad response to REMOTE ENABLE command - parts[0] is "+ parts[0]);
			return;
		}
		
		if(!parts[1].equals("ENABLED")) {
			System.err.println("System did not enable Remote Control");
			return;
		}
				
		reportConnected("Connection Protocol complete and Remote Control is Enabled");
		initDone = true;
		
		
	}
	
	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException{
		this.fromDevice = new BufferedInputStream(inputStream);
		this.toDevice = new BufferedOutputStream(outputStream);
		try {
			while( !initDone) {
				Thread.sleep(1000L);
			}
			while (true) {
				doStatReq();
				Thread.sleep(2500);
			}
		}catch (InterruptedException e) {
			e.printStackTrace();
			}catch (Exception e2) {
				e2.printStackTrace();
			}
		}
		
	private synchronized void doStatReq() throws IOException{
//		
//		ENABLE REMOTE CONRTOL
		byte[] initBytes = REMOTEENABLE.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("REMOTE ENABLE command written in doInitCommands");
		byte[] responseBytes = new byte[64];
		System.err.println("Reading for response - REMOTE ENABLE");
		int bytesRead = 0;
		
		while(bytesRead <43 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
			
		}

//
//		FIRST INF COMMAND TO READ INFUSION RATE ON PUMP
		initBytes = INFSTATUS.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("INF command written in doInitCommands");
		responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - INF");
		bytesRead = 0;
		
		while(bytesRead <75 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
			
		}
//		System.err.println(ArrayUtils.toString(responseBytes));
		
		String response = new String (responseBytes,0,bytesRead);
		System.err.println("INF command response is " + response);
		String parts[] = response.split("\\^");
		
		InstSerial = parts [1];
		AlarmNotification = parts [2];
		InfMode = parts [3];
		InfRate = parts [4];
		InfRateUnit = parts [5];
		VolumeInf = parts [7];
		InfTimeRemaining = parts[11];
		
		System.err.println("Flow Rate is "+ InfRate);
		
		numericSample(flowRateHolderHead, Float.parseFloat(InfRate), wallClock.instant());
		
		
//		initBytes = INFSTOP.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("INF STOP command written in doStatReq");
//		responseBytes = new byte[MAX_RESPONSE_LEN];
//		System.err.println("Reading for response - INF STOP");
//		bytesRead = 0;
//		
//		while(bytesRead <14 || responseBytes[bytesRead-6] !='|') {
//			System.err.println("Read with bytesRead " + bytesRead);
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
//			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//		}
		
//		
//		SET VALUE ON PUMP TO BASE VALUE		
		initBytes = INFRATESET_BASE.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("INF RATE SET BASE command written in doStatReq");
		responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - BASE RATE");
		bytesRead = 0;
		
		while(bytesRead <24 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
		}
		
//		
//		READING VALUE FROM PUMP AFTER 1ST WRITE
		initBytes = INFSTATUS.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("INFSTATUS command written in doStatReq");
		responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - INF");
		bytesRead = 0;
		
		while(bytesRead <75 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
			
		}
		
		response = new String (responseBytes,0,bytesRead);
		System.err.println("INF command response is " + response);
		parts = response.split("\\^");
		
		InstSerial = parts [1];
		AlarmNotification = parts [2];
		InfMode = parts [3];
		InfRate = parts [4];
		InfRateUnit = parts [5];
		VolumeInf = parts [7];
		InfTimeRemaining = parts[11];
		
		System.err.println("Flow Rate is "+ InfRate);
		numericSample(flowRateHolderHead, Float.parseFloat(InfRate), wallClock.instant());
		
		
//		
//		ADD PAUSE AFTER FIRST WRITE COMMAND
		try {
			Thread.sleep(2000L);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
//		
//		SET VALUE ON PUMP TO SAMPLE VALUE		
		initBytes = INFRATESET.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("INF RATE SET command written in doStatReq");
		responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - SAMPLE RATE");
		bytesRead = 0;
		
		while(bytesRead <24 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
		}
		
//		READING VALUE FROM PUMP AFTER 2ST WRITE
		initBytes = INFSTATUS.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("INFSTATUS command written in doStatReq");
		responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - INF");
		bytesRead = 0;
		
		while(bytesRead <75 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
			
		}
		
		response = new String (responseBytes,0,bytesRead);
		System.err.println("INF command response is " + response);
		parts = response.split("\\^");
		
		InstSerial = parts [1];
		AlarmNotification = parts [2];
		InfMode = parts [3];
		InfRate = parts [4];
		InfRateUnit = parts [5];
		VolumeInf = parts [7];
		InfTimeRemaining = parts[11];
		
		System.err.println("Flow Rate is "+ InfRate);
		numericSample(flowRateHolderHead, Float.parseFloat(InfRate), wallClock.instant());
		
//		
//		ADD PAUSE AFTER SECOND WRITE COMMAND
		try {
			Thread.sleep(2000L);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
//		initBytes = INFSTART.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("INF START command written in doStatReq");
//		responseBytes = new byte[MAX_RESPONSE_LEN];
//		System.err.println("Reading for response - INF START");
//		bytesRead = 0;
//		
//		while(bytesRead <15 || responseBytes[bytesRead-6] !='|') {
//			System.err.println("Read with bytesRead " + bytesRead);
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
//			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//		}
	
		
//		
//		DISABLE REMOTE CONTROL TO PUMP
//		TO AVOID COMMS TIMEOUT ERROR
		initBytes = REMOTEDISABLE.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("REMOTE DISABLE command written in doStatReq");
		responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - REMOTE DISABLE");
		bytesRead = 0;
		
		while(bytesRead <44 || responseBytes[bytesRead-6] !='|') {
			System.err.println("Read with bytesRead " + bytesRead);
			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
		}
		
		
	}
		
		
	
	@Override
	protected String iconResourceName() {
		return "pump.png";
	}
	

}
