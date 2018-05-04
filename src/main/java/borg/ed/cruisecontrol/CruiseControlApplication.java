package borg.ed.cruisecontrol;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import borg.ed.cruisecontrol.gui.CruiseControlFrame;
import borg.ed.cruisecontrol.sysmap.SysmapScanner;
import borg.ed.universe.UniverseApplication;
import borg.ed.universe.journal.JournalEventReader;
import borg.ed.universe.journal.JournalReaderThread;
import borg.ed.universe.journal.StatusReaderThread;
import borg.ed.universe.journal.events.AbstractJournalEvent;
import borg.ed.universe.journal.events.DiedEvent;
import borg.ed.universe.journal.events.FSDJumpEvent;
import borg.ed.universe.journal.events.LoadGameEvent;
import borg.ed.universe.journal.events.ScanEvent;
import borg.ed.universe.journal.events.SellExplorationDataEvent;
import borg.ed.universe.util.BodyUtil;

@Configuration
@Import(UniverseApplication.class)
public class CruiseControlApplication {

	static final Logger logger = LoggerFactory.getLogger(CruiseControlApplication.class);

	private static final ApplicationContext APPCTX = new AnnotationConfigApplicationContext(CruiseControlApplication.class);

	public static final long APPLICATION_START = System.currentTimeMillis();
	public static int jumpsSession = 0;
	public static int jumpsTotal = 0;
	public static float lightyearsSession = 0;
	public static float lightyearsTotal = 0;
	public static long explorationPayoutSession = 0;
	public static long explorationPayoutTotal = 0;
	public static long playtimeMillisSession = 0;
	public static long playtimeMillisLastDock = 0;

	public static String myShip = "Unknown Ship";
	public static String myCommanderName = "Unknown Commander Name";
	public static Set<String> myVisitedSystems = new HashSet<>();
	public static float maxFuel = 0f;
	public static List<String> myWingMates = new ArrayList<>();

	public static final int SCALED_WIDTH = 1920;
	public static final int SCALED_HEIGHT = 1080;
	public static final boolean SHOW_LIVE_DEBUG_IMAGE = true;
	public static final boolean WRITE_SYSMAP_DEBUG_RGB_ORIGINAL = true;
	public static final boolean WRITE_SYSMAP_DEBUG_GRAY = false;
	public static final boolean WRITE_SYSMAP_DEBUG_THRESHOLD = false;
	public static final boolean WRITE_SYSMAP_DEBUG_RGB_RESULT = false;
	public static final boolean WRITE_SYSMAP_DEBUG_BODY_RGB_ORIGINAL = true;
	public static final boolean WRITE_SYSMAP_DEBUG_BODY_GRAY = true;
	public static final boolean WRITE_SYSMAP_DEBUG_BODY_RGB_RESULT = true;

	public static void main(String[] args) throws AWTException, IOException {
		try {
			UIManager.setLookAndFeel("com.jtattoo.plaf.noire.NoireLookAndFeel");
		} catch (Exception e) {
			// Ignore
		}

		// Know who we are
		File[] sortedJournalFiles = getSortedJournalFiles();
		setCommanderData(sortedJournalFiles[sortedJournalFiles.length - 1]);

		// Compute totals for statistics display (may take a while with hundreds of journal files, so do it in a thread)
		new Thread(new Runnable() {
			@Override
			public void run() {
				computeTotals(sortedJournalFiles);
			}
		}).start();

		// Start all event-generating threads
		JournalReaderThread journalReaderThread = APPCTX.getBean(JournalReaderThread.class);
		journalReaderThread.start();
		StatusReaderThread statusReaderThread = APPCTX.getBean(StatusReaderThread.class);
		statusReaderThread.start();
		ScreenReaderThread screenReaderThread = APPCTX.getBean(ScreenReaderThread.class);
		screenReaderThread.start();
		ScreenConverterThread screenConverterThread = APPCTX.getBean(ScreenConverterThread.class);
		screenConverterThread.start();

		// Start the main cruise control thread
		CruiseControlThread cruiseControlThread = APPCTX.getBean(CruiseControlThread.class);
		cruiseControlThread.start();

		// Open the GUI
		CruiseControlFrame cruiseControlFrame = new CruiseControlFrame();
		cruiseControlFrame.setVisible(true);

		// Add listeners
		cruiseControlThread.addDebugImageListener(cruiseControlFrame);

		journalReaderThread.addListener(cruiseControlThread);
		journalReaderThread.addListener(cruiseControlFrame);

		statusReaderThread.addListener(cruiseControlThread);
		statusReaderThread.addListener(cruiseControlFrame);
	}

	private static File[] getSortedJournalFiles() {
		File journalDir = new File(System.getProperty("user.home"), "Saved Games\\Frontier Developments\\Elite Dangerous");
		if (!journalDir.exists()) {
			return null;
		} else {
			File[] journalFiles = journalDir.listFiles(new FileFilter() {
				@Override
				public boolean accept(File file) {
					return file.getName().startsWith("Journal.") && file.getName().endsWith(".log");
				}
			});
			Arrays.sort(journalFiles, new Comparator<File>() {
				@Override
				public int compare(File f1, File f2) {
					return new Long(f1.lastModified()).compareTo(new Long(f2.lastModified()));
				}
			});
			logger.debug("Found " + journalFiles.length + " journal files");
			return journalFiles;
		}
	}

	private static void setCommanderData(File file) throws IOException {
		JournalEventReader jer = new JournalEventReader();
		List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		for (String line : lines) {
			AbstractJournalEvent event = jer.readLine(line);
			if (event instanceof LoadGameEvent) {
				LoadGameEvent loadGameEvent = (LoadGameEvent) event;
				myCommanderName = loadGameEvent.getCommander();
				myShip = loadGameEvent.getShip();
				maxFuel = loadGameEvent.getFuelCapacity().floatValue();
				break;
			}
		}
	}

	private static void computeTotals(File[] sortedJournalFiles) {
		JournalEventReader jer = new JournalEventReader();

		int jumps = 0;
		float lightyears = 0;
		long explorationPayout = 0;
		long lastEventMillis = 0;

		try {
			for (File journalFile : sortedJournalFiles) {
				// Quick check using plain string compare if it is for the current commander
				boolean isForCurrentCommander = false;
				try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(journalFile), "UTF-8"))) {
					String line = null;
					while ((line = br.readLine()) != null) {
						if (line.contains("\"event\":\"LoadGame\"")) {
							isForCurrentCommander = line.contains("\"Commander\":\"" + myCommanderName + "\"");
							break;
						}
					}
				}
				if (!isForCurrentCommander) {
					continue;
				}

				// If so, then process all lines
				List<String> lines = Files.readAllLines(journalFile.toPath(), StandardCharsets.UTF_8);
				for (String line : lines) {
					AbstractJournalEvent event = jer.readLine(line);
					if (event != null) {
						boolean countEventForPlaytime = false;
						if (event instanceof FSDJumpEvent) {
							myVisitedSystems.add(((FSDJumpEvent) event).getStarSystem());

							jumpsTotal++;
							jumps++;
							lightyearsTotal += ((FSDJumpEvent) event).getJumpDist().floatValue();
							lightyears += ((FSDJumpEvent) event).getJumpDist().floatValue();
							explorationPayoutTotal += 2000;
							explorationPayout += 2000;
							countEventForPlaytime = true;
						} else if (event instanceof ScanEvent) {
							explorationPayoutTotal += BodyUtil.estimatePayout((ScanEvent) event);
							explorationPayout += BodyUtil.estimatePayout((ScanEvent) event);
							countEventForPlaytime = true;
						} else if (event instanceof SellExplorationDataEvent || event instanceof DiedEvent) {
							jumpsTotal -= jumps;
							jumps = 0;
							lightyearsTotal -= lightyears;
							lightyears = 0;
							explorationPayoutTotal -= explorationPayout;
							explorationPayout = 0;
							playtimeMillisLastDock = 0;
							countEventForPlaytime = true;
						}

						if (countEventForPlaytime) {
							long millis = event.getTimestamp().toEpochSecond() * 1000 - lastEventMillis;
							if (millis <= 600000) {
								playtimeMillisLastDock += millis;
							}
							lastEventMillis = event.getTimestamp().toEpochSecond() * 1000;
						}
					}
				}
			}
		} catch (Exception e) {
			logger.error("Failed to compute totals", e);
		}
	}

	@Bean
	public Robot robot() {
		try {
			return new Robot(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice());
		} catch (AWTException e) {
			throw new RuntimeException("Failed to obtain a robot", e);
		}
	}

	@Bean
	public ShipControl shipControl() {
		return new ShipControl();
	}

	@Bean
	public SysmapScanner sysmapScanner() {
		return new SysmapScanner();
	}

	@Bean
	public ScreenReaderThread screenReaderThread() {
		return new ScreenReaderThread();
	}

	@Bean
	public ScreenConverterThread screenConverterThread() {
		return new ScreenConverterThread();
	}

	@Bean
	public CruiseControlThread cruiseControlThread() {
		return new CruiseControlThread();
	}

}
