package borg.ed.cruisecontrol.util;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;

import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.Planar;

public abstract class ImageUtil {

	public static BufferedImage toFullHd(BufferedImage original) {
		return scaleAndCrop(original, 1920, 1080);
	}

	public static BufferedImage toFourK(BufferedImage original) {
		return scaleAndCrop(original, 3840, 2160);
	}

	public static BufferedImage scaleAndCrop(BufferedImage original, int targetWidth, int targetHeight) {
		if (original.getWidth() == targetWidth && original.getHeight() == targetHeight) {
			// Perfect!
			return original;
		} else {
			// Scale to the target height
			float scale = original.getHeight() / (float) targetHeight;
			int newHeight = Math.round(original.getHeight() / scale);
			int newWidth = Math.round(original.getWidth() / scale);

			if (newWidth >= targetWidth) {
				// Greater or equal to the target width is okay.
				// If equal we can leave the width as it is, if greater we can crop to the target width.
			} else {
				// If the new width is less than the target width we need to scale by width instead.
				// The new height will then be greater than the target height, so we can crop to the target height.
				scale = original.getWidth() / (float) targetWidth;
				newWidth = Math.round(original.getWidth() / scale);
				newHeight = Math.round(original.getHeight() / scale);
			}

			// Scale!
			BufferedImage scaled = scaleTo(original, newWidth, newHeight);

			// Maybe return a cropped subimage
			return cropTo(scaled, targetWidth, targetHeight);
		}
	}

	public static BufferedImage scaleTo(BufferedImage original, int targetWidth, int targetHeight) {
		if (original.getWidth() == targetWidth && original.getHeight() == targetHeight) {
			return original;
		} else {
			int resultType = original.getTransparency() == Transparency.OPAQUE ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
			BufferedImage resultImage = new BufferedImage(targetWidth, targetHeight, resultType);
			Image scaledImage = original.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
			Graphics2D resultGraphics = (Graphics2D) resultImage.getGraphics();
			resultGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			resultGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			resultGraphics.drawImage(scaledImage, 0, 0, null);
			return resultImage;
		}
	}

	public static BufferedImage cropTo(BufferedImage original, int targetWidth, int targetHeight) {
		if (original.getWidth() > targetWidth && original.getHeight() > targetHeight) {
			int x = (original.getWidth() - targetWidth) / 2;
			int y = (original.getHeight() - targetHeight) / 2;
			return original.getSubimage(x, y, targetWidth, targetHeight);
		} else if (original.getWidth() > targetWidth) {
			int x = (original.getWidth() - targetWidth) / 2;
			return original.getSubimage(x, 0, targetWidth, targetHeight);
		} else if (original.getHeight() > targetHeight) {
			int y = (original.getHeight() - targetHeight) / 2;
			return original.getSubimage(0, y, targetWidth, targetHeight);
		} else {
			return original;
		}
	}

	/**
	 * Hard division by 255. Multiple invocations will again divide by 255.
	 */
	public static GrayF32 normalize255(GrayF32 original) {
		GrayF32 normalized = original.createSameShape();
		for (int y = 0; y < original.height; y++) {
			for (int x = 0; x < original.width; x++) {
				normalized.unsafe_set(x, y, original.unsafe_get(x, y) / 255f);
			}
		}
		return normalized;
	}

	/**
	 * Hard division by 255. Multiple invocations will again divide by 255.
	 */
	public static Planar<GrayF32> normalize255(Planar<GrayF32> original) {
		Planar<GrayF32> normalized = original.createSameShape();
		for (int band = 0; band < original.getNumBands(); band++) {
			GrayF32 originalBand = original.getBand(band);
			GrayF32 normalizedBand = ImageUtil.normalize255(originalBand);
			normalized.setBand(band, normalizedBand);
		}
		return normalized;
	}

	public static GrayU8 denormalize255(GrayU8 original) {
		GrayU8 denormalized = original.createSameShape();
		for (int y = 0; y < original.height; y++) {
			for (int x = 0; x < original.width; x++) {
				denormalized.unsafe_set(x, y, original.unsafe_get(x, y) * 255);
			}
		}
		return denormalized;
	}

	public static GrayF32 denormalize255(GrayF32 original) {
		GrayF32 denormalized = original.createSameShape();
		for (int y = 0; y < original.height; y++) {
			for (int x = 0; x < original.width; x++) {
				denormalized.unsafe_set(x, y, original.unsafe_get(x, y) * 255f);
			}
		}
		return denormalized;
	}

	public static Planar<GrayF32> denormalize255(Planar<GrayF32> original) {
		Planar<GrayF32> denormalized = original.createSameShape();
		for (int band = 0; band < original.getNumBands(); band++) {
			GrayF32 originalBand = original.getBand(band);
			GrayF32 denormalizedBand = ImageUtil.denormalize255(originalBand);
			denormalized.setBand(band, denormalizedBand);
		}
		return denormalized;
	}

	/**
	 * Normalize to a range from 0.0 to 1.0. Can be invoked multiple times.
	 */
	public static GrayF32 normalize(GrayF32 original) {
		float max = 0f;
		for (int y = 0; y < original.height; y++) {
			for (int x = 0; x < original.width; x++) {
				max = Math.max(max, original.unsafe_get(x, y));
			}
		}
		if (max == 1f) {
			return original;
		} else {
			GrayF32 normalized = original.createSameShape();
			for (int y = 0; y < original.height; y++) {
				for (int x = 0; x < original.width; x++) {
					normalized.unsafe_set(x, y, original.unsafe_get(x, y) / max);
				}
			}
			return normalized;
		}
	}

	/**
	 * Normalize to a range from 0.0 to 1.0. Can be invoked multiple times.
	 */
	public static Planar<GrayF32> normalize(Planar<GrayF32> original) {
		float max = 0f;
		for (int band = 0; band < original.getNumBands(); band++) {
			GrayF32 originalBand = original.getBand(band);
			for (int y = 0; y < original.height; y++) {
				for (int x = 0; x < original.width; x++) {
					max = Math.max(max, originalBand.unsafe_get(x, y));
				}
			}
		}
		if (max == 1f) {
			return original;
		} else {
			Planar<GrayF32> normalized = original.createSameShape();
			for (int band = 0; band < original.getNumBands(); band++) {
				GrayF32 originalBand = original.getBand(band);
				GrayF32 normalizedBand = normalized.getBand(band);
				for (int y = 0; y < original.height; y++) {
					for (int x = 0; x < original.width; x++) {
						normalizedBand.unsafe_set(x, y, originalBand.unsafe_get(x, y) / max);
					}
				}
			}
			return normalized;
		}
	}

}
