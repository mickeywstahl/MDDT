package org.mdpnp.devices.alaris;

import java.io.BufferedInputStream;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.DecimalFormat;

import org.apache.commons.lang3.ArrayUtils;
import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.medsteer.bis.BisMonitor;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.devices.simulation.pump.SimControllablePump;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.mdpnp.rtiapi.data.EventLoop.ConditionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.infrastructure.Condition;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.StringSeq;
import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.QueryCondition;
import com.rti.dds.subscription.ReadCondition;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.Topic;

import ice.FlowRateObjectiveDataReader;
import ice.InfusionObjectiveDataReader;
import ice.InfusionProgram;
import ice.InfusionProgramDataReader;
import ice.Numeric;

public class BragaAlarisMITM extends AbstractSerialDevice {
	
	private boolean initDone;

	private static final Logger Log = LoggerFactory.getLogger(BisMonitor.class);
	private static final Logger easyTivaLog = LoggerFactory.getLogger("easy.tiva");

//	private BufferedReader fromDevice;
//	private InputStream fromRequestor;
//	private BufferedOutputStream toRequestor, toDevice, toLog;
	
//	private BufferedInputStream fromDevice;
//	private BufferedOutputStream toDevice;
	
	private DeviceClock.WallClock wallClock;
	
	private String InstSerial;
	private String ConnectionStatus;
	private String AlarmNotification;
	private String InfMode;
	private String InfRate;
	private String InfRateUnit;
	private String VolumeInf;
	private String VolumeInfUnit;
	private String InfTimeRemaining;
	private String Pressure; 
	private String PressureUnit;
	private String LogType;
	private String LogEntryID;
	
	private float currentVTBI;
	
	private boolean pendingWriteSpeed = false;
	private boolean pendingWriteBolus = false;
	float setPumpSpeed;
	float setVTBIvol;
	float setBolusSpeed;
	float setBolusVol;
	int masterValue = (int)1;
	
	private final InstanceHolder<Numeric> flowRateHolderHead;
	
	private FlowRateObjectiveDataReader flowRateReader;
	private InfusionObjectiveDataReader pauseResumeReader;
	private InfusionProgramDataReader programReader;
	private Topic flowRateTopic,pauseResumeTopic, programTopic;
	private QueryCondition flowRateQueryCondition, pauseResumeQueryCondition, programQueryCondition;

	
	private static final Logger log = LoggerFactory.getLogger(BragaAlarisMITM.class);
	
	private String drugName;
	
	private static final String INFSTATUS = "!INF|4961\r";
	private static final String COMMSPROTOCOL = "!COMMS_PROTOCOL|E8DA\r";
	private static final String INSTSERIAL = "!INST_SERIALNO|457D\r";
	private static final String REMOTEENABLE = "!REMOTE_CTRL^ENABLED^3CC1|B1F1\r";

	private static final String BOLUSENABLE = "!INF_BOLUS^ENABLED|E288\r";
	private static final String VTBI = "!INF_VTBI|8456\r";
	private static final String VTBISET = "!INF_VTBI^ACTIV^11.000^ml^STOP|4433\r";
	private static final String VTBIDEACTIVATE = "!INF_VTBI^DEACT|D8C5\r";
	
	private static final String REMQUERYDEACTIVATE = "!REMQUERY^DEACT|F295\r";
	private static final String REMOTEDISABLE = "!REMOTE_CTRL^DISABLED|EF95\r";
	
	private static final String INFSTART = "!INF_START|38F3\r";
	private static final String INFSTOP = "!INF_STOP|CD57\r";
	
	private static final int MAX_RESPONSE_LEN = 15000;
	
	public BragaAlarisMITM(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,2);
	}
	
	public BragaAlarisMITM(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		deviceIdentity.manufacturer = "Alaris";
		deviceIdentity.model = "Asena";
		deviceIdentity.operating_system="OS";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
		flowRateHolderHead = createNumericInstance("MDC_FLOW_FLUID_PUMP", "DEV_STATUS_INFRATE_ACTUAL");
		wallClock = new DeviceClock.WallClock();
//		addListener();
		addObjectiveListeners();
	}
	
	
	@Override
	protected long getMaximumQuietTime(int idx) {
		// TODO Auto-generated method stub
		return 10_000L;
	}
	
//	private BufferedInputStream fromDevice; //change to BufferedReader
	private BufferedReader fromDevice, fromRequestor;
//	private InputStream fromRequestor;
	private BufferedOutputStream toRequestor, toDevice, toLog;
	
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider provider = super.getSerialProvider(idx);
		provider.setDefaultSerialSettings(38400, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
		return provider;
	}

	
	@Override
	protected void doInitCommands(int idx) throws IOException{
//		connected=true;
		int i = 0;
		drugName = "(Generic Drug at " + getPortIdentifier(idx) + ")";
		if (i ==0) {
			if (getPortIdentifier(idx).equals("COM3") || getPortIdentifier(idx).equals("COM6") ){
				drugName = "(Propofol)";
				System.err.println(getPortIdentifier(idx) + drugName);
				i++;
			}
			if (getPortIdentifier(idx).equals("COM11") || getPortIdentifier(idx).equals("COM7") ){
				drugName = "(Remifentanil)";
				System.err.println(getPortIdentifier(idx) + drugName);
				i++;
			}
		}
		reportConnected(drugName + " Pump is now connected.");
	}
	
	
	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		String responseTIVA = null, responsePump =null;
		System.err.println("process starts for idx "+idx);
		
		if(idx==0) {
			fromRequestor= new BufferedReader(new InputStreamReader(inputStream)); //inputStream;
			toRequestor=new BufferedOutputStream(outputStream);
//			toLog=new BufferedOutputStream(new FileOutputStream("C:/Users/MDPNP/bis_"+System.currentTimeMillis()+".log"));
			System.err.println("Created fromRequestor and toRequestor on port "+getPortIdentifier(idx)+" for idx "+idx);
			
		} else {
			fromDevice=new BufferedReader(new InputStreamReader(inputStream));
			toDevice=new BufferedOutputStream(outputStream);
			System.err.println("Created fromDevice and toDevice on port "+getPortIdentifier(idx)+" for idx "+idx);
		}
		if(idx ==0) {
			System.err.println("Receiving from requestor idx "+idx);
			responseTIVA = fromRequestor.readLine();
			System.err.println(drugName + ": First response from EasyTiva is  " + responseTIVA);

//			responsePump = writetoDevice(responseTIVA); // INSTSERIAL to pump
//			responseTIVA = writetoRequestor(responsePump); // INSTSERIAL response to EasyTIVA
//			
//			System.err.println(drugName + ": Second response from EasyTIVA is " + responseTIVA);
//			
//			responsePump = writetoDevice(responseTIVA); //COMMSPROTOCOL to pump
//			responseTIVA = writetoRequestor(responsePump); // COMMSPROTOCOL response to EasyTIVA
//			
//			System.err.println(drugName + ": Third response  from EasyTIVA is " + responseTIVA);
//			
//			responsePump = writetoDevice(responseTIVA); //REMOTE ENABLE to pump
//			responseTIVA = writetoRequestor(responsePump); // REMOTE ENABLE response to EasyTIVA
//			
//			System.err.println(drugName + ": Fourth response  from EasyTIVA is " + responseTIVA);
		}
		while(true) {
			if(idx==0) {
				long t1 = System.currentTimeMillis();
				responsePump = writetoDevice(responseTIVA); 
				responseTIVA = writetoRequestor(responsePump);
				String parts[] = responsePump.split("\\^");
				if(parts[0] == "INF") {
					InstSerial = parts [1];
					AlarmNotification = parts [2];
					InfMode = parts [3];
					InfRate = parts [4];
					InfRateUnit = parts [5];
					VolumeInf = parts [7];
					VolumeInfUnit = parts[8];
					Pressure = parts[9];
					PressureUnit = parts[10];
					InfTimeRemaining = parts[11];
					LogType = parts[12];
					LogEntryID = parts[13];
					
					String lastvals [] = LogEntryID.split("|");
					LogEntryID = lastvals[0];
//					numericSample(flowRateHolderHead, Float.parseFloat(InfRate), wallClock.instant());
				}
				
				long t2 = System.currentTimeMillis();
				System.err.println(drugName+" Next response  from EasyTIVA is " + responseTIVA+" at "+t2);
				if (t2 - t1 > 6000) {
					System.err.println("Breaking out from while(true) for idx==0");
					break;
				}
			}
			if (idx==1) {
				
			}
		}
		}
		
	private synchronized String writetoDevice(String command) throws IOException {
		
		command+="\r";
		System.err.println(drugName + ": Receiving Command from EasyTIVA - " + command);
		byte[] initBytes = command.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println(drugName + ": Command written to AlarisGH - " + command);
		easyTivaLog.trace("<<< " + drugName + " "+ command);
	
		IOException[] ioeDuringRead=new IOException[1];
		String[] response = new String [1];
		Thread t = new Thread() {

			@Override
			public void run() {
				// TODO Auto-generated method stub
				long l1=System.currentTimeMillis();
				try {
					
					Thread.sleep(5000L);
					if (response[0]==null || response[0].length() == 0){
						System.err.println("!!!!! " + drugName + " Pump did not send response to EasyTIVA within 5 seconds");
						easyTivaLog.trace("!!!!! "+ drugName + " Pump did not send response to EasyTIVA within 5 seconds");
						if(ioeDuringRead!=null) {
							easyTivaLog.trace("exception during read", ioeDuringRead[0]);
						}
						
					}
				} catch (InterruptedException e) {
					System.err.println(drugName+" Interrupted after "+(System.currentTimeMillis()-l1));
				}
			}
			
		};
		t.setName(drugName + " Pump Timeout Thread");
		t.start();
		try {
			response[0] = fromDevice.readLine();
		} catch (IOException ioe) {
			ioe.printStackTrace();
			ioeDuringRead[0]=ioe;
		}
		t.interrupt();
		System.err.println(drugName + ": Response from Alaris is " + response[0]);
		
		return response[0];
	}
	
	/*
	 * I don't think this cycle of events make sense.  Reading from the requestor here makes it seem
	 * as if the tiva responds to the response from the pump.
	 */
	private synchronized String writetoRequestor(String command) throws IOException {
		
		command+="\r";
		System.err.println(drugName + ": Receiving response from AlarisGH - "+ command);
		byte[] initBytes = command.getBytes();
		toRequestor.write(initBytes);
		toRequestor.flush();
		System.err.println(drugName + ": Command written to EasyTIVA - " + command);
		easyTivaLog.trace(">>> " + drugName + " "+ command);
		String response = fromRequestor.readLine();
		System.err.println(drugName + ": Response from EasyTIVA is " + response);
		
		return response;
	}
		
	private synchronized void doStatReq() throws IOException{
		
//		remoteEnable();
//		
//		pumpStatus();
		
		System.err.println("Flow Rate is "+ InfRate);
		numericSample(flowRateHolderHead, Float.parseFloat(InfRate), wallClock.instant());
		
		try {
			Thread.sleep(2000L);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
	}
		
	
	private void setSpeed(float newFlowRate) throws IOException {
		
		setPumpSpeed = newFlowRate;
		pendingWriteSpeed = true;
	}
	
//	private void actualSetSpeed(float newFlowRate) throws IOException {
//		
//		remoteEnable();
//		
////		check if value is within limits
//		
//		if (newFlowRate > 1200 || newFlowRate < 0.1 ) {
//			System.err.println("Value of pump flow rate is out of limits. Rate must be 0.1 - 1200 ml/h \n");
//			return;
//		}
//		
////		format value to be set
//		DecimalFormat df = new DecimalFormat("0.00");
//		System.err.println("Asena setSpeed to " + df.format(newFlowRate));
//		
//		String command = "INF_RATE^" + df.format(newFlowRate) + "^ml/h";
//		System.err.println(command);
//		String sendCommand = crc(command);
//		System.err.println(sendCommand);
//		
//		byte[] initBytes = sendCommand.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("INF RATE SET command written in setSpeed");
//		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
//		System.err.println("Reading for response - SAMPLE RATE");
//		int bytesRead = 0;
//		
//		while(bytesRead <24 || responseBytes[bytesRead-6] !='|') {
////			System.err.println("Read with bytesRead " + bytesRead);
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
////			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//		}
//		String response = new String (responseBytes,0,bytesRead);
//		System.err.println("INF SET command response is " + response);
//
//	}
//
//	private void setBolus(float newFlowRate) throws IOException {
//		
//		remoteEnable();
//		
////		check if value is within limits
//		
//		if (newFlowRate > 600 || newFlowRate < 1200 ) {
//			
////			format value to be set
//			DecimalFormat df = new DecimalFormat("0.00");
////			System.err.println("Asena setSpeed to " + df.format(newFlowRate));
//			
//			String command = "INF_RATE^" + df.format(newFlowRate) + "^ml/h";
////			System.err.println(command);
//			String sendCommand = crc(command);
//			System.err.println(sendCommand);
//			
//			byte[] initBytes = sendCommand.getBytes();
//			toDevice.write(initBytes);
//			toDevice.flush();
//			System.err.println("SET BOLUS command written");
//			byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
//			System.err.println("Reading for response - SAMPLE RATE");
//			int bytesRead = 0;
//			
//			while(bytesRead <24 || responseBytes[bytesRead-6] !='|') {
////				System.err.println("Read with bytesRead " + bytesRead);
//				bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
////				System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//			}
//			String response = new String (responseBytes,0,bytesRead);
//			System.err.println("SET BOLUS command response is " + response);
//			
//		}
//		
//		else {
//			System.err.println("Value of Bolus is out of limits.");
//			return;
//		}
//		
//
//
//	}
	
	private synchronized String pauseInfusion() throws IOException {
		
		byte[] initBytes = INFSTOP.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("Attempting to pause infusion");
		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - INF STOP");
		int bytesRead = 0;
		
		String nextLine=fromDevice.readLine();		
//		String response = new String (responseBytes,0,bytesRead);
		System.err.println("INF STOP command response is " + nextLine);
		return nextLine;
	}
	
	private synchronized void startInfusion() throws IOException {
		
		byte[] initBytes = INFSTART.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("Attempting to start infusion");
		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response - INF START");
		int bytesRead = 0;
		
		while(bytesRead <15 || responseBytes[bytesRead-6] !='|') {
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);			
		}		
		String response = new String (responseBytes,0,bytesRead);
		System.err.println("INF START command response is " + response);
	}
	
//	private synchronized void instituteSerialNumber() throws IOException {
//		
//		byte[] initBytes = INSTSERIAL.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("INSTSERIAL command written");
//		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
//		System.err.println("Reading for response");
//		int bytesRead = 0;
//		
//		while(bytesRead <31 || responseBytes[bytesRead-6] !='|') {
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
//		}
//				
//		String response = new String(responseBytes,0,bytesRead);
//		System.err.println("INST SERIAL response is " + response);
//	}
//	
	private synchronized String commsProtocol() throws IOException {
		
		byte[] initBytes = COMMSPROTOCOL.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("Comms Protocol command written");
		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
		System.err.println("Reading for response");
		int bytesRead = 0;
		
		String nextLine=fromDevice.readLine();
				
//		String response = new String(responseBytes,0,bytesRead);
		System.err.println("COMMS PROTOCOL response is " + nextLine);
		return nextLine;
	}

	
	private synchronized String remoteEnable() throws IOException {
		
		byte[] initBytes = REMOTEENABLE.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("Attempting to write REMOTE ENABLE command");
		byte[] responseBytes = new byte[64];
		System.err.println("Reading for response - REMOTE ENABLE");
		int bytesRead = 0;
		
		String nextLine=fromDevice.readLine();
		String response = new String (responseBytes,0,bytesRead);
		System.err.println("REMOTE ENABLE command response is " + nextLine);
		return nextLine;
	}
	
	private synchronized String remoteDisable() throws IOException {
		
		byte[] initBytes = REMOTEDISABLE.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("Attempting to write REMOTE DISABLE command");
		byte[] responseBytes = new byte[64];
		System.err.println("Reading for response - REMOTE DISBALE");
		int bytesRead = 0;
		
		String nextLine=fromDevice.readLine();
		String response = new String (responseBytes,0,bytesRead);
		System.err.println("REMOTE DISABLE command response is " + nextLine);
		return nextLine;
	}
	
	private synchronized void pumpStatus() throws IOException {
		
		
	}
//	
//	private synchronized void pumpStatus() throws IOException {
//		
//		byte[] initBytes = INFSTATUS.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("Attempting to write INF command");
//		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
//		System.err.println("Reading for response - INF");
//		int bytesRead = 0;
//		
//		while(bytesRead <75 || responseBytes[bytesRead-6] !='|') {
//			System.err.println("Read with bytesRead " + bytesRead);
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
//			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//			
//		}
//		
//		String response = new String (responseBytes,0,bytesRead);
//		System.err.println("INF command response is " + response);
//		String parts[] = response.split("\\^");
//		
//		InstSerial = parts [1];
//		AlarmNotification = parts [2];
//		InfMode = parts [3];
//		InfRate = parts [4];
//		InfRateUnit = parts [5];
//		VolumeInf = parts [7];
//		VolumeInfUnit = parts[8];
//		Pressure = parts[9];
//		PressureUnit = parts[10];
//		InfTimeRemaining = parts[11];
//		LogType = parts[12];
//		LogEntryID = parts[13];
//		
//		String lastvals [] = LogEntryID.split("|");
//		LogEntryID = lastvals[0];
//		
//	}
//	
//	private void queryVTBI() throws IOException{
//		byte[] initBytes = VTBI.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("Attempting to query VTBI status");
//		byte[] responseBytes = new byte[64];
//		System.err.println("Reading for response");
//		int bytesRead = 0;
//
//		while(bytesRead <24 || responseBytes[bytesRead-6] !='|') {
////		while(bytesRead <35 || responseBytes[bytesRead-6] !='|') {
////			System.err.println("Read with bytesRead " + bytesRead);
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
////			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//		}
//	
//		String response = new String (responseBytes,0,bytesRead);
//		System.err.println("VTBI command response is " + response);
//		String parts[] = response.split("\\^");
//		
//		float vtbi;
//				
//		if(parts[2].isEmpty()) {
//			vtbi = 0;
//		}
//		else {
//			vtbi =  Float.parseFloat(parts[2]);
//		}
//		
//		currentVTBI = vtbi;
//	}
//	
//	private void setVTBI(float VTBIvol) throws IOException {
//		
//		remoteEnable();
//		
////		check if value is within limits
//		
//		if (VTBIvol > 50 || VTBIvol < 0 ) {
//			System.err.println("Value of VTBI is out of limits. Rate must be 0 - 50 ml \n");
//			return;
//		}
//		
////		format value to be set
//		DecimalFormat df = new DecimalFormat("0.000");
//		System.err.println("Asena setVTBI to " + df.format(VTBIvol));
//		
//		String command = "INF_VTBI^ACTIV^" + df.format(VTBIvol) + "^ml^STOP";
//		System.err.println(command);
//		String sendCommand = crc(command);
//		System.err.println(sendCommand);
//		
//		byte[] initBytes = sendCommand.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("VTBI SET command written in setVTBI");
//		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
//		System.err.println("Reading for response - ");
//		int bytesRead = 0;
//		
//		while(bytesRead <35 || responseBytes[bytesRead-6] !='|') {
////			System.err.println("Read with bytesRead " + bytesRead);
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
////			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//		}
//		String response = new String (responseBytes,0,bytesRead);
//		System.err.println("VTBI SET command response is " + response);
//
//	}
//	
//	private void deactivateVTBI() throws IOException {
//		
//		remoteEnable();
//		
//		byte[] initBytes = VTBIDEACTIVATE.getBytes();
//		toDevice.write(initBytes);
//		toDevice.flush();
//		System.err.println("VTBI DEACTIVATE command written in deactivateVTBI");
//		byte[] responseBytes = new byte[MAX_RESPONSE_LEN];
//		System.err.println("Reading for response - ");
//		int bytesRead = 0;
//		
//		while(bytesRead <24 || responseBytes[bytesRead-6] !='|') {
//			System.err.println("Read with bytesRead " + bytesRead);
//			bytesRead += fromDevice.read(responseBytes, bytesRead, responseBytes.length - bytesRead);
//			System.err.println("Response is now " + new String(responseBytes,0,bytesRead));
//		}
//		String response = new String (responseBytes,0,bytesRead);
//		System.err.println("VTBI DEACTIVATE command response is " + response);
//
//	}
	
	public static String crc(String input) {
		final short initRegister = (short)0xffff;
		String message = input;
        byte[] messageBytes = message.getBytes();

        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(messageBytes);
        short bitMask = (short)(1 << 15);

        // Process each message byte.
        int value = stream.read();
        short register = initRegister;
        while (value != -1) {
            byte element = (byte)value;

            register ^= ((short)element << 8);
            for (int i = 0; i < 8; i++) {
                if ((register & bitMask) != 0) {
                    register = (short)((register << 1) ^ 0x1021);
                }
                else {
                register <<= 1;
                }
            }
            value = stream.read();
        }

        // XOR the final register value.
        register ^= 0x0000;
        String hexValue = valueOf(register);
        hexValue = hexValue.toUpperCase();
        while(hexValue.length()!=4){
            hexValue = "0"+hexValue;
        }
        String fullCommand = "!" + message +"|" + hexValue + "\r";
        
        return fullCommand;
		
	}
	
	public static String valueOf(short number) {
        // Create a mask to isolate only the correct width of bits.
        long fullMask = (((1L << 15) - 1L) << 1) | 1L;
        return Long.toHexString(number & fullMask);
      }
	
	public void addListener() {
		
		/**
		 * Following block of code is for receiving objectives for the flow rate
		 */
		ice.FlowRateObjectiveTypeSupport.register_type(getParticipant(), ice.FlowRateObjectiveTypeSupport.get_type_name());
		flowRateTopic = TopicUtil.findOrCreateTopic(getParticipant(), ice.FlowRateObjectiveTopic.VALUE, ice.FlowRateObjectiveTypeSupport.class);
		flowRateReader = (ice.FlowRateObjectiveDataReader) subscriber.create_datareader_with_profile(flowRateTopic,
        		QosProfiles.ice_library, QosProfiles.state,  null, StatusKind.STATUS_MASK_NONE);
		StringSeq params = new StringSeq();
        params.add("'" + deviceIdentity.unique_device_identifier + "'");
        flowRateQueryCondition = flowRateReader.create_querycondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
        		ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);
        eventLoop.addHandler(flowRateQueryCondition, new ConditionHandler() {
            private ice.FlowRateObjectiveSeq data_seq = new ice.FlowRateObjectiveSeq();
            private SampleInfoSeq info_seq = new SampleInfoSeq();

            @Override
            public void conditionChanged(Condition condition) {

                for (;;) {
                    try {
                        flowRateReader.read_w_condition(data_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                                (ReadCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo si = (SampleInfo) info_seq.get(i);
                            ice.FlowRateObjective data = (ice.FlowRateObjective) data_seq.get(i);
                            if (si.valid_data) {
                            	try { 
                            		setSpeed(data.newFlowRate);
                            	} catch (IOException ioe) {
                            		Log.error("Failed to set pump speed", ioe);
                            		ioe.printStackTrace();
                            	}
                            }
                        }
                    } catch (RETCODE_NO_DATA noData) {
                        break;
                    } finally {
                        flowRateReader.return_loan(data_seq, info_seq);
                    }
                }
            }

        });
		
	}
	
	
	
	@Override
	public void disconnect() {
		if(toDevice == null) {
			super.disconnect();
			return;
		}
		byte[] initBytes = REMOTEDISABLE.getBytes();
		try {
			toDevice.write(initBytes);
			toDevice.flush();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.err.println("REMOTE DISABLE command written in disconnect");
		
		
		super.disconnect();
	}

	@Override
	public void shutdown() {
		// TODO Auto-generated method stub
		super.shutdown();
	}

	
	private final void addObjectiveListeners() {
//		addFlowRateListener();
		addPauseResumeListener();
		addProgramListener();
	}
	
	@SuppressWarnings("unused")
	private final void addFlowRateListener() {
		/**
		 * Following block of code is for receiving objectives for the flow rate
		 */
		ice.FlowRateObjectiveTypeSupport.register_type(getParticipant(), ice.FlowRateObjectiveTypeSupport.get_type_name());
		flowRateTopic = TopicUtil.findOrCreateTopic(getParticipant(), ice.FlowRateObjectiveTopic.VALUE, ice.FlowRateObjectiveTypeSupport.class);
		flowRateReader = (ice.FlowRateObjectiveDataReader) subscriber.create_datareader_with_profile(flowRateTopic,
        		QosProfiles.ice_library, QosProfiles.state,  null, StatusKind.STATUS_MASK_NONE);
		StringSeq params = new StringSeq();
        params.add("'" + deviceIdentity.unique_device_identifier + "'");
        flowRateQueryCondition = flowRateReader.create_querycondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
        		ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);
        eventLoop.addHandler(flowRateQueryCondition, new ConditionHandler() {
            private ice.FlowRateObjectiveSeq data_seq = new ice.FlowRateObjectiveSeq();
            private SampleInfoSeq info_seq = new SampleInfoSeq();

            @Override
            public void conditionChanged(Condition condition) {

                for (;;) {
                    try {
                        flowRateReader.read_w_condition(data_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                                (ReadCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo si = (SampleInfo) info_seq.get(i);
                            ice.FlowRateObjective data = (ice.FlowRateObjective) data_seq.get(i);
                            if (si.valid_data) {
                            	try { 
                            		setSpeed(data.newFlowRate);
                            	} catch (IOException ioe) {
                            		Log.error("Failed to set pump speed", ioe);
                            		ioe.printStackTrace();
                            	}
                            }
                        }
                    } catch (RETCODE_NO_DATA noData) {
                        break;
                    } finally {
                        flowRateReader.return_loan(data_seq, info_seq);
                    }
                }
            }
        });
	}
	
	private final void addPauseResumeListener() {
		/**
		 * Following block of code is for receiving objectives to pause resume.
		 */
		ice.InfusionObjectiveTypeSupport.register_type(getParticipant(), ice.InfusionObjectiveTypeSupport.get_type_name());
		pauseResumeTopic = TopicUtil.findOrCreateTopic(getParticipant(), ice.InfusionObjectiveTopic.VALUE, ice.InfusionObjectiveTypeSupport.class);
		pauseResumeReader = (ice.InfusionObjectiveDataReader) subscriber.create_datareader_with_profile(pauseResumeTopic,
        		QosProfiles.ice_library, QosProfiles.state,  null, StatusKind.STATUS_MASK_NONE);
		StringSeq params = new StringSeq();
        params.add("'" + deviceIdentity.unique_device_identifier + "'");
        pauseResumeQueryCondition = pauseResumeReader.create_querycondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
        		ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);
        eventLoop.addHandler(pauseResumeQueryCondition, new ConditionHandler() {
            private ice.InfusionObjectiveSeq data_seq = new ice.InfusionObjectiveSeq();
            private SampleInfoSeq info_seq = new SampleInfoSeq();

            @Override
            public void conditionChanged(Condition condition) {

                for (;;) {
                    try {
                    	pauseResumeReader.read_w_condition(data_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                                (ReadCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo si = (SampleInfo) info_seq.get(i);
                            ice.InfusionObjective data = (ice.InfusionObjective) data_seq.get(i);
                            if (si.valid_data) {
                            	try { 
                            		if(data.stopInfusion) {
                            			pauseInfusion(); // removed argument

                            		} else {
                            			startInfusion(); // removed argument, changed to "startInfusion" to reflect Asena method
                            		}
                            	} catch (IOException ioe) {
                            		log.error("Failed to pause/resume pump", ioe);
                            		ioe.printStackTrace();
                            	}
                            }
                        }
                    } catch (RETCODE_NO_DATA noData) {
                        break;
                    } finally {
                        pauseResumeReader.return_loan(data_seq, info_seq);
                    }
                }
            }
        });
	}
	
	private final void addProgramListener() {
		/**
		 * Following block of code is for receiving objectives to pause resume.
		 */
		ice.InfusionProgramTypeSupport.register_type(getParticipant(), ice.InfusionProgramTypeSupport.get_type_name());
		programTopic = TopicUtil.findOrCreateTopic(getParticipant(), ice.InfusionProgramTopic.VALUE, ice.InfusionProgramTypeSupport.class);
		programReader = (ice.InfusionProgramDataReader) subscriber.create_datareader_with_profile(programTopic,
        		QosProfiles.ice_library, QosProfiles.state,  null, StatusKind.STATUS_MASK_NONE);
		StringSeq params = new StringSeq();
        params.add("'" + deviceIdentity.unique_device_identifier + "'");
        programQueryCondition = programReader.create_querycondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
        		ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);
        eventLoop.addHandler(programQueryCondition, new ConditionHandler() {
            private ice.InfusionProgramSeq data_seq = new ice.InfusionProgramSeq();
            private SampleInfoSeq info_seq = new SampleInfoSeq();

            @Override
            public void conditionChanged(Condition condition) {

                for (;;) {
                    try {
                    	programReader.read_w_condition(data_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                                (ReadCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo si = (SampleInfo) info_seq.get(i);
                            ice.InfusionProgram data = (ice.InfusionProgram) data_seq.get(i);
                            if (si.valid_data) {
                            	try { 
                            		programPump(data);
                            	} catch (IOException ioe) {
                            		Log.error("Failed to program pump", ioe);
                            		ioe.printStackTrace();
                            	}
                            }
                        }
                    } catch (RETCODE_NO_DATA noData) {
                        break;
                    } finally {
                        programReader.return_loan(data_seq, info_seq);
                    }
                }
            }
        });
	}
	
	
	private synchronized void programPump(InfusionProgram program) throws IOException {
		
		if(program.infusionRate > 0) {
			setPumpSpeed = program.infusionRate;
			setVTBIvol = program.VTBI;
			pendingWriteSpeed = true;
		}
		
		if( program.bolusRate > 0 ) {
			setBolusSpeed = program.bolusRate;
			setBolusVol = program.bolusVolume;
			pendingWriteBolus = true;
		}
		
	}
	
	@Override
    protected String iconResourceName() {
        return "alaris_asena_pump.png";
    }
	
}

        

