package flimlib.flimj.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.scijava.Context;
import org.scijava.object.ObjectService;
import org.scijava.script.ScriptService;
import org.scijava.ui.UIService;
import net.imagej.Dataset;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imagej.ops.OpService;
import net.imagej.roi.ROIService;
import flimlib.flimj.DefaultCalc.CalcTauMean;
import flimlib.flimj.ui.controller.AbstractCtrl;
import flimlib.flimj.FitParams;
import flimlib.flimj.FitResults;
import flimlib.flimj.ParamEstimator;
import flimlib.flimj.FlimOps;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.roi.Masks;
import net.imglib2.roi.RealMask;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * ProcessingService
 */
public class FitProcessor {

	/** The collection of all fitting algorithms */
	public static enum FitType {
		LMA, Global, Bayes
		/** Phasor */
	};

	public static final int PREVIEW_INTENSITY = -1;

	public static final int PREVIEW_TAU_M = -2;

	public static final int PREVIEW_IRF_INTENSITY = -3;

	private final OpService ops;

	private final UIService uis;

	private final ObjectService obs;

	private final Dataset dataset;

	private final FitParams<FloatType> params, irfInfoParams;

	private FitResults results;

	// private Mask roi;
	private boolean useRoi, isPickingRIF;

	private RandomAccessibleInterval<FloatType> origTrans, binnedTrans;

	private Img<FloatType> dispParams, irfIntensity;

	private String fitType;

	private List<String> contextualPreviewOptions, persistentPreviewOptions;

	private static final BiFunction<Float, float[], Float> MULTI_EXP;

	private BiFunction<Float, float[], Float> fitFunc;

	private int nParam, previewX, previewY;

	private AbstractCtrl[] controllers;

	private final ExecutorService executor;

	static {
		// z + sum[a_i * exp(-t / tau_i)]
		MULTI_EXP = (t, param) -> {
			float y = param[0];
			for (int i = 1; i < param.length - 1; i += 2) {
				y += param[i] * Math.exp(-t / param[i + 1]);
			}
			// Inf/NaN blows up the plot thread
			return Float.isFinite(y) ? y : 0f;
		};
	}

	public FitProcessor(Dataset dataset) {
		this.dataset = dataset;
		final Context context = dataset.getContext();
		this.ops = context.service(OpService.class);
		this.uis = context.service(UIService.class);
		this.obs = context.service(ObjectService.class);
		this.params = new FitParams<>();
		this.irfInfoParams = new FitParams<>();
		this.results = new FitResults();
		this.useRoi = true;
		this.executor = Executors.newFixedThreadPool(1);
		init();

		setBinning(0);

		setPreviewPos(0, 0);
		// updateFit();
	}

	private void init() {
		populateParams(this.dataset, this.params);
		origTrans = params.transMap;

		// allocate buffers
		params.trans = new float[(int) params.transMap.dimension(params.ltAxis)];
		params.paramFree = new boolean[0];
		params.param = new float[0];
		params.paramMap =
				ArrayImgs.floats(params.param, permuteAxes(new long[] {1, 1, 0}, params.ltAxis));

		contextualPreviewOptions = new ArrayList<>();
		persistentPreviewOptions = new ArrayList<>();
		// this option is always present
		persistentPreviewOptions.add("Intensity");
	}

	@SuppressWarnings("unchecked")
	public <T extends RealType<T>> boolean populateParams(Dataset dataset,
			FitParams<FloatType> params) {
		ImgPlus<T> imp = (ImgPlus<T>) dataset.getImgPlus();
		// dimensionality check
		final int nD = imp.numDimensions();
		if (nD < 3 || nD > 4) {
			throw new IllegalArgumentException("Dataset dimensionality (" + nD + ") is not 3 or 4");
		}
		RandomAccessibleInterval<FloatType> img = ops.convert().float32(imp.getImg());

		boolean userCancled = false;

		int channelAxis = -1;
		if (nD == 4) {
			final String[] cAxisLabels = {"Channel", "Spectra"};
			for (String label : cAxisLabels) {
				channelAxis = dataset.dimensionIndex(Axes.get(label));
				if (channelAxis != -1) {
					break;
				}
			}

			// prompt for channel selection
			if (channelAxis == -1)
				channelAxis = (int) harvestNumber("Multiple Channel Detected", "Integer",
						"Select Channel Dimension Index:", 0, nD - 1, 0, "slider");
			int channelIndex = (int) harvestNumber("Multiple Channel Detected", "Integer",
					"Select Channel:", 0, img.dimension(channelAxis) - 1, 0, "slider");
			img = Views.hyperSlice(img, channelAxis, channelIndex);
		}
		params.transMap = img;

		// axis index
		final String[] axisLabels = {"Time", "Lifetime"};
		for (String label : axisLabels) {
			params.ltAxis = dataset.dimensionIndex(Axes.get(label));
			if (params.ltAxis != -1) {
				break;
			}
		}
		if (params.ltAxis == -1)
			params.ltAxis = (int) harvestNumber("Lifetime Axis Not Detected", "Integer",
					"Select Channel Dimension Index:", 0, nD - 1, 0, "slider");
		// in case the channel axis before the lifetime axis is dropped
		if (channelAxis != -1 && channelAxis < params.ltAxis) {
			params.ltAxis -= 1;
		}

		// time increment
		CalibratedAxis lifetimeAxis = imp.axis(params.ltAxis);
		if (lifetimeAxis.unit() == null) {
			lifetimeAxis.setUnit("ns");
		}
		// TODO handle other time units
		params.xInc = (float) lifetimeAxis.calibratedValue(1);
		if (params.xInc <= 0) {
			float timeBin = harvestNumber("Time Bin Info Not Detected", "Float",
					"Input Time Bin (ns):", 0, Float.NaN, 10, "spinner");
			params.xInc = timeBin / imp.dimension(params.ltAxis);
		}

		return !userCancled;
	}

	/**
	 * Harvest user numerical input
	 * @param title the dialog title
	 * @param type Integer, Float, etc.
	 * @param label Descriptive label on the left
	 * @param min mininmum, Float.NaN ignores this setting
	 * @param max maxinmum, Float.NaN ignores this setting
	 * @param defaultVal default input value, Float.NaN ignores this setting
	 * @param style slider | spinner | scroll bar
	 * @return the harvested value
	 */
	private float harvestNumber(String title, String type, String label, float min, float max,
			float defaultVal, String style) {
		try {
			String script =
				"#@script (label='%s')\n" +
				"#@ %s (label='%s', %s %s %s style='%s') number";
			return ((Number) ops.getContext().getService(ScriptService.class)
					.run(title + ".js", String.format(script,
							title, type, label, min != Float.NaN ? "min=" + min + "," : "",
							max != Float.NaN ? "max=" + max + "," : "",
							defaultVal != Float.NaN ? "value=" + defaultVal + ",": "", style), true)
					.get().getInput("number")).floatValue();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
		return Float.NaN;
	}

	public void setControllers(AbstractCtrl... controllers) {
		this.controllers = controllers;
	}

	public void refreshControllers() {
		for (AbstractCtrl controller : controllers) {
			controller.requestRefresh();
		}
	}

	/**
	 * @return the params
	 */
	public FitParams<FloatType> getParams() {
		return params;
	}

	/**
	 * @return the IRF info capsule
	 */
	public FitParams<FloatType> getIRFInfo() {
		return irfInfoParams;
	}

	/**
	 * @return the results
	 */
	public FitResults getResults() {
		return results;
	}

	/**
	 * @return the Op service
	 */
	public OpService getOps() {
		return ops;
	}

	/**
	 * @return the UI service
	 */
	public UIService getUIs() {
		return uis;
	}

	/**
	 * @return the object service
	 */
	public ObjectService getObs() {
		return obs;
	}

	public void updateFit() {
		FitResults fr = (FitResults) ops.run("flim.fit" + fitType, params);
		fr.intensityMap = this.results.intensityMap;
		this.results = fr;
	}

	public void setBinning(int size) {
		binnedTrans = origTrans;
		if (size > 0) {
			Img<DoubleType> knl = FlimOps.makeSquareKernel(size * 2 + 1);
			binnedTrans = ops.filter().convolve(origTrans, knl);
		}

		// setBinning() incurs a change in the source image. Roi should be re-set
		setUseRoi(useRoi);
	}

	public void setUseRoi(boolean useRoi) {
		params.roiMask = useRoi ? getROI() : Masks.allRealMask(0);

		RandomAccessibleInterval<FloatType> previewTransMap = params.transMap;
		params.transMap = binnedTrans;
		ParamEstimator<FloatType> estimator = new ParamEstimator<>(params);
		params.transMap = previewTransMap;

		results.intensityMap = estimator.getIntensityMap();

		estimator.estimateStartEnd();

		setPreviewPos(previewX, previewY);
	}

	public void setAlgo(FitType algo) {
		switch (algo) {
			case LMA:
				fitType = "MLA";
				fitFunc = MULTI_EXP;
				nParam = 2 * params.nComp + 1;
				break;

			case Global:
				fitType = "Global";
				fitFunc = MULTI_EXP;
				nParam = 2 * params.nComp + 1;
				break;

			case Bayes:
				fitType = "Bayes";
				fitFunc = MULTI_EXP;
				nParam = 2 * params.nComp + 1;
				break;
		}
	}

	public void setIsPickingRIF(boolean isPickingRIF) {
		this.isPickingRIF = isPickingRIF;
	}

	public boolean isPickingRIF() {
		return isPickingRIF;
	}

	public void updateIRF() {
		if (irfInfoParams.transMap != null) {
			ParamEstimator<FloatType> estimator = new ParamEstimator<>(irfInfoParams);
			estimator.estimateStartEnd();
			irfIntensity = estimator.getIntensityMap();
			persistentPreviewOptions.add("IRF Intensity");
		} else {
			persistentPreviewOptions.remove("IRF Intensity");
			irfInfoParams.trans = null;
			params.instr = null;
		}
	}

	public BiFunction<Float, float[], Float> getFitFunc() {
		return fitFunc != null ? fitFunc : (t, param) -> 0f;
	}

	public int getNParam() {
		return nParam;
	}

	public void setPreviewPos(int x, int y) {
		if (isPickingRIF) {
			// just load the IRF into the .trans array
			fillTrans(irfInfoParams.transMap, irfInfoParams.trans, x, y, irfInfoParams.ltAxis);
		} else {
			previewX = x;
			previewY = y;
			params.transMap = fillTrans(binnedTrans, params.trans, x, y, params.ltAxis);

			// clear out estimations
			for (int i = 0; i < params.param.length; i++) {
				if (params.paramFree[i]) {
					params.param[i] = Float.POSITIVE_INFINITY;
				}
			}
		}
	}

	private static IntervalView<FloatType> fillTrans(RandomAccessibleInterval<FloatType> transMap,
			float[] transArr, int x, int y, int ltAxis) {
		IntervalView<FloatType> transView =
				Views.offsetInterval(transMap, permuteAxes(new long[] {x, y, 0}, ltAxis),
						permuteAxes(new long[] {1, 1, transArr.length}, ltAxis));
		int i = 0;
		for (FloatType data : transView) {
			transArr[i++] = data.get();
		}
		return transView;
	}

	public void fitDataset() {
		// temporarily save trans and param maps for preview
		RandomAccessibleInterval<FloatType> previewTransMap, previewParamMap;
		previewTransMap = params.transMap;
		params.transMap = binnedTrans;
		previewParamMap = params.paramMap;
		params.paramMap = null;

		updateFit();

		params.paramMap = previewParamMap;
		params.transMap = previewTransMap;

		dispParams = results.paramMap;
	}

	/**
	 * @return the previewOptions
	 */
	public List<String> getPreviewOptions() {
		return Stream.of(persistentPreviewOptions, contextualPreviewOptions)
				.flatMap(Collection::stream).collect(Collectors.toList());
	}

	/**
	 * @param options the previewOptions to set
	 */
	public void setPreviewOptions(List<String> options) {
		// remove non-persistent options
		contextualPreviewOptions = options;
	}

	public boolean previewReady() {
		return dispParams != null || irfIntensity != null;
	}

	@SuppressWarnings("unchecked")
	public RandomAccessibleInterval<FloatType> getPreviewImg(int optionIdx) {
		switch (fitType) {
			case "MLA":
			case "Global":
			case "Bayes":
				switch (optionIdx) {
					case PREVIEW_INTENSITY:
						return Views.dropSingletonDimensions(getResults().intensityMap);

					case PREVIEW_TAU_M:
						Img<FloatType> previewParamMap = results.paramMap;
						results.paramMap = dispParams;
						Img<FloatType> tauM = (Img<FloatType>) ops.run(CalcTauMean.class, results);
						results.paramMap = previewParamMap;
						return tauM;

					case PREVIEW_IRF_INTENSITY:
						return Views.dropSingletonDimensions(irfIntensity);

					default:
						// get the ith param beyond persistent options
						return Views.hyperSlice(dispParams, params.ltAxis,
								optionIdx - persistentPreviewOptions.size());
				}

			default:
				break;
		}
		return null;
	}

	/**
	 * Permute the coordinates from ltDimension-last to ltDimension-at-ltAxis.
	 * 
	 * @param coordinates  the coordinates in ltDimension-last order
	 * @param lifetimeAxis the index of the lifetime axis
	 * @return the coordinates in ltDimension-at-ltAxis order
	 */
	public static long[] permuteAxes(long[] coordinates, int lifetimeAxis) {
		for (int i = coordinates.length - 1; i > lifetimeAxis; i--) {
			long tmp = coordinates[i];
			coordinates[i] = coordinates[i - 1];
			coordinates[i - 1] = tmp;
		}
		return coordinates;
	}

	public void submitRunnable(Runnable runnable) {
		executor.submit(runnable);
	}

	public void destroy() {
		for (AbstractCtrl controller : controllers) {
			controller.destroy();
		}
		executor.shutdownNow();
	}

	private RealMask getROI() {
		// TODO
		ROIService roiService = ops.getContext().service(ROIService.class);
		return Masks.allRealMask(0);
	}
}
