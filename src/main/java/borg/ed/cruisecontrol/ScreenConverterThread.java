package borg.ed.cruisecontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.color.ColorHsv;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.util.ImageUtil;

public class ScreenConverterThread extends Thread {

	static final Logger logger = LoggerFactory.getLogger(ScreenConverterThread.class);

	private final ScreenReaderResult screenReaderResult;
	private final ScreenConverterResult screenConverterResult;

	public ScreenConverterThread(ScreenReaderResult screenReaderResult, ScreenConverterResult screenConverterResult) {
		this.setName("SCThread");
		this.setDaemon(true);

		this.screenReaderResult = screenReaderResult;
		this.screenConverterResult = screenConverterResult;
	}

	@Override
	public void run() {
		logger.info(this.getName() + " started");

		Planar<GrayF32> rgb = new Planar<>(GrayF32.class, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, 3);
		Planar<GrayF32> hsv = rgb.createSameShape();

		GrayF32 orangeHudImage = new GrayF32(CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);
		GrayF32 blueWhiteHudImage = orangeHudImage.createSameShape();
		GrayF32 redHudImage = orangeHudImage.createSameShape();
		GrayF32 brightImage = orangeHudImage.createSameShape();

		while (!Thread.currentThread().isInterrupted()) {
			// Wait for the next (scaled) screen capture and convert it into BoofCV format
			synchronized (this.screenReaderResult) {
				try {
					this.screenReaderResult.wait();
					ConvertBufferedImage.convertFromMulti(this.screenReaderResult.getScaledScreenCapture(), rgb, true, GrayF32.class);
				} catch (InterruptedException e) {
					break;
				}
			}

			// Convert into relevant color bands
			ColorHsv.rgbToHsv_F32(rgb, hsv);
			this.hsvToHudImages(hsv, orangeHudImage, blueWhiteHudImage, redHudImage, brightImage);
			rgb = ImageUtil.normalize255(rgb);

			// Notify waiting threads
			synchronized (this.screenConverterResult) {
				this.screenConverterResult.setRgb(rgb);
				this.screenConverterResult.setOrangeHudImage(orangeHudImage);
				this.screenConverterResult.setBlueWhiteHudImage(blueWhiteHudImage);
				this.screenConverterResult.setRedHudImage(redHudImage);
				this.screenConverterResult.setBrightImage(brightImage);
				this.screenConverterResult.notifyAll();
			}
		}

		logger.info(this.getName() + " stopped");
	}

	private void hsvToHudImages(Planar<GrayF32> hsv, GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
		for (int y = 0; y < hsv.height; y++) {
			for (int x = 0; x < hsv.width; x++) {
				float h = hsv.bands[0].unsafe_get(x, y);
				float s = hsv.bands[1].unsafe_get(x, y);
				float v = hsv.bands[2].unsafe_get(x, y) / 255f;

				if ((s > 0.70f) && (v >= 0.50f) && (h >= 0.25f && h < 1.00f)) {
					// Orange
					orangeHudImage.unsafe_set(x, y, v);
				} else {
					orangeHudImage.unsafe_set(x, y, 0);
				}

				if (v >= 0.75f && s < 0.15f) {
					// White
					blueWhiteHudImage.unsafe_set(x, y, v * v);
				} else if ((h > 3.14f && h < 3.84f) && s > 0.15f) {
					// Blue-white
					blueWhiteHudImage.unsafe_set(x, y, v * v);
				} else {
					blueWhiteHudImage.unsafe_set(x, y, 0);
				}

				if ((s > 0.80f) && (v >= 0.70f) && (h < 0.25f || h > 6.0f)) {
					// Red
					redHudImage.unsafe_set(x, y, v * v * v);
				} else {
					redHudImage.unsafe_set(x, y, 0);
				}

				if (v >= 0.85f) {
					brightImage.unsafe_set(x, y, v);
				} else {
					brightImage.unsafe_set(x, y, 0);
				}
			}
		}
	}

}
