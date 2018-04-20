package borg.ed.cruisecontrol;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import boofcv.alg.filter.basic.GrayImageOps;
import boofcv.core.image.ConvertImage;
import boofcv.gui.image.VisualizeImageData;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;
import borg.ed.cruisecontrol.templatematching.TemplateRgb;
import borg.ed.cruisecontrol.util.ImageUtil;

public class SysMapTest {

	public static void main(String[] args) throws Exception {
		File baseDir = new File(System.getProperty("user.home"), "Google Drive\\Elite Dangerous\\CruiseControl");
		File refDir = new File(baseDir, "ref");
		File debugDir = new File(baseDir, "debug");
		Planar<GrayF32> refUniversalCartographics = ImageUtil
				.normalize255(ConvertBufferedImage.convertFromMulti(ImageIO.read(new File(refDir, "universal_cartographics.png")), (Planar<GrayF32>) null, true, GrayF32.class));
		GrayF32 refGenericBody = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body.png")), (GrayF32) null));
		GrayF32 refGenericBodyMask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_mask.png")), (GrayF32) null));
		GrayF32 refGenericBody50 = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_50.png")), (GrayF32) null));
		GrayF32 refGenericBody50Mask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_50_mask.png")), (GrayF32) null));
		GrayF32 refGenericBody60 = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_60.png")), (GrayF32) null));
		GrayF32 refGenericBody60Mask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "generic_body_60_mask.png")), (GrayF32) null));
		List<TemplateRgb> refSysMapPlanets = TemplateRgb.fromFolder(new File(refDir, "sysMapPlanets"));

		File[] testFiles = debugDir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.getName().endsWith(".png") && !file.getName().endsWith("_debug.png");
			}
		});

		for (File testFile : testFiles) {
			BufferedImage bi = ImageIO.read(testFile);
			Planar<GrayF32> image = ImageUtil.normalize255(ConvertBufferedImage.convertFromMulti(bi, (Planar<GrayF32>) null, true, GrayF32.class));
			TemplateMatch ucMatch = TemplateMatcher.findBestMatchingLocation(image.subimage(0, 0, 280, 100), refUniversalCartographics);
			if (ucMatch.getErrorPerPixel() < 0.0005f) {
				GrayF32 gray = ConvertImage.average(image, null);
				gray = GrayImageOps.brighten(gray, -0.1f, 1.0f, null);
				gray = GrayImageOps.stretch(gray, 3.0f, 0.0f, 1.0f, null);
				//				for (int y = 0; y < gray.height; y++) {
				//					for (int x = 0; x < gray.width; x++) {
				//						//gray.unsafe_set(x, y, Math.max(0f, gray.unsafe_get(x, y) - 0.5f));
				//						if (gray.unsafe_get(x, y) < 0) {
				//							System.out.println(gray.unsafe_get(x, y));
				//						} else if (gray.unsafe_get(x, y) > 1) {
				//							System.out.println(gray.unsafe_get(x, y));
				//						}
				//					}
				//				}

				//GrayU8 threshold = GThresholdImageOps.localSquare(gray, null, 15, 0.7f, false, null, null);
				//GrayU8 threshold = GThresholdImageOps.localSauvola(gray, null, 15, 0.3f, false);
				//GrayU8 threshold = GThresholdImageOps.threshold(gray, null, GThresholdImageOps.computeEntropy(gray, 0, 1), false);
				//BufferedImage bin = VisualizeBinaryData.renderBinary(threshold, false, null);
				BufferedImage bin = VisualizeImageData.grayMagnitude(gray, null, 1);
				ImageIO.write(bin, "PNG", new File(debugDir, testFile.getName().replace(".png", "_bin.png")));
				BufferedImage debugUC = ImageIO.read(testFile);
				Graphics2D g = debugUC.createGraphics();
				g.setColor(Color.CYAN);
				g.drawRect(ucMatch.getX(), ucMatch.getY(), ucMatch.getWidth(), ucMatch.getHeight());
				g.drawString(String.format(Locale.US, "%.6f", ucMatch.getErrorPerPixel()), ucMatch.getX(), ucMatch.getY() + 20);
				g.dispose();
				ImageIO.write(debugUC, "PNG", new File(debugDir, testFile.getName().replace(".png", "_universal_cartographics_debug.png")));

				List<TemplateMatch> bodyLocations = TemplateMatcher.findAllMatchingLocations(gray, refGenericBody, refGenericBodyMask, 0.1f);
				bodyLocations.addAll(TemplateMatcher.findAllMatchingLocations(gray, refGenericBody50, refGenericBody50Mask, 0.1f));
				bodyLocations.addAll(TemplateMatcher.findAllMatchingLocations(gray, refGenericBody60, refGenericBody60Mask, 0.1f));
				bodyLocations = bodyLocations.stream().filter(m -> m.getX() >= 420).sorted((m1, m2) -> new Float(m1.getErrorPerPixel()).compareTo(new Float(m2.getErrorPerPixel())))
						.collect(Collectors.toList());
				List<Rectangle> rects = new ArrayList<>(bodyLocations.size());
				List<TemplateMatch> tmp = new ArrayList<>();
				for (TemplateMatch bl : bodyLocations) {
					Rectangle rect = new Rectangle(bl.getX(), bl.getY(), bl.getWidth(), bl.getHeight());
					if (!intersectsWithAny(rect, rects)) {
						tmp.add(bl);
						rects.add(rect);
						System.out.println(bl.getErrorPerPixel());
					}
				}
				bodyLocations = tmp;
				BufferedImage debugLoc = ImageIO.read(testFile);
				g = debugLoc.createGraphics();
				g.setColor(Color.CYAN);
				for (TemplateMatch m : bodyLocations) {
					g.drawRect(m.getX(), m.getY(), m.getWidth(), m.getHeight());
					g.drawString(String.format(Locale.US, "%.6f", m.getErrorPerPixel()), m.getX(), m.getY());
				}
				g.dispose();
				ImageIO.write(debugLoc, "PNG", new File(debugDir, testFile.getName().replace(".png", "_locations_debug.png")));
			}
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

}
