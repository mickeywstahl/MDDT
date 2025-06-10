package org.mdpnp.apps.testapp.pumps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.HashMap;

import org.mdpnp.apps.fxbeans.AlertFx;
import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
//import org.mdpnp.apps.fxbeans.patientAlertList;

import ice.AlarmPriority;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;


import javafx.collections.ListChangeListener;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

import javafx.util.Callback;

/**
 * A UI controller1 for the AP-4000.  This is to work in the PumpControllerTestApplication,
 * not to interface with the device itself.
 * 
 * @author Simon
 * 
 */
public class AP4000vINTController extends AbstractControllablePump {
	
	@FXML VBox veryTop;
	
	@FXML Label weightLabel;
	@FXML Label heightLabel;
	@FXML Label ageLabel;
	@FXML Label asaLabel;
	@FXML Label pumpSerialLabel;
	@FXML Label pumpUDILabel;
	@FXML Label modelLabel;
	//@FXML Label alarmLabel;
	@FXML TableView<AP4000Alarm> alertTable;
	
	private AlertFxList alertListMain;
	private NumericFxList numericListMain;
	private String pt_weight, pt_height, pt_age, pt_state;
	private HashMap<String, AP4000Alarm> alarmMap;

	@Override
	public int getChanneCount() {
		return 2;
	}
	
	
	@Override
	public void start() {
		
		populateAlarmList();
		
		setPatientInfo("PT_WEIGHT","PT_HEIGHT","PT_AGE","PT_STATE");
		this.alertListMain = alertList;
//		this.alertListMain = patientAlertList;
		this.numericListMain = numericList;
		alertListMain.addListener(new ListChangeListener<AlertFx>() {

			@Override
			public void onChanged(Change<? extends AlertFx> c) {
				while(c.next()) {
					c.getAddedSubList().forEach( a -> {
						a.getClass().getName();
						System.err.println(a);
						assign(a);
						
					});
				}
			}
		});
		alertListMain.forEach( a -> {
			a.getClass().getName();
			System.err.println("Alert "+a.getIdentifier());
			assign(a);
		});
		
		/*
		 *This is a test alarm that you can use to check that the table is working
		 */
		AlertFx high=new AlertFx();
		high.setIdentifier("PA.02");
		high.setText("AA!BB!CC");
		high.setSource_timestamp(new Date());
		high.setUnique_device_identifier("abc12345");
		alertListMain.add(high);
		
		AlertFx medium=new AlertFx();
		medium.setIdentifier("PSE.05");
		medium.setText("DD!EE!FF");
		medium.setSource_timestamp(new Date());
		medium.setUnique_device_identifier("abc12345");
		alertListMain.add(medium);
		
		AlertFx low=new AlertFx();
		low.setIdentifier("BA.01");
		low.setText("GG!HH!II");
		low.setSource_timestamp(new Date());
		low.setUnique_device_identifier("abc12345");
		alertListMain.add(low);
		
//		numericList.addListener(new ListChangeListener<NumericFx>() {
//
//			@Override
//			public void onChanged(Change<? extends NumericFx> c) {
//				while(c.next()) {
//					c.getAddedSubList().forEach( n -> {
//						n.getClass().getName();
//						System.err.println(n);
//						assign(n);
//					});
//				}
//			}
//		});
//		numericList.forEach( n -> {
//			n.getClass().getName();
//			System.err.println(n);
//			assign(n);
//		});

		/*
		alertTable.setRowFactory(new Callback<TableView<AP4000Alarm>, TableRow<AP4000Alarm>>() {

			@Override
			public TableRow<AP4000Alarm> call(TableView<AP4000Alarm> param) {
				// TODO Auto-generated method stub
				return null;
			}
			
		});
		*/
		alertTable.setRowFactory( row -> new TableRow<AP4000Alarm>() {

			@Override
			protected void updateItem(AP4000Alarm item, boolean empty) {
				Thread.dumpStack();
				if(empty) {
					super.updateItem(item, empty);
					return;
				}
				System.err.println("updateItem called for item "+item.toString());
				ObservableList<Node> children=getChildren();
				System.err.println("Children size is "+children.size());
//				children.forEach( n -> {
//					System.err.println("Node "+n.getClass().getName()+" has style "+n.getStyle());
//				});
				//System.err.println("Current style is "+getStyle());
				String[] ourStyle=new String[]{""};
				Color[] c=new Color[] {Color.BLACK};
				if(item.prioProperty.get()==ALARM_PRIORITY.HIGH) {
					System.err.println("set to red");
					ourStyle[0]="-fx-text-fill: red";
					c[0]=Color.RED;
				}
				if(item.prioProperty.get()==ALARM_PRIORITY.MEDIUM) {
					System.err.println("set to orange");
					ourStyle[0]="-fx-text-fill: orange";
					c[0]=Color.ORANGE;
				}
				if(item.prioProperty.get()==ALARM_PRIORITY.LOW) {
					System.err.println("set to yellow");
					ourStyle[0]="-fx-text-fill: yellow";
					c[0]=Color.YELLOW;
				}
				ObservableList<Node> childrenOfParent=getParent().getChildrenUnmodifiable();
				System.err.println("childrenOfParent size is "+childrenOfParent.size());
				childrenOfParent.forEach( n -> {
					if(n instanceof javafx.scene.control.Labeled) {
						javafx.scene.control.Labeled l=(javafx.scene.control.Labeled)n;
						if(l.getText()==null) {
							return;
						}
						System.err.println("child is instance of Labeled");
						System.err.println("text of child is "+l.getText());
						l.setStyle(ourStyle[0]);
						l.setTextFill(c[0]);
					}
					//System.err.println("Setting style on "+n.getClass().getName());
					
				});

				
			}
			

		});
		
		try {
			FXMLLoader loader1 = new FXMLLoader(AP4000vINTController.class.getResource("neurowave_ap-4000-vINT-channel.fxml"));
			Parent ui1 = loader1.load();
			AP4000vINTChannelController controller1=(AP4000vINTChannelController)loader1.getController();
			controller1.setDevice(device);
			controller1.setInfusionObjectiveWriter(infusionObjectiveWriter);
			controller1.setInfusionProgramDataWriter(infusionProgramDataWriter);
			controller1.setNumericFxList(numericList);
			controller1.setAlertFxList(alertList);
			controller1.setMyFlowRate("MDC_FLOW_FLUID_PUMP_1");
			controller1.setChannel(1);
			controller1.start();
			
			veryTop.getChildren().add(ui1);
			
			//Repeat for second channel
			FXMLLoader loader2 = new FXMLLoader(AP4000vINTController.class.getResource("neurowave_ap-4000-vINT-channel.fxml"));
			Parent ui2 = loader2.load();
			AP4000vINTChannelController controller2=(AP4000vINTChannelController)loader2.getController();
			controller2.setDevice(device);
			controller2.setInfusionObjectiveWriter(infusionObjectiveWriter);
			controller2.setInfusionProgramDataWriter(infusionProgramDataWriter);
			controller2.setNumericFxList(numericList);
			controller2.setAlertFxList(alertList);
			controller2.setMyFlowRate("MDC_FLOW_FLUID_PUMP_2");
			controller2.setChannel(2);
			controller2.start();
			
			veryTop.getChildren().add(ui2);
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		
	}

	private void setPatientInfo(String weight, String height, String age, String state) {
		this.pt_weight = weight;
		this.pt_height = height;
		this.pt_age = age;
		this.pt_state = state;
	}
	
	
	
	private void assign(AlertFx a) {
		if( ! a.getUnique_device_identifier().equals(device.getUDI())) {
			//Not our device
			return;
		}
		
		if(a.getIdentifier().equals("CASE_ID")) {
			//It seems VERY unlikely that this will change, but we'll handle it.
			addAlertListener(a,pumpSerialLabel);
		}
		if(a.getIdentifier().equals("UDI")) {
			addAlertListener(a, pumpUDILabel);
		}
		if(a.getIdentifier().equals("Model")) {
			addAlertListener(a, modelLabel);
		}
		if(a.getIdentifier().equals("Neurowave_PT_WEIGHT")) {
			addAlertListener(a, weightLabel);
		}
		if(a.getIdentifier().equals("Neurowave_PT_HEIGHT")) {
			addAlertListener(a, heightLabel);
		}
		if(a.getIdentifier().equals("Neurowave_PT_AGE")) {
			addAlertListener(a, ageLabel);
		}
		if(a.getIdentifier().equals("Neurowave_PT_STATUS")) {
//			Boss asked for ASA label to be removed for ASA demo - change corresponding fxml too!
//			addAlertListener(a, asaLabel);
			asaLabel.setText("");
			
		}
//		if(a.getIdentifier().equals("Current_Alarm")) {
//			addAlertListener(a, alarmLabel);
//		}
		
		if(a.getIdentifier().equals("PT_WEIGHT")) {
			//It seems VERY unlikely that this will change, but we'll handle it.
			System.err.println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Patient Weight CheckPoint ^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
			addAlertListener(a,weightLabel);
//			addListener(a,weightLabel);
		}
		
		//After handling other explicit cases above, check alarm keys against our map
		if(alarmMap.containsKey(a.getIdentifier())) {
			AP4000Alarm alarm=alarmMap.get(a.getIdentifier());
			if(alarm.priorityProperty().get()==ALARM_PRIORITY.NONE) {
				//Don't care
				return;
			}
			boolean[] there=new boolean[]{false};
			alertTable.getItems().forEach( existing -> {
				if(existing.codeProperty().get().equals(a.getIdentifier())) {
					/*
					 * Row already exists in the table.  Just in case the publish date/time has changed
					 * update it.
					 */
					existing.whenProperty().set(a.getSource_timestamp());
					there[0]=true;
					return;	//Don't do anything else with this code.	
				}
			});
			if(!there[0]) {
				//Alarm was not in the table.
				alarm.whenProperty().set(a.getSource_timestamp());
				alertTable.getItems().add(alarm);
				alertTable.refresh();
			}
		}
		
		
		
//		else {
//			System.err.println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Patient Weight CheckPoint Didn't Activate ^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
//		}
	}
	
	private void assign(NumericFx a) {
		if( ! a.getUnique_device_identifier().equals(device.getUDI())) {
			//Not our device
			return;
		}
		
		if(a.getMetric_id().equals("PT_WEIGHT")) {
			//It seems VERY unlikely that this will change, but we'll handle it.
			System.err.println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Patient Weight CheckPoint ^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
//			addAlertListener(a,weightLabel);
			addListener(a,weightLabel);
		}
		else {
			System.err.println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Patient Weight CheckPoint Didn't Activate ^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
		}
	}
	
	private void addAlertListener(AlertFx a, Label targetLabel) {
		if(targetLabel.getUserData()==null) {
			//Set the user data to the base text of the label, so that we can use the base text
			//as the description of the label.
			targetLabel.setUserData(targetLabel.getText());
		}

		targetLabel.textProperty().bindBidirectional(a.textProperty(), new StringConverter<String>() {

			@Override
			public String toString(String object) {
				return targetLabel.getUserData()+" "+object;
			}

			@Override
			public String fromString(String string) {
				System.err.println("stringconverter fromString called with "+string);
				return null;
			}
			
		});	
	}
	
	private void addListener(NumericFx n, Label targetLabel) {
	
		
		if(targetLabel.getUserData()==null) {
			//Set the user data to the base text of the label, so that we can use the base text
			//as the description of the label.
			targetLabel.setUserData(targetLabel.getText());
		}
		
		targetLabel.textProperty().bindBidirectional(n.presentation_timeProperty(), new StringConverter<Date>() {

			@Override
			public String toString(Date object) {
				return targetLabel.getUserData()+" "+n.getValue();
			}

			@Override
			public Date fromString(String string) {
				System.err.println("stringconverter fromString called with "+string);
				return null;
			}
			
		});
	}
		
	private void populateAlarmList() {
		alarmMap=new HashMap<>();
		InputStream stream=AP4000vINTController.class.getResourceAsStream("ap4000-alarms.csv");
		if(stream==null) {
			//file not found, accessible etc.
			return;
		}
		BufferedReader br=new BufferedReader(new InputStreamReader(stream));
		String line;
		try {
			while( (line=br.readLine())!=null) {
				String[] fields=line.split("\t");
				AP4000Alarm alarm=new AP4000Alarm(fields[0], fields[1], ALARM_PRIORITY.valueOf(fields[2]));
				alarmMap.put(fields[0], alarm);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//System.err.println(alarmMap.toString());
	}
	
	public enum ALARM_PRIORITY {
		NONE,
		LOW,
		MEDIUM,
		HIGH
	}
	
	/**
	 * A class to represent the alarm info produced by AP4000, according to mappings provided
	 * by the vendor.  We include the when Date field so we can set the date on the alarm later
	 * when it actually occurs.  This allows us to use this class as the type for the alarm
	 * display table.
	 * @author simon
	 *
	 */
	public class AP4000Alarm {
		
		public AP4000Alarm(String code, String description, ALARM_PRIORITY priority) {
			super();
			codeProperty().set(code);
			descriptionProperty().set(description);
			whenProperty().set(null);
			priorityProperty().set(priority);
		}
		@Override
		public String toString() {
			return codeProperty().get()+","+descriptionProperty().get()+","+priorityProperty().get();
		}
		
		private SimpleStringProperty codeProperty;
		private SimpleStringProperty descProperty;
		private SimpleObjectProperty<Date> whenProperty;
		private SimpleObjectProperty<ALARM_PRIORITY> prioProperty;
		
		public SimpleStringProperty codeProperty() {
			if(codeProperty==null) {
				codeProperty=new SimpleStringProperty(this,"code");
			}
			return codeProperty;
		}
		public String getCode() {
			return codeProperty.get();
		}
		public void setCode(String code) {
			codeProperty.set(code);
		}
		
		public SimpleStringProperty descriptionProperty() {
			if(descProperty==null) {
				descProperty=new SimpleStringProperty(this,"description");
			}
			return descProperty;
		}
		public String getDescription() {
			return descriptionProperty().get();
		}
		public void setDescription(String description) {
			descriptionProperty().set(description);
		}
		
		public SimpleObjectProperty<Date> whenProperty() {
			if(whenProperty==null) {
				whenProperty=new SimpleObjectProperty<>(this,"when");
			}
			return whenProperty;
		}
		public SimpleObjectProperty<ALARM_PRIORITY> priorityProperty() {
			if(prioProperty==null) {
				prioProperty=new SimpleObjectProperty<>(this,"priority");
			}
			return prioProperty;
		}

	}
	
}
