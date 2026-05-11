package org.mdpnp.apps.testapp.mddt;

import com.rti.dds.subscription.SampleInfoSeq;

/**
 * Stub DataReader for the TrialMarker topic.
 * Replace with RTI-generated TrialMarkerDataReader once IDL is defined.
 */
public class TrialMarkerDataReader {
    public com.rti.dds.subscription.ReadCondition create_readcondition(
            int sampleState, int viewState, int instanceState) {
        // TODO: replace with generated DataReader.create_readcondition()
        return null;
    }

    public void read_w_condition(TrialMarkerSeq data_seq, SampleInfoSeq info_seq,
            int maxSamples, com.rti.dds.subscription.ReadCondition condition) {
        throw new com.rti.dds.infrastructure.RETCODE_NO_DATA();
    }

    public void return_loan(TrialMarkerSeq data_seq, SampleInfoSeq info_seq) {
        // no-op in stub
    }
}
