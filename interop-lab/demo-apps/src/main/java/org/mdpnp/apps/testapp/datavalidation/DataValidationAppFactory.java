package org.mdpnp.apps.testapp.datavalidation;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.springframework.context.ApplicationContext;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

/**
 * DataValidationAppFactory
 * ========================
 * IceApplicationProvider factory for the Data Validation app.
 *
 * Resolves the "sampleArrayList" Spring bean (SampleArrayFxList),
 * injects it into the controller, and wires activate/stop/destroy
 * so the OpenICE container can manage the app lifecycle correctly.
 *
 * activate() is critical -- it calls bridge.start() which launches
 * the 5-second SQI computation scheduler. Without it the SQI trace
 * and cleaned PPG panes never update.
 *
 * SPI registration -- confirm this line is in the services file:
 *   org.mdpnp.apps.testapp.datavalidation.DataValidationAppFactory
 */
public class DataValidationAppFactory implements IceApplicationProvider {

    private final IceApplicationProvider.AppType appType =
        new IceApplicationProvider.AppType(
            "Data Validation",
            "NO_DATA_VALIDATION",
            (URL) DataValidationAppFactory.class.getResource(
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

        // Resolve the shared SampleArray list from Spring
        final SampleArrayFxList sampleArrayList =
            parentContext.getBean("sampleArrayList", SampleArrayFxList.class);

        // Load FXML
        FXMLLoader loader = new FXMLLoader();
        loader.setClassLoader(getClass().getClassLoader());
        loader.setLocation(DataValidationAppFactory.class
            .getResource("DataValidationApp.fxml"));

        final Parent ui = loader.load();
        final DataValidationApp controller =
            (DataValidationApp) loader.getController();

        // Inject dependencies and initialize UI
        controller.set(sampleArrayList);
        controller.initialize();

        return new IceApplicationProvider.IceApp() {

            @Override
            public IceApplicationProvider.AppType getDescriptor() {
                return appType;
            }

            @Override
            public Parent getUI() {
                return ui;
            }

            @Override
            public void activate(ApplicationContext context) {
                // This starts the bridge scheduler and rawWaveformPane
                controller.activate(context);
            }

            @Override
            public void stop() throws Exception {
                controller.stop();
            }

            @Override
            public void destroy() throws Exception {
                controller.destroy();
            }

            @Override public int getPreferredWidth()  { return 1100; }
            @Override public int getPreferredHeight() { return 750;  }
        };
    }
}
