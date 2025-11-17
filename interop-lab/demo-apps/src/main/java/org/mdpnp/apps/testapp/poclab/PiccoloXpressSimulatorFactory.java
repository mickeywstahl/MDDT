package org.mdpnp.apps.testapp.poclab;

import java.io.IOException;

import org.mdpnp.apps.testapp.IceApplicationProvider;
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
		
		final Subscriber subscriber = (Subscriber) parentContext.getBean("subscriber");

        final EventLoop eventLoop = (EventLoop) parentContext.getBean("eventLoop");
        
        //"flowRateObjectiveWriter" is a new bean in IceAppContainerContext.xml
       
		
		FXMLLoader loader = new FXMLLoader(PiccoloXpressSimulator.class.getResource("piccolo-xpress-sim.fxml"));

        final Parent ui = loader.load();
        
        final PiccoloXpressSimulator controller = ((PiccoloXpressSimulator) loader.getController());
        
        controller.set();
        
        controller.start(eventLoop, subscriber);
		
		return new IceApplicationProvider.IceApp() {

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