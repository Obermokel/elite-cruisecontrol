package borg.ed.cruisecontrol;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import org.ddogleg.struct.FastQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.abst.feature.associate.AssociateDescription;
import boofcv.abst.feature.associate.ScoreAssociation;
import boofcv.abst.feature.detdesc.DetectDescribePoint;
import boofcv.abst.feature.detect.interest.ConfigFastHessian;
import boofcv.alg.descriptor.UtilFeature;
import boofcv.factory.feature.associate.FactoryAssociation;
import boofcv.factory.feature.detdesc.FactoryDetectDescribe;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.feature.AssociatedIndex;
import boofcv.struct.feature.BrightFeature;
import boofcv.struct.image.GrayF32;
import borg.ed.cruisecontrol.util.ImageUtil;
import georegression.struct.point.Point2D_F64;

public class FeatureTest {

	static final Logger logger = LoggerFactory.getLogger(FeatureTest.class);

	public static final String FILENAME_PATTERN = "cc_view_11_yellow";

	private static final int TARGET_REGION_X = 555;
	private static final int TARGET_REGION_Y = 190;
	private static final int TARGET_REGION_WIDTH = 810;
	private static final int TARGET_REGION_HEIGHT = 570;

	public static void main(String[] args) throws Exception {
		float detectThreshold = 0.0008f; // Default: 1
		int extractRadius = 2; // Default: 2
		int maxFeaturesPerScale = -1; // Default: 300; < 0 = all
		int initialSampleSize = 1; // Default: 1
		int initialSize = 9; // Default: 9
		int numberOfOctaves = 4; // Default: 4
		int numberScalesPerOctave = 4; // Default: 4
		DetectDescribePoint<GrayF32, BrightFeature> detDesc = FactoryDetectDescribe.surfStable(
				new ConfigFastHessian(detectThreshold, extractRadius, maxFeaturesPerScale, initialSampleSize, initialSize, numberScalesPerOctave, numberOfOctaves), null, null, GrayF32.class);
		ScoreAssociation<BrightFeature> scorer = FactoryAssociation.defaultScore(detDesc.getDescriptionType());
		AssociateDescription<BrightFeature> associate = FactoryAssociation.greedy(scorer, Double.MAX_VALUE, true);

		File baseDir = new File(System.getProperty("user.home"), "Google Drive\\Elite Dangerous\\CruiseControl");
		if (!baseDir.exists()) {
			baseDir = new File(System.getProperty("user.home"), "CruiseControl");
		}
		File refDir = new File(baseDir, "ref");
		File debugDir = new File(baseDir, "debug");

		File refFile = new File(refDir, "target_yellow_feature.png");
		GrayF32 ref = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(refFile), (GrayF32) null));
		detDesc.detect(ref);
		logger.debug("Found " + detDesc.getNumberOfFeatures() + " features IN REF");
		List<Point2D_F64> pointsRef = new ArrayList<>();
		FastQueue<BrightFeature> descRef = UtilFeature.createQueue(detDesc, 100);
		for (int i = 0; i < detDesc.getNumberOfFeatures(); i++) {
			pointsRef.add(detDesc.getLocation(i).copy());
			descRef.grow().setTo(detDesc.getDescription(i));
		}

		File[] testFiles = debugDir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.getName().endsWith(".png") && !file.getName().endsWith("_associate.png") && file.getName().contains(FILENAME_PATTERN);
				//return file.getName().endsWith(".png") && file.getName().contains(FILENAME_PATTERN) && file.getName().contains("KC-B");
			}
		});

		for (File testFile : testFiles) {
			GrayF32 image = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(testFile), (GrayF32) null));
			logger.debug("Processing " + testFile.getName());
			GrayF32 subimage = image.subimage(TARGET_REGION_X, TARGET_REGION_Y, TARGET_REGION_X + TARGET_REGION_WIDTH, TARGET_REGION_Y + TARGET_REGION_HEIGHT);
			detDesc.detect(subimage);
			logger.debug("Found " + detDesc.getNumberOfFeatures() + " features");
			List<Point2D_F64> pointsImage = new ArrayList<>();
			FastQueue<BrightFeature> descImage = UtilFeature.createQueue(detDesc, 100);
			for (int i = 0; i < detDesc.getNumberOfFeatures(); i++) {
				pointsImage.add(detDesc.getLocation(i).copy());
				descImage.grow().setTo(detDesc.getDescription(i));
			}
			associate.setSource(descRef);
			associate.setDestination(descImage);
			associate.associate();
			logger.debug("Found " + associate.getMatches().size() + " matches");

			FastQueue<AssociatedIndex> matches = associate.getMatches();
			if (matches.size() == 0) {
				continue;
			}
			List<Double> distances = new ArrayList<>();
			for (int i = 0; i < matches.size(); i++) {
				AssociatedIndex a = matches.get(i);
				Point2D_F64 refPoint = pointsRef.get(a.src);
				Point2D_F64 imagePoint = pointsImage.get(a.dst);
				distances.add(refPoint.distance(imagePoint));
			}
			Collections.sort(distances);
			double medianDistance = distances.get(distances.size() / 2);
			logger.debug("medianDistance = " + medianDistance);
			logger.debug(distances.toString());

			List<Integer> dxList = new ArrayList<>();
			List<Integer> dyList = new ArrayList<>();
			for (int i = 0; i < matches.size(); i++) {
				AssociatedIndex a = matches.get(i);
				Point2D_F64 refPoint = pointsRef.get(a.src);
				Point2D_F64 imagePoint = pointsImage.get(a.dst);
				if (Math.abs(refPoint.distance(imagePoint) - medianDistance) < 10) {
					dxList.add(((int) imagePoint.x + TARGET_REGION_X) - (int) refPoint.x);
					dyList.add(((int) imagePoint.y + TARGET_REGION_Y) - (int) refPoint.y);
				}
			}
			Collections.sort(dxList);
			Collections.sort(dyList);
			logger.debug("matchingMedian.size = " + dxList.size());
			logger.debug(dxList.toString());
			logger.debug(dyList.toString());
			if (dxList.isEmpty()) {
				continue;
			}
			int medianDx = dxList.get(dxList.size() / 2);
			int medianDy = dyList.get(dyList.size() / 2);

			BufferedImage bi = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < image.height; y++) {
				for (int x = 0; x < image.width; x++) {
					int vr = (int) (ref.unsafe_get(x, y) * 255);
					if (vr > 0) {
						bi.setRGB(x, y, new Color(0, vr, 0).getRGB());
					}
					int vi = (int) (image.unsafe_get(x, y) * 255);
					if (vi > 0) {
						bi.setRGB(x, y, new Color(0, 0, vi).getRGB());
					}
				}
			}
			Graphics2D g = bi.createGraphics();
			g.setColor(new Color(255, 0, 0));
			g.setStroke(new BasicStroke(3));
			g.drawLine(960, 540, 960 + medianDx, 540 + medianDy);
			g.dispose();
			ImageIO.write(bi, "PNG", new File(debugDir, testFile.getName().replace(".png", "_associate.png")));
		}
	}

}
