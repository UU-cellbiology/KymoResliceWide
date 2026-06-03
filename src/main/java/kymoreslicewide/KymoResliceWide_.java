package kymoreslicewide;

import java.awt.Rectangle;
import java.util.Arrays;

import ij.gui.Line;
import ij.gui.NewImage;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.PlugIn;
import ij.plugin.RGBStackMerge;
import ij.plugin.Straightener;
import ij.process.ImageProcessor;
import ij.*;


public class KymoResliceWide_ implements PlugIn 
{
	private static String version = "ver.0.7.0";
	private ImagePlus imp;	
	private static boolean rotate;	
	private static final String[] reslicetype = {"Maximum", "Average"};
	private static final String[] subtracttype = {"Nothing", "Minimum", "Average", "Median"};
	private static final int VALUE_AVG = 1;
	private static final int SUBTRACT_MIN = 1, SUBTRACT_AVG = 2, SUBTRACT_MEDIAN = 3;
	private static int nKymoType;
	private static int nSubtractType;
	private boolean rgb;
	private boolean noRoi;
	private int outputSlices = 1;
	private double inputZSpacing = 1.0;
	private double outputZSpacing = 1.0;
	private boolean bAddRoiToOverlay = true;
	private boolean bIgnoreNaN = true;
	private float fStrokeWidth;
	/** whether to ignore calibration or not **/
	private boolean bCalIgnore;
	Overlay impOverlay;

	Roi roi;

	// Variables used by getIrregularProfile and doIrregularSetup
	private int n;
	private double[] x;
	private	double[] y;
	private int xbase;
	private int ybase;
	private int out_length;
	private double[] segmentLengths;
	private double[] dx;
	private double[] dy;
	int roiType;

	@Override
	public void run(String arg)
	{

		if (IJ.versionLessThan("1.44e"))
		{
			IJ.error("ImageJ version later than 1.44e is required, sorry.");
			return;

		}
		IJ.register(KymoResliceWide_.class);

		imp = WindowManager.getCurrentImage();		
		if (imp == null) 
		{
			IJ.noImage();
			return;
		}

		int stackSize = imp.getStackSize();
		// stack always required 
		if (stackSize < 2) {
			IJ.error("Reslice...", "Stack required");
			return;
		}

		roi = imp.getRoi();
		roiType = roi != null ? roi.getType() : 0;

		// permissible ROI types: *LINE
		if (roiType != Roi.LINE && roiType != Roi.POLYLINE && roiType != Roi.FREELINE) 
		{
			IJ.error("Reslice...", "Line, polyline or freeline selection required");
			return;
		}

		if (!showDialog())
			return;

		long startTime = System.currentTimeMillis();

		ImagePlus imp2 = null;
		rgb = imp.getType() == ImagePlus.COLOR_RGB;

		if (imp.isHyperStack())
		{
			imp2 = resliceHyperstack(imp);
		}
		else	
		{
			if (rgb)
				imp2 = resliceRGB(imp);
			else
				imp2 = resliceSimpleStack(imp);			
		}	

		if (imp2 == null)
			return;

		ImageProcessor ip = imp.getProcessor();
		double min = ip.getMin();
		double max = ip.getMax();
		if (!rgb) imp2.getProcessor().setMinAndMax(min, max);

		//remove calibration
		if(bCalIgnore)
		{
			imp2.setCalibration(new Calibration());
		}
		imp2.show();

		if ( noRoi )
		{
			imp.deleteRoi();
		}
		else
		{
			imp.draw();
		}
		IJ.showStatus(IJ.d2s(((System.currentTimeMillis() - startTime) / 1000.0), 2)+" seconds");

	}

	boolean showDialog() 
	{
		GenericDialog gd = new GenericDialog("Wide Reslice " + version);

		gd.addChoice("Intensity value across width:", reslicetype, Prefs.get("KymoResliceWide.Type", "Maximum"));		
		gd.addChoice("Subtract:", subtracttype, Prefs.get("KymoResliceWide.SubtractType", subtracttype[0]));		

		gd.addCheckbox("Rotate 90 degrees", Prefs.get("KymoResliceWide.rotate", false));
		gd.addCheckbox("Add ROI to Overlay", Prefs.get("KymoResliceWide.addROI", false));
		gd.addCheckbox("Ignore image calibration", Prefs.get("KymoResliceWide.bCalIgnore", true));
		gd.addCheckbox("Ignore NaN values?", Prefs.get("KymoResliceWide.bIgnoreNaN", true));
		gd.showDialog();
		if (gd.wasCanceled())
			return false;
		nKymoType = gd.getNextChoiceIndex();
		Prefs.set("KymoResliceWide.Type", reslicetype[nKymoType]);

		nSubtractType = gd.getNextChoiceIndex();
		Prefs.set("KymoResliceWide.SubtractType", subtracttype[nKymoType]);

		rotate = gd.getNextBoolean();
		Prefs.set("KymoResliceWide.rotate", rotate);

		bAddRoiToOverlay = gd.getNextBoolean();
		Prefs.set("KymoResliceWide.addROI", bAddRoiToOverlay);

		bCalIgnore = gd.getNextBoolean();
		Prefs.set("KymoResliceWide.bCalIgnore", bCalIgnore);

		bIgnoreNaN = gd.getNextBoolean();
		Prefs.set("KymoResliceWide.bIgnoreNaN", bIgnoreNaN);

		return true;
	}

	/** helper unwrapper and re-wrapper for timelapse stacks, 
	 * in the end calls resliceRGB or reslice SimpleStack**/
	ImagePlus resliceHyperstack(final ImagePlus imp1) 
	{
		Calibration cal;
		int channels = imp1.getNChannels();
		int slices = imp1.getNSlices();
		int frames = imp1.getNFrames();
		if (slices == 1)
			return resliceTimeLapseHyperstack(imp1);
		if (frames == 1)
			return resliceZHyperstack(imp1);
		int c1 = imp1.getChannel();
		int z1 = imp1.getSlice();
		int t1 = imp1.getFrame();
		int width = imp1.getWidth();
		int height = imp1.getHeight();
		ImagePlus imp2 = null;
		Roi roi1 = imp1.getRoi();
		for (int z = 1; z <= slices; z++) 
		{
			for (int c = 1; c <= channels; c++) 
			{
				ImageStack tmp1Stack = new ImageStack(width, height);
				for (int t = 1; t <= frames; t++) 
				{
					imp1.setPositionWithoutUpdate(c, z, t);
					tmp1Stack.addSlice(null, imp1.getProcessor());
				}
				ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);

				//change time scale calibration
				cal = imp1.getCalibration().copy();
				cal.pixelDepth = cal.frameInterval;
				tmp1.setCalibration(cal);
				tmp1.setRoi(roi1);

				ImagePlus tmp2 = null;
				if(rgb)
					tmp2 = resliceRGB(tmp1);
				else
					tmp2 = resliceSimpleStack(tmp1);

				//int slices2 = tmp2.getStackSize();
				int frames2 = tmp2.getStackSize();
				if (imp2 == null) 
				{
					String sPrefix = getPrefix();
					imp2 = tmp2.createHyperStack(sPrefix + imp1.getTitle(), channels, slices, frames2, tmp2.getBitDepth());
				}
				ImageStack tmp2Stack = tmp2.getStack();
				ImageStack stack2 = imp2.getStack();
				for (int t = 1; t <= frames2; t++) 
				{
					imp1.setPositionWithoutUpdate(c, z, t);
					int n2 = imp2.getStackIndex(c, z, t);
					stack2.setPixels(tmp2Stack.getPixels(t), n2);
				}
			}
		}
		imp1.setPosition(c1, z1, t1);

		if (channels > 1 && imp1.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp1).getMode());
			((CompositeImage)imp2).copyLuts(imp1);
		}
		return imp2;
	}

	/** helper unwrapper and re-wrapper for timelapse stacks, 
	 * in the end calls resliceRGB or reslice SimpleStack**/
	ImagePlus resliceTimeLapseHyperstack(final ImagePlus imp1) {
		int channels = imp1.getNChannels();
		int frames = imp1.getNFrames();
		int c1 = imp1.getChannel();
		int t1 = imp1.getFrame();
		int width = imp1.getWidth();
		int height = imp1.getHeight();
		Calibration cal;
		ImagePlus imp2 = null;
		Roi roi1 = imp1.getRoi();
		int z = 1;
		for (int c = 1; c <= channels; c++) 
		{
			ImageStack tmp1Stack = new ImageStack(width, height);
			for (int t = 1; t <= frames; t++) {
				imp1.setPositionWithoutUpdate(c, z, t);
				tmp1Stack.addSlice(null, imp1.getProcessor());
			}
			ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
			cal = imp1.getCalibration().copy();
			cal.pixelDepth = cal.frameInterval;
			tmp1.setCalibration(cal);
			//tmp1.setCalibration(imp.getCalibration());
			tmp1.setRoi(roi1);

			ImagePlus tmp2 = null;
			if(rgb)
				tmp2 = resliceRGB(tmp1);
			else
				tmp2 = resliceSimpleStack(tmp1);


			int frames2 = tmp2.getStackSize();
			if (imp2 == null) 
			{
				String sPrefix = getPrefix();

				imp2 = tmp2.createHyperStack(sPrefix + imp1.getTitle(), channels, 1, frames2, tmp2.getBitDepth());
			}

			ImageStack tmp2Stack = tmp2.getStack();
			ImageStack stack2 = imp2.getStack();
			for (int t = 1; t <= frames2; t++) 
			{
				imp1.setPositionWithoutUpdate(c, z, t);
				int n2 = imp2.getStackIndex(c, z, t);
				stack2.setPixels(tmp2Stack.getPixels(z), n2);
			}
		}
		imp1.setPosition(c1, 1, t1);
		if (channels > 1 && imp1.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp1).getMode());
			((CompositeImage)imp2).copyLuts(imp1);
		}
		return imp2;
	}
	/** helper unwrapper and re-wrapper for z hyperstacks, 
	 * in the end calls resliceRGB or reslice SimpleStack**/

	ImagePlus resliceZHyperstack(final ImagePlus imp1) {
		int channels = imp1.getNChannels();
		int slices = imp1.getNSlices();
		int c1 = imp1.getChannel();
		int z1 = imp1.getSlice();
		int width = imp1.getWidth();
		int height = imp1.getHeight();

		ImagePlus imp2 = null;

		Roi roi1 = imp1.getRoi();
		int t = 1;
		for (int c = 1; c <= channels; c++) {
			ImageStack tmp1Stack = new ImageStack(width, height);
			for (int z = 1; z <= slices; z++) {
				imp1.setPositionWithoutUpdate(c, z, t);
				tmp1Stack.addSlice(null, imp1.getProcessor());
			}
			ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
			tmp1.setCalibration(imp1.getCalibration());
			tmp1.setRoi(roi1);

			ImagePlus tmp2 = null;
			if( rgb )
				tmp2 = resliceRGB(tmp1);
			else
				tmp2 = resliceSimpleStack(tmp1);


			int slices2 = tmp2.getStackSize();
			if (imp2 == null) 
			{
				String sPrefix = getPrefix();
				imp2 = tmp2.createHyperStack(sPrefix + imp1.getTitle(), channels, slices2, 1, tmp2.getBitDepth());				
			}
			ImageStack tmp2Stack = tmp2.getStack();
			ImageStack stack2 = imp2.getStack();
			for (int z = 1; z <= slices2; z++) 
			{
				imp1.setPositionWithoutUpdate(c, z, t);
				int n2 = imp2.getStackIndex(c, z, t);
				stack2.setPixels(tmp2Stack.getPixels(t), n2);
			}
		}
		imp1.setPosition(c1, z1, 1);
		if (channels > 1 && imp1.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp1).getMode());
			((CompositeImage)imp2).copyLuts(imp1);
		}
		return imp2;
	}

	/** Function splits RGB image to three components,
	 reslices them independently and join results together	**/
	public ImagePlus resliceRGB(final ImagePlus imp1)	
	{
		ImageStack[] colorStack;
		ImagePlus imp2 = null;

		ImageStack stack2 = null;
		ImagePlus red = null;
		ImagePlus green = null;
		ImagePlus blue = null;
		ImagePlus tmp1 = null;        

		colorStack = ChannelSplitter.splitRGB(imp1.getStack(), true);
		tmp1 = new ImagePlus("tmp", colorStack[0]);
		red = resliceSimpleStack(tmp1);
		tmp1 = new ImagePlus("tmp", colorStack[1]);
		green = resliceSimpleStack(tmp1);
		tmp1 = new ImagePlus("tmp", colorStack[2]);
		blue = resliceSimpleStack(tmp1);
		stack2 = RGBStackMerge.mergeStacks(red.getStack(), green.getStack(), blue.getStack(), false);

		String sPrefix = getPrefix();
		imp2  = new ImagePlus(sPrefix + imp1.getTitle(), stack2);

		return imp2;
	}

	/** Function assumes the input stack is 3D, single channel, non RGB.
	 * Returns result of reslice with a proper calibration, calls getSlice **/
	public ImagePlus resliceSimpleStack(final ImagePlus imp1) 
	{
		ImagePlus imp2;

		//save global calibration
		Calibration origCal = imp1.getCalibration();
		boolean globalCalibration = false;
		globalCalibration = imp1.getGlobalCalibration() != null;
		//remove it;
		imp1.setGlobalCalibration(null);
		Calibration tmpCal = origCal.copy();
		tmpCal.pixelWidth = 1.0;
		tmpCal.pixelHeight = 1.0;
		tmpCal.pixelDepth = 1.0;
		imp1.setCalibration(tmpCal);
		inputZSpacing = 1.0;
		if (roiType != Roi.LINE)
			outputZSpacing = 1.0;

		double zSpacing = inputZSpacing / imp1.getCalibration().pixelWidth;

		fStrokeWidth = roi.getStrokeWidth();

		String status = imp1.getStack().isVirtual() ? "" : null;

		IJ.showStatus("Reslice...");

		ImageProcessor ip2 = getSlice(imp1, status);
		String sPrefix = getPrefix();
		imp2 = new ImagePlus(sPrefix + imp1.getShortTitle(), ip2);

		if (globalCalibration)
			imp1.setGlobalCalibration(origCal);
		imp1.setCalibration(origCal);

		boolean horizontal = false;
		boolean vertical = false;

		if (roi != null && roiType == Roi.LINE) 
		{
			Line l = (Line)roi;
			horizontal  = (l.y2 - l.y1) == 0;
			vertical    = (l.x2 - l.x1) == 0;
		}	

		imp2.setCalibration(imp1.getCalibration());

		Calibration cal = imp2.getCalibration();

		if (horizontal) 
		{
			cal.pixelWidth = origCal.pixelWidth;
			cal.pixelHeight = origCal.pixelDepth / zSpacing;
			cal.pixelDepth = origCal.pixelHeight * outputZSpacing;
		} 
		else if (vertical) 
		{
			cal.pixelWidth = origCal.pixelHeight;
			cal.pixelHeight = origCal.pixelDepth / zSpacing;
			cal.pixelDepth = origCal.pixelWidth * outputZSpacing;
		} 
		else 
		{ // oblique line, polyLine or freeline
			if (origCal.pixelHeight == origCal.pixelWidth) 
			{
				cal.pixelWidth = origCal.pixelWidth;
				cal.pixelHeight = origCal.pixelDepth/zSpacing;
				cal.pixelDepth = origCal.pixelWidth*outputZSpacing;
			} else {
				cal.pixelWidth = cal.pixelHeight = cal.pixelDepth = 1.0;
				cal.setUnit("pixel");
			}
		}
		double tmp;
		if (rotate) 
		{// if rotated flip X and Y
			tmp = cal.pixelWidth;
			cal.pixelWidth = cal.pixelHeight;
			cal.pixelHeight = tmp;
		}
		return imp2;
	}	
	/** given an imagePlus (supposed to be 3D, single channel, non RGB),
	 * returns a slice of kymo **/
	@SuppressWarnings( "null" )
	ImageProcessor getSlice(final ImagePlus imp_in, String status) 
	{		 
		ImageStack stack = imp_in.getStack();
		int stackSize = stack.getSize();
		ImageProcessor ip, ip_out = null;
		int nSliceInitialN;
		float[] line = null;

		//thick freeline or spline fitted poly-line
		if((roiType == Roi.FREELINE && fStrokeWidth > 1) ||
				(roiType == Roi.POLYLINE && ((PolygonRoi)roi).isSplineFit() && fStrokeWidth > 1))
		{
			if(roiType == Roi.FREELINE && fStrokeWidth > 1)
			{
				IJ.run(this.imp, "Fit Spline", "");
				roi = this.imp.getRoi();
			}
			imp_in.setRoi(roi);

			nSliceInitialN = imp_in.getCurrentSlice();			 			 			 

			for (int i = 0; i < stackSize; i++) 
			{
				imp_in.setSliceWithoutUpdate( i + 1 );
				ip = (new Straightener()).straightenLine(imp_in, (int)fStrokeWidth);
				line = getFreeHandProfileWide(ip);
				if (i == 0) 
				{
					//init output
					ip_out = imp_in.getChannelProcessor().createProcessor(line.length, stackSize);
				}
				putRow(ip_out, 0, i, line, line.length);

				if (status != null) IJ.showStatus("Slicing: "+status +i+"/"+stackSize);
				IJ.showProgress(i, stackSize);			 
			}

			//put back the slice we've used
			imp_in.setSliceWithoutUpdate(nSliceInitialN);
			//correct the height of the kymograph
			int new_width = (int)roi.getLength();
			ip_out.setInterpolationMethod(ij.process.ImageProcessor.BICUBIC);
			ip_out = ip_out.resize(new_width, ip_out.getHeight(), true);

			//show overlay
			if(bAddRoiToOverlay)
			{
				impOverlay = imp_in.getOverlay();
				if(impOverlay == null)
					impOverlay = new Overlay();

				impOverlay.add(roi);
				this.imp.setOverlay(impOverlay);
				this.imp.updateAndRepaintWindow();
				this.imp.show();
				bAddRoiToOverlay = false;
			}
		}
		// line or 
		// thin freeline or non-spline fitted poly-line
		else
		{
			for (int i = 0; i < stackSize; i++) 
			{
				ip = stack.getProcessor( i + 1 );
				if (roiType == Roi.LINE)
					line = getStraightLine(ip);
				else 
					//if	(roiType==Roi.POLYLINE || (roiType==Roi.FREELINE && fStrokeWidth<=1))					
					line = getIrregularProfileWide(ip);

				if (i == 0) 
				{
					ip_out = ip.createProcessor(line.length, stackSize);
				}
				putRow(ip_out, 0, i, line, line.length);

				if (status != null) IJ.showStatus("Slicing: " + status + i + "/" + stackSize);
				IJ.showProgress(i, stackSize);

			}
		}

		Calibration cal = imp_in.getCalibration();
		double zSpacing = inputZSpacing/cal.pixelWidth;
		if (zSpacing != 1.0) 
		{
			ip_out.setInterpolate(true);
			if (rotate)
				ip_out = ip_out.resize((int)(stackSize * zSpacing), line.length);
			else
				ip_out = ip_out.resize(line.length, (int)(stackSize*zSpacing));
		}

		if(rotate)
			ip_out = ip_out.rotateLeft();
		return ip_out;
	}


	/** build a kymo along a straight line **/
	private float[] getStraightLine(ImageProcessor ip) 
	{
		Line line = (Line)roi;
		float[] valuesOnePix = null;
		double[] interx = null;
		double[] intery = null;

		double x1, y1, x2, y2;
		x1 = line.x1;
		x2 = line.x2;
		y1 = line.y1;
		y2 = line.y2;
		double dx1 = x2 - x1;
		double dy1 = y2 - y1;
		double line_length = Math.sqrt(dx1 * dx1 + dy1 * dy1);
		out_length = (int)Math.round(line_length);

		valuesOnePix = new float[out_length];
		interx = new double[out_length];
		intery = new double[out_length];
		double xinc = dx1 / out_length;
		double yinc = dy1 / out_length;
		double rx = x1;
		double ry = y1;		
		for (int i = 0; i < out_length; i++) 
		{
			valuesOnePix[i] = (float)ip.getInterpolatedValue(rx, ry);
			interx[i] = rx;
			intery[i] = ry;
			rx += xinc;
			ry += yinc;
		}

		int widthline = Math.round(fStrokeWidth);	

		impOverlay = imp.getOverlay();
		if(impOverlay == null)
		{
			impOverlay = new Overlay();			 
		}

		//one pix wide
		if (widthline <= 1) 
		{
			if(bAddRoiToOverlay)
			{
				impOverlay.add(roi);
				imp.setOverlay(impOverlay);
				imp.updateAndRepaintWindow();
				imp.show();
				bAddRoiToOverlay = false;			 
			}			
			return valuesOnePix;
		}

		//wider line
		int nOffset;
		float[][] lineprof2D = new float[out_length][widthline];

		if(widthline % 2 == 0)
			nOffset = widthline / 2;
		else
			nOffset = (widthline - 1) / 2;

		//find perpendicular direction 
		dx1 /= line_length;
		dy1 /= line_length;			
		//perpendicular direction;
		line_length = dx1;
		dx1 = (-1) * dy1;
		dy1 = line_length;

		for(int k = 0; k < out_length; k++)
		{
			for(int j = 0; j < widthline; j++)
			{
				rx = interx[ k ] + dx1 * (nOffset - j);
				ry = intery[ k ] + dy1 * (nOffset - j);
				lineprof2D[ k ][ j ] = (float)ip.getInterpolatedValue(rx, ry);				 
			}

			//adding scanning roi
			if(bAddRoiToOverlay)
			{
				Line lineROI = new Line(interx[k] + dx1 * (nOffset),
						intery[k] + dy1 * (nOffset),
						interx[k] + dx1 * (nOffset - widthline + 1),
						intery[k] + dy1 * (nOffset - widthline + 1));
				lineROI.setStrokeWidth(1.);
				impOverlay.add(lineROI);
			}		 
		}			 

		//some debugging
		if(bAddRoiToOverlay)
		{
			imp.setOverlay(impOverlay);
			imp.updateAndRepaintWindow();
			imp.show();
			bAddRoiToOverlay = false;
		}

		return processWideLine(lineprof2D);	
	}	

	float[] getIrregularProfileWide(ImageProcessor ip) 
	{		
		if (x == null)
			doIrregularSetup(roi);

		float[] valuesOnePix = new float[out_length];
		double[] interx = new double[out_length];
		double[] intery = new double[out_length];
		double leftOver = 1.0;
		double distance = 0.0;
		int index;		
		for (int i = 0; i < n; i++) 
		{
			double len = segmentLengths[i];
			if (len == 0.0)
				continue;
			double xinc = dx[i] / len;
			double yinc = dy[i] / len;
			double start = 1.0 - leftOver;
			double rx = xbase + x[i] + start * xinc;
			double ry = ybase + y[i] + start * yinc;
			double len2 = len - start;
			int n2 = (int)len2;
			for (int j = 0; j <= n2; j++) {
				index = (int)distance + j;
				if (index < out_length)
				{
					valuesOnePix[index] = (float)ip.getInterpolatedValue(rx, ry);
					interx[index] = rx;
					intery[index] = ry;
				}
				rx += xinc;
				ry += yinc;
			}
			distance += len;
			leftOver = len2 - n2;
		}

		impOverlay = imp.getOverlay();
		if(impOverlay == null)
		{
			impOverlay = new Overlay();		 
		}

		int widthline = Math.round(fStrokeWidth);		 
		//ok, we got main line now
		if (widthline <= 1)
		{
			if(bAddRoiToOverlay)
			{
				impOverlay.add(roi);
				imp.setOverlay(impOverlay);
				imp.updateAndRepaintWindow();
				imp.show();
				bAddRoiToOverlay = false;			 
			}

			return valuesOnePix;
		}

		//wide line
		//averaging/finding maximum in perpendicular direction
		float[][] lineprof2D = new float[out_length][widthline];
		int nOffset;
		double deltax, deltay, lendelta;
		double rx, ry;
		if(widthline % 2 == 0)
			nOffset = widthline / 2;
		else
			nOffset = (widthline - 1) / 2;

		//initial point

		//direction
		deltax = interx[1] - interx[0];
		deltay = intery[1] - intery[0];
		//normalization
		lendelta = Math.sqrt(deltax * deltax + deltay * deltay);
		deltax /= lendelta;
		deltay /= lendelta;
		//perpendicular direction;
		lendelta = deltax;
		deltax = (-1) * deltay;
		deltay = lendelta;

		for(int j = 0; j < widthline; j++)
		{
			rx = interx[0] + deltax * (nOffset - j);
			ry = intery[0] + deltay * (nOffset - j);
			lineprof2D[0][j] = (float)ip.getInterpolatedValue(rx, ry);			 				 
		}

		if(bAddRoiToOverlay)
		{				 
			Line lineROI = new Line(interx[0] + deltax * (nOffset),
					intery[0] + deltay * (nOffset),
					interx[0] + deltax * (nOffset - widthline + 1),
					intery[0] + deltay * (nOffset - widthline + 1));
			lineROI.setStrokeWidth(1.);
			impOverlay.add(lineROI);
		}			 
		//all other points
		for(int k = 1; k < out_length; k++)
		{
			//direction
			deltax = interx[k] - interx[k-1];
			deltay = intery[k] - intery[k-1];
			//normalization
			lendelta = Math.sqrt(deltax * deltax + deltay * deltay);
			deltax /= lendelta;
			deltay /= lendelta;
			//perpendicular direction;
			lendelta = deltax;
			deltax = (-1) * deltay;
			deltay = lendelta;

			for(int j = 0; j < widthline; j++)
			{
				rx = interx[k] + deltax * (nOffset - j);
				ry = intery[k] + deltay * (nOffset - j);
				lineprof2D[k][j] = (float)ip.getInterpolatedValue(rx, ry);

			}
			if(bAddRoiToOverlay)
			{				 
				Line lineROI = new Line(interx[k] + deltax * (nOffset),
						intery[k] + deltay * (nOffset),
						interx[k] + deltax * (nOffset - widthline + 1),
						intery[k] + deltay * (nOffset - widthline + 1));
				lineROI.setStrokeWidth(1.);
				impOverlay.add(lineROI);
			}

		}

		//some debuggin
		if(bAddRoiToOverlay)
		{
			imp.setOverlay(impOverlay);
			imp.updateAndRepaintWindow();
			imp.show();
			bAddRoiToOverlay = false;
		}

		return processWideLine(lineprof2D);
	}

	float [] getFreeHandProfileWide(ImageProcessor ip)
	{
		out_length = ip.getWidth();
		int widthline = Math.round(fStrokeWidth);	

		float[][] lineprof2D = new float[out_length][widthline];

		for(int k = 0; k < out_length; k++)
		{
			for(int j = 0; j < widthline; j++)
			{
				lineprof2D[k][j] = (float)ip.getInterpolatedValue(k, j);
			}
		}
		return processWideLine(lineprof2D);
	}

	float [] processWideLine(final float [][] lineprof2D)
	{
		final int nLength = lineprof2D.length;
		final int nWidth = lineprof2D[0].length;
		final float[] values_out = new float[nLength];
		float fMin;
		float fMax;
		float fMedian = 0.0f;
		float fMean;
		float fCount;
		float curVal;
		for(int k = 0; k < nLength; k++)
		{
			fCount = 0;
			fMin = Float.MAX_VALUE;
			fMax = (-1) * Float.MAX_VALUE;
			fMean = 0;
			for(int j = 0; j < nWidth; j++)
			{
				curVal = lineprof2D[ k ][ j ];			 
				if(bIgnoreNaN && Float.isNaN( curVal ))
				{
					continue;
				}
				fMean += curVal;
				fCount ++;
				fMax = Math.max (curVal, fMax);
				fMin = Math.min (curVal, fMin);
			}
			fMean /= fCount;
			if(nSubtractType == SUBTRACT_MEDIAN)
			{
				final float [] singleLine = new float[nWidth];
				for(int j = 0; j < nWidth; j++)
				{
					singleLine[j] = lineprof2D[k][j];
				}
				Arrays.sort( singleLine );
				if (nWidth % 2 == 0)
					fMedian = (singleLine[nWidth/2] + singleLine[nWidth/2 - 1])*0.5f;
				else
					fMedian = singleLine[nWidth/2];
			}

			//VALUE
			if(nKymoType == VALUE_AVG)
				curVal = fMean;
			else
				curVal = fMax;

			//SUBTRACT
			switch (nSubtractType)
			{
			case SUBTRACT_MIN:
				curVal -= fMin;
				break;
			case SUBTRACT_AVG:
				curVal -= fMean;
				break;
			case SUBTRACT_MEDIAN:
				curVal -= fMedian;
				break;
			default:
				break;
			}
			//finally
			values_out[ k ] = curVal;

		}

		return values_out;
	}

	public void putRow(ImageProcessor ip, int x_, int y_, float[] data_, int length_) 
	{
		for (int i = 0; i < length_; i++)
			ip.putPixelValue(x_++, y_, data_[i]);
	}

	ImageStack createOutputStack(ImagePlus imp_, ImageProcessor ip) {
		int bitDepth = imp_.getBitDepth();
		int w2 = ip.getWidth(), h2 = ip.getHeight(), d2 = outputSlices;
		int flags = NewImage.FILL_BLACK + NewImage.CHECK_AVAILABLE_MEMORY;
		ImagePlus imp2 = NewImage.createImage("temp", w2, h2, d2, bitDepth, flags);
		if (imp2 != null && imp2.getStackSize() == d2)
			IJ.showStatus("Reslice... (press 'Esc' to abort)");
		if (imp2 == null)
			return null;
		ImageStack stack2 = imp2.getStack();
		stack2.setColorModel(ip.getColorModel());
		return stack2;
	}

	void doIrregularSetup(Roi roi1) {
		n = ((PolygonRoi)roi1).getNCoordinates();
		int[] ix = ((PolygonRoi)roi1).getXCoordinates();
		int[] iy = ((PolygonRoi)roi1).getYCoordinates();
		x = new double[n];
		y = new double[n];
		for (int i = 0; i < n; i++) 
		{
			x[i] = ix[i];
			y[i] = iy[i];
		}
		if (roi1.getType() == Roi.FREELINE) 
		{
			// smooth line
			for (int i = 1; i < n - 1; i++) 
			{
				x[i] = (x[i-1] + x[i] + x[i+1]) / 3.0 + 0.5;
				y[i] = (y[i-1] + y[i] + y[i+1]) / 3.0 + 0.5;
			}
		}
		Rectangle r = roi1.getBounds();
		xbase = r.x;
		ybase = r.y;
		double dLength = 0.0;
		double segmentLength;
		double xdelta, ydelta;
		segmentLengths = new double[n];
		dx = new double[n];
		dy = new double[n];
		for (int i = 0; i < (n - 1); i++) 
		{
			xdelta = x[i+1] - x[i];
			ydelta = y[i+1] - y[i];
			segmentLength = Math.sqrt(xdelta * xdelta + ydelta * ydelta);
			dLength += segmentLength;
			segmentLengths[i] = segmentLength;
			dx[i] = xdelta;
			dy[i] = ydelta;
		}
		out_length = (int)dLength;
	}

	String getPrefix()
	{
		String out = "Reslice ";
		if(nKymoType == VALUE_AVG)
			out = out  + "(AVRG";
		else
			out = out  + "(MAX";
		
		switch (nSubtractType)
		{
		case SUBTRACT_MIN:
			out = out + " - MIN)";
			break;
		case SUBTRACT_AVG:
			out = out + " - AVRG)";
			break;
		case SUBTRACT_MEDIAN:
			out = out + " - MEDIAN)";
			break;
		default:
			out = out + ")";
			break;
		}
		out = out + " of ";
		return out;
	}

	public static void main( final String[] args ) 
	{
		// open an ImageJ window
		new ImageJ();
		KymoResliceWide_ krw = new KymoResliceWide_();

		//subtraction debug
		IJ.open("/home/eugene/Desktop/projects/Kymoreslicewide/20260603_subtract/cropped_cellmovie.tif");

		//NaN DEBUG
		//		IJ.open("/home/eugene/Desktop/projects/Kymoreslicewide/20240508_NaN/HyperStack_NaN.tif");
		//		IJ.open("/home/eugene/Desktop/projects/Kymoreslicewide/20240508_NaN/NaN.roi");


		krw.run(null);
	}

}
