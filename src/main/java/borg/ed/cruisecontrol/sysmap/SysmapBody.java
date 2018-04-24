package borg.ed.cruisecontrol.sysmap;

import java.awt.Point;
import java.awt.Rectangle;
import java.math.BigDecimal;
import java.util.List;

import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.templatematching.TemplateMatchRgb;

public class SysmapBody {

    public Rectangle areaInImage = null;
    public Point centerOnScreen = null;
    public boolean unexplored = false;
    public TemplateMatchRgb bestBodyMatch = null;
    public BigDecimal distanceLs = null;
    public BigDecimal earthMasses = null;
    public BigDecimal radiusKm = null;
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

    public boolean hasDifferentData(SysmapBody other) {
        return !this.equals(other);
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
        sb.append("; ").append("earthMasses=").append(this.earthMasses);
        sb.append("; ").append("radiusKm=").append(this.radiusKm);
        sb.append("]");
        return sb.toString();
    }

}
