package borg.ed.cruisecontrol.sysmap;

import java.awt.Point;
import java.awt.Rectangle;
import java.math.BigDecimal;
import java.util.List;

import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.templatematching.TemplateMatchRgb;
import borg.ed.universe.constants.PlanetClass;
import borg.ed.universe.constants.StarClass;
import borg.ed.universe.util.BodyUtil;

public class SysmapBody {

	public Rectangle areaInImage = null;
	public Point centerOnScreen = null;
	public boolean unexplored = false;
	public TemplateMatchRgb bestBodyMatch = null;
	public BigDecimal distanceLs = null;
	public BigDecimal solarMasses = null;
	public BigDecimal moonMasses = null;
	public BigDecimal earthMasses = null;
	public BigDecimal radiusKm = null;
	public BigDecimal semiMajorAxisAu = null;
	public Planar<GrayF32> rgbDebugImage = null;
	public GrayF32 grayDebugImage = null;

	public SysmapBody(Rectangle areaInImage) {
		this.areaInImage = areaInImage;
	}

	public static boolean intersectsWithAny(SysmapBody b, List<SysmapBody> others) {
		for (SysmapBody other : others) {
			if (b.areaInImage.intersects(other.areaInImage)) {
				return true;
			}
		}
		return false;
	}

	public static String getAbbreviatedType(SysmapBody b) {
		return BodyUtil.getAbbreviatedType(b.getStarClass(), b.getPlanetClass(), b.isTerraformingCandidate());
	}

	public static long estimatePayout(SysmapBody b) {
		return BodyUtil.estimatePayout(b.getStarClass(), b.getPlanetClass(), b.isTerraformingCandidate());
	}

	public StarClass getStarClass() {
		if (this.bestBodyMatch != null) {
			try {
				return StarClass.fromJournalValue(this.bestBodyMatch.getTemplate().getName());
			} catch (IllegalArgumentException e) {
				// Unknown
			}
		}

		return null;
	}

	public PlanetClass getPlanetClass() {
		if (this.bestBodyMatch != null) {
			try {
				return PlanetClass.fromJournalValue(this.bestBodyMatch.getTemplate().getName());
			} catch (IllegalArgumentException e) {
				// Unknown
			}
		}

		return null;
	}

	public boolean isTerraformingCandidate() {
		if (this.bestBodyMatch != null && this.distanceLs != null && this.earthMasses != null && this.radiusKm != null) {
			//        		Statistics of 44470 WWs:
			//        		distanceToArrivalLs:    min=9.00 / 1%=55.00 / 5%=107.00 / 10%=181.00 / med=811.00 / 90%=9634.00 / 95%=37270.00 / 99%=252147.00 / max=628531.00
			//        		semiMajorAxisAu:        min=0.01 / 1%=0.02 / 5%=0.12 / 10%=0.21 / med=1.04 / 90%=3.18 / 95%=3.80 / 99%=5.27 / max=10.93
			//        		earthMasses:            min=0.0827 / 1%=0.1238 / 5%=0.1443 / 10%=0.1681 / med=0.5212 / 90%=1.7431 / 95%=2.1990 / 99%=2.9647 / max=8.3074
			//        		radiusKm:               min=2833 / 1%=3190 / 5%=3349 / 10%=3516 / med=5038 / 90%=7250 / 95%=7747 / 99%=8553 / max=13445
			//        		gravityG:               min=0.40 / 1%=0.47 / 5%=0.52 / 10%=0.55 / med=0.83 / 90%=1.37 / 95%=1.52 / 99%=1.73 / max=2.00
			//
			//        		Statistics of 111336 HMCs:
			//        		distanceToArrivalLs:    min=6.00 / 1%=45.00 / 5%=94.00 / 10%=171.00 / med=717.00 / 90%=7655.00 / 95%=30478.00 / 99%=236797.00 / max=705268.00
			//        		semiMajorAxisAu:        min=0.01 / 1%=0.04 / 5%=0.12 / 10%=0.20 / med=1.01 / 90%=2.69 / 95%=3.26 / 99%=4.91 / max=10.99
			//        		earthMasses:            min=0.0664 / 1%=0.0720 / 5%=0.0871 / 10%=0.1087 / med=0.4923 / 90%=2.0406 / 95%=2.6832 / 99%=3.6599 / max=6.4841
			//        		radiusKm:               min=2597 / 1%=2679 / 5%=2848 / 10%=3056 / med=4906 / 90%=7494 / 95%=8098 / 99%=8829 / max=12172
			//        		gravityG:               min=0.40 / 1%=0.41 / 5%=0.44 / 10%=0.47 / med=0.83 / 90%=1.48 / 95%=1.66 / 99%=1.91 / max=2.00
			//
			//        		Statistics of 10059 ELWs:
			//        		distanceToArrivalLs:    min=16.74 / 1%=58.97 / 5%=133.00 / 10%=231.59 / med=969.33 / 90%=15044.00 / 95%=55175.00 / 99%=324168.00 / max=660486.00
			//        		semiMajorAxisAu:        min=0.01 / 1%=0.03 / 5%=0.12 / 10%=0.25 / med=1.13 / 90%=3.55 / 95%=4.25 / 99%=5.82 / max=10.93
			//        		earthMasses:            min=0.0713 / 1%=0.2453 / 5%=0.2748 / 10%=0.3020 / med=0.7004 / 90%=1.8148 / 95%=2.1458 / 99%=2.5796 / max=7.1000
			//        		radiusKm:               min=2673 / 1%=3954 / 5%=4098 / 10%=4220 / med=5481 / 90%=7246 / 95%=7603 / 99%=8022 / max=10631
			//        		gravityG:               min=0.41 / 1%=0.63 / 5%=0.66 / 10%=0.69 / med=0.95 / 90%=1.40 / 95%=1.51 / 99%=1.63 / max=2.55

			if (this.distanceLs.intValue() >= 50 && this.earthMasses.floatValue() >= 0.05f && this.radiusKm.intValue() >= 2600) { // TODO Semi major axis <= 4.00 AU
				BigDecimal gravityG = BodyUtil.calculateGravityG(this.earthMasses, this.radiusKm);
				if (gravityG.floatValue() >= 0.4f && gravityG.floatValue() <= 2.0f) {
					PlanetClass planetClass = this.getPlanetClass();
					if (planetClass == PlanetClass.WATER_WORLD || planetClass == PlanetClass.HIGH_METAL_CONTENT_BODY) {
						return true;
					}
				}
			}
		}

		return false;
	}

	@Override
	public boolean equals(Object that) {
		if (this == that)
			return true;
		if (that == null)
			return false;
		if (getClass() != that.getClass())
			return false;
		SysmapBody other = (SysmapBody) that;
		return this.toString().equals(other.toString());
	}

	@Override
	public int hashCode() {
		return this.toString().hashCode();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("SysmapBody[");
		sb.append("area=").append(this.areaInImage.x).append(",").append(this.areaInImage.y).append(" ").append(this.areaInImage.width).append("x").append(this.areaInImage.height);
		sb.append("; ").append("unexplored=").append(this.unexplored);
		sb.append("; ").append("distanceLs=").append(this.distanceLs);
		sb.append("; ").append("solarMasses=").append(this.solarMasses);
		sb.append("; ").append("moonMasses=").append(this.moonMasses);
		sb.append("; ").append("earthMasses=").append(this.earthMasses);
		sb.append("; ").append("radiusKm=").append(this.radiusKm);
		sb.append("; ").append("semiMajorAxisAu=").append(this.semiMajorAxisAu);
		sb.append("]");
		return sb.toString();
	}

	public String toDataString() {
		StringBuilder sb = new StringBuilder("SysmapBody[");
		sb.append("; ").append("distanceLs=").append(this.distanceLs);
		sb.append("; ").append("solarMasses=").append(this.solarMasses);
		sb.append("; ").append("moonMasses=").append(this.moonMasses);
		sb.append("; ").append("earthMasses=").append(this.earthMasses);
		sb.append("; ").append("radiusKm=").append(this.radiusKm);
		sb.append("; ").append("semiMajorAxisAu=").append(this.semiMajorAxisAu);
		sb.append("]");
		return sb.toString();
	}

	public boolean hasSameData(SysmapBody other) {
		return this.toDataString().equals(other.toDataString());
	}

	public void clearData() {
		this.distanceLs = null;
		this.solarMasses = null;
		this.moonMasses = null;
		this.earthMasses = null;
		this.radiusKm = null;
		this.semiMajorAxisAu = null;
	}

}
