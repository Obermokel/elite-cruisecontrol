package borg.ed.cruisecontrol.fss;

import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.util.ImageUtil;

/**
 * <p>Scans the entire screen for glowing bubbles, allowing to give direction hints.</p>
 * 
 * <p>
 * Trivia:<br>
 * The screen is refreshed every 2 seconds. If a bubble didn't glow for longer than 2s, then it can be discarded.<br>
 * A full speed vertical turn takes 12 seconds.<br>
 * A full speed horizontal turn takes 12 seconds.<br>
 * </p>
 */
public class FssBodyLocator {

	static final Logger logger = LoggerFactory.getLogger(FssBodyLocator.class);

	@SuppressWarnings("unused")
	private Planar<GrayF32> scaledRgbImage = new Planar<>(GrayF32.class, 1920, 1080, 3);
	private Planar<GrayF32> scaledHsvImage = new Planar<>(GrayF32.class, 1920, 1080, 3);
	private GrayF32 blueBubbleImage = new GrayF32(1920, 1080);
	private GrayF32 workBlur = new GrayF32(1920, 1080);
	private GrayF32 blurredBubbleImage = new GrayF32(1920, 1080);

	/**
	 * Scans a new screen capture (given as RGB and HSV images scaled to 1920x1080).
	 * 
	 * @param scaledRgbImage
	 * 		Must be normalized to 0.0 to 1.0
	 * @param scaledHsvImage
	 * 		Must be created from the normalized RGB image, resulting in h=0..2xPI, s=0..1, v=0..1
	 */
	public void refresh(Planar<GrayF32> scaledRgbImage, Planar<GrayF32> scaledHsvImage) {
		this.scaledRgbImage = scaledRgbImage;
		this.scaledHsvImage = scaledHsvImage;

		this.refreshBlueBubbleImage();
		this.refreshBlurredBubbleImage();
	}

	private void refreshBlueBubbleImage() {
		for (int y = 0; y < scaledHsvImage.height; y++) {
			for (int x = 0; x < scaledHsvImage.width; x++) {
				float h = scaledHsvImage.bands[0].unsafe_get(x, y);
				float s = scaledHsvImage.bands[1].unsafe_get(x, y);
				float v = scaledHsvImage.bands[2].unsafe_get(x, y);

				//					blueBubbleImage.unsafe_set(x, y, 0);
				//					if (v >= 0.5f && v <= 0.65f) {
				//						if (s >= 0.55f) {
				//							if (h >= 3.50f && h <= 3.752f) {
				//								blueBubbleImage.unsafe_set(x, y, Math.min(1.0f, v * 1.5f));
				//							}
				//						}
				//					}
				if (v >= 0.5f && v <= 0.65f && s >= 0.55f && h >= 3.50f && h <= 3.752f) {
					blueBubbleImage.unsafe_set(x, y, Math.min(1.0f, v * 1.5f));
				} else {
					blueBubbleImage.unsafe_set(x, y, 0);
				}
			}
		}
	}

	private void refreshBlurredBubbleImage() {
		GBlurImageOps.gaussian(this.blueBubbleImage, this.blurredBubbleImage, -1, 3, this.workBlur);
	}

	public GrayF32 getBlueBubbleImage() {
		return this.blueBubbleImage;
	}

	public void writeBlueBubbleImage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.blueBubbleImage), null, true), "PNG", pngFile);
	}

	public GrayF32 getBlurredBubbleImage() {
		return this.blurredBubbleImage;
	}

	public void writeBlurredBubbleImage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.blurredBubbleImage), null, true), "PNG", pngFile);
	}

}