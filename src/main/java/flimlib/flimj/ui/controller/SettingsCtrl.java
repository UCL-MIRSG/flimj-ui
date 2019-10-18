package flimlib.flimj.ui.controller;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.StringConverter;
import net.imagej.Dataset;
import flimlib.NoiseType;
import flimlib.flimj.FitParams;
import flimlib.flimj.FitResults;
import flimlib.flimj.ui.FitProcessor;
import flimlib.flimj.ui.Utils;
import flimlib.flimj.ui.FitProcessor.FitType;
import flimlib.flimj.ui.controls.NumericSpinner;
import flimlib.flimj.ui.controls.NumericTextField;

import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;

/**
 * The controller of the "Settings" tab.
 */
public class SettingsCtrl extends AbstractCtrl {

	@FXML
	private NumericSpinner binSizeSpinner, iThreshSpinner;

	@FXML
	private NumericTextField chisqTgtTextField;

	@FXML
	private TextField chisqTextField;

	@FXML
	private ChoiceBox<NoiseType> noiseChoiceBox;

	@FXML
	private ChoiceBox<FitType> algoChoiceBox;

	@FXML
	private ChoiceBox<String> irfChoiceBox;

	@FXML
	private ChoiceBox<Integer> nCompChoiceBox;

	@FXML
	private GridPane paramPane;

	@FXML
	private Button fitButton;

	/** The list of all parameter name labels */
	private List<Text> paramLabels;

	/** The list of all parameter value TextFields */
	private List<NumericTextField> paramValues;

	/** The list of all parameter fixing state CheckBox */
	private List<CheckBox> paramFixed;

	/** The list of dataset present under the current context */
	private List<Dataset> presentDatasets;

	@Override
	public void initialize(URL location, ResourceBundle resources) {
		paramLabels = new ArrayList<>();
		paramValues = new ArrayList<>();
		paramFixed = new ArrayList<>();
		// keep only the table header (remove the preview parameters)
		paramPane.getChildren().removeIf(child -> GridPane.getRowIndex(child) > 0);

		// numerical fields
		iThreshSpinner.setMin(0.0);
		iThreshSpinner.setStepSize(1.0);
		iThreshSpinner.getNumberProperty().addListener((obs, oldVal, newVal) -> {
			getParams().iThresh = newVal.floatValue();
			requestUpdate();
		});

		binSizeSpinner.setIntOnly(true);
		binSizeSpinner.setMin(0.0);
		binSizeSpinner.setMax(255.0);
		binSizeSpinner.setStepSize(1.0);
		binSizeSpinner.getNumberProperty().addListener((obs, oldVal, newVal) -> {
			// binning will freeze the JFX thread and not allow the +/- event to be consumed
			// which causes indefinite +/- and resulting calls to setBinning()
			fp.submitRunnable(() -> {
				fp.setBinning(newVal.intValue());
				// update of UI components should be run from JFX thread
				Platform.runLater(() -> requestUpdate());
			});
		});

		chisqTgtTextField.getNumberProperty().addListener((obs, oldVal, newVal) -> {
			getParams().chisq_target = newVal.floatValue();
			requestUpdate();
		});

		// CB's
		noiseChoiceBox.setConverter(new StringConverter<NoiseType>() {
			@Override
			public String toString(NoiseType noiseType) {
				switch (noiseType) {
					case NOISE_GAUSSIAN_FIT:
						return "Gaussian (Fit)";
					case NOISE_POISSON_FIT:
						return "Poisson (Fit)";
					case NOISE_POISSON_DATA:
						return "Poisson (Data)";
					case NOISE_MLE:
						return "MLE";
					default:
						return "";
				}
			}

			@Override
			public NoiseType fromString(String string) {
				switch (string) {
					case "Gaussian (Fit)":
						return NoiseType.NOISE_GAUSSIAN_FIT;
					case "Poisson (Fit)":
						return NoiseType.NOISE_POISSON_FIT;
					case "Poisson (Data)":
						return NoiseType.NOISE_POISSON_DATA;
					case "MLE":
						return NoiseType.NOISE_MLE;
					default:
						return null;
				}
			}
		});
		// kept separate from numerical fields because they don't have onChange
		ChangeListener<Object> paramPaneUpdateHandler = (obs, oldVal, newVal) -> {
			final Integer nComp = nCompChoiceBox.getValue();
			final FitType algo = algoChoiceBox.getValue();
			FitParams<FloatType> params = getParams();
			params.nComp = nComp;
			fp.setAlgo(algo);
			setupParams(algo, nComp);

			// resize and initialize the two arrays
			int nParam = fp.getNParam();
			int fillStart = params.paramFree.length;
			params.param = Arrays.copyOf(params.param, nParam);
			params.paramMap = ArrayImgs.floats(params.param,
					FitProcessor.permuteAxes(new long[] {1, 1, nParam}, params.ltAxis));
			params.paramFree = Arrays.copyOf(params.paramFree, nParam);
			for (int i = fillStart; i < params.paramFree.length; i++) {
				// set +Inf for no estimation (RAHelper#loadData)
				params.param[i] = Float.POSITIVE_INFINITY;
				params.paramFree[i] = true;
			}

			requestUpdate();
		};
		algoChoiceBox.valueProperty().addListener(paramPaneUpdateHandler);
		nCompChoiceBox.valueProperty().addListener(paramPaneUpdateHandler);

		noiseChoiceBox.valueProperty().addListener((obs, oldVal, newVal) -> {
			getParams().noise = noiseChoiceBox.getValue();
			requestUpdate();
		});

		// populate available IRF source datasets
		irfChoiceBox.focusedProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal) {
				presentDatasets = fp.getObs().getObjects(Dataset.class);
				List<String> irfOptions = new ArrayList<>();
				irfOptions.add("None");
				presentDatasets.forEach(d -> irfOptions.add(d.getName()));

				if (!irfChoiceBox.getItems().equals(irfOptions)) {
					irfChoiceBox.getItems().setAll(irfOptions);
					irfChoiceBox.setValue("None");
				}
			}
		});
		irfChoiceBox.valueProperty().addListener((obs, oldVal, newVal) -> {
			// this happens when the list items are changed
			// if no item is now selected, select the previously selected item
			if (newVal == null) {
				irfChoiceBox.setValue(oldVal);
				return;
			}
			// this happens when we recover from the above situation
			if (oldVal == null) {
				return;
			}

			// update IRF information and notify fp
			FitParams<FloatType> irfInfo = fp.getIRFInfo();
			if (!newVal.equals("None")) {
				// locate the chosen dataset
				Dataset chosenDataset = null;
				for (Dataset dataset : presentDatasets) {
					if (dataset.getName().equals(newVal)) {
						chosenDataset = dataset;
						break;
					}
				}
				// populate IRF information
				if (!fp.populateParams(chosenDataset, irfInfo)) {
					irfChoiceBox.setValue(oldVal);
				} ;
			} else {
				irfInfo.transMap = null;
				// if is currently in picking mode, exit immediately
				fp.setIsPickingRIF(false);
			}
			fp.updateIRF();
			requestUpdate();
		});

		fitButton.setOnAction(event -> {
			fp.fitDataset();

			// set new options
			List<String> previewOptions = new ArrayList<>();
			for (Text label : paramLabels) {
				previewOptions.add(label.getText());
			}
			previewOptions.add("τₘ");
			fp.setPreviewOptions(previewOptions);

			requestUpdate();
		});
	}

	@Override
	public void refresh(FitParams<FloatType> params, FitResults results) {
		iThreshSpinner.setMax(getOps().stats().max(results.intensityMap).getRealDouble());
		iThreshSpinner.getNumberProperty().setValue((double) params.iThresh);
		chisqTgtTextField.setText(Utils.prettyFmt(params.chisq_target));
		noiseChoiceBox.setValue(params.noise);
		nCompChoiceBox.setValue(params.nComp);
		chisqTextField.setText(Utils.prettyFmt(results.chisq));

		if (results.param != null) {
			for (int i = 0; i < results.param.length; i++) {
				paramValues.get(i).getNumberProperty().set((double) results.param[i]);
				paramFixed.get(i).selectedProperty().set(!params.paramFree[i]);
			}
		}
	}

	/**
	 * Adjust the parameter pane to make the parameter labels agree with the algorithm and the
	 * number of components.
	 * 
	 * @param algo  the algorithm used to perform fitting
	 * @param nComp the number of components (available only for LMA and global)
	 */
	private void setupParams(FitType algo, int nComp) {
		List<String> paramNames = new ArrayList<>();
		switch (algo) {
			case LMA:
			case Global:
			case Bayes:
				final String[] subScripts = {"₁", "₂", "₃", "ᵢ"};
				paramNames.add("z");
				for (int i = 0; i < nComp; i++) {
					String subscript = nComp > 1
							? subScripts[i >= subScripts.length ? subScripts.length - 1 : i]
							: "";
					paramNames.add("A" + subscript);
					paramNames.add("τ" + subscript);
				}
				break;

			default:
				break;
		}
		setParams(paramNames);
	}

	/**
	 * Set the parameter labels. Add and remove entries if necessary.
	 * 
	 * @param paramNames the list of all labels
	 */
	private void setParams(List<String> paramNames) {
		// trim table
		paramPane.getChildren().removeIf(child -> GridPane.getRowIndex(child) >= paramNames.size());
		paramLabels.removeIf(element -> element.getParent() != paramPane);
		paramValues.removeIf(element -> element.getParent() != paramPane);
		paramFixed.removeIf(element -> element.getParent() != paramPane);
		// change row labels (add new ones if required)
		for (int i = 0; i < paramNames.size(); i++) {
			if (i < paramLabels.size()) {
				paramLabels.get(i).setText(paramNames.get(i));
			} else {
				addParamRow(paramNames.get(i));
			}
		}
	}

	/**
	 * Create a parameter entry that includes the label, the input TextField and the "Fix" CheckBox.
	 * 
	 * @param name the parameter label
	 */
	private void addParamRow(String name) {
		final int paramIdx = paramLabels.size();
		String paramId = "param" + paramIdx;

		// the name label
		Text paramNameText = new Text(name);
		paramNameText.setFont(new Font("Cambria", 13));
		paramNameText.setId(paramId + "_name");
		paramLabels.add(paramNameText);

		// the input text field
		NumericTextField paramTF = new NumericTextField();
		paramTF.setId(paramId + "_input");
		final ObservableList<String> paramTFSC = paramTF.getStyleClass();
		paramValues.add(paramTF);

		// "Fix" checkbox, automatically selected on user input to the text filed
		CheckBox paramCB = new CheckBox();
		paramCB.setId(paramId + "_fixed");
		paramCB.selectedProperty().addListener((obs, oldVal, newVal) -> {
			if (paramCB.isSelected()) {
				if (!paramTFSC.contains("param-fiexd")) {
					paramTFSC.add("param-fiexd");
				}
			} else {
				paramTFSC.remove("param-fiexd");
			}
			FitParams<FloatType> params = getParams();
			params.paramFree[paramIdx] = !newVal;

			// if changed to free, re-fit the parameter
			if (!newVal) {
				params.param[paramIdx] = Float.POSITIVE_INFINITY;
			}
			requestUpdate();
		});
		paramFixed.add(paramCB);

		EventHandler<ActionEvent> oldOnAction = paramTF.getOnAction();
		paramTF.setOnAction(event -> {
			oldOnAction.handle(event);
			// on input: set param fixed
			paramCB.setSelected(true);
		});
		paramTF.getNumberProperty().addListener((obs, oldValue, newValue) -> {
			FitParams<FloatType> params = getParams();
			params.param[paramIdx] = newValue.floatValue();

			// update when an already fixed value is changed
			if (!params.paramFree[paramIdx]) {
				requestUpdate();
			}
		});

		paramPane.addRow(paramIdx + 1, paramNameText, paramTF, paramCB);
	}
}
