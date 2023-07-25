package org.mdpnp.devices.alaris;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Queue;

import org.mdpnp.devices.medsteer.bis.BisMonitor;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.EventLoop.ConditionHandler;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;
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

import ice.InfusionObjectiveDataReader;
import ice.InfusionProgram;
import ice.InfusionProgramDataReader;

public class AlarisMITM extends AbstractSerialDevice {
	
	class TimeAndCommand {
		long elapsed;
		String command;
		public TimeAndCommand(long elapsed, String command) {
			super();
			this.elapsed = elapsed;
			this.command = command;
		}
		@Override
		public String toString() {
			return elapsed + "\t" + command;
		}
		
	}
	
	/**
	 * Flag to indicate whether to return alarms at pre-defined intervals.
	 */
	private boolean timedAlarms;

	/**
	 * The time that execution starts, used to know if we should be returning alarms
	 * if timedAlarms is true.
	 */
	private long startTime;

	/**
	 * Start of first time period to return alarms (five minutes)
	 */
	private static final long START_ALARM_RANGE_ONE=60*1000*1;
	/**
	 * End of first time period (thirty seconds after start of first period)
	 */
	private static final long END_ALARM_RANGE_ONE=START_ALARM_RANGE_ONE+30*1000;

	/**
	 * Start of second time period (two minutes after end of first period)
	 */
	private static final long START_ALARM_RANGE_TWO=END_ALARM_RANGE_ONE+60*1000*1;
	/**
	 * End of second time period (one minute after start of second period)
	 */
	private static final long END_ALARM_RANGE_TWO=START_ALARM_RANGE_TWO+60*1000;

	/**
	 * Start of third time period (two minutes after end of second period)
	 */
	private static final long START_ALARM_RANGE_THREE=END_ALARM_RANGE_TWO+60*1000*1;
	/**
	 * End of third time period (two minutes after start of third period)
	 */
	private static final long END_ALARM_RANGE_THREE=START_ALARM_RANGE_THREE+60*1000*1;
	
	private String[] alarmCodes = { "AL_ACDIS", "AL_CALLB", "AL_COMTO", "AL_LDSCN", "AL DRDIS", "AL_EMBAT", "AL_EOIKV", "AL_EOIST", "AL_FAULT", 
			"AL_LBTAC", "AL_LWBAT", "AL_NOALM", "AL_OCCLU", "AL_NEOIN", "AL_PRDSC", "AL_RHIGH", "AL_SYRCO", "AL_TCITG", "AL_TITCA", "AL_TITNC", "AL_VTBIC", "AL_VTBIK", "AL_VTBIS"};

	private String alarmFields[];
	
	private static final long MAX_WAIT=500L;
	
	private static final int MAX_RETRIES=5;
	
	private static final Logger Log = LoggerFactory.getLogger(BisMonitor.class);
	private static final Logger easyTivaLog = LoggerFactory.getLogger("easy.tiva");

	private static final Logger mitmTimeLog = LoggerFactory.getLogger("mitm.timetrace");

	private boolean pendingWriteBolus = false;
	float setPumpSpeed;
	float setVTBIvol;
	float setBolusSpeed;
	float setBolusVol;
	int masterValue = (int)1;
	
	Queue<String> TIVAq = new LinkedList<>();
	Queue<String> PUMPq = new LinkedList<>();
	
	private static final Logger log = LoggerFactory.getLogger(AlarisMITM.class);
	
	private String drugName;
	private String saveCommand;
	
	private boolean remoteDisableFlag = false;
	
	private static final String REMOTEDISABLE = "!REMOTE_CTRL^DISABLED|EF95\r";
	
	/**
	 * From the EasyTIVA to the pump
	 */
	private ArrayList<TimeAndCommand> sentCommands;
	
	/**
	 * From the pump to the EasyTIVA
	 */
	private ArrayList<TimeAndCommand> sentResponses;
	
	private final long ourStartMillis=System.currentTimeMillis();
	
	TIVAreader t1;
	PUMPwriter p1;
	private boolean keepGoing=true;
	
	
	public AlarisMITM(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,2);
	}
	
	public AlarisMITM(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		deviceIdentity.manufacturer = "Alaris";
		deviceIdentity.model = "Asena MITM";
		deviceIdentity.operating_system="OS";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
		createNumericInstance("MDC_FLOW_FLUID_PUMP", "DEV_STATUS_INFRATE_ACTUAL");
		//new DeviceClock.WallClock();
//		addListener();
		//addObjectiveListeners();
		sentCommands=new ArrayList<>();
		sentResponses=new ArrayList<>();
//		System.err.println("^^^^^^^^^^^^^^^^ In device creation ^^^^^^^^^^^^^^^^^^^^^^^");
//		System.err.println("^^^^^^^^^^^^^^^^ " + additionalParams);
//		if(additionalParams!=null && additionalParams.equals("alarms")) {
//			timedAlarms=true;
//			System.err.println("^^^^^^^^^^^^^^^^ In alarm condition in constructor, set to true ^^^^^^^^^^^^^^^^^^^^^^^");
//			easyTivaLog.trace("timedAlarms flag has been activated");
//			startTime=System.currentTimeMillis();
//		} else {
//			System.err.println("NO ADDITIONAL PARAMS IN CONSTRUCTOR");
//		}
	}
	
	
	@Override
	protected long getMaximumQuietTime(int idx) {
		// TODO Auto-generated method stub
		//return 10_000L;
		return Long.MAX_VALUE;		//OK, this is stupidly long, but we don't want to interfere. 
	}
	
	private BufferedReader fromDevice, fromRequestor;
	private BufferedWriter toRequestor, toDevice, toLog;

	private boolean connected;
	
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider provider = super.getSerialProvider(idx);
		provider.setDefaultSerialSettings(38400, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
		return provider;
	}

	
	@Override
	protected void doInitCommands(int idx) throws IOException{
//		alarmFields =  additionalParams.split("\\.");
//		if(additionalParams!=null && alarmFields[0].equals("alarms")) {
		if(additionalParams!=null && additionalParams.equals("alarms")) {
			timedAlarms=true;
			System.err.println("^^^^^^^^^^^^^^^^ In alarm condition in doInitCommands, set to true ^^^^^^^^^^^^^^^^^^^^^^^");
			easyTivaLog.trace("timedAlarms flag has been activated");
			startTime=System.currentTimeMillis();
		} 
//		else if(additionalParams!=null && alarmFields[0].equals("alarm")) {
//			timedAlarms=true;
//			System.err.println("^^^^^^^^^^^^^^^^ In alarm condition in doInitCommands, set to true ^^^^^^^^^^^^^^^^^^^^^^^");
//			easyTivaLog.trace("timedAlarms flag has been activated");
//			startTime=System.currentTimeMillis();
//		}
		else {
			System.err.println("NO ADDITIONAL PARAMS IN CONSTRUCTOR");
		}
//		connected=true;
		int i = 0;
		drugName = "(Generic Drug at " + getPortIdentifier(idx) + ")";
		if (i ==0) {
			if (getPortIdentifier(idx).equals("COM3") || getPortIdentifier(idx).equals("COM6") ){
				drugName = "(Propofol)";
				System.err.println(getPortIdentifier(idx) + drugName);
				i++;
			}
			if (getPortIdentifier(idx).equals("COM13") || getPortIdentifier(idx).equals("COM7") ){
				drugName = "(Remifentanil)";
				System.err.println(getPortIdentifier(idx) + drugName);
				i++;
			}
		}
		reportConnected(drugName + " Pump is now connected.");
		easyTivaLog.trace(drugName + " Created new MITM device.");
		mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| Created new MITM device |");
		connected=true;
	}
	
	
	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		while(!connected) {
			System.err.println("!!! Waiting for connect");
			try {
				Thread.sleep(1000L);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		System.err.println("process starts for idx "+idx);
		
		if(idx==0) {
			fromRequestor= new BufferedReader(new InputStreamReader(inputStream)); //inputStream;
			toRequestor=new BufferedWriter(new OutputStreamWriter(outputStream));
			System.err.println("Created fromRequestor and toRequestor on port "+getPortIdentifier(idx)+" for idx "+idx);
			
		} else {
			fromDevice=new BufferedReader(new InputStreamReader(inputStream));
			toDevice=new BufferedWriter(new OutputStreamWriter(outputStream));
			System.err.println("Created fromDevice and toDevice on port "+getPortIdentifier(idx)+" for idx "+idx);
		}
		
		if (idx ==0) {
			
			t1 = new TIVAreader();
			p1  = new PUMPwriter();
			p1.start();
			t1.start();
			try {
				p1.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		if(idx==1) {
			while(true) {
				if(remoteDisableFlag) {
					System.err.println("!! Current status of remote disable flag for "+ drugName +" is " + remoteDisableFlag + " !!");
					remoteDisableFlag = false;
					break;
				}
			}
		}
		
	}

	private class TIVAreader extends Thread {

		@Override
		public void run() {
			while(keepGoing) {
				System.err.println(drugName + " - TIVAreader blocks sync");
				synchronized(TIVAq) {
					String TIVArequest = null;
					try {
						TIVArequest=fromRequestor.readLine();
						mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| From EasyTIVA |" +TIVArequest );
						/*
						 * If doing timed alarms, check if we are in an alarm window...
						 */
//						if(timedAlarms && TIVArequest.equals("!ALARM|50B2")) {
//							long currentInterval=System.currentTimeMillis() - startTime;
//							if(
//								(currentInterval>START_ALARM_RANGE_ONE && currentInterval<END_ALARM_RANGE_ONE) ||
//								(currentInterval>START_ALARM_RANGE_TWO && currentInterval<END_ALARM_RANGE_TWO) ||
//								(currentInterval>START_ALARM_RANGE_THREE && currentInterval<END_ALARM_RANGE_THREE)
//							) {
//								//TODO: Check if this is an accurate occlusion alarm
//								String finalFakeResponse=crc("ALARM^AL_OCCL^OCCLUSION^");
//								easyTivaLog.trace("Writing 'finalFakeResponse' to Requestor");
//								writetoRequestor(finalFakeResponse);
//								
//							} else {
//								TIVAq.add(TIVArequest);
//								long t1 = System.currentTimeMillis();
//								System.err.println(drugName+" Next request from EasyTIVA is " + TIVArequest+" at "+t1);
//							}
//						} else {
//							//Either not doing timed alarms, or it's not in a time window.  Send the command to the pump
//							TIVAq.add(TIVArequest);
//							long t1 = System.currentTimeMillis();
//							System.err.println(drugName+" Next request from EasyTIVA is " + TIVArequest+" at "+t1);
//						}
						TIVAq.add(TIVArequest);
						long t1 = System.currentTimeMillis();
						System.err.println(drugName+" Next request from EasyTIVA is " + TIVArequest+" at "+t1);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				System.err.println(drugName + " - TIVAreader releases sync");
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	private class PUMPwriter extends Thread {
		
		public void run() {
			while (keepGoing) {
				String command = null;
				synchronized(TIVAq) {
					try {
						System.err.println(drugName + " - PUMPWriter is allowed to try remove");
						command = TIVAq.remove();
					} catch (NoSuchElementException nsee) {
						System.err.println(drugName + " - PUMPWriter got no elements");
						continue;
					}
				}
				command+="\r";
				saveCommand = command;
				System.err.println(drugName + ": Receiving Command from EasyTIVA - " + command);
				try {
					toDevice.write(command);
					toDevice.flush();
					mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| To Pump |" +command);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if (command == REMOTEDISABLE) {
					remoteDisableFlag = true;
				}
				System.err.println(drugName + ": Command written to AlarisGH - " + command);
				sentCommands.add(new TimeAndCommand(System.currentTimeMillis()-ourStartMillis, command));
				easyTivaLog.trace("<<< " + drugName + " "+ command);
				//We are not timed out yet.
				boolean[] timedOut=new boolean[] {false};
				
				try {
					Thread.sleep(2);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				final StringBuilder sb=new StringBuilder();
				String PUMPresponse = null;
				
				// Timeout tracking Thread
				IOException[] ioeDuringRead=new IOException[1];
				Thread t = new Thread() {

					@Override
					public void run() {
						long l1=System.currentTimeMillis();
						try {
							
							sleep(MAX_WAIT);
							if (sb.length() == 0){
								System.err.println("!!!!! " + drugName + " Pump did not send response to EasyTIVA within "+MAX_WAIT);
								easyTivaLog.trace("!!!!! "+ drugName + " Pump did not send response to EasyTIVA within "+MAX_WAIT);
								System.err.println("String read when looking for line is "+sb.toString());
								retrySendCommand();
								if(ioeDuringRead!=null) {
									easyTivaLog.trace("exception during read", ioeDuringRead[0]);
								}
								timedOut[0]=true;
								String filename="commands-"+getPortIdentifier()+".log";
								PrintStream commands=new PrintStream(new File(filename));
								for(int i=0;i<sentCommands.size();i++) {
									commands.println(sentCommands.get(i).toString());
								}
								commands.close();
								
								filename="responses-"+getPortIdentifier()+".log";
								PrintStream responses=new PrintStream(new File(filename));
								for(int i=0;i<sentResponses.size();i++) {
									responses.println(sentResponses.get(i).toString());
								}
								responses.close();
							}
							else {
								System.err.println(drugName + " : Partial read from pump is : "+ sb.toString());
							}
						} catch (InterruptedException e) {
							System.err.println(drugName+" Interrupted after "+(System.currentTimeMillis()-l1));
						} catch (FileNotFoundException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					
				};
				t.setName(drugName + " Pump Timeout Thread");
				t.start();
				
				// Read Char by char
				try {
					char c;
					//response[0] = fromDevice.readLine();
					while( (c=(char)fromDevice.read())!=-1 && c!='\r') {
						sb.append(c);
//						System.err.println(sb);
					}
					PUMPresponse=sb.toString();
					mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| From Pump |" +PUMPresponse );
				} catch (IOException ioe) {
					ioeDuringRead[0] = ioe;
					ioe.printStackTrace();
				}
				t.interrupt();
				System.err.println(drugName + ": Response from Alaris is " + PUMPresponse);
				easyTivaLog.trace(">>> " + drugName + " "+ PUMPresponse);
				
				//  write pump response to easytiva
				try {
					if(timedAlarms && PUMPresponse.startsWith("!INF^")) {
						easyTivaLog.trace(drugName +" timedAlarm and INF command satisfied");
						long currentInterval=System.currentTimeMillis() - startTime;
						System.err.println("Current Interval for Alarms is: " + currentInterval);
						if(
							(currentInterval>START_ALARM_RANGE_ONE && currentInterval<END_ALARM_RANGE_ONE) ||
							(currentInterval>START_ALARM_RANGE_TWO && currentInterval<END_ALARM_RANGE_TWO) ||
							(currentInterval>START_ALARM_RANGE_THREE && currentInterval<END_ALARM_RANGE_THREE)
						) {
							easyTivaLog.trace(drugName + " Time interval conditions satisfied");
							String fields[]=PUMPresponse.split("\\^");
							String lastField=fields[fields.length-1];
							lastField=lastField.substring(0,lastField.indexOf('|'));
							
							fields[fields.length-1]=lastField;
							
							if(fields[2].equals("-")) {
								//Replace field 2...
								fields[2]="A";
								fields[3]="SET";
								fields[4]="0.00";
								int f = Integer.valueOf(lastField);
								fields[fields.length-1] = String.valueOf(f);
							}
							//Trim ! off first field
							fields[0]=fields[0].substring(1);
							//Get rid of checksum from last field.
							
							//Merge strings together
							String finalFakeResponse=crc(String.join("^", fields));
							easyTivaLog.trace("Reconstructed INF command and sending to Requestor");
							easyTivaLog.trace("Reconstructed INF is: " + finalFakeResponse);
//							easyTivaLog.trace("Sending actual command - !INF^8002-51733^A^HLD^0.00^ml/h^^0.969^ml^530.15^mmHg^00:04:09^EVENT^214725|DB9D");
//							writetoRequestor("!INF^8002-51733^A^HLD^0.00^ml/h^^0.969^ml^530.15^mmHg^00:04:09^EVENT^214725|DB9D");
							writetoRequestor(finalFakeResponse);
							mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| To EasyTIVA |" +finalFakeResponse );
						} else {
							easyTivaLog.trace(drugName + " Time interval condition not satisfied for INF");
							writetoRequestor(PUMPresponse);
							mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| To EasyTIVA |" +PUMPresponse );
						}
					}
					
					else if(timedAlarms && PUMPresponse.startsWith("!ALARM")) {
						long currentInterval=System.currentTimeMillis() - startTime;
						if(
							(currentInterval>START_ALARM_RANGE_ONE && currentInterval<END_ALARM_RANGE_ONE) ||
							(currentInterval>START_ALARM_RANGE_TWO && currentInterval<END_ALARM_RANGE_TWO) ||
							(currentInterval>START_ALARM_RANGE_THREE && currentInterval<END_ALARM_RANGE_THREE)
						) {
							//TODO: Check if this is an accurate occlusion alarm
//							String finalFakeResponse;
//							if (alarmFields[1]!= null) {
//								finalFakeResponse=crc("ALARM^"+ alarmCodes[Integer.valueOf(alarmFields[1])-1] +"^^");
//							}else {
//								finalFakeResponse = crc("ALARM^AL_OCCLU^OCCLUSION^"); // default alarm code sent
//							}
							String finalFakeResponse =  crc("ALARM^AL_VTBIS^^");
							easyTivaLog.trace("Writing 'finalFakeResponse' to Requestor");
							easyTivaLog.trace("finalFakeResponse is - " + finalFakeResponse);
							writetoRequestor(finalFakeResponse);
							mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| To EasyTIVA |" +finalFakeResponse );
							
						} else {
							easyTivaLog.trace(drugName + " Time interval condition not satisfied for ALARM");
							writetoRequestor(PUMPresponse);
							mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| To EasyTIVA |" +PUMPresponse );
						}
					}else {
						easyTivaLog.trace(drugName + " timedAlarm and INF/ALARM command not satisfied");
						writetoRequestor(PUMPresponse);
						mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| To EasyTIVA |" +PUMPresponse );
					}

					
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
	private void retrySendCommand() {
		
		try {
			toDevice.write(saveCommand);
			toDevice.flush();
			mitmTimeLog.trace(drugName + "|"+ System.currentTimeMillis()  +"| To Pump - Rewrite |" +saveCommand );
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		System.err.println(drugName + ": Command rewrite attempt to AlarisGH - " + saveCommand);
		sentCommands.add(new TimeAndCommand(System.currentTimeMillis()-ourStartMillis, saveCommand));
		easyTivaLog.trace("<<< " + drugName + " "+ saveCommand + " (attempted rewrite)");
		
	}
	
	/*
	 * I don't think this cycle of events make sense.  Reading from the requestor here makes it seem
	 * as if the tiva responds to the response from the pump.
	 */
	private synchronized void writetoRequestor(String command) throws IOException {
		
		command+="\r";
		System.err.println(drugName + ": Receiving response from AlarisGH - "+ command);
//		byte[] initBytes = command.getBytes();
		toRequestor.write(command);
		toRequestor.flush();
		sentResponses.add(new TimeAndCommand(System.currentTimeMillis()-ourStartMillis, command));
	}
		
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
        String fullCommand = "!" + message +"|" + hexValue/* + "\r"*/;
        
        return fullCommand;
		
	}
	
	public static String valueOf(short number) {
        // Create a mask to isolate only the correct width of bits.
        long fullMask = (((1L << 15) - 1L) << 1) | 1L;
        return Long.toHexString(number & fullMask);
      }
	
	@Override
	public void disconnect() {
		if(toDevice == null) {
			super.disconnect();
			return;
		}
		//byte[] initBytes = REMOTEDISABLE.getBytes();
		try {
			toDevice.write(REMOTEDISABLE);
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
		keepGoing=false;
		t1.interrupt();
		p1.interrupt();
		super.shutdown();
	}
	
	@Override
    protected String iconResourceName() {
        return "alaris_asena_pump.png";
    }
	
}

        

