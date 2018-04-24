package borg.ed.cruisecontrol.sysmap;

import java.awt.Point;
import java.util.Comparator;

public class SensibleScanOrderComparator implements Comparator<SysmapBody> {

    @Override
    public int compare(SysmapBody b1, SysmapBody b2) {
        // Prefer the ones having a known distance
        if (b1.distanceLs != null && b2.distanceLs == null) {
            return -1;
        } else if (b1.distanceLs == null && b2.distanceLs != null) {
            return 1;
        } else {
            // Are the two close to each other?
            float distanceBetweenBodiesInLs = 0f;
            if (b1.distanceLs != null && b2.distanceLs != null) {
                distanceBetweenBodiesInLs = b1.distanceLs.subtract(b2.distanceLs).abs().floatValue();
            }

            // If not prefer the one which is closer to the entry star
            if (distanceBetweenBodiesInLs > 20) {
                return b1.distanceLs.compareTo(b2.distanceLs);
            } else {
                // When close to each other prefer the heavier one
                if (b1.earthMasses != null && b2.earthMasses == null) {
                    return -1;
                } else if (b1.earthMasses == null && b2.earthMasses != null) {
                    return 1;
                } else if (b1.earthMasses != null && b2.earthMasses != null) {
                    return -1 * b1.earthMasses.compareTo(b2.earthMasses);
                }
            }
        }

        // Prefer closer to top-left screen corner as a last resort
        Point l0 = new Point(0, 0);
        Point l1 = b1.areaInImage.getLocation();
        Point l2 = b2.areaInImage.getLocation();
        return new Double(l1.distance(l0)).compareTo(new Double(l2.distance(l0)));
    }

}
