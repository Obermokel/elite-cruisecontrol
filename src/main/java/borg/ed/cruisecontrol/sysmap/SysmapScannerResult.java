package borg.ed.cruisecontrol.sysmap;

import java.util.ArrayList;
import java.util.List;

import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;

public class SysmapScannerResult {

	private Planar<GrayF32> rgb = null;
	private Planar<GrayF32> hsv = null;
	private List<SysmapBody> bodies = new ArrayList<>();

	public SysmapScannerResult(Planar<GrayF32> rgb, Planar<GrayF32> hsv, List<SysmapBody> bodies) {
		this.setRgb(rgb);
		this.setHsv(hsv);
		this.setBodies(bodies);
	}

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

	public List<SysmapBody> getBodies() {
		return bodies;
	}

	public void setBodies(List<SysmapBody> bodies) {
		this.bodies = bodies;
	}

}
