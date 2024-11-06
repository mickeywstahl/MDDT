package org.mdpnp.apps.docbox;

import java.util.Date;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.NumericObservedValueFxList;
import org.mdpnp.apps.fxbeans.NumericObservedValueFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;

import com.pixelmed.dicom.AttributeList;
import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.infrastructure.QosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.publication.DataWriter;
import com.rti.dds.publication.DataWriterQos;
import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.topic.Topic;
import com.rti.dds.topic.TopicQos;

import ice.AVAType;
import ice.AVATypeSeq;
import ice.BPObjectiveDataWriter;
import ice.BPPauseResumeObjectiveDataWriter;
import ice.MDO_ID;
import ice.NumericDataWriter;
import ice.NumericObservedValueDataReader;
import ice.NumericTypeSupport;
import ice.OpElementAttr;
import ice.OpInvokeElement;
import ice.OpInvokeElementSeq;
import ice.OpInvokeList;
import ice.OpModType;
import ice.SCOOperationInvoke;
import ice.SCOOperationInvokeDataWriter;
import ice.SCOOperationInvokeTopic;
import ice.SCOOperationInvokeTypeSupport;
import ice.Timespec;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

public class DocBoxApplication {
	
	private DomainParticipant participant;
    private Publisher publisher;
    private SCOOperationInvokeDataWriter writer;
    SCOOperationInvoke op;
    Topic topic;
	
	@FXML
	TextField flowRate1,flowRate2;
	private NumericObservedValueFxList docBoxData;
	@FXML Label infusionRateLabel1, infusionRateLabel2, volInfused1, volInfused2, vtbi1, vtbi2;

	public void set(NumericObservedValueFxList docBoxData) {
		this.docBoxData=docBoxData;
		// TODO Auto-generated method stub
		
	}

	public void start(EventLoop eventLoop, Subscriber subscriber) {
		initDocBoxWriter();
		addListeners();
	}

	public void activate() {
		// TODO Auto-generated method stub
		
	}

	public void stop() {
		// TODO Auto-generated method stub
		
	}

	public void destroy() {
		if(writer!=null) {
			writer.dispose(op, InstanceHandle_t.HANDLE_NIL);
		}
		if(publisher!=null) {
			publisher.delete_datawriter(writer);
		}
		participant.delete_topic(topic);
		participant.delete_publisher(publisher);
		DomainParticipantFactory.get_instance().delete_participant(participant);
		
	}
	
	public void publish() {
		String strRate1=flowRate1.getText();
		String strRate2=flowRate2.getText();
		System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!! INSIDE PUBILISH, got values - " + strRate1 + strRate2);
		if(op==null) {
			op=new SCOOperationInvoke();
		}
		op.ice_id="ControlApp";
		op.handle=6000;
		op.parent_handle=0;
		op.source_timestamp=new Timespec();
		op.source_timestamp.seconds=(int)System.currentTimeMillis();
		
		//op.source_timestamp=REQUIRED_OR_NOT?

		op.sco_id=new MDO_ID();
		op.sco_id.handle=502;
		op.sco_id.ice_id="nwap1";
		
		op.checksum=0;
		op.invoke_cookie=246099521;
		
		OpInvokeList op_invoke=new OpInvokeList();
		OpInvokeElementSeq seq=new OpInvokeElementSeq();
		
		OpInvokeElement elem1=new OpInvokeElement();
		elem1.op_mod_type=OpModType.op_invokeAction;
		OpElementAttr attr1=new OpElementAttr();
		attr1.op_class_id=50;
		attr1.op_instance_no=2;
		ice.AttributeList attribsList1=new ice.AttributeList();
		attribsList1.count=1;
		attribsList1.value=new AVATypeSeq();
		AVAType type1=new AVAType();
		if(strRate1.length()>0) {
			type1.attribute_id=26712;
			type1.attribute_value=strRate1;
		}
		attribsList1.value.add(type1);
		AVAType type2=new AVAType();
		if(strRate2.length()>0) {
			type2.attribute_id=26712;
			type2.attribute_value=strRate2;
		}
		attribsList1.value.add(type2);
		attr1.attributes=attribsList1;
		elem1.op_elem_attr=attr1;
		
		seq.add(elem1);
		
		OpInvokeElement elem2=new OpInvokeElement();
		elem2.op_mod_type=OpModType.op_replace;
		OpElementAttr attr2=new OpElementAttr();
		attr2.op_class_id=0;
		attr2.op_instance_no=0;
		ice.AttributeList attribsList2=new ice.AttributeList();
		attribsList2.count=1;
		attribsList2.value=new AVATypeSeq();
		AVAType type3=new AVAType();
		type3.attribute_id=0;
		type3.attribute_value="";
		attribsList2.value.add(type3);
		AVAType type4=new AVAType();
		type4.attribute_id=0;
		type4.attribute_value="";
		attribsList2.value.add(type4);
		attr2.attributes=attribsList2;
		elem2.op_elem_attr=attr2;
		
		seq.add(elem2);
		
		op_invoke.userData=seq;
		op.op_elem_list=op_invoke;
		
		System.out.println("<<<<<About to publish "+op);
		
		writer.write(op, InstanceHandle_t.HANDLE_NIL);
		
		System.out.println(">>>>Published it...");
		
		
		
	}
	
	private void initDocBoxWriter() {
		
		/*
		 * NumericTypeSupport.register_type(domainParticipant, NumericTypeSupport.get_type_name());
        numericTopic = TopicUtil.findOrCreateTopic(domainParticipant, ice.NumericTopic.VALUE, NumericTypeSupport.class);
        numericDataWriter = (NumericDataWriter) publisher.create_datawriter_with_profile(numericTopic, QosProfiles.ice_library,
                QosProfiles.numeric_data, null, StatusKind.STATUS_MASK_NONE);
        if (null == numericDataWriter) {
            throw new RuntimeException("numericDataWriter not created");
        }
		 */
		
		
		participant=DomainParticipantFactory.get_instance().create_participant(2, DomainParticipantFactory.PARTICIPANT_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
		
		TopicQos topicQos=new TopicQos();
		
//		DomainParticipantFactory.get_instance().get_topic_qos_from_profile(topicQos, "dices_dim_library", "dices_dim_durable_profile");
//		topicQos.
		
		
		SCOOperationInvokeTypeSupport.register_type(participant, SCOOperationInvokeTypeSupport.get_type_name());
		publisher=participant.create_publisher_with_profile("dices_dim_library", "dices_dim_durable_profile", null, StatusKind.STATUS_MASK_NONE);
		topic=TopicUtil.createTopic(participant, SCOOperationInvokeTopic.VALUE, SCOOperationInvokeTypeSupport.class);
		writer=(SCOOperationInvokeDataWriter) publisher.create_datawriter_with_profile(topic, "dices_dim_library", "dices_dim_durable_profile", null, StatusKind.STATUS_MASK_NONE);
		DataWriterQos getme=new DataWriterQos();
		writer.get_qos(getme);
		System.err.println(getme.publication_name.toString());
	}
	
	private void addListeners() {
		docBoxData.addListener(new ListChangeListener<NumericObservedValueFx>() {

			@Override
			public void onChanged(Change<? extends NumericObservedValueFx> c) {
				while(c.next()) {
					c.getAddedSubList().forEach( n -> {
						addDocBoxListener(n);
					});
				}
			}
			
		});
	}
	
	private void addDocBoxListener(NumericObservedValueFx n) {
		n.source_timestampProperty().addListener(new ChangeListener<Date>() {

			@Override
			public void changed(ObservableValue<? extends Date> observable, Date oldValue, Date newValue) {
				System.err.println("At date "+newValue+" values is "+n.getValue());
				if (n.getDBHandle() == 1009) {
					infusionRateLabel1.setText("Current Infusion Rate: " + n.getValue() + " ml/h");
				}
				if (n.getDBHandle() == 1005) {
					volInfused1.setText("Vol Infused: " + n.getValue() + " ml");
				}
				
				if (n.getDBHandle() == 1007) {
					vtbi1.setText("VTBI: " + n.getValue() + " ml");
				}
				
				if (n.getDBHandle() == 1022) {
					infusionRateLabel2.setText("Current Infusion Rate: " + n.getValue() + " ml/h");
				}
				
				if (n.getDBHandle() == 1018) {
					volInfused2.setText("Vol Infused: " + n.getValue() + " ml");
				}
				
				if (n.getDBHandle() == 1020) {
					vtbi2.setText("VTBI: " + n.getValue() + " ml");
				}
				
				//n.dbHandle()
				
			}
			
		}); 
		
	}
	
	

}
