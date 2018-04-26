package borg.ed.cruisecontrol;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;

import javax.swing.UIManager;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import borg.ed.cruisecontrol.gui.CruiseControlFrame;
import borg.ed.universe.UniverseApplication;
import borg.ed.universe.journal.JournalReaderThread;
import borg.ed.universe.journal.StatusReaderThread;
import borg.ed.universe.service.UniverseService;

@Configuration
@Import(UniverseApplication.class)
public class CruiseControlApplication {

	private static final ApplicationContext APPCTX = new AnnotationConfigApplicationContext(CruiseControlApplication.class);

	public static final int SCALED_WIDTH = 1920;
	public static final int SCALED_HEIGHT = 1080;
	public static final float MAX_FUEL = 32f;
	public static final boolean JONK_MODE = false;
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

		CruiseControlThread cruiseControlThread = new CruiseControlThread(robot, screenRect, universeService);
		cruiseControlThread.start();

		CruiseControlFrame cruiseControlFrame = new CruiseControlFrame();
		cruiseControlFrame.setVisible(true);

		cruiseControlThread.addDebugImageListener(cruiseControlFrame);

		JournalReaderThread journalReaderThread = APPCTX.getBean(JournalReaderThread.class);
		journalReaderThread.addListener(cruiseControlThread);
		journalReaderThread.addListener(cruiseControlFrame);
		journalReaderThread.start();

		StatusReaderThread statusReaderThread = APPCTX.getBean(StatusReaderThread.class);
		statusReaderThread.addListener(cruiseControlThread);
		statusReaderThread.addListener(cruiseControlFrame);
		statusReaderThread.start();
	}

}
