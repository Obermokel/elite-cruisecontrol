package borg.ed.cruisecontrol;

import java.awt.Rectangle;
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
import borg.ed.cruisecontrol.sysmap.SysmapBody;
import borg.ed.cruisecontrol.sysmap.SysmapScanner;
import borg.ed.cruisecontrol.util.ImageUtil;

public class DistMasses {

    private static final String FILENAME_PATTERN = " system_map_body3_rgb_original ";

    static final Logger logger = LoggerFactory.getLogger(DistMasses.class);

    public static void main(String[] args) throws Exception {
        final SysmapScanner sysmapScanner = new SysmapScanner();
        sysmapScanner.setWriteDebugImageBodyGray(true);
        sysmapScanner.setWriteDebugImageBodyRgbResult(true);

        File baseDir = new File(System.getProperty("user.home"), "Google Drive\\Elite Dangerous\\CruiseControl");
        if (!baseDir.exists()) {
            baseDir = new File(System.getProperty("user.home"), "CruiseControl");
        }
        File refDir = new File(baseDir, "ref");
        File debugDir = new File(baseDir, "debug");

        File[] testFiles = debugDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.getName().endsWith(".png") && file.getName().contains(FILENAME_PATTERN);
                //return file.getName().endsWith(".png") && file.getName().contains(FILENAME_PATTERN) && file.getName().contains("Boelts WL-L d8-846");
            }
        });

        for (File testFile : testFiles) {
            logger.debug("Processing " + testFile.getName());
            String systemName = testFile.getName().substring(testFile.getName().indexOf(FILENAME_PATTERN) + FILENAME_PATTERN.length(), testFile.getName().indexOf(".png")).trim();
            BufferedImage bi = ImageIO.read(testFile);
            Planar<GrayF32> rgb = ImageUtil.normalize255(ConvertBufferedImage.convertFromMulti(bi, (Planar<GrayF32>) null, true, GrayF32.class));
            Planar<GrayF32> hsv = rgb.createSameShape();
            ColorHsv.rgbToHsv_F32(rgb, hsv);
            if (sysmapScanner.isUniversalCartographicsLogoVisible(rgb)) {
                SysmapBody b = new SysmapBody(new Rectangle(420, 20, 50, 50));
                sysmapScanner.extractBodyData(rgb, hsv, b);
                sysmapScanner.writeDebugImages(b, systemName, "body0");
            }
        }
    }

}
