package borg.ed.cruisecontrol.sysmap;

import java.util.ArrayList;
import java.util.List;

public class SysmapScannerResult {

    private List<SysmapBody> bodies = new ArrayList<>();

    public SysmapScannerResult(List<SysmapBody> bodies) {
        this.setBodies(bodies);
    }

    public List<SysmapBody> getBodies() {
        return bodies;
    }

    public void setBodies(List<SysmapBody> bodies) {
        this.bodies = bodies;
    }

}
