package borg.ed.cruisecontrol.templatematching;

import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;

public class TemplateMatchRgb {

    private final Planar<GrayF32> image;
    private final TemplateRgb template;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final float error;
    private final float errorPerPixel;

    public TemplateMatchRgb(Planar<GrayF32> image, TemplateRgb template, int x, int y, int width, int height, float error, float errorPerPixel) {
        this.image = image;
        this.template = template;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.error = error;
        this.errorPerPixel = errorPerPixel;
    }

    public Planar<GrayF32> getImage() {
        return image;
    }

    public TemplateRgb getTemplate() {
        return template;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float getError() {
        return error;
    }

    public float getErrorPerPixel() {
        return errorPerPixel;
    }

}
