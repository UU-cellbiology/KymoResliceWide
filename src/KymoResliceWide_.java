

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
	private static String version = "ver.0.5";
	private ImagePlus imp;	
	private static boolean rotate;	
	private static final String[] reslicetype = {"Maximum", "Average",};
	private static int nKymoType;
	private boolean rgb;
	private boolean noRoi;
	private int outputSlices = 1;
	private double inputZSpacing = 1.0;
	private double outputZSpacing = 1.0;
	private boolean bAddRoiToOverlay=true;
	private float fStrokeWidth;
	/** whether to ignore calibration or not **/
	private boolean bCalIgnore;
	Overlay SpotsPositions;
	
	
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
		
		if (!showDialog(imp))
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
				imp2 = reslice(imp);			
		}
		
		
		if (imp2==null)
			return;
		ImageProcessor ip = imp.getProcessor();
		double min = ip.getMin();
		double max = ip.getMax();
		if (!rgb) imp2.getProcessor().setMinAndMax(min, max);
		//remove calibration
		if(bCalIgnore)
			imp2.setCalibration(new Calibration());
		imp2.show();
		if (noRoi)
			imp.deleteRoi();
		else
			imp.draw();
		IJ.showStatus(IJ.d2s(((System.currentTimeMillis()-startTime)/1000.0),2)+" seconds");

		
	}
	
	
	ImagePlus resliceHyperstack(ImagePlus imp) {
		Calibration cal;
		int channels = imp.getNChannels();
		int slices = imp.getNSlices();
		int frames = imp.getNFrames();
		if (slices==1)
			return resliceTimeLapseHyperstack(imp);
		if (frames==1)
			return resliceZHyperstack(imp);
		int c1 = imp.getChannel();
		int z1 = imp.getSlice();
		int t1 = imp.getFrame();
		int width = imp.getWidth();
		int height = imp.getHeight();
		ImagePlus imp2 = null;
		ImageStack stack2 = null;
		Roi roi = imp.getRoi();
		//for (int t=1; t<=frames; t++) {
		for (int z=1; z<=slices; z++) {
			for (int c=1; c<=channels; c++) {
				ImageStack tmp1Stack = new ImageStack(width, height);
				//for (int z=1; z<=slices; z++) {
				for (int t=1; t<=frames; t++) {
					imp.setPositionWithoutUpdate(c, z, t);
					tmp1Stack.addSlice(null, imp.getProcessor());
				}
				ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
				//tmp1.setCalibration(imp.getCalibration());
				//change time scale calibration
				cal=imp.getCalibration().copy();
				cal.pixelDepth=cal.frameInterval;
				tmp1.setCalibration(cal);
				tmp1.setRoi(roi);
		
	            ImagePlus tmp2 = null;
	            if(rgb)
	            	tmp2 = resliceRGB(tmp1);
	            else
	            	tmp2 = reslice(tmp1);
				
				//int slices2 = tmp2.getStackSize();
	            int frames2 = tmp2.getStackSize();
				if (imp2==null) 
				{
	            	if(nKymoType == 1)
	            		imp2 = tmp2.createHyperStack("Reslice (AVRG) of "+imp.getTitle(), channels, slices, frames2, tmp2.getBitDepth());
	            	else
	            		imp2 = tmp2.createHyperStack("Reslice (MAX) of "+imp.getTitle(), channels, slices, frames2, tmp2.getBitDepth());
					stack2 = imp2.getStack();
				}
				ImageStack tmp2Stack = tmp2.getStack();
				//for (int z=1; z<=slices2; z++) {
				for (int t=1; t<=frames2; t++) {
					imp.setPositionWithoutUpdate(c, z, t);
					int n2 = imp2.getStackIndex(c, z, t);
					stack2.setPixels(tmp2Stack.getPixels(t), n2);
				}
			}
		}
		imp.setPosition(c1, z1, t1);
		if (channels>1 && imp.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp).getMode());
			((CompositeImage)imp2).copyLuts(imp);
		}
		return imp2;
	}


	ImagePlus resliceTimeLapseHyperstack(ImagePlus imp) {
		int channels = imp.getNChannels();
		int frames = imp.getNFrames();
		int c1 = imp.getChannel();
		int t1 = imp.getFrame();
		int width = imp.getWidth();
		int height = imp.getHeight();
		Calibration cal;
		ImagePlus imp2 = null;
		ImageStack stack2 = null;
		Roi roi = imp.getRoi();
		int z = 1;
		for (int c=1; c<=channels; c++) {
			ImageStack tmp1Stack = new ImageStack(width, height);
			for (int t=1; t<=frames; t++) {
				imp.setPositionWithoutUpdate(c, z, t);
				tmp1Stack.addSlice(null, imp.getProcessor());
			}
			ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
			cal=imp.getCalibration().copy();
			cal.pixelDepth=cal.frameInterval;
			tmp1.setCalibration(cal);
			//tmp1.setCalibration(imp.getCalibration());
			tmp1.setRoi(roi);
            
			ImagePlus tmp2 = null;
            if(rgb)
            	tmp2 = resliceRGB(tmp1);
            else
            	tmp2 = reslice(tmp1);
			
			
			int frames2 = tmp2.getStackSize();
			if (imp2==null) {
				if(nKymoType == 1)
					imp2 = tmp2.createHyperStack("Reslice (AVRG) of "+imp.getTitle(), channels, 1, frames2, tmp2.getBitDepth());
				else
					imp2 = tmp2.createHyperStack("Reslice (MAX) of "+imp.getTitle(), channels, 1, frames2, tmp2.getBitDepth());
				stack2 = imp2.getStack();
			}
			ImageStack tmp2Stack = tmp2.getStack();
			for (int t=1; t<=frames2; t++) {
				imp.setPositionWithoutUpdate(c, z, t);
				int n2 = imp2.getStackIndex(c, z, t);
				stack2.setPixels(tmp2Stack.getPixels(z), n2);
			}
		}
		imp.setPosition(c1, 1, t1);
		if (channels>1 && imp.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp).getMode());
			((CompositeImage)imp2).copyLuts(imp);
		}
		return imp2;
	}

	ImagePlus resliceZHyperstack(ImagePlus imp) {
		int channels = imp.getNChannels();
		int slices = imp.getNSlices();
		int c1 = imp.getChannel();
		int z1 = imp.getSlice();
		int width = imp.getWidth();
		int height = imp.getHeight();
		
		ImagePlus imp2 = null;
		ImageStack stack2 = null;
		Roi roi = imp.getRoi();
		int t = 1;
		for (int c=1; c<=channels; c++) {
			ImageStack tmp1Stack = new ImageStack(width, height);
			for (int z=1; z<=slices; z++) {
				imp.setPositionWithoutUpdate(c, z, t);
				tmp1Stack.addSlice(null, imp.getProcessor());
			}
			ImagePlus tmp1 = new ImagePlus("tmp", tmp1Stack);
			tmp1.setCalibration(imp.getCalibration());
			tmp1.setRoi(roi);
            
			ImagePlus tmp2 = null;
            if(rgb)
            	tmp2 = resliceRGB(tmp1);
            else
            	tmp2 = reslice(tmp1);
			
			
			int slices2 = tmp2.getStackSize();
			if (imp2==null) {
				if(nKymoType == 1)
					imp2 = tmp2.createHyperStack("Reslice (AVRG) of "+imp.getTitle(), channels, slices2, 1, tmp2.getBitDepth());
				else
					imp2 = tmp2.createHyperStack("Reslice (MAX) of "+imp.getTitle(), channels, slices2, 1, tmp2.getBitDepth());
				stack2 = imp2.getStack();
			}
			ImageStack tmp2Stack = tmp2.getStack();
			for (int z=1; z<=slices2; z++) {
				imp.setPositionWithoutUpdate(c, z, t);
				int n2 = imp2.getStackIndex(c, z, t);
				stack2.setPixels(tmp2Stack.getPixels(t), n2);
			}
		}
		imp.setPosition(c1, z1, 1);
		if (channels>1 && imp.isComposite()) {
			imp2 = new CompositeImage(imp2, ((CompositeImage)imp).getMode());
			((CompositeImage)imp2).copyLuts(imp);
		}
		return imp2;
	}
	//split RGB image to three components,
	//reslice them independently and join together	
	public ImagePlus resliceRGB(ImagePlus imp)	
	{
		ImageStack[] colorStack;
		ImagePlus imp2 = null;
	
        ImageStack stack2 = null;
        ImagePlus red = null;
        ImagePlus green = null;
        ImagePlus blue = null;
        ImagePlus tmp1 = null;
        

		colorStack = ChannelSplitter.splitRGB(imp.getStack(), true);
		tmp1 = new ImagePlus("tmp",colorStack[0]);
		red = reslice(tmp1);
		tmp1 = new ImagePlus("tmp",colorStack[1]);
		green = reslice(tmp1);
		tmp1 = new ImagePlus("tmp",colorStack[2]);
		blue = reslice(tmp1);
		stack2 = RGBStackMerge.mergeStacks(red.getStack(), green.getStack(), blue.getStack(), false);
		if(nKymoType == 1)
			imp2  = new ImagePlus("Reslice (AVRG) of "+imp.getTitle(), stack2);
		else
			imp2  = new ImagePlus("Reslice (MAX) of "+imp.getTitle(), stack2);
		return imp2;
	}

	
	public ImagePlus reslice(ImagePlus imp) {
		 ImagePlus imp2;

		 int roiType = roi!=null?roi.getType():0;
		 Calibration origCal = imp.getCalibration();
		 boolean globalCalibration = false;
		
 		 globalCalibration = imp.getGlobalCalibration()!=null;
		 imp.setGlobalCalibration(null);
		 Calibration tmpCal = origCal.copy();
		 tmpCal.pixelWidth = 1.0;
		 tmpCal.pixelHeight = 1.0;
		 tmpCal.pixelDepth = 1.0;
		 imp.setCalibration(tmpCal);
		 inputZSpacing = 1.0;
		 if (roiType!=Roi.LINE)
			 outputZSpacing = 1.0;
		
		double zSpacing = inputZSpacing/imp.getCalibration().pixelWidth;
		
		fStrokeWidth = roi.getStrokeWidth();

		String status = imp.getStack().isVirtual()?"":null;
		IJ.showStatus("Reslice...");
		ImageProcessor ip2 = getSlice(imp, status);
		if(nKymoType == 1)
			imp2 = new ImagePlus("Reslice (AVRG) of "+imp.getShortTitle(), ip2);
		else
			imp2 = new ImagePlus("Reslice (MAX) of "+imp.getShortTitle(), ip2);
 		if (globalCalibration)
			imp.setGlobalCalibration(origCal);
		imp.setCalibration(origCal);

  	    boolean horizontal = false;
		boolean vertical = false;

		if (roi!=null && roiType==Roi.LINE) {
			Line l = (Line)roi;
			horizontal  = (l.y2-l.y1)==0;
			vertical = (l.x2-l.x1)==0;
		}
		
		imp2.setCalibration(imp.getCalibration());
		Calibration cal = imp2.getCalibration();
		if (horizontal) {
			cal.pixelWidth = origCal.pixelWidth;
			cal.pixelHeight = origCal.pixelDepth/zSpacing;
			cal.pixelDepth = origCal.pixelHeight*outputZSpacing;
		} else if (vertical) {
			cal.pixelWidth = origCal.pixelHeight;
			cal.pixelHeight = origCal.pixelDepth/zSpacing;
			cal.pixelDepth = origCal.pixelWidth*outputZSpacing;;
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
	
	
	
	ImageProcessor getSlice(ImagePlus imp, String status) {
		 
		 int roiType = roi!=null?roi.getType():0;
		 ImageStack stack = imp.getStack();
		 int stackSize = stack.getSize();
		 ImageProcessor ip,ip2=null;
		 int nSliceInitialN;
		 float[] line = null;
		

		 if((roiType==Roi.FREELINE && fStrokeWidth>1)||(roiType==Roi.POLYLINE && ((PolygonRoi)roi).isSplineFit()&& fStrokeWidth>1))
		 {
			 if(roiType==Roi.FREELINE && fStrokeWidth>1)
			 {
				 IJ.run(this.imp, "Fit Spline", "");
				 roi = this.imp.getRoi();
			 }
			 imp.setRoi(roi);
			 nSliceInitialN = imp.getCurrentSlice();			 			 			 
			 for (int i=0; i<stackSize; i++) {
				 imp.setSliceWithoutUpdate(i+1);
				 ip = (new Straightener()).straightenLine(imp, (int)fStrokeWidth);
				 line = getFreeHandProfileWide(ip);
				 if (i==0) ip2 = imp.getChannelProcessor().createProcessor(line.length, stackSize);
				 putRow(ip2, 0, i, line, line.length);

				 if (status!=null) IJ.showStatus("Slicing: "+status +i+"/"+stackSize);
				 IJ.showProgress(i, stackSize);
			 
			 }
			 
			 //put back the slice we've used
			 imp.setSliceWithoutUpdate(nSliceInitialN);
			 //correct the height of the kymograph
			 int new_width = (int)roi.getLength();
			 ip2.setInterpolationMethod(ij.process.ImageProcessor.BICUBIC);
			 ip2=ip2.resize(new_width,ip2.getHeight(), true);
			 
			 //show overlay
			 if(bAddRoiToOverlay)
			 {
				 SpotsPositions = imp.getOverlay();
				 if(SpotsPositions==null)
					 SpotsPositions = new Overlay();

				 SpotsPositions.add(roi);
 			     this.imp.setOverlay(SpotsPositions);
				 this.imp.updateAndRepaintWindow();
				 this.imp.show();
				 bAddRoiToOverlay=false;
			 }
		 }
		 else
		 {
			 for (int i=0; i<stackSize; i++) {
				 ip = stack.getProcessor(i+1);
				 if (roiType==Roi.LINE)
					 line = getLine(ip);
				 else if	(roiType==Roi.POLYLINE || (roiType==Roi.FREELINE && fStrokeWidth<=1))					
				  	 line = getIrregularProfileWide(ip);

		
				 if (i==0) ip2 = ip.createProcessor(line.length, stackSize);
						putRow(ip2, 0, i, line, line.length);
		
				 if (status!=null) IJ.showStatus("Slicing: "+status +i+"/"+stackSize);
				 IJ.showProgress(i, stackSize);
			 
			 }
		 }
		 
		 Calibration cal = imp.getCalibration();
		 double zSpacing = inputZSpacing/cal.pixelWidth;
		 if (zSpacing!=1.0) {
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
	
	boolean showDialog(ImagePlus imp) {

		GenericDialog gd = new GenericDialog("Wide Reslice "+version);
		
		gd.addChoice("Intensity value across width:", reslicetype, Prefs.get("KymoResliceWide.Type", "Maximum"));		
		gd.addCheckbox("Rotate 90 degrees", Prefs.get("KymoResliceWide.rotate", false));
		gd.addCheckbox("Add ROI to Overlay", Prefs.get("KymoResliceWide.addROI", false));
		gd.addCheckbox("Ignore image calibration", Prefs.get("KymoResliceWide.bCalIgnore", true));
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
		return true;
	}
	

	
	private float[] getLine(ImageProcessor ip) {
		Line line = (Line)roi;
		float[] data = null;
		double[] interx = null;
		double[] intery = null;
				
					
		double x1, y1, x2, y2;
		x1 = line.x1;
		x2 = line.x2;
		y1 = line.y1;
		y2 = line.y2;
		double dx = x2-x1;
		double dy = y2-y1;
		double line_length = Math.sqrt(dx*dx + dy*dy);
		int n = (int)Math.round(line_length);
			 
		data = new float[n];
		interx = new double[n];
		intery = new double[n];
		double xinc = dx/n;
		double yinc = dy/n;
		double rx = x1;
		double ry = y1;		
		for (int i=0; i<n; i++) {
					
					data[i] = (float)ip.getInterpolatedValue(rx, ry);
			 		interx[i]=rx;
			 		intery[i]=ry;
					rx += xinc;
					ry += yinc;
		}
		
		int widthline = Math.round(fStrokeWidth);	
		
		//Overlay SpotsPositions;
		SpotsPositions = imp.getOverlay();
		if(SpotsPositions==null)
		{
			SpotsPositions = new Overlay();
			 
		}
		 
		//one pix wide
		if (widthline<=1) 
		{
			 if(bAddRoiToOverlay)
			 {
				 SpotsPositions.add(roi);
 			     imp.setOverlay(SpotsPositions);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 bAddRoiToOverlay=false;			 
			 }			
			return data;
		}
		else
		{
			int nOffset;
			float[][] lineprof2D = new float[n][widthline];
		
			if(widthline%2==0)
				 nOffset = widthline/2;
			else
				 nOffset = (widthline-1)/2;

			
			//find perpendicular direction
			 
			 dx /=line_length;
			 dy /=line_length;			
			 //perpendicular direction;
			 line_length=dx;
			 dx = (-1)*dy;
			 dy = line_length;
			 			 			 			 			 			 			
			 for(int k=0;k<n;k++)
			 {
				 
				 for(int j=0;j<widthline;j++)
				 {
					 rx = interx[k]+dx*(nOffset-j);
					 ry = intery[k]+dy*(nOffset-j);
					 lineprof2D[k][j] = (float)ip.getInterpolatedValue(rx, ry);
					 
				 }
				 //adding scanning roi
				 if(bAddRoiToOverlay)
				 {
					 
					 Line lineROI = new Line(interx[k]+dx*(nOffset),intery[k]+dy*(nOffset),interx[k]+dx*(nOffset-widthline+1),intery[k]+dy*(nOffset-widthline+1));
					 lineROI.setStrokeWidth(1.);
					 SpotsPositions.add(lineROI);
				 }
			 
			 }			 
			 
			 //some debuggin
			 if(bAddRoiToOverlay)
			 {
				 imp.setOverlay(SpotsPositions);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 bAddRoiToOverlay=false;
			 }
			 //now averaging or finding maximum
			 //average intensity
			 if(nKymoType == 1)
			 {
				 for(int k = 0; k<n;k++)
				 {
					 data[k]=0;
					 for(int j=0;j<widthline;j++)
					 {
						 data[k]+=lineprof2D[k][j];
					 }
					 data[k]/=(float)widthline;
				 }
			 } 
			 //maximum intensity
			 else
			 {
				 float currMax;
				 for(int k = 0; k<n;k++)
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

		 
		 SpotsPositions = imp.getOverlay();
		 if(SpotsPositions==null)
		 {
			 SpotsPositions = new Overlay();
			 
		 }

		 int widthline = Math.round(fStrokeWidth);		 
		 //ok, we got main line now
		 if (widthline <=1)
		 {
			 if(bAddRoiToOverlay)
			 {
				 SpotsPositions.add(roi);
  			     imp.setOverlay(SpotsPositions);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 bAddRoiToOverlay=false;			 
			 }
			 
			 return values;
		 }
		 //averaging/finding maximum in perpendicular direction
		 else
		 {
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
				 SpotsPositions.add(lineROI);
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
					 SpotsPositions.add(lineROI);
				 }
					 
			 }
			 
			 //some debuggin
			 if(bAddRoiToOverlay)
			 {
				    imp.setOverlay(SpotsPositions);
					imp.updateAndRepaintWindow();
					imp.show();
					bAddRoiToOverlay=false;
			 }
			 //now averaging or finding maximum
			 //average intensity
			 if(nKymoType == 1)
			 {
				 for(int k = 0; k<(int)length;k++)
				 {
					 values[k]=0;
					 for(int j=0;j<widthline;j++)
					 {
						 values[k]+=lineprof2D[k][j];
					 }
					 values[k]/=(float)widthline;
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
		 
		 

		
	}
	float [] getFreeHandProfileWide(ImageProcessor ip)
	{
		
		//if (x==null)
//			doIrregularSetup(roi);
		length = (float)ip.getWidth();
		float[] values = new float[(int)length];
		int widthline = Math.round(fStrokeWidth);	
		
		//new ImagePlus("zzz", ip2.duplicate()).show();
		if(nKymoType == 1)
		 {
			 for(int k = 0; k<(int)length;k++)
			 {
				 values[k]=0;
				 for(int j=0;j<widthline;j++)
				 {
					 values[k]+= (float)ip.getInterpolatedValue(k, j);					 
						 
				 }
				 values[k]/=(float)widthline;
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
	
	
	public void putRow(ImageProcessor ip, int x, int y, float[] data, int length) {

				for (int i=0; i<length; i++)
					ip.putPixelValue(x++, y, data[i]);

	}
	
	
	ImageStack createOutputStack(ImagePlus imp, ImageProcessor ip) {
		 int bitDepth = imp.getBitDepth();
		 int w2=ip.getWidth(), h2=ip.getHeight(), d2=outputSlices;
		 int flags = NewImage.FILL_BLACK + NewImage.CHECK_AVAILABLE_MEMORY;
		 ImagePlus imp2 = NewImage.createImage("temp", w2, h2, d2, bitDepth, flags);
		 if (imp2!=null && imp2.getStackSize()==d2)
				IJ.showStatus("Reslice... (press 'Esc' to abort)");
		 if (imp2==null)
				return null;
		 else {
				ImageStack stack2 = imp2.getStack();
				stack2.setColorModel(ip.getColorModel());
				return stack2;
		 }
	}
	
	void doIrregularSetup(Roi roi) {
		 n = ((PolygonRoi)roi).getNCoordinates();
		 int[] ix = ((PolygonRoi)roi).getXCoordinates();
		 int[] iy = ((PolygonRoi)roi).getYCoordinates();
		 x = new double[n];
		 y = new double[n];
		 for (int i=0; i<n; i++) {
				x[i] = ix[i];
				y[i] = iy[i];
		 }
		 if (roi.getType()==Roi.FREELINE) {
				// smooth line
				for (int i=1; i<n-1; i++) {
					x[i] = (x[i-1] + x[i] + x[i+1])/3.0+0.5;
					y[i] = (y[i-1] + y[i] + y[i+1])/3.0+0.5;
				}
		 }
		 Rectangle r = roi.getBounds();
		 xbase = r.x;
		 ybase = r.y;
		 length = 0.0;
		 double segmentLength;
		 double xdelta, ydelta;
		 segmentLengths = new double[n];
		 dx = new double[n];
		 dy = new double[n];
		 for (int i=0; i<(n-1); i++) {
				xdelta = x[i+1] - x[i];
				ydelta = y[i+1] - y[i];
				segmentLength = Math.sqrt(xdelta*xdelta+ydelta*ydelta);
				length += segmentLength;
				segmentLengths[i] = segmentLength;
				dx[i] = xdelta;
				dy[i] = ydelta;
		 }
	}

	
	
}
