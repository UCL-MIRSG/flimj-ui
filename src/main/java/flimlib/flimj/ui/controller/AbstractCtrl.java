package flimlib.flimj.ui.controller;

import java.net.URL;
import java.util.ResourceBundle;
import org.scijava.ui.UIService;
import javafx.fxml.Initializable;
import net.imagej.ops.OpService;
import flimlib.flimj.FitParams;
import flimlib.flimj.FitResults;
import flimlib.flimj.ui.FitProcessor;

import net.imglib2.type.numeric.real.FloatType;

/**
 * The basis for a tab controller that is {@link Initializable} and refreshable by both external
 * control flow and other controllers backed by the same fit processor.
 */
public abstract class AbstractCtrl implements Initializable {

	protected FitProcessor fp;

	private boolean blockUpdate;

	@Override
	public void initialize(URL location, ResourceBundle resources) {

	}

	/**
	 * Sets the fit processor behind the controller.
	 * 
	 * @param fp the fit processor
	 */
	public void setFitProcessor(FitProcessor fp) {
		this.fp = fp;
	}

	/**
	 * Called by external control flow to notify the controller to refresh itself.
	 */
	public void requestRefresh() {
		blockUpdate = true;
		refresh(fp.getParams(), fp.getResults());
		blockUpdate = false;
	}

	/**
	 * Release the reources occupied by fields in the controller (e.g. {@link #fp}).
	 */
	public void destroy() {
		// so that we can release the resources in datasets/imgs
		fp = null;
	}

	/**
	 * Retrieves the current fitting parameter.
	 * 
	 * @return the current fitting parameter
	 */
	protected FitParams<FloatType> getParams() {
		return fp.getParams();
	}

	/**
	 * Retrieves the current IRF information parameter.
	 * 
	 * @return the current IRF information
	 */
	protected FitParams<FloatType> getIRFInfo() {
		return fp.getIRFInfo();
	}

	/**
	 * Retrieves the current fitted results.
	 * 
	 * @return the current fitted results
	 */
	protected FitResults getResults() {
		return fp.getResults();
	}

	/**
	 * Gets the Op service in context.
	 * 
	 * @return the Op service
	 */
	protected OpService getOps() {
		return fp.getOps();
	}

	/**
	 * Gets the UI service in context.
	 * 
	 * @return the UI service
	 */
	protected UIService getUIs() {
		return fp.getUIs();
	}

	/**
	 * Called by the controller to notify the fit processor to perform a fit and other controllers
	 * to update themselves based on the fit results.
	 */
	protected void requestUpdate() {
		if (blockUpdate) {
			return;
		}
		fp.updateFit();
		fp.refreshControllers();
	}

	/**
	 * The callback called when the controller is supposed to refresh itself based on the parameters
	 * and the results provided by the arguments.
	 * 
	 * @param params  the active fitting parameter
	 * @param results the active fitted results
	 */
	protected void refresh(FitParams<FloatType> params, FitResults results) {

	}
}
