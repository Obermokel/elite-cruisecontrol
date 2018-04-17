package borg.ed.cruisecontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.color.ColorHsv;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;

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

		while (!Thread.currentThread().isInterrupted()) {
			// Wait for the next (scaled) screen capture and convert it into BoofCV format
			synchronized (this.screenReaderResult) {
				try {
					this.screenReaderResult.wait();
					ConvertBufferedImage.convertFrom(this.screenReaderResult.getScaledScreenCapture(), true, rgb);
				} catch (InterruptedException e) {
					break;
				}
			}

			// Convert into relevant color bands
			ColorHsv.rgbToHsv_F32(rgb, hsv);
			this.hsvToHudImages(hsv, orangeHudImage, blueWhiteHudImage, redHudImage);

			// Notify waiting threads
			synchronized (this.screenConverterResult) {
				this.screenConverterResult.setOrangeHudImage(orangeHudImage);
				this.screenConverterResult.setBlueWhiteHudImage(blueWhiteHudImage);
				this.screenConverterResult.setRedHudImage(redHudImage);
				this.screenConverterResult.notifyAll();
				logger.debug("Notified waiting threads of new screen conversion result");
			}
		}

		logger.info(this.getName() + " stopped");
	}

	private void hsvToHudImages(Planar<GrayF32> hsv, GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage) {
		for (int y = 0; y < hsv.height; y++) {
			for (int x = 0; x < hsv.width; x++) {
				float h = hsv.bands[0].unsafe_get(x, y);
				float s = hsv.bands[1].unsafe_get(x, y);
				float v = hsv.bands[2].unsafe_get(x, y) / 255f;

				if ((s > 0.70f) && (v >= 0.50f) && (h >= 0.25f && h < 1.00f)) {
					// Orange
					orangeHudImage.unsafe_set(x, y, v * 255);
				} else {
					orangeHudImage.unsafe_set(x, y, 0);
				}

				if (v >= 0.75f && s < 0.15f) {
					// White
					blueWhiteHudImage.unsafe_set(x, y, v * v * 255);
				} else if ((h > 3.14f && h < 3.84f) && s > 0.15f) {
					// Blue-white
					blueWhiteHudImage.unsafe_set(x, y, v * v * 255);
				} else {
					blueWhiteHudImage.unsafe_set(x, y, 0);
				}

				if ((s > 0.80f) && (v >= 0.70f) && (h < 0.25f || h > 6.0f)) {
					// Red
					redHudImage.unsafe_set(x, y, v * v * v * 255);
				} else {
					redHudImage.unsafe_set(x, y, 0);
				}
			}
		}
	}

}
