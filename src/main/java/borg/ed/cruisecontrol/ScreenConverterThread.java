package borg.ed.cruisecontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import boofcv.alg.color.ColorHsv;
import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.util.ImageUtil;

public class ScreenConverterThread extends Thread {

	static final Logger logger = LoggerFactory.getLogger(ScreenConverterThread.class);

	public volatile boolean shutdown = false;

	private final ScreenConverterResult screenConverterResult = new ScreenConverterResult();

	@Autowired
	private ScreenReaderThread screenReaderThread = null;

	public ScreenConverterThread() {
		this.setName("SCThread");
		this.setDaemon(true);
	}

	@Override
	public void run() {
		logger.info(this.getName() + " started");

		final ScreenReaderResult screenReaderResult = this.screenReaderThread.getScreenReaderResult();

		Planar<GrayF32> rgb = new Planar<>(GrayF32.class, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, 3);
		Planar<GrayF32> hsv = rgb.createSameShape();

		GrayF32 orangeHudImage = new GrayF32(CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);
		GrayF32 yellowHudImage = orangeHudImage.createSameShape();
		GrayF32 blueWhiteHudImage = orangeHudImage.createSameShape();
		GrayF32 redHudImage = orangeHudImage.createSameShape();
		GrayF32 brightImage = orangeHudImage.createSameShape();

		GrayF32 work = orangeHudImage.createSameShape();

		while (!Thread.currentThread().isInterrupted() && !this.shutdown) {
			// Wait for the next (scaled) screen capture and convert it into BoofCV format
			synchronized (screenReaderResult) {
				try {
					screenReaderResult.wait();
					ConvertBufferedImage.convertFromMulti(screenReaderResult.getScaledScreenCapture(), rgb, true, GrayF32.class);
				} catch (InterruptedException e) {
					break;
				}
			}

			// Convert into relevant color bands
			rgb = ImageUtil.normalize255(rgb);
			ColorHsv.rgbToHsv_F32(rgb, hsv);
			this.hsvToHudImages(hsv, orangeHudImage, yellowHudImage, blueWhiteHudImage, redHudImage, brightImage);
			GBlurImageOps.gaussian(orangeHudImage, orangeHudImage, -1, 1, work);
			GBlurImageOps.gaussian(yellowHudImage, yellowHudImage, -1, 1, work);
			GBlurImageOps.gaussian(blueWhiteHudImage, blueWhiteHudImage, -1, 1, work);
			GBlurImageOps.gaussian(redHudImage, redHudImage, -1, 1, work);

			// Notify waiting threads
			synchronized (this.screenConverterResult) {
				this.screenConverterResult.setRgb(rgb);
				this.screenConverterResult.setHsv(hsv);
				this.screenConverterResult.setOrangeHudImage(orangeHudImage);
				this.screenConverterResult.setYellowHudImage(yellowHudImage);
				this.screenConverterResult.setBlueWhiteHudImage(blueWhiteHudImage);
				this.screenConverterResult.setRedHudImage(redHudImage);
				this.screenConverterResult.setBrightImage(brightImage);
				this.screenConverterResult.notifyAll();
			}
		}

		logger.info(this.getName() + " stopped");
	}

	public ScreenConverterResult getScreenConverterResult() {
		return screenConverterResult;
	}

	private void hsvToHudImages(Planar<GrayF32> hsv, GrayF32 orangeHudImage, GrayF32 yellowHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
		for (int y = 0; y < hsv.height; y++) {
			for (int x = 0; x < hsv.width; x++) {
				float h = hsv.bands[0].unsafe_get(x, y);
				float s = hsv.bands[1].unsafe_get(x, y);
				float v = hsv.bands[2].unsafe_get(x, y);

				if ((s > 0.70f) && (v >= 0.50f) && (h >= 0.25f && h < 1.00f)) {
					// Orange
					orangeHudImage.unsafe_set(x, y, v);
				} else {
					orangeHudImage.unsafe_set(x, y, 0);
				}

				if ((s > 0.6f) && (v >= 0.85f) && (h >= 0.5f && h < 1.0f)) {
					// Yellow
					yellowHudImage.unsafe_set(x, y, v);
				} else {
					yellowHudImage.unsafe_set(x, y, 0);
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
				} else if (v >= 0.25f && s >= 0.75f && h >= 5.5f && h <= 6.0f) { // Brown dwarf stars...
					brightImage.unsafe_set(x, y, v);
				} else {
					brightImage.unsafe_set(x, y, 0);
				}
			}
		}
	}

}
