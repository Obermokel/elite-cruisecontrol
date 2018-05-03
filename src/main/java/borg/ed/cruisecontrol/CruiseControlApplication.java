package borg.ed.cruisecontrol;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import borg.ed.cruisecontrol.gui.CruiseControlFrame;
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
import borg.ed.universe.service.UniverseService;
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

	public static String myCommanderName = "Unknown Commander Name";
	public static List<String> myWingMates = new ArrayList<>();

	public static final int SCALED_WIDTH = 1920;
	public static final int SCALED_HEIGHT = 1080;
	public static final float MAX_FUEL = 32f;
	public static final boolean JONK_MODE = false;
	public static final boolean CREDITS_MODE = true;
	public static final boolean SHOW_LIVE_DEBUG_IMAGE = true;
	public static final boolean WRITE_SYSMAP_DEBUG_RGB_ORIGINAL = true;
	public static final boolean WRITE_SYSMAP_DEBUG_GRAY = false;
	public static final boolean WRITE_SYSMAP_DEBUG_THRESHOLD = false;
	public static final boolean WRITE_SYSMAP_DEBUG_RGB_RESULT = false;
	public static final boolean WRITE_SYSMAP_DEBUG_BODY_RGB_ORIGINAL = true;
	public static final boolean WRITE_SYSMAP_DEBUG_BODY_GRAY = true;
	public static final boolean WRITE_SYSMAP_DEBUG_BODY_RGB_RESULT = true;

	public static void main(String[] args) throws AWTException {
		try {
			UIManager.setLookAndFeel("com.jtattoo.plaf.noire.NoireLookAndFeel");
		} catch (Exception e) {
			// Ignore
		}

		GraphicsDevice primaryScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
		Rectangle screenRect = new Rectangle(primaryScreen.getDisplayMode().getWidth(), primaryScreen.getDisplayMode().getHeight());
		Robot robot = new Robot(primaryScreen);

		UniverseService universeService = APPCTX.getBean(UniverseService.class);

		JournalReaderThread journalReaderThread = APPCTX.getBean(JournalReaderThread.class);
		journalReaderThread.start();

		StatusReaderThread statusReaderThread = APPCTX.getBean(StatusReaderThread.class);
		statusReaderThread.start();

		CruiseControlThread cruiseControlThread = new CruiseControlThread(robot, screenRect, universeService);
		cruiseControlThread.start();

		CruiseControlFrame cruiseControlFrame = new CruiseControlFrame();
		cruiseControlFrame.setVisible(true);

		cruiseControlThread.addDebugImageListener(cruiseControlFrame);

		new Thread(new Runnable() {
			@Override
			public void run() {
				computeExplorationPayoutTotal();
			}
		}).start();

		journalReaderThread.addListener(cruiseControlThread);
		journalReaderThread.addListener(cruiseControlFrame);

		statusReaderThread.addListener(cruiseControlThread);
		statusReaderThread.addListener(cruiseControlFrame);
	}

	private static void computeExplorationPayoutTotal() {
		int jumps = 0;
		float lightyears = 0;
		long explorationPayout = 0;
		long lastEventMillis = 0;
		try {
			JournalEventReader jer = new JournalEventReader();
			File journalDir = new File(System.getProperty("user.home"), "Saved Games\\Frontier Developments\\Elite Dangerous");
			if (journalDir.exists()) {
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
				String myCommanderName = lookupCommanderName(journalFiles[journalFiles.length - 1]);
				for (File journalFile : journalFiles) {
					List<String> lines = Files.readAllLines(journalFile.toPath(), StandardCharsets.UTF_8);
					for (String line : lines) {
						AbstractJournalEvent event = jer.readLine(line);
						if (event != null) {
							long millis = event.getTimestamp().toEpochSecond() * 1000 - lastEventMillis;
							if (event instanceof LoadGameEvent) {
								String commander = ((LoadGameEvent) event).getCommander();
								if (!myCommanderName.equals(commander)) {
									break;
								}
							} else if (event instanceof FSDJumpEvent) {
								jumpsTotal++;
								jumps++;
								lightyearsTotal += ((FSDJumpEvent) event).getJumpDist().floatValue();
								lightyears += ((FSDJumpEvent) event).getJumpDist().floatValue();
								explorationPayoutTotal += 2000;
								explorationPayout += 2000;
								if (millis <= 600000) {
									playtimeMillisLastDock += millis;
								}
							} else if (event instanceof ScanEvent) {
								explorationPayoutTotal += BodyUtil.estimatePayout((ScanEvent) event);
								explorationPayout += BodyUtil.estimatePayout((ScanEvent) event);
								if (millis <= 600000) {
									playtimeMillisLastDock += millis;
								}
							} else if (event instanceof SellExplorationDataEvent || event instanceof DiedEvent) {
								jumpsTotal -= jumps;
								jumps = 0;
								lightyearsTotal -= lightyears;
								lightyears = 0;
								explorationPayoutTotal -= explorationPayout;
								explorationPayout = 0;
								playtimeMillisLastDock = 0;
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

	private static String lookupCommanderName(File file) throws IOException {
		JournalEventReader jer = new JournalEventReader();
		List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		for (String line : lines) {
			AbstractJournalEvent event = jer.readLine(line);
			if (event instanceof LoadGameEvent) {
				return ((LoadGameEvent) event).getCommander();
			}
		}
		return null;
	}

}
