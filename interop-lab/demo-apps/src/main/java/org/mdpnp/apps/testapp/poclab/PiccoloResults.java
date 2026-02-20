package org.mdpnp.apps.testapp.poclab;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.Callback;

public class PiccoloResults {

	public PiccoloResults() {
		// TODO Auto-generated constructor stub
	}
	
	final PiccoloResultModel sodiumModel = new PiccoloResultModel("Sodium", "2951-2", "Sodium SerPl-sCnc", null,
			"mmol/L", 128, 145, "");
	final PiccoloResultModel potassiumModel = new PiccoloResultModel("Potassium", "2823-3", "Potassium SerPl-sCnc",
			null, "mmol/L", 3.6f, 5.1f, "");
	final PiccoloResultModel co2Model = new PiccoloResultModel("CO\u2082", "2028-9", "CO2 SerPl-sCnc", null, "mmol/L",
			18, 33, "");
	final PiccoloResultModel chlorideModel = new PiccoloResultModel("Chloride", "2075-0", "Chloride SerPl-sCnc", null,
			"mmol/L", 98, 108, "");
	final PiccoloResultModel glucoseModel = new PiccoloResultModel("Glucose", "2345-7", "Glucose SerPl-mCnc", null,
			"mg/dL", 73, 118, "");
	final PiccoloResultModel calciumModel = new PiccoloResultModel("Calcium", "17861-6", "Calcium SerPl-mCnc", null,
			"mg/dL", 8.0f, 10.3f, "");
	final PiccoloResultModel bunModel = new PiccoloResultModel("BUN", "3094-0", "BUN SerPl-mCnc", null, "mg/dL", 7, 22,
			"");
	final PiccoloResultModel creatinineModel = new PiccoloResultModel("Creatinine", "2160-0", "Creat SerPl-mCnc", null,
			"mg/dL", 0.6f, 1.2f, "");
	final PiccoloResultModel alpModel = new PiccoloResultModel("ALP", "6768-6", "ALP SerPl-cCnc", null, "U/L", 42, 141,
			"");
	final PiccoloResultModel altModel = new PiccoloResultModel("ALT", "1742-6", "ALT SerPl-cCnc", null, "U/L", 10, 47,
			"");
	final PiccoloResultModel astModel = new PiccoloResultModel("AST", "1920-8", "AST SerPl-cCnc", null, "U/L", 11, 38,
			"");
	final PiccoloResultModel bilirubinModel = new PiccoloResultModel("Bilirubin", "1975-2", "Bilirub SerPl-mCnc", null,
			"mg/dL", 0.2f, 1.6f, "");
	final PiccoloResultModel albuminModel = new PiccoloResultModel("Albumin", "1751-7", "Alb SerPl-mCnc", null, "g/dL",
			3.3f, 5.5f, "");
	final PiccoloResultModel proteinModel = new PiccoloResultModel("Total Protein", "2885-2", "Prot SerPl-mCnc", null,
			"g/dL", 6.4f, 8.1f, "");
	
	public PiccoloResultModel getSodiumModel() {
		return sodiumModel;
	}

	public PiccoloResultModel getPotassiumModel() {
		return potassiumModel;
	}

	public PiccoloResultModel getCo2Model() {
		return co2Model;
	}

	public PiccoloResultModel getChlorideModel() {
		return chlorideModel;
	}

	public PiccoloResultModel getGlucoseModel() {
		return glucoseModel;
	}

	public PiccoloResultModel getCalciumModel() {
		return calciumModel;
	}

	public PiccoloResultModel getBunModel() {
		return bunModel;
	}

	public PiccoloResultModel getCreatinineModel() {
		return creatinineModel;
	}

	public PiccoloResultModel getAlpModel() {
		return alpModel;
	}

	public PiccoloResultModel getAltModel() {
		return altModel;
	}

	public PiccoloResultModel getAstModel() {
		return astModel;
	}

	public PiccoloResultModel getBilirubinModel() {
		return bilirubinModel;
	}

	public PiccoloResultModel getAlbuminModel() {
		return albuminModel;
	}

	public PiccoloResultModel getProteinModel() {
		return proteinModel;
	}

	
	
	public TableView<PiccoloResultModel> getPiccoloResultsTable() {

		TableView<PiccoloResultModel> tableView = new TableView<PiccoloResultModel>();
		ObservableList<PiccoloResultModel> allMeasurements = getAllMeasurements();
		tableView.setItems(allMeasurements);

		TableColumn<PiccoloResultModel, String> testNameColumn = new TableColumn<PiccoloResultModel, String>(
				"Test Name");
		testNameColumn.setCellValueFactory(new PropertyValueFactory<PiccoloResultModel, String>("label"));
		testNameColumn.setMinWidth(200);
		tableView.getColumns().add(testNameColumn);

		TableColumn<PiccoloResultModel, Float> valueColumn = new TableColumn<PiccoloResultModel, Float>("Value");
		valueColumn.setCellValueFactory(new PropertyValueFactory<PiccoloResultModel, Float>("value"));
		valueColumn.setMinWidth(200);
		/*
		 * A custom call factory. The only point of the override for updateItem is to
		 * "mask out" values of -1 because we use those as an initial value for the the
		 * PiccoloResultModel entries, because we know that no generated values from the
		 * Piccolo can ever be negative. When those values are present, we just want the
		 * corresponding cell to be empty, as the result hasn't been generated yet. We
		 * only need this for the value property.
		 */
		valueColumn.setCellFactory(
				new Callback<TableColumn<PiccoloResultModel, Float>, TableCell<PiccoloResultModel, Float>>() {

					@Override
					public TableCell<PiccoloResultModel, Float> call(TableColumn<PiccoloResultModel, Float> param) {
						// TODO Auto-generated method stub
						return new TableCell<PiccoloResultModel, Float>() {

							@Override
							protected void updateItem(Float item, boolean empty) {
								// TODO Auto-generated method stub
								float val = (item == null) ? -1f : item.floatValue();
								if (empty || val == -1f) {
									setText("");
								} else {
									setText(String.valueOf(val));
								}
							}
						};
					}
				});
		tableView.getColumns().add(valueColumn);

		TableColumn<PiccoloResultModel, Float> lowerColumn = new TableColumn<PiccoloResultModel, Float>(
				"Lower Range Limit");
		lowerColumn.setCellValueFactory(new PropertyValueFactory<PiccoloResultModel, Float>("lower"));
		lowerColumn.setMinWidth(150);
		tableView.getColumns().add(lowerColumn);

		TableColumn<PiccoloResultModel, Float> upperColumn = new TableColumn<PiccoloResultModel, Float>(
				"Upper Range Limit");
		upperColumn.setCellValueFactory(new PropertyValueFactory<PiccoloResultModel, Float>("upper"));
		upperColumn.setMinWidth(150);
		tableView.getColumns().add(upperColumn);

		TableColumn<PiccoloResultModel, String> abnormalColumn = new TableColumn<PiccoloResultModel, String>(
				"Abnormal Flag");
		abnormalColumn.setCellValueFactory(new PropertyValueFactory<PiccoloResultModel, String>("abnormal"));
		abnormalColumn.setMinWidth(150);
		/*
		 * Another custom cell factory, this time for the abnormal column value. For
		 * this one, we compare the value of the abnormal property, and if it is set,
		 * and is not set to N (for normal) then we make the row in the table red...
		 */
		abnormalColumn.setCellFactory(
				new Callback<TableColumn<PiccoloResultModel, String>, TableCell<PiccoloResultModel, String>>() {

					@Override
					public TableCell<PiccoloResultModel, String> call(TableColumn<PiccoloResultModel, String> param) {
						return new TableCell<PiccoloResultModel, String>() {

							@Override
							protected void updateItem(String abnormal, boolean empty) {
								super.updateItem(abnormal, empty);
								if (abnormal != null && !empty) {
									setText(abnormal);
								} else {
									setText("");
								}
								TableRow<PiccoloResultModel> containingRow = getTableRow();
								if (containingRow == null) {
									return;
								}
								PiccoloResultModel result = containingRow.getItem();
								if (result != null) {
									// Need to check result here as could be null early on in rendering.
									if (abnormal != null && abnormal.length() > 0 && !abnormal.equals("N")) {
										containingRow.setStyle("-fx-background-color: #dd0000");
									} else {
										containingRow.setStyle("");
									}
								} else {
									// Result is null - don't set any style for now.
									containingRow.setStyle("");
								}
							}
						};
					};
				});
		tableView.getColumns().add(abnormalColumn);

		tableView.prefWidth(900);
		
		return tableView;

	}
	
	private ObservableList<PiccoloResultModel> getAllMeasurements() {
		ObservableList<PiccoloResultModel> returnList = FXCollections.observableArrayList();
		returnList.addAll(sodiumModel, potassiumModel, co2Model, chlorideModel, glucoseModel, calciumModel, bunModel,
				creatinineModel, alpModel, altModel, astModel, bilirubinModel, albuminModel, proteinModel);
		return returnList;
	}

}
