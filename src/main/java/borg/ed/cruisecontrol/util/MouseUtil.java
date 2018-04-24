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

			float scaleHeight = (float) imageHeight / (float) screenHeight;
			int scaledWidth = Math.round(scaleHeight * screenWidth);

			if (scaledWidth == imageWidth) {
				// Perfect!
				this.scale = scaleHeight;
				this.paddingLeftRight = 0;
				this.paddingTopBottom = 0;
			} else if (scaledWidth > imageWidth) {
				// Left and right can be cropped
				this.scale = scaleHeight;
				this.paddingLeftRight = (screenWidth - scaledWidth) / 2;
				this.paddingTopBottom = 0;
			} else {
				// Width would be too small, scale by width instead
				float scaleWidth = (float) imageWidth / (float) scaledWidth;
				int scaledHeight = Math.round(scaleWidth * screenHeight);

				if (scaledHeight == imageHeight) {
					// Perfect!
					this.scale = scaleWidth;
					this.paddingLeftRight = 0;
					this.paddingTopBottom = 0;
				} else if (scaledHeight > imageHeight) {
					// Top and bottom can be cropped
					this.scale = scaleWidth;
					this.paddingLeftRight = 0;
					this.paddingTopBottom = (screenHeight - scaledHeight) / 2;
				} else {
					throw new IllegalStateException(scaledWidth + "x" + screenHeight + " -> " + imageWidth + "x" + imageHeight);
				}
			}
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
