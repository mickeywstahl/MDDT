package org.mdpnp.apps.testapp.poclab;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.rtiapi.data.EventLoop;

import com.rti.dds.subscription.Subscriber;

import docbox.MDSDataWriter;
import docbox.NumericObservedValueDataWriter;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * An Abaxis Piccolo Xpress point-of-care lab device simulator...
 * 
 * @author Braga Aroulmozhi
 *
 */
public class PiccoloXpressSimulator {
	
	private DeviceListModel deviceListModel;
	private NumericFxList numericList;
	private SampleArrayFxList sampleList;
	private MDSDataWriter docBoxMDSWriter;
	private NumericObservedValueDataWriter docBoxNOVWriter;
	
	public Button startButton;
	public TextField mdsIDField;
	
	@FXML Label instrumentqcLabel, chemistryqcLabel, hemolysisLabel, lipemiaLabel;
	
	@FXML Label sodiumLabel, potassiumLabel, glucoseLabel, albuminLabel, bilirubinLabel, astLabel, altLabel, alpLabel, gfrLabel, bunLabel, calciumLabel, proteinLabel, chlorideLabel, co2Label, creatinineLabel ;
	
	@FXML CheckBox notfastedLabel, qcfailurelabel, diabetesLabel, renalfailureLabel;

	public void set() {
		
	}

	public void start(EventLoop eventLoop, Subscriber subscriber) {
		//Nothing special to do at the moment...
		
	}
	
	public void activate() {
		// TODO Auto-generated method stub
		
	}

	public void stop() {
		// TODO Auto-generated method stub
		
	}

	public void destroy() {
		// TODO Auto-generated method stub
		
	}

	public void startProcess() {
//		System.err.println("We just created a new sample");
		// Check for QC Failure
		if (qcfailurelabel.isSelected()) {
			instrumentqcLabel.setText("Instrument QC: FAIL" );
			chemistryqcLabel.setText("Chemistry QC: FAIL" );
			hemolysisLabel.setText("" );
			lipemiaLabel.setText("" );
			sodiumLabel.setText("" ); 
			potassiumLabel.setText("" ); 
			glucoseLabel.setText("" );
			albuminLabel.setText("" );
			bilirubinLabel.setText("" );
			astLabel.setText("" );
			altLabel.setText("" );
			alpLabel.setText("" );
			gfrLabel.setText("" );
			bunLabel.setText("" );
			calciumLabel.setText("" );
			proteinLabel.setText("" );
			chlorideLabel.setText("" );
			co2Label.setText("" );
			creatinineLabel.setText("" );
			
			return;
		}else {
			instrumentqcLabel.setText("Instrument QC: OK" );
			chemistryqcLabel.setText("Chemistry QC: OK" );
		}
		
		//HEM value
		float hemoIndex = randomValueGenerator(0f, 20f);
		hemolysisLabel.setText("Hemolysis Index: " + Float.toString(hemoIndex));
		
		//Check for patient fasted status
		float lipemiaIndex = 0f;
		if (notfastedLabel.isSelected()) {
			lipemiaIndex = randomValueGenerator(35f, 100f);
			lipemiaLabel.setText("Lipemia Index: " + Float.toString(lipemiaIndex) + " (Moderate - High)");
		}else {
			lipemiaIndex = randomValueGenerator(1f, 35f);
			lipemiaLabel.setText("Lipemia Index: " + Float.toString(lipemiaIndex) + " (Normal)");
		}
		
		
		//Check for renal failure
		float BUN = 0f;
		float creatinine = 0f;
		float potassium = 0f;
		if (renalfailureLabel.isSelected()) {
			BUN = randomValueGenerator(22f, 50f);
			bunLabel.setText(Float.toString(BUN) + " (High)");
			potassium = randomValueGenerator(5.1f, 12f);
			potassiumLabel.setText(Float.toString(potassium) + " (High)");
			creatinine = randomValueGenerator(1.2f, 3f);
			creatinineLabel.setText(Float.toString(creatinine) + " (High)");
		}else {
			BUN = randomValueGenerator(7f, 22f);
			bunLabel.setText(Float.toString(BUN));
			potassium = randomValueGenerator(3.6f, 5.1f);
			potassiumLabel.setText(Float.toString(potassium));
			creatinine = randomValueGenerator(0.6f, 1.2f);
			creatinineLabel.setText(Float.toString(creatinine));
		}
		
		
		
		
		//Check for Diabetes
		float glucose = 0f;
		if(diabetesLabel.isSelected()) {
			glucose = randomValueGenerator(120f, 170f);
			glucoseLabel.setText(Float.toString(glucose) + " (High)");
		}else {
			glucose = randomValueGenerator(73f, 118f);
			glucoseLabel.setText(Float.toString(glucose));
		}
		
		
		//Set other result values
		float sodium = randomValueGenerator(128f, 145f);
		sodiumLabel.setText(Float.toString(sodium));		
		
		float co2 = randomValueGenerator(18f, 33f);
		co2Label.setText(Float.toString(co2));
		
		float chloride = randomValueGenerator(98f, 108f);
		chlorideLabel.setText(Float.toString(chloride));		
		
		float calcium = randomValueGenerator(8.0f, 10.3f);
		calciumLabel.setText(Float.toString(calcium));
		
		float gfr = randomValueGenerator(70f, 100f);
		gfrLabel.setText(Float.toString(gfr));
		
		float alp = randomValueGenerator(42f, 141f);
		alpLabel.setText(Float.toString(alp));
				
		float alt = randomValueGenerator(10f, 47f);
		altLabel.setText(Float.toString(alt));
		
		float ast = randomValueGenerator(11f, 38f);
		astLabel.setText(Float.toString(ast));
		
		float bilirubin = randomValueGenerator(0.2f, 1.6f);
		bilirubinLabel.setText(Float.toString(bilirubin));
		
		float albumin = randomValueGenerator(3.3f, 5.5f);
		albuminLabel.setText(Float.toString(albumin));
		
		float protein = randomValueGenerator(6.4f, 8.1f);
		proteinLabel.setText(Float.toString(protein));
		
		
		/*
		 * List of variables to potentially push to EMR:
		 * 
		 * sodium
		 * potassium
		 * co2
		 * chloride
		 * glucose
		 * calcium
		 * BUN
		 * creatinine
		 * gfr
		 * alp
		 * alt
		 * ast
		 * bilirubin
		 * albumin
		 * protein
		 * 
		 * hemoIndex
		 * lipemiaIndex
		 * */
	}
	
	public static float randomValueGenerator(float min, float max) {
		float randFloat = (float) ( Math.round((min + (max - min)* Math.random()) *10.0 ) /10.0 );
		
		return randFloat;
		
	}
}
