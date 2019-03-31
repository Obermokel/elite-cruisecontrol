package borg.ed.cruisecontrol.fss;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.abst.distort.FDistort;
import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.alg.interpolate.InterpolationType;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.templatematching.Template;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;
import borg.ed.cruisecontrol.util.ImageUtil;

/**
 * <p>Scans the entire screen for glowing bubbles, allowing to give direction hints.</p>
 * <p>Also scans for the reticules, allowing for even more precise navigation hints.</p>
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
	private GrayF32 miniBubbleImage = new GrayF32(192, 108);
	private GrayF32 workBlur = new GrayF32(192, 108);
	private GrayF32 miniBlurredImage = new GrayF32(192, 108);
	private Template tBlurredMiniBubble = null;
	private List<TemplateMatch> bubbleMatches = new ArrayList<>();
	private BufferedImage debugImage = null;

	public FssBodyLocator() {
		try {
			this.tBlurredMiniBubble = Template.fromFile(new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref/blurredMiniBubble.png"));
		} catch (IOException e) {
			throw new RuntimeException("Failed to initialize " + this, e);
		}
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
		this.scaledRgbImage = scaledRgbImage;
		this.scaledHsvImage = scaledHsvImage;

		this.refreshBlueBubbleImage();
		this.refreshMiniBubbleImage();
		this.refreshMiniBlurredImage();
		this.refreshBodyLocations();
		this.refreshDebugImage();
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

	private void refreshMiniBubbleImage() {
		new FDistort().input(this.blueBubbleImage).output(this.miniBubbleImage).interp(InterpolationType.BICUBIC).scaleExt().apply();
	}

	private void refreshMiniBlurredImage() {
		GBlurImageOps.gaussian(this.miniBubbleImage, this.miniBlurredImage, -1, 3, this.workBlur);
	}

	private void refreshBodyLocations() {
		List<TemplateMatch> allMatches = TemplateMatcher.findAllMatchingLocations(this.miniBlurredImage, this.tBlurredMiniBubble, 0.1f);
		allMatches = allMatches.stream().sorted((m1, m2) -> new Float(m1.getErrorPerPixel()).compareTo(new Float(m2.getErrorPerPixel()))).collect(Collectors.toList());
		//logger.debug("allMatches = " + allMatches.size());
		this.bubbleMatches = new ArrayList<>();
		List<Rectangle> rects = new ArrayList<>();
		for (TemplateMatch m : allMatches) {
			Rectangle r = new Rectangle(m.getX(), m.getY(), m.getWidth(), m.getHeight());
			if (!intersectsWithAny(r, rects)) {
				rects.add(r);
				this.bubbleMatches.add(m);
				//logger.debug(String.format(Locale.US, "%s = %.6f", m.getTemplate().getName(), m.getErrorPerPixel()));
			}
		}
	}

	private void refreshDebugImage() {
		this.debugImage = ImageUtil.scaleTo(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.miniBlurredImage), null, true), 1920, 1080);

		Graphics2D g = this.debugImage.createGraphics();
		g.setColor(Color.RED);
		g.setFont(new Font("Sans Serif", Font.BOLD, 24));
		for (TemplateMatch m : this.bubbleMatches) {
			g.drawRect(m.getX() * 10, m.getY() * 10, m.getWidth() * 10, m.getHeight() * 10);
			g.drawString(String.format(Locale.US, "%.6f", m.getErrorPerPixel()), m.getX() * 10, m.getY() * 10 - 2);
		}
		g.dispose();
	}

	public GrayF32 getBlueBubbleImage() {
		return this.blueBubbleImage;
	}

	public void writeBlueBubbleImage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.blueBubbleImage), null, true), "PNG", pngFile);
	}

	public GrayF32 getMiniBubbleImage() {
		return this.miniBubbleImage;
	}

	public void writeMiniBubbleImage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.miniBubbleImage), null, true), "PNG", pngFile);
	}

	public GrayF32 getMiniBlurredImage() {
		return this.miniBlurredImage;
	}

	public void writeMiniBlurredImage(File pngFile) throws IOException {
		ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.miniBlurredImage), null, true), "PNG", pngFile);
	}

	public List<TemplateMatch> getBubbleMatches() {
		return this.bubbleMatches;
	}

	public BufferedImage getDebugImage() {
		return this.debugImage;
	}

	public void writeDebugImage(File pngFile) throws IOException {
		ImageIO.write(this.debugImage, "PNG", pngFile);
	}

	private static boolean intersectsWithAny(Rectangle rect, List<Rectangle> rects) {
		for (Rectangle other : rects) {
			if (rect.intersects(other)) {
				return true;
			}
		}
		return false;
	}

}