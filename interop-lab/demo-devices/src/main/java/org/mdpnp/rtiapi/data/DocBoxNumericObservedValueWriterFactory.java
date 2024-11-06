package org.mdpnp.rtiapi.data;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.publication.Publisher;
import com.rti.dds.topic.Topic;

import docbox.NumericMeasObservedValueTopic;
import docbox.NumericObservedValueDataWriter;

/**
 *
 */
public class DocBoxNumericObservedValueWriterFactory implements FactoryBean<NumericObservedValueDataWriter>, DisposableBean {
    private Topic topic;
    private NumericObservedValueDataWriter instance;

    private final DomainParticipant participant;
    private final Publisher publisher;

    @Override
    public NumericObservedValueDataWriter getObject() throws Exception {
        if(instance == null) {
            topic = TopicUtil.findOrCreateTopic(participant, NumericMeasObservedValueTopic.VALUE,  docbox.NumericObservedValueTypeSupport.class);
            instance = (NumericObservedValueDataWriter) publisher.create_datawriter_with_profile(topic, QosProfiles.ice_library,
                    QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);
        }
        return instance;
    }

    @Override
    public Class<?> getObjectType() {
        return NumericObservedValueDataWriter.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    public DocBoxNumericObservedValueWriterFactory(DomainParticipant participant, Publisher publisher) {
        this.participant = participant;
        this.publisher = publisher;
    }

    @Override
    public void destroy() throws Exception {
        if(null != instance) {
            publisher.delete_datawriter(instance);
            publisher.get_participant().delete_topic(topic);
        }
    }
}
