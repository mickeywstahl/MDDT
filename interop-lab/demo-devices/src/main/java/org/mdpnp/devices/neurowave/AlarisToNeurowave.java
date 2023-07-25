package org.mdpnp.devices.neurowave;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.concurrent.Executors;

import org.mdpnp.devices.alaris.Asena;
import org.mdpnp.devices.neurowave.AP4000.AP4000Alarm;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket.*;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

import ice.ConnectionState;
import ice.InfusionProgram;


/**
 * This class will be receiving commands from an EasyTiva device, and the EasyTiva
 * will be sending commands that it expects to be sent to an Alaris GH pump.  We
 * will receive those commands, and translate them to the equivalent Neurowave
 * commands.  We also need to translate the responses of the Neurowave to responses
 * that the EasyTiva will understand - i.e. the response that the Alaris would have
 * sent.  When creating one of these devices, the first port (index 0) should be the
 * EasyTiva port for Propofol, and the second port (index 1) should be the EasyTiva
 * port for Remifentanil.  The third (index 2) should be the Neurowave port.
 * 
 * @author simon
 *
 */
public class AlarisToNeurowave extends AbstractSerialDevice {
	
	private static final Logger log = LoggerFactory.getLogger(AlarisToNeurowave.class);
	
	private static final Logger alarisNeurowaveLog = LoggerFactory.getLogger("neurowave.alaris");
	
	private static final Hashtable<String,String> AP4000AlarmMap=new Hashtable<>();

	/**
	 * An instance of the AP4000 class.  Using this and creating it from inside
	 * this class, using the known serial port values etc., means that we can
	 * avoid duplicating all the code from inside that class into this one.
	 */
	private AP4000 neurowaveDevice;
	
	class NeurowaveThread extends Thread {
		
		@Override
		public void run() {
			System.err.println("creating neurowaveDevice in thread");
			neurowaveDevice=new AP4000(subscriber, publisher, eventLoop, 1);
			neurowaveDevice.setExternalLog(alarisNeurowaveLog);
			neurowaveDevice.init();
		}
	}

	public AlarisToNeurowave(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, 2);
		super.debug=true;
		populateAlarmMap();
		NeurowaveThread nt=new NeurowaveThread();
		nt.setDaemon(true);
		nt.start();
		System.err.println("Made the neurowaveDevice");
		deviceIdentity.manufacturer="Neurowave";
		deviceIdentity.model="AP4000<->EasyTIVA";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
	}

	public AlarisToNeurowave(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,2);
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
		//TODO: Put some meaningful criteria here to decide that we actually are connected.
		System.err.println("Currently in doInit for idx - " + idx);
		
		if (idx == 0) {
			reportConnected("Assume connected for idx - "+ idx);
		}
		if (idx == 1) {
			reportConnected("Assume connected for idx - "+ idx);
		}
		
	}

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		System.err.println("for idx="+idx+" state is "+stateMachine.getState());
		//If we get here, we are "connected".
		alarisNeurowaveLog.trace("Started new session |");
		if(idx<2) {
			//0 and 1 are the two EasyTiva channels to what they think is an Alaris.
			BufferedReader fromEasyTiva=new BufferedReader(new InputStreamReader(inputStream));
			BufferedWriter toEasyTiva=new BufferedWriter(new OutputStreamWriter(outputStream));
			System.err.println("Created fromEasyTIVA and toEasyTIVA on port "+getPortIdentifier(idx)+" for idx "+idx);
			System.err.println(getPortIdentifier(idx));
			String alarisCommand=null;
			while( (alarisCommand=fromEasyTiva.readLine()) != null ) {
				System.err.println(getPortIdentifier(idx) + ": Printing EasyTIVA Command for " + alarisCommand);

				String alarisResponse=executeNeurowaveCommand(alarisCommand,idx);
				System.err.println(getPortIdentifier(idx)+ ": Response from translator app is " +alarisResponse);
				
				toEasyTiva.write(alarisResponse);
				toEasyTiva.flush();
				System.err.println(getPortIdentifier(idx) + ": Written to EasyTIVA");
				
			}
			System.err.println("Null data from EasyTIVA for idx - "+ idx);
		}
	}
	
	/**
	 * This method tells the Neurowave instance to run one of its commands, if there is a
	 * relevant equivalent command to send to the Neurowave.  Not all Alaris commands have
	 * a one to one mapping to the Neurowave - BUT, we need to send a response to the EasyTiva
	 * that it thinks came from an Alaris pump.  So sometimes we will need to fake that response.
	 * 
	 * @param alarisCommand The command from the EasyTiva
	 * @param idx Which EasyTiva channel we are receiving the command from
	 * @return a String representing the response to send to the EasyTiva
	 * @throws IOException if the Neurowave throws the same
	 */
	private String executeNeurowaveCommand(String alarisCommand, int idx) throws IOException {
		alarisNeurowaveLog.trace("From EasyTiva: "+(idx+1) + " "+alarisCommand);
		if(alarisCommand.startsWith("!INST_SERIALNO|")) {
			if(idx==0) {
				String finalResponse="!INST_SERIALNO^8002-51733|ACFF\r";
				alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
				return finalResponse;
			}
			if(idx==1) {
				String finalResponse="!INST_SERIALNO^8002-51740|050B\r";
				alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
				return finalResponse;
			}
		}
		if(alarisCommand.startsWith("!COMMS_PROTOCOL|")) {
			//No condition on idx here - we assume both pumps have the same comms version
			String finalResponse="!COMMS_PROTOCOL^Asena Rev 2.1.5|661A\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!REMOTE_CTRL^ENABLED^")) {
			/*
			 * The AP4000 class is coded so that it will do a control request if the
			 * authorization key is null.  So for now we also don't need to do anything here
			 * as it will be done in response to programming the pump, pause/resume etc. 
			 */
			String finalResponse="!REMOTE_CTRL^ENABLED^****^PERMIT^0^ms|7BB0\r"; //pumps just send asterisks, not hidden values
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!COMMS_RESPONSE_MAX|7E29")) {
			//We will just return a fixed response for this for now.
			String finalResponse="!COMMS_RESPONSE_MAX^4000^ms|DFDF\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!SYRINGE_STATUS|4102")) {
			/*
			 * Closest info from the Neurowave is the DEV_SET_BAG_SIZE[12] from the output of
			 * STATREQ.  However, as we can't know that there's any sensible value mapping here
			 * as a bag could be much larger than a syringe.  So for now, this is also a fixed
			 * response.  If we need to do a mapping, the AP4000 class needs to expose
			 * DEV_SET_BAG_SIZE[12].  As there doesn't seem to be a need for that for any of the
			 * existing use cases, it would probably make sense to just expose that via a method,
			 * rather than bothering to publish it in DDS.
			 */
			String finalResponse="!SYRINGE_STATUS^BD PLASTIPAK^ 50^CONF|1CF8\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!INF_RATE_MAX_SYRINGE|8F30")) {
			//TODO: See if there is a mapping here or not.
			/*
			 * Alaris seems to have some contraints on flow rate vs VTBI
			 * i.e. whether a certain flow rate is permitted depends on 
			 * what the VTBI is.
			 */
			String finalResponse="!INF_RATE_MAX_SYRINGE^1200.00^ml/h|8575\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!INF|4961")) {
			/*
			 * This is the first real case where we can do a mapping to actual values from
			 * the AP4000 class (Neurowave device).
			 */
			StringBuilder sb=new StringBuilder("INF^");
			if(idx==0) {
				sb.append("8002-51733"); //TODO: copy serial numbers from 90.15
			} else {
				sb.append("8002-51740");
			}
			
			
			sb.append("^-^SET^");	//This is the infusion mode
			//Now, we want the actual infusion rate value.
			if(idx==0) {
				sb.append(neurowaveDevice.getFlowRate(1));
			}
			if(idx==1) {
				sb.append(neurowaveDevice.getFlowRate(2));
			}
			//Don't think units are available via the Neurowave
			sb.append("^ml/h");
			sb.append("^^");	//Drug name seems to be empty for now
			//Now, get the volume infused
			if(idx==0) {
				sb.append(neurowaveDevice.getVolumeInfused(1));
			}
			if(idx==1) {
				sb.append(neurowaveDevice.getVolumeInfused(2));
			}
			//Ditto for units of volume infused - not available from Neurowave
			sb.append("^ml");
			//We don't appear to have any kind of "pressure" equivalent in the Neurowave
			sb.append("^-50.00^mmHg^");
			//Unclear if this newly implemented time getter from the AP4000 is what we want here.
			if(idx==0) {
				sb.append(neurowaveDevice.getTimeRemaining(1)); //time is in HH:MM:SS until 24hours, then it is represented by "24+"
			}
			if(idx==1) {
				sb.append(neurowaveDevice.getTimeRemaining(2));
			}
			//Rest of the line is <LogType> and <LatestLogEntryID>
			sb.append("^EVENT^185995"); //log type and number not used
			
			String finalResponse=Asena.crc(sb.toString());
			//finalResponse should now have the initial ! and the | and checksum at the end.
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
			
		}
		if(alarisCommand.startsWith("!INF_VI_CLEAR|")) {
			//Is it possible to reset the volume infused indicator on the Neurowave?  I don't think so.
			//TODO: Make code in the Neurowave that deducts the current volume infused from future readings.
			//TODO: Also need to reflect the corrected amount in the INF parameters
			String finalResponse="!INF_VI_CLEAR|A706\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!INF_STOP|")) {
			neurowaveDevice.pauseInfusion(idx+1);
			String finalResponse="!INF_STOP|CD57\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!INF_RATE^")) {
			System.err.println(alarisCommand);
			String[] parts=alarisCommand.split("\\^");
//			System.err.println("!!!!!! Actual Rate to be set is.... "  +parts[1]);
			int newRate=Math.round(Float.parseFloat(parts[1])) +1;
			//We can just pass an InfusionProgram to the Neurowave
			InfusionProgram program=new InfusionProgram();
			program.head=idx+1;
			program.bolusRate=-1;
			program.bolusVolume=-1;
			program.infusionRate=newRate;
			program.requestor=this.getClass().getName();
			program.seconds=-1;
			program.unique_device_identifier="";	//This isn't used in this direct call - only by the listener
			//AP4000 class updated to return the Neurowave response, that we were previously capturing and not doing anything with.
			String neurowaveResponse=neurowaveDevice.programPump(program);
			String[] responseParts=neurowaveResponse.split("\\^");
			System.err.println(neurowaveResponse);
			//responseParts[4] = String.format("%.2f",responseParts[4]);
//			String response="INF_RATE^" + (idx==0 ? Float.parseFloat(responseParts[4]) : Float.parseFloat(responseParts[9])) + "^ml/h";
			DecimalFormat df = new DecimalFormat("0.00");
			String returnRate = df.format(Float.parseFloat(parts[1]));
			
			String response="INF_RATE^" + returnRate + "^ml/h";
			String finalResponse=Asena.crc(response);
			System.err.println(finalResponse);
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!INF_START|")) {
			neurowaveDevice.resumeInfusion(idx+1);
			String finalResponse="!INF_START|38F3\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!ALARM|")) {
			String finalResponse="";
			String noAlarmsActive="ALARM^AL_NOALM^ ^";
			ArrayList<AP4000Alarm> alarms=neurowaveDevice.alarmList;
			if(alarms.size()==0) {
				finalResponse=Asena.crc(noAlarmsActive);
			} else {
				//We can't handle more than one alarm, so we really need to sort the highest priority ones anyway...
				AP4000Alarm ap4000Alarm=alarms.get(0);
				String alarisAlarm=AP4000AlarmMap.get(ap4000Alarm.code);
				if(alarisAlarm==null) {
					log.warn("Unmapped AP4000 alarm "+ap4000Alarm.code);
					alarisNeurowaveLog.trace("Unmapped AP4000 alarm "+ap4000Alarm.code);
					//TODO: Return AL_NOALM or AL_OCCLU?
					finalResponse=Asena.crc(noAlarmsActive);
				} else {
					finalResponse=Asena.crc("ALARM^"+alarisAlarm+"^ ^");
				}
			}
			System.err.println("returning "+finalResponse+" for !ALARM query");
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!REMOTE_CTRL^DISABLED")) {
			String finalResponse="!REMOTE_CTRL^DISABLED^****^PERMIT^0^ms|3C5D\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!REMQUERY^DEACT")) {
			String finalResponse="!REMQUERY^DEACT^^^^|E5C9\r";
			alarisNeurowaveLog.trace("To EasyTiva: "+(idx+1) + " "+finalResponse);
			return finalResponse;
		}
		
		System.err.println("!+!+!+! - Unhandled request from EasyTiva - "+alarisCommand);
		alarisNeurowaveLog.trace("!+!+!+! - Unhandled request from EasyTiva - "+alarisCommand);
		return null;
	}
	
	@Override
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider provider = super.getSerialProvider(idx);
		if(idx<2) {
			//EasyTiva thinks it's talking to Alaris, so Alaris settings.  Index 0 and 1
			provider.setDefaultSerialSettings(38400, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
		} else {
			//This port is talking to the Neurowave. Index 2
			//provider.setDefaultSerialSettings(115200, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
			//Does this work, or will it get confused?  Think it might be OK.
			if(neurowaveDevice==null) {
				System.err.println("neurowaveDevice is null in getSerialProvider...");
			}
			provider=neurowaveDevice.getSerialProvider(0);
		}
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

	/**
	 * We override this method to do some odd things.  We will get three ports here,
	 * two for the EasyTiva<->Alaris channels, and one for the Neurowave. We need to
	 * intercept this method to we can trim off the final address for the list that
	 * relates to the Neurowave.  Then we call connect with that address on the
	 * Neurowave instance.  After trimming it down to two ports, we'll call the super
	 * method with those two addresses, which will trigger starting a thread for each
	 * port and everything it does.
	 * 
	 * Then we'll call connect on the AP4000 class instance with the one address.
	 * 
	 * We need to do all this because this is the point at which we get the actual port
	 * names, such as COM3 or ttyS2, and those are required to make the devices do anything
	 * useful.
	 */
	@Override
	public boolean connect(String address) {
		String[] allPorts=address.split(",");
		//Now make a two port array with two EasyTiva ports.
		String justEasyTivaPorts=allPorts[0]+","+allPorts[1];
		boolean connectThis=super.connect(justEasyTivaPorts);
		
		//TODO: How do we handle the result of the neurowave.  && it together with the super?
		neurowaveDevice.setExecutor(Executors.newSingleThreadScheduledExecutor());
		new Thread() {
			public void run() {
				try {
					sleep(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				neurowaveDevice.connect("COM10");
			}
		}.start();
		
		return connectThis;
	}

	@Override
	protected String iconResourceName() {
		return "alaris-neurowave.png";
	}

	@Override
	public void disconnect() {
		new Thread() {
			public void run() {
				neurowaveDevice.disconnect();
			}
		}.run();
		super.disconnect();
	}

	@Override
	public void shutdown() {
		neurowaveDevice.keepGoing=false;
		new Thread() {
			public void run() {
				neurowaveDevice.shutdown();
			}
		}.run();
		super.shutdown();
	}
	
	//private static final Hashtable<String,String> AP4000AlarmMap=new Hashtable<>();
	private void populateAlarmMap() {
		InputStream is=getClass().getResourceAsStream("neurowave-alaris-alarm-map2.txt");
		if(is==null) {
			log.warn("No neurowave-alaris-alarm-map.txt");
			return;
		}
		BufferedReader br=new BufferedReader(new InputStreamReader(is));
		String line;
		try {
			while( (line=br.readLine())!=null) {
				if(line.startsWith("#") || line.length()==0 || line.indexOf('\t')==-1) {
					continue;	//Not an interesting line
				}
				String[] fields=line.split("\t");
				if(fields.length!=2) {
					//Weird line
					continue;
				}
				AP4000AlarmMap.put(fields[0], fields[1]);
			}
		} catch (IOException e) {
			log.error("Failed to read neurowave-alaris-alarm-map.txt" , e);
		}
	}
	
}
