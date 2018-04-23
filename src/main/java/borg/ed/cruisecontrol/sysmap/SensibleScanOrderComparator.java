package borg.ed.cruisecontrol.sysmap;

import java.util.Comparator;

public class SensibleScanOrderComparator implements Comparator<SysmapBody> {

    @Override
    public int compare(SysmapBody b1, SysmapBody b2) {
        if (b1.distanceLs != null && b2.distanceLs == null) {
            return -1;
        } else if (b1.distanceLs == null && b2.distanceLs != null) {
            return 1;
        } else {
            float distanceBetweenBodiesInLs = 0f;
            if (b1.distanceLs != null && b2.distanceLs != null) {
                distanceBetweenBodiesInLs = b1.distanceLs.subtract(b2.distanceLs).abs().floatValue();
            }

            if (distanceBetweenBodiesInLs > 20) {
                return b1.distanceLs.compareTo(b2.distanceLs);
            } else {
                if (b1.earthMasses != null && b2.earthMasses == null) {
                    return -1;
                } else if (b1.earthMasses == null && b2.earthMasses != null) {
                    return 1;
                } else if (b1.earthMasses != null && b2.earthMasses != null) {
                    return -1 * b1.earthMasses.compareTo(b2.earthMasses);
                }
            }
        }

        return 0;
    }

}
