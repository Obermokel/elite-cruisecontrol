package borg.ed.cruisecontrol;

import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;

public class ScreenConverterResult {

	private Planar<GrayF32> rgb = null;
	private Planar<GrayF32> hsv = null;
	private GrayF32 orangeHudImage = null;
	private GrayF32 blueWhiteHudImage = null;
	private GrayF32 redHudImage = null;
	private GrayF32 brightImage = null;

	public Planar<GrayF32> getRgb() {
		return rgb;
	}

	public void setRgb(Planar<GrayF32> rgb) {
		this.rgb = rgb;
	}

	public Planar<GrayF32> getHsv() {
		return hsv;
	}

	public void setHsv(Planar<GrayF32> hsv) {
		this.hsv = hsv;
	}

	public GrayF32 getOrangeHudImage() {
		return orangeHudImage;
	}

	public void setOrangeHudImage(GrayF32 orangeHudImage) {
		this.orangeHudImage = orangeHudImage;
	}

	public GrayF32 getBlueWhiteHudImage() {
		return blueWhiteHudImage;
	}

	public void setBlueWhiteHudImage(GrayF32 blueWhiteHudImage) {
		this.blueWhiteHudImage = blueWhiteHudImage;
	}

	public GrayF32 getRedHudImage() {
		return redHudImage;
	}

	public void setRedHudImage(GrayF32 redHudImage) {
		this.redHudImage = redHudImage;
	}

	public GrayF32 getBrightImage() {
		return brightImage;
	}

	public void setBrightImage(GrayF32 brightImage) {
		this.brightImage = brightImage;
	}

}
