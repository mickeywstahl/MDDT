package org.mdpnp.apps.testapp.mddt;

/**
 * Stub DataWriter for the TrialMarker topic.
 * Replace with RTI-generated TrialMarkerDataWriter once IDL is defined.
 */
public class TrialMarkerDataWriter {
    public void write(TrialMarker marker, com.rti.dds.infrastructure.InstanceHandle_t handle) {
        // TODO: replace with generated DataWriter.write()
        // For now, log to SLF4J so the actuator behaves correctly during stub phase
        org.slf4j.LoggerFactory.getLogger(TrialMarkerDataWriter.class)
            .info("[TRIAL_MARKER_STUB] {}", marker);
    }
}
