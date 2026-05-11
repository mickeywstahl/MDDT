package org.mdpnp.apps.testapp.datavalidation;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.paint.Color;
import javafx.util.Callback;

import org.mdpnp.apps.fxbeans.SampleArrayFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.guis.waveform.SampleArrayWaveformSource;
import org.mdpnp.guis.waveform.javafx.JavaFXWaveformPane;
import org.springframework.context.ApplicationContext;

/**
 * DataValidationApp
 * =================
 * JavaFX Controller for the Data Validation OpenICE application.
 */
public class DataValidationApp {

    @FXML private ComboBox<SampleArrayFx> waveformSelector;
    @FXML private JavaFXWaveformPane rawWaveformPane;
    @FXML private JavaFXWaveformPane validationSignalPane;
    @FXML private JavaFXWaveformPane validatedWaveformPane;

    private SampleArrayFxList sampleList;
    private SampleArrayWaveformSource source;

    public void set(SampleArrayFxList sampleList) {
        this.sampleList = sampleList;
        waveformSelector.setItems(sampleList);
    }

    public void initialize() {
        // Customize how items are rendered in the dropdown list
        waveformSelector.setCellFactory(new Callback<ListView<SampleArrayFx>, ListCell<SampleArrayFx>>() {
            @Override
            public ListCell<SampleArrayFx> call(ListView<SampleArrayFx> param) {
                return new ListCell<SampleArrayFx>() {
                    @Override
                    protected void updateItem(SampleArrayFx item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                        } else {
                            setText(item.getMetric_id() + " [" + item.getUnique_device_identifier() + "]");
                        }
                    }
                };
            }
        });

        waveformSelector.setButtonCell(waveformSelector.getCellFactory().call(null));

        // When a new stream is selected, update the source for all charts
        waveformSelector.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<SampleArrayFx>() {
            @Override
            public void changed(ObservableValue<? extends SampleArrayFx> observable, SampleArrayFx oldValue, SampleArrayFx newValue) {
                if (sampleList != null && newValue != null) {
                    ice.SampleArray keyHolder = new ice.SampleArray();
                    sampleList.getReader().get_key_value(keyHolder, newValue.getHandle());
                    source = new SampleArrayWaveformSource(sampleList.getReader(), keyHolder);
                } else {
                    source = null;
                }
                
                rawWaveformPane.setSource(source);
                validationSignalPane.setSource(source);
                validatedWaveformPane.setSource(source);
            }
        });

        // Set bright stroke colors so the waveforms are visible against the dark background
        rawWaveformPane.getCanvas().getGraphicsContext2D().setStroke(Color.GREEN);
        rawWaveformPane.getCanvas().getGraphicsContext2D().setLineWidth(1.5);
        
        validationSignalPane.getCanvas().getGraphicsContext2D().setStroke(Color.YELLOW);
        validationSignalPane.getCanvas().getGraphicsContext2D().setLineWidth(1.5);
        
        validatedWaveformPane.getCanvas().getGraphicsContext2D().setStroke(Color.CYAN);
        validatedWaveformPane.getCanvas().getGraphicsContext2D().setLineWidth(1.5);

        // Turn off overwrite mode to make the waveforms scroll continuously leftward
        rawWaveformPane.setOverwrite(false);
        validationSignalPane.setOverwrite(false);
        validatedWaveformPane.setOverwrite(false);

        // Start the native renderers
        rawWaveformPane.start();
        validationSignalPane.start();
        validatedWaveformPane.start();
    }

    public void activate(ApplicationContext context) {
        rawWaveformPane.start();
        validationSignalPane.start();
        validatedWaveformPane.start();
    }

    public void stop() {
        rawWaveformPane.stop();
        validationSignalPane.stop();
        validatedWaveformPane.stop();
    }

    public void destroy() {
        stop();
    }
}
