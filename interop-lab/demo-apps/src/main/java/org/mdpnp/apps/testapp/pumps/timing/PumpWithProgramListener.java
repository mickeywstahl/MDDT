package org.mdpnp.apps.testapp.pumps.timing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;

import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.testapp.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.infrastructure.InstanceHandle_t;

import ice.FlowRateObjectiveDataWriter;
import ice.InfusionProgramDataWriter;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;

public class PumpWithProgramListener {
	
	@FXML Label pumpId;
	@FXML Label currentSpeedLabel;
	@FXML Label currentFileLabel;
	//@FXML CheckBox selected; 
	@FXML HBox main;
		
	private Device pump;
	private InfusionProgramDataWriter writer;
	private Connection dbconn;
	private PreparedStatement controlStatement;
	
	private File timingsFile;
	
	private static final Logger log = LoggerFactory.getLogger(PumpWithProgramListener.class);
	private final String FLOW_RATE=rosetta.MDC_FLOW_FLUID_PUMP.VALUE;
	
	
	public void initialize() {
		
	}
	
	class NumericValueChangeListener implements ChangeListener<Number> {

		@Override
		public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) {
			// TODO Auto-generated method stub
			System.err.println("pumpSpeedListener newValue is "+newValue.floatValue());
			currentSpeedLabel.setText(Float.toString(newValue.floatValue()));
		}
		
	}
	
	NumericValueChangeListener pumpSpeedListener=new NumericValueChangeListener();
	
	public void setPump(Device pump, NumericFxList list, InfusionProgramDataWriter writer, Connection dbconn) {
		this.pump=pump;
		this.writer=writer;
		this.dbconn=dbconn;
		
		pumpId.textProperty().bind(pump.comPortProperty());
		
		list.forEach( n -> {
			log.info("handleDeviceChange numeric dev ident is "+n.getUnique_device_identifier()+" "+n.getMetric_id());
			if( ! n.getUnique_device_identifier().equals(pump.getUDI())) return;	//Some other device
			//When we get here, we are looking at a property for the currently selected device
			if(n.getMetric_id().equals(FLOW_RATE)) {
				currentSpeedLabel.setText(Integer.toString((int)n.getValue()));
				n.valueProperty().addListener(pumpSpeedListener);
				System.err.println("Added pump speed listener to "+pump.getComPort());
			}
		});

	}
	

	public void setTheFlowRate(TimeAndRate tr) {
		ice.InfusionProgram objective=new ice.InfusionProgram();
		objective.infusionRate=tr.rate;
		objective.head=tr.head;
		objective.unique_device_identifier=pump.getUDI();
		objective.requestor="PumpTimingApp";
		/*
		 * For the time being, we aren't supporting bolus etc. in these scripts,
		 * but the AP4000 can expect -1 in some fields.
		 */
		objective.bolusRate=-1;
		objective.bolusVolume=-1;
		objective.seconds=-1;
		writer.write(objective, InstanceHandle_t.HANDLE_NIL);
		log.info("Published an infusion program "+tr.toString());
	}
	
	public void selectFile() {
		FileChooser chooser=new FileChooser();
		chooser.setTitle("Select speed control file");
		File f=chooser.showOpenDialog(main.getScene().getWindow());
		if(f!=null) {
			timingsFile=f;
			currentFileLabel.setText(f.getName());
		}
	}
	
	void runTimings() {
		
	}
	
	/**
	 * It barely seems worth a class, but it is one...
	 * @author Simon
	 *
	 */
	class TimeAndRate {
		/**
		 * How long to sleep before asking for the given rate
		 */
		long interval;
		/**
		 * The rate to ask for.
		 */
		float rate;
		/**
		 * The head to apply the flow rate to.
		 */
		int head;
		
		@Override
		public String toString() {
			return String.format("%.2f,%d", rate,head);
		}
	}
	
	private ArrayList<TimeAndRate> timesAndRates=new ArrayList<>();

	public void startSettingSpeeds() throws IOException {
		BufferedReader br=new BufferedReader(new FileReader(timingsFile));
		String nextLine;
		while( (nextLine=br.readLine())!=null) {
			if( nextLine.length()==0 || nextLine.startsWith("#") || (nextLine.indexOf(',')==-1) ) {
				System.err.println("Skipping line "+nextLine);
				continue;
			}
			String[] parts=nextLine.trim().split(",");
			TimeAndRate tr=new TimeAndRate();
			tr.interval=Long.parseLong(parts[0].trim());
			tr.rate=Float.parseFloat(parts[1].trim());
			if(parts.length==3) {
				tr.head=Integer.parseInt(parts[2].trim());
			} else {
				/*
				 * Most devices don't have more than one head anyway,
				 * and will just ignore this number.
				 */
				tr.head=1;
			}
			timesAndRates.add(tr);
		}
		br.close();
		System.err.println("Full sequence is ");
		for(int i=0;i<timesAndRates.size();i++) {
			System.err.println(timesAndRates.get(i));
		}
		//Now we have a full ArrayList of times and rates.
		//We need to make this a separate runnable, because
		//otherwise the sleeps cause the GUI to hang.
		Thread setterThread=new Thread() {
			public void run() {
				for(int i=0;i<timesAndRates.size();i++) {
					TimeAndRate tr=timesAndRates.get(i);
					try {
						Thread.sleep(tr.interval);
					} catch (InterruptedException ie) {
						ie.printStackTrace();
					}
					//Now we've slept that long, set the rate...
					setTheFlowRate(tr);
				}
			}
		};
		setterThread.start();
		
	}
	


}
