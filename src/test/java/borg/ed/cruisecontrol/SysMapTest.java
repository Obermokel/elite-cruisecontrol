package borg.ed.cruisecontrol;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileFilter;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.color.ColorHsv;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.sysmap.SysmapScanner;
import borg.ed.cruisecontrol.util.ImageUtil;

public class SysMapTest {

	static final Logger logger = LoggerFactory.getLogger(SysMapTest.class);

	public static void main(String[] args) throws Exception {
		final SysmapScanner sysmapScanner = new SysmapScanner();
		sysmapScanner.setWriteDebugImageGray(true);
		sysmapScanner.setWriteDebugImageRgbResult(true);
		sysmapScanner.setWriteDebugImageThreshold(true);

		File baseDir = new File(System.getProperty("user.home"), "Google Drive\\Elite Dangerous\\CruiseControl");
		File refDir = new File(baseDir, "ref");
		File debugDir = new File(baseDir, "debug");

		File[] testFiles = debugDir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.getName().endsWith(".png") && file.getName().contains("System map debug");
			}
		});

		for (File testFile : testFiles) {
			logger.debug("Processing " + testFile.getName());
			String systemName = testFile.getName().substring(testFile.getName().indexOf("System map debug") + "System map debug".length(), testFile.getName().indexOf(".png")).trim();
			BufferedImage bi = ImageIO.read(testFile);
			for (int y = 0; y < bi.getHeight(); y++) {
				for (int x = 0; x < bi.getWidth(); x++) {
					if (bi.getRGB(x, y) == Color.GREEN.getRGB()) {
						bi.setRGB(x, y, Color.BLACK.getRGB());
					}
				}
			}
			Planar<GrayF32> rgb = ImageUtil.normalize255(ConvertBufferedImage.convertFromMulti(bi, (Planar<GrayF32>) null, true, GrayF32.class));
			Planar<GrayF32> hsv = rgb.createSameShape();
			ColorHsv.rgbToHsv_F32(rgb, hsv);
			if (sysmapScanner.isUniversalCartographicsLogoVisible(rgb)) {
				sysmapScanner.scanSystemMap(rgb, hsv, systemName);
			}
		}
	}

}
