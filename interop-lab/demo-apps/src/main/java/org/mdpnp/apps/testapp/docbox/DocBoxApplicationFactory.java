package org.mdpnp.apps.testapp.docbox;

import java.io.IOException;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.rtiapi.data.DocBoxNumericObservedValueWriterFactory;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

import docbox.MDSDataWriter;
import docbox.NumericObservedValueDataWriter;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

public class DocBoxApplicationFactory implements IceApplicationProvider {
	
	final AppType type=new AppType("DocBox", "NoDocBox", DocBoxApplicationFactory.class.getResource("docbox.png"), 1, false);

	@Override
	public AppType getAppType() {
		return type;
	}

	@Override
	public IceApp create(ApplicationContext parentContext) throws IOException {
		final DeviceListModel deviceListModel = parentContext.getBean("deviceListModel", DeviceListModel.class);
		
		final NumericFxList numericList = parentContext.getBean("numericList", NumericFxList.class);
		
		final SampleArrayFxList sampleList = parentContext.getBean("sampleArrayList", SampleArrayFxList.class);
		
		final Subscriber subscriber = (Subscriber) parentContext.getBean("subscriber");

        final EventLoop eventLoop = (EventLoop) parentContext.getBean("eventLoop");
        
        final MDSDataWriter docBoxMDSWriter=(MDSDataWriter) parentContext.getBean("docBoxMDSWriter");
        
        final NumericObservedValueDataWriter docBoxNOVWriter=(NumericObservedValueDataWriter) parentContext.getBean("docBoxNOVWriter");
        
//        final MDSHandler mdsHandler=(MDSHandler)parentContext.getBean("mdsConnectivity",MDSHandler.class);
//        mdsHandler.start();
        
        FXMLLoader loader = new FXMLLoader(DocBoxApplication.class.getResource("DocBoxApplication.fxml"));

        final Parent ui = loader.load();
        
        final DocBoxApplication controller = ((DocBoxApplication) loader.getController());
        
        controller.set(deviceListModel, numericList, sampleList, docBoxMDSWriter, docBoxNOVWriter/*, mdsHandler*/);
        
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

			@Override
			public int getPreferredWidth() {
				// TODO Auto-generated method stub
				return 800;
			}

			@Override
			public int getPreferredHeight() {
				// TODO Auto-generated method stub
				return 200;
			}
			
			
			
		};
	}

}
