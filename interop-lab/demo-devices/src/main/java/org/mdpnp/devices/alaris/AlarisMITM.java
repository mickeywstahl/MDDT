package org.mdpnp.devices.alaris;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
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

import org.mdpnp.devices.DeviceClock;
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
	
	private static final long MAX_WAIT=500L;
	
	private static final int MAX_RETRIES=5;
	
	private static final Logger Log = LoggerFactory.getLogger(BisMonitor.class);
	private static final Logger easyTivaLog = LoggerFactory.getLogger("easy.tiva");

	private boolean pendingWriteBolus = false;
	float setPumpSpeed;
	float setVTBIvol;
	float setBolusSpeed;
	float setBolusVol;
	int masterValue = (int)1;
	
	Queue<String> TIVAq = new LinkedList<>();
	Queue<String> PUMPq = new LinkedList<>();
	
	private InfusionObjectiveDataReader pauseResumeReader;
	private InfusionProgramDataReader programReader;
	private Topic pauseResumeTopic, programTopic;
	private QueryCondition pauseResumeQueryCondition, programQueryCondition;

	
	private static final Logger log = LoggerFactory.getLogger(AlarisMITM.class);
	
	private String drugName;
	private String saveCommand;
	
	private boolean remoteDisableFlag = false;
	
	private static final String REMOTEDISABLE = "!REMOTE_CTRL^DISABLED|EF95\r";
	
	private static final String INFSTART = "!INF_START|38F3\r";
	private static final String INFSTOP = "!INF_STOP|CD57\r";
	
	private static final int MAX_RESPONSE_LEN = 15000;
	
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
		String TIVArequest = null, responsePump =null;
		System.err.println("process starts for idx "+idx);
		
		if(idx==0) {
			fromRequestor= new BufferedReader(new InputStreamReader(inputStream)); //inputStream;
			//toRequestor=new BufferedOutputStream(outputStream);
			toRequestor=new BufferedWriter(new OutputStreamWriter(outputStream));
//			toLog=new BufferedOutputStream(new FileOutputStream("C:/Users/MDPNP/bis_"+System.currentTimeMillis()+".log"));
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
//			try {
//				System.err.println("!!!!!!! Current status of remote disable flag is " + remoteDisableFlag + " !!!!!!!");
//				Thread.sleep(Long.MAX_VALUE);
//			} catch (InterruptedException ie) {
//				ie.printStackTrace();
//			}
				if(remoteDisableFlag) {
					System.err.println("!! Current status of remote disable flag for "+ drugName +" is " + remoteDisableFlag + " !!");
					remoteDisableFlag = false;
					break;
				}
			}
		}
		
//		while(true) {
//			if(idx==0) {
//				long t1 = System.currentTimeMillis();
//				
//				TIVArequest=fromRequestor.readLine();
//				TIVAq.add(TIVArequest);
//				System.err.println(drugName+" Next request from EasyTIVA is " + TIVArequest+" at "+t1);
//				responsePump = writetoDevice(TIVArequest);
//				writetoRequestor(responsePump);
//				long t2 = System.currentTimeMillis();
//				
//				if (t2 - t1 > 6000) {
//					System.err.println("Breaking out from while(true) for idx==0");
//					break;
//				}
//			}
//			if (idx==1) {
//				
//			}
//		}
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
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					TIVAq.add(TIVArequest);
					long t1 = System.currentTimeMillis();
					System.err.println(drugName+" Next request from EasyTIVA is " + TIVArequest+" at "+t1);
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
				byte[] initBytes = command.getBytes();
				try {
					toDevice.write(command);
					toDevice.flush();
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
						System.err.println(sb);
					}
					PUMPresponse=sb.toString();	
				} catch (IOException ioe) {
					ioeDuringRead[0] = ioe;
					ioe.printStackTrace();
				}
				t.interrupt();
				System.err.println(drugName + ": Response from Alaris is " + PUMPresponse);
				
				//  write pump response to easytiva
				try {
					writetoRequestor(PUMPresponse);
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
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		System.err.println(drugName + ": Command rewrite attempt to AlarisGH - " + saveCommand);
		sentCommands.add(new TimeAndCommand(System.currentTimeMillis()-ourStartMillis, saveCommand));
		easyTivaLog.trace("<<< " + drugName + " "+ saveCommand + " (attempted rewrite)");
		
	}
	
	private synchronized String writetoDeviceOnce(String command) throws IOException {
		command+="\r";
		System.err.println(drugName + ": Receiving Command from EasyTIVA - " + command);
		byte[] initBytes = command.getBytes();
		toDevice.write(command);
		toDevice.flush();
		System.err.println(drugName + ": Command written to AlarisGH - " + command);
		sentCommands.add(new TimeAndCommand(System.currentTimeMillis()-ourStartMillis, command));
		easyTivaLog.trace("<<< " + drugName + " "+ command);
		//We are not timed out yet.
		boolean[] timedOut=new boolean[] {false};
	
		IOException[] ioeDuringRead=new IOException[1];
		String[] response = new String [1];
		final StringBuilder sb=new StringBuilder();
		Thread t = new Thread() {

			@Override
			public void run() {
				long l1=System.currentTimeMillis();
				try {
					
					sleep(MAX_WAIT);
					if (response[0]==null || response[0].length() == 0){
						System.err.println("!!!!! " + drugName + " Pump did not send response to EasyTIVA within "+MAX_WAIT);
						easyTivaLog.trace("!!!!! "+ drugName + " Pump did not send response to EasyTIVA within "+MAX_WAIT);
						System.err.println("String read when looking for line is "+sb.toString());
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
		
		try {
			char c;
			//response[0] = fromDevice.readLine();
			while( (c=(char)fromDevice.read())!=-1 && c!='\r') {
				sb.append(c);
			}
			response[0]=sb.toString();	
		} catch (IOException ioe) {
			ioe.printStackTrace();
			ioeDuringRead[0]=ioe;
		}
		t.interrupt();
		System.err.println(drugName + ": Response from Alaris is " + response[0]);
		
		return response[0];
	}
	
	private synchronized String writetoDevice(String command) throws IOException {
		String ret=null;
		int count=0;
		while( (ret==null || ret.length()==0) && count<5 ) {
			System.err.println("Calling writeToDeviceOnce count "+ (count++) +" for command "+command);
			ret=writetoDeviceOnce(command);
		}
//		if(ret==null || ret.length()==0) {
//			PrintStream commands=new PrintStream(new File("commands.log"));
//			for(int i=0;i<sentCommands.size();i++) {
//				commands.println(sentCommands.get(i).toString());
//			}
//			commands.close();
//			
//			PrintStream responses=new PrintStream(new File("responses.log"));
//			for(int i=0;i<sentResponses.size();i++) {
//				responses.println(sentResponses.get(i).toString());
//			}
//			responses.close();
//		}
		return ret;
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
		
	private synchronized String pauseInfusion() throws IOException {
		
		//byte[] initBytes = INFSTOP.getBytes();
		toDevice.write(INFSTOP);
		toDevice.flush();
		System.err.println("Attempting to pause infusion");
		System.err.println("Reading for response - INF STOP");
		
		String nextLine=fromDevice.readLine();		
		System.err.println("INF STOP command response is " + nextLine);
		return nextLine;
	}
	
	private synchronized void startInfusion() throws IOException {
		
		//byte[] initBytes = INFSTART.getBytes();
		toDevice.write(INFSTART);
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

	
	private final void addObjectiveListeners() {
		addPauseResumeListener();
		addProgramListener();
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

        

