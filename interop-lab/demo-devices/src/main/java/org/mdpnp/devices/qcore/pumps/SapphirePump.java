package org.mdpnp.devices.qcore.pumps;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.codec.binary.Hex;
import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.DeviceClock.Reading;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.mdpnp.sql.SQLLogging;
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

import ice.ConnectionState;
import ice.FlowRateObjectiveDataReader;
import ice.InfusionProgramDataReader;
import ice.Numeric;
import ice.OximetryAveragingObjective;
//import ice.VTBIObjectiveDataReader;

/**
 * Perhaps we will want/need to support multiple Sapphire Pump types - they have
 * Multi-Therapy, SapphirePlus, SapphireH100 and Sapphire Epidural.  So it might
 * be that this will be better as an AbstractDelegatingSerialDevice with an abstract
 * SapphirePump as the delegate with different types extending SapphirePump.<br/>
 * <br/>
 * But we'll start with this.<br/>
 * <br/>
 * This is based on the document "Sapphire QCore Communication Protocol - Software Design Document (SWDD) Rev00"<br/>
 * and<br/>
 * "Attachment A - QCP Messaged structure"<br/>
 * <br/>
 * Message headers are big endian<br/>
 * Message data is little endian<br/>
 * 
 * @author simon
 *
 */
public class SapphirePump extends AbstractSerialDevice {
	
	private InputStream is;
	private OutputStream os;
	
	private Thread thisIsPCThread;
	
	private Thread steppingThread;
	
	/**
	 * Whether the main process loop should continue
	 */
	private boolean keepGoing;
	
	/**
	 * Indicate to interrupted threads that they should stop 
	 */
	private boolean pleaseStop;
	
	/**
	 * Whether this is the first heartbeat pass, and so we need to receive
	 */
	private boolean firstHeartbeat=true;
	
	/**
	 * Whether we are in the test stepping mode to set the flow rate
	 */
	private boolean stepping=false;
	
	private int batchesReceived;
	
	private int currentFlowRate=-1;
	
	private FlowRateObjectiveDataReader flowRateReader;
	private Topic flowRateTopic, programTopic;
	private QueryCondition flowRateQueryCondition, programQueryCondition;
	
	private InfusionProgramDataReader programReader;
	
	//private VTBIObjectiveDataReader vtbiReader;
	private Topic vtbiTopic;
	private QueryCondition vtbiQueryCondition;
	
	private PreparedStatement loggingStatement;
	
	/**
	 * We already have a private one of these?!?!
	 * @return
	 */
	public float getCurrentFlowRate() {
		return ((float)currentFlowRate)/10;
	}

	private enum EMessageTarget {
		
		e_MessageTargetCradle( (byte) (100) ),
		e_MessageTargetPump( (byte) (101) ),
		e_MessageTargetLCM( (byte) (102) ),
		e_MessageTargetPC( (byte) (103) );
		
		private byte target;
		
		EMessageTarget( byte b ) {
			this.target=b;
		}
	}
	
	private enum ETransmissionPackage {
		e_OlderTransmissionMode ( (byte) 0 ),
		e_SendBatch ( (byte) 1 ),
		e_EachByteIsMessage ( (byte) 2 ),
		e_SendCommand ( (byte) 3 ),
		//e_SendPressureCycle | e_SendGraph( (byte) 4 ),	//What is the | for here in the docs
		e_SendAbEncoderCycle ( (byte) 5 ),
		e_SendNeededFlowParameters ( (byte) 6 ),
		e_SendNeededCycleMappingParameters ( (byte) 7 ),
		e_BalanceMessage ( (byte) 8 ),
		e_SetCycleMappingParameters ( (byte) 9 ),
		e_CradleMessage ( (byte) 10 ),
		e_SendLargeSizeBatch ( (byte) 11 ),
		e_CradleReset ( (byte) 12 ),
		e_WriteDataToPumpEeprom ( (byte) 13 ),
		e_SendPressureParameters ( (byte) 14 ),
		e_ReadEepromData ( (byte) 15 ),
		e_SetPrimeParameters ( (byte) 16 ),
		e_SetAlarmStatus ( (byte) 17 ),
		e_WriteBatteryEeprom ( (byte) 18 ),
		e_SetEvent ( (byte) 19 ),
		e_ScreenCommand ( (byte) 20 ),
		e_IDSoftBatch ( (byte) 21 ),
		e_SetDateAndTime ( (byte) 22 ),
		e_SetPcToPumpDebugDwords ( (byte) 23 ),
		e_SendBatchCommand ( (byte) 24 ),
		e_GenIIMessage ( (byte) 25 ),
		e_ResponseToCommand ( (byte) 26 ),
		e_TestType ( (byte) 27 ),
		e_nTransmissionModes ( (byte) 28 );
		
		byte command;
		
		ETransmissionPackage(byte b) {
			this.command=b;
		}

	}
	
	
	private enum ETtransmissionCommand {
		
		e_RestartPrime( (byte) 0 ),
		e_EnableTransmitPressureInformation( (byte) 1 ),
		e_DisableTransmitPressureInformation( (byte) 2 ),
		e_ShowForceGraph_NotInUse( (byte) 3 ),
		e_HoldForceGraph_NotInUse( (byte) 4 ),
		e_ResetPump( (byte) 5 ),
		e_EnablePumpPcCommunication( (byte) 6 ),
		e_DisablePumpPcCommunication( (byte) 7 ),
		e_EnableSendPressureGraphData( (byte) 8 ),
		e_DisableSendPressureGraphData( (byte) 9 ),
		e_EnableSendABEncoderGraphData( (byte) 10 ),
		e_DisableSendABEncoderGraphData( (byte) 11 ),
		e_EnableSendBubbleGraphData( (byte) 12 ),
		e_DisableSendBubbleGraphData( (byte) 13 ),
		e_StartCycleMapping( (byte) 14 ),
		e_StopCycleMapping( (byte) 15 ),
		e_CycleMappingPositionAcknowledge( (byte) 16 ),
		e_PressureGraphNeedSyncronize( (byte) 17 ),
		e_PressureGraphDoNotNeedSyncronize( (byte) 18 ),
		e_AlarmsDisabledForDevelopmentOnly( (byte) 19 ),
		e_EnableAlarms( (byte) 20 ),
		e_RestoreDisableAlarmStatus( (byte) 21 ),
		e_ThisIsPc( (byte) 22 ),
		e_BootPump( (byte) 23 ),
		e_BolusRequest( (byte) 24 ),
		e_ConstantsRequest( (byte) 25 ),
		e_BubbleGraphNeedSyncronize( (byte) 26 ),
		e_BubbleGraphDoNotNeedSyncronize( (byte) 27 ),
		e_SetLightSensorToAutoWork( (byte) 28 ),
		e_SetLightSensorIlluminationOff( (byte) 29 ),
		e_SetLightSensorIlluminationNormal( (byte) 30 ),
		e_SetLightSensorIlluminationHigh( (byte) 31 ),
		e_SetLightSensorIlluminationVeryHigh( (byte) 32 ),
		e_SetForceSensorZoomIn( (byte) 33 ),
		e_ShowForceSensorFullAmplitude( (byte) 34 ),
		e_UpdateCacheParameters( (byte) 35 ),
		e_SetLightSensorHighAmplification( (byte) 36 ),
		e_SetLightSensorLowAmplification( (byte) 37 ),
		e_SetLightSensorToForceWork( (byte) 38 ),
		e_ClearBubbleAccomulatedCache( (byte) 39 ),
		e_ClearTestDwords( (byte) 40 ),
		e_ActivateLogTransmission( (byte) 41 ),
		e_DeactivateLogTransmission( (byte) 42 ),
		e_OverrideBatteryWithPumpId( (byte) 43 ),
		e_GetAlarmStatus( (byte) 44 ),
		e_ActivatePowerManagerDataTransmission( (byte) 45 ),
		e_DeactivatePowerManagerDataTransmission( (byte) 46 ),
		e_ThisIsCradle( (byte) 47 ),
		e_ActivateInfusionPumpBootEmulation( (byte) 48 ),
		e_EnableExternalWatchdog( (byte) 49 ),
		e_DisableExternalWatchdog( (byte) 50 ),
		e_EnableSendScreenGraphData( (byte) 51 ),
		e_DisableSendScreeenGraphData( (byte) 52 ),
		e_ClearDrugLibrary( (byte) 53 ),
		e_ClearPumpWorkingTime( (byte) 54 ),
		e_LockPumpByADP( (byte) 55 ),
		e_ReleaseLockPumpByADP( (byte) 56 ),
		e_SendIDSoftBatch( (byte) 57 ),
		e_ClearPumpTotalWorkingTime( (byte) 58 ),
		e_SetPumpWorkingTimeForPassedCertification( (byte) 59 ),
		e_nTransmissionCommand( (byte) 60 );
		
		byte command;
		
		ETtransmissionCommand(byte b) {
			this.command=b;
		}
	}
	
	private enum EPcToPumpFlowCommand {
		e_PcToPumpFlowCommandRun ( (byte) 0x1 ),
		e_PcToPumpFlowCommandStop ( (byte) 0x2 ),
		e_PcToPumpFlowCommandSetDropSize ( (byte) 0x4 ),
		e_PcToPumpFlowCommandSetNeededFlow ( (byte) 0x8 ),
		e_PcToPumpFlowCommandRecoverDropSize ( (byte) 0x10 ),
		e_PcToPumpFlowCommandSetPwmParameters ( (byte) 0x20 );

		byte command;
		
		EPcToPumpFlowCommand(byte b) {
			this.command=b;
		}
		
	}
	
	private enum EOperationEvent {
		e_NoOperationEvent ( (byte) 0),
		e_OperationEventPowerOn ( (byte) 1),
		e_OperationEventAllKeysReleased ( (byte) 2),
		e_OperationEventRunOrContinue ( (byte) 3),
		e_OperationEventStartRunPrimary ( (byte) 4),
		e_OperationEventStartRunSecondary ( (byte) 5),
		e_OperationEventPrime ( (byte) 6),
		e_OperationEventStartingInternalPrime ( (byte) 7),
		e_OperationEventBolus ( (byte) 8),
		e_OperationEventDose ( (byte) 9),
		e_OperationEventContinuePrime ( (byte) 10),
		e_OperationEventStopPrime ( (byte) 11),
		e_OperationEventPause ( (byte) 12),
		e_OperationEventAlarmPause ( (byte) 13),
		e_OperationEventForceTaperDown ( (byte) 14),
		e_OperationEventUpdateTreatmentParameters ( (byte) 15),
		e_OperationEventEndOfTreatment ( (byte) 16),
		e_OperationEventEndOfTreatment_MoveToKvo ( (byte) 17),
		e_OperationEventDebugStop ( (byte) 18),
		e_OperationEventDebugContinuesFlow ( (byte) 19),
		e_OperationEventBoot ( (byte) 20),
		e_OperationEventForceShutDown ( (byte) 21),
		e_OperationEventStartLightSensorCalibration ( (byte) 22),
		e_OperationEventStopLightSensorCalibration ( (byte) 23),
		e_OperationEventStartPush ( (byte) 24),
		e_OperationEventStopPush ( (byte) 25),
		e_OperationEventRebootAndUpdateProgram ( (byte) 26),
		e_OperationEventRemoveSecondaryLine ( (byte) 27),
		e_OperationEventRunLastTreatment ( (byte) 28),
		e_OperationEventStartPrimaryDelayedTreatment ( (byte) 29),
		e_OperationEventStartSecondaryDelayedTreatment ( (byte) 30),
		e_OperationEventClampTest ( (byte) 31),
		e_OperationEventSecondaryBolus ( (byte) 32),
		e_OperationEvents ( (byte) 33);
		
		byte event;
		
		EOperationEvent(byte b) {
			this.event=b;
		}

	}
	
	//private enum 
	
	
	
	private byte nextSequence;

	//Should probably make this size dependent on the version somehow.
	private byte[] batchBytes;
	
	private String userHome=System.getProperty("user.home");
	
	private short startOfMessage=0xBB;
	
	private InstanceHolder<Numeric> flowRateHolder, vtbiRemainingHolder, vtbiSoFarHolder;
	private DeviceClock defaultClock;	//Will map to DDS time via default implementation
	
	private static final Logger log = LoggerFactory.getLogger(SapphirePump.class);
	
	public SapphirePump(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, boolean stepping) {
		this(subscriber, publisher, eventLoop,1, stepping);
		// TODO Auto-generated constructor stub
	}

	public SapphirePump(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, int countSerialPorts, boolean stepping) {
		super(subscriber, publisher, eventLoop, countSerialPorts);
		deviceIdentity.manufacturer="QCore";
		if(stepping) {
			deviceIdentity.model="Sapphire (stepping)";
		} else {
			deviceIdentity.model="Sapphire";
		}
		deviceIdentity.operating_system="QCore/OS";
		AbstractSimulatedDevice.randomUDI(deviceIdentity);
		writeDeviceIdentity();
		defaultClock=new QCoreClock();
		this.stepping=stepping;
		
		Connection c=SQLLogging.getConnection();
		try {
			loggingStatement=c.prepareStatement("INSERT INTO qcoreinfo(udi,type,value,timing) values (?,?,?,?)");
			loggingStatement.setString(1, deviceIdentity.unique_device_identifier);
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		/**
		 * Following block of code is for receiving objectives for the flow rate
		 */
		addObjectiveListeners();
//		System.err.println("****** Checkpoint 1 *********");
//		ice.FlowRateObjectiveTypeSupport.register_type(getParticipant(), ice.FlowRateObjectiveTypeSupport.get_type_name());
//		flowRateTopic = TopicUtil.findOrCreateTopic(getParticipant(), ice.FlowRateObjectiveTopic.VALUE, ice.FlowRateObjectiveTypeSupport.class);
//		flowRateReader = (ice.FlowRateObjectiveDataReader) subscriber.create_datareader_with_profile(flowRateTopic,
//        		QosProfiles.ice_library, QosProfiles.state,  null, StatusKind.STATUS_MASK_NONE);
//		StringSeq params = new StringSeq();
//		System.err.println("****** Checkpoint 2 *********");
//		params.add("'" + deviceIdentity.unique_device_identifier + "'");
//		flowRateQueryCondition = flowRateReader.create_querycondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
//        		ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);
//		log.info("Created flowRateQueryCondition using "+deviceIdentity.unique_device_identifier);
//		System.err.println("****** Checkpoint 3 *********");	
//		eventLoop.addHandler(flowRateQueryCondition, new ConditionHandler() {
//			
//            private ice.FlowRateObjectiveSeq data_seq = new ice.FlowRateObjectiveSeq();
//            private SampleInfoSeq info_seq = new SampleInfoSeq();
//
//            @Override
//            public void conditionChanged(Condition condition) {
//            	System.err.println("****** Checkpoint 4 *********");
//                for (;;) {
//                    try {
//                        flowRateReader.read_w_condition(data_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
//                                (ReadCondition) condition);
//                        for (int i = 0; i < info_seq.size(); i++) {
//                            SampleInfo si = (SampleInfo) info_seq.get(i);
//                            ice.FlowRateObjective data = (ice.FlowRateObjective) data_seq.get(i);
//                            if (si.valid_data) {
//                            	try { 
//                            		if(loggingStatement!=null) {
//                            			loggingStatement.setInt(2, 1);	//1 for a "request"
//                            			loggingStatement.setFloat(3, data.newFlowRate);
//                            			loggingStatement.setLong(4,System.currentTimeMillis());
//                            			loggingStatement.execute();
//                            		}
//                            		System.err.println("QCore Got a flow rate request of "+data.newFlowRate);
//                            		setSpeed(data.newFlowRate);
//                            	} catch (IOException ioe) {
//                            		log.error("Failed to set pump speed", ioe);
//                            		ioe.printStackTrace();
//                            	} catch (SQLException sqle) {
//					log.error("Failed to log received speed request to database", sqle);
//				}
//                            }
//                        }
//                    } catch (RETCODE_NO_DATA noData) {
//                        break;
//                    } finally {
//                        flowRateReader.return_loan(data_seq, info_seq);
//                    }
//                }
//            }
//        });
        	
    		/**
    		 * Following block of code is for receiving objectives for the VTBI
    		 */
        	/*
    		ice.VTBIObjectiveTypeSupport.register_type(getParticipant(), ice.VTBIObjectiveTypeSupport.get_type_name());
    		vtbiTopic = TopicUtil.findOrCreateTopic(getParticipant(), ice.VTBIObjectiveTopic.VALUE, ice.VTBIObjectiveTypeSupport.class);
    		vtbiReader = (ice.VTBIObjectiveDataReader) subscriber.create_datareader_with_profile(vtbiTopic,
            		QosProfiles.ice_library, QosProfiles.state,  null, StatusKind.STATUS_MASK_NONE);
    		StringSeq vtbiParams = new StringSeq();
            params.add("'" + deviceIdentity.unique_device_identifier + "'");
            vtbiQueryCondition = vtbiReader.create_querycondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
            		ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ALIVE_INSTANCE_STATE, "unique_device_identifier = %0", params);
            eventLoop.addHandler(vtbiQueryCondition, new ConditionHandler() {
                private ice.VTBIObjectiveSeq data_seq = new ice.VTBIObjectiveSeq();
                private SampleInfoSeq info_seq = new SampleInfoSeq();

                @Override
                public void conditionChanged(Condition condition) {

                    for (;;) {
                        try {
                            vtbiReader.read_w_condition(data_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                                    (ReadCondition) condition);
                            for (int i = 0; i < info_seq.size(); i++) {
                                SampleInfo si = (SampleInfo) info_seq.get(i);
                                ice.VTBIObjective data = (ice.VTBIObjective) data_seq.get(i);
                                if (si.valid_data) {
                                	log.warn("QCore currently ignores VTBI requests.  The requested values was "+data.newVTBI);
//                                	try { 
//                                		//writeVTBIToEeprom(data.newVTBI);
//                                		//writeVTBIToEeprom();
//                                	} catch (IOException ioe) {
//                                		log.error("Failed to set pump VTBI", ioe);
//                                		ioe.printStackTrace();
//                                	}
                                }
                            }
                        } catch (RETCODE_NO_DATA noData) {
                            break;
                        } finally {
                            vtbiReader.return_loan(data_seq, info_seq);
                        }
                    }
                }
            });
            */


		
		
	}

	@Override
	protected void doInitCommands(int idx) throws IOException {
		//In here, we'll already have is and os available.  So we can send the init commands, and set the
		//keepGoing flag to tell the code in the process loop that it can do its thing.
		//Because the pump expects the "this is pc" command to be sent "in intervals no greater than 30 seconds"
		//we set up a thread to do it every 10 seconds.
		//Use a null check to only start one until we work our why the reportConnected
		//doesn't work and the watchdog keeps going.
		if(thisIsPCThread==null) {
			log.info("doInitCommands creating new thread");
			thisIsPCThread=new Thread(
					new Runnable() {
						public void run() {
							try {
								ConnectionState initial=stateMachine.getState();
								if(initial!=null) {
									log.info("SapphirePump initial state is "+initial.value());
								} else {
									log.info("SapphirePump has no initial state");
								}
								while(true) {
									log.info("thisIsPCThread starting");
									synchronized (os) {
										sendAndReceiveHeartbeat();
									}
									log.info("thisIsPCThread about to sleep");
									Thread.sleep(15000);
									log.info("thisIsPCThread ending");
								}
							} catch (IOException ioe) {
								keepGoing=false;
							} catch (InterruptedException e) {
								keepGoing=false;
								if(pleaseStop) {
									return;	//Quit running...
								}
							}
						}
					},"QCoreKeepAlive");
			thisIsPCThread.start();
			log.info("New thisIsPCThread started");
		}
		if(steppingThread==null && stepping) {
			log.info("creating a thread to step the speed");
			steppingThread=new Thread(
					new Runnable() {
						public void run() {
							try {
								while(true) {
									if(currentFlowRate==-1) {
										log.info("Flow rate stepping thread does not know flow rate yet");
									} else {
										synchronized (os) {
											stepFlowRate();
										}
									}
									Thread.sleep(30000);
								}
							} catch (InterruptedException e) {
								keepGoing=false;
								if(pleaseStop) {
									//Unlock the pump...
									try {
										unlockPumpByADP();
									} catch (IOException ioe) {
										log.error("Error during pump unlock", ioe);
									}
									return;	//Quit running...
								}
							} catch (Exception e) {
								log.error("Exception in pump rate stepping code",e);
							}
						}
					},"QCoreSteppingThread");
			steppingThread.start();
		}
	}
	
	@Override
	public SerialProvider getSerialProvider(int idx) {
		SerialProvider serialProvider = super.getSerialProvider(idx);
        serialProvider.setDefaultSerialSettings(9600, DataBits.Eight, Parity.None, StopBits.One);
        return serialProvider;
	}

	@Override
	protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
		//Store inputStream and outputStream in instance variables to they can be used in doInitCommands
		this.is=new BufferedInputStream(inputStream);
		this.os=outputStream;
		if(os==null) {
			log.info("process set os to null");
		} else {
			log.info("process set os to not null");
		}
		while(!keepGoing) {
			//Initial connection has not yet occurred.
			try {
				//System.out.println("process loop needs to sleep for keepGoing");
				Thread.sleep(10);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		log.info("process loop got out of !keepGoing");
		while(keepGoing) {
			//Initial connection has occurred.
			readBatchBytes();
			//TODO: Do we need every single update - at 100ms intervals it could get quite CPU intensive?  Could sleep and so miss some.
		}
	}

	@Override
	protected long getMaximumQuietTime(int idx) {
		// TODO Auto-generated method stub
		return 15000L;
	}

	@Override
	protected String iconResourceName() {
		return "SapphireQ.png";
	}
	
	@Override
	public void shutdown() {
		// TODO Auto-generated method stub
		pleaseStop=true;
		thisIsPCThread.interrupt();
		steppingThread.interrupt();
		super.shutdown();
	}

	/*
	 * So, the things it seems we really NEED to do are...
	 * 
	 * Send a message to the LCM (NOT to the pump) to enable comms e_SendCommand=e_EnablePumpPcCommunication
	 * that seems to have some byte attached
	 * 0, 6, dc, 0, 2
	 * 
	 * Then another one, 1 second later, with bytes
	 * 0, 6, 13, 0, 4....
	 * 
	 * Then another one (approx two seconds later?) with
	 * 0, 6, dc, 0, 2
	 * again.
	 * 
	 * then one second later another with
	 * 0, 6, 13, 0, 4
	 * 
	 * then eSendCommand=e_LockPumpByADP
	 * 
	 * writeDataToEprom, byte 22 with value 0	(continuous flow)
	 * 
	 * writeDataToEprom, byte 128, VTBI 0 prob * 100
	 * 
	 * -----------
	 * writeDataToEprom, byte 144,145 for the flow rate MSB or LSB? *10
	 * 
	 * e_SendCommand=e_UpdateCacheParameters
	 * -----------
	 * 
	 * e_SetEvent=e_OperationEventRunOrContinue
	 * 
	 * writeDataToEprom, byte 22 with value 0	(continuous flow)
	 * 
	 */
	private void stepFlowRate() {
		
		try {
			enableComms1();
			Thread.sleep(1000);
			enableComms2();
			Thread.sleep(1000);
			enableComms1();
			Thread.sleep(1000);
			enableComms2();
			Thread.sleep(2000);
			lockPumpByADP();
			Thread.sleep(1000);
			writeContinuousFlowToEeprom();
			Thread.sleep(300);
			//writeVTBIToEeprom();
			Thread.sleep(300);
			writeFlowRateToEeprom();
			Thread.sleep(1300);
			updateCacheParameters();
			Thread.sleep(7000);
			runOrContinueEvent();
			/*
			 * As the pumps seem to stop running after disconnecting in OpenICE, we will try
			 * and use the e_ReleaseLockPumpByADP command to release the lock and see if that
			 * then allows the pump to continue to run.  This command should probably also be
			 * added to the disconnect code.
			 */
			//unlockPumpByADP();
			
		} catch (IOException ioe) {
			log.error("Failed to step speed",ioe);
		} catch (InterruptedException ie) {
			log.error("Interrupted during sleep", ie);
		}
		
	}
	
	private void setFlowRateWithAllPreReqs(float f) {
		try {
			enableComms1();
			log.info("setFlowRateWithAllPreReqs enableComms1");
			Thread.sleep(1000);
			enableComms2();
			log.info("setFlowRateWithAllPreReqs enableComms2");
			Thread.sleep(1000);
			enableComms1();
			log.info("setFlowRateWithAllPreReqs enableComms1");
			Thread.sleep(1000);
			enableComms2();
			log.info("setFlowRateWithAllPreReqs enableComms2");
			Thread.sleep(2000);
			lockPumpByADP();
			log.info("setFlowRateWithAllPreReqs lockPumpByADP");
			Thread.sleep(1000);
			writeContinuousFlowToEeprom();
			log.info("setFlowRateWithAllPreReqs writeContinuousFlowToEeprom");
			Thread.sleep(300);
			//writeVTBIToEeprom();
			Thread.sleep(300);
			writeFlowRateToEeprom(f);
			log.info("setFlowRateWithAllPreReqs writeFlowRateToEeprom");
			Thread.sleep(1300);
			updateCacheParameters();
			log.info("setFlowRateWithAllPreReqs updateCacheParameters");
			Thread.sleep(7000);
			runOrContinueEvent();
			log.info("setFlowRateWithAllPreReqs runOrContinueEvent");
			if(loggingStatement!=null) {
    				loggingStatement.setInt(2, 2);	//1 for a "request"
    				loggingStatement.setFloat(3, f);
    				loggingStatement.setLong(4,System.currentTimeMillis());
    				loggingStatement.execute();
    			}
		} catch (IOException ioe) {
			log.error("Failed to step speed",ioe);
		} catch (SQLException sqle) {
			log.error("Failed to log flow rate change response to database",sqle);
		} catch (InterruptedException ie) {
			log.error("Interrupted during sleep", ie);
		}
	}
	
	/**
	 * This is designed to be called from stepFlowRate, but might not always need to be,
	 * because we don't actually know precisely when it's required.
	 * @throws IOException
	 */
	private void enableComms1() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetLCM;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_SendCommand.command;
		m.header.messageSize=6;		//We need 5 data bytes after the command
		m.header.messageNumber=nextSequence++;
		m.header.messageData=new byte[] {ETtransmissionCommand.e_EnablePumpPcCommunication.command, 0x00, 0x06, (byte)0xdc, 0x00, 0x02 };
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("enableComms1 bytes written");
		}
	}
	
	/**
	 * This is designed to be called from stepFlowRate, but might not always need to be,
	 * because we don't actually know precisely when it's required.
	 * @throws IOException
	 */
	private void enableComms2() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetLCM;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_SendCommand.command;
		m.header.messageSize=6;		//We need 5 data bytes after the command
		m.header.messageNumber=nextSequence++;
		m.header.messageData=new byte[] {ETtransmissionCommand.e_EnablePumpPcCommunication.command, 0x00, 0x06, (byte)0x13, 0x00, 0x04 };
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("enableComms2 bytes written");

		}
	}
	
	/**
	 * Lock the pump - meaning/need unclear at the moment...
	 * @throws IOException
	 */
	private void lockPumpByADP() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_SendCommand.command;
		m.header.messageSize=1;		//We need 1 data byte for the command itself - no additional data.
		m.header.messageNumber=nextSequence++;
		m.header.messageData=new byte[] {ETtransmissionCommand.e_LockPumpByADP.command};
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("lockPumpByADP bytes written");
		}
	}
	
	/**
	 * Unlock the pump - meaning/need unclear at the moment...
	 * @throws IOException
	 */
	private void unlockPumpByADP() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_SendCommand.command;
		m.header.messageSize=1;		//We need 1 data byte for the command itself - no additional data.
		m.header.messageNumber=nextSequence++;
		m.header.messageData=new byte[] {ETtransmissionCommand.e_ReleaseLockPumpByADP.command};
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("unlockPumpByADP bytes written");
		}
	}
	
	/**
	 * Write "continuous flow" to the eeprom
	 * @throws IOException
	 */
	private void writeContinuousFlowToEeprom() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_WriteDataToPumpEeprom.command;
		m.header.messageSize=5;		//We need 5 data bytes
		m.header.messageNumber=nextSequence++;
		//22 HERE IS NOT!!!! A HEX VALUE - IT'S DECIMAL!!! - 00 22 is two byte target address of the eeprom.
				//0, 1 is two byte value of the number of bytes to write
				//0 is the byte value to write.
		m.header.messageData=new byte[] { 00, 22, 0, 1, 0 };
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("writeContinuousFlowToEeprom bytes written");
		}
	}
	
	/**
	 * This needs to take a parameter for the actual VTBI - 0x20 0x4e are the hard coded vals at the moment.
	 * 
	 * @throws IOException
	 */
	private void writeVTBIToEeprom() throws IOException {
		//short s=(short)newVTBI;
		
		
		
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_WriteDataToPumpEeprom.command;
		m.header.messageSize=6;		//We need 6 data bytes
		m.header.messageNumber=nextSequence++;
		//00, 128 is two byte target address of the eeprom
		//0, 2 is the two byte value of the number of bytes to write
		//0xa0 and 0x0f are the two bytes to write.
		m.header.messageData=new byte[] { 00, (byte)128, 0, 2, (byte)0x20, 0x4e };
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("writeVTBIToEeprom bytes written");
		}
	}
	
	/**
	 * Increase the flow rate by 10ml/h - does not take a value, as used from the stepping code.
	 * 
	 * @throws IOException
	 */
	private void writeFlowRateToEeprom() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_WriteDataToPumpEeprom.command;
		m.header.messageSize=6;		//We need 6 data bytes
		m.header.messageNumber=nextSequence++;
		
		//Increment of 100 means actual step of 10 in the flow rate.
		int flowRateToSet=getFlowRate()+100;
		if(flowRateToSet>1100) {
			flowRateToSet=1;
		}
		
		ByteBuffer bb=ByteBuffer.allocate(4);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		bb.putInt(flowRateToSet);
		byte[] newFlowBytes=bb.array();
		
		//00, 128 is two byte target address of the eeprom
		//0, 2 is the two byte value of the number of bytes to write
		//0xa0 and 0x0f are the two bytes to write.
		m.header.messageData=new byte[] { 00, (byte)144, 0, 2, newFlowBytes[0], newFlowBytes[1] };
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("writeFlowRateToEeprom bytes written");
		}
	}
	
	private void writeFlowRateToEeprom(float newValue) throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_WriteDataToPumpEeprom.command;
		m.header.messageSize=6;		//We need 6 data bytes
		m.header.messageNumber=nextSequence++;
		
		
		
		ByteBuffer bb=ByteBuffer.allocate(4);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		//If we treat it as a float here, we get the wrong byte values.
		//But we need to allow float on the way in to allow a decimal point line 10.5
		//as the speed - which is required to be 105 as the written value.  So we multiply
		//by 10 first and cast that to a short
		bb.putShort( (short)(newValue*10) );
		byte[] newFlowBytes=bb.array();
		
		//00, 128 is two byte target address of the eeprom
		//0, 2 is the two byte value of the number of bytes to write
		//0xa0 and 0x0f are the two bytes to write.
		m.header.messageData=new byte[] { 00, (byte)144, 0, 2, newFlowBytes[0], newFlowBytes[1] };
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("writeFlowRateToEeprom bytes written");
		}
	}
	
	/**
	 * update the cache - meaning/need unclear at the moment...
	 * @throws IOException
	 */
	private void updateCacheParameters() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_SendCommand.command;
		m.header.messageSize=1;		//We need 1 data byte for the command itself - no additional data.
		m.header.messageNumber=nextSequence++;
		m.header.messageData=new byte[] {ETtransmissionCommand.e_UpdateCacheParameters.command};
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("updateCacheParameters bytes written");
		}
	}
	
	/**
	 * run or continue - meaning/need unclear at the moment...
	 * @throws IOException
	 */
	private void runOrContinueEvent() throws IOException {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_SetEvent.command;
		m.header.messageSize=1;		//We need 1 data byte for the command itself - no additional data.
		m.header.messageNumber=nextSequence++;
		m.header.messageData=new byte[] {EOperationEvent.e_OperationEventRunOrContinue.event};
		synchronized (os) {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			os.write(bytes);
			os.flush();
			log.info("updateCacheParameters bytes written");
		}
	}
	
	private void sendAndReceiveHeartbeat() throws IOException {
		log.info("sendAndReceiveHeartbeat called");
		byte[] nextHearbeat=getNextHeartbeatMessage();
		if(nextHearbeat==null) {
			throw new IOException("next heartbeat message was null");
		}
		if(os==null) {
			log.warn("OutputStream is null in sendAndReceiveHeartbeat");
			return;
		}
		os.write(nextHearbeat);
		/*
		 * We found lots of instances where we couldn't connect cleanly.  Looking
		 * at some dumps we captured from the QCore PC software, we could see that
		 * they sometimes sent the heartbeat message twice right at the start of
		 * the connection. So we do too, to see how if that makes it better.
		 */
		if(firstHeartbeat) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			os.write(nextHearbeat);
			log.info("sendAndReceiveHeartbeat doing extra initial heartbeat");
		}
		os.flush();
		log.info("sendAndReceiveHeartbeat wrote and flushed bytes");
		//According to the docs, we need to receive one of the replies to deem that we are connected.
		//If firstheartbeat is true, we didn't do that yet.  After receiving one heartbeat reply here,
		//we will set keepgoing to true and the "process(...) loop will take over reading the status
		//block messages.
		log.info("in sendAndReceiveHearbeat firstHeartbeat is {}",firstHeartbeat);
		if(firstHeartbeat) {
			readBatchBytes();
			firstHeartbeat=false;
			log.info("calling reportConnected from current state "+stateMachine.getState().value());
			reportConnected("Connected to QCore with serial number "+getPumpSerialNumber());
			keepGoing=true;
			log.info("sendAndReceiveHeartbeat set keepGoing to true");
		}
	}
	
	private void readBatchBytes() throws IOException {
		//log.info("readBatchBytes called");
		int stx=-1;
		int i=0;
		while(stx != startOfMessage) {
		  if(firstHeartbeat) {
			log.info("readBatchBytes about to try and read for start of message");
			System.err.println("readBatchBytes about to try and read for start of message");
		  }
		  stx=is.read();	//This should be OxBB
		  i++;
		  if(firstHeartbeat) {
			log.info("readBatchBytes did the read and got {} ",stx);
			System.err.println("readBatchBytes did the read and got "+stx);
		  }
		}
//		log.debug("readBatchBytes got startOfMessage after {} bytes",i);
//		if(stx!=startOfMessage) {
//			throw new IOException("readBatchBytes didn't start with 0xBB");
//		}
		batchBytes=readMessageBytes();
		handleDataUpdates();
		int checksum=is.read();	//Drain the checksum byte from the input stream.
		//TODO: See if the checksum matches - after all, that's what it's for (although we can't be sure of the algorithm).
		
		
		
		
		//synchronized (batchBytes) {
//			int total=0;
//			while(total!=batchBytes.length) {
//				total+=is.read(batchBytes,total,batchBytes.length-total);
//			}
//			log.info("total bytes read is "+batchBytes.length);
//			if ( (batchesReceived % 10) == 0 ) {
//				for(int i=0;i<batchBytes.length;i++)  {
//                                  System.err.print(batchBytes[i]+" ");
//				  if( (i%20) == 0) {
//					  System.err.println();
//				  }
//				}
//				System.err.println();
//			}
//			handleDataUpdates();
		//}
	}
	
	/**
	 * This reads a structure of CTransmissionMessage - in fullness of time, we need to handle more message types,
	 * but for now we concentrate on a batch message
	 * @return an array of bytes from the batch message.
	 */
	private byte[] readMessageBytes() throws IOException {
		//log.info("readMessageBytes called");
		//ByteArrayOutputStream baos=new ByteArrayOutputStream();
		int target=is.read();	//Target should be in range 100-103
//		if(target<100 || target > 103) {
//			throw new IOException("Unexpected target value "+target+" in readMessageBytes");
//		}
		//log.info("rmb target is "+target);
		int flags=is.read();	//flags should be 0x00
//		if(flags!=0) {
//			throw new IOException("Unexpected flags value "+flags+" in readMessageBytes");
//		}
		//log.info("rmb flags is "+flags);
		int sender=is.read();	//sender should be in range 100-103
//		if(sender<100 || sender > 103) {
//			throw new IOException("Unexpected sender value "+sender+" in readMessageBytes");
//		}
		//log.info("rmb sender is "+sender);
		int messageType=is.read();		//we expect this to be 1, ETransmissionType of e_SendBatch
//		if(messageType!=1) {
//			throw new IOException("Unexpected messageType "+messageType+" in readMessageBytes");
//		}
		//log.info("rmb messageType is "+messageType);
		byte[] msgSizeBytes=new byte[2];
		int bytesReadForTotalSize=0;
		while(bytesReadForTotalSize!=2) {
			bytesReadForTotalSize+=is.read(msgSizeBytes,bytesReadForTotalSize,2-bytesReadForTotalSize);
		}
		//This is part of the header, so shoudl be BIG endian.
		short sizeOfMsg=toBigEndianBBShort(msgSizeBytes);
		//log.info("rmb sizeOfMsg is "+sizeOfMsg);
		int rcvSequenceNumber=is.read();
//		if(rcvSequenceNumber<0) {
//			throw new IOException("Unexpected sequence number "+rcvSequenceNumber+" in readMessageBytes");
//		}
		log.debug("rmb seq num is {}",rcvSequenceNumber);
		byte[] msgBytes=new byte[sizeOfMsg];
		int total=0;
		while(total!=msgBytes.length) {
			total+=is.read(msgBytes,total,msgBytes.length-total);
		}
		//log.info("rmb read "+total);
		
		
		return msgBytes;
	}
	
	

	private void handleDataUpdates() {
		//At this point batchBytes contains the bytes defined in the structure/class
		//e_SendBatch [CBatchTransmission]
		//The pump pushes data every 100ms, so checking for sequence numbers that divide by 10
		//should give us approximately 1 reading per second.
		if( ( (batchesReceived++) % 10) == 0) {
			long pumpSoftwareVersion=getPumpSoftwareVersion();
			long pumpSerialNumber=getPumpSerialNumber();
			int dropSize=getDropSize();
			int flowRate=getFlowRate();
			float vtbiRemaining=((float)getVTBIRemaining())/100;
			float vtbiSoFar=((float)getVTBISoFar())/100;
			this.currentFlowRate=flowRate;	//Store it in the class instance variable so the flow rate stepper can track it.
			log.info("Pump serial number {} running software version {} has drop size {} and flow rate {} in batch {}\n", pumpSerialNumber, pumpSoftwareVersion, dropSize, flowRate, batchesReceived);
			try {
				dumpBytesToFile(batchesReceived);
			} catch(IOException ioe) {
				log.warn("Failed to dump qcore bytes", ioe);
			}
			//Unit = rosetta.MDC_DIM_MILLI_L_PER_HR
			//metric-id = rosetta.MDC_FLOW_FLUID_PUMP
			float newValue=((float)flowRate)/10;
			Reading r=defaultClock.instant();
			numericSample(flowRateHolder, newValue, rosetta.MDC_FLOW_FLUID_PUMP.VALUE, "", rosetta.MDC_FLOW_FLUID_PUMP.VALUE, new DeviceClock.CombinedReading(r, r));
			numericSample(vtbiRemainingHolder, vtbiRemaining, "PUMP_VTBI_REMAINING", "", "PUMP_VTBI_REMAINING", new DeviceClock.CombinedReading(r, r));
			numericSample(vtbiSoFarHolder, vtbiSoFar, "PUMP_VTBI_SO_FAR", "", "PUMP_VTBI_SO_FAR", new DeviceClock.CombinedReading(r, r));
			
			String serialno=Long.toString(getPumpSerialNumber());
			writeTechnicalAlert("serialNumber", serialno);
			writeTechnicalAlert("UDI", deviceIdentity.unique_device_identifier);
			writeTechnicalAlert("Model", "QCore");
		}
	}
	
	private void dumpBytesToFile(int batchNumber) throws IOException {
		//Path path=Paths.get(userHome,"batch"+batchNumber);
		//Files.write(path, batchBytes);
	}
	
	private long getPumpSerialNumber() {
		return Integer.toUnsignedLong(toLittleEndianBB(batchBytes[38],batchBytes[39],batchBytes[40],batchBytes[41]));
	}
	
	private long getPumpSoftwareVersion() {
		return Integer.toUnsignedLong(toLittleEndianBB(batchBytes[34],batchBytes[35],batchBytes[36],batchBytes[37]));
	}
	
	private int getDropSize() {
		return Short.toUnsignedInt(toLittleEndianBBShort(batchBytes[45],batchBytes[46]));
	}
	
	private int getFlowRate() {
		return Short.toUnsignedInt(toLittleEndianBBShort(batchBytes[47],batchBytes[48]));
	}
	
	private int getVTBIRemaining() {
		return (int)(Integer.toUnsignedLong(toLittleEndianBB(batchBytes[163],batchBytes[164],batchBytes[165],batchBytes[166])));
	}
	
	private int getVTBISoFar() {
		return (int)(Integer.toUnsignedLong(toLittleEndianBB(batchBytes[167],batchBytes[168],batchBytes[169],batchBytes[170])));
	}
	
	/**
	 * Set the current flow rate of the pump.  There is no validation
	 * @param f
	 * @throws IOException if we could not write the speed to the pump
	 */
	public void setSpeed(float f) throws IOException {
		setFlowRateWithAllPreReqs(f);
	}
	
	/**
	 * The QCore data structure uses a mixture of what it describes as bytes, 16 bit ints and 32 bit ints.
	 * Of course due to the unsigned keyword missing in Java, we can't reliably represent a byte >127 etc.,
	 * so we just make ints out of them all.  We assume that the caller of this method knows that size they
	 * want and masks the int returned here accordingly.
	 * @param bytes
	 * @return
	 */
//	private int toBigEndian(byte... bytes) {
//		int ret=0;
//		int j=bytes.length;
//		for(int i=0;i<j;i++) {
//			ret=ret | (bytes[i] << ( 8 * (j-i-1) ) );
//		}
//		return ret;
//	}
	
	private static int toBigEndianBB(byte... bytes) {
		ByteBuffer bb=ByteBuffer.allocate(bytes.length);
		bb.order(ByteOrder.BIG_ENDIAN);
		return bb.put(bytes).getInt(0);
	}
	
	private static int toLittleEndianBB(byte... bytes) {
		ByteBuffer bb=ByteBuffer.allocate(bytes.length);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		return bb.put(bytes).getInt(0);
	}
	
	private static long toLittleEndianBBLong(byte... bytes) {
		ByteBuffer bb=ByteBuffer.allocate(bytes.length);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		return bb.put(bytes).getLong(0);
	}
	
	private static short toBigEndianBBShort(byte... bytes) {
		ByteBuffer bb=ByteBuffer.allocate(bytes.length);
		bb.order(ByteOrder.BIG_ENDIAN);
		return bb.put(bytes).getShort(0);
	}
	
	private static short toLittleEndianBBShort(byte... bytes) {
		ByteBuffer bb=ByteBuffer.allocate(bytes.length);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		return bb.put(bytes).getShort(0);
	}

	
	
//	private short toBigEndianShort(byte... bytes) {
//		log.info("toBigEndianShort byte 0 is "+bytes[0]+" and byte 1 is "+bytes[1]);
//		short ret=0;
//		ret |= bytes[0]<<8;
//		ret |= (bytes[1] & 0xff);
//		return ret;
//	}
	
	private byte[] toBigEndian(short input) {
		byte[] ret=new byte[2];
		ret[0]=(byte)(input & 0xff00);
		ret[1]=(byte)(input & 0x00ff);
		return ret;
	}
	
	private byte[] toLittleEndian(short input) {
		byte[] ret=new byte[2];
		ret[0]=(byte)(input & 0x00ff);
		ret[1]=(byte)(input & 0xff00);
		return ret;
	}
	
	class Message {
		byte startOfMessage=(byte)0xBB;
		CTransmissionMessageII header;
		byte checksum;
		
		void addBytesToStream(ByteArrayOutputStream baos) throws IOException {
			baos.write(startOfMessage);
			header.addBytesToStream(baos);
			setChecksum(baos);
			baos.write(checksum);
		}
		
		void setChecksum(ByteArrayOutputStream baos) {
			checksum=0;
			byte[] allButHeader=baos.toByteArray();
			for(int i=1;i<allButHeader.length;i++) {
				checksum+=allButHeader[i];
			}
		}
	}
	
	class CTransmissionMessageII {
		EMessageTarget target;
		final byte flags=0x00;	//Make final to avoid accidental overwrite.
		EMessageTarget sender;
		byte messageType;
		short messageSize;	//BIG ENDIAN - size of message data
		byte messageNumber;	//sequence number - auto increment somehow...
		byte[] messageData;
		
		void addBytesToStream(ByteArrayOutputStream baos) throws IOException {
			baos.write(target.target);
			baos.write(flags);
			baos.write(sender.target);
			baos.write(messageType);
			baos.write(toBigEndian(messageSize));
			baos.write(messageNumber);
			baos.write(messageData);
		}
	}
	
	/**
	 * A class to handle the bytes that set the needed flow rate
	 *
	 */
	class SendNeededFlowParameters {
		
		short neededFlow;
		
		SendNeededFlowParameters(short neededFlow) {
			this.neededFlow=neededFlow;
		}
		
		byte[] getBytes(){
			byte[] bytes=new byte[6];
			bytes[0]=EPcToPumpFlowCommand.e_PcToPumpFlowCommandSetNeededFlow.command;
			byte[] flowBytes=toLittleEndian(neededFlow);
			bytes[1]=flowBytes[0];
			bytes[2]=flowBytes[1];
			bytes[3]=0x00;
			bytes[4]=bytes[5]=-0x7f;
			return bytes;
		}
		
	}
	
	private byte[] getNextHeartbeatMessage() {
		ByteArrayOutputStream baos=new ByteArrayOutputStream();
		Message m=new Message();
		m.header=new CTransmissionMessageII();
		m.header.target=EMessageTarget.e_MessageTargetPump;
		m.header.sender=EMessageTarget.e_MessageTargetPC;
		m.header.messageType=ETransmissionPackage.e_SendCommand.command;
		m.header.messageSize=1;		//ThisIsPC only has one byte
		m.header.messageNumber=nextSequence++;
		m.header.messageData=new byte[] {ETtransmissionCommand.e_ThisIsPc.command};
		
		try {
			m.addBytesToStream(baos);
			byte bytes[]=baos.toByteArray();
			String hexString=Hex.encodeHexString(bytes);
			log.info("Hex bytes are "+hexString);
			return bytes;
		} catch (IOException ioe) {
			log.error("Failed to add bytes to byte array output stream", ioe);
		}
		return null;
		
		
	}
	
	//Do we not have a better built in ticking clock somewhere?
	//ALSO - does the device have a time field anywhere?
	private class QCoreClock implements DeviceClock {

		@Override
		public Reading instant() {
			return new Reading() {
				
				@Override
				public Reading refineResolutionForFrequency(int hertz, int size) {
					// TODO Auto-generated method stub
					return null;
				}
				
				@Override
				public boolean hasDeviceTime() {
					// TODO Auto-generated method stub
					return true;
				}
				
				@Override
				public Instant getTime() {
					// TODO Auto-generated method stub
					Instant i=Instant.ofEpochMilli(System.currentTimeMillis());
					//System.out.println("MSeriesScaleClock returning "+i.toString());
					return i;
				}
				
				@Override
				public Instant getDeviceTime() {
					Instant i=Instant.ofEpochMilli(System.currentTimeMillis());
					return i;
				}
			};
		}
		
	}
	
	static void parse(String inputName, String outputName) throws IOException {
		File inFile=new File(inputName);
		Path inPath=inFile.toPath();
		
		File outFile=new File(outputName);
		
		ArrayList<String> timeVals=new ArrayList<>();
		ArrayList<Integer> intVals=new ArrayList<>();
		ArrayList<String> outputLines=new ArrayList<>();
		/*
		Keep the whole file in a list right from the start, to make it easy to scroll up and down.
		We aren't going to try and be too clever just yet - we'll assume lines are CSV files, with
		a header on the first line.  So we start on line one.
		*/
		/*Some sample line
		 * 
		 * Time [s],Value,Parity Error,Framing Error
		 * 0.00063,'0' (0x00),,
		 * 0.001878,'0' (0x00),,
		 * 0.003127,'0' (0x00),, 
		 * 0.004271,'0' (0x00),,
		 */
		
		List<String> allLines=Files.readAllLines(inPath);
		int i=1;
		for(;i<allLines.size();i++) {
			String line=allLines.get(i);
			String[] fields=line.split(",");
			String hexField;
			switch (fields.length) {
			case 2:
				hexField=fields[1];
				break;
			case 3:
				hexField=fields[2];
				break;
			default:
				throw new RuntimeException("could not parse line "+line+" at index "+i);
			}
			if(fields.length==2) {
				hexField=fields[1];
			}
			if(fields.length==3) {
				hexField=fields[2];
			}
			try {
				String hex=hexField.substring(hexField.lastIndexOf('(')+3, hexField.lastIndexOf(')'));
				int hexVal=Integer.parseInt(hex,16);
				timeVals.add(fields[0]);
				intVals.add(hexVal);
				//outputLines.add("Line "+i+" has value "+hexVal);
			} catch (StringIndexOutOfBoundsException bounds) {
				System.err.println("Invalid contents for line "+line+" at index "+i);
				System.err.println(bounds.getMessage());
			}
		}
		System.err.println("Read "+i+" lines from the input file");
		
		//We now have an array of the integer values from the file - which should be all
		//we need to know about the data to map it back to stuff.
		boolean startFound=false;
		i=0;
		int currentVal=-1;
		while(i<intVals.size()) {
			while(i<intVals.size() && !startFound) {	//This second check of .size() here will be unnecessary once we consume all bytes 
				currentVal=intVals.get(i++);
				if(currentVal==0xBB) {
					startFound=true;
				}
			}
			if(!startFound) {
				break;	//End of input data...
			}
			outputLines.add(timeVals.get(i-1)+"\t"+currentVal+"\tstart of message");
			
			//Target
			int target=intVals.get(i++);
			EMessageTarget[] targets=EMessageTarget.values();
			for(EMessageTarget emt : targets) {
				if( (int)emt.target == target) {
					outputLines.add(timeVals.get(i-1)+"\t"+target+"\ttarget is "+emt);
				}
			}
			
			//Flags
			int flags=intVals.get(i++);
			//outputLines.add(flags+"\tflags (should be zero)");
			outputLines.add(timeVals.get(i-1)+"\t"+0+"\tflags (set be zero)");

			//Sender
			int sender=intVals.get(i++);
			for(EMessageTarget emt : targets) {
				if( (int)emt.target == sender) {
					outputLines.add(timeVals.get(i-1)+"\t"+sender+"\tsender is "+emt);
				}
			}
			
			int messageType=intVals.get(i++);
			ETransmissionPackage[] etps=ETransmissionPackage.values();
			ETransmissionPackage thisETP=null;
//			System.err.println("Searching for ETP "+messageType+" with i "+(i-1));
			for(ETransmissionPackage etp : etps) {
				if( (int)etp.command == messageType) {
					outputLines.add(timeVals.get(i-1)+"\t"+messageType+"\tcommand is "+etp);
					thisETP=etp;	//Save it for later.
				}
			}
			
			if(thisETP==null) {
				throw new RuntimeException("No ETP at or around line "+i);
			}
			
			//Get message size next from two bytes
			int messageSize=(intVals.get(i++) << 8) | intVals.get(i++);
			outputLines.add(timeVals.get(i-1)+"\t"+messageSize+"\tmessage size");
			
			//Get sequence number next from one byte
			int sequenceNumber=intVals.get(i++);
			//outputLines.add(sequenceNumber+"\tsequence number");
			outputLines.add(timeVals.get(i-1)+"\t"+0+"\tsequence number");
			
			//Now handle the rest of the data by switching on the ETP
			
			switch (thisETP) {
			case e_WriteDataToPumpEeprom :
				
				i+=decodeWriteDataToPumpEprom(intVals, timeVals, i, outputLines);
				break;	//end of e_WriteDataToPumpEeprom
			case e_SendCommand :
				i+=decodeSendCommand(intVals, timeVals, i, outputLines);
				int j=0;
				/*
				 * Dump any additional message bytes here.  We've read the command byte in decodeSendCommand,
				 * which could just return 1 and almost be inlined here.  Does it make sense to do this?  Better
				 * to pass in the message size to decodeSendCommand and get it to do the job?  Not quite clear
				 * if any send commands actually have data...
				 */
				while(j<messageSize-1) {
					outputLines.add( String.format("%x - %s byte %d", intVals.get(i+j), thisETP, j ) );
					j++;
				}
				i+=j;
				break;	//end of e_SendCommand
			case e_SetEvent :
				i+=decodeSetEvent(intVals, timeVals, i, outputLines);
				j=0;
				while(j<messageSize-1) {
					outputLines.add( String.format("%x - %s byte %d", intVals.get(i+j), thisETP, j ) );
					j++;
				}
				i+=j;
				break;	//end of e_SetEvent
			case e_SetAlarmStatus:
				i+=decodeSetAlarmStatus(intVals, timeVals, i, outputLines);
				
				break;
			case e_ReadEepromData:
				i+=decodeReadEepromData(intVals, timeVals, i, outputLines);
				
				break;
			default:
				System.err.println("Unhandled ETransmissionPackage "+thisETP);
				break;
			}
			
			int checksum=intVals.get(i++);

			
			
			startFound=false;
			outputLines.add("");
		}
		System.err.println("end of intVals loop,i is "+i);
		System.err.println("outputLines.size() is "+outputLines.size());
		
		for(i=305;i<outputLines.size();i++) {
			System.err.println(outputLines.get(i));
		}
		
		
		
		System.err.println("files.write starts at "+System.currentTimeMillis());
		Files.write(outFile.toPath(), outputLines, StandardOpenOption.CREATE);
		System.err.println("files.write ends at "+System.currentTimeMillis());
	}
	
	private static int decodeReadEepromData(ArrayList<Integer> intVals, ArrayList<String> timeVals, int initial, ArrayList<String> outputLines) {
		int i=initial;
		int startAddress= (intVals.get(i++) << 8) | intVals.get(i++); 
		outputLines.add(timeVals.get(i-1)+"\t"+startAddress+"\tEeprom start address to read from");
		int numOfBytes= (intVals.get(i++) << 8) | intVals.get(i++); 
		outputLines.add(timeVals.get(i-1)+"\t"+numOfBytes+"\tnum of bytes to read (UNUSED!?)");
		return initial-i;
	}
	
	private static int decodeSetAlarmStatus(ArrayList<Integer> intVals, ArrayList<String> timeVals, int initial, ArrayList<String> outputLines) {
		int i=initial;
		int sizeOfAlarmsArray=intVals.get(i++);
		outputLines.add(timeVals.get(i-1)+"\t"+sizeOfAlarmsArray+"\tnumber of alarms to disable/enable");
		int setOrReset=intVals.get(i++);	//Always 0 or 1?
		outputLines.add(timeVals.get(i-1)+"\t"+setOrReset+"\tset or reset...");
		int j=0;
		while(j<sizeOfAlarmsArray) {
			//TODO: We can decode the alarms now using the doc we found...
			outputLines.add( String.format("%d - alarm %d", intVals.get(i+j), j ) );
			j++;
		}
		i+=j;
		
		return initial-i;
	}

	private static int decodeSetEvent(ArrayList<Integer> intVals, ArrayList<String> timeVals, int initial, ArrayList<String> outputLines) {
		int i=initial;
		int eventType=intVals.get(i++);
		EOperationEvent events[]=EOperationEvent.values();
		for(EOperationEvent event : events ) {
			if( (int) event.event == eventType ) {
				outputLines.add(timeVals.get(i-1)+"\t"+eventType+"\t"+event);
			}
		}
		return initial-i;
	}

	private static int decodeSendCommand(ArrayList<Integer> intVals, ArrayList<String> timeVals, int initial, ArrayList<String> outputLines) {
		int i=initial;
		int command=intVals.get(i++);
		ETtransmissionCommand[] etcs=ETtransmissionCommand.values();
		for(ETtransmissionCommand etc : etcs) {
			if( (int)etc.command == command) {		//This just looks wrong...
				switch (etc) {
				case e_ThisIsPc:
					outputLines.add(timeVals.get(i-1)+"\t"+command+"\tThisIsPc - request fast data");
					break;
					
				case e_UpdateCacheParameters:
					outputLines.add(timeVals.get(i-1)+"\t"+command+"\te_UpdateCacheParameters");
					break;
					
				case e_GetAlarmStatus:
					outputLines.add(timeVals.get(i-1)+"\t"+command+"\te_GetAlarmStatus");
					break;
					
				case e_EnablePumpPcCommunication:
					outputLines.add(timeVals.get(i-1)+"\t"+command+"\te_EnablePumpPcCommunication");
					break;
					
				case e_LockPumpByADP:
					outputLines.add(timeVals.get(i-1)+"\t"+command+"\te_LockPumpByADP");
					break;
					
				case e_ReleaseLockPumpByADP:
					outputLines.add(timeVals.get(i-1)+"\t"+command+"\te_ReleaseLockPumpByADP");
					break;

				default:
					System.err.println("Unhandled send command "+command);
				}
			}
		}
		return initial-i;
	}

	/**
	 * Decode a write data to pump command.
	 * @param intVals the array of values
	 * @param initial the initial location in the array to work from
	 * @return the number of entries read from the list.
	 */
	private static int decodeWriteDataToPumpEprom(ArrayList<Integer> intVals, ArrayList<String> timeVals, int initial, ArrayList<String> outputLines) {
		int i=initial;
		int startAddress= (intVals.get(i++) << 8) | intVals.get(i++); 
		outputLines.add(timeVals.get(i-1)+"\t"+startAddress+"\tEeprom start address to write to");
		//System.err.println("Start address is "+startAddress);
		int bytesToWrite= (intVals.get(i++) << 8) | intVals.get(i++);
		//System.err.println("bytesToWrite is "+bytesToWrite);
		outputLines.add(timeVals.get(i-1)+"\t"+bytesToWrite+"\tNumber of bytes");
		int j=0;
		while(j<bytesToWrite) {
			outputLines.add( String.format("%x - byte %d", intVals.get(i+j), j ) );
			j++;
		}
		i+=j;
		return initial-i;	//The number of bytes we processed
		
	}
	
	public static void main(String args[]) {
		try {
			SapphirePump.parse(args[0], args[1]);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	@Override
	public String toString() {
		return getPortIdentifier();
	}

	public int hashCode() {
		return getPortIdentifier().hashCode(); 
	}

	public boolean equals(Object other) {
		if ( ! (other instanceof SapphirePump)) {
			return false;
		}
		SapphirePump sp=(SapphirePump)other;
		return getPortIdentifier().equals(sp.getPortIdentifier());
	}

	private final void addObjectiveListeners() {
		addFlowRateListener();
//		addPauseResumeListener();
		addProgramListener();
	}
	
	private void addFlowRateListener() {
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
		log.info("Created flowRateQueryCondition using "+deviceIdentity.unique_device_identifier);
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
                            		if(loggingStatement!=null) {
                            			loggingStatement.setInt(2, 1);	//1 for a "request"
                            			loggingStatement.setFloat(3, data.newFlowRate);
                            			loggingStatement.setLong(4,System.currentTimeMillis());
                            			loggingStatement.execute();
                            		}
                            		setSpeed(data.newFlowRate);
                            	} catch (IOException ioe) {
                            		log.error("Failed to set pump speed", ioe);
                            		ioe.printStackTrace();
                            	} catch (SQLException sqle) {
					log.error("Failed to log received speed request to database", sqle);
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
        System.err.println(params);
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
                            		if(loggingStatement!=null) {
                            			loggingStatement.setInt(2, 1);	//1 for a "request"
                            			loggingStatement.setFloat(3, data.infusionRate);
                            			loggingStatement.setLong(4,System.currentTimeMillis());
                            			loggingStatement.execute();
                            		}
                            		System.err.println("QCore Got a flow rate request of "+data.infusionRate);
                            		setSpeed(data.infusionRate);
                            	} catch (IOException ioe) {
                            		log.error("Failed to set pump speed", ioe);
                            		ioe.printStackTrace();
                            	} catch (SQLException sqle) {
					log.error("Failed to log received speed request to database", sqle);
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
	
}
