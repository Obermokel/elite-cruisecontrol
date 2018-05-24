package borg.ed.cruisecontrol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class CruiseSettings {

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private boolean jonkMode = false;
	private boolean creditsMode = false;
	private List<String> waypoints = new ArrayList<>();

	public static CruiseSettings load() throws IOException {
		File settingsFile = new File(System.getProperty("user.home"), "cruise-settings.json");
		if (!CruiseControlApplication.myCommanderName.toLowerCase().contains("unknown")) {
			settingsFile = new File(System.getProperty("user.home"), "cruise-settings " + CruiseControlApplication.myCommanderName + ".json");
		}
		if (settingsFile.exists()) {
			try (InputStreamReader reader = new InputStreamReader(new BufferedInputStream(new FileInputStream(settingsFile)), "UTF-8")) {
				return gson.fromJson(reader, CruiseSettings.class);
			}
		}
		return null;
	}

	public static void save(CruiseSettings settings) throws IOException {
		File settingsFile = new File(System.getProperty("user.home"), "cruise-settings.json");
		if (!CruiseControlApplication.myCommanderName.toLowerCase().contains("unknown")) {
			settingsFile = new File(System.getProperty("user.home"), "cruise-settings " + CruiseControlApplication.myCommanderName + ".json");
		}
		try (OutputStreamWriter writer = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(settingsFile)), "UTF-8")) {
			gson.toJson(settings, writer);
		}
	}

	public boolean isJonkMode() {
		return jonkMode;
	}

	public void setJonkMode(boolean jonkMode) {
		this.jonkMode = jonkMode;
	}

	public boolean isCreditsMode() {
		return creditsMode;
	}

	public void setCreditsMode(boolean creditsMode) {
		this.creditsMode = creditsMode;
	}

	public List<String> getWaypoints() {
		return waypoints;
	}

	public void setWaypoints(List<String> waypoints) {
		this.waypoints = waypoints;
	}

}
