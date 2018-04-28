package borg.ed.cruisecontrol;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import borg.ed.cruisecontrol.util.ImageUtil;

public class BlurRefs {

	static final Logger logger = LoggerFactory.getLogger(BlurRefs.class);

	public static void main(String[] args) throws Exception {
		File baseDir = new File(System.getProperty("user.home"), "Google Drive\\Elite Dangerous\\CruiseControl");
		if (!baseDir.exists()) {
			baseDir = new File(System.getProperty("user.home"), "CruiseControl");
		}
		File refDir = new File(baseDir, "ref");
		File debugDir = new File(baseDir, "debug");

		List<File> testFiles = new ArrayList<>();
		testFiles.add(new File(refDir, "compass_dot_filled_bluewhite.png"));
		testFiles.add(new File(refDir, "compass_dot_hollow_bluewhite.png"));
		testFiles.add(new File(refDir, "compass_orange.png"));
		testFiles.add(new File(refDir, "compass_type9.png"));
		testFiles.add(new File(refDir, "cruise_30kms.png"));
		testFiles.add(new File(refDir, "impact.png"));
		testFiles.add(new File(refDir, "scanning.png"));
		testFiles.add(new File(refDir, "ship_hud.png"));
		testFiles.add(new File(refDir, "ship_hud_type9.png"));

		for (File testFile : testFiles) {
			logger.debug("Processing " + testFile.getName());
			BufferedImage orig = ImageIO.read(testFile);
			GrayF32 gray = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(orig, (GrayF32) null));
			GrayF32 blur = GBlurImageOps.gaussian(gray, null, -1, 1, null);
			ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(blur), null), "PNG", new File(refDir, testFile.getName().replace(".png", "_blurred.png")));
		}
	}

}
