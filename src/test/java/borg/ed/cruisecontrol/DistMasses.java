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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.color.ColorHsv;
import boofcv.core.image.ConvertImage;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.templatematching.Template;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;
import borg.ed.cruisecontrol.util.ImageUtil;

public class DistMasses {

    static final Logger logger = LoggerFactory.getLogger(DistMasses.class);

    public static void main(String[] args) throws Exception {
        File baseDir = new File(System.getProperty("user.home"), "Google Drive\\Elite Dangerous\\CruiseControl");
        if (!baseDir.exists()) {
            baseDir = new File(System.getProperty("user.home"), "CruiseControl");
        }
        File refDir = new File(baseDir, "ref");
        File debugDir = new File(baseDir, "debug");

        File[] testFiles = debugDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.getName().endsWith(".png") && (file.getName().contains("Blido Piru") || file.getName().contains("Maridal")) && !file.getName().endsWith("_debug.png")
                        && !file.getName().endsWith("_scaled.png");
            }
        });

        Template refUnexplored = Template.fromFile(new File(refDir, "unexplored.png"));
        Template refArrivalPoint = Template.fromFile(new File(refDir, "arrival_point.png"));
        Template refEarthMasses = Template.fromFile(new File(refDir, "earth_masses.png"));
        Template refRadius = Template.fromFile(new File(refDir, "radius.png"));
        List<Template> textTemplates = Template.fromFolder(new File(refDir, "sysMapText"));

        for (File testFile : testFiles) {
            logger.debug("Processing " + testFile.getName());
            BufferedImage bi = ImageUtil.scaleAndCrop(ImageIO.read(testFile), 1920, 1080);
            //ImageIO.write(bi, "PNG", new File(debugDir, testFile.getName().replace(".png", "_scaled.png")));
            Planar<GrayF32> rgb = ImageUtil.normalize255(ConvertBufferedImage.convertFromMulti(bi, (Planar<GrayF32>) null, true, GrayF32.class));
            Planar<GrayF32> hsv = rgb.createSameShape();
            ColorHsv.rgbToHsv_F32(rgb, hsv);
            GrayF32 gray = ConvertImage.average(rgb, null);
            for (int y = 0; y < hsv.height; y++) {
                for (int x = 0; x < hsv.width; x++) {
                    float v = hsv.bands[2].unsafe_get(x, y);
                    float s = hsv.bands[1].unsafe_get(x, y);
                    if (v < 0.45f || s > 0.2f) {
                        gray.unsafe_set(x, y, 0f);
                    }
                }
            }
            BufferedImage debug = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(gray), null);
            ImageIO.write(debug, "PNG", new File(debugDir, testFile.getName().replace(".png", "_00_debug.png")));

            TemplateMatch mEarthMasses = TemplateMatcher.findBestMatchingLocationInRegion(gray, 0, 180, 210, 400, refEarthMasses);
            if (mEarthMasses.getErrorPerPixel() > 0.025f) {
                logger.warn("Earth masses not found - epp=" + mEarthMasses.getErrorPerPixel());
            } else {
                TemplateMatch mUnexplored = TemplateMatcher.findBestMatchingLocationInRegion(gray, 420, 0, 1920 - 420, 1080, refUnexplored);
                if (mUnexplored.getErrorPerPixel() > 0.025f) {
                    logger.warn("Unexplored not found - epp=" + mUnexplored.getErrorPerPixel());
                } else {
                    TemplateMatch mArrivalPoint = TemplateMatcher.findBestMatchingLocationInRegion(gray, mUnexplored.getX() - 20, mUnexplored.getY() + 20, 170, 55,
                            refArrivalPoint);
                    TemplateMatch mRadius = TemplateMatcher.findBestMatchingLocationInRegion(gray, mEarthMasses.getX() - 2, mEarthMasses.getY() + 20, 170, 40, refRadius);
                    if (mArrivalPoint.getErrorPerPixel() > 0.025f) {
                        logger.warn("Arrival Point not found - epp=" + mArrivalPoint.getErrorPerPixel());
                    } else if (mRadius.getErrorPerPixel() > 0.025f) {
                        logger.warn("Radius not found - epp=" + mRadius.getErrorPerPixel());
                    } else {
                        logger.debug(String.format(Locale.US, "EM=%.4f / UNEXPL=%.4f / AP=%.4f / RAD=%.4f", mEarthMasses.getErrorPerPixel(), mUnexplored.getErrorPerPixel(),
                                mArrivalPoint.getErrorPerPixel(), mRadius.getErrorPerPixel()));

                        int apX0 = Math.min(gray.width - 1, mArrivalPoint.getX() + mArrivalPoint.getWidth());
                        int apY0 = Math.max(0, mArrivalPoint.getY() - 5);
                        int apX1 = Math.min(gray.width - 1, apX0 + 225);
                        int apY1 = Math.min(gray.height - 1, apY0 + 30);
                        String arrivalPointText = scanText(gray.subimage(apX0, apY0, apX1, apY1), textTemplates);
                        int emX0 = Math.min(gray.width - 1, mEarthMasses.getX() + mEarthMasses.getWidth());
                        int emY0 = Math.max(0, mEarthMasses.getY() - 5);
                        int emX1 = Math.min(gray.width - 1, emX0 + 250);
                        int emY1 = Math.min(gray.height - 1, emY0 + 30);
                        String earthMassesText = scanText(gray.subimage(emX0, emY0, emX1, emY1), textTemplates);
                        int radX0 = Math.min(gray.width - 1, mRadius.getX() + mRadius.getWidth());
                        int radY0 = Math.max(0, mRadius.getY() - 5);
                        int radX1 = Math.min(gray.width - 1, radX0 + 300);
                        int radY1 = Math.min(gray.height - 1, radY0 + 30);
                        String radiusText = scanText(gray.subimage(radX0, radY0, radX1, radY1), textTemplates);

                        Graphics2D g = bi.createGraphics();
                        g.setColor(Color.CYAN);
                        g.drawString(arrivalPointText, mArrivalPoint.getX(), mArrivalPoint.getY());
                        g.drawString(earthMassesText, mEarthMasses.getX(), mEarthMasses.getY());
                        g.drawString(radiusText, mRadius.getX(), mRadius.getY());
                        g.dispose();
                        ImageIO.write(bi, "PNG", new File(debugDir, testFile.getName().replace(".png", "_01_debug.png")));
                    }
                }
            }
        }
    }

    private static String scanText(GrayF32 image, List<Template> textTemplates) {
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

}
