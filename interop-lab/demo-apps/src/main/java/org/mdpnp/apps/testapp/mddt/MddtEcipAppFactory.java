package org.mdpnp.apps.testapp.mddt;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

import ice.InfusionObjectiveDataWriter;
import ice.InfusionProgramDataWriter;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

public class MddtEcipAppFactory implements IceApplicationProvider {

    // Fix: use a valid icon and set disableProperty to null
    private final AppType type = new AppType(
            "MDDT for ECIPs",
            null,
            MddtEcipAppFactory.class.getResource("/org/mdpnp/apps/testapp/chart/chart.png"),
            0.75f,
            false);

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
        final SampleArrayFxList sampleList =
                parentContext.getBean("sampleArrayList", SampleArrayFxList.class);
        final AlertFxList alertList =
                parentContext.getBean("technicalAlertList", AlertFxList.class);

        final Subscriber subscriber =
                (Subscriber) parentContext.getBean("subscriber");
        final EventLoop eventLoop =
                (EventLoop) parentContext.getBean("eventLoop");

        final InfusionObjectiveDataWriter infusionObjectiveWriter =
                (InfusionObjectiveDataWriter) parentContext.getBean("infusionObjectiveWriter");
        final InfusionProgramDataWriter infusionProgramWriter =
                (InfusionProgramDataWriter) parentContext.getBean("infusionProgramWriter");

        final MDSHandler mdsHandler =
                (MDSHandler) parentContext.getBean("mdsConnectivity", MDSHandler.class);
        mdsHandler.start();

        FXMLLoader loader = new FXMLLoader(
                MddtEcipAppFactory.class.getResource("MddtEcip.fxml"));
        Parent ui = loader.load();

        MddtEcipApp app = (MddtEcipApp) loader.getController();
        app.set(deviceListModel, numericList, sampleList, alertList,
                infusionObjectiveWriter, infusionProgramWriter, mdsHandler);
        app.start(eventLoop, subscriber);

        return new IceApp() {
            @Override
            public AppType getDescriptor() { return type; }

            @Override
            public Parent getUI() { return ui; }

            @Override
            public void activate(ApplicationContext context) { app.activate(); }

            @Override
            public void stop() throws Exception { app.stop(); }

            @Override
            public void destroy() throws Exception { app.destroy(); }
        };
    }
}
