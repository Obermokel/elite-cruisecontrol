package borg.ed.cruisecontrol.sysmap;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.ddogleg.struct.FastQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.color.ColorHsv;
import boofcv.alg.filter.basic.GrayImageOps;
import boofcv.alg.filter.binary.BinaryImageOps;
import boofcv.alg.filter.binary.Contour;
import boofcv.alg.filter.binary.ThresholdImageOps;
import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.alg.shapes.ellipse.BinaryEllipseDetector;
import boofcv.core.image.ConvertImage;
import boofcv.factory.shape.ConfigEllipseDetector;
import boofcv.factory.shape.FactoryShapeDetector;
import boofcv.gui.binary.VisualizeBinaryData;
import boofcv.gui.feature.VisualizeShapes;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.ConnectRule;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.CruiseControlApplication;
import borg.ed.cruisecontrol.ScreenConverterResult;
import borg.ed.cruisecontrol.templatematching.Template;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
import borg.ed.cruisecontrol.templatematching.TemplateMatchRgb;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;
import borg.ed.cruisecontrol.templatematching.TemplateRgb;
import borg.ed.cruisecontrol.util.ImageUtil;
import borg.ed.cruisecontrol.util.MouseUtil;
import georegression.struct.point.Point2D_I32;
import georegression.struct.shapes.EllipseRotated_F64;

public class SysmapScanner {

	static final Logger logger = LoggerFactory.getLogger(SysmapScanner.class);

	private Robot robot = null;
	private Rectangle screenRect = null;
	private ScreenConverterResult screenConverterResult = null;

	private TemplateRgb refUcLogo = null;
	private TemplateRgb refDetailsTabActive = null;
	private TemplateRgb refDetailsTabInactive = null;
	private Template refUnexplored = null;
	private Template refArrivalPoint = null;
	private Template refSolarMasses = null;
	private Template refMoonMasses = null;
	private Template refEarthMasses = null;
	private Template refRadius = null;
	private List<Template> textTemplates = null;
	private List<TemplateRgb> refSysMapBodies = null;

	private boolean writeDebugImageRgbOriginal = false;
	private boolean writeDebugImageRgbResult = false;
	private boolean writeDebugImageGray = false;
	private boolean writeDebugImageThreshold = false;
	private boolean writeDebugImageBodyRgbOriginal = false;
	private boolean writeDebugImageBodyRgbResult = false;
	private boolean writeDebugImageBodyGray = false;

	public SysmapScanner() {
		this(null, null, null);
	}

	public SysmapScanner(Robot robot, Rectangle screenRect, ScreenConverterResult screenConverterResult) {
		this.robot = robot;
		this.screenRect = screenRect;
		this.screenConverterResult = screenConverterResult;

		this.reloadTemplates();
	}

	public boolean isUniversalCartographicsLogoVisible(Planar<GrayF32> rgb) {
		return TemplateMatcher.findBestMatchingLocationInRegion(rgb, 0, 0, 280, 100, this.refUcLogo).getErrorPerPixel() <= 0.0005f;
	}

	/**
	 * Does NOT modify the rgb and hsv images!
	 * @throws InterruptedException 
	 */
	public SysmapScannerResult scanSystemMap(Planar<GrayF32> rgb, Planar<GrayF32> hsv, String systemName) throws InterruptedException {
		// Search for the UC logo - if found, the system map is open and can be scanned
		if (!this.isUniversalCartographicsLogoVisible(rgb)) {
			return null;
		} else {
			if (this.robot != null && this.screenConverterResult != null) {
				Thread.sleep(1000); // Wait for graphics to settle, especially coronas of stars
				synchronized (this.screenConverterResult) {
					this.screenConverterResult.wait();
					rgb = this.screenConverterResult.getRgb().clone();
					hsv = this.screenConverterResult.getHsv().clone();
				}
			}

			final File debugFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/debug");
			final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());

			logger.debug("Start scanning system map");
			long scanStart = System.currentTimeMillis();

			// Convert to gray
			GrayF32 gray = ConvertImage.average(rgb, null);
			try {
				BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(gray), null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 000_gray " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			GrayU8 binary = new GrayU8(gray.width, gray.height);
			ThresholdImageOps.threshold(gray, binary, 0.50f, false);
			try {
				BufferedImage debugImage = VisualizeBinaryData.renderBinary(binary, false, null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 010_binary " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			binary = BinaryImageOps.erode8(binary, 2, null);
			try {
				BufferedImage debugImage = VisualizeBinaryData.renderBinary(binary, false, null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 020_eroded " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			binary = BinaryImageOps.dilate4(binary, 2, null);
			try {
				BufferedImage debugImage = VisualizeBinaryData.renderBinary(binary, false, null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 030_dilated " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			ConfigEllipseDetector config = new ConfigEllipseDetector();
			config.maxDistanceFromEllipse = 20.0;
			BinaryEllipseDetector<GrayU8> detector = FactoryShapeDetector.ellipse(config, GrayU8.class);
			detector.process(ImageUtil.denormalize255(binary), binary);
			FastQueue<EllipseRotated_F64> ellipses = detector.getFoundEllipses();
			logger.debug("Found " + ellipses.size + " ellipse(s) in " + detector.getAllContours().size() + " contour(s)");

			// Keep only big and circle ones
			List<EllipseRotated_F64> bigCircles = new ArrayList<>();
			for (int i = 0; i < ellipses.size; i++) {
				EllipseRotated_F64 e = ellipses.get(i);
				if (e.a > 50 && e.a / e.b < 1.1) {
					bigCircles.add(new EllipseRotated_F64(e));
				}
			}
			logger.debug("Kept " + bigCircles.size() + " big circle(s) of " + ellipses.size + " total ellipse(s)");

			try {
				BufferedImage debugImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(rgb), null, true);
				Graphics2D g2 = debugImage.createGraphics();
				g2.setStroke(new BasicStroke(3));
				g2.setColor(Color.GREEN);
				for (EllipseRotated_F64 bc : bigCircles) {
					VisualizeShapes.drawEllipse(bc, g2);
					g2.drawString(String.format(Locale.US, "%.6f", bc.a / bc.b), (int) bc.center.x, (int) bc.center.y);
				}
				g2.dispose();
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 098_circles " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			// Overwrite the found ones with black
			BufferedImage tmpBI = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(rgb), null, true);
			Graphics2D tmpG = tmpBI.createGraphics();
			tmpG.setColor(Color.BLACK);
			tmpG.fillRect(0, 0, 420, 1080);
			for (EllipseRotated_F64 c : bigCircles) {
				tmpG.fillOval((int) (c.center.x - c.a) - 25, (int) (c.center.y - c.a) - 25, (int) (2 * c.a) + 50, (int) (2 * c.a) + 50);
			}
			tmpG.dispose();
			Planar<GrayF32> overwrittenRgb = ImageUtil.normalize255(ConvertBufferedImage.convertFromMulti(tmpBI, null, true, GrayF32.class));
			try {
				ImageIO.write(tmpBI, "PNG", new File(debugFolder, "DEBUG " + ts + " 099_overwrittenRgb " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			// Convert to gray
			GrayF32 overwrittenGray = ConvertImage.average(overwrittenRgb, null);
			try {
				BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(overwrittenGray), null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 100_overwrittenGray " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			// Remove cyan arcs of landable planets
			GrayF32 noArcsGray = overwrittenGray.clone();
			Planar<GrayF32> overwrittenHsv = overwrittenRgb.createSameShape();
			ColorHsv.rgbToHsv_F32(overwrittenRgb, overwrittenHsv);
			for (int y = 0; y < overwrittenHsv.height; y++) {
				for (int x = 420; x < overwrittenHsv.width; x++) {
					float h = overwrittenHsv.bands[0].unsafe_get(x, y);
					float s = overwrittenHsv.bands[1].unsafe_get(x, y);
					float v = overwrittenHsv.bands[2].unsafe_get(x, y);

					if (s >= 0.75f) {
						if (h >= Math.toRadians(190) && h <= Math.toRadians(200)) {
							noArcsGray.unsafe_set(x, y, 0);
						}
					}
				}
			}
			try {
				BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(noArcsGray), null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 110_noArcsGray " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			// Amplify
			GrayF32 amplified = GrayImageOps.brighten(noArcsGray, -0.04f, 1.0f, null);
			amplified = GrayImageOps.stretch(amplified, 10000.0f, 0.0f, 1.0f, null);
			try {
				BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(amplified), null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 120_amplified " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			// Blur
			GrayF32 blurred = GBlurImageOps.gaussian(amplified, null, -1, 13, null);
			try {
				BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(blurred), null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 130_blurred " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			// Threshold
			GrayU8 thresholded = new GrayU8(blurred.width, blurred.height);
			ThresholdImageOps.threshold(blurred, thresholded, 0.5f, false);
			try {
				BufferedImage debugImage = VisualizeBinaryData.renderBinary(thresholded, false, null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 140_thresholded " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			detector.process(ImageUtil.denormalize255(thresholded), thresholded);
			ellipses = detector.getFoundEllipses();
			logger.debug("Found " + ellipses.size + " ellipse(s) in " + detector.getAllContours().size() + " contour(s)");

			// Keep only big and circle ones
			List<EllipseRotated_F64> smallEllipses = new ArrayList<>();
			for (int i = 0; i < ellipses.size; i++) {
				EllipseRotated_F64 e = ellipses.get(i);
				if (e.a >= 5 && e.b >= 5 && e.a / e.b < 3) {
					smallEllipses.add(new EllipseRotated_F64(e));
				}
			}
			logger.debug("Kept " + smallEllipses.size() + " small ellipse(s) of " + ellipses.size + " total ellipse(s)");

			try {
				BufferedImage debugImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(rgb), null, true);
				Graphics2D g2 = debugImage.createGraphics();
				g2.setStroke(new BasicStroke(3));
				g2.setColor(Color.GREEN);
				for (EllipseRotated_F64 bc : bigCircles) {
					VisualizeShapes.drawEllipse(bc, g2);
					g2.drawString(String.format(Locale.US, "%.1f / %.1f = %.6f", bc.a, bc.b, bc.a / bc.b), (int) bc.center.x, (int) bc.center.y);
				}
				for (EllipseRotated_F64 se : smallEllipses) {
					VisualizeShapes.drawEllipse(se, g2);
					g2.drawString(String.format(Locale.US, "%.1f", se.a / se.b), (int) se.center.x, (int) se.center.y);
				}
				g2.dispose();
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " 198_bodies " + systemName + ".png"));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			// Detect contours
			List<Contour> contours = BinaryImageOps.contour(binary, ConnectRule.EIGHT, null);
			logger.debug("Contours found: " + contours.size());

			// Keep only those of planet or star size, remove noise
			List<Rectangle> rects = new ArrayList<>();
			for (Contour c : contours) {
				int xMin = Integer.MAX_VALUE;
				int xMax = Integer.MIN_VALUE;
				int yMin = Integer.MAX_VALUE;
				int yMax = Integer.MIN_VALUE;
				for (Point2D_I32 p : c.external) {
					xMin = Math.min(xMin, p.x);
					xMax = Math.max(xMax, p.x);
					yMin = Math.min(yMin, p.y);
					yMax = Math.max(yMax, p.y);
				}
				int width = xMax - xMin;
				int height = yMax - yMin;
				if (width >= 15 && width <= 1000 && height >= 15 && height <= 1000) {
					rects.add(new Rectangle(xMin, yMin, width, height));
				}
			}
			logger.debug("Contours of reasonable size: " + rects.size());

			List<SysmapBody> bodies = new ArrayList<>();
			for (Rectangle rect : rects) {
				SysmapBody b = new SysmapBody(rect);
				if (!SysmapBody.intersectsWithAny(b, bodies)) {
					bodies.add(b);
				}
			}
			logger.debug("Non-intersecting contours: " + bodies.size());

			// If we have control over the computer move the mouse over the found locations to get distance information
			if (!bodies.isEmpty() && this.robot != null && this.screenRect != null && this.screenConverterResult != null) {
				try {
					this.hoverOverBodiesAndExtractData(bodies);
				} catch (Exception e) {
					logger.error("Failed to extract body information", e);
				}
			}

			// Try to identify the body types
			ListIterator<SysmapBody> it = bodies.listIterator();
			while (it.hasNext()) {
				SysmapBody b = it.next();
				logger.debug("Guessing body type of " + b);
				Planar<GrayF32> bodyImage = rgb.subimage(b.areaInImage.x, b.areaInImage.y, b.areaInImage.x + b.areaInImage.width, b.areaInImage.y + b.areaInImage.height);
				TemplateMatchRgb bestMatch = TemplateMatcher.findBestMatchingTemplate(bodyImage, this.refSysMapBodies);
				b.bestBodyMatch = bestMatch;
			}

			// Finished
			logger.info(String.format(Locale.US, "System map scan took %,d ms, found %d bodies", System.currentTimeMillis() - scanStart, bodies.size()));
			this.writeDebugImages(rgb, gray, binary, systemName, bodies);
			return new SysmapScannerResult(bodies);
		}
	}

	private void hoverOverBodiesAndExtractData(List<SysmapBody> bodies) throws InterruptedException {
		MouseUtil mouseUtil = new MouseUtil(this.screenRect.width, this.screenRect.height, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);
		Random random = new Random();

		//		SysmapBody leftmostBodyOnScreen = bodies.stream().sorted((b1, b2) -> new Integer(b1.areaInImage.x).compareTo(new Integer(b2.areaInImage.x))).findFirst().orElse(null);
		//		leftmostBodyOnScreen.centerOnScreen = mouseUtil.imageToScreen(
		//				new Point(leftmostBodyOnScreen.areaInImage.x + leftmostBodyOnScreen.areaInImage.width / 2, leftmostBodyOnScreen.areaInImage.y + leftmostBodyOnScreen.areaInImage.height / 2));
		//		SysmapBody rightmostBodyOnScreen = bodies.stream().sorted((b1, b2) -> -1 * new Integer(b1.areaInImage.x).compareTo(new Integer(b2.areaInImage.x))).findFirst().orElse(null);
		//		rightmostBodyOnScreen.centerOnScreen = mouseUtil.imageToScreen(new Point(rightmostBodyOnScreen.areaInImage.x + rightmostBodyOnScreen.areaInImage.width / 2,
		//				rightmostBodyOnScreen.areaInImage.y + rightmostBodyOnScreen.areaInImage.height / 2));
		for (SysmapBody b : bodies) {
			b.centerOnScreen = mouseUtil.imageToScreen(new Point(b.areaInImage.x + b.areaInImage.width / 2, b.areaInImage.y + b.areaInImage.height / 2));
		}
		final Point p0 = new Point(0, 0);
		Collections.sort(bodies, new Comparator<SysmapBody>() {
			@Override
			public int compare(SysmapBody b1, SysmapBody b2) {
				return new Double(b1.centerOnScreen.distance(p0)).compareTo(new Double(b2.centerOnScreen.distance(p0)));
			}
		});
		SysmapBody leftmostBodyOnScreen = bodies.get(0);
		SysmapBody rightmostBodyOnScreen = bodies.get(bodies.size() - 1);

		// Ensure details are visible
		boolean detailsVisible = false;
		while (!detailsVisible) {
			synchronized (this.screenConverterResult) {
				this.screenConverterResult.wait();
				detailsVisible = TemplateMatcher.findBestMatchingLocationInRegion(this.screenConverterResult.getRgb(), 140, 120, 60, 55, this.refDetailsTabActive).getErrorPerPixel() <= 0.1f;
				if (!detailsVisible) {
					logger.debug("Details tab not visible");
					TemplateMatchRgb m = TemplateMatcher.findBestMatchingLocationInRegion(this.screenConverterResult.getRgb(), 140, 120, 60, 55, this.refDetailsTabInactive);
					Point p = mouseUtil.imageToScreen(new Point(m.getX() + m.getWidth() / 2, m.getY() + m.getHeight() / 2));
					this.robot.mouseMove(p.x, p.y);
					Thread.sleep(200);
					this.robot.mousePress(InputEvent.getMaskForButton(1));
					Thread.sleep(50);
					this.robot.mouseRelease(InputEvent.getMaskForButton(1));
					logger.debug("Clicked on details tab");
				}
			}
		}

		SysmapBody lastBody = null;
		for (SysmapBody b : bodies) {
			logger.debug("Extracting data for body" + bodies.indexOf(b));

			this.robot.mouseMove((rightmostBodyOnScreen.centerOnScreen.x - 5) + random.nextInt(10), (rightmostBodyOnScreen.centerOnScreen.y - 5) + random.nextInt(10));
			Thread.sleep(500);
			this.robot.mouseMove((leftmostBodyOnScreen.centerOnScreen.x - 5) + random.nextInt(10), (leftmostBodyOnScreen.centerOnScreen.y - 5) + random.nextInt(10));
			Thread.sleep(500);
			this.robot.mouseMove((b.centerOnScreen.x - 5) + random.nextInt(10), (b.centerOnScreen.y - 5) + random.nextInt(10));
			Thread.sleep(500);

			final long start = System.currentTimeMillis();

			while ((System.currentTimeMillis() - start) < 2500L) {
				Planar<GrayF32> rgb = null;
				Planar<GrayF32> hsv = null;
				synchronized (this.screenConverterResult) {
					this.screenConverterResult.wait();
					rgb = this.screenConverterResult.getRgb().clone();
					hsv = this.screenConverterResult.getHsv().clone();
				}
				if (this.extractBodyData(rgb, hsv, b)) {
					break;
				}
			}

			if (lastBody != null && !b.hasDifferentData(lastBody)) {
				logger.warn(b + " has same data as " + lastBody);
				b.clearData();
			}

			lastBody = b;
		}
	}

	public boolean extractBodyData(Planar<GrayF32> rgb, Planar<GrayF32> hsv, SysmapBody b) {
		b.rgbDebugImage = rgb.clone();
		b.grayDebugImage = ConvertImage.average(rgb, null);
		b.grayDebugImage = GBlurImageOps.gaussian(b.grayDebugImage, null, -1, 3, null);
		for (int y = 0; y < hsv.height; y++) {
			for (int x = 0; x < hsv.width; x++) {
				float v = hsv.bands[2].unsafe_get(x, y);
				float s = hsv.bands[1].unsafe_get(x, y);
				if (v < 0.45f || s > 0.2f) {
					b.grayDebugImage.unsafe_set(x, y, 0f);
				}
			}
		}

		TemplateMatch mUnexplored = TemplateMatcher.findBestMatchingLocationInRegion(b.grayDebugImage, 420, 0, 1920 - 420, 1080, refUnexplored);
		logger.debug("Best UNEXPLORED match = " + mUnexplored.getErrorPerPixel());
		if (mUnexplored.getErrorPerPixel() > 0.05f) {
			return false;
		} else {
			b.unexplored = true;

			TemplateMatch mArrivalPoint = TemplateMatcher.findBestMatchingLocationInRegion(b.grayDebugImage, mUnexplored.getX() - 20, mUnexplored.getY() + 20, 170, 55, refArrivalPoint);
			logger.debug("Best ARRIVAL POINT match = " + mArrivalPoint.getErrorPerPixel());
			TemplateMatch mSolarMasses = TemplateMatcher.findBestMatchingLocationInRegion(b.grayDebugImage, 0, 180, 210, 400, refSolarMasses);
			logger.debug("Best SOLAR MASSES match = " + mSolarMasses.getErrorPerPixel());
			TemplateMatch mMoonMasses = TemplateMatcher.findBestMatchingLocationInRegion(b.grayDebugImage, 0, 180, 210, 400, refMoonMasses);
			logger.debug("Best MOON MASSES match = " + mMoonMasses.getErrorPerPixel());
			TemplateMatch mEarthMasses = TemplateMatcher.findBestMatchingLocationInRegion(b.grayDebugImage, 0, 180, 210, 400, refEarthMasses);
			logger.debug("Best EARTH MASSES match = " + mEarthMasses.getErrorPerPixel());

			if (mArrivalPoint.getErrorPerPixel() <= 0.05f) {
				int apX0 = Math.min(b.grayDebugImage.width - 1, mArrivalPoint.getX() + mArrivalPoint.getWidth());
				int apY0 = Math.max(0, mArrivalPoint.getY() - 5);
				int apX1 = Math.min(b.grayDebugImage.width - 1, apX0 + 225);
				int apY1 = Math.min(b.grayDebugImage.height - 1, apY0 + 30);
				String arrivalPointText = this.scanText(b.grayDebugImage.subimage(apX0, apY0, apX1, apY1), textTemplates);
				logger.debug("...arrivalPointText='" + arrivalPointText + "'");
				Pattern p = Pattern.compile(".*?(\\d+.*\\d+.*LS).*?");
				Matcher m = p.matcher(arrivalPointText);
				if (m.matches()) {
					StringBuilder sb = new StringBuilder(m.group(1).replaceAll("\\D", ""));
					sb.insert(sb.length() - 2, ".");
					try {
						b.distanceLs = new BigDecimal(sb.toString());
					} catch (NumberFormatException e) {
						logger.error("Failed to parse '" + sb + "' (derived from the original '" + m.group(1) + "') to a BigDecimal");
					}
				}
			}

			if (mSolarMasses.getErrorPerPixel() <= 0.05f && mSolarMasses.getErrorPerPixel() < mMoonMasses.getErrorPerPixel()
					&& mSolarMasses.getErrorPerPixel() < mEarthMasses.getErrorPerPixel()) {
				int emX0 = Math.min(b.grayDebugImage.width - 1, mSolarMasses.getX() + mSolarMasses.getWidth());
				int emY0 = Math.max(0, mSolarMasses.getY() - 5);
				int emX1 = Math.min(b.grayDebugImage.width - 1, emX0 + 250);
				int emY1 = Math.min(b.grayDebugImage.height - 1, emY0 + 30);
				String solarMassesText = this.scanText(b.grayDebugImage.subimage(emX0, emY0, emX1, emY1), textTemplates);
				logger.debug("...solarMassesText='" + solarMassesText + "'");
				Pattern p = Pattern.compile(".*?(\\d+.*\\d+).*?");
				Matcher m = p.matcher(solarMassesText);
				if (m.matches()) {
					StringBuilder sb = new StringBuilder(m.group(1).replaceAll("\\D", ""));
					sb.insert(sb.length() - 4, ".");
					try {
						b.solarMasses = new BigDecimal(sb.toString());
					} catch (NumberFormatException e) {
						logger.error("Failed to parse '" + sb + "' (derived from the original '" + m.group(1) + "') to a BigDecimal");
					}
				}
			}

			if (mMoonMasses.getErrorPerPixel() <= 0.05f && mMoonMasses.getErrorPerPixel() < mSolarMasses.getErrorPerPixel()
					&& mMoonMasses.getErrorPerPixel() < mEarthMasses.getErrorPerPixel()) {
				int emX0 = Math.min(b.grayDebugImage.width - 1, mMoonMasses.getX() + mMoonMasses.getWidth());
				int emY0 = Math.max(0, mMoonMasses.getY() - 5);
				int emX1 = Math.min(b.grayDebugImage.width - 1, emX0 + 250);
				int emY1 = Math.min(b.grayDebugImage.height - 1, emY0 + 30);
				String moonMassesText = this.scanText(b.grayDebugImage.subimage(emX0, emY0, emX1, emY1), textTemplates);
				logger.debug("...moonMassesText='" + moonMassesText + "'");
				Pattern p = Pattern.compile(".*?(\\d+.*\\d+).*?");
				Matcher m = p.matcher(moonMassesText);
				if (m.matches()) {
					StringBuilder sb = new StringBuilder(m.group(1).replaceAll("\\D", ""));
					sb.insert(sb.length() - 4, ".");
					try {
						b.moonMasses = new BigDecimal(sb.toString());
					} catch (NumberFormatException e) {
						logger.error("Failed to parse '" + sb + "' (derived from the original '" + m.group(1) + "') to a BigDecimal");
					}
				}
			}

			if (mEarthMasses.getErrorPerPixel() <= 0.05f && mEarthMasses.getErrorPerPixel() < mSolarMasses.getErrorPerPixel()
					&& mEarthMasses.getErrorPerPixel() < mMoonMasses.getErrorPerPixel()) {
				int emX0 = Math.min(b.grayDebugImage.width - 1, mEarthMasses.getX() + mEarthMasses.getWidth());
				int emY0 = Math.max(0, mEarthMasses.getY() - 5);
				int emX1 = Math.min(b.grayDebugImage.width - 1, emX0 + 250);
				int emY1 = Math.min(b.grayDebugImage.height - 1, emY0 + 30);
				String earthMassesText = this.scanText(b.grayDebugImage.subimage(emX0, emY0, emX1, emY1), textTemplates);
				logger.debug("...earthMassesText='" + earthMassesText + "'");
				Pattern p = Pattern.compile(".*?(\\d+.*\\d+).*?");
				Matcher m = p.matcher(earthMassesText);
				if (m.matches()) {
					StringBuilder sb = new StringBuilder(m.group(1).replaceAll("\\D", ""));
					sb.insert(sb.length() - 4, ".");
					try {
						b.earthMasses = new BigDecimal(sb.toString());
					} catch (NumberFormatException e) {
						logger.error("Failed to parse '" + sb + "' (derived from the original '" + m.group(1) + "') to a BigDecimal");
					}
				}

				TemplateMatch mRadius = TemplateMatcher.findBestMatchingLocationInRegion(b.grayDebugImage, mEarthMasses.getX() - 2, mEarthMasses.getY() + 20, 170, 40, refRadius);
				logger.debug("Best RADIUS match = " + mRadius.getErrorPerPixel());
				if (mRadius.getErrorPerPixel() <= 0.05f) {
					int radX0 = Math.min(b.grayDebugImage.width - 1, mRadius.getX() + mRadius.getWidth());
					int radY0 = Math.max(0, mRadius.getY() - 5);
					int radX1 = Math.min(b.grayDebugImage.width - 1, radX0 + 300);
					int radY1 = Math.min(b.grayDebugImage.height - 1, radY0 + 30);
					String radiusText = this.scanText(b.grayDebugImage.subimage(radX0, radY0, radX1, radY1), textTemplates);
					logger.debug("...radiusText='" + radiusText + "'");
					p = Pattern.compile(".*?(\\d+.*\\d+.*KM).*?");
					m = p.matcher(radiusText);
					if (m.matches()) {
						StringBuilder sb = new StringBuilder(m.group(1).replaceAll("\\D", ""));
						try {
							b.radiusKm = new BigDecimal(sb.toString());
						} catch (NumberFormatException e) {
							logger.error("Failed to parse '" + sb + "' (derived from the original '" + m.group(1) + "') to a BigDecimal");
						}
					}
				}
			}

			return true;
		}
	}

	private String scanText(GrayF32 image, List<Template> textTemplates) {
		List<TemplateMatch> allMatches = new ArrayList<>();
		for (Template template : textTemplates) {
			allMatches.addAll(TemplateMatcher.findAllMatchingLocations(image, template, 0.05f));
		}
		allMatches = allMatches.stream().sorted((m1, m2) -> new Float(m1.getErrorPerPixel()).compareTo(new Float(m2.getErrorPerPixel()))).collect(Collectors.toList());

		List<TemplateMatch> remainingMatches = new ArrayList<>();
		List<Rectangle> rects = new ArrayList<>();
		for (TemplateMatch m : allMatches.stream().filter(m -> m.getTemplate().getName().matches("\\w+")).collect(Collectors.toList())) {
			Rectangle r = new Rectangle(m.getX(), m.getY(), m.getWidth(), m.getHeight());
			if (!intersectsWithAny(r, rects)) {
				rects.add(r);
				remainingMatches.add(m);
			}
		}
		for (TemplateMatch m : allMatches.stream().filter(m -> m.getTemplate().getName().matches("\\W+")).collect(Collectors.toList())) {
			Rectangle r = new Rectangle(m.getX(), m.getY(), m.getWidth(), m.getHeight());
			if (!intersectsWithAny(r, rects)) {
				rects.add(r);
				remainingMatches.add(m);
			}
		}
		remainingMatches = remainingMatches.stream().sorted((m1, m2) -> new Integer(m1.getX()).compareTo(new Integer(m2.getX()))).collect(Collectors.toList());

		StringBuilder sbText = new StringBuilder();
		for (TemplateMatch m : remainingMatches) {
			sbText.append(m.getTemplate().getName());
		}
		return sbText.toString();
	}

	private static boolean intersectsWithAny(Rectangle rect, List<Rectangle> rects) {
		for (Rectangle other : rects) {
			if (rect.intersects(other)) {
				return true;
			}
		}
		return false;
	}

	public void reloadTemplates() {
		// REF IMAGES MUST BE 1080p!!!!
		File refDir = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref");
		try {
			this.refUcLogo = TemplateRgb.fromFile(new File(refDir, "uc_logo.png"));
			this.refDetailsTabActive = TemplateRgb.fromFile(new File(refDir, "sysmap_details_tab_active.png"));
			this.refDetailsTabInactive = TemplateRgb.fromFile(new File(refDir, "sysmap_details_tab_inactive.png"));
			this.refUnexplored = Template.fromFile(new File(refDir, "unexplored2.png"));
			this.refArrivalPoint = Template.fromFile(new File(refDir, "arrival_point.png"));
			this.refSolarMasses = Template.fromFile(new File(refDir, "solar_masses.png"));
			this.refMoonMasses = Template.fromFile(new File(refDir, "moon_masses.png"));
			this.refEarthMasses = Template.fromFile(new File(refDir, "earth_masses.png"));
			this.refRadius = Template.fromFile(new File(refDir, "radius.png"));
			this.textTemplates = Template.fromFolder(new File(refDir, "sysmapText"));
			this.refSysMapBodies = TemplateRgb.fromFolder(new File(refDir, "sysmapBodies"));
		} catch (IOException e) {
			logger.error("Failed to load ref images", e);
		}
	}

	private void writeDebugImages(Planar<GrayF32> rgb, GrayF32 gray, GrayU8 binary, String systemName, List<SysmapBody> bodies) {
		try {
			final File debugFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/debug");
			final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());

			if (this.isWriteDebugImageRgbOriginal() || this.isWriteDebugImageRgbResult()) {
				BufferedImage debugImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(rgb), null, true);
				if (this.isWriteDebugImageRgbOriginal()) {
					ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_rgb_original " + systemName + ".png"));
				}
				if (this.isWriteDebugImageRgbResult()) {
					Graphics2D g = debugImage.createGraphics();
					g.setColor(Color.GREEN);
					for (SysmapBody b : bodies) {
						g.drawRect(b.areaInImage.x, b.areaInImage.y, b.areaInImage.width, b.areaInImage.height);
						if (b.distanceLs == null) {
							g.drawString("?.?? Ls", b.areaInImage.x + b.areaInImage.width + 2, b.areaInImage.y + 15);
						} else {
							g.drawString(String.format(Locale.US, "%.2f Ls", b.distanceLs), b.areaInImage.x + b.areaInImage.width + 2, b.areaInImage.y + 15);
						}
						if (b.bestBodyMatch == null) {
							g.drawString("???", b.areaInImage.x + b.areaInImage.width + 2, b.areaInImage.y + 30);
						} else {
							g.drawString(SysmapBody.getAbbreviatedType(b), b.areaInImage.x + b.areaInImage.width + 2, b.areaInImage.y + 30);
						}
					}
					g.dispose();
					ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_rgb_result " + systemName + ".png"));
				}
			}

			if (this.isWriteDebugImageGray()) {
				BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(gray), null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_gray " + systemName + ".png"));
			}

			if (this.isWriteDebugImageThreshold()) {
				BufferedImage debugImage = VisualizeBinaryData.renderBinary(binary, false, null);
				ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_threshold " + systemName + ".png"));
			}

			for (SysmapBody b : bodies) {
				if (this.isWriteDebugImageBodyRgbOriginal() || this.isWriteDebugImageBodyRgbResult()) {
					BufferedImage debugImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(b.rgbDebugImage), null, true);
					if (this.isWriteDebugImageBodyRgbOriginal()) {
						ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_body" + bodies.indexOf(b) + "_rgb_original " + systemName + ".png"));
					}
					if (this.isWriteDebugImageBodyRgbResult()) {
						Graphics2D g = debugImage.createGraphics();
						g.setColor(Color.GREEN);
						g.drawRect(b.areaInImage.x, b.areaInImage.y, b.areaInImage.width, b.areaInImage.height);
						if (b.bestBodyMatch != null) {
							g.drawString(String.format(Locale.US, "%.6f (%s)", b.bestBodyMatch.getErrorPerPixel(), b.bestBodyMatch.getTemplate().getName()), b.areaInImage.x, b.areaInImage.y);
						}
						if (b.distanceLs != null) {
							g.drawString(String.format(Locale.US, "distanceLs=%,.2f", b.distanceLs), b.areaInImage.x, b.areaInImage.y + 15);
						}
						if (b.solarMasses != null) {
							g.drawString(String.format(Locale.US, "solarMasses=%,.4f", b.solarMasses), b.areaInImage.x, b.areaInImage.y + 30);
						} else if (b.moonMasses != null) {
							g.drawString(String.format(Locale.US, "moonMasses=%,.4f", b.moonMasses), b.areaInImage.x, b.areaInImage.y + 30);
						} else if (b.earthMasses != null) {
							g.drawString(String.format(Locale.US, "earthMasses=%,.4f", b.earthMasses), b.areaInImage.x, b.areaInImage.y + 30);
						}
						if (b.radiusKm != null) {
							g.drawString(String.format(Locale.US, "radiusKm=%,.0f", b.radiusKm), b.areaInImage.x, b.areaInImage.y + 45);
						}
						g.dispose();
						ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_body" + bodies.indexOf(b) + "_rgb_result " + systemName + ".png"));
					}
				}

				if (this.isWriteDebugImageBodyGray()) {
					BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(b.grayDebugImage), null);
					ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_body" + bodies.indexOf(b) + "_gray " + systemName + ".png"));
				}
			}
		} catch (IOException e) {
			logger.warn("Failed to write debug image", e);
		}
	}

	public boolean isWriteDebugImageRgbOriginal() {
		return writeDebugImageRgbOriginal;
	}

	public void setWriteDebugImageRgbOriginal(boolean writeDebugImageRgbOriginal) {
		this.writeDebugImageRgbOriginal = writeDebugImageRgbOriginal;
	}

	public boolean isWriteDebugImageRgbResult() {
		return writeDebugImageRgbResult;
	}

	public void setWriteDebugImageRgbResult(boolean writeDebugImageRgbResult) {
		this.writeDebugImageRgbResult = writeDebugImageRgbResult;
	}

	public boolean isWriteDebugImageGray() {
		return writeDebugImageGray;
	}

	public void setWriteDebugImageGray(boolean writeDebugImageGray) {
		this.writeDebugImageGray = writeDebugImageGray;
	}

	public boolean isWriteDebugImageThreshold() {
		return writeDebugImageThreshold;
	}

	public void setWriteDebugImageThreshold(boolean writeDebugImageThreshold) {
		this.writeDebugImageThreshold = writeDebugImageThreshold;
	}

	public boolean isWriteDebugImageBodyRgbOriginal() {
		return writeDebugImageBodyRgbOriginal;
	}

	public void setWriteDebugImageBodyRgbOriginal(boolean writeDebugImageBodyRgbOriginal) {
		this.writeDebugImageBodyRgbOriginal = writeDebugImageBodyRgbOriginal;
	}

	public boolean isWriteDebugImageBodyRgbResult() {
		return writeDebugImageBodyRgbResult;
	}

	public void setWriteDebugImageBodyRgbResult(boolean writeDebugImageBodyRgbResult) {
		this.writeDebugImageBodyRgbResult = writeDebugImageBodyRgbResult;
	}

	public boolean isWriteDebugImageBodyGray() {
		return writeDebugImageBodyGray;
	}

	public void setWriteDebugImageBodyGray(boolean writeDebugImageBodyGray) {
		this.writeDebugImageBodyGray = writeDebugImageBodyGray;
	}

}
