package org.mdpnp.apps.fxbeans;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;

public class NumericObservedValueFxListFactory extends AbstractFxListFactory implements FactoryBean<NumericObservedValueFxList>, DisposableBean {

    private NumericObservedValueFxList instance;
    
    public NumericObservedValueFxListFactory() {
    }
        
    @Override
    public void destroy() throws Exception {
        if(null != instance) {
            instance.stop();
        }
    }

    @Override
    public NumericObservedValueFxList getObject() throws Exception {
        if(null == instance) {
            instance = new NumericObservedValueFxList(topicName);
            System.err.println("callin instance.start for topicName "+topicName);
            instance.start(subscriber, eventLoop, expression, params, qosLibrary, qosProfile);
        }
        return instance;
    }

    @Override
    public Class<?> getObjectType() {
        return NumericObservedValueFxList.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

}
