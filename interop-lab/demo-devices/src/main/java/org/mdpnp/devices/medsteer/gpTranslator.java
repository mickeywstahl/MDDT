package org.mdpnp.devices.medsteer;

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
 * @author braga
 *
 */
public class gpTranslator extends AbstractSerialDevice {
	
	private static final Logger log = LoggerFactory.getLogger(gpTranslator.class);
	
	private static final Logger alarisNeurowaveLog = LoggerFactory.getLogger("easy.tiva");
	
	private static final Hashtable<String,String> AP4000AlarmMap=new Hashtable<>();

	public gpTranslator(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		deviceIdentity.manufacturer="Medsteer";
		deviceIdentity.model="GPC Translator";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
	}

	public gpTranslator(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,1);
	}
	
	private BufferedReader fromRequestor, fromDevice;
	private BufferedWriter toRequestor, toDevice;
	private boolean connected;
	
	private float infRatePROP, infRateREMI;

	@Override
	protected void doInitCommands(int idx) throws IOException {
		
		infRatePROP = (float) 0.0;
		infRateREMI = (float) 0.0;
		
		reportConnected("Assume connected for idx - "+ idx);
		connected =true;
	}

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		while(!connected) {
			System.err.println("Waiting to connect");
			try {
				Thread.sleep(1000L);
			}catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
		fromRequestor = new BufferedReader(new InputStreamReader (inputStream));
		toRequestor = new BufferedWriter(new OutputStreamWriter (outputStream));
		System.err.println("Created fromRequestor and toRequestor on port " +getPortIdentifier()+ " for idx " + idx);
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
		/*
		if(idx ==0) {
			fromRequestor = new BufferedReader(new InputStreamReader (inputStream));
			toRequestor = new BufferedWriter(new OutputStreamWriter (outputStream));
			System.err.println("Created fromRequestor and toRequestor on port " +getPortIdentifier()+ " for idx " + idx);
		} else {
			fromDevice = new BufferedReader(new InputStreamReader (inputStream));
			toDevice = new BufferedWriter(new OutputStreamWriter (outputStream));
			System.err.println("Created fromDevice and toDevice on port " +getPortIdentifier()+ " for idx " + idx);
		}
		if (idx == 0) {
			String inputCommand=null;
			System.err.println("Reading from TIVA algorithm -----");
			while(true) {
				if ( (inputCommand = fromRequestor.readLine() )!=null) {
					System.err.println(getPortIdentifier(idx) + ": Printing EasyTIVA Command for " + inputCommand);
				} else {
					System.err.println("Did not read lines from TIVA");
				}
			}
		} else if(idx ==1) {
			while(true) {}
		} */
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
		
		if(inputCommand.startsWith("!INFO_GET_DEVICE_NB")) {
			response = "!INFO_GET_DEVICE_NB^2" +defaultCRC;
			return response;
		}
		
		if(inputCommand.startsWith("!INFO_GET_DEVICE_INFO")) {
			response = "!INFO_GET_DEVICE_INFO^0^" + infRatePROP + "^ml/h^0.00000^ml^0^0^1^" + infRateREMI + "^ml/h^0.00000^ml^0^0" + defaultCRC;
			return response;
		}
		
		if(inputCommand.startsWith("!INF_SET_RATE")) {
			
			String fields[] = inputCommand.split("\\^");
			String moduleNumber = fields[1];
			String pendingInfRate = fields[2];
			
			if(moduleNumber.equals("0")) {
				infRatePROP = Float.parseFloat(fields[2]);
			}else if(moduleNumber.equals("1")) {
				infRateREMI = Float.parseFloat(fields[2]);
			}else {
				System.err.println("Invalid pump or pump module selected");
			}
			
			response = "!INF_SET_RATE^" + moduleNumber + "^" + pendingInfRate + "^ml/h^0" +defaultCRC;
			return response;
		}
		
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

	
	@Override
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider provider = super.getSerialProvider(idx);
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
	
}
