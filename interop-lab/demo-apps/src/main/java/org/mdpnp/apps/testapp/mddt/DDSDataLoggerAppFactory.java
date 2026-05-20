package org.mdpnp.apps.testapp.mddt;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.apps.testapp.pumps.PumpControllerTestApplicationFactory;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

public class DDSDataLoggerAppFactory implements IceApplicationProvider {

    private static final URL ICON_URL =
        DDSDataLoggerAppFactory.class.getResource("/org/mdpnp/apps/testapp/chart/chart.png");

    private final IceApplicationProvider.AppType type =
        new IceApplicationProvider.AppType(
            "MDDT DDS Logger",
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

        final AlertFxList alertList =
            parentContext.getBean("technicalAlertList", AlertFxList.class);

        final Subscriber subscriber =
            (Subscriber) parentContext.getBean("subscriber");

        final EventLoop eventLoop =
            (EventLoop) parentContext.getBean("eventLoop");

        // Stub — swap for generated DataReader once TrialMarker.idl is built.
        final TrialMarkerDataReader trialMarkerReader = new TrialMarkerDataReader();

        FXMLLoader loader = new FXMLLoader();
        loader.setClassLoader(getClass().getClassLoader());
        loader.setLocation(DDSDataLoggerAppFactory.class.getResource("DDSDataLoggerApp.fxml"));
        final Parent ui = loader.load();
        final DDSDataLoggerApp controller = (DDSDataLoggerApp) loader.getController();

        controller.set(
            parentContext,
            deviceListModel,
            numericList,
            alertList,
            trialMarkerReader);

        controller.start(eventLoop, subscriber);

        return new IceApplicationProvider.IceApp() {

            @Override public AppType getDescriptor() { return type; }
            @Override public Parent getUI()          { return ui;   }

            @Override
            public void activate(ApplicationContext context) {
                controller.activate();
            }

            @Override public void stop()    throws Exception { controller.stop();    }
            @Override public void destroy() throws Exception { controller.destroy(); }

            @Override public int getPreferredWidth()  { return 900; }
            @Override public int getPreferredHeight() { return 700; }
        };
    }
}
