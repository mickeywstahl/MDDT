package org.mdpnp.apps.testapp.patient;

import java.util.Date;

import javafx.beans.property.SimpleStringProperty;

/**
 * Extension of the basic patient info, for patients whose info is loaded
 * from OpenEMR.  We extend this so we can set additional properties on these
 * patients such as their uid or anything else we might want from OpenEMR.
 */
public class OpenEMRPatientInfo extends PatientInfo {
	
	private SimpleStringProperty uuidProperty = new SimpleStringProperty();

	public OpenEMRPatientInfo(String mrn, String fn, String ln, Gender g, Date d, String uuid) {
		super(mrn, fn, ln, g, d);
		uuidProperty.set(uuid);
	}
	
	public String getUUID() {
		return uuidProperty.get();
	}
	
	
	

}
