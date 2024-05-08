package kymoreslicewide;

import java.awt.Rectangle;


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
	private static String version = "ver.0.6.0";
	private ImagePlus imp;	
	private static boolean rotate;	
	private static final String[] reslicetype = {"Maximum", "Average"};
	private static int nKymoType;
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
	private double length;
	private double[] segmentLengths;
	private double[] dx;
	private double[] dy;
	
	@Override
	public void run(String arg)
	{
		
		if (IJ.versionLessThan("1.44e"))
		{
		    IJ.error("ImageJ version later than 1.44e is required, sorry.");
		    return;
			
		}
		IJ.register(KymoResliceWide_.class);
		// This code based a lot on
		// straightforward ImageJ reslicer!! Check Slicer.java
		imp = WindowManager.getCurrentImage();		
		if (imp==null) {
			IJ.noImage();
			return;
		}

		int stackSize = imp.getStackSize();
		roi = imp.getRoi();
		int roiType = roi!=null?roi.getType():0;
		// stack always required 
		if (stackSize<2) {
			IJ.error("Reslice...", "Stack required");
			return;
		}
		// permissible ROI types: *LINE
		if (roiType!=Roi.LINE && roiType!=Roi.POLYLINE && roiType!=Roi.FREELINE) {
			IJ.error("Reslice...", "Line, polyline or freeline selection required");
			return;
		}
		
		if (!showDialog())
			return;
		
		long startTime = System.currentTimeMillis();
		ImagePlus imp2 = null;
		rgb = imp.getType()==ImagePlus.COLOR_RGB;
		
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
		
		
		if (imp2==null)
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
		if (noRoi)
		{
			imp.deleteRoi();
		}
		else
		{
			imp.draw();
		}
		IJ.showStatus(IJ.d2s(((System.currentTimeMillis()-startTime)/1000.0),2)+" seconds");

		
	}
	
	
	ImagePlus resliceHyperstack(ImagePlus imp1) 
	{
		Calibration cal;
		int channels = imp1.getNChannels();
		int slices = imp1.getNSlices();
		int frames = imp1.getNFrames();
		if (slices==1)
			return resliceTimeLapseHyperstack(imp1);
		if (frames==1)
			return resliceZHyperstack(imp1);
		int c1 = imp1.getChannel();
		int z1 = imp1.getSlice();
		int t1 = imp1.getFrame();
		int width = imp1.getWidth();
		int height = imp1.getHeight();
		ImagePlus imp2 = null;
		Roi roi1 = imp1.getRoi();
		//for (int t=1; t<=frames; t++) {
		for (int z=1; z<=slices; z++) 
		{
			for (int c=1; c<=channels; c++) 
			{
				ImageStack tmp1Stack = new ImageStack(width, height);
				//for (int z=1; z<=slices; z++) {
				for (int t=1; t<=frames; t++) 
				{
					imp1.setPositionWithoutUpdate(c, z, t);
					tmp1Stack.addSlice(null, imp1.getProcessor());
				}
				ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
				//tmp1.setCalibration(imp.getCalibration());
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
					String sPrefix = "Reslice ";
	            	if(nKymoType == 1)
	            		sPrefix = sPrefix + "(AVRG) of ";
	            	else
	            		sPrefix = sPrefix + "(MAX) of ";

	            	imp2 = tmp2.createHyperStack(sPrefix + imp1.getTitle(), channels, slices, frames2, tmp2.getBitDepth());
				}
				ImageStack tmp2Stack = tmp2.getStack();
				ImageStack stack2 = imp2.getStack();
				for (int t=1; t<=frames2; t++) 
				{
					imp1.setPositionWithoutUpdate(c, z, t);
					int n2 = imp2.getStackIndex(c, z, t);
					stack2.setPixels(tmp2Stack.getPixels(t), n2);
				}
			}
		}
		imp1.setPosition(c1, z1, t1);
		if (channels>1 && imp1.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp1).getMode());
			((CompositeImage)imp2).copyLuts(imp1);
		}
		return imp2;
	}


	ImagePlus resliceTimeLapseHyperstack(ImagePlus imp1) {
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
		for (int c=1; c<=channels; c++) 
		{
			ImageStack tmp1Stack = new ImageStack(width, height);
			for (int t=1; t<=frames; t++) {
				imp1.setPositionWithoutUpdate(c, z, t);
				tmp1Stack.addSlice(null, imp1.getProcessor());
			}
			ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
			cal=imp1.getCalibration().copy();
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
			if (imp2==null) 
			{
				String sPrefix = "Reslice ";
            	if(nKymoType == 1)
            		sPrefix = sPrefix + "(AVRG) of ";
            	else
            		sPrefix = sPrefix + "(MAX) of ";
				
            	imp2 = tmp2.createHyperStack(sPrefix + imp1.getTitle(), channels, 1, frames2, tmp2.getBitDepth());
			}
			
			ImageStack tmp2Stack = tmp2.getStack();
			ImageStack stack2 = imp2.getStack();
			for (int t=1; t<=frames2; t++) 
			{
				imp1.setPositionWithoutUpdate(c, z, t);
				int n2 = imp2.getStackIndex(c, z, t);
				stack2.setPixels(tmp2Stack.getPixels(z), n2);
			}
		}
		imp1.setPosition(c1, 1, t1);
		if (channels>1 && imp1.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp1).getMode());
			((CompositeImage)imp2).copyLuts(imp1);
		}
		return imp2;
	}

	ImagePlus resliceZHyperstack(ImagePlus imp1) {
		int channels = imp1.getNChannels();
		int slices = imp1.getNSlices();
		int c1 = imp1.getChannel();
		int z1 = imp1.getSlice();
		int width = imp1.getWidth();
		int height = imp1.getHeight();
		
		ImagePlus imp2 = null;

		Roi roi1 = imp1.getRoi();
		int t = 1;
		for (int c=1; c<=channels; c++) {
			ImageStack tmp1Stack = new ImageStack(width, height);
			for (int z=1; z<=slices; z++) {
				imp1.setPositionWithoutUpdate(c, z, t);
				tmp1Stack.addSlice(null, imp1.getProcessor());
			}
			ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
			tmp1.setCalibration(imp1.getCalibration());
			tmp1.setRoi(roi1);
            
			ImagePlus tmp2 = null;
            if(rgb)
            	tmp2 = resliceRGB(tmp1);
            else
            	tmp2 = resliceSimpleStack(tmp1);
			
			
			int slices2 = tmp2.getStackSize();
			if (imp2==null) 
			{
				String sPrefix = "Reslice ";
            	if(nKymoType == 1)
            		sPrefix = sPrefix + "(AVRG) of ";
            	else
            		sPrefix = sPrefix + "(MAX) of ";

				imp2 = tmp2.createHyperStack(sPrefix + imp1.getTitle(), channels, slices2, 1, tmp2.getBitDepth());
				
			}
			ImageStack tmp2Stack = tmp2.getStack();
			ImageStack stack2 = imp2.getStack();
			for (int z=1; z<=slices2; z++) 
			{
				imp1.setPositionWithoutUpdate(c, z, t);
				int n2 = imp2.getStackIndex(c, z, t);
				stack2.setPixels(tmp2Stack.getPixels(t), n2);
			}
		}
		imp1.setPosition(c1, z1, 1);
		if (channels>1 && imp1.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp1).getMode());
			((CompositeImage)imp2).copyLuts(imp1);
		}
		return imp2;
	}
	
	/** Function splits RGB image to three components,
	 reslices them independently and join results together	**/
	public ImagePlus resliceRGB(ImagePlus imp1)	
	{
		ImageStack[] colorStack;
		ImagePlus imp2 = null;
	
        ImageStack stack2 = null;
        ImagePlus red = null;
        ImagePlus green = null;
        ImagePlus blue = null;
        ImagePlus tmp1 = null;
        

		colorStack = ChannelSplitter.splitRGB(imp1.getStack(), true);
		tmp1 = new ImagePlus("tmp",colorStack[0]);
		red = resliceSimpleStack(tmp1);
		tmp1 = new ImagePlus("tmp",colorStack[1]);
		green = resliceSimpleStack(tmp1);
		tmp1 = new ImagePlus("tmp",colorStack[2]);
		blue = resliceSimpleStack(tmp1);
		stack2 = RGBStackMerge.mergeStacks(red.getStack(), green.getStack(), blue.getStack(), false);
		if(nKymoType == 1)
			imp2  = new ImagePlus("Reslice (AVRG) of "+imp1.getTitle(), stack2);
		else
			imp2  = new ImagePlus("Reslice (MAX) of "+imp1.getTitle(), stack2);
		return imp2;
	}

	
	/** Function assumes the input stack is 3D, single channel, non RGB **/
	public ImagePlus resliceSimpleStack(ImagePlus imp1) {
		 ImagePlus imp2;

		 int roiType = roi!=null?roi.getType():0;
		 //save global calibration
		 Calibration origCal = imp1.getCalibration();
		 boolean globalCalibration = false;
		 globalCalibration = imp1.getGlobalCalibration()!=null;
		 //remove it;
		 imp1.setGlobalCalibration(null);
		 Calibration tmpCal = origCal.copy();
		 tmpCal.pixelWidth = 1.0;
		 tmpCal.pixelHeight = 1.0;
		 tmpCal.pixelDepth = 1.0;
		 imp1.setCalibration(tmpCal);
		 inputZSpacing = 1.0;
		 if (roiType!=Roi.LINE)
			 outputZSpacing = 1.0;
		
		double zSpacing = inputZSpacing/imp1.getCalibration().pixelWidth;
		
		fStrokeWidth = roi.getStrokeWidth();

		String status = imp1.getStack().isVirtual()?"":null;
		IJ.showStatus("Reslice...");
		ImageProcessor ip2 = getSlice(imp1, status);
		if(nKymoType == 1)
			imp2 = new ImagePlus("Reslice (AVRG) of "+imp1.getShortTitle(), ip2);
		else
			imp2 = new ImagePlus("Reslice (MAX) of "+imp1.getShortTitle(), ip2);
 		if (globalCalibration)
			imp1.setGlobalCalibration(origCal);
		imp1.setCalibration(origCal);

  	    boolean horizontal = false;
		boolean vertical = false;

		if (roi!=null && roiType==Roi.LINE) {
			Line l = (Line)roi;
			horizontal  = (l.y2-l.y1)==0;
			vertical = (l.x2-l.x1)==0;
		}
		
		imp2.setCalibration(imp1.getCalibration());
		Calibration cal = imp2.getCalibration();
		if (horizontal) {
			cal.pixelWidth = origCal.pixelWidth;
			cal.pixelHeight = origCal.pixelDepth/zSpacing;
			cal.pixelDepth = origCal.pixelHeight*outputZSpacing;
		} else if (vertical) {
			cal.pixelWidth = origCal.pixelHeight;
			cal.pixelHeight = origCal.pixelDepth/zSpacing;
			cal.pixelDepth = origCal.pixelWidth*outputZSpacing;
		} else { // oblique line, polyLine or freeline
				if (origCal.pixelHeight==origCal.pixelWidth) {
					cal.pixelWidth = origCal.pixelWidth;
					cal.pixelHeight=origCal.pixelDepth/zSpacing;
					cal.pixelDepth = origCal.pixelWidth*outputZSpacing;
				} else {
					cal.pixelWidth = cal.pixelHeight=cal.pixelDepth=1.0;
					cal.setUnit("pixel");
				}
		 }
		 double tmp;
		 if (rotate) {// if rotated flip X and Y
				tmp = cal.pixelWidth;
				cal.pixelWidth = cal.pixelHeight;
				cal.pixelHeight = tmp;
		 }
		 return imp2;
	}
	
	
	
	ImageProcessor getSlice(ImagePlus imp1, String status) 
	{
		 
		 int roiType = roi!=null?roi.getType():0;
		 ImageStack stack = imp1.getStack();
		 int stackSize = stack.getSize();
		 ImageProcessor ip, ip2 = null;
		 int nSliceInitialN;
		 float[] line = null;
		

		 if((roiType==Roi.FREELINE && fStrokeWidth>1)||(roiType==Roi.POLYLINE && ((PolygonRoi)roi).isSplineFit()&& fStrokeWidth>1))
		 {
			 if(roiType == Roi.FREELINE && fStrokeWidth>1)
			 {
				 IJ.run(this.imp, "Fit Spline", "");
				 roi = this.imp.getRoi();
			 }
			 imp1.setRoi(roi);
			 nSliceInitialN = imp1.getCurrentSlice();			 			 			 
			 for (int i=0; i<stackSize; i++) 
			 {
				 imp1.setSliceWithoutUpdate(i+1);
				 ip = (new Straightener()).straightenLine(imp1, (int)fStrokeWidth);
				 line = getFreeHandProfileWide(ip);
				 if (i==0) 
				 {
					 //init output
					 ip2 = imp1.getChannelProcessor().createProcessor(line.length, stackSize);
				 }
				 putRow(ip2, 0, i, line, line.length);

				 if (status!=null) IJ.showStatus("Slicing: "+status +i+"/"+stackSize);
				 IJ.showProgress(i, stackSize);
			 
			 }
			 
			 //put back the slice we've used
			 imp1.setSliceWithoutUpdate(nSliceInitialN);
			 //correct the height of the kymograph
			 int new_width = (int)roi.getLength();
			 ip2.setInterpolationMethod(ij.process.ImageProcessor.BICUBIC);
			 ip2=ip2.resize(new_width,ip2.getHeight(), true);
			 
			 //show overlay
			 if(bAddRoiToOverlay)
			 {
				 impOverlay = imp1.getOverlay();
				 if(impOverlay==null)
					 impOverlay = new Overlay();

				 impOverlay.add(roi);
 			     this.imp.setOverlay(impOverlay);
				 this.imp.updateAndRepaintWindow();
				 this.imp.show();
				 bAddRoiToOverlay=false;
			 }
		 }
		 else
		 {
			 for (int i=0; i<stackSize; i++) 
			 {
				 ip = stack.getProcessor(i+1);
				 if (roiType == Roi.LINE)
					 line = getStraightLine(ip);
				 else //if	(roiType==Roi.POLYLINE || (roiType==Roi.FREELINE && fStrokeWidth<=1))					
				  	 line = getIrregularProfileWide(ip);

		
				 if (i==0) 
				 {
					 ip2 = ip.createProcessor(line.length, stackSize);
				 }
				 putRow(ip2, 0, i, line, line.length);
		
				 if (status!=null) IJ.showStatus("Slicing: "+status +i+"/"+stackSize);
				 IJ.showProgress(i, stackSize);
			 
			 }
		 }
		 
		 Calibration cal = imp1.getCalibration();
		 double zSpacing = inputZSpacing/cal.pixelWidth;
		 if (zSpacing!=1.0) 
		 {
				ip2.setInterpolate(true);
				if (rotate)
					ip2 = ip2.resize((int)(stackSize*zSpacing), line.length);
				else
					ip2 = ip2.resize(line.length, (int)(stackSize*zSpacing));
		 }
		 
		 if(rotate)
			 ip2 = ip2.rotateLeft();
		 return ip2;
	}
	
	boolean showDialog() 
	{

		GenericDialog gd = new GenericDialog("Wide Reslice "+version);
		
		gd.addChoice("Intensity value across width:", reslicetype, Prefs.get("KymoResliceWide.Type", "Maximum"));		
		gd.addCheckbox("Rotate 90 degrees", Prefs.get("KymoResliceWide.rotate", false));
		gd.addCheckbox("Add ROI to Overlay", Prefs.get("KymoResliceWide.addROI", false));
		gd.addCheckbox("Ignore image calibration", Prefs.get("KymoResliceWide.bCalIgnore", true));
		gd.addCheckbox("Ignore NaN values?", Prefs.get("KymoResliceWide.bIgnoreNaN", true));
		gd.showDialog();
		if (gd.wasCanceled())
            return false;
		nKymoType = gd.getNextChoiceIndex();
		Prefs.set("KymoResliceWide.Type", reslicetype[nKymoType]);
		
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
	

	/** build a kymo along a straight line **/
	private float[] getStraightLine(ImageProcessor ip) 
	{
		Line line = (Line)roi;
		float[] data = null;
		double[] interx = null;
		double[] intery = null;
				
					
		double x1, y1, x2, y2;
		x1 = line.x1;
		x2 = line.x2;
		y1 = line.y1;
		y2 = line.y2;
		double dx1 = x2-x1;
		double dy1 = y2-y1;
		double line_length = Math.sqrt(dx1*dx1 + dy1*dy1);
		int n1 = (int)Math.round(line_length);
			 
		data = new float[n1];
		interx = new double[n1];
		intery = new double[n1];
		double xinc = dx1/n1;
		double yinc = dy1/n1;
		double rx = x1;
		double ry = y1;		
		for (int i=0; i<n1; i++) 
		{
					
					data[i] = (float)ip.getInterpolatedValue(rx, ry);
			 		interx[i]=rx;
			 		intery[i]=ry;
					rx += xinc;
					ry += yinc;
		}
		
		int widthline = Math.round(fStrokeWidth);	
		
		
		impOverlay = imp.getOverlay();
		if(impOverlay==null)
		{
			impOverlay = new Overlay();
			 
		}
		 
		//one pix wide
		if (widthline<=1) 
		{
			 if(bAddRoiToOverlay)
			 {
				 impOverlay.add(roi);
 			     imp.setOverlay(impOverlay);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 bAddRoiToOverlay=false;			 
			 }			
			return data;
		}
		
		//wider line
		int nOffset;
		float[][] lineprof2D = new float[n1][widthline];

		if(widthline%2==0)
			 nOffset = widthline/2;
		else
			 nOffset = (widthline-1)/2;

		
		//find perpendicular direction
		 
		 dx1 /=line_length;
		 dy1 /=line_length;			
		 //perpendicular direction;
		 line_length=dx1;
		 dx1 = (-1)*dy1;
		 dy1 = line_length;
		 			 			 			 			 			 			
		 for(int k=0;k<n1;k++)
		 {
			 
			 for(int j=0;j<widthline;j++)
			 {
				 rx = interx[k]+dx1*(nOffset-j);
				 ry = intery[k]+dy1*(nOffset-j);
				 lineprof2D[k][j] = (float)ip.getInterpolatedValue(rx, ry);
				 
			 }
			 //adding scanning roi
			 if(bAddRoiToOverlay)
			 {
				 
				 Line lineROI = new Line(interx[k]+dx1*(nOffset),intery[k]+dy1*(nOffset),interx[k]+dx1*(nOffset-widthline+1),intery[k]+dy1*(nOffset-widthline+1));
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
			 bAddRoiToOverlay=false;
		 }
		 //now averaging or finding maximum
		 //average intensity
		 float curVal;
		 float nCount;
		 if(nKymoType == 1)
		 {
			 for(int k = 0; k<n1;k++)
			 {
				 data[k] = 0;
				 nCount = 0;
				 for(int j=0;j<widthline;j++)
				 {
					 curVal = lineprof2D[k][j];
					 if(bIgnoreNaN)
					 {
						 if(!Float.isNaN( curVal ))
						 {
							 data[k]+=curVal;
							 nCount++;							 
						 }
					 }
					 else
					 {
						 data[k]+=curVal;
						 nCount++;
					 }
				 }
				 data[k] /= nCount;
			 }
		 } 
		 //maximum intensity
		 else
		 {
			 float currMax;
			 for(int k = 0; k<n1;k++)
			 {
				 currMax = lineprof2D[k][0];					 
				 for(int j=1;j<widthline;j++)
				 {
					 if(lineprof2D[k][j]>currMax)
						 currMax=lineprof2D[k][j];
				 }
				 data[k]=currMax;
			 }
			 
		 }
		 return data;
	
	}
	
	
	float[] getIrregularProfileWide(ImageProcessor ip) {
		

		if (x==null)
			doIrregularSetup(roi);
		 
		 float[] values = new float[(int)length];
		 double[] interx = new double[(int)length];
		 double[] intery = new double[(int)length];
		 double leftOver = 1.0;
		 double distance = 0.0;
		 int index;		
		 for (int i=0; i<n; i++) {
				double len = segmentLengths[i];
				if (len==0.0)
					continue;
				double xinc = dx[i]/len;
				double yinc = dy[i]/len;
				double start = 1.0-leftOver;
				double rx = xbase+x[i]+start*xinc;
				double ry = ybase+y[i]+start*yinc;
				double len2 = len - start;
				int n2 = (int)len2;
				for (int j=0; j<=n2; j++) {
					index = (int)distance+j;
					if (index<values.length) {
						values[index] = (float)ip.getInterpolatedValue(rx, ry);
						 interx[index] =rx;
						 intery[index] =ry;
					}
					rx += xinc;
					ry += yinc;
				}
				distance += len;
				leftOver = len2 - n2;
		 }

		 
		 impOverlay = imp.getOverlay();
		 if(impOverlay==null)
		 {
			 impOverlay = new Overlay();
			 
		 }

		 int widthline = Math.round(fStrokeWidth);		 
		 //ok, we got main line now
		 if (widthline <=1)
		 {
			 if(bAddRoiToOverlay)
			 {
				 impOverlay.add(roi);
  			     imp.setOverlay(impOverlay);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 bAddRoiToOverlay=false;			 
			 }
			 
			 return values;
		 }
		 //wide line
		 //averaging/finding maximum in perpendicular direction
		 float[][] lineprof2D = new float[(int)length][widthline];
		 int nOffset;
		 double deltax,deltay,lendelta;
		 double rx,ry;
		 if(widthline%2==0)
			 nOffset = widthline/2;
		 else
			 nOffset = (widthline-1)/2;
		 
		 //initial point
		 
		 //direction
		 deltax = interx[1]-interx[0];
		 deltay = intery[1]-intery[0];
		 //normalization
		 lendelta = Math.sqrt(deltax*deltax+deltay*deltay);
		 deltax /=lendelta;
		 deltay /=lendelta;
		 //perpendicular direction;
		 lendelta=deltax;
		 deltax = (-1)*deltay;
		 deltay = lendelta;
		 
		 for(int j=0;j<widthline;j++)
		 {
			 rx=interx[0]+deltax*(nOffset-j);
			 ry=intery[0]+deltay*(nOffset-j);
			 lineprof2D[0][j] = (float)ip.getInterpolatedValue(rx, ry);
			 				 
		 }

		 if(bAddRoiToOverlay)
		 {				 
			 Line lineROI = new Line(interx[0]+deltax*(nOffset),intery[0]+deltay*(nOffset),interx[0]+deltax*(nOffset-widthline+1),intery[0]+deltay*(nOffset-widthline+1));
			 lineROI.setStrokeWidth(1.);
			 impOverlay.add(lineROI);
		 }			 
		 //all other points
		 for(int k=1;k<(int)length;k++)
		 {
			 //direction
			 deltax = interx[k]-interx[k-1];
			 deltay = intery[k]-intery[k-1];
			 //normalization
			 lendelta = Math.sqrt(deltax*deltax+deltay*deltay);
			 deltax /=lendelta;
			 deltay /=lendelta;
			 //perpendicular direction;
			 lendelta=deltax;
			 deltax = (-1)*deltay;
			 deltay = lendelta;
			 
			 for(int j=0;j<widthline;j++)
			 {
				 rx = interx[k]+deltax*(nOffset-j);
				 ry = intery[k]+deltay*(nOffset-j);
				 lineprof2D[k][j] = (float)ip.getInterpolatedValue(rx, ry);
				 
			 }
			 if(bAddRoiToOverlay)
			 {
				 
				 Line lineROI = new Line(interx[k]+deltax*(nOffset),intery[k]+deltay*(nOffset),interx[k]+deltax*(nOffset-widthline+1),intery[k]+deltay*(nOffset-widthline+1));
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
				bAddRoiToOverlay=false;
		 }
		 //now averaging or finding maximum
		 //average intensity
		 if(nKymoType == 1)
		 {
			 float curVal;
			 float nCount;
			 for(int k = 0; k<(int)length;k++)
			 {
				 values[k] = 0;
				 nCount = 0;
				 for(int j=0;j<widthline;j++)
				 {
					 curVal = lineprof2D[k][j];
					 if(bIgnoreNaN)
					 {
						 if(!Float.isNaN( curVal ))
						 {
							 values[k]+=curVal;
							 nCount++;							 
						 }						 
					 }
					 else
					 {
						 values[k]+=curVal;
						 nCount++;
					 }
				 }
				 values[k] /= nCount;
			 }
		 } 
		 //maximum intensity
		 else
		 {
			 float currMax;
			 for(int k = 0; k<(int)length;k++)
			 {
				 currMax = lineprof2D[k][0];					 
				 for(int j=1;j<widthline;j++)
				 {
					 if(lineprof2D[k][j]>currMax)
						 currMax=lineprof2D[k][j];
				 }
				 values[k]=currMax;
			 }
			 
		 }
		 return values;
		 
		 

		
	}
	float [] getFreeHandProfileWide(ImageProcessor ip)
	{
		
		//if (x==null)
//			doIrregularSetup(roi);
		length = ip.getWidth();
		float[] values = new float[(int)length];
		int widthline = Math.round(fStrokeWidth);	
		
		//new ImagePlus("zzz", ip2.duplicate()).show();
		if(nKymoType == 1)
		 {
			 float curVal;
			 float nCount;
			 for(int k = 0; k<(int)length;k++)
			 {
				 values[k]=0;
				 nCount = 0;
				 for(int j=0;j<widthline;j++)
				 {
					 curVal = (float)ip.getInterpolatedValue(k, j);
					 if(bIgnoreNaN)
					 {
						 if(!Float.isNaN( curVal ))
						 {
							 values[k]+= curVal;
							 nCount++;						 
						 } 
					 }
					 else
					 {
						 values[k]+= curVal;
						 nCount++;
					 } 
				 }
				 values[k]/=nCount;
			 }
		 } 
		 //maximum intensity
		 else
		 {
			 float currMax;
			 float currVal;
			 for(int k = 0; k<(int)length;k++)
			 {
				 
					 currMax = (float)ip.getInterpolatedValue(k, 0);					 
					 
				 
				 
				 for(int j=1;j<widthline;j++)
				 {
					 currVal = (float)ip.getInterpolatedValue(k, j);						 						 
					 if(currVal>currMax)
						 currMax=currVal;
				 }
				 values[k]=currMax;
			 }
			 
		 }
						
		return values;
		
	}
	
	
	public void putRow(ImageProcessor ip, int x_, int y_, float[] data_, int length_) 
	{
				for (int i=0; i<length_; i++)
					ip.putPixelValue(x_++, y_, data_[i]);
	}
	
	
	ImageStack createOutputStack(ImagePlus imp_, ImageProcessor ip) {
		 int bitDepth = imp_.getBitDepth();
		 int w2=ip.getWidth(), h2=ip.getHeight(), d2=outputSlices;
		 int flags = NewImage.FILL_BLACK + NewImage.CHECK_AVAILABLE_MEMORY;
		 ImagePlus imp2 = NewImage.createImage("temp", w2, h2, d2, bitDepth, flags);
		 if (imp2!=null && imp2.getStackSize()==d2)
				IJ.showStatus("Reslice... (press 'Esc' to abort)");
		 if (imp2==null)
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
		 for (int i=0; i<n; i++) {
				x[i] = ix[i];
				y[i] = iy[i];
		 }
		 if (roi1.getType()==Roi.FREELINE) 
		 {
				// smooth line
				for (int i=1; i<n-1; i++) {
					x[i] = (x[i-1] + x[i] + x[i+1])/3.0+0.5;
					y[i] = (y[i-1] + y[i] + y[i+1])/3.0+0.5;
				}
		 }
		 Rectangle r = roi1.getBounds();
		 xbase = r.x;
		 ybase = r.y;
		 length = 0.0;
		 double segmentLength;
		 double xdelta, ydelta;
		 segmentLengths = new double[n];
		 dx = new double[n];
		 dy = new double[n];
		 for (int i=0; i<(n-1); i++) 
		 {
				xdelta = x[i+1] - x[i];
				ydelta = y[i+1] - y[i];
				segmentLength = Math.sqrt(xdelta*xdelta+ydelta*ydelta);
				length += segmentLength;
				segmentLengths[i] = segmentLength;
				dx[i] = xdelta;
				dy[i] = ydelta;
		 }
	}
	public static void main( final String[] args ) 
	{
		// open an ImageJ window
		new ImageJ();
		IJ.open("/home/eugene/Desktop/projects/Kymoreslicewide/HyperStack_NaN.tif");
		IJ.open("/home/eugene/Desktop/projects/Kymoreslicewide/NaN.roi");
		KymoResliceWide_ krw = new KymoResliceWide_();
		krw.run(null);


	}
	
	
}
