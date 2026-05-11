package org.mdpnp.apps.testapp.acliva;

import java.io.IOException;
import java.net.URL;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.patient.EMRFacade.EMRFacadeFactory;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

import ice.FlowRateObjectiveDataWriter;
import ice.InfusionObjectiveDataWriter;
import ice.InfusionProgramDataWriter;

public class AclivaTestApplicationFactory implements IceApplicationProvider {



    private final IceApplicationProvider.AppType AclivaTestApplication = new IceApplicationProvider.AppType(
            "ACLIVA Test", "NO_ACLIVA", (URL) null, 0.75, false
    );

    @Override
    public IceApplicationProvider.AppType getAppType() {
        return AclivaTestApplication;
    }

    @Override
    public IceApplicationProvider.IceApp create(ApplicationContext parentContext) throws IOException {

        // assigning parent context?

        final DeviceListModel deviceListModel = parentContext.getBean("deviceListModel", DeviceListModel.class);
		
		final NumericFxList numericList = parentContext.getBean("numericList", NumericFxList.class);
		
		final SampleArrayFxList sampleList = parentContext.getBean("sampleArrayList", SampleArrayFxList.class);
		
		final Subscriber subscriber = (Subscriber) parentContext.getBean("subscriber");

        final EventLoop eventLoop = (EventLoop) parentContext.getBean("eventLoop");
        
        final VitalModel vitalModel= (VitalModel) parentContext.getBean("vitalModel");
        
        //"flowRateObjectiveWriter" is a new bean in IceAppContainerContext.xml
        final FlowRateObjectiveDataWriter objectiveWriter=(FlowRateObjectiveDataWriter) parentContext.getBean("flowRateObjectiveWriter");
        
        final InfusionObjectiveDataWriter infusionObjectiveWriter =
                (InfusionObjectiveDataWriter) parentContext.getBean("objectiveWriter");

        final InfusionProgramDataWriter infusionProgramWriter =
            (InfusionProgramDataWriter) parentContext.getBean("infusionPumpProgramWriter");
        
        final MDSHandler mdsHandler=(MDSHandler)parentContext.getBean("mdsConnectivity",MDSHandler.class);
        mdsHandler.start();

        final EMRFacade emr=(EMRFacade) parentContext.getBean("emr");

        // Load FXML
        FXMLLoader loader = new FXMLLoader(AclivaTestApplication.class.getResource("AclivaTestApplication.fxml"));

        final Parent ui = loader.load();
       
        final AclivaTestApplication controller = ((AclivaTestApplication) loader.getController());
        
        controller.set(parentContext, deviceListModel, numericList, sampleList, objectiveWriter, infusionObjectiveWriter, infusionProgramWriter, mdsHandler, vitalModel, subscriber, emr);
        
        controller.start(eventLoop, subscriber);

        return new IceApplicationProvider.IceApp() {

			@Override
			public AppType getDescriptor() {
				return AclivaTestApplication;
			}

			@Override
			public Parent getUI() {
				return ui;
			}

			@Override
			public void activate(ApplicationContext context) {
				// controller.activate();
				
			}

			@Override
			public void stop() throws Exception {
				// controller.stop();
				
			}

			@Override
			public void destroy() throws Exception {
				// controller.destroy();
				
			}
			
			@Override
			public int getPreferredWidth() {
				return 1440;
			}
			
			public int getPreferredHeight() {
				return 1000;
			}
			
		};

    }

}
