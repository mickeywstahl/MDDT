package org.mdpnp.apps.testapp.poclab;

import java.io.IOException;

import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

public class PiccoloXpressSimulatorFactory implements IceApplicationProvider {
	
	final AppType type=new AppType("Abaxis Piccolo Xpress", "NoPiccoloXpress", PiccoloXpressSimulatorFactory.class.getResource("piccolo-xpress-3.jpg"), 0.75, false);

	@Override
	public AppType getAppType() {
		return type;
	}

	@Override
	public IceApp create(ApplicationContext parentContext) throws IOException {
		
		final EMRFacade emr = (EMRFacade) parentContext.getBean("emr");
		
		final Subscriber subscriber = (Subscriber) parentContext.getBean("subscriber");

        final EventLoop eventLoop = (EventLoop) parentContext.getBean("eventLoop");
        
        final MDSHandler mdsHandler=(MDSHandler)parentContext.getBean("mdsConnectivity",MDSHandler.class);
        mdsHandler.start();
        
		FXMLLoader loader = new FXMLLoader(PiccoloXpressSimulator.class.getResource("piccolo-xpress-sim.fxml"));

        final Parent ui = loader.load();
        
        final PiccoloXpressSimulator controller = ((PiccoloXpressSimulator) loader.getController());
        
        controller.set(emr, mdsHandler);
        
        controller.start(eventLoop, subscriber);
		
		return new IceApplicationProvider.IceApp() {

			@Override
			public int getPreferredWidth() {
				return 800;
			}

			@Override
			public int getPreferredHeight() {
				return 600;
			}

			@Override
			public AppType getDescriptor() {
				return type;
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
			
		};
	}

}