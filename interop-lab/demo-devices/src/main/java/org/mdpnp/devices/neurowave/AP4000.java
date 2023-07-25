package org.mdpnp.devices.neurowave;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;

import org.apache.commons.lang3.ArrayUtils;
import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.connected.AbstractConnectedDevice;
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

import ice.FlowRateObjectiveDataReader;
import ice.InfusionObjectiveDataReader;
import ice.InfusionProgram;
import ice.InfusionProgramDataReader;
import ice.Numeric;

/**
 * A device driver for the AP-4000 pump, following the specification documents that we were provided with.
 * 
 * Because we need to renew the authorization key, we use a thread that runs every few seconds to check the
 * age, and if that thread decides that the key needs to be renewed, it needs I/O with the pump.  Because of
 * that, all methods that do pump I/O are synchronized.  If this proves to be problematic for performance, we
 * can try and narrow the sync down.  The exception is doInitCommands(), because that's all initialization,
 * and other threads shouldn't be coming in to play at that point.
 * 
 * @author MDPNP
 *
 */
public class AP4000 extends AbstractSerialDevice {
	
	private static final Logger log = LoggerFactory.getLogger(AP4000.class);
	
	private Logger externalLog;
	
	private boolean initDone;
	
	private BufferedInputStream fromDevice;
	private BufferedOutputStream toDevice;
	
	/**
	 * The connection request command is always the same.
	 */
	static final String CONNREQ="!CONNREQ|02B3\r\n";
	
	/**
	 * The communication protocol version reported by the pump
	 */
	private String commVersion;
	
	/**
	 * The system id reported by the pump
	 */
	private String sysId;
	
	private String softwareVersion;
	
	private String cfgVersion;
	
	/**
	 * The system date reported by the pump
	 */
	private String sysDate;
	
	/**
	 * The system time reported by the pump
	 */
	private String sysTime;
	
	/**
	 * The session case id reported by the pump in a STATREQ response
	 */
	private String sessionCaseId;
	
	/**
	 * Device ID serial number 1, reported by the pump in a STATREQ response
	 * and required when requesting or performing control.
	 */
	private String devIdSn1;
	
	/**
	 * Device ID serial number 2, reported by the pump in a STATREQ response
	 * and required when requesting or performing control.
	 */
	private String devIdSn2;
	
	/**
	 * The current authorisation token for remote control of the device.  This is obtained
	 * via an initial CNTLREQ request, and then maintained by an instance of {@link #AuthKeyRenewer}.
	 * When a called using the token wants to kill the token, it should set {@link #releaseToken} to
	 * true, which will cause AuthKeyRenewer to exit.
	 */
	private String currentAuthKey;
	
	/**
	 * A boolean to tell the auth key renewal thread that it can exit because
	 * the token is not required any more.  The setter of this should take care
	 * of unsetting {@link #currentAuthKey} as only it truly knows that the token
	 * is not needed anymore.
	 */
	private boolean releaseToken;
	
	/**
	 * A thread that takes care of renewing {@link #currentAuthKey} within the specified time limit.
	 * This member should be created whenever {@link #currentAuthKey} is initially obtained, and then
	 * start should be called on it.
	 */
	private AuthKeyRenewer renewer;
	
	/**
	 * The max expected length of a response.  No particular note of what this should be,
	 * this is stolen from some other example we saw
	 */
	private static final int MAX_RESPONSE_LEN=15000;
	
	/**
	 * An instance holder for pump flow rate.  In time, we will need two of these.
	 */
	private final InstanceHolder<Numeric>
		flowRateHolderHeadOne, flowRateHolderHeadTwo, volumeInfusedHeadOne, volumeInfusedHeadTwo, vtbiProgrammedHeadOne, vtbiProgrammedHeadTwo,
		volumeRemainingHeadOne, volumeRemainingHeadTwo, bolusInfusedHeadOne, bolusInfusedHeadTwo, cumulativeBolusHeadOne, cumulativeBolusHeadTwo;
	
	private FlowRateObjectiveDataReader flowRateReader;
	private InfusionObjectiveDataReader pauseResumeReader;
	private InfusionProgramDataReader programReader;
	private Topic flowRateTopic,pauseResumeTopic, programTopic;
	private QueryCondition flowRateQueryCondition, pauseResumeQueryCondition, programQueryCondition;
	
	//private DeviceClock.WallClock wallClock;
	private DeviceClock.CombinedReading combinedReading;
	
	/**
	 * A calendar instance to use to get the device time in millis;
	 */
	private Calendar deviceTimeCalendar=Calendar.getInstance();
	
	private ArrayList<String[]> recentStats=new ArrayList<>();
	
	private int statIndex=0;
	
	/**
	 * String constant for version 0.1 of the protocol.  In this version, there is no &quot;v&quot; prefix.
	 */
	private static final String V_0_1="0.1";
	
	/**
	 * String constant for version 0.2 of the protocol.  In this version, there is a &quot;v&quot; prefix.
	 */
	private static final String V_0_2="v0.2";

	public AP4000(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
		this(subscriber, publisher, eventLoop,1);
	}

	public AP4000(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		Thread.dumpStack();
		deviceIdentity.manufacturer="Neurowave";
		deviceIdentity.model="AP-4000";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		super.writeDeviceIdentity();
		flowRateHolderHeadOne=createNumericInstance("MDC_FLOW_FLUID_PUMP_1", "DEV_STATUS_INFRATE_ACTUAL1");
		flowRateHolderHeadTwo=createNumericInstance("MDC_FLOW_FLUID_PUMP_2", "DEV_STATUS_INFRATE_ACTUAL2");
		volumeInfusedHeadOne=createNumericInstance("MDC_VOL_FLUID_DELIVERED_1", "DEV_STATUS_TVI1");
		volumeInfusedHeadTwo=createNumericInstance("MDC_VOL_FLUID_DELIVERED_2", "DEV_STATUS_TVI2");
		vtbiProgrammedHeadOne=createNumericInstance("ICE_PROGRAMMED_VTBI_1", "DEV_PROG_CONT_VTBI1");
		vtbiProgrammedHeadTwo=createNumericInstance("ICE_PROGRAMMED_VTBI_2", "DEV_PROG_CONT_VTBI2");
		volumeRemainingHeadOne=createNumericInstance("MDC_VOL_FLUID_TBI_REMAIN_1", "DEV_STATUS_VTBIr1");
		volumeRemainingHeadTwo=createNumericInstance("MDC_VOL_FLUID_TBI_REMAIN_2", "DEV_STATUS_VTBIr2");
		bolusInfusedHeadOne=createNumericInstance("ICE_BOLUS_INFUSED_1", "DEV_STATUS_BOL_INFUSED1");
		bolusInfusedHeadTwo=createNumericInstance("ICE_BOLUS_INFUSED_2", "DEV_STATUS_BOL_INFUSED2");
		cumulativeBolusHeadOne=createNumericInstance("ICE_BOLUS_SESSION_TOTAL_1", "DEV_STATUS_ACC_BOL_VOL1");
		cumulativeBolusHeadTwo=createNumericInstance("ICE_BOLUS_SESSION_TOTAL_2", "DEV_STATUS_ACC_BOL_VOL2");
		addObjectiveListeners();
	}
	
	void setExternalLog(Logger external) {
		this.externalLog=external;
	}
	
	void otherLog(String msg) {
		if(externalLog!=null) {
			externalLog.trace(msg);
		}
	}
	
	private final void addObjectiveListeners() {
		addFlowRateListener();
		addPauseResumeListener();
		addProgramListener();
	}
	
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
                            		log.error("Failed to set pump speed", ioe);
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
                            			pauseInfusion(data.head);
                            		} else {
                            			resumeInfusion(data.head);
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
                            		log.error("Failed to program pump", ioe);
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
	
	private void setSpeed(float newSpeed) throws IOException {
		
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
//		Thread.dumpStack();
		initDone=false;	//In case we are coming back here after a comms interruption.
		byte[] initBytes=CONNREQ.getBytes();
		toDevice.write(initBytes);
		toDevice.flush();
		System.err.println("wrote CONNREQ in doInitCommands");
		log.info("wrote CONNREQ in doInitCommands");
		//byte[] responseBytes=new byte[MAX_RESPONSE_LEN];
		byte[] responseBytes=new byte[96];
		System.err.println("About to read for response");
		log.info("About to read for response");
		int bytesRead=0;
		/*
		 * We could probably rely on a fixed response length, but in the absence of that,
		 * we need at least 9 bytes ( !CONNREQ^ ) and we need to have 7 bytes at the end that
		 * start with '|' to designate the checksum, plus \r \n.  So given that it's a line, we
		 * could probably just use a line reader - if we know that all other responses are terminated
		 * the same way.
		 */
		/*
		 * 0.1 looks like this
		 * !CONNREQ^0.1^032-19-50-014^2022-11-04^11:10:36.067|0B8B
		 * 
		 * 0.2 looks like this
		 * !CONNREQ^v0.2^066-22-20-003^v0.1.0.8218^v1.0^2022-11-04^11:59:44.217|102C
		 */
		while(bytesRead<9 || responseBytes[bytesRead-7] != '|') {
			System.err.println("doing a read with bytesRead "+bytesRead);
			bytesRead+=fromDevice.read(responseBytes,bytesRead,responseBytes.length-bytesRead);
			System.err.println("response is now "+new String(responseBytes,0,bytesRead));
		}
		System.err.println(ArrayUtils.toString(responseBytes));
		
//		System.err.println("bytesRead in response is "+bytesRead);
		String response=new String(responseBytes,0,bytesRead);
//		System.err.println("bytesRead 0 is "+responseBytes[0]);
//		System.err.println("doInitCommands response is "+response);
		String parts[]=response.split("\\^");
		if(!parts[0].equals("!CONNREQ")) {
//			System.err.println("Bad response to !CONNREQ command - parts[0] is "+parts[0]);
			log.error("Bad response to !CONNREQ command - parts[0] is "+parts[0]);
			return;
		}
		commVersion=parts[1];
		if(commVersion.equals(V_0_1)) {
			sysId=parts[2];
			sysDate=parts[3];
			String timeAndSum[]=parts[4].split("\\|");
			sysTime=timeAndSum[0];
			String checksum=timeAndSum[1];
		} else if(commVersion.equals(V_0_2)) {
			sysId=parts[2];
			softwareVersion=parts[3];
			cfgVersion=parts[4];
			sysDate=parts[5];
			String timeAndSum[]=parts[6].split("\\|");
			sysTime=timeAndSum[0];
			String checksum=timeAndSum[1];
		} else {
			throw new RuntimeException("Unsupported commVersion "+commVersion);
		}
		//TODO: Check the checksum.
		reportConnected("CONNREQ received for system id "+sysId);
		log.info("CONNREQ received for system id "+sysId);
		System.err.println("CONNREQ received for system id "+sysId);
		initDone=true;
	}
	
	protected boolean keepGoing=true;
	
	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		Thread.dumpStack();
		this.fromDevice=new BufferedInputStream(inputStream);
		this.toDevice=new BufferedOutputStream(outputStream);
		try {
			while( ! initDone) {
				Thread.sleep(1000L);
			}
			while(keepGoing) {
				doStatReq();
				Thread.sleep(3000);
			}
			System.err.println("process stopped on keepGoing=false");
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	/**
	 * Gets the current flow rate for the given head.  Mainly to use for the integration
	 * with translation devices.  Most cases should just subscribe to the DDS values that
	 * are published.
	 * 
	 * @param head Which head to return the flow rate for.
	 * @return
	 */
	float getFlowRate(int head) {
		if(head==1) {
			return flowRate1;
		}
		return flowRate2;	//Assuming they don't ask for head 3 or something...
	}
	
	float getVolumeInfused(int head) {
		if(head==1) {
			return volumeInfused1;
		}
		return volumeInfused2;
	}
	
	/**
	 * It's not clear yet if this is the time remaining or something completely different.
	 * @param head
	 * @return
	 */
	float getTimeRemaining(int head) {
		if(head==1) {
			return time1;
		}
		return time2;
	}
	
	private float flowRate1, flowRate2, volumeInfused1, volumeInfused2, time1, time2;
	
	/**
	 * Performs a status request of the pump, and 
	 * @throws IOException
	 */
	private synchronized void doStatReq() throws IOException {
		byte statBytes[]=createCommand("STATREQ^"+sysId);
		toDevice.write(statBytes);
		toDevice.flush();
		byte responseBytes[]=new byte[MAX_RESPONSE_LEN];
		int bytesRead=0;
		/*
		 * We could probably rely on a fixed response length, but in the absence of that,
		 * we need at least 9 bytes ( !CONNREQ^ ) and we need to have 7 bytes at the end that
		 * start with '|' to designate the checksum, plus \r \n.  So given that it's a line, we
		 * could probably just use a line reader - if we know that all other responses are terminated
		 * the same way.
		 */
		while(bytesRead<9 || responseBytes[bytesRead-7] != '|') {
			//System.err.println("doing a read with bytesRead "+bytesRead);
			bytesRead+=fromDevice.read(responseBytes,bytesRead,responseBytes.length-bytesRead);
			//System.err.println("response is now "+new String(responseBytes,0,bytesRead));
		}
		
		String response=new String(responseBytes,0,bytesRead);
		//System.err.println("STATREQ response is "+response);
		response=response.replace("\r\n","");
		String parts[]=response.split("\\^");
		if(recentStats.size()<5) {
			recentStats.add(parts);
			statIndex++;
		} else {
			recentStats.set(statIndex++%5, parts);
		}
		
		
		//dumpRecentStats();
		String sysDate=parts[3];
		String sysTime=parts[4];
		//System.err.println("sysDate and time are "+sysDate+" "+sysTime);
		/*
		 * We can create a date using the constructor that has the all the fields from years to seconds.
		 * We lose the milliseconds that were available from the time though.
		 */
		//TODO: Faster to use a compiled pattern match?  Probably not an issue at 1Hz...
		
		String splitDate[]=sysDate.split("-");
		String millis="0";
		if(commVersion.equals(V_0_1)) {
			String splitTime[]=sysTime.split(":");
			String splitMillis[]=splitTime[2].split("\\.");
			
			deviceTimeCalendar.set(
					Integer.parseInt(splitDate[0]),		//year
					Integer.parseInt(splitDate[1])-1,	//0 based month
					Integer.parseInt(splitDate[2]),		//day
					Integer.parseInt(splitTime[0]),		//hours
					Integer.parseInt(splitTime[1]),		//minutes
					Integer.parseInt(splitMillis[0])	//seconds
			);
			millis=splitMillis[1];
		} else if(commVersion.equals(V_0_2)) {
			String splitTime[]=sysTime.split(":");
		
			deviceTimeCalendar.set(
					Integer.parseInt(splitDate[0]),		//year
					Integer.parseInt(splitDate[1])-1,	//0 based month
					Integer.parseInt(splitDate[2]),		//day
					Integer.parseInt(splitTime[0]),		//hours
					Integer.parseInt(splitTime[1]),		//minutes
					Integer.parseInt(splitTime[2])		//seconds
			);
			millis=splitTime[3];
		}
		
//		System.err.println("millis from reading is "+millis);
		long msForReading=deviceTimeCalendar.getTimeInMillis()+Integer.parseInt(millis);	//Add on the extra milliseconds
//		System.err.println("msForReading "+msForReading);
		
		String sysOper=parts[5];
		sessionCaseId=parts[6];
		String flowRate1=parts[61];
		this.flowRate1=Float.parseFloat(flowRate1);
		String flowRate2=parts[94];
		this.flowRate2=Float.parseFloat(flowRate2);
		devIdSn1=parts[34];
		devIdSn2=parts[67];
		String programmedVTBI1=parts[42];
		String programmedVTBI2=parts[75];
//		System.err.println("FlowRate1 is "+flowRate1);
//		System.err.println("FlowRate2 is "+flowRate2);
		String channelOneDrugName=parts[37];
		String channelOneDrugConc=parts[38];
		if(channelOneDrugConc.equals("0")) {
			channelOneDrugConc="N/A";
		} else {
			channelOneDrugConc+=" "+parts[39];
		}
		String channelTwoDrugName=parts[70];
		String channelTwoDrugConc=parts[71];
		if(channelTwoDrugConc.equals("0")) {
			channelTwoDrugConc="N/A";
		} else {
			channelTwoDrugConc+=" "+parts[72];			
		}
//		System.err.println("channel one drug "+channelOneDrug);
		
//		for(int i=87;i<99;i++) {
//			System.err.println(i+" "+parts[i]);
//		}
		
		String strTime1=parts[54];	//Is this time remaining?
//		this.time1=Float.parseFloat(strTime1);
		this.time1= -1;
		
		String strTime2=parts[87];	//Is this time remaining?
//		this.time2=Float.parseFloat(strTime2);
		this.time2= -1;
		
		String strTotalVolumeInfused1=parts[56];
		volumeInfused1=Float.parseFloat(strTotalVolumeInfused1);
		String strTotalVolumeInfused2=parts[89];
		volumeInfused2=Float.parseFloat(strTotalVolumeInfused2);
		
		String strVolumeRemaining1=parts[58];
		String strVolumeRemaining2=parts[91];
		
		String strBolusInfused1=parts[62];
		String strBolusInfused2=parts[95];
		
		String strTotalBolusSession1=parts[63];
		String strTotalBolusSession2=parts[96];
		
		//Patient information
		String weight=parts[7];
		String height=parts[8];
		String age=parts[9];
		String gender=parts[10];
		String anState=parts[11];
		
		long forNow=System.currentTimeMillis();
		combinedReading=new DeviceClock.CombinedReading(new DeviceClock.ReadingImpl(forNow),new DeviceClock.ReadingImpl(msForReading));
		numericSample(flowRateHolderHeadOne, Float.parseFloat(flowRate1), combinedReading);
		numericSample(flowRateHolderHeadTwo, Float.parseFloat(flowRate2), combinedReading);
		numericSample(volumeInfusedHeadOne, Float.parseFloat(strTotalVolumeInfused1), combinedReading);
		numericSample(volumeInfusedHeadTwo, Float.parseFloat(strTotalVolumeInfused2), combinedReading);
		numericSample(vtbiProgrammedHeadOne, Float.parseFloat(programmedVTBI1), combinedReading);
		numericSample(vtbiProgrammedHeadTwo, Float.parseFloat(programmedVTBI2), combinedReading);
		numericSample(volumeRemainingHeadOne, Float.parseFloat(strVolumeRemaining1), combinedReading);
		numericSample(volumeRemainingHeadTwo, Float.parseFloat(strVolumeRemaining2), combinedReading);
		numericSample(bolusInfusedHeadOne, Float.parseFloat(strBolusInfused1), combinedReading);
		numericSample(bolusInfusedHeadTwo, Float.parseFloat(strBolusInfused2), combinedReading);
		numericSample(cumulativeBolusHeadOne, Float.parseFloat(strTotalBolusSession1), combinedReading);
		numericSample(cumulativeBolusHeadTwo, Float.parseFloat(strTotalBolusSession2), combinedReading);
		
		writeTechnicalAlert("CASE_ID", sessionCaseId);
		writeTechnicalAlert("SYS_ID", sysOper);
		writeTechnicalAlert("CHANNEL1_DRUG_NAME",channelOneDrugName);
		writeTechnicalAlert("CHANNEL1_DRUG_CONC",channelOneDrugConc);
		writeTechnicalAlert("CHANNEL2_DRUG_NAME",channelTwoDrugName);
		writeTechnicalAlert("CHANNEL2_DRUG_CONC",channelTwoDrugConc);
		writeTechnicalAlert("Model", "AP4000");
		writeTechnicalAlert("UDI", deviceIdentity.unique_device_identifier);
		//writeTechnicalAlert("Current_Alarm", "No alarms currently active");
		//Test alarm you can publish for an app...
		//publishAlarm("PA.02", "PUMP1", "HIGH", "HP_PUMP");
		
		writeTechnicalAlert("Neurowave_PT_WEIGHT", weight);
		writeTechnicalAlert("Neurowave_PT_HEIGHT", height);
		writeTechnicalAlert("Neurowave_PT_AGE", age);
		writeTechnicalAlert("Neurowave_PT_STATUS", anState);
		
		writePatientAlert("PT_WEIGHT",weight);
		writePatientAlert("PT_HEIGHT",height);
		writePatientAlert("PT_AGE",age);
		writePatientAlert("PT_GENDER",gender);
		writePatientAlert("PT_STATE",anState);
		
		/*
		 * Do some alarm handling.
		 */
		processAlarms(parts);
		
		
		
		//System.err.println(ArrayUtils.toString(parts));
	}
	
	enum ALARM_PRIORITY {
		NONE,
		LOW,
		MEDIUM,
		HIGH
	}
	
	class AP4000Alarm {
		String code;
		String device;
		String priority;
		String sound;
		int prio;
		
		public AP4000Alarm(String code, String device, String priority, String sound) {
			super();
			this.code = code;
			this.device = device;
			this.priority = priority;
			this.sound = sound;
		}
	}
	
	ArrayList<AP4000Alarm> alarmList=new ArrayList<>();
	
	private void processAlarms(String[] parts) {
		String alarmCount=parts[12];
		int alarm_n=Integer.parseInt(alarmCount);
		if(alarm_n==-1) {
			//No alarms.
			alarmList.clear();
			return;
		}
		String alarmAudio=parts[13];	//Not sure what/if we need this for?
		/*
		 * There are up to four alarm structures available, each having
		 * ALARM_CODEn
		 * ALARM_DEVICEn
		 * ALARM_PRIORITYn
		 * ALARM_SOUNDn
		 */
		int nextIndex=14;
		for(int i=0;i<alarm_n;i++) {
			String alarmCode=parts[nextIndex++];
			String alarmDevice=parts[nextIndex++];
			String alarmPriority=parts[nextIndex++];
			String alarmSound=parts[nextIndex++];
			AP4000Alarm alarm=new AP4000Alarm(alarmCode, alarmDevice, alarmPriority, alarmSound);
			alarmList.add(alarm);
			publishAlarm(alarm);
		}
	}
	
	/**
	 * Publish an alarm.
	 * 
	 * @param code
	 * @param device
	 * @param priority
	 * @param sound
	 */
	private void publishAlarm(AP4000Alarm alarm) {
		writeTechnicalAlert(alarm.code, alarm.device+"!"+alarm.priority+"!"+alarm.sound);
	}
	
	private void dumpRecentStats() {
		int refSize=recentStats.get(0).length;	//num of fields from one response.
		int numOfStats=recentStats.size();
		for(int i=0;i<refSize;i++) {
			String base=recentStats.get(0)[i];
			StringBuilder sb=new StringBuilder("|"+i+"|");
			boolean diff=false;
			for(int j=0;j<numOfStats;j++) {
				if( ! recentStats.get(j)[i].equals(base)) {
					diff=true;
				}
				sb.append(recentStats.get(j)[i]);
				sb.append("\t");
				sb.append("|");
			}
			if(diff) {
				System.err.println(sb.toString());
			}
			
		}
	}
	
	private synchronized void requestControl() throws IOException {
		if( ! checkRequiredControlParams(new String[] {sysId, sessionCaseId, devIdSn1, devIdSn2}) ) {
			log.error("Attempt to request control with null or empty required parameters");
		}
		byte[] controlBytes=createCommand("CNTLREQ^"+sysId+"^"+sessionCaseId+"^"+devIdSn1+"^"+devIdSn2);
		
		String parts[] = new String[1];
		parts[0]= "";
		int i =0;
//		while(parts[0]!="!CNTLREQ" || i++<5) {	
//		}
		
		toDevice.write(controlBytes);
		toDevice.flush();
		log.info("Request control with "+new String(controlBytes));
		externalLog.trace("Request control with "+new String(controlBytes));
		byte responseBytes[]=new byte[MAX_RESPONSE_LEN];
		int bytesRead=0;
		while(bytesRead<9 || responseBytes[bytesRead-7] != '|') {
			//System.err.println("doing a read with bytesRead "+bytesRead);
			bytesRead+=fromDevice.read(responseBytes,bytesRead,responseBytes.length-bytesRead);
			//System.err.println("response is now "+new String(responseBytes,0,bytesRead));
		}
		String response=new String(responseBytes,0,bytesRead);
//		System.err.println("CNTLREQ response is "+response);
		log.info("CNTLREQ response is "+response);
		externalLog.info("CNTLREQ response is "+response);
		parts=response.split("\\^");
		System.err.println(parts[0]);
		if(!parts[0].equals("!CNTLREQ")) {
			toDevice.write(controlBytes);
			toDevice.flush();
			externalLog.trace("Attempted rewrite of CNTLREQ");
			log.info("Attempted rewrite of CNTLREQ");
			while(bytesRead<9 || responseBytes[bytesRead-7] != '|') {
				bytesRead+=fromDevice.read(responseBytes,bytesRead,responseBytes.length-bytesRead);
			}
			response=new String(responseBytes,0,bytesRead);
			externalLog.info("CNTLREQ rewrite response is "+response);
			parts=response.split("\\^");
		}
		else {
			externalLog.trace("CNTLREQ came out fine");
		}
		
		String authKey=parts[3];
		log.info("Obtained "+authKey+" from control request");
		externalLog.info("Obtained "+authKey+" from control request");
		currentAuthKey=authKey;
		if(renewer==null) {
			renewer=new AuthKeyRenewer();
			renewer.start();
		}
	}
	
	synchronized void pauseInfusion(int head) throws IOException {
		if(currentAuthKey==null) {
			requestControl();
		}
		/*
		 * DEV_INF_PAUSE1 and DEV_INF_PAUSE2 should be "-1" to ignore that head,
		 * and 1 to pause that head.
		 */
		//byte[] pauseBytes=createCommand("INFPAUS^"+currentAuthKey+"^"+devIdSn1+"^1^"+devIdSn2+"^1");
		//Or instead, try 0.  Perhaps 0=pause and 1=run
		//This needs to timeout, because otherwise it gets confused after the timeout period by the connectivity loop.
		byte[] pauseBytes;
		if(head==1) {
			pauseBytes=createCommand("INFPAUS^"+currentAuthKey+"^"+devIdSn1+"^1^"+devIdSn2+"^-1");
		} else {
			pauseBytes=createCommand("INFPAUS^"+currentAuthKey+"^"+devIdSn1+"^-1^"+devIdSn2+"^1");
		}
		log.info("Calling pause infusion for head "+head+" with "+new String(pauseBytes));
		otherLog("To AP4000: "+new String(pauseBytes));
		toDevice.write(pauseBytes);
		toDevice.flush();
		byte responseBytes[]=new byte[MAX_RESPONSE_LEN];
		int bytesRead=0;
		while(bytesRead<9 || responseBytes[bytesRead-7] != '|') {
			//System.err.println("doing a read with bytesRead "+bytesRead);
			bytesRead+=fromDevice.read(responseBytes,bytesRead,responseBytes.length-bytesRead);
			//System.err.println("response is now "+new String(responseBytes,0,bytesRead));
		}
		String response=new String(responseBytes,0,bytesRead);
		otherLog("From AP4000: "+response);
//		System.err.println("INFPAUS response is "+response);
		log.info("INFPAUS response is "+response);
		
	}
	
	synchronized void resumeInfusion(int head) throws IOException {
		if(currentAuthKey==null) {
			requestControl();
		}
		/*
		 * DEV_INF_RESUME1 and DEV_INF_RESUME2 should be "-1" to ignore that head,
		 * and 1 to resume that head.
		 */
		//This needs to timeout, because otherwise it gets confused after the timeout period by the connectivity loop.
		byte[] resumeBytes;
		if(head==1) {
			resumeBytes=createCommand("INFRESM^"+currentAuthKey+"^"+devIdSn1+"^1^"+devIdSn2+"^-1");
		} else {
			resumeBytes=createCommand("INFRESM^"+currentAuthKey+"^"+devIdSn1+"^-1^"+devIdSn2+"^1");
		}
		log.info("Calling resume infusion for head "+head+" with "+new String(resumeBytes));
		otherLog("To AP4000: "+new String(resumeBytes));
		toDevice.write(resumeBytes);
		toDevice.flush();
		byte responseBytes[]=new byte[MAX_RESPONSE_LEN];
		int bytesRead=0;
		while(bytesRead<9 || responseBytes[bytesRead-7] != '|') {
			//System.err.println("doing a read with bytesRead "+bytesRead);
			bytesRead+=fromDevice.read(responseBytes,bytesRead,responseBytes.length-bytesRead);
			//System.err.println("response is now "+new String(responseBytes,0,bytesRead));
		}
		String response=new String(responseBytes,0,bytesRead);
//		System.err.println("INFRESM response is "+response);
		otherLog("From AP4000: "+response);
		log.info("INFRESM response is "+response);
		
	}
	
	synchronized String programPump(InfusionProgram program) throws IOException {
		if(currentAuthKey==null) {
			requestControl();
		}
		if(program.head<1 || program.head>2) {
			throw new IOException("Illegal head value "+program.head);
		}
		byte[] programBytes=null;
		String cmd;
		if(program.VTBI==0) {
			program.VTBI=-1;
		}
		if(program.head==1) {
			/*
			 * For now, we will just expect the calling code that passes in this program to have -1 for the fields that it
			 * does not want to set - because that's the default with a AP4000 for "ignore this, it's not being changed".
			 * We also do some casts here, down to int from the floats in the struct for the program.  That's because
			 * we have allowed float in the program struct, but the AP-4000 rejects floating point values.
			 * 
			 *  We could change the struct, but at least one other pump we know about does support floating point values.
			 */
			
			
			cmd="INFPROG^"+currentAuthKey+
						"^"+devIdSn1+"^"+(int)program.infusionRate+"^"+(int)program.VTBI+"^"+(int)program.bolusVolume+"^"+(int)program.bolusRate+
						"^"+devIdSn2+"^-1^-1^-1^-1";
		
//			cmd="INFPROG^"+currentAuthKey+
//				"^"+devIdSn1+"^"+(int)program.infusionRate+"^"+(int)program.VTBI+"^"+(int)program.bolusVolume+"^"+(int)program.bolusRate+
//					"^"+devIdSn2+"^"+(int)program.infusionRate+"^"+(int)program.VTBI+"^"+(int)program.bolusVolume+"^"+(int)program.bolusRate;


			//ACTUALLY THE INFPROG COMMAND DOES NOT CHANGE BETWEEN V_0_1 AND V_0_2 SO THE ELSE BLOCK IN HEAD 1 DOESN'T MATTER
		} else {
			//Must be 2 because we throw IOException for not 1 or 2...
			cmd="INFPROG^"+currentAuthKey+
					"^"+devIdSn1+"^-1^-1^-1^-1"+
					"^"+devIdSn2+"^"+(int)program.infusionRate+"^"+(int)program.VTBI+"^"+(int)program.bolusVolume+"^"+(int)program.bolusRate;
		}
		programBytes=createCommand(cmd);
		toDevice.write(programBytes);
		toDevice.flush();
		log.info("Sent infusion program "+new String(programBytes));
		otherLog("To AP4000: " +new String(programBytes));
		byte responseBytes[]=new byte[MAX_RESPONSE_LEN];
		int bytesRead=0;
		while(bytesRead<9 || responseBytes[bytesRead-7] != '|') {
			//System.err.println("doing a read with bytesRead "+bytesRead);
			bytesRead+=fromDevice.read(responseBytes,bytesRead,responseBytes.length-bytesRead);
			//System.err.println("response is now "+new String(responseBytes,0,bytesRead));
		}
		String response=new String(responseBytes,0,bytesRead);
//		System.err.println("INFPROG response is "+response);
		log.info("INFPROG response is "+response);
		otherLog("From AP4000: "+response);
		return response;
	}
	
	/**
	 * Checks that all the required parameters are specified.
	 * 
	 * @return true if all parameters are not null and not empty.  false otherwise.
	 */
	private boolean checkRequiredControlParams(String params[]) {
		for(String param : params) {
			if(param==null || param.length()==0) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Generate the full byte sequence for the given command.  The command should include all required arguments,
	 * but omit the leading ! character.  That is added by this method, which also generates and adds the final |
	 * character followed by the checksum.
	 * @param command
	 * @return
	 */
	public static byte[] createCommand(String command) {
		String checksumThis="!" + command + "|";
		String crc=crc(checksumThis);
		String fullCommand= checksumThis + crc + "\r\n";
//		if(command.indexOf("STATREQ")==-1) {
//			System.err.println("fullCommand is "+fullCommand);
//		}
		return fullCommand.getBytes();
	}

	@Override
	protected long getMaximumQuietTime(int idx) {
		// TODO Auto-generated method stub
		return 30_000L;
	}
	
	@Override
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider provider=super.getSerialProvider(idx);
		provider.setDefaultSerialSettings(115200, DataBits.Eight, Parity.None, StopBits.One, FlowControl.None);
		return provider;
	}
	
	@Override
	protected String iconResourceName() {
		// TODO Auto-generated method stub
		return "ap4000.png";
	}

	static String crc(String input) {
		char[] chars=input.toCharArray();
		int sum=0;
		for(int i=0;i<chars.length;i++) {
			sum+=(int)chars[i];
		}
		return String.format("%04X", sum);
	}
	
	private class AuthKeyRenewer extends Thread {
		
		public AuthKeyRenewer() {
			setName("AP-4000 auth key renewer");
		}

		@Override
		public void run() {
			int age=0;
			while(true) {
				if(releaseToken) {
					return;
				}
				try {
					sleep(5000);
				} catch (InterruptedException e) {
					if(releaseToken) {
						return;
					}
				}
				age+=5000;
				//The time limit for renewal is 120s.  We'll start renewal after 90s
				if(age==90000) {
					//Then call requestControl.
					try {
						String currentToken=currentAuthKey;
						requestControl();
						//TODO: DOES IT CHANGE?
						/*
						 * For now, just assume that if requestControl returns, then it's been successful,
						 * and reset the age.
						 */
						age=0;
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}
		
	}

	@Override
	public void disconnect() {
		// TODO Auto-generated method stub
		super.disconnect();
	}

	@Override
	public void shutdown() {
		// TODO Auto-generated method stub
		super.shutdown();
	}

}
