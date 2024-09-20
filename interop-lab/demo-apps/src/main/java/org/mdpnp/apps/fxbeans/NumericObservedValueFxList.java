package org.mdpnp.apps.fxbeans;


public class NumericObservedValueFxList extends AbstractFxList<ice.NumericObservedValue, ice.NumericObservedValueDataReader, NumericObservedValueFx> {

    public NumericObservedValueFxList(final String topicName) {
        super(topicName, ice.NumericObservedValue.class, ice.NumericObservedValueDataReader.class, ice.NumericObservedValueTypeSupport.class, ice.NumericObservedValueSeq.class, NumericObservedValueFx.class);
    }

}
