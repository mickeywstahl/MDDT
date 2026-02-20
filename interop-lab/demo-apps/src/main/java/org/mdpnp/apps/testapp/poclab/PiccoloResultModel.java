package org.mdpnp.apps.testapp.poclab;

import javafx.beans.property.FloatProperty;
import javafx.beans.property.SimpleFloatProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * This class represents the result of a Piccolo blood test for a particular metric such as
 * Sodium or Potassium.  It has properties for the value type, which for all known Piccolo
 * results is NM for numeric (see the HL7 specs for details, the best reference I found for
 * lookup was <a href="https://hl7-definition.caristix.com/v2/HL7v2.5">Caristix</a>),
 * the LOINC code, the coding system (defaults to LOINC as that seems to be what all Piccolo
 * results use), the Piccolo assigned label, the units for the measurement, the lower and upper
 * limits for the measurement.
 * 
 * Important parameters are defined using Observable properties so that they can be listened to
 * for changes.
 * 
 */
public class PiccoloResultModel {

	public PiccoloResultModel() {
		// TODO Auto-generated constructor stub
	}
	
	/**
	 * The measurement type, defaults to NM for Numeric
	 */
	String valueType = "NM";
	/**
	 * The LOINC code, e.g. 2951-2 for Sodium
	 */
	String loinc;
	/**
	 * The coding system, defaults to LN for LOINC
	 */
	String codingSystem = "LN";
	/**
	 * The label that Piccolo assign, such as Sodium SerPl-sCnc
	 */
	String picLabel;
	/**
	 * Units for the measurement, e.g. mg/dL
	 */
	String units;
	
	/**
	 * Result status, defaults to R for Results entered -- not verified
	 */
	String resultStatus = "R";

	/**
	 * Generate a new instance of the class with given values
	 * 
	 * @param label        simple label, e.g. Sodium
	 * @param loinc        the LOINC code, e.g. 2951-2
	 * @param picLabel     the label from the Piccolo, e.g. Sodium SerPl-sCnc
	 * @param value        the measurement value, e.g. 137
	 * @param units        the measurement units, e.g. mg/dL
	 * @param lower        the lower limit of the normal range of the value, e.g. 128
	 * @param upper		   the upper limit of the normal range of the value, e.g. 145
	 * @param abnormalFlag the status of the measurement e.g. N for normal or H for
	 *                     high.
	 */
	public PiccoloResultModel(String label, String loinc, String picLabel, Float value, String units, float lower,
			float upper, String abnormalFlag) {
		this.labelProperty().set(label);
		this.loinc = loinc;
		this.picLabel = picLabel;
		/*
		 * It's not possible to have negative values for any of the parameters from the
		 * Piccolo. So we use -1 here as the default, and then mask that to an empty
		 * String in the cell factory. Can we ever have a null for a property?
		 */
		if (value != null) {
			this.valueProperty().set(value);
		} else {
			this.valueProperty().set(-1f);
		}
		this.units = units;
		this.abnormalProperty().set(abnormalFlag);
		this.lowerProperty().set(lower);
		this.upperProperty().set(upper);
	}

	private StringProperty labelProperty;

	public void setLabel(String label) {
		labelProperty().set(label);
	}

	public String getLabel() {
		return labelProperty.get();
	}

	public StringProperty labelProperty() {
		if (labelProperty == null) {
			labelProperty = new SimpleStringProperty(this, "label");
		}
		return labelProperty;
	}

	private FloatProperty valueProperty;

	public void setValue(float value) {
		valueProperty().set(value);
		setAbnormalFlag();
	}

	public float getValue() {
		return valueProperty().get();
	}

	public FloatProperty valueProperty() {
		if (valueProperty == null) {
			valueProperty = new SimpleFloatProperty(this, "value");
		}
		return valueProperty;
	}

	private FloatProperty lowerProperty;

	public void setLower(float lower) {
		lowerProperty().set(lower);
	}

	public float getLower() {
		return lowerProperty().get();
	}

	public FloatProperty lowerProperty() {
		if (lowerProperty == null) {
			lowerProperty = new SimpleFloatProperty(this, "lower");
		}
		return lowerProperty;
	}

	private FloatProperty upperProperty;

	public void setUpper(float upper) {
		upperProperty().set(upper);
	}

	public float getUpper() {
		return upperProperty().get();
	}

	public FloatProperty upperProperty() {
		if (upperProperty == null) {
			upperProperty = new SimpleFloatProperty(this, "upper");
		}
		return upperProperty;
	}

	private StringProperty abnormalProperty;

	public void setAbnormalProperty(String abnormal) {
		abnormalProperty().set(abnormal);
	}

	public String getAbnormal() {
		return abnormalProperty().get();
	}

	public StringProperty abnormalProperty() {
		if (abnormalProperty == null) {
			abnormalProperty = new SimpleStringProperty(this, "abnormal");
		}
		return abnormalProperty;
	}

	private void setAbnormalFlag() {
		float lower = lowerProperty().get();
		float upper = upperProperty().get();
		float value = valueProperty.get();
		if (value >= lower && value <= upper) {
			abnormalProperty().set("N");
			return;
		}
		if (value < lower) {
			abnormalProperty().set("L");
		}
		if (value > upper) {
			abnormalProperty.set("H");
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(valueType).append("|").append(loinc).append("^").append(getLabel())
				.append("^").append(codingSystem).append("^").append(picLabel).append("||").append(getValue())
				.append("|").append(units).append("|").append(getLower()).append(" to ").append(getUpper()).append("|")
				.append(abnormalProperty().get()).append("||").append(resultStatus);
		return sb.toString();
	}
	
	public String toStringPiccolo() {
		StringBuilder sb=new StringBuilder(loinc).append("^^LN^")
				.append(picLabel).append("|").append(getValue());
		/*
		 * The piccolo uses an asterisk to indicate in the value field
		 * if the value is out of range, in addition to the N (normal) or H (high)
		 * field that is a later field in the result line.  Here, we check that.
		 */
		String abnormalString=abnormalProperty().get();
		if( ! abnormalString.equals("N")) {
			//Result is not normal
			sb.append(" *");
		}
		sb.append("|").append(units).append("|").append(getLower()).append(" to ").append(getUpper())
		//Double pipe on the next line is because there is no "nature of abnormality testing"
		.append(abnormalString).append("||")
		.append("R");	//R for "previously transmitted" - should we be able to specify F for final here?
		
		return sb.toString();
	}

}
