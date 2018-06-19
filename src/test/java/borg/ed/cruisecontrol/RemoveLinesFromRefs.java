package borg.ed.cruisecontrol;

import java.io.File;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;

public class RemoveLinesFromRefs {

	static final Logger logger = LoggerFactory.getLogger(RemoveLinesFromRefs.class);

	public static void main(String[] args) throws Exception {
		File baseDir = new File(System.getProperty("user.home"), "Google Drive\\Elite Dangerous\\CruiseControl");
		if (!baseDir.exists()) {
			baseDir = new File(System.getProperty("user.home"), "CruiseControl");
		}
		File refDir = new File(baseDir, "ref");
		File sysmapBodiesDir = new File(refDir, "sysmapBodies");
		File sysmapBodiesCleanedDir = new File(refDir, "sysmapBodiesCleaned");

		for (File bodyDir : sysmapBodiesDir.listFiles()) {
			if (bodyDir.isDirectory()) {
				for (File refFile : bodyDir.listFiles()) {
					if (refFile.getName().endsWith(".png")) {
						Planar<GrayF32> orig = ConvertBufferedImage.convertFromMulti(ImageIO.read(refFile), (Planar<GrayF32>) null, true, GrayF32.class);
						Planar<GrayF32> cleaned = orig.clone();
						for (int y = 0; y < orig.height; y++) {
							for (int x = 0; x < orig.width; x++) {
								int r = (int) orig.bands[0].unsafe_get(x, y);
								int g = (int) orig.bands[1].unsafe_get(x, y);
								int b = (int) orig.bands[2].unsafe_get(x, y);
								if (r >= 92 && r <= 106 && g >= 92 && g <= 102 && b >= 92 && b <= 100) {
									cleaned.bands[0].unsafe_set(x, y, 0f);
									cleaned.bands[1].unsafe_set(x, y, 0f);
									cleaned.bands[2].unsafe_set(x, y, 0f);
								}
							}
						}
						File cleanedDir = new File(sysmapBodiesCleanedDir, bodyDir.getName());
						cleanedDir.mkdirs();
						File cleanedFile = new File(cleanedDir, refFile.getName());
						ImageIO.write(ConvertBufferedImage.convertTo_F32(cleaned, null, true), "PNG", cleanedFile);
					}
				}
			}
		}
	}

}
