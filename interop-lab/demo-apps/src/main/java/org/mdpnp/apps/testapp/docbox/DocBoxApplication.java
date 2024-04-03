package org.mdpnp.apps.testapp.docbox;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.rtiapi.data.EventLoop;

import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.subscription.Subscriber;

import docbox.MDS;
import docbox.MDSDataWriter;
import docbox.MDSStatus;
import docbox.NumericObservedValue;
import docbox.NumericObservedValueDataWriter;
import docbox.Timespec;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;

/**
 * An app that publishes records to DocBox...
 * 
 * @author simon
 *
 */
public class DocBoxApplication {
	
	private DeviceListModel deviceListModel;
	private NumericFxList numericList;
	private SampleArrayFxList sampleList;
	private MDSDataWriter docBoxMDSWriter;
	private NumericObservedValueDataWriter docBoxNOVWriter;
	
	public Button startButton;
	public TextField mdsIDField;
	
	private static final String ICE_ID="D4X-27412";
	

	public void set(DeviceListModel deviceListModel, NumericFxList numericList, SampleArrayFxList sampleList,
			MDSDataWriter docBoxMDSWriter, NumericObservedValueDataWriter docBoxNOVWriter) {
		this.deviceListModel=deviceListModel;
		this.numericList=numericList;
		this.sampleList=sampleList;
		this.docBoxMDSWriter=docBoxMDSWriter;
		this.docBoxNOVWriter=docBoxNOVWriter;
	}

	public void start(EventLoop eventLoop, Subscriber subscriber) {
		//Nothing special to do at the moment...
		
	}
	
	public static void printDocBoxMDS(MDS docboxMDS) {
		StringBuilder sb=new StringBuilder("Publish Medical Device System (MDS) data");
		sb.append("\n")
		.append("  ice id:           ")
		.append(docboxMDS.ice_id).append("\n")
		.append("  mds status:       ")
		.append(decodeStatus(docboxMDS.mds_status)).append("\n")
		.append("  source timestamp: ")
		.append(decodeTimestamp(docboxMDS.source_timestamp));
		System.out.println(sb);
	}
	
	public static String decodeStatus(MDSStatus status) {
		return status.value()+"  "+status.name();
	}
	
	public static String decodeTimestamp(Timespec ts) {
		return ts.seconds+"."+ts.nanoseconds;
	}
	
	public static Timespec getCurrentTimespec() {
		Timespec ts=new Timespec();
		long now=System.currentTimeMillis();
		ts.seconds=(int)(now/1000);
		ts.nanoseconds=(int)(now%1000)*1_000_000;
		return ts;
	}
	
	public static void printDocBoxNOV(NumericObservedValue nov) {
		StringBuilder sb=new StringBuilder("Publish Numeric Observed Value data");
		sb.append("\n")
		.append("  ice id: ")
		.append(nov.ice_id).append("\n")
		.append("  metric_id: ").append(nov.nu_observed_value.metric_id).append("\n")
		.append("  value: ").append(nov.nu_observed_value.value).append("\n")
		.append("  source timestamp: ")
		.append(decodeTimestamp(nov.source_timestamp));
		System.out.println(sb);
	}
	
	public void publishSomething() {
		System.err.println("publishSomething started");
		
		/*
		 * To start off with, just publish a few MDS records...
		 */
		try {
			String ourIceId=mdsIDField.getText();
			if(ourIceId.trim().length()==0) {
				ourIceId=ICE_ID;
			}
			int status=1;	//Initial value.
			MDS docboxMDS=new MDS();
			docboxMDS.ice_id=ourIceId;
			InstanceHandle_t docboxMDSHandle=docBoxMDSWriter.register_instance(docboxMDS);
			while(status<4) {
				docboxMDS.mds_status=MDSStatus.from_int(status++);
				docboxMDS.source_timestamp=getCurrentTimespec();
				printDocBoxMDS(docboxMDS);
				docBoxMDSWriter.write(docboxMDS, docboxMDSHandle);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException ie) {
					//whatever...
				}
			}
			
			//Now we have printed those, write some numerics...
			NumericObservedValue nov=new NumericObservedValue();
			nov.ice_id=ourIceId;
			nov.nu_observed_value.metric_id=19384;	//TODO: Hard coded metric id from x73_nomen_medical_scada.h in RTI sample code
			nov.handle=300;	//TODO: Hard coded value from HelloIceData.cxx
			nov.parent_handle=100;	//TODO: Also hard coded.
			int count=0;
			InstanceHandle_t docboxNOVHandle=docBoxNOVWriter.register_instance(nov);
			while(count++<10) {
				long now=System.nanoTime();
				long val=((now%7)+92);
				nov.nu_observed_value.value=val;
				nov.source_timestamp=getCurrentTimespec();
				printDocBoxNOV(nov);
				docBoxNOVWriter.write(nov, docboxNOVHandle);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException ie) {
					//whatever...
				}
			}
			
			
		} catch (Exception e) {
			e.printStackTrace();
		}
			
		
	}

	public void activate() {
		// TODO Auto-generated method stub
		
	}

	public void stop() {
		// TODO Auto-generated method stub
		
	}

	public void destroy() {
		// TODO Auto-generated method stub
		
	}

}
