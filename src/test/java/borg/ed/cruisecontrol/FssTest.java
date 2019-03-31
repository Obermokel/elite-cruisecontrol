package borg.ed.cruisecontrol;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.alg.color.ColorHsv;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.fss.FssBodyLocator;
import borg.ed.cruisecontrol.fss.FssSpectrumBar;
import borg.ed.cruisecontrol.util.ImageUtil;

public class FssTest {

	static final Logger logger = LoggerFactory.getLogger(FssTest.class);

	public static void main(String[] args) throws IOException {
		File testDir = new File(System.getProperty("user.home"), "Pictures\\FSS");

		testFile(new File(testDir, "spectrum and glow.png"));
	}

	static void testFile(File testFile) throws IOException {
		BufferedImage orig = ImageIO.read(testFile);
		BufferedImage scaledScreenCapture = ImageUtil.scaleAndCrop(orig, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);
		ImageIO.write(scaledScreenCapture, "PNG", new File(testFile.getParentFile(), testFile.getName().replace(".png", "_scaled.png")));
		//GrayF32 gray = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(scaledScreenCapture, (GrayF32) null));
		//ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(gray), null), "PNG", new File(testFile.getParentFile(), testFile.getName().replace(".png", "_gray.png")));

		// RGB and HSV
		Planar<GrayF32> rgb = new Planar<>(GrayF32.class, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, 3);
		Planar<GrayF32> hsv = rgb.createSameShape();
		ConvertBufferedImage.convertFromMulti(scaledScreenCapture, rgb, true, GrayF32.class);
		rgb = ImageUtil.normalize255(rgb);
		ColorHsv.rgbToHsv_F32(rgb, hsv);

		// FSS spectrum bar
		FssSpectrumBar fssSpectrumBar = new FssSpectrumBar();
		fssSpectrumBar.refresh(rgb, hsv);
		//fssSpectrumBar.writeWhiteIndicatorSubimage(new File(testFile.getParentFile(), testFile.getName().replace(".png", "_whiteIndicator.png")));
		//fssSpectrumBar.writeBlueSpectrumSubimage(new File(testFile.getParentFile(), testFile.getName().replace(".png", "_blueSpectrum.png")));
		fssSpectrumBar.writeDebugSubimage(new File(testFile.getParentFile(), testFile.getName().replace(".png", "_debug.png")));

		// FSS bodies
		FssBodyLocator fssBodyLocator = new FssBodyLocator();
		fssBodyLocator.refresh(rgb, hsv);
		fssBodyLocator.writeBlueBubbleImage(new File(testFile.getParentFile(), testFile.getName().replace(".png", "_blueBubbles.png")));
		fssBodyLocator.writeMiniBubbleImage(new File(testFile.getParentFile(), testFile.getName().replace(".png", "_miniBubbles.png")));
		fssBodyLocator.writeMiniBlurredImage(new File(testFile.getParentFile(), testFile.getName().replace(".png", "_miniBlurred.png")));
		fssBodyLocator.writeDebugImage(new File(testFile.getParentFile(), testFile.getName().replace(".png", "_debugLocations.png")));
	}

	public static class FssBodyLocation {

		public float x = 0;
		public float y = 0;
		public long lastSeen = 0;

	}

}
