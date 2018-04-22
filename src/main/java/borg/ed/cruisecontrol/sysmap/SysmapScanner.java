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
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.filter.basic.GrayImageOps;
import boofcv.alg.misc.ImageMiscOps;
import boofcv.core.image.ConvertImage;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.ScreenCoord;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
import borg.ed.cruisecontrol.templatematching.TemplateMatchRgb;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;
import borg.ed.cruisecontrol.templatematching.TemplateRgb;
import borg.ed.cruisecontrol.util.ImageUtil;
import borg.ed.universe.util.MiscUtil;

public class SysmapScanner {

	static final Logger logger = LoggerFactory.getLogger(SysmapScanner.class);

	private Planar<GrayF32> refUniversalCartographics = null;
	private GrayF32 refGenericBody = null;
	private GrayF32 refGenericBodyMask = null;
	private GrayF32 refGenericBody50 = null;
	private GrayF32 refGenericBody50Mask = null;
	private GrayF32 refGenericBody60 = null;
	private GrayF32 refGenericBody60Mask = null;
	private List<TemplateRgb> refSysMapPlanets = null;

	private boolean writeDebugImageRgbOriginal = false;
	private boolean writeDebugImageRgbResult = false;
	private boolean writeDebugImageGray = false;

	public SysmapScanner() {
		this.reloadTemplates();
	}

	public boolean waitForSystemMap(Planar<GrayF32> rgb) {
		return TemplateMatcher.findBestMatchingLocation(rgb.subimage(0, 0, 280, 100), this.refUniversalCartographics).getErrorPerPixel() <= 0.0005f;
	}

	public SysmapScannerResult scanSystemMap(Planar<GrayF32> rgb, String systemName) {
		// Search for the UC logo - if found, the system map is open and can be scanned
		if (!this.waitForSystemMap(rgb)) {
			return null;
		} else {
			logger.debug("Start scanning system map");
			long scanStart = System.currentTimeMillis();
			LinkedHashMap<TemplateMatchRgb, ScreenCoord> systemMapScreenCoords = new LinkedHashMap<>();

			// First, search for locations of planets
			GrayF32 gray = ConvertImage.average(rgb, null);
			ImageMiscOps.fillRectangle(gray, 0f, 0, 0, 420, 1080); // Overwrite left panel with black
			gray = GrayImageOps.brighten(gray, -0.1f, 1.0f, null);
			gray = GrayImageOps.stretch(gray, 3.0f, 0.0f, 1.0f, null);
			logger.debug("Converted to gray and enhanced contrast");

			List<TemplateMatch> bodyLocations = TemplateMatcher.findAllMatchingLocations(gray, this.refGenericBody, this.refGenericBodyMask, 0.1f);
			bodyLocations.addAll(TemplateMatcher.findAllMatchingLocations(gray, this.refGenericBody50, this.refGenericBody50Mask, 0.1f));
			bodyLocations.addAll(TemplateMatcher.findAllMatchingLocations(gray, this.refGenericBody60, this.refGenericBody60Mask, 0.1f));
			bodyLocations = bodyLocations.stream().sorted((m1, m2) -> new Float(m1.getErrorPerPixel()).compareTo(new Float(m2.getErrorPerPixel()))).collect(Collectors.toList());
			logger.debug("Candidate locations found: " + bodyLocations.size());

			List<Rectangle> rects = new ArrayList<>(bodyLocations.size());
			List<TemplateMatch> tmp = new ArrayList<>();
			for (TemplateMatch bl : bodyLocations) {
				Rectangle rect = new Rectangle(bl.getX(), bl.getY(), bl.getWidth(), bl.getHeight());
				if (!intersectsWithAny(rect, rects)) {
					tmp.add(bl);
					rects.add(rect);
				}
			}
			bodyLocations = tmp;
			logger.debug("Removed intersections, " + bodyLocations.size() + " location(s) remaining");

			// Try to identify the planet classes
			for (TemplateMatch bl : bodyLocations) {
				Planar<GrayF32> planetSubimage = rgb.subimage(bl.getX(), bl.getY(), bl.getX() + bl.getWidth(), bl.getY() + bl.getHeight());
				TemplateMatchRgb bestMatch = TemplateMatcher.findBestMatchingTemplate(planetSubimage, this.refSysMapPlanets);
				systemMapScreenCoords.put(bestMatch, new ScreenCoord(bl.getX() + bl.getWidth() / 2, bl.getY() + bl.getHeight() / 2));
			}
			MiscUtil.sortMapByValue(systemMapScreenCoords);
			logger.debug("Guessed planet classes");

			// Finished
			logger.info(String.format(Locale.US, "System map scan took %,d ms, found %d bodies", System.currentTimeMillis() - scanStart, systemMapScreenCoords.size()));
			this.writeDebugImages(rgb, gray, systemName, bodyLocations);
			return new SysmapScannerResult(systemMapScreenCoords);
		}
	}

	public void reloadTemplates() {
		// REF IMAGES MUST BE 1080p!!!!
		File refDir = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref");
		try {
			this.refUniversalCartographics = ImageUtil
					.normalize255(ConvertBufferedImage.convertFromMulti(ImageIO.read(new File(refDir, "universal_cartographics.png")), (Planar<GrayF32>) null, true, GrayF32.class));
			this.refGenericBody = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body.png")), (GrayF32) null));
			this.refGenericBodyMask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_mask.png")), (GrayF32) null));
			this.refGenericBody50 = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_50.png")), (GrayF32) null));
			this.refGenericBody50Mask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_50_mask.png")), (GrayF32) null));
			this.refGenericBody60 = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_60.png")), (GrayF32) null));
			this.refGenericBody60Mask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_60_mask.png")), (GrayF32) null));
			this.refSysMapPlanets = TemplateRgb.fromFolder(new File(refDir, "sysMapPlanets"));
		} catch (IOException e) {
			logger.error("Failed to load ref images", e);
		}
	}

	private void writeDebugImages(Planar<GrayF32> rgb, GrayF32 gray, String systemName, List<TemplateMatch> bodyLocations) {
		try {
			if (this.isWriteDebugImageRgbOriginal() || this.isWriteDebugImageRgbResult() || this.isWriteDebugImageGray()) {
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
						for (TemplateMatch bl : bodyLocations) {
							g.drawRect(bl.getX(), bl.getY(), bl.getWidth(), bl.getHeight());
							g.drawString(String.format(Locale.US, "%.6f", bl.getErrorPerPixel()), bl.getX(), bl.getY());
						}
						g.dispose();
						ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_rgb_result " + systemName + ".png"));
					}
				}

				if (this.isWriteDebugImageGray()) {
					BufferedImage debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(gray), null);
					ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " system_map_gray " + systemName + ".png"));
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

}
