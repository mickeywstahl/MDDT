package org.mdpnp.devices.neurowave;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import org.mdpnp.devices.alaris.Asena;
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

	/**
	 * An instance of the AP4000 class.  Using this and creating it from inside
	 * this class, using the known serial port values etc., means that we can
	 * avoid duplicating all the code from inside that class into this one.
	 */
	private AP4000 neurowaveDevice;

	public AlarisToNeurowave(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, 2);
		neurowaveDevice=new AP4000(subscriber, publisher, eventLoop, 1);
		System.err.println("Made the neurowaveDevice");
		deviceIdentity.manufacturer="Neurowave";
		deviceIdentity.model="ET-4000";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
	}

	public AlarisToNeurowave(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,3);
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
		//TODO: Put some meaningful criteria here to decide that we actually are connected.
		if(idx<2) {
			reportConnected("Connect properly later...");
		}
		if(idx==2) {
			neurowaveDevice.doInitCommands(0);
		}
	}

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		while(stateMachine.getState()!=ConnectionState.Connected) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		//If we get here, we are "connected".
		if(idx<2) {
			//0 and 1 are the two EasyTiva channels to what they think is an Alaris.
			BufferedReader fromEasyTiva=new BufferedReader(new InputStreamReader(inputStream));
			BufferedWriter toEasyTiva=new BufferedWriter(new OutputStreamWriter(outputStream));
			String alarisCommand=null;
			while( (alarisCommand=fromEasyTiva.readLine()) != null ) {
				/*
				String translated=translateCommand(alarisCommand);
				if(translated==null) {
					String fakeResponse=fakeResponse(alarisCommand);
				}
				*/
				String alarisResponse=executeNeurowaveCommand(alarisCommand,idx);
			}
		}
		if(idx==2) {
			//idx for the neurowave is 0, as it only has one port.
			neurowaveDevice.process(0, inputStream, outputStream);
		}
		
		
	}
	
	private boolean neurowaveInitDone=false;
	
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
		if(alarisCommand.startsWith("!INST_SERIALNO|")) {
			//TODO: Get the two different serial numbers!
			if(idx==0) {
				return "!INST_SERIALNO^8002-51733|ACFF";
			}
			if(idx==1) {
				return "!INST_SERIALNO^8002-51733|ACFF"; //TODO: copy serial numbers from 90.15
			}
		}
		if(alarisCommand.startsWith("!COMMS_PROTOCOL|")) {
			//No condition on idx here - we assume both pumps have the same comms version
			return "!COMMS_PROTOCOL^Asena Rev 2.1.5|661A";
		}
		if(alarisCommand.startsWith("!REMOTE_CTRL^ENABLED^")) {
			/*
			 * The AP4000 class is coded so that it will do a control request if the
			 * authorization key is null.  So for now we also don't need to do anything here
			 * as it will be done in response to programming the pump, pause/resume etc. 
			 */
			return "!REMOTE_CTRL^ENABLED^****^PERMIT^0^ms|7BB0"; //TODO: check if crc code changes for different pumps
		}
		if(alarisCommand.startsWith("!COMMS_RESPONSE_MAX|7E29")) {
			//We will just return a fixed response for this for now.
			return "!COMMS_RESPONSE_MAX^4000^ms|DFDF";
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
			return "!SYRINGE_STATUS^BD PLASTIPAK^ 50^CONF|1CF8";
		}
		if(alarisCommand.startsWith("!INF_RATE_MAX_SYRINGE|8F30")) {
			//TODO: See if there is a mapping here or not.
			/*
			 * Alaris seems to have some contraints on flow rate vs VTBI
			 * i.e. whether a certain flow rate is permitted depends on 
			 * what the VTBI is.
			 */
			return "!INF_RATE_MAX_SYRINGE^1200.00^ml/h|8575";
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
				sb.append("8002-51733");
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
			sb.append("^ml/h");
			//We don't appear to have any kind of "pressure" equivalent in the Neurowave
			sb.append("^-50.00^mmHg^");
			//Unclear if this newly implemented time getter from the AP4000 is what we want here.
			if(idx==0) {
				sb.append(neurowaveDevice.getTimeRemaining(1)); //TODO: check time formatting + variations
			}
			if(idx==1) {
				sb.append(neurowaveDevice.getTimeRemaining(2));
			}
			//Rest of the line is <LogType> and <LatestLogEntryID>
			sb.append("^EVENT^185995"); //TODO: check log number increments
			
			String finalResponse=Asena.crc(sb.toString());
			//finalResponse should now have the initial ! and the | and checksum at the end.
			return finalResponse;
			
		}
		if(alarisCommand.startsWith("!INF_VI_CLEAR|")) {
			//Is it possible to reset the volume infused indicator on the Neurowave?  I don't think so.
			//TODO: Make code in the Neurowave that deducts the current volume infused from future readings.
			return "!INF_VI_CLEAR|A706";
		}
		if(alarisCommand.startsWith("!INF_STOP|")) {
			neurowaveDevice.pauseInfusion(idx+1);
			return "!INF_STOP|CD57";
		}
		if(alarisCommand.startsWith("!INF_RATE^")) {
			String[] parts=alarisCommand.split("^");
			float newRate=Float.parseFloat(parts[1]);
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
			String[] responseParts=neurowaveResponse.split("^");
			String response="INF_RATE^" + (idx==0 ? responseParts[4] : responseParts[9]) + "^ml/h";
			String finalResponse=Asena.crc(response);
			return finalResponse;
		}
		if(alarisCommand.startsWith("!INF_START|")) {
			neurowaveDevice.resumeInfusion(idx+1);
			return "!INF_START|38F3";
		}
		
		
		
		return null;
	}
	
	/**
	 * Translate the requested Alaris command to the closest equivalent EasyTiva command.
	 * It might be that not everything will get a translation, because there are not one
	 * to one equivalents in all cases.  For instance, both the !INST_SERIALNO command
	 * and the !COMMS_PROTOCOL| command map most closely to the !CONNREQ command for the
	 * Alaris.  So we might translate both of these, or we might just ignore one of them
	 * and use a default response for it, to save sending additional traffic to the Neurowave
	 * 
	 * Some of these translations will be more detailed than others, because we need to map
	 * two different pumps to the two heads on the one Neurowave.  So anything asking for
	 * infusion rates, or commands to change those rates, will need to be mapped to a particular
	 * head.
	 * 
	 * Because the AP4000 class already has the functionality to create commands with checksums,
	 * we've changed that class so that that method is static and we can just invoke it from
	 * here.  Although for its purposes, that one returns a byte array, which we promptly
	 * turn back into a String.  But never mind...
	 *  
	 * @param alarisCommand
	 * @return the equivalent Neurowave command.
	 */
	private String translateCommand(String alarisCommand) {
		if(alarisCommand.startsWith("!INST_SERIALNO|") ||
		   alarisCommand.startsWith("!COMMS_PROTOCOL|")
		) {
			//We just get this directly from the original AP4000 class.
			return AP4000.CONNREQ;
		}
		if(alarisCommand.startsWith("!REMOTE_CTRL^ENABLED")) {
			
		}
		
		
		return null;
	}
	
	private String fakeResponse(String alarisCommand) {
		
		
		
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
		return 30_000L;
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
		if(allPorts.length!=3) {
			throw new IllegalArgumentException("Wrong number of ports ("+allPorts.length+") called for connect");
		}
		//Now make a two port array with two EasyTiva ports.
		String justEasyTivaPorts=allPorts[0]+","+allPorts[1];
		//TODO: How do we handle the result of the neurowave.  && it together with the super?
		boolean neurowaveConnect=neurowaveDevice.connect(allPorts[2]);
		return super.connect(justEasyTivaPorts);
	}

	@Override
	protected String iconResourceName() {
		return "alaris-neurowave.png";
	}
	
}
