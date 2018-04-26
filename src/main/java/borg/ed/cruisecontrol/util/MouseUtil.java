package borg.ed.cruisecontrol.util;

import java.awt.Point;

public class MouseUtil {

    private final int screenWidth;
    private final int screenHeight;
    private final int imageWidth;
    private final int imageHeight;

    private final float scale;
    private final int paddingLeftRight;
    private final int paddingTopBottom;

    public MouseUtil(int screenWidth, int screenHeight, int imageWidth, int imageHeight) {
        if (screenWidth < imageWidth) {
            throw new IllegalArgumentException("Screen width " + screenWidth + " must not be less than image width " + imageWidth);
        } else if (screenHeight < imageHeight) {
            throw new IllegalArgumentException("Screen height " + screenHeight + " must not be less than image height " + imageHeight);
        } else {
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;

            float scale = (float) screenHeight / (float) imageHeight;
            int scaledWidth = Math.round(imageWidth * scale);
            int scaledHeight = Math.round(imageHeight * scale);
            int paddingLeftRight = (screenWidth - scaledWidth) / 2;
            int paddingTopBottom = (screenHeight - scaledHeight) / 2;
            if (paddingLeftRight < 0 || paddingTopBottom < 0) {
                scale = (float) screenWidth / (float) imageWidth;
                scaledWidth = Math.round(imageWidth * scale);
                scaledHeight = Math.round(imageHeight * scale);
                paddingLeftRight = (screenWidth - scaledWidth) / 2;
                paddingTopBottom = (screenHeight - scaledHeight) / 2;
            }
            this.scale = scale;
            this.paddingLeftRight = paddingLeftRight;
            this.paddingTopBottom = paddingTopBottom;
        }
    }

    public Point imageToScreen(Point pImage) {
        return new Point(Math.round(pImage.x / this.scale) + this.paddingLeftRight, Math.round(pImage.y / this.scale) + this.paddingTopBottom);
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public int getImageWidth() {
        return imageWidth;
    }

    public int getImageHeight() {
        return imageHeight;
    }

}
