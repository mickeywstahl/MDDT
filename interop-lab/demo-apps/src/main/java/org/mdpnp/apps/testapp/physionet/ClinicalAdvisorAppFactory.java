package org.mdpnp.apps.testapp.physionet;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.springframework.context.ApplicationContext;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

/**
 * ClinicalAdvisorAppFactory
 * =========================
 * IceApplicationProvider that registers the Clinical Advisor app in the
 * OpenICE launcher. The app displays real-time ECG diagnosis from the
 * Ribeiro ResNet model (Nature Communications 2020).
 *
 * SPI registration: append to
 *   interop-lab/demo-apps/src/main/resources/META-INF/services/
 *       org.mdpnp.apps.testapp.IceApplicationProvider
 *
 *   org.mdpnp.apps.testapp.physionet.ClinicalAdvisorAppFactory
 */
public class ClinicalAdvisorAppFactory implements IceApplicationProvider {

    private final IceApplicationProvider.AppType appType = new IceApplicationProvider.AppType(
        "Clinical Advisor",
        "NO_CLINICAL_ADVISOR",
        (URL) ClinicalAdvisorAppFactory.class.getResource(
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

        final SampleArrayFxList sampleArrayList = parentContext.getBean("sampleArrayList", SampleArrayFxList.class);

        FXMLLoader loader = new FXMLLoader();
        loader.setClassLoader(getClass().getClassLoader());
        loader.setLocation(ClinicalAdvisorAppFactory.class
            .getResource("ClinicalAdvisorApp.fxml"));

        final Parent ui = loader.load();
        final ClinicalAdvisorApp controller =
            (ClinicalAdvisorApp) loader.getController();

        controller.set(sampleArrayList);
        controller.initialize();

        return new IceApplicationProvider.IceApp() {

            @Override public IceApplicationProvider.AppType getDescriptor() { return appType; }
            @Override public Parent  getUI()         { return ui; }

            @Override
            public void activate(ApplicationContext context) {
                controller.activate(context);
            }

            @Override public void stop()    throws Exception { controller.stop(); }
            @Override public void destroy() throws Exception { controller.destroy(); }

            @Override public int getPreferredWidth()  { return 1100; }
            @Override public int getPreferredHeight() { return 720;  }
        };
    }
}
