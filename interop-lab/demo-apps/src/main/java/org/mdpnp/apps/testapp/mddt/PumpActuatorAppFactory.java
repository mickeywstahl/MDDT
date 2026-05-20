package org.mdpnp.apps.testapp.mddt;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.apps.testapp.pumps.PumpControllerTestApplicationFactory;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

import ice.FlowRateObjectiveDataWriter;
import ice.InfusionObjectiveDataWriter;
import ice.InfusionProgramDataWriter;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

public class PumpActuatorAppFactory implements IceApplicationProvider {

    private static final URL ICON_URL =
        PumpActuatorAppFactory.class.getResource("/org/mdpnp/apps/testapp/chart/chart.png");

    private final IceApplicationProvider.AppType type =
        new IceApplicationProvider.AppType(
            "MDDT Pump Actuator",
            null,
            ICON_URL,
            0.75,
            false
        );

    @Override
    public AppType getAppType() {
        return type;
    }

    @Override
    public IceApp create(ApplicationContext parentContext) throws IOException {

        final DeviceListModel deviceListModel =
            parentContext.getBean("deviceListModel", DeviceListModel.class);

        final NumericFxList numericList =
            parentContext.getBean("numericList", NumericFxList.class);

        final Subscriber subscriber =
            (Subscriber) parentContext.getBean("subscriber");

        final EventLoop eventLoop =
            (EventLoop) parentContext.getBean("eventLoop");

        final FlowRateObjectiveDataWriter flowRateWriter =
            (FlowRateObjectiveDataWriter) parentContext.getBean("flowRateObjectiveWriter");

        final InfusionObjectiveDataWriter infusionObjectiveWriter =
            (InfusionObjectiveDataWriter) parentContext.getBean("objectiveWriter");

        final InfusionProgramDataWriter infusionProgramWriter =
            (InfusionProgramDataWriter) parentContext.getBean("infusionPumpProgramWriter");

        final MDSHandler mdsHandler =
            (MDSHandler) parentContext.getBean("mdsConnectivity", MDSHandler.class);
        mdsHandler.start();

        // Stub — swap for generated DataWriter once TrialMarker.idl is built.
        final TrialMarkerDataWriter trialMarkerWriter = new TrialMarkerDataWriter();

        FXMLLoader loader = new FXMLLoader();
        loader.setClassLoader(getClass().getClassLoader());
        loader.setLocation(PumpActuatorAppFactory.class.getResource("PumpActuatorApp.fxml"));
        final Parent ui = loader.load();
        final PumpActuatorApp controller = (PumpActuatorApp) loader.getController();

        controller.set(
            parentContext,
            deviceListModel,
            numericList,
            flowRateWriter,
            infusionObjectiveWriter,
            infusionProgramWriter,
            trialMarkerWriter);

        controller.start(eventLoop);

        return new IceApplicationProvider.IceApp() {

            @Override public AppType getDescriptor() { return type; }
            @Override public Parent getUI()          { return ui;   }

            @Override
            public void activate(ApplicationContext context) {
                controller.activate();
            }

            @Override public void stop()    throws Exception { controller.stop();    }
            @Override public void destroy() throws Exception { controller.destroy(); }

            @Override public int getPreferredWidth()  { return 600; }
            @Override public int getPreferredHeight() { return 500; }
        };
    }
}
