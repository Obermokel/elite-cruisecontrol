package borg.ed.cruisecontrol.sysmap;

import java.util.LinkedHashMap;

import borg.ed.cruisecontrol.ScreenCoord;
import borg.ed.cruisecontrol.templatematching.TemplateMatchRgb;

public class SysmapScannerResult {

	private LinkedHashMap<TemplateMatchRgb, ScreenCoord> systemMapScreenCoords = new LinkedHashMap<>();

	public SysmapScannerResult(LinkedHashMap<TemplateMatchRgb, ScreenCoord> systemMapScreenCoords) {
		this.setSystemMapScreenCoords(systemMapScreenCoords);
	}

	public LinkedHashMap<TemplateMatchRgb, ScreenCoord> getSystemMapScreenCoords() {
		return systemMapScreenCoords;
	}

	public void setSystemMapScreenCoords(LinkedHashMap<TemplateMatchRgb, ScreenCoord> systemMapScreenCoords) {
		this.systemMapScreenCoords = systemMapScreenCoords;
	}

}
