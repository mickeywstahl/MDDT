package org.mdpnp.apps.testapp.openemr7;

import java.io.IOException;
import java.net.URL;

import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.devices.MDSHandler;
import org.springframework.context.ApplicationContext;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

public class OpenEMRDataTransferApplicationFactory implements IceApplicationProvider {
	
	private IceApplicationProvider.AppType type=new IceApplicationProvider.AppType(
		"OpenEMRTransfer", "NoEMRTransfer", (URL) OpenEMRDataTransferApplicationFactory.class.getResource("openemr.png"), 0.75, false
	);

	public OpenEMRDataTransferApplicationFactory() {
		// TODO Auto-generated constructor stub
	}

	@Override
	public AppType getAppType() {
		return type;
	}

	@Override
	public IceApp create(ApplicationContext parentContext) throws IOException {
		
		final EMRFacade emr = (EMRFacade) parentContext.getBean("emr");

		FXMLLoader loader = new FXMLLoader(OpenEMRDataTransferApplication.class.getResource("OpenEMRDataTransferApplication.fxml"));

        final Parent ui = loader.load();
        
        final OpenEMRDataTransferApplication controller = ((OpenEMRDataTransferApplication) loader.getController());

        final MDSHandler mdsHandler=(MDSHandler)parentContext.getBean("mdsConnectivity",MDSHandler.class);
        mdsHandler.start();
        
        controller.set(mdsHandler, emr);
        controller.start();
		
		return new IceApp() {
			
			

			@Override
			public AppType getDescriptor() {
				// TODO Auto-generated method stub
				return type;
			}

			@Override
			public Parent getUI() {
				return ui;
			}

			@Override
			public void activate(ApplicationContext context) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void stop() throws Exception {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void destroy() throws Exception {
				// TODO Auto-generated method stub
				
			}
			
		};
	}

}
