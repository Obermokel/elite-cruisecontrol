package borg.ed.cruisecontrol.templatematching;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import boofcv.abst.distort.FDistort;
import boofcv.alg.interpolate.InterpolationType;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import borg.ed.cruisecontrol.util.ImageUtil;

public class Template {

    private final File file;
    private final GrayF32 pixels;
    private final GrayF32 mask;
    private final String name;

    private final Map<String, GrayF32> scaledPixelsCache = new HashMap<>();
    private final Map<String, GrayF32> scaledMaskCache = new HashMap<>();

    private Template(File file, GrayF32 pixels, GrayF32 mask, String name) {
        this.file = file;
        this.pixels = pixels;
        this.mask = mask;
        this.name = name;
    }

    public static Template fromFile(File file) throws IOException {
        GrayF32 pixels = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(file), (GrayF32) null));
        String name = file.getParentFile().getName().replace("_dot", ".").replace("_comma", ",");
        GrayF32 mask = null;
        File maskFile = new File(file.getParentFile(), file.getName().replace(".png", "_mask.png"));
        if (maskFile.exists()) {
            mask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(maskFile), (GrayF32) null));
        }

        return new Template(file, pixels, mask, name);
    }

    public static List<Template> fromFolder(File setDir) throws IOException {
        List<Template> result = new ArrayList<>();
        File[] nameDirs = setDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory();
            }
        });
        for (File nameDir : nameDirs) {
            File[] files = nameDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    return file.getName().endsWith(".png") && !file.getName().endsWith("_mask.png");
                }
            });
            for (File file : files) {
                result.add(Template.fromFile(file));
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return this.getName() + " (" + this.getWidth() + "x" + this.getHeight() + ")";
    }

    public GrayF32 scalePixelsToSize(int width, int height) {
        if (this.getPixels().getWidth() == width && this.getPixels().getHeight() == height) {
            return this.getPixels();
        } else if (this.scaledPixelsCache.containsKey(width + "x" + height)) {
            return this.scaledPixelsCache.get(width + "x" + height);
        } else {
            GrayF32 scaled = new GrayF32(width, height);
            new FDistort().input(this.getPixels()).output(scaled).interp(InterpolationType.BICUBIC).scaleExt().apply();
            this.scaledPixelsCache.put(width + "x" + height, scaled);
            return scaled;
        }
    }

    public GrayF32 scaleMaskToSize(int width, int height) {
        if (this.getMask() == null) {
            return null;
        } else if (this.getMask().getWidth() == width && this.getMask().getHeight() == height) {
            return this.getMask();
        } else if (this.scaledMaskCache.containsKey(width + "x" + height)) {
            return this.scaledMaskCache.get(width + "x" + height);
        } else {
            GrayF32 scaled = new GrayF32(width, height);
            new FDistort().input(this.getMask()).output(scaled).interp(InterpolationType.BICUBIC).scaleExt().apply();
            this.scaledMaskCache.put(width + "x" + height, scaled);
            return scaled;
        }
    }

    /**
     * Source file of this template data
     */
    public File getFile() {
        return this.file;
    }

    /**
     * Pre-processed image data of the template file
     */
    public GrayF32 getPixels() {
        return this.pixels;
    }

    public GrayF32 getMask() {
        return this.mask;
    }

    /**
     * Name of the template
     */
    public String getName() {
        return this.name;
    }

    /**
     * Width in pixels of this template
     */
    public int getWidth() {
        return this.pixels.width;
    }

    /**
     * Height in pixels of this template
     */
    public int getHeight() {
        return this.pixels.height;
    }

}
