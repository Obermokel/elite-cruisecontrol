package borg.ed.cruisecontrol;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import borg.ed.cruisecontrol.sysmap.SysmapScanner;
import borg.ed.cruisecontrol.sysmap.SysmapScannerResult;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
import borg.ed.cruisecontrol.templatematching.TemplateMatchRgb;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;
import borg.ed.cruisecontrol.util.ImageUtil;
import borg.ed.universe.journal.JournalReaderThread;
import borg.ed.universe.journal.JournalUpdateListener;
import borg.ed.universe.journal.Status;
import borg.ed.universe.journal.StatusReaderThread;
import borg.ed.universe.journal.StatusUpdateListener;
import borg.ed.universe.journal.events.AbstractJournalEvent;
import borg.ed.universe.journal.events.DiscoveryScanEvent;
import borg.ed.universe.journal.events.FSDJumpEvent;
import borg.ed.universe.journal.events.FuelScoopEvent;
import borg.ed.universe.journal.events.ScanEvent;
import borg.ed.universe.journal.events.StartJumpEvent;
import borg.ed.universe.model.Body;
import borg.ed.universe.service.UniverseService;
import borg.ed.universe.util.BodyUtil;
import borg.ed.universe.util.MiscUtil;

public class CruiseControlThread extends Thread implements JournalUpdateListener, StatusUpdateListener {

	static final Logger logger = LoggerFactory.getLogger(CruiseControlThread.class);

	private final Robot robot;
	private final Rectangle screenRect;
	private final UniverseService universeService;
	private final ShipControl shipControl;
	private final SysmapScanner sysmapScanner = new SysmapScanner();

	private GrayF32 refCompass = null;
	private GrayF32 refCompassMask = null;
	private GrayF32 refCompassDotFilled = null;
	private GrayF32 refCompassDotHollow = null;
	private GrayF32 refTarget = null;
	private GrayF32 refTargetMask = null;
	private GrayF32 refShipHud = null;
	private GrayF32 refSixSeconds = null;
	private GrayF32 refScanning = null;

	private GameState gameState = GameState.UNKNOWN;
	private long jumpInitiated = 0;
	private long honkInitiated = 0;
	private long escapeInitiated = 0;
	private int xPercent = 0;
	private int yPercent = 0;
	private boolean hollow = false;
	private float brightnessAhead = 0;
	private boolean scoopingFuel = false;
	private boolean fsdCooldown = false;
	private float fuelLevel = 0;
	private long inSupercruiseSince = Long.MAX_VALUE;
	private long inHyperspaceSince = Long.MAX_VALUE; // Timestamp when the FSD was charged and the countdown started
	private long escapingFromStarSince = Long.MAX_VALUE;
	private boolean fsdCharging = false;
	private long fsdChargingSince = Long.MAX_VALUE;
	private String currentSystemName = "";
	private List<Body> knownValuableBodies = new ArrayList<>();
	private int discoveredBodiesInSystem = 0;
	private TemplateMatchRgb currentBodyTemplateMatch = null;
	private SysmapScannerResult sysmapScannerResult = null;
	private long lastTick = System.currentTimeMillis();

	private static final int COMPASS_REGION_X = 620;
	private static final int COMPASS_REGION_Y = 777;
	private static final int COMPASS_REGION_WIDTH = 190;
	private static final int COMPASS_REGION_HEIGHT = 200;
	private TemplateMatch compassMatch = null;

	private static final int TARGET_REGION_X = 555;
	private static final int TARGET_REGION_Y = 190;
	private static final int TARGET_REGION_WIDTH = 810;
	private static final int TARGET_REGION_HEIGHT = 550;
	private TemplateMatch targetMatch = null;

	private TemplateMatch sixSecondsMatch = null;
	private TemplateMatch scanningMatch = null;

	private static Boolean inEmergencyExit = Boolean.FALSE;

	private List<DebugImageListener> debugImageListeners = new ArrayList<>();

	public CruiseControlThread(Robot robot, Rectangle screenRect, UniverseService universeService) {
		this.setName("CCThread");
		this.setDaemon(false);

		this.robot = robot;
		this.screenRect = screenRect;
		this.universeService = universeService;
		this.shipControl = new ShipControl(robot);

		this.sysmapScanner.setWriteDebugImageRgbOriginal(CruiseControlApplication.WRITE_SYSMAP_DEBUG_RGB_ORIGINAL);
		this.sysmapScanner.setWriteDebugImageRgbResult(CruiseControlApplication.WRITE_SYSMAP_DEBUG_RGB_RESULT);
		this.sysmapScanner.setWriteDebugImageGray(CruiseControlApplication.WRITE_SYSMAP_DEBUG_GRAY);
	}

	@Override
	public void run() {
		logger.info(this.getName() + " started");

		this.loadRefImages();

		final ScreenReaderResult screenReaderResult = new ScreenReaderResult();
		final ScreenReaderThread screenReaderThread = new ScreenReaderThread(this.robot, this.screenRect, screenReaderResult);
		screenReaderThread.start();

		final ScreenConverterResult screenConverterResult = new ScreenConverterResult();
		final ScreenConverterThread screenConverterThread = new ScreenConverterThread(screenReaderResult, screenConverterResult);
		screenConverterThread.start();

		//		Planar<GrayF32> rgb = new Planar<>(GrayF32.class, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, 3);
		//		Planar<GrayF32> hsv = rgb.createSameShape();
		GrayF32 orangeHudImage = new GrayF32(CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);
		GrayF32 blueWhiteHudImage = orangeHudImage.createSameShape();
		GrayF32 redHudImage = orangeHudImage.createSameShape();
		GrayF32 brightImage = orangeHudImage.createSameShape();
		BufferedImage debugImage = new BufferedImage(CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, BufferedImage.TYPE_INT_RGB);

		final ExecutorService threadPool = Executors.newFixedThreadPool(4);

		while (!Thread.currentThread().isInterrupted()) {
			try {
				lastTick = System.currentTimeMillis();

				// >>>> SCREEN CAPTURE >>>>
				//				HWND hwnd = User32.INSTANCE.FindWindow(null, "Elite - Dangerous (CLIENT)");
				//				BufferedImage screenCapture = GDI32Util.getScreenshot(hwnd);
				//				BufferedImage screenCapture = robot.createScreenCapture(this.screenRect);
				//				BufferedImage scaledScreenCapture = ImageUtil.scaleAndCrop(screenCapture, rgb.width, rgb.height);
				//				ConvertBufferedImage.convertFrom(scaledScreenCapture, true, rgb);
				//				ColorHsv.rgbToHsv_F32(rgb, hsv);
				//				this.hsvToHudImages(hsv, orangeHudImage, blueWhiteHudImage, redHudImage);
				synchronized (screenConverterResult) {
					try {
						screenConverterResult.wait();
						orangeHudImage = screenConverterResult.getOrangeHudImage().clone();
						blueWhiteHudImage = screenConverterResult.getBlueWhiteHudImage().clone();
						redHudImage = screenConverterResult.getRedHudImage().clone();
						brightImage = screenConverterResult.getBrightImage().clone();
					} catch (InterruptedException e) {
						break;
					}
				}
				// <<<< SCREEN CAPTURE <<<<

				brightnessAhead = this.computeBrightnessAhead(brightImage);
				compassMatch = null;
				targetMatch = null;
				if (gameState == GameState.UNKNOWN || gameState == GameState.ALIGN_TO_NEXT_SYSTEM || gameState == GameState.FSD_CHARGING || gameState == GameState.ALIGN_TO_NEXT_BODY
						|| gameState == GameState.APPROACH_NEXT_BODY) {
					final GrayF32 myOrangeHudImage = orangeHudImage.clone();
					Future<TemplateMatch> futureTarget = threadPool.submit(new Callable<TemplateMatch>() {
						@Override
						public TemplateMatch call() throws Exception {
							return locateTargetSmart(myOrangeHudImage);
						}
					});
					Future<TemplateMatch> futureCompass = threadPool.submit(new Callable<TemplateMatch>() {
						@Override
						public TemplateMatch call() throws Exception {
							return locateCompassSmart(myOrangeHudImage);
						}
					});
					targetMatch = futureTarget.get();
					compassMatch = futureCompass.get();
				} else {
					compassMatch = null;
					targetMatch = null;
				}

				if (gameState == GameState.APPROACH_NEXT_BODY) {
					if (targetMatch == null) {
						sixSecondsMatch = null;
					} else {
						int sixSecondsX = targetMatch.getX() + 90;
						int sixSecondsY = targetMatch.getY() + 75;
						sixSecondsMatch = TemplateMatcher.findBestMatchingLocation(brightImage.subimage(sixSecondsX, sixSecondsY, sixSecondsX + 82, sixSecondsY + 28), this.refSixSeconds);
						if (sixSecondsMatch.getErrorPerPixel() > 0.15f) {
							sixSecondsMatch = null;
						} else {
							sixSecondsMatch = new TemplateMatch(sixSecondsMatch.getX() + sixSecondsX, sixSecondsMatch.getY() + sixSecondsY, sixSecondsMatch.getWidth(),
									sixSecondsMatch.getHeight(), sixSecondsMatch.getError(), sixSecondsMatch.getErrorPerPixel());
						}
					}

					scanningMatch = TemplateMatcher.findBestMatchingLocation(orangeHudImage.subimage(2, 860, 180, 970), this.refScanning);
					if (scanningMatch.getErrorPerPixel() > 0.05f) {
						scanningMatch = null;
					} else {
						scanningMatch = new TemplateMatch(scanningMatch.getX() + 2, scanningMatch.getY() + 860, scanningMatch.getWidth(), scanningMatch.getHeight(), scanningMatch.getError(),
								scanningMatch.getErrorPerPixel());
					}
				} else {
					sixSecondsMatch = null;
					scanningMatch = null;
				}

				TemplateMatch compassDotMatch = null;
				boolean hollow = false;
				if (compassMatch != null) {
					int xOffset = compassMatch.getX() - 16;
					int yOffset = compassMatch.getY() - 16;
					int regionWidth = compassMatch.getWidth() + 32;
					int regionHeight = compassMatch.getHeight() + 32;
					GrayF32 grayCompass = blueWhiteHudImage.subimage(xOffset, yOffset, xOffset + regionWidth, yOffset + regionHeight);
					TemplateMatch compassDotFilledMatch = TemplateMatcher.findBestMatchingLocation(grayCompass, this.refCompassDotFilled);
					if (compassDotFilledMatch.getErrorPerPixel() >= 0.05f) {
						compassDotFilledMatch = null;
					}
					TemplateMatch compassDotHollowMatch = TemplateMatcher.findBestMatchingLocation(grayCompass, this.refCompassDotHollow);
					if (compassDotHollowMatch.getErrorPerPixel() >= 0.05f) {
						compassDotHollowMatch = null;
					}
					if (compassDotFilledMatch == null && compassDotHollowMatch == null) {
						// Not found
					} else if ((compassDotFilledMatch != null && compassDotHollowMatch == null)
							|| (compassDotFilledMatch != null && compassDotHollowMatch != null && compassDotFilledMatch.getErrorPerPixel() <= compassDotHollowMatch.getErrorPerPixel())) {
						hollow = false;
						compassDotMatch = new TemplateMatch(compassDotFilledMatch.getX() + xOffset, compassDotFilledMatch.getY() + yOffset, compassDotFilledMatch.getWidth(),
								compassDotFilledMatch.getHeight(), compassDotFilledMatch.getError(), compassDotFilledMatch.getErrorPerPixel());
					} else if ((compassDotFilledMatch == null && compassDotHollowMatch != null)
							|| (compassDotFilledMatch != null && compassDotHollowMatch != null && compassDotFilledMatch.getErrorPerPixel() >= compassDotHollowMatch.getErrorPerPixel())) {
						hollow = true;
						compassDotMatch = new TemplateMatch(compassDotHollowMatch.getX() + xOffset, compassDotHollowMatch.getY() + yOffset, compassDotHollowMatch.getWidth(),
								compassDotHollowMatch.getHeight(), compassDotHollowMatch.getError(), compassDotHollowMatch.getErrorPerPixel());
					}
				}

				// >>>> DEBUG IMAGE >>>>
				//ConvertBufferedImage.convertTo(orangeHudImage, debugImage);

				this.drawColoredDebugImage(debugImage, orangeHudImage, blueWhiteHudImage, redHudImage, brightImage);
				this.drawDebugInfoOnImage(debugImage, compassDotMatch, hollow);

				for (DebugImageListener listener : this.debugImageListeners) {
					listener.onNewDebugImage(debugImage, orangeHudImage, blueWhiteHudImage, redHudImage, brightImage);
				}
				// <<<< DEBUG IMAGE <<<<

				switch (this.gameState) {
				case UNKNOWN:
					this.shipControl.releaseAllKeys();
					break;
				case FSD_CHARGING:
					if (this.shipControl.getThrottle() < 100) {
						this.shipControl.setThrottle(100);
					}
					if (System.currentTimeMillis() - this.fsdChargingSince > 15000) {
						if (this.shipControl.getThrottle() != 75) {
							this.shipControl.setThrottle(75);
						}
					} else if (System.currentTimeMillis() - this.fsdChargingSince > 20000) {
						if (this.shipControl.getThrottle() != 100) {
							this.shipControl.setThrottle(100);
						}
					}
					if (targetMatch != null) {
						this.alignToTargetInHud(targetMatch);
					} else {
						this.alignToTargetInCompass(compassMatch, compassDotMatch, hollow);
					}
					break;
				case IN_HYPERSPACE:
					if (System.currentTimeMillis() - this.inHyperspaceSince > 5000 && this.shipControl.getThrottle() > 0) {
						this.shipControl.setThrottle(0);
					}
					break;
				case WAIT_FOR_FSD_COOLDOWN:
					if (this.shipControl.getThrottle() > 0) {
						this.shipControl.setThrottle(0);
					}
					break;
				case GET_IN_SCOOPING_RANGE:
					if (this.shipControl.getThrottle() != 25) {
						this.shipControl.setThrottle(25);
					}
					if (this.scoopingFuel) {
						this.shipControl.setThrottle(0);
						this.gameState = GameState.SCOOPING_FUEL;
						logger.debug("Scooping fuel...");
					}
					break;
				case SCOOPING_FUEL:
					if (this.fuelLevel >= (CruiseControlApplication.MAX_FUEL / 2)) {
						this.gameState = GameState.ALIGN_TO_STAR_ESCAPE;
						logger.debug("Fuel tank filled > 50%, aligning to star escape vector");
					}
					break;
				case ALIGN_TO_STAR_ESCAPE:
					if (this.brightnessAhead > 0.05f) {
						this.shipControl.setPitchDown(100);
					} else {
						this.shipControl.stopTurning();
						this.gameState = GameState.ESCAPE_FROM_STAR_SLOW;
						logger.debug("Escape vector reached, accelerating to 25%");
					}
					break;
				case ESCAPE_FROM_STAR_SLOW:
					if (this.fuelLevel >= CruiseControlApplication.MAX_FUEL) {
						if (this.shipControl.getThrottle() != 50) {
							this.shipControl.setThrottle(50);
							logger.debug("Fuel tank full, accelerating to 50%");
						}
					} else {
						if (this.shipControl.getThrottle() != 25) {
							this.shipControl.setThrottle(25);
						}
					}
					if (this.brightnessAhead > 0.10f) {
						this.shipControl.setPitchDown(100);
					} else {
						this.shipControl.stopTurning();
					}
					if (!this.scoopingFuel) {
						this.escapingFromStarSince = System.currentTimeMillis();
						this.gameState = GameState.ESCAPE_FROM_STAR_FASTER;
						logger.debug("Scooping range left, accelerating to 75%");
					}
					break;
				case ESCAPE_FROM_STAR_FASTER:
					if (this.shipControl.getThrottle() != 75) {
						this.shipControl.setThrottle(75);
					}
					if (this.brightnessAhead > 0.15f) {
						this.shipControl.setPitchDown(100);
					} else {
						this.shipControl.stopTurning();
					}
					if (System.currentTimeMillis() - this.escapingFromStarSince > 10000) {
						this.escapingFromStarSince = Long.MAX_VALUE;
						if (this.discoveredBodiesInSystem > 1 && !CruiseControlApplication.JONK_MODE) {
							this.shipControl.setThrottle(0);
							this.shipControl.toggleSystemMap();
							this.gameState = GameState.SCAN_SYSTEM_MAP;
							logger.debug("Escaped from entry star, " + this.discoveredBodiesInSystem + " bodies discovered, throttle to 0% and scanning system map");
						} else {
							this.shipControl.setThrottle(100);
							this.shipControl.selectNextSystemInRoute();
							this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
							logger.debug("Escaped from entry star, no other bodies discovered, aligning to next jump target at 100% throttle");
						}
					}
					break;
				case SCAN_SYSTEM_MAP:
					synchronized (screenConverterResult) {
						this.sysmapScannerResult = this.sysmapScanner.scanSystemMap(screenConverterResult.getRgb(), screenConverterResult.getHsv(), this.currentSystemName);
						if (this.sysmapScannerResult != null) {
							if (this.sysmapScannerResult.getSystemMapScreenCoords().isEmpty()) {
								// Close sysmap, then throttle up and go to next system in route
								this.shipControl.toggleSystemMap();
								Thread.sleep(1000);
								this.shipControl.setThrottle(100);
								this.shipControl.selectNextSystemInRoute();
								this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
								logger.debug("System map scanned, no bodies recognized, aligning to next jump target at 100% throttle");
							} else {
								// Select body from sysmap, then close map and wait for ship HUD
								this.clickOnNextBodyOnSystemMap();
								this.shipControl.toggleSystemMap();
								this.shipControl.setThrottle(0);
								this.gameState = GameState.WAIT_FOR_SHIP_HUD;
								logger.debug("System map scanned, waiting for ship HUD at 0% throttle");
							}
						}
					}
					break;
				case ALIGN_TO_NEXT_BODY:
					if (this.brightnessAhead > 0.15f) {
						if (this.shipControl.getThrottle() != 25) {
							this.shipControl.setThrottle(25);
						}
						this.shipControl.setPitchDown(100);
					} else {
						if (targetMatch != null) {
							if (this.shipControl.getThrottle() != 0) {
								this.shipControl.setThrottle(0);
							}
							if (this.alignToTargetInHud(targetMatch)) {
								this.shipControl.setThrottle(75);
								this.gameState = GameState.APPROACH_NEXT_BODY;
								logger.debug("Next body in sight, accelerating to 75% and waiting for detailed surface scan");
							}
						} else if (compassMatch != null && compassDotMatch != null) {
							if (this.shipControl.getThrottle() != 0) {
								this.shipControl.setThrottle(0);
							}
							if (this.alignToTargetInCompass(compassMatch, compassDotMatch, hollow)) {
								this.shipControl.setThrottle(75);
								this.gameState = GameState.APPROACH_NEXT_BODY;
								logger.debug("Next body in sight, accelerating to 75% and waiting for detailed surface scan");
							}
						} else {
							if (this.shipControl.getThrottle() != 25) {
								this.shipControl.setThrottle(25);
							}
						}
					}
					break;
				case APPROACH_NEXT_BODY:
					if (this.brightnessAhead > 0.15f) {
						if (this.shipControl.getThrottle() != 25) {
							this.shipControl.setThrottle(25);
						}
						this.shipControl.setPitchDown(100);
					} else {
						if (scanningMatch != null) {
							if (this.shipControl.getThrottle() != 0) {
								this.shipControl.setThrottle(0);
							}
						} else if (sixSecondsMatch != null) {
							if (this.shipControl.getThrottle() != 75) {
								this.shipControl.setThrottle(75);
							}
						} else if (targetMatch == null) {
							if (this.shipControl.getThrottle() != 0) {
								this.shipControl.setThrottle(0);
							}
						} else {
							if (this.shipControl.getThrottle() != 100) {
								this.shipControl.setThrottle(100);
							}
						}
						if (targetMatch != null) {
							this.alignToTargetInHud(targetMatch);
						} else {
							this.alignToTargetInCompass(compassMatch, compassDotMatch, hollow);
						}
					}
					break;
				case WAIT_FOR_SYSTEM_MAP:
					synchronized (screenConverterResult) {
						if (this.sysmapScanner.waitForSystemMap(screenConverterResult.getRgb().clone())) {
							this.clickOnNextBodyOnSystemMap();
							this.shipControl.toggleSystemMap();
							this.shipControl.setThrottle(0);
							this.gameState = GameState.WAIT_FOR_SHIP_HUD;
							logger.debug("Clicked on next body, waiting for ship HUD at 0% throttle");
						}
					}
					break;
				case WAIT_FOR_SHIP_HUD:
					if (this.waitForShipHud(orangeHudImage)) {
						this.shipControl.setThrottle(25);
						this.gameState = GameState.ALIGN_TO_NEXT_BODY;
						logger.debug("Ship HUD visible, aligning to next body at 25% throttle");
					}
					break;
				case ALIGN_TO_NEXT_SYSTEM:
					if (this.shipControl.getThrottle() < 100) {
						this.shipControl.setThrottle(100);
					}
					if (targetMatch != null) {
						if (this.alignToTargetInHud(targetMatch)) {
							this.shipControl.toggleFsd();
							this.gameState = GameState.FSD_CHARGING;
							logger.debug("Next system in sight, charging FSD");
						}
					} else {
						if (this.alignToTargetInCompass(compassMatch, compassDotMatch, hollow)) {
							this.shipControl.toggleFsd();
							this.gameState = GameState.FSD_CHARGING;
							logger.debug("Next system in sight, charging FSD");
						}
					}
					break;
				case IN_EMERGENCY_EXIT:
					break;
				default:
					this.doEmergencyExit("Unknown game state " + this.gameState);
					break;
				}
			} catch (Exception e) {
				logger.error(this.getName() + " crashed", e);
				break; // Quit
			}
		}

		logger.info(this.getName() + " stopped");
	}

	private boolean waitForShipHud(GrayF32 orangeHudImage) {
		return TemplateMatcher.findBestMatchingLocation(orangeHudImage.subimage(1650, 900, 1900, 1050), this.refShipHud).getErrorPerPixel() <= 0.05f;
	}

	private void doEmergencyExit(String reason) {
		synchronized (inEmergencyExit) {
			inEmergencyExit = Boolean.TRUE;

			try {
				logger.error("Emergency exit! Reason: " + reason);
				this.gameState = GameState.IN_EMERGENCY_EXIT;

				// Terminate all event-generating threads
				Thread[] tarray = new Thread[Thread.activeCount() + 100];
				Thread.enumerate(tarray);
				for (Thread t : tarray) {
					if (t instanceof JournalReaderThread || t instanceof StatusReaderThread || t instanceof ScreenConverterThread || t instanceof ScreenReaderThread) {
						logger.warn("Interrupting " + t);
						t.interrupt();
					}
				}

				// Give them some time to terminate
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					logger.warn("Interrupted while waiting after stopping threads");
				}

				// Full stop, then exit
				this.shipControl.fullStop();
				this.shipControl.exitToMainMenu();

				// Wait until we are at the main menu
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					logger.warn("Interrupted while waiting after exit to main menu");
				}

				// We want to see what has happened
				this.shipControl.saveShadowplay();

				// Give the video some time to save
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					logger.warn("Interrupted while waiting after shadowplay capture");
				}

				// Let's hope it worked
				System.exit(-1);
			} catch (Exception e) {
				logger.error("Exception while in emergency exit", e);
				this.shipControl.saveShadowplay();
				System.exit(-1);
			}
		}
	}

	private float computeBrightnessAhead(GrayF32 brightImage) {
		// Upper 2/3 of the screen
		int stepX = (int) (brightImage.width / 32.0f);
		int offX = stepX / 2;
		int stepY = (int) (brightImage.height / 18.0f);
		int offY = stepY / 2;

		float total = 32.0f * 12.0f;
		float bright = 0.0f;

		for (int x = 0; x < 32; x++) {
			for (int y = 0; y < 12; y++) {
				int myX = offX + x * stepX;
				int myY = offY + y * stepY;
				if (brightImage.unsafe_get(myX, myY) > 0) {
					bright++;
				}
			}
		}

		// Upper 10% of the screen
		stepX = (int) (brightImage.width / 32.0f);
		offX = stepX / 2;
		stepY = (int) (brightImage.height / 40.0f);
		offY = stepY / 2;

		total += 32.0f * 4.0f;

		for (int x = 0; x < 32; x++) {
			for (int y = 0; y < 4; y++) {
				int myX = offX + x * stepX;
				int myY = offY + y * stepY;
				if (brightImage.unsafe_get(myX, myY) > 0) {
					bright++;
				}
			}
		}

		// Center 20% square of the screen
		int squareSize = brightImage.height / 5;
		stepX = squareSize / 10;
		offX = (brightImage.width / 2) - (int) (4.5f * stepX);
		stepY = squareSize / 10;
		offY = (brightImage.height / 2) - (int) (4.5f * stepY);

		total += 10.0f * 10.0f;

		for (int x = 0; x < 10; x++) {
			for (int y = 0; y < 10; y++) {
				int myX = offX + x * stepX;
				int myY = offY + y * stepY;
				if (brightImage.unsafe_get(myX, myY) > 0) {
					bright++;
				}
			}
		}

		// Result
		return bright / total;
	}

	private void honkAndSelect() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					CruiseControlThread.this.shipControl.honk();
					Thread.sleep(7500);
					CruiseControlThread.this.shipControl.uiLeftPanel();
					Thread.sleep(5000);
					CruiseControlThread.this.shipControl.uiRight();
					Thread.sleep(500);
					CruiseControlThread.this.shipControl.uiUp(2500);
					Thread.sleep(3000);
					CruiseControlThread.this.shipControl.uiSelect();
					Thread.sleep(500);
					CruiseControlThread.this.shipControl.uiSelect();
					Thread.sleep(500);
					CruiseControlThread.this.shipControl.uiLeftPanel();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}).start();
	}

	private boolean alignToTargetInHud(TemplateMatch hudMatch) {
		if (hudMatch == null) {
			this.shipControl.stopTurning();
		} else {
			int x = (hudMatch.getX() + hudMatch.getWidth() / 2);
			int y = (hudMatch.getY() + hudMatch.getHeight() / 2);
			xPercent = (x * 100) / CruiseControlApplication.SCALED_WIDTH;
			yPercent = (y * 100) / CruiseControlApplication.SCALED_HEIGHT;

			if (xPercent >= 48 && xPercent <= 52 && yPercent >= 48 && yPercent <= 52) {
				this.shipControl.stopTurning();
				return true;
			}

			// Controlled pitch
			if (yPercent < 50) {
				if (yPercent < 25) {
					this.shipControl.setPitchUp(100);
				} else {
					this.shipControl.setPitchUp(Math.max(5, (50 - yPercent) * 2));
				}
			} else {
				if (yPercent > 75) {
					this.shipControl.setPitchDown(100);
				} else {
					this.shipControl.setPitchDown(Math.max(5, (yPercent - 50) * 2));
				}
			}
			// Roll to target
			if (xPercent < 50) {
				if (xPercent < 45) {
					this.shipControl.setYawLeft(100);
				} else {
					this.shipControl.setYawLeft(Math.max(15, (50 - xPercent) * 2));
				}
			} else {
				if (xPercent > 55) {
					this.shipControl.setYawRight(100);
				} else {
					this.shipControl.setYawRight(Math.max(15, (xPercent - 50) * 2));
				}
			}
		}

		return false;
	}

	private boolean alignToTargetInCompass(TemplateMatch compassMatch, TemplateMatch compassDotMatch, boolean hollow) {
		if (compassMatch == null || compassDotMatch == null) {
			this.shipControl.stopTurning();
		} else {
			int width = compassMatch.getWidth();
			int height = compassMatch.getHeight();
			int x = (compassDotMatch.getX() - compassMatch.getX()) + (compassDotMatch.getWidth() / 2);
			int y = (compassDotMatch.getY() - compassMatch.getY()) + (compassDotMatch.getHeight() / 2);
			xPercent = (x * 100) / width;
			yPercent = (y * 100) / height;
			this.hollow = hollow;

			if (!hollow && xPercent >= 48 && xPercent <= 52 && yPercent >= 48 && yPercent <= 52) {
				this.shipControl.stopTurning();
				return true;
			}

			if (hollow) {
				// Strong pitch!
				if (yPercent < 50) {
					this.shipControl.setPitchUp(100);
				} else {
					this.shipControl.setPitchDown(100);
				}
				// Also yaw
				if (xPercent < 50) {
					this.shipControl.setYawLeft(100);
				} else {
					this.shipControl.setYawRight(100);
				}
			} else {
				// Controlled pitch
				if (yPercent <= 40) {
					if (yPercent <= 20) {
						this.shipControl.setPitchUp(100);
					} else {
						this.shipControl.setPitchUp(Math.max(10, Math.round((1f - ((yPercent - 20) / 20f)) * 100f)));
					}
				} else {
					if (yPercent > 70) {
						this.shipControl.setPitchDown(100);
					} else {
						this.shipControl.setPitchDown(Math.max(10, Math.round(((yPercent - 40) / 30f) * 100f)));
					}
				}
				// Roll to target
				if (xPercent < 50) {
					if (xPercent < 45) {
						this.shipControl.setYawLeft(100);
					} else {
						this.shipControl.setYawLeft(Math.max(25, (50 - xPercent) * 2));
					}
				} else {
					if (xPercent > 55) {
						this.shipControl.setYawRight(100);
					} else {
						this.shipControl.setYawRight(Math.max(25, (xPercent - 50) * 2));
					}
				}
			}
		}

		return false;
	}

	private boolean alignToEscapeInCompass(TemplateMatch compassMatch, TemplateMatch compassDotMatch, boolean hollow) {
		if (compassMatch == null || compassDotMatch == null) {
			this.shipControl.stopTurning();
		} else {
			int width = compassMatch.getWidth();
			int height = compassMatch.getHeight();
			int x = (compassDotMatch.getX() - compassMatch.getX()) + (compassDotMatch.getWidth() / 2);
			int y = (compassDotMatch.getY() - compassMatch.getY()) + (compassDotMatch.getHeight() / 2);
			xPercent = (x * 100) / width;
			yPercent = (y * 100) / height;
			this.hollow = hollow;

			if (hollow && xPercent >= 45 && xPercent <= 55 && (yPercent > 80 || yPercent < 20)) {
				this.shipControl.stopTurning();
				return true;
			}

			if (!hollow) {
				// Strong pitch!
				if (yPercent < 50) {
					this.shipControl.setPitchDown(100);
				} else {
					this.shipControl.setPitchUp(100);
				}
				// Also yaw
				if (xPercent < 50) {
					this.shipControl.setYawLeft(100);
				} else {
					this.shipControl.setYawRight(100);
				}
			} else {
				// Controlled pitch
				if (yPercent < 50) {
					// 10: slow pitch up
					// 40: fast pitch up
					this.shipControl.setPitchUp(Math.max(10, yPercent * 2));
				} else {
					// 60: fast pitch down
					// 90: slow pitch down
					this.shipControl.setPitchDown(Math.max(10, (50 - (yPercent - 50)) * 2));
				}
				// Roll to target
				if (xPercent < 50) {
					this.shipControl.setYawRight(Math.max(25, (50 - xPercent) * 2));
				} else {
					this.shipControl.setYawLeft(Math.max(25, (xPercent - 50) * 2));
				}
			}
		}

		return false;
	}

	private TemplateMatch locateCompass(GrayF32 orangeHudImage) {
		TemplateMatch m = this.locateTemplateInRegion(orangeHudImage, COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT, this.refCompass, this.refCompassMask);
		return m.getErrorPerPixel() < 0.15f ? m : null;
	}

	private TemplateMatch locateCompassSmart(GrayF32 orangeHudImage) {
		int startX = this.compassMatch == null ? COMPASS_REGION_WIDTH / 2 : this.compassMatch.getX() - COMPASS_REGION_X;
		int startY = this.compassMatch == null ? COMPASS_REGION_HEIGHT / 2 : this.compassMatch.getY() - COMPASS_REGION_Y;
		TemplateMatch m = this.locateTemplateInRegionSmart(orangeHudImage, COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT, this.refCompass,
				this.refCompassMask, startX, startY, 0.25f);
		return m.getErrorPerPixel() < 0.25f ? m : null;
	}

	private TemplateMatch locateTargetSmart(GrayF32 orangeHudImage) {
		int startX = this.targetMatch == null ? TARGET_REGION_WIDTH / 2 : this.targetMatch.getX() - TARGET_REGION_X;
		int startY = this.targetMatch == null ? TARGET_REGION_HEIGHT / 2 : this.targetMatch.getY() - TARGET_REGION_Y;
		TemplateMatch m = this.locateTemplateInRegionSmart(orangeHudImage, TARGET_REGION_X, TARGET_REGION_Y, TARGET_REGION_WIDTH, TARGET_REGION_HEIGHT, this.refTarget, this.refTargetMask,
				startX, startY, 0.04f);
		return m.getErrorPerPixel() < 0.04f ? m : null;
	}

	private TemplateMatch locateTemplateInRegion(GrayF32 image, int regionX, int regionY, int regionWidth, int regionHeight, GrayF32 template, GrayF32 mask) {
		GrayF32 subimage = image.subimage(regionX, regionY, regionX + regionWidth, regionY + regionHeight);
		TemplateMatch m = TemplateMatcher.findBestMatchingLocation(subimage, template, mask);
		return m == null ? null : new TemplateMatch(m.getX() + regionX, m.getY() + regionY, m.getWidth(), m.getHeight(), m.getError(), m.getErrorPerPixel());
	}

	private TemplateMatch locateTemplateInRegionSmart(GrayF32 image, int regionX, int regionY, int regionWidth, int regionHeight, GrayF32 template, GrayF32 mask, int startX, int startY) {
		GrayF32 subimage = image.subimage(regionX, regionY, regionX + regionWidth, regionY + regionHeight);
		TemplateMatch m = TemplateMatcher.findBestMatchingLocationSmart(subimage, template, mask, startX, startY);
		return m == null ? null : new TemplateMatch(m.getX() + regionX, m.getY() + regionY, m.getWidth(), m.getHeight(), m.getError(), m.getErrorPerPixel());
	}

	private TemplateMatch locateTemplateInRegionSmart(GrayF32 image, int regionX, int regionY, int regionWidth, int regionHeight, GrayF32 template, GrayF32 mask, int startX, int startY,
			float maxErrorPerPixel) {
		GrayF32 subimage = image.subimage(regionX, regionY, regionX + regionWidth, regionY + regionHeight);
		TemplateMatch m = TemplateMatcher.findBestMatchingLocationSmart(subimage, template, mask, startX, startY, maxErrorPerPixel);
		return m == null ? null : new TemplateMatch(m.getX() + regionX, m.getY() + regionY, m.getWidth(), m.getHeight(), m.getError(), m.getErrorPerPixel());
	}

	private void loadRefImages() {
		// REF IMAGES MUST BE 1080p!!!!
		File refDir = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref");
		try {
			this.refCompass = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_orange.png")), (GrayF32) null));
			this.refCompassMask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_orange_mask.png")), (GrayF32) null));
			this.refCompassDotFilled = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_dot_filled_bluewhite.png")), (GrayF32) null));
			this.refCompassDotHollow = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_dot_hollow_bluewhite.png")), (GrayF32) null));
			this.refTarget = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "target.png")), (GrayF32) null));
			this.refTargetMask = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "target_mask.png")), (GrayF32) null));
			this.refShipHud = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "ship_hud.png")), (GrayF32) null));
			this.refSixSeconds = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "six_seconds.png")), (GrayF32) null));
			this.refScanning = ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "scanning.png")), (GrayF32) null));
		} catch (IOException e) {
			throw new RuntimeException("Failed to load ref images", e);
		}
	}

	private void drawDebugInfoOnImage(BufferedImage debugImage, TemplateMatch compassDotMatch, boolean hollow) {
		Graphics2D g = debugImage.createGraphics();

		g.setColor(new Color(0, 0, 127));
		g.drawRect(COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT);

		if (compassMatch != null) {
			g.setColor(new Color(0, 0, 255));
			g.drawRect(compassMatch.getX(), compassMatch.getY(), compassMatch.getWidth(), compassMatch.getHeight());
			g.drawString(String.format(Locale.US, "%.4f", compassMatch.getErrorPerPixel()), compassMatch.getX(), compassMatch.getY());
			if (compassDotMatch != null) {
				g.setColor(hollow ? Color.RED : Color.GREEN);
				g.drawRect(compassDotMatch.getX(), compassDotMatch.getY(), compassDotMatch.getWidth(), compassDotMatch.getHeight());
				g.drawString(String.format(Locale.US, "%.4f", compassDotMatch.getErrorPerPixel()), compassDotMatch.getX(), compassDotMatch.getY());
			}
		}

		g.setColor(new Color(64, 64, 64));
		g.drawRect(TARGET_REGION_X, TARGET_REGION_Y, TARGET_REGION_WIDTH, TARGET_REGION_HEIGHT);

		if (targetMatch != null) {
			g.setColor(new Color(128, 128, 128));
			g.drawRect(targetMatch.getX(), targetMatch.getY(), targetMatch.getWidth(), targetMatch.getHeight());
			g.drawString(String.format(Locale.US, "%.4f", targetMatch.getErrorPerPixel()), targetMatch.getX(), targetMatch.getY());
		}

		if (sixSecondsMatch != null) {
			g.setColor(Color.YELLOW);
			g.drawRect(sixSecondsMatch.getX(), sixSecondsMatch.getY(), sixSecondsMatch.getWidth(), sixSecondsMatch.getHeight());
			g.drawString(String.format(Locale.US, "%.4f", sixSecondsMatch.getErrorPerPixel()), sixSecondsMatch.getX(), sixSecondsMatch.getY());
		}

		if (scanningMatch != null) {
			g.setColor(Color.RED);
			g.drawRect(scanningMatch.getX(), scanningMatch.getY(), scanningMatch.getWidth(), scanningMatch.getHeight());
			g.drawString(String.format(Locale.US, "%.4f", scanningMatch.getErrorPerPixel()), scanningMatch.getX(), scanningMatch.getY());
		}

		g.setColor(Color.BLACK);
		g.fillRect(debugImage.getWidth() - 20, 0, 20, debugImage.getHeight());
		if (brightnessAhead > 0) {
			int brightnessRed = 127 + (int) (128.0f * brightnessAhead);
			int brightnessHeight = (int) (brightnessAhead * debugImage.getHeight());
			g.setColor(new Color(brightnessRed, 127, 127));
			g.fillRect(debugImage.getWidth() - 20, debugImage.getHeight() - brightnessHeight, 20, brightnessHeight);
		}
		g.setColor(Color.YELLOW);
		g.drawString(String.format(Locale.US, "%.0f%%", brightnessAhead * 100), debugImage.getWidth() - 25, 25);

		long millis = System.currentTimeMillis() - this.lastTick;
		double fps = 1000.0 / Math.max(1, millis);
		g.setColor(Color.YELLOW);
		g.setFont(new Font("Sans Serif", Font.BOLD, 20));
		g.drawString(String.format(Locale.US, "%.2f FPS / %s", fps, this.gameState), 10, 30);
		g.drawString(String.format(Locale.US, "x=%d%% / y=%d%%", this.xPercent, this.yPercent), 10, 60);
		g.drawString(String.format(Locale.US, "pitchUp=%d%% / pitchDown=%d%%", this.shipControl.getPitchUp(), this.shipControl.getPitchDown()), 10, 90);
		g.drawString(String.format(Locale.US, "rollRight=%d%% / rollLeft=%d%%", this.shipControl.getRollRight(), this.shipControl.getRollLeft()), 10, 120);
		g.drawString(String.format(Locale.US, "yawRight=%d%% / yawLeft=%d%%", this.shipControl.getYawRight(), this.shipControl.getYawLeft()), 10, 150);
		g.drawString(String.format(Locale.US, "throttle=%d%%", this.shipControl.getThrottle()), 10, 180);
		g.drawString(String.format(Locale.US, "fuel=%.1ft", this.fuelLevel), 10, 210);
		g.drawString(String.format(Locale.US, "system=%s", this.currentSystemName), 10, 240);
		StringBuilder sbKnownValuableBodies = new StringBuilder();
		for (Body body : this.knownValuableBodies) {
			sbKnownValuableBodies.append(String.format(Locale.US, " | %s (%,d CR)", body.getName().replace(this.currentSystemName, "").trim(), BodyUtil.estimatePayout(body)));
		}
		g.drawString(String.format(Locale.US, "knownBodies=%d%s", this.discoveredBodiesInSystem, sbKnownValuableBodies), 10, 270);
		StringBuilder sbGuessedBodies = new StringBuilder();
		if (this.sysmapScannerResult != null) {
			for (TemplateMatchRgb tm : this.sysmapScannerResult.getSystemMapScreenCoords().keySet()) {
				sbGuessedBodies.append(String.format(Locale.US, " | %s", tm.getTemplate().getName()));
			}
		}
		g.drawString(String.format(Locale.US, "guessedBodies=%d%s", this.sysmapScannerResult == null ? 0 : this.sysmapScannerResult.getSystemMapScreenCoords().size(), sbGuessedBodies), 10,
				300);

		g.dispose();
	}

	private void drawColoredDebugImage(BufferedImage debugImage, GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
		for (int y = 0; y < debugImage.getHeight(); y++) {
			for (int x = 0; x < debugImage.getWidth(); x++) {
				float r = redHudImage.unsafe_get(x, y) * 255;
				float bw = blueWhiteHudImage.unsafe_get(x, y) * 255;
				float o = orangeHudImage.unsafe_get(x, y) * 255;
				float b = brightImage.unsafe_get(x, y) * 255;
				if (r > 0) {
					debugImage.setRGB(x, y, new Color((int) r, (int) (r * 0.15f), (int) (r * 0.15f)).getRGB());
				} else if (bw > 0) {
					debugImage.setRGB(x, y, new Color((int) (bw * 0.66f), (int) (bw * 0.66f), (int) bw).getRGB());
				} else if (o > 0) {
					debugImage.setRGB(x, y, new Color((int) o, (int) (o * 0.5f), 0).getRGB());
				} else if (b > 0) {
					debugImage.setRGB(x, y, new Color((int) b, (int) b, (int) b).getRGB());
				} else {
					debugImage.setRGB(x, y, new Color(0, 0, 0).getRGB());
				}
			}
		}
	}

	public void addDebugImageListener(DebugImageListener listener) {
		if (listener != null && !this.debugImageListeners.contains(listener)) {
			this.debugImageListeners.add(listener);
		}
	}

	public void removeDebugImageListener(DebugImageListener listener) {
		if (listener != null) {
			this.debugImageListeners.remove(listener);
		}
	}

	@Override
	public void onNewStatus(Status status) {
		if (status == null) {
			logger.warn("null status");
		} else {
			logger.debug("New status: " + status);

			if (status.isLowFuel()) {
				this.doEmergencyExit("Low fuel");
			} else if (status.isInDanger()) {
				this.doEmergencyExit("In danger");
			} else if (!status.isInSupercruise()) {
				this.doEmergencyExit("Dropped from supercruise");
			}

			if (status.isFsdCooldown() && !this.fsdCooldown && this.gameState == GameState.WAIT_FOR_FSD_COOLDOWN) {
				this.gameState = GameState.GET_IN_SCOOPING_RANGE;
				logger.debug("FSD cooldown started, getting in scooping range");
			}

			if (status.isFsdCharging() && !this.fsdCharging) {
				this.fsdChargingSince = System.currentTimeMillis();
			} else if (!status.isFsdCharging() && this.fsdCharging) {
				this.fsdChargingSince = Long.MAX_VALUE;
			}

			this.scoopingFuel = status.isScoopingFuel();
			this.fsdCooldown = status.isFsdCooldown();
			this.fsdCharging = status.isFsdCharging();
		}
	}

	@Override
	public void onNewJournalEntry(AbstractJournalEvent event) {
		if (event instanceof StartJumpEvent) {
			this.shipControl.stopTurning();
			this.inSupercruiseSince = Long.MAX_VALUE;
			this.inHyperspaceSince = System.currentTimeMillis();
			this.currentSystemName = ((StartJumpEvent) event).getStarSystem();
			this.knownValuableBodies = this.universeService.findBodiesByStarSystemName(((StartJumpEvent) event).getStarSystem()).stream()
					.filter(b -> b.getDistanceToArrival().longValue() < 10000 && BodyUtil.estimatePayout(b) >= 100000)
					.sorted((b1, b2) -> (b1.getName().toLowerCase().compareTo(b2.getName().toLowerCase()))).collect(Collectors.toList());
			this.discoveredBodiesInSystem = 0;
			this.sysmapScannerResult = null;
			this.gameState = GameState.IN_HYPERSPACE;
			logger.debug("Jumping through hyperspace to " + ((StartJumpEvent) event).getStarSystem());
		} else if (event instanceof FSDJumpEvent) {
			this.loadRefImages();
			this.fuelLevel = ((FSDJumpEvent) event).getFuelLevel().floatValue();
			this.inHyperspaceSince = Long.MAX_VALUE;
			this.inSupercruiseSince = System.currentTimeMillis();
			this.shipControl.honkDelayed(1000);
			this.gameState = GameState.WAIT_FOR_FSD_COOLDOWN;
			logger.debug("Arrived at " + ((FSDJumpEvent) event).getStarSystem() + ", honking and waiting for FSD cooldown to start");
		} else if (event instanceof FuelScoopEvent) {
			this.fuelLevel = ((FuelScoopEvent) event).getTotal().floatValue();
		} else if (event instanceof DiscoveryScanEvent) {
			this.discoveredBodiesInSystem += MiscUtil.getAsInt(((DiscoveryScanEvent) event).getBodies(), 0);
		} else if (event instanceof ScanEvent) {
			if (this.currentBodyTemplateMatch != null) {
				this.shipControl.setThrottle(0);
				this.shipControl.stopTurning();
				String realPlanetClass = ((ScanEvent) event).getPlanetClass();
				String guessedPlanetClass = this.currentBodyTemplateMatch.getTemplate().getName();
				if (StringUtils.isNotEmpty(realPlanetClass) && !realPlanetClass.equals(guessedPlanetClass)) {
					logger.warn("Wrongly guessed " + guessedPlanetClass + ", but was " + realPlanetClass);
					try {
						BufferedImage planetImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(this.currentBodyTemplateMatch.getImage()), null, true);
						File refFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref/sysMapPlanets/" + realPlanetClass);
						if (!refFolder.exists()) {
							refFolder.mkdirs();
						}
						final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());
						ImageIO.write(planetImage, "PNG", new File(refFolder, ts + " " + realPlanetClass + " " + this.currentSystemName + ".png"));
						this.loadRefImages();
					} catch (IOException e) {
						logger.warn("Failed to write planet ref image", e);
					}
				} else {
					logger.info("Correctly guessed " + guessedPlanetClass + ", and was " + realPlanetClass);
				}

				this.currentBodyTemplateMatch = null;
				this.knownValuableBodies = this.knownValuableBodies.stream().filter(b -> !b.getName().equals(((ScanEvent) event).getBodyName())).collect(Collectors.toList());
				if (this.sysmapScannerResult.getSystemMapScreenCoords().isEmpty()) {
					this.shipControl.setThrottle(100);
					this.shipControl.selectNextSystemInRoute();
					this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
					logger.debug("All bodies scanned, aligning to next jump target at 100% throttle");
				} else {
					this.shipControl.setThrottle(0);
					this.shipControl.stopTurning();
					this.shipControl.toggleSystemMap();
					this.gameState = GameState.WAIT_FOR_SYSTEM_MAP;
					logger.debug(((ScanEvent) event).getBodyName() + " scanned, waiting for system map at stand-still");
				}
			}
		}
	}

	private void clickOnNextBodyOnSystemMap() {
		this.currentBodyTemplateMatch = this.sysmapScannerResult.getSystemMapScreenCoords().keySet().iterator().next();
		ScreenCoord scaledCoords = this.sysmapScannerResult.getSystemMapScreenCoords().remove(this.currentBodyTemplateMatch);
		int screenX = Math.round(scaledCoords.x * (2560f / 1920f)) + ((3440 - 2560) / 2); // FIXME
		int screenY = Math.round(scaledCoords.y * (1440f / 1080f)); // FIXME

		try {
			Thread.sleep(1000);
			this.robot.mouseMove(screenX, screenY);
			Thread.sleep(100);
			this.robot.mouseMove(screenX + 1, screenY + 1);
			Thread.sleep(500);
			this.robot.mousePress(InputEvent.getMaskForButton(1));
			Thread.sleep(100);
			this.robot.mouseRelease(InputEvent.getMaskForButton(1));
			Thread.sleep(2000);
			this.robot.mouseMove(3440 / 2, 1440 / 2); // FIXME
			Thread.sleep(500);
			this.robot.keyPress(KeyEvent.VK_ENTER);
			Thread.sleep(100);
			this.robot.keyRelease(KeyEvent.VK_ENTER);
			Thread.sleep(200);
			this.robot.keyPress(KeyEvent.VK_NUMPAD6);
			Thread.sleep(100);
			this.robot.keyRelease(KeyEvent.VK_NUMPAD6);
			Thread.sleep(200);
			this.robot.keyPress(KeyEvent.VK_NUMPAD4);
			Thread.sleep(500);
			this.robot.keyRelease(KeyEvent.VK_NUMPAD4);
			Thread.sleep(200);
			this.robot.keyPress(KeyEvent.VK_ENTER);
			Thread.sleep(100);
			this.robot.keyRelease(KeyEvent.VK_ENTER);
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			logger.warn("Interrupted while clicked on system map", e);
		}
	}

}
