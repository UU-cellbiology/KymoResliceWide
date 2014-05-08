

import java.awt.Rectangle;




import ij.gui.Line;
import ij.gui.NewImage;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.Straightener;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.Prefs;



public class KymoResliceWide_ implements PlugIn 
{
	private static String version = "ver.0.3";
	private ImagePlus imp;	
	private static boolean rotate;	
	private static final String[] reslicetype = {"Maximum", "Average",};
	private static int nKymoType;
	private boolean rgb;
	private boolean noRoi;
	private int outputSlices = 1;
	private double inputZSpacing = 1.0;
	private double outputZSpacing = 1.0;
	private boolean debugroigen=true;
	private float fStrokeWidth;
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
		//just straightforward ImageJ reslicer!!
		imp = WindowManager.getCurrentImage();
		if (imp==null) {
			IJ.noImage();
			return;
		}
		else if (imp.getType() != ImagePlus.GRAY8 && imp.getType() != ImagePlus.GRAY16 && imp.getType() != ImagePlus.GRAY32) 
		{
		    IJ.error("Greyscale image required");
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
//			imp2 = resliceHyperstack(imp);
			IJ.error("Reslice...", "Sorry, current version of plugin cannot work with HyperStacks.");
			return;
		}
		else
			imp2 = reslice(imp);
		if (imp2==null)
			return;
		ImageProcessor ip = imp.getProcessor();
		double min = ip.getMin();
		double max = ip.getMax();
		if (!rgb) imp2.getProcessor().setMinAndMax(min, max);
		imp2.show();
		if (noRoi)
			imp.deleteRoi();
		else
			imp.draw();
		IJ.showStatus(IJ.d2s(((System.currentTimeMillis()-startTime)/1000.0),2)+" seconds");

		
	}
	
	public ImagePlus reslice(ImagePlus imp) {
		 ImagePlus imp2;
		 //Roi roi = imp.getRoi();
		 int roiType = roi!=null?roi.getType():0;
		 Calibration origCal = imp.getCalibration();
		 boolean globalCalibration = false;
		// if (nointerpolate) {// temporarily clear spatial calibration
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
		 //}
		double zSpacing = inputZSpacing/imp.getCalibration().pixelWidth;
		fStrokeWidth = roi.getStrokeWidth();
		// if (roiType==Roi.LINE) {
//				imp2 = resliceLine(imp);
	//	 } else {// we assert roiType==Roi.POLYLINE || roiType==Roi.FREELINE
				String status = imp.getStack().isVirtual()?"":null;
				IJ.showStatus("Reslice...");
				ImageProcessor ip2 = getSlice(imp, status);
				if(nKymoType == 1)
					imp2 = new ImagePlus("Reslice (AVRG) of "+imp.getShortTitle(), ip2);
				else
					imp2 = new ImagePlus("Reslice (MAX) of "+imp.getShortTitle(), ip2);
		// }
		 //if (nointerpolate) { // restore calibration
		 		if (globalCalibration)
					imp.setGlobalCalibration(origCal);
				imp.setCalibration(origCal);
		 //}
		 // create Calibration for new stack
		 // start from previous cal and swap appropriate fields
		 boolean horizontal = false;
		 boolean vertical = false;
		/*if (roi==null || roiType==Roi.RECTANGLE) {
			if (startAt.equals(starts[0]) || startAt.equals(starts[2]))
				horizontal = true;
			else
				vertical = true;
		} */
		if (roi!=null && roiType==Roi.LINE) {
			Line l = (Line)roi;
			horizontal  = (l.y2-l.y1)==0;
			vertical = (l.x2-l.x1)==0;
		}
		//if (imp2==null) return null;
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
		 //Roi roi = imp.getRoi();
		 int roiType = roi!=null?roi.getType():0;
		 ImageStack stack = imp.getStack();
		 int stackSize = stack.getSize();
		 ImageProcessor ip,ip2=null;
		 int nSliceInitialN;
		 float[] line = null;
		
		//boolean vertical = x1==x2 && (roi==null||roiType==Roi.RECTANGLE);
		//if (rotate) vertical = !vertical;
		 if((roiType==Roi.FREELINE && fStrokeWidth>1)||(roiType==Roi.POLYLINE && ((PolygonRoi)roi).isSplineFit()&& fStrokeWidth>1))
		 {
			 if(roiType==Roi.FREELINE && fStrokeWidth>1)
			 {
				 IJ.run(imp, "Fit Spline", "");
				 roi = imp.getRoi();
			 }
			
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
			 if(debugroigen)
			 {
				 SpotsPositions = imp.getOverlay();
				 if(SpotsPositions==null)
					 SpotsPositions = new Overlay();

				 SpotsPositions.add(roi);
 			     imp.setOverlay(SpotsPositions);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 debugroigen=false;
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
			//	 else
				//	 line = getFreeHandProfileWide(ip);
		
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
		gd.showDialog();
		if (gd.wasCanceled())
            return false;
		nKymoType = gd.getNextChoiceIndex();
		Prefs.set("KymoResliceWide.Type", reslicetype[nKymoType]);
		rotate = gd.getNextBoolean();
		Prefs.set("KymoResliceWide.rotate", rotate);
		debugroigen = gd.getNextBoolean();
		Prefs.set("KymoResliceWide.addROI", debugroigen);
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
					
					if (rgb) {
						int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(rx, ry);
						data[i] = Float.intBitsToFloat(rgbPixel&0xffffff);
					} else
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
			 if(debugroigen)
			 {
				 SpotsPositions.add(roi);
 			     imp.setOverlay(SpotsPositions);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 debugroigen=false;			 
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
					 if (rgb) {
							int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(rx, ry);
							lineprof2D[k][j] = Float.intBitsToFloat(rgbPixel&0xffffff);
					 } else
						 lineprof2D[k][j] = (float)ip.getInterpolatedValue(rx, ry);
					 
				 }
				 //adding scanning roi
				 if(debugroigen)
				 {
					 
					 Line lineROI = new Line(interx[k]+dx*(nOffset),intery[k]+dy*(nOffset),interx[k]+dx*(nOffset-widthline+1),intery[k]+dy*(nOffset-widthline+1));
					 lineROI.setStrokeWidth(1.);
					 SpotsPositions.add(lineROI);
				 }
			 
			 }			 
			 
			 //some debuggin
			 if(debugroigen)
			 {
				 imp.setOverlay(SpotsPositions);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 debugroigen=false;
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
						if (rgb) {
								int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(rx, ry);
								values[index] = Float.intBitsToFloat(rgbPixel&0xffffff);
						 } else
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
			 if(debugroigen)
			 {
				 SpotsPositions.add(roi);
  			     imp.setOverlay(SpotsPositions);
				 imp.updateAndRepaintWindow();
				 imp.show();
				 debugroigen=false;			 
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
				 if (rgb) {
						int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(rx, ry);
						lineprof2D[0][j] = Float.intBitsToFloat(rgbPixel&0xffffff);
				 } else
					 lineprof2D[0][j] = (float)ip.getInterpolatedValue(rx, ry);
				 				 
			 }

			 if(debugroigen)
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
					 if (rgb) {
							int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(rx, ry);
							lineprof2D[k][j] = Float.intBitsToFloat(rgbPixel&0xffffff);
					 } else
						 lineprof2D[k][j] = (float)ip.getInterpolatedValue(rx, ry);
					 
				 }
				 if(debugroigen)
				 {
					 
					 Line lineROI = new Line(interx[k]+deltax*(nOffset),intery[k]+deltay*(nOffset),interx[k]+deltax*(nOffset-widthline+1),intery[k]+deltay*(nOffset-widthline+1));
					 lineROI.setStrokeWidth(1.);
					 SpotsPositions.add(lineROI);
				 }
					 
			 }
			 
			 //some debuggin
			 if(debugroigen)
			 {
				    imp.setOverlay(SpotsPositions);
					imp.updateAndRepaintWindow();
					imp.show();
					debugroigen=false;
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
					 if (rgb) {
							int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(k, j);
							values[k]+= Float.intBitsToFloat(rgbPixel&0xffffff);
					 } else
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
				 
				 if (rgb) {
						int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(k, 0);
						currMax = Float.intBitsToFloat(rgbPixel&0xffffff);
				 } else
					 currMax = (float)ip.getInterpolatedValue(k, 0);					 
					 
				 
				 
				 for(int j=1;j<widthline;j++)
				 {
					
					 if (rgb) {
							int rgbPixel = ((ColorProcessor)ip).getInterpolatedRGBPixel(k, j);
							currVal = Float.intBitsToFloat(rgbPixel&0xffffff);
					 } else
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
		 if (rgb) {
				for (int i=0; i<length; i++)
					ip.putPixel(x++, y, Float.floatToIntBits(data[i]));
		 } else {
				for (int i=0; i<length; i++)
					ip.putPixelValue(x++, y, data[i]);
		 }
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
