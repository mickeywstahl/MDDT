package org.mdpnp.apps.testapp.physionet;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.springframework.context.ApplicationContext;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.publication.Publisher;
import com.rti.dds.topic.Topic;

import ice.NumericDataWriter;
import ice.NumericTopic;
import ice.NumericTypeSupport;
import ice.SampleArrayDataWriter;
import ice.SampleArrayTopic;
import ice.SampleArrayTypeSupport;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

/**
 * PhysioNetReplayAppFactory
 * =========================
 * IceApplicationProvider factory that registers the PhysioNet Replay device
 * in the OpenICE app launcher grid and wires real DDS DataWriters so that
 * waveform and numeric data are published to the ICE bus during playback.
 *
 * Writer creation follows the same pattern as RealisticSimPump and other
 * simulated devices: resolve "publisher" and "domainParticipant" Spring beans,
 * find or create the topic, then create a writer with the appropriate QoS profile.
 *
 * SPI registration -- confirm this line is in the services file:
 *   org.mdpnp.apps.testapp.physionet.PhysioNetReplayAppFactory
 */
public class PhysioNetReplayAppFactory implements IceApplicationProvider {

    private final IceApplicationProvider.AppType appType = new IceApplicationProvider.AppType(
        "PhysioNet Replay",
        "NO_PHYSIONET_REPLAY",
        (URL) PhysioNetReplayAppFactory.class.getResource(
            "/org/mdpnp/apps/testapp/sim/no-sim.png"),
        0.75f,
        false
    );

    @Override
    public IceApplicationProvider.AppType getAppType() {
        return appType;
    }

    @Override
    public IceApp create(ApplicationContext parentContext) throws IOException {

        // -- Resolve DDS infrastructure from Spring context ------------------
        final Publisher publisher =
            (Publisher) parentContext.getBean("publisher");
        final DomainParticipant participant =
            (DomainParticipant) parentContext.getBean("domainParticipant");

        // -- Create SampleArray DataWriter (waveform ECG leads) --------------
        SampleArrayDataWriter saWriter = createSampleArrayWriter(
            publisher, participant);

        // -- Create Numeric DataWriter (numeric signals e.g. HR, SpO2) ------
        NumericDataWriter numWriter = createNumericWriter(
            publisher, participant);

        // -- Instantiate replay device with live writers ---------------------
        PhysioNetReplayDevice device =
            new PhysioNetReplayDevice(saWriter, numWriter);

        // -- Load FXML using the instance pattern (required by OpenICE) ------
        FXMLLoader loader = new FXMLLoader();
        loader.setClassLoader(getClass().getClassLoader());
        loader.setLocation(PhysioNetReplayAppFactory.class
            .getResource("PhysioNetReplayApp.fxml"));

        final Parent ui = loader.load();
        final PhysioNetReplayApp controller =
            (PhysioNetReplayApp) loader.getController();

        controller.setDevice(device);
        controller.initialize();

        // -- Return the IceApp handle ----------------------------------------
        return new IceApplicationProvider.IceApp() {

            @Override public IceApplicationProvider.AppType getDescriptor() { return appType; }
            @Override public Parent  getUI()         { return ui;      }

            @Override
            public void activate(ApplicationContext context) {
                controller.activate(context);
            }

            @Override
            public void stop() throws Exception {
                controller.stop();
            }

            @Override
            public void destroy() throws Exception {
                controller.destroy();
                // Clean up writers when the app is destroyed
                if (saWriter  != null) saWriter.get_publisher()
                    .delete_datawriter(saWriter);
                if (numWriter != null) numWriter.get_publisher()
                    .delete_datawriter(numWriter);
            }

            @Override public int getPreferredWidth()  { return 1100; }
            @Override public int getPreferredHeight() { return 720;  }
        };
    }

    // -- Writer creation helpers ---------------------------------------------

    private SampleArrayDataWriter createSampleArrayWriter(
            Publisher publisher, DomainParticipant participant) {
        try {
            // Find or create the SampleArray topic
            Topic topic = findOrCreateTopic(
                participant,
                SampleArrayTopic.VALUE,
                SampleArrayTypeSupport.get_type_name(),
                SampleArrayTypeSupport.class);

            return (SampleArrayDataWriter)
                publisher.create_datawriter_with_profile(
                    topic,
                    QosProfiles.ice_library,
                    QosProfiles.waveform_data,
                    null,
                    StatusKind.STATUS_MASK_NONE);

        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to create SampleArray DataWriter for PhysioNet Replay", e);
        }
    }

    private NumericDataWriter createNumericWriter(
            Publisher publisher, DomainParticipant participant) {
        try {
            Topic topic = findOrCreateTopic(
                participant,
                NumericTopic.VALUE,
                NumericTypeSupport.get_type_name(),
                NumericTypeSupport.class);

            return (NumericDataWriter)
                publisher.create_datawriter_with_profile(
                    topic,
                    QosProfiles.ice_library,
                    QosProfiles.numeric_data,
                    null,
                    StatusKind.STATUS_MASK_NONE);

        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to create Numeric DataWriter for PhysioNet Replay", e);
        }
    }

    /**
     * Find an existing topic by name or create it if it doesn't exist yet.
     * Mirrors the pattern used by AbstractSimulatedConnectedDevice.
     */
    private Topic findOrCreateTopic(
            DomainParticipant participant,
            String topicName,
            String typeName,
            Class<?> typeSupport) {
        // Try to find an already-registered topic first
        Topic topic = participant.find_topic(
            topicName,
            com.rti.dds.infrastructure.Duration_t.DURATION_ZERO);

        if (topic != null) return topic;

        // Register the type and create the topic
        try {
            typeSupport.getMethod("register_type",
                DomainParticipant.class, String.class)
                .invoke(null, participant, typeName);
        } catch (Exception e) {
            // Type may already be registered -- that is fine
        }

        return participant.create_topic(
            topicName,
            typeName,
            null,
            null,
            StatusKind.STATUS_MASK_NONE);
    }
}
