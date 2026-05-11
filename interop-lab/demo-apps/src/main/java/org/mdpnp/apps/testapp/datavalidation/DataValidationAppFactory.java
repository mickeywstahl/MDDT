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
 * Factory for creating the Data Validation application and registering it
 * with the OpenICE app launcher.
 */
public class DataValidationAppFactory implements IceApplicationProvider {

    // Using a default placeholder icon (no-sim.png) or you can place a custom icon in resources
    private final IceApplicationProvider.AppType appType = new IceApplicationProvider.AppType(
        "Data Validation",
        "NO_DATA_VALIDATION",
        (URL) DataValidationAppFactory.class.getResource("/org/mdpnp/apps/testapp/sim/no-sim.png"),
        0.75f,
        false
    );

    @Override
    public IceApplicationProvider.AppType getAppType() {
        return appType;
    }

    @Override
    public IceApp create(ApplicationContext parentContext) throws IOException {

        final SampleArrayFxList sampleArrayList = parentContext.getBean("sampleArrayList", SampleArrayFxList.class);

        FXMLLoader loader = new FXMLLoader();
        loader.setClassLoader(getClass().getClassLoader());
        loader.setLocation(DataValidationAppFactory.class.getResource("DataValidationApp.fxml"));

        final Parent ui = loader.load();
        final DataValidationApp controller = loader.getController();

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

            @Override 
            public int getPreferredWidth()  { 
                return 800; 
            }
            
            @Override 
            public int getPreferredHeight() { 
                return 600;  
            }
        };
    }
}
