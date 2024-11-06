package org.mdpnp.apps.testapp.easytiva;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

import ice.InfusionProgramDataWriter;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

public class GenericTranslatorApplicationFactory implements IceApplicationProvider {
	
	private IceApplicationProvider.AppType type=new IceApplicationProvider.AppType(
			"Generic Translator", "NoGT", (URL) GenericTranslatorApplicationFactory.class.getResource("easytiva.png"), 0.75, false
		);

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
        
        final VitalModel vitalModel= (VitalModel) parentContext.getBean("vitalModel");
        
        final InfusionProgramDataWriter infusionProgramWriter=(InfusionProgramDataWriter) parentContext.getBean("infusionPumpProgramWriter");
        
        final MDSHandler mdsHandler=(MDSHandler)parentContext.getBean("mdsConnectivity",MDSHandler.class);
        mdsHandler.start();

        final EMRFacade emr=(EMRFacade) parentContext.getBean("emr");
		
		FXMLLoader loader = new FXMLLoader(GenericTranslatorApplication.class.getResource("GenericTranslatorApplication.fxml"));
		
		final AlertFxList patientAlertList=(AlertFxList) parentContext.getBean("patientAlertList");
		
		final AlertFxList technicalAlertList=(AlertFxList) parentContext.getBean("technicalAlertList");

        final Parent ui = loader.load();
       
        final GenericTranslatorApplication controller = ((GenericTranslatorApplication) loader.getController());
        
        controller.set(parentContext, deviceListModel, numericList, sampleList, infusionProgramWriter, mdsHandler, vitalModel, subscriber, emr, patientAlertList, technicalAlertList);
        
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
				return 800;
			}
			
			public int getPreferredHeight() {
				return 800;
			}
			
		};
	}

}
