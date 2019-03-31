package borg.ed.cruisecontrol.fss;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.util.ImageUtil;
import borg.ed.galaxy.constants.PlanetClass;
import borg.ed.galaxy.util.MiscUtil;

/**
 * The spectrum bar of the FSS
 */
public class FssSpectrumBar {

	static final Logger logger = LoggerFactory.getLogger(FssSpectrumBar.class);

	// Coordinates in 1920x1080: 580,814 to 1340,856
	public static final int X_OFFSET = 580;
	public static final int Y_OFFSET = 814;
	public static final int X_END = 1340;
	public static final int Y_END = 856;
	public static final int WIDTH = X_END - X_OFFSET;
	public static final int HEIGHT = Y_END - Y_OFFSET;

	private Planar<GrayF32> rgbSubimage = new Planar<>(GrayF32.class, WIDTH, HEIGHT, 3);
	private Planar<GrayF32> hsvSubimage = new Planar<>(GrayF32.class, WIDTH, HEIGHT, 3);
	private Planar<GrayF32> debugBackgroundSubimage = new Planar<>(GrayF32.class, WIDTH, HEIGHT, 3);
	private Planar<GrayF32> debugSubimage = new Planar<>(GrayF32.class, WIDTH, HEIGHT, 3);
	private GrayF32 whiteIndicatorSubimage = new GrayF32(WIDTH, HEIGHT);
	private GrayF32 blueSpectrumSubimage = new GrayF32(WIDTH, HEIGHT);
	private Float currentFrequency = null;
	private List<Float> remainingAmplitues = new ArrayList<Float>();

	public FssSpectrumBar() {
		this.initDebugBackgroundSubimage();
	}

	/**
	 * Scans a new screen capture (given as RGB and HSV images scaled to 1920x1080).
	 * 
	 * @param scaledRgbImage
	 * 		Must be normalized to 0.0 to 1.0
	 * @param scaledHsvImage
	 * 		Must be created from the normalized RGB image, resulting in h=0..2xPI, s=0..1, v=0..1
	 */
	public void refresh(Planar<GrayF32> scaledRgbImage, Planar<GrayF32> scaledHsvImage) {
		this.rgbSubimage = scaledRgbImage.subimage(X_OFFSET, Y_OFFSET, X_END, Y_END);
		this.hsvSubimage = scaledHsvImage.subimage(X_OFFSET, Y_OFFSET, X_END, Y_END);

		this.refreshWhiteIndicatorSubimage();
		this.refreshCurrentFrequency();
		this.refreshBlueSpectrumSubimage();
		this.refreshRemainingAmplitues();
		this.refreshDebugSubimage();
	}

	private void refreshWhiteIndicatorSubimage() {
		for (int y = 0; y < hsvSubimage.height; y++) {
			for (int x = 0; x < hsvSubimage.width; x++) {
				//float h = hsvSubimage.bands[0].unsafe_get(x, y);
				float s = hsvSubimage.bands[1].unsafe_get(x, y);
				float v = hsvSubimage.bands[2].unsafe_get(x, y);

				//					whiteIndicatorSubimage.unsafe_set(x, y, 0);
				//					if (v >= 0.4f && v <= 0.5f) {
				//						if (s <= 0.1f) {
				//							whiteIndicatorSubimage.unsafe_set(x, y, Math.min(1.0f, v * 2.0f));
				//						}
				//					}
				if (v >= 0.4f && v <= 0.5f && s <= 0.1f) {
					whiteIndicatorSubimage.unsafe_set(x, y, Math.min(1.0f, v * 2.0f));
				} else {
					whiteIndicatorSubimage.unsafe_set(x, y, 0);
				}
			}
		}
	}

	private void refreshBlueSpectrumSubimage() {
		for (int y = 0; y < hsvSubimage.height; y++) {
			for (int x = 0; x < hsvSubimage.width; x++) {
				float h = hsvSubimage.bands[0].unsafe_get(x, y);
				float s = hsvSubimage.bands[1].unsafe_get(x, y);
				float v = hsvSubimage.bands[2].unsafe_get(x, y);

				//				blueSpectrumSubimage.unsafe_set(x, y, 0);
				//				if (v >= 0.35f && v <= 0.55f) {
				//					if (s >= 0.55f) {
				//						if (h >= 3.50f && h <= 3.752f) {
				//							blueSpectrumSubimage.unsafe_set(x, y, Math.min(1.0f, v * 2.0f));
				//						}
				//					}
				//				}
				if (v >= 0.35f && v <= 0.55f && s >= 0.55f && h >= 3.50f && h <= 3.752f) {
					blueSpectrumSubimage.unsafe_set(x, y, Math.min(1.0f, v * 2.0f));
				} else {
					blueSpectrumSubimage.unsafe_set(x, y, 0);
				}
			}
		}
	}

	private void refreshCurrentFrequency() {
		// Collect brightness by X
		final float MINIMUM_BRIGHTNESS = HEIGHT / 2.0f;
		LinkedHashMap<Integer, Float> brightnessByX = new LinkedHashMap<Integer, Float>();
		for (int x = 0; x < this.whiteIndicatorSubimage.width; x++) {
			float brightness = 0.0f;
			for (int y = 0; y < this.whiteIndicatorSubimage.height; y++) {
				brightness += this.whiteIndicatorSubimage.unsafe_get(x, y);
			}
			if (brightness >= MINIMUM_BRIGHTNESS) {
				brightnessByX.put(x, brightness);
			}
		}
		if (logger.isTraceEnabled()) {
			for (Integer x : brightnessByX.keySet()) {
				logger.trace(String.format(Locale.US, "%4d: %12.2f", x, brightnessByX.get(x)));
			}
			logger.trace("Found " + brightnessByX.size() + " candidate(s) for an amplitude");
		}

		// Use the strongest
		this.currentFrequency = brightnessByX.isEmpty() ? null : this.xInSubimageToPosition(brightnessByX.keySet().iterator().next());
	}

	private void refreshRemainingAmplitues() {
		// Collect brightness by X
		final float MINIMUM_BRIGHTNESS = 7.0f;
		LinkedHashMap<Integer, Float> brightnessByX = new LinkedHashMap<Integer, Float>();
		for (int x = 0; x < this.blueSpectrumSubimage.width; x++) {
			float brightness = 0.0f;
			for (int y = 0; y < this.blueSpectrumSubimage.height; y++) {
				brightness += this.blueSpectrumSubimage.unsafe_get(x, y);
			}
			if (brightness >= MINIMUM_BRIGHTNESS) {
				brightnessByX.put(x, brightness);
			}
		}
		if (logger.isTraceEnabled()) {
			for (Integer x : brightnessByX.keySet()) {
				logger.trace(String.format(Locale.US, "%4d: %12.2f", x, brightnessByX.get(x)));
			}
			logger.trace("Found " + brightnessByX.size() + " candidate(s) for an amplitude");
		}

		// Start with the strongest, require a minimum gap of 3 pixels
		MiscUtil.sortMapByValueReverse(brightnessByX);
		List<Integer> amplituesInSubimage = new ArrayList<Integer>();
		for (Integer x : brightnessByX.keySet()) {
			if (!isCloseToOtherAmplitude(x, amplituesInSubimage, 3)) {
				amplituesInSubimage.add(x);
			}
		}
		Collections.sort(amplituesInSubimage);
		if (logger.isTraceEnabled()) {
			for (Integer x : amplituesInSubimage) {
				logger.trace("Amplitude at " + x);
			}
			logger.trace("Found " + amplituesInSubimage.size() + " amplitude(s)");
		}

		// Convert to positions
		this.remainingAmplitues = new ArrayList<Float>(amplituesInSubimage.size());
		for (Integer x : amplituesInSubimage) {
			this.remainingAmplitues.add(this.xInSubimageToPosition(x));
		}
	}

	private static boolean isCloseToOtherAmplitude(Integer x, List<Integer> others, final int minGap) {
		for (Integer other : others) {
			if (Math.abs(other - x) <= minGap) {
				return true;
			}
		}
		return false;
	}

	private void refreshDebugSubimage() {
		this.debugSubimage = this.debugBackgroundSubimage.clone();
		for (int y = 0; y < this.blueSpectrumSubimage.height; y++) {
			for (int x = 0; x < this.blueSpectrumSubimage.width; x++) {
				float v = this.blueSpectrumSubimage.unsafe_get(x, y);
				if (v > 0) {
					this.debugSubimage.bands[0].unsafe_set(x, y, v);
					this.debugSubimage.bands[1].unsafe_set(x, y, v);
					this.debugSubimage.bands[2].unsafe_set(x, y, v);
				}
			}
		}
		for (float position : this.remainingAmplitues) {
			int x = this.positionToXInSubimage(position);
			for (int y = 2; y < HEIGHT - 4; y++) {
				this.debugSubimage.bands[0].unsafe_set(x, y, 1);
				this.debugSubimage.bands[1].unsafe_set(x, y, 0);
				this.debugSubimage.bands[2].unsafe_set(x, y, 0);
			}
		}
		if (this.currentFrequency != null) {
			int x = this.positionToXInSubimage(this.currentFrequency);
			for (int y = 2; y < HEIGHT - 4; y++) {
				this.debugSubimage.bands[0].unsafe_set(x, y, 0);
				this.debugSubimage.bands[1].unsafe_set(x, y, 1);
				this.debugSubimage.bands[2].unsafe_set(x, y, 0);
			}
		}
	}

	/**
	 * Subimage of the RGB image given to {@link #refresh(Planar, Planar)} containing only the spectrum bar region.
	 */
	public Planar<GrayF32> getRgbSubimage() {
		return this.rgbSubimage;
	}

	public void writeRgbSubimage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(this.rgbSubimage), null, true), "PNG", pngFile);
	}

	/**
	 * Subimage of the HSV image given to {@link #refresh(Planar, Planar)} containing only the spectrum bar region.
	 */
	public Planar<GrayF32> getHsvSubimage() {
		return this.hsvSubimage;
	}

	public void writeHsvSubimage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(this.hsvSubimage), null, true), "PNG", pngFile);
	}

	/**
	 * Filtered to the white position indicator.
	 */
	public GrayF32 getWhiteIndicatorSubimage() {
		return this.whiteIndicatorSubimage;
	}

	public void writeWhiteIndicatorSubimage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.whiteIndicatorSubimage), null, true), "PNG", pngFile);
	}

	/**
	 * Filtered to the blue line with the amplitudes.
	 */
	public GrayF32 getBlueSpectrumSubimage() {
		return this.blueSpectrumSubimage;
	}

	public void writeBlueSpectrumSubimage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.blueSpectrumSubimage), null, true), "PNG", pngFile);
	}

	/**
	 * Current position of the scanner, ranging from 0.0 to 1.0. <code>null</code> if unknown.
	 */
	public Float getCurrentFrequency() {
		return this.currentFrequency;
	}

	/**
	 * A list of positions with remaining amplitudes, each ranging from 0.0 to 1.0. Empty list if none were found.
	 */
	public List<Float> getRemainingAmplitues() {
		return this.remainingAmplitues;
	}

	public PlanetClass positionToPlanetClass(float position) {
		if (position < 0.366f) {
			return null; // USS, Scenario, Star or Belt
		} else if (position < 0.391f) {
			return PlanetClass.METAL_RICH_BODY;
		} else if (position < 0.426f) {
			return PlanetClass.HIGH_METAL_CONTENT_BODY;
		} else if (position < 0.458f) {
			return PlanetClass.ROCKY_BODY;
		} else if (position < 0.483f) {
			return PlanetClass.ICY_BODY;
		} else if (position < 0.518f) {
			return PlanetClass.ROCKY_ICY_BODY;
		} else if (position < 0.551f) {
			return PlanetClass.EARTHLIKE_BODY;
		} else if (position < 0.583f) {
			return PlanetClass.AMMONIA_WORLD;
		} else if (position < 0.609f) {
			return PlanetClass.WATER_WORLD;
		} else {
			return PlanetClass.SUDARSKY_CLASS_I_GAS_GIANT;
		}
	}

	public int positionToXInScaledImage(Float position) {
		return X_OFFSET + this.positionToXInSubimage(position);
	}

	public int positionToXInSubimage(Float position) {
		if (position == null || position < 0.0f || position > 1.0f) {
			throw new IllegalArgumentException("Invalid spectrum position " + position);
		} else {
			return Math.min(WIDTH - 1, Math.round(position.floatValue() * WIDTH));
		}
	}

	public float xInSubimageToPosition(int x) {
		if (x < 0 || x >= WIDTH) {
			throw new IllegalArgumentException("Invalid subimage x coordinate " + x);
		} else {
			return (float) x / (float) WIDTH;
		}
	}

	public Planar<GrayF32> getDebugSubimage() {
		return debugSubimage;
	}

	public void writeDebugSubimage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(this.debugSubimage), null, true), "PNG", pngFile);
	}

	private void initDebugBackgroundSubimage() {
		for (int x = 0; x < WIDTH; x++) {
			float position = this.xInSubimageToPosition(x);
			PlanetClass planetClass = this.positionToPlanetClass(position);

			int r = 20;
			int g = 20;
			int b = 20;
			if (planetClass != null) {
				switch (planetClass) {
				case METAL_RICH_BODY:
					r = 66;
					g = 0;
					b = 0;
					break;
				case HIGH_METAL_CONTENT_BODY:
					r = 91;
					g = 0;
					b = 91;
					break;
				case ROCKY_BODY:
					r = 100;
					g = 100;
					b = 100;
					break;
				case ICY_BODY:
					r = 28;
					g = 111;
					b = 132;
					break;
				case ROCKY_ICY_BODY:
					r = 113;
					g = 140;
					b = 0;
					break;
				case EARTHLIKE_BODY:
					r = 0;
					g = 83;
					b = 0;
					break;
				case AMMONIA_WORLD:
					r = 62;
					g = 62;
					b = 0;
					break;
				case WATER_WORLD:
					r = 22;
					g = 27;
					b = 84;
					break;
				case SUDARSKY_CLASS_I_GAS_GIANT:
					r = 30;
					g = 18;
					b = 13;
					break;
				default:
					break;
				}
			}

			for (int y = 0; y < HEIGHT; y++) {
				this.debugBackgroundSubimage.bands[0].unsafe_set(x, y, r / 255f);
				this.debugBackgroundSubimage.bands[1].unsafe_set(x, y, g / 255f);
				this.debugBackgroundSubimage.bands[2].unsafe_set(x, y, b / 255f);
			}
		}
	}

}