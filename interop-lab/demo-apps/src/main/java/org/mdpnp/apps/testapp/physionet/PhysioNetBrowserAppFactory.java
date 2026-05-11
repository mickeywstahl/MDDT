package org.mdpnp.apps.testapp.physionet;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.springframework.context.ApplicationContext;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

/**
 * PhysioNetBrowserAppFactory
 * ==========================
 * IceApplicationProvider factory that registers the PhysioNet Browser
 * in the OpenICE app launcher grid.
 *
 * Registration: add this fully-qualified class name to
 *   interop-lab/demo-apps/src/main/resources/META-INF/services/
 *       org.mdpnp.apps.testapp.IceApplicationProvider
 *
 * The app lets clinicians/researchers browse PhysioNet databases,
 * inspect signal metadata, and download targeted WFDB record files
 * to a fixed local path for replay into OpenICE via a device adapter.
 *
 * No DDS writers or subscribers are required — this app is read-only
 * with respect to the ICE bus. It only uses the Spring context for
 * standard infrastructure beans if needed in future extensions.
 */
public class PhysioNetBrowserAppFactory implements IceApplicationProvider {

    private final IceApplicationProvider.AppType appType = new IceApplicationProvider.AppType(
        "PhysioNet Browser",    // display name shown in the launcher grid
        "NO_PHYSIONET_BROWSER", // unique string ID — must not clash with others
        (URL) PhysioNetBrowserAppFactory.class.getResource(
            "/org/mdpnp/apps/testapp/sim/no-sim.png"), // reuse standard placeholder icon
        0.75f,
        false                   // not a device — no device selection required
    );

    @Override
    public IceApplicationProvider.AppType getAppType() {
        return appType;
    }

    @Override
    public IceApp create(ApplicationContext parentContext) throws IOException {

        // ---- Load FXML using the instance pattern ----
        // Always use instance FXMLLoader with explicit setClassLoader +
        // setLocation. The static FXMLLoader.load() shorthand fails in
        // OSGi / modular classpath environments used by OpenICE.

        FXMLLoader loader = new FXMLLoader();
        loader.setClassLoader(getClass().getClassLoader());
        loader.setLocation(PhysioNetBrowserAppFactory.class
            .getResource("PhysioNetBrowserApp.fxml"));

        final Parent ui = loader.load();
        final PhysioNetBrowserApp controller =
            (PhysioNetBrowserApp) loader.getController();

        controller.initialize();

        // ---- Return the IceApp handle ----

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
                controller.activate();
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
            public int getPreferredWidth() {
                // Three-panel layout needs generous horizontal space:
                // ~300px database list + ~220px record list + ~600px detail panel
                return 1180;
            }

            @Override
            public int getPreferredHeight() {
                return 780;
            }
        };
    }
}
