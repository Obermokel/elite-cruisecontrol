package borg.ed.cruisecontrol.sysmap;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.filter.basic.GrayImageOps;
import boofcv.alg.filter.binary.BinaryImageOps;
import boofcv.alg.filter.binary.Contour;
import boofcv.alg.filter.binary.ThresholdImageOps;
import boofcv.alg.misc.ImageMiscOps;
import boofcv.core.image.ConvertImage;
import boofcv.gui.binary.VisualizeBinaryData;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.ConnectRule;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.ScreenCoord;
import borg.ed.cruisecontrol.templatematching.TemplateMatchRgb;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;
import borg.ed.cruisecontrol.templatematching.TemplateRgb;
import borg.ed.cruisecontrol.util.ImageUtil;
import borg.ed.universe.util.MiscUtil;
import georegression.struct.point.Point2D_I32;

public class SysmapScanner {

	static final Logger logger = LoggerFactory.getLogger(SysmapScanner.class);

	private Planar<GrayF32> refUcLogo = null;
	private List<TemplateRgb> refSysMapPlanets = null;

	private boolean writeDebugImageRgbOriginal = false;
	private boolean writeDebugImageRgbResult = false;
	private boolean writeDebugImageGray = false;
	private boolean writeDebugImageThreshold = false;

	public SysmapScanner() {
		this.reloadTemplates();
	}

	public boolean waitForSystemMap(Planar<GrayF32> rgb) {
		return TemplateMatcher.findBestMatchingLocation(rgb.subimage(0, 0, 280, 100), this.refUcLogo).getErrorPerPixel() <= 0.0005f;
	}

	/**
	 * Does NOT modify the rgb and hsv images!
	 */
	public SysmapScannerResult scanSystemMap(Planar<GrayF32> rgb, Planar<GrayF32> hsv, String systemName) {
		// Search for the UC logo - if found, the system map is open and can be scanned
		if (!this.waitForSystemMap(rgb)) {
			return null;
		} else {
			logger.debug("Start scanning system map");
			long scanStart = System.currentTimeMillis();
			LinkedHashMap<TemplateMatchRgb, ScreenCoord> systemMapScreenCoords = new LinkedHashMap<>();

			// Convert to gray
			GrayF32 gray = ConvertImage.average(rgb, null);

			// Overwrite left panel with black
			ImageMiscOps.fillRectangle(gray, 0f, 0, 0, 420, 1080);

			// Remove red-orange coronas
			for (int y = 0; y < rgb.height; y++) {
				for (int x = 420; x < rgb.width; x++) {
					float s = hsv.bands[1].unsafe_get(x, y);
					if (s >= 0.75f) {
						float h = hsv.bands[0].unsafe_get(x, y);
						if (h > 0.15f && h < 0.45f) {
							float v = hsv.bands[2].unsafe_get(x, y);
							if (v < 0.75f) {
								gray.unsafe_set(x, y, 0f);
							}
						}
					}
				}
			}

			// Enhance contrast
			gray = GrayImageOps.brighten(gray, -0.1f, 1.0f, null);
			gray = GrayImageOps.stretch(gray, 8.0f, 0.0f, 1.0f, null);
			logger.debug("Converted to gray and enhanced contrast");

			// Detect contours
			GrayU8 binary = new GrayU8(gray.width, gray.height);
			ThresholdImageOps.threshold(gray, binary, 0.40f, false);
			binary = BinaryImageOps.erode8(binary, 2, null);
			binary = BinaryImageOps.dilate4(binary, 2, null);
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
			logger.debug("Candidate locations found: " + rects.size());

			List<Rectangle> result = new ArrayList<>();
			for (Rectangle rect : rects) {
				if (!intersectsWithAny(rect, result)) {
					result.add(rect);
				}
			}
			logger.debug("Removed intersections, " + result.size() + " location(s) remaining");

			// Try to identify the planet classes
			for (Rectangle bl : result) {
				Planar<GrayF32> planetSubimage = rgb.subimage(bl.x, bl.y, bl.x + bl.width, bl.y + bl.height);
				TemplateMatchRgb bestMatch = TemplateMatcher.findBestMatchingTemplate(planetSubimage, this.refSysMapPlanets);
				systemMapScreenCoords.put(bestMatch, new ScreenCoord(bl.x + bl.width / 2, bl.y + bl.height / 2));
			}
			MiscUtil.sortMapByValue(systemMapScreenCoords);
			logger.debug("Guessed planet classes");

			// Finished
			logger.info(String.format(Locale.US, "System map scan took %,d ms, found %d bodies", System.currentTimeMillis() - scanStart, systemMapScreenCoords.size()));
			this.writeDebugImages(rgb, gray, binary, systemName, result);
			return new SysmapScannerResult(systemMapScreenCoords);
		}
	}

	public void reloadTemplates() {
		// REF IMAGES MUST BE 1080p!!!!
		File refDir = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref");
		try {
			this.refUcLogo = ImageUtil.normalize255(ConvertBufferedImage.convertFromMulti(ImageIO.read(new File(refDir, "uc_logo.png")), (Planar<GrayF32>) null, true, GrayF32.class));
			this.refSysMapPlanets = TemplateRgb.fromFolder(new File(refDir, "sysMapPlanets"));
		} catch (IOException e) {
			logger.error("Failed to load ref images", e);
		}
	}

	private void writeDebugImages(Planar<GrayF32> rgb, GrayF32 gray, GrayU8 binary, String systemName, List<Rectangle> result) {
		try {
			if (this.isWriteDebugImageRgbOriginal() || this.isWriteDebugImageRgbResult() || this.isWriteDebugImageGray() || this.isWriteDebugImageThreshold()) {
				final File debugFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/debug");
				final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());

				if (this.isWriteDebugImageRgbOriginal() || this.isWriteDebugImageRgbResult()) {
					BufferedImage debugImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(rgb), null, true);
					if (this.isWriteDebugImageRgbOriginal()) {
						ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_rgb_original " + systemName + ".png"));
					}
					if (this.isWriteDebugImageRgbResult()) {
						Graphics2D g = debugImage.createGraphics();
						g.setColor(Color.CYAN);
						for (Rectangle bl : result) {
							g.drawRect(bl.x, bl.y, bl.width, bl.height);
							//g.drawString(String.format(Locale.US, "%.6f", bl.getErrorPerPixel()), bl.getX(), bl.getY());
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
			}
		} catch (IOException e) {
			logger.warn("Failed to write debug image", e);
		}
	}

	private static boolean intersectsWithAny(Rectangle rect, List<Rectangle> rects) {
		for (Rectangle other : rects) {
			if (rect.intersects(other)) {
				return true;
			}
		}
		return false;
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

}
