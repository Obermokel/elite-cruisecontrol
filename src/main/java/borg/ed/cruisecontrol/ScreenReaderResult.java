package borg.ed.cruisecontrol;

import java.awt.image.BufferedImage;

public class ScreenReaderResult {

	private BufferedImage scaledScreenCapture = null;

	public BufferedImage getScaledScreenCapture() {
		return scaledScreenCapture;
	}

	public void setScaledScreenCapture(BufferedImage scaledScreenCapture) {
		this.scaledScreenCapture = scaledScreenCapture;
	}

}
