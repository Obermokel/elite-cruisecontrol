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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.sysmap.SensibleScanOrderComparator;
import borg.ed.cruisecontrol.sysmap.SysmapBody;
import borg.ed.cruisecontrol.sysmap.SysmapScanner;
import borg.ed.cruisecontrol.sysmap.SysmapScannerResult;
import borg.ed.cruisecontrol.templatematching.Template;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
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
import borg.ed.universe.journal.events.MusicEvent;
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
    private SysmapScanner sysmapScanner = null;

    private Template refCompass = null;
    private Template refCompassDotFilled = null;
    private Template refCompassDotHollow = null;
    private Template refTarget = null;
    private Template refShipHud = null;
    private Template refSixSeconds = null;
    private Template refScanning = null;

    private GameState gameState = GameState.UNKNOWN;
    private int xPercent = 0;
    private int yPercent = 0;
    private float brightnessAhead = 0;
    private boolean scoopingFuel = false;
    private boolean fsdCooldown = false;
    private float fuelLevel = 0;
    private long inHyperspaceSince = Long.MAX_VALUE; // Timestamp when the FSD was charged and the countdown started
    private long escapingFromStarSince = Long.MAX_VALUE;
    private boolean fsdCharging = false;
    private long fsdChargingSince = Long.MAX_VALUE;
    private long getInScoopingRangeSince = Long.MAX_VALUE;
    private String currentSystemName = "";
    private List<Body> currentSystemKnownBodies = new ArrayList<>();
    private List<ScanEvent> currentSystemScannedBodies = new ArrayList<>();
    private int currentSystemNumDiscoveredBodies = 0;
    private SysmapBody currentSysmapBody = null;
    private SysmapScannerResult sysmapScannerResult = null;
    private float lastScannedBodyDistanceFromArrival = 0;
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

        this.sysmapScanner = new SysmapScanner(robot, screenRect, screenConverterResult);
        this.sysmapScanner.setWriteDebugImageRgbOriginal(CruiseControlApplication.WRITE_SYSMAP_DEBUG_RGB_ORIGINAL);
        this.sysmapScanner.setWriteDebugImageGray(CruiseControlApplication.WRITE_SYSMAP_DEBUG_GRAY);
        this.sysmapScanner.setWriteDebugImageThreshold(CruiseControlApplication.WRITE_SYSMAP_DEBUG_THRESHOLD);
        this.sysmapScanner.setWriteDebugImageRgbResult(CruiseControlApplication.WRITE_SYSMAP_DEBUG_RGB_RESULT);
        this.sysmapScanner.setWriteDebugImageBodyRgbOriginal(CruiseControlApplication.WRITE_SYSMAP_DEBUG_BODY_RGB_ORIGINAL);
        this.sysmapScanner.setWriteDebugImageBodyGray(CruiseControlApplication.WRITE_SYSMAP_DEBUG_BODY_GRAY);
        this.sysmapScanner.setWriteDebugImageBodyRgbResult(CruiseControlApplication.WRITE_SYSMAP_DEBUG_BODY_RGB_RESULT);

        Planar<GrayF32> rgb = new Planar<>(GrayF32.class, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, 3);
        Planar<GrayF32> hsv = rgb.createSameShape();
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
                synchronized (screenConverterResult) {
                    try {
                        screenConverterResult.wait();
                        rgb = screenConverterResult.getRgb().clone();
                        hsv = screenConverterResult.getHsv().clone();
                        orangeHudImage = screenConverterResult.getOrangeHudImage().clone();
                        blueWhiteHudImage = screenConverterResult.getBlueWhiteHudImage().clone();
                        redHudImage = screenConverterResult.getRedHudImage().clone();
                        brightImage = screenConverterResult.getBrightImage().clone();
                    } catch (InterruptedException e) {
                        this.doEmergencyExit("Interrupted while waiting for screen converter result, exiting main loop");
                        break;
                    }
                }
                // <<<< SCREEN CAPTURE <<<<

                // >>>> PROCESSING THE DATA >>>>
                brightnessAhead = this.computeBrightnessAhead(brightImage);
                TemplateMatch compassDotMatch = this.searchForCompassAndTarget(orangeHudImage, blueWhiteHudImage, threadPool);
                this.searchForSixSecondsOrScanning(orangeHudImage, brightImage);
                this.handleGameState(rgb, hsv, orangeHudImage, compassDotMatch);
                // <<<< PROCESSING THE DATA <<<<

                // >>>> DEBUG IMAGE >>>>
                if (CruiseControlApplication.SHOW_LIVE_DEBUG_IMAGE) {
                    this.drawColoredDebugImage(debugImage, orangeHudImage, blueWhiteHudImage, redHudImage, brightImage);
                    this.drawDebugInfoOnImage(debugImage, compassDotMatch);
                    for (DebugImageListener listener : this.debugImageListeners) {
                        listener.onNewDebugImage(debugImage, orangeHudImage, blueWhiteHudImage, redHudImage, brightImage);
                    }
                }
                // <<<< DEBUG IMAGE <<<<
            } catch (Exception e) {
                logger.error(this.getName() + " crashed", e);
                break; // Quit
            }
        }

        logger.info(this.getName() + " stopped");
    }

    private TemplateMatch searchForCompassAndTarget(GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, final ExecutorService threadPool) throws InterruptedException, ExecutionException {
        compassMatch = null;
        targetMatch = null;
        if (gameState == GameState.UNKNOWN || gameState == GameState.ALIGN_TO_NEXT_SYSTEM || gameState == GameState.FSD_CHARGING || gameState == GameState.ALIGN_TO_NEXT_BODY
                || gameState == GameState.APPROACH_NEXT_BODY) {
            final GrayF32 myOrangeHudImage = orangeHudImage.clone(); // TODO Why is this cloned?
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

        TemplateMatch compassDotMatch = null;
        if (compassMatch != null) {
            int xOffset = compassMatch.getX() - 16;
            int yOffset = compassMatch.getY() - 16;
            int regionWidth = compassMatch.getWidth() + 32;
            int regionHeight = compassMatch.getHeight() + 32;
            //GrayF32 grayCompass = blueWhiteHudImage.subimage(xOffset, yOffset, xOffset + regionWidth, yOffset + regionHeight);
            TemplateMatch compassDotFilledMatch = TemplateMatcher.findBestMatchingLocationInRegion(blueWhiteHudImage, xOffset, yOffset, regionWidth, regionHeight, this.refCompassDotFilled);
            if (compassDotFilledMatch.getErrorPerPixel() >= 0.05f) {
                compassDotFilledMatch = null;
            }
            TemplateMatch compassDotHollowMatch = TemplateMatcher.findBestMatchingLocationInRegion(blueWhiteHudImage, xOffset, yOffset, regionWidth, regionHeight, this.refCompassDotHollow);
            if (compassDotHollowMatch.getErrorPerPixel() >= 0.05f) {
                compassDotHollowMatch = null;
            }
            if (compassDotFilledMatch == null && compassDotHollowMatch == null) {
                // Not found
            } else if ((compassDotFilledMatch != null && compassDotHollowMatch == null)
                    || (compassDotFilledMatch != null && compassDotHollowMatch != null && compassDotFilledMatch.getErrorPerPixel() <= compassDotHollowMatch.getErrorPerPixel())) {
                compassDotMatch = compassDotFilledMatch;
            } else if ((compassDotFilledMatch == null && compassDotHollowMatch != null)
                    || (compassDotFilledMatch != null && compassDotHollowMatch != null && compassDotFilledMatch.getErrorPerPixel() >= compassDotHollowMatch.getErrorPerPixel())) {
                compassDotMatch = compassDotHollowMatch;
            }
        }
        return compassDotMatch;
    }

    private void searchForSixSecondsOrScanning(GrayF32 orangeHudImage, GrayF32 brightImage) {
        if (gameState == GameState.APPROACH_NEXT_BODY) {
            if (targetMatch == null) {
                sixSecondsMatch = null;
            } else {
                int sixSecondsX = targetMatch.getX() + 90;
                int sixSecondsY = targetMatch.getY() + 75;
                sixSecondsMatch = TemplateMatcher.findBestMatchingLocationInRegion(brightImage, sixSecondsX, sixSecondsY, 82, 28, this.refSixSeconds);
                if (sixSecondsMatch.getErrorPerPixel() > 0.15f) {
                    sixSecondsMatch = null;
                }
            }

            scanningMatch = TemplateMatcher.findBestMatchingLocationInRegion(orangeHudImage, 2, 860, 180, 110, this.refScanning);
            if (scanningMatch.getErrorPerPixel() > 0.075f) {
                scanningMatch = null;
            }
        } else {
            sixSecondsMatch = null;
            scanningMatch = null;
        }
    }

    private void handleGameState(Planar<GrayF32> rgb, Planar<GrayF32> hsv, GrayF32 orangeHudImage, TemplateMatch compassDotMatch) throws InterruptedException {
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
                    this.alignToTargetInCompass(compassMatch, compassDotMatch);
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
                } else {
                    if (System.currentTimeMillis() - getInScoopingRangeSince > 10000) {
                        logger.warn("Did not scoop any fuel for 10 seconds, aligning to star escape now");
                        this.shipControl.setThrottle(0);
                        this.gameState = GameState.ALIGN_TO_STAR_ESCAPE;
                    }
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
                    if (this.currentSystemNumDiscoveredBodies > 1 && !CruiseControlApplication.JONK_MODE) {
                        this.shipControl.setThrottle(0);
                        logger.debug("Open system map");
                        this.robot.mouseMove(1, 1);
                        this.shipControl.toggleSystemMap();
                        this.gameState = GameState.SCAN_SYSTEM_MAP;
                        logger.debug("Escaped from entry star, " + this.currentSystemNumDiscoveredBodies + " bodies discovered, throttle to 0% and scan system map");
                    } else {
                        this.shipControl.setThrottle(100);
                        this.shipControl.selectNextSystemInRoute();
                        this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
                        if (CruiseControlApplication.JONK_MODE) {
                            logger.debug("Escaped from entry star, jonk mode, aligning to next jump target at 100% throttle");
                        } else {
                            logger.debug("Escaped from entry star, no other bodies discovered, aligning to next jump target at 100% throttle");
                        }
                    }
                }
                break;
            case SCAN_SYSTEM_MAP:
                this.sysmapScannerResult = this.sysmapScanner.scanSystemMap(rgb, hsv, this.currentSystemName);
                if (this.sysmapScannerResult != null) {
                    if (this.nextBodyToScan() == null) {
                        // Close sysmap, then throttle up and go to next system in route
                        logger.debug("Close system map");
                        this.shipControl.toggleSystemMap();
                        Thread.sleep(1000);
                        this.shipControl.setThrottle(100);
                        this.shipControl.selectNextSystemInRoute();
                        this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
                        logger.debug("System map scanned, no body to scan, aligning to next jump target at 100% throttle");
                    } else {
                        // Select body from sysmap, then close map and wait for ship HUD
                        this.clickOnNextBodyOnSystemMap();
                        logger.debug("Close system map");
                        this.shipControl.toggleSystemMap();
                        this.shipControl.setThrottle(0);
                        this.gameState = GameState.WAIT_FOR_SHIP_HUD;
                        logger.debug("System map scanned, waiting for ship HUD at 0% throttle");
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
                        if (this.alignToTargetInCompass(compassMatch, compassDotMatch)) {
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
                        this.alignToTargetInCompass(compassMatch, compassDotMatch);
                    }
                }
                break;
            case WAIT_FOR_SYSTEM_MAP:
                if (this.sysmapScanner.isUniversalCartographicsLogoVisible(rgb)) {
                    this.clickOnNextBodyOnSystemMap();
                    logger.debug("Close system map");
                    this.shipControl.toggleSystemMap();
                    this.shipControl.setThrottle(0);
                    this.gameState = GameState.WAIT_FOR_SHIP_HUD;
                    logger.debug("Clicked on next body, waiting for ship HUD at 0% throttle");
                }
                break;
            case WAIT_FOR_SHIP_HUD:
                if (this.isShipHudVisible(orangeHudImage)) {
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
                    if (this.alignToTargetInCompass(compassMatch, compassDotMatch)) {
                        this.shipControl.toggleFsd();
                        this.gameState = GameState.FSD_CHARGING;
                        logger.debug("Next system in sight, charging FSD");
                    }
                }
                break;
            case IN_EMERGENCY_EXIT:
                logger.debug("In emergency exit...");
                break;
            default:
                this.doEmergencyExit("Unknown game state " + this.gameState);
                break;
        }
    }

    private boolean isShipHudVisible(GrayF32 orangeHudImage) {
        return TemplateMatcher.findBestMatchingLocation(orangeHudImage.subimage(1650, 900, 1900, 1050), this.refShipHud).getErrorPerPixel() <= 0.075f;
    }

    private void doEmergencyExit(String reason) {
        synchronized (inEmergencyExit) {
            if (Boolean.FALSE.equals(inEmergencyExit)) {
                inEmergencyExit = Boolean.TRUE;

                try {
                    logger.error("Emergency exit! Reason: " + reason);
                    this.gameState = GameState.IN_EMERGENCY_EXIT;

                    // Terminate all event-generating threads
                    Thread[] tarray = new Thread[Thread.activeCount() + 100];
                    Thread.enumerate(tarray);
                    for (Thread t : tarray) {
                        if (t instanceof JournalReaderThread) {
                            ((JournalReaderThread) t).shutdown = true;
                        } else if (t instanceof StatusReaderThread) {
                            ((StatusReaderThread) t).shutdown = true;
                        } else if (t instanceof ScreenConverterThread) {
                            ((ScreenConverterThread) t).shutdown = true;
                        } else if (t instanceof ScreenReaderThread) {
                            ((ScreenReaderThread) t).shutdown = true;
                        }
                    }

                    // Give them some time to terminate
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        logger.warn("Interrupted while waiting after stopping threads");
                    }

                    // Full stop, then exit
                    this.robot.mouseMove(1, 1);
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

    private boolean alignToTargetInCompass(TemplateMatch compassMatch, TemplateMatch compassDotMatch) {
        if (compassMatch == null || compassDotMatch == null) {
            this.shipControl.stopTurning();
        } else {
            boolean hollow = compassDotMatch.getTemplate().getName().contains("hollow");
            int width = compassMatch.getWidth();
            int height = compassMatch.getHeight();
            int x = (compassDotMatch.getX() - compassMatch.getX()) + (compassDotMatch.getWidth() / 2);
            int y = (compassDotMatch.getY() - compassMatch.getY()) + (compassDotMatch.getHeight() / 2);
            xPercent = (x * 100) / width;
            yPercent = (y * 100) / height;

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

    private boolean alignToEscapeInCompass(TemplateMatch compassMatch, TemplateMatch compassDotMatch) {
        if (compassMatch == null || compassDotMatch == null) {
            this.shipControl.stopTurning();
        } else {
            boolean hollow = compassDotMatch.getTemplate().getName().contains("hollow");
            int width = compassMatch.getWidth();
            int height = compassMatch.getHeight();
            int x = (compassDotMatch.getX() - compassMatch.getX()) + (compassDotMatch.getWidth() / 2);
            int y = (compassDotMatch.getY() - compassMatch.getY()) + (compassDotMatch.getHeight() / 2);
            xPercent = (x * 100) / width;
            yPercent = (y * 100) / height;

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
        TemplateMatch m = TemplateMatcher.findBestMatchingLocationInRegion(orangeHudImage, COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT, this.refCompass);
        return m.getErrorPerPixel() < 0.15f ? m : null;
    }

    private TemplateMatch locateCompassSmart(GrayF32 orangeHudImage) {
        int startX = this.compassMatch == null ? COMPASS_REGION_WIDTH / 2 : this.compassMatch.getX() - COMPASS_REGION_X;
        int startY = this.compassMatch == null ? COMPASS_REGION_HEIGHT / 2 : this.compassMatch.getY() - COMPASS_REGION_Y;
        TemplateMatch m = TemplateMatcher.findBestMatchingLocationInRegionSmart(orangeHudImage, COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT,
                this.refCompass, startX, startY, 0.25f);
        return m.getErrorPerPixel() < 0.25f ? m : null;
    }

    private TemplateMatch locateTargetSmart(GrayF32 orangeHudImage) {
        int startX = this.targetMatch == null ? TARGET_REGION_WIDTH / 2 : this.targetMatch.getX() - TARGET_REGION_X;
        int startY = this.targetMatch == null ? TARGET_REGION_HEIGHT / 2 : this.targetMatch.getY() - TARGET_REGION_Y;
        TemplateMatch m = TemplateMatcher.findBestMatchingLocationInRegionSmart(orangeHudImage, TARGET_REGION_X, TARGET_REGION_Y, TARGET_REGION_WIDTH, TARGET_REGION_HEIGHT, this.refTarget,
                startX, startY, 0.04f);
        return m.getErrorPerPixel() < 0.04f ? m : null;
    }

    private void loadRefImages() {
        // REF IMAGES MUST BE 1080p!!!!
        File refDir = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref");
        try {
            this.refCompass = Template.fromFile(new File(refDir, "compass_orange.png"));
            this.refCompassDotFilled = Template.fromFile(new File(refDir, "compass_dot_filled_bluewhite.png"));
            this.refCompassDotHollow = Template.fromFile(new File(refDir, "compass_dot_hollow_bluewhite.png"));
            this.refTarget = Template.fromFile(new File(refDir, "target.png"));
            this.refShipHud = Template.fromFile(new File(refDir, "ship_hud.png"));
            this.refSixSeconds = Template.fromFile(new File(refDir, "six_seconds.png"));
            this.refScanning = Template.fromFile(new File(refDir, "scanning.png"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ref images", e);
        }
    }

    private void drawDebugInfoOnImage(BufferedImage debugImage, TemplateMatch compassDotMatch) {
        Graphics2D g = debugImage.createGraphics();

        g.setColor(new Color(0, 0, 127));
        g.drawRect(COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT);

        if (compassMatch != null) {
            g.setColor(new Color(0, 0, 255));
            g.drawRect(compassMatch.getX(), compassMatch.getY(), compassMatch.getWidth(), compassMatch.getHeight());
            g.drawString(String.format(Locale.US, "%.4f", compassMatch.getErrorPerPixel()), compassMatch.getX(), compassMatch.getY());
            if (compassDotMatch != null) {
                g.setColor(compassDotMatch.getTemplate().getName().contains("hollow") ? Color.RED : Color.GREEN);
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
        g.setColor(Color.GREEN);
        g.setFont(new Font("Sans Serif", Font.BOLD, 20));
        g.drawString(String.format(Locale.US, "%.2f FPS / %s", fps, this.gameState), 10, 30);
        g.drawString(String.format(Locale.US, "x=%d%% / y=%d%%", this.xPercent, this.yPercent), 10, 60);
        g.drawString(String.format(Locale.US, "pitchUp=%d%% / pitchDown=%d%%", this.shipControl.getPitchUp(), this.shipControl.getPitchDown()), 10, 90);
        g.drawString(String.format(Locale.US, "rollRight=%d%% / rollLeft=%d%%", this.shipControl.getRollRight(), this.shipControl.getRollLeft()), 10, 120);
        g.drawString(String.format(Locale.US, "yawRight=%d%% / yawLeft=%d%%", this.shipControl.getYawRight(), this.shipControl.getYawLeft()), 10, 150);
        g.drawString(String.format(Locale.US, "throttle=%d%%", this.shipControl.getThrottle()), 10, 180);
        g.drawString(String.format(Locale.US, "fuel=%.1ft", this.fuelLevel), 10, 210);
        // system=Name ab-c d10
        // known=9 | 1x ELW | 1x WW-TF | 2x HMC | 1x M | 4x Icy
        // guessed=9 | 1x ELW | 1x WW-TF | 1x HMC-TF | 1x HMC | 1x M | 4x Icy
        // scanned=1/9 | 1x ELW
        g.drawString(String.format(Locale.US, "system=%s", this.currentSystemName), 10, 240);
        StringBuilder sbKnown = new StringBuilder("known=").append(this.currentSystemKnownBodies.size());
        if (!this.currentSystemKnownBodies.isEmpty()) {
            List<Body> bodiesSortedByValue = this.currentSystemKnownBodies.stream()
                    .sorted((b1, b2) -> -1 * new Long(BodyUtil.estimatePayout(b1)).compareTo(new Long(BodyUtil.estimatePayout(b2)))).collect(Collectors.toList());
            LinkedHashMap<String, Integer> countByType = new LinkedHashMap<>();
            for (Body b : bodiesSortedByValue) {
                String abbrType = BodyUtil.getAbbreviatedType(b);
                countByType.put(abbrType, countByType.getOrDefault(abbrType, 0) + 1);
            }
            for (String abbrType : countByType.keySet()) {
                sbKnown.append(String.format(Locale.US, " | %dx %s", countByType.get(abbrType), abbrType));
            }
        }
        g.drawString(sbKnown.toString(), 10, 270);
        StringBuilder sbGuessed = new StringBuilder("guessed=").append(this.sysmapScannerResult == null ? 0 : this.sysmapScannerResult.getBodies().size());
        if (this.sysmapScannerResult != null && !this.sysmapScannerResult.getBodies().isEmpty()) {
            List<SysmapBody> bodiesSortedByValue = this.sysmapScannerResult.getBodies().stream()
                    .sorted((b1, b2) -> -1 * new Long(SysmapBody.estimatePayout(b1)).compareTo(new Long(SysmapBody.estimatePayout(b2)))).collect(Collectors.toList());
            LinkedHashMap<String, Integer> countByType = new LinkedHashMap<>();
            for (SysmapBody b : bodiesSortedByValue) {
                String abbrType = SysmapBody.getAbbreviatedType(b);
                countByType.put(abbrType, countByType.getOrDefault(abbrType, 0) + 1);
            }
            for (String abbrType : countByType.keySet()) {
                sbGuessed.append(String.format(Locale.US, " | %dx %s", countByType.get(abbrType), abbrType));
            }
        }
        g.drawString(sbGuessed.toString(), 10, 300);
        StringBuilder sbScanned = new StringBuilder("scanned=").append(this.currentSystemScannedBodies.size()).append("/").append(this.currentSystemNumDiscoveredBodies);
        if (!this.currentSystemScannedBodies.isEmpty()) {
            List<ScanEvent> bodiesSortedByValue = this.currentSystemScannedBodies.stream()
                    .sorted((b1, b2) -> -1 * new Long(BodyUtil.estimatePayout(b1)).compareTo(new Long(BodyUtil.estimatePayout(b2)))).collect(Collectors.toList());
            LinkedHashMap<String, Integer> countByType = new LinkedHashMap<>();
            for (ScanEvent e : bodiesSortedByValue) {
                String abbrType = BodyUtil.getAbbreviatedType(e);
                countByType.put(abbrType, countByType.getOrDefault(abbrType, 0) + 1);
            }
            for (String abbrType : countByType.keySet()) {
                sbScanned.append(String.format(Locale.US, " | %dx %s", countByType.get(abbrType), abbrType));
            }
        }
        g.drawString(sbScanned.toString(), 10, 330);

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
            if (status.isLowFuel()) {
                this.doEmergencyExit("Low fuel");
            } else if (status.isInDanger()) {
                this.doEmergencyExit("In danger");
            } else if (!status.isInSupercruise()) {
                this.doEmergencyExit("Dropped from supercruise");
            }

            if (status.isFsdCooldown() && !this.fsdCooldown && this.gameState == GameState.WAIT_FOR_FSD_COOLDOWN) {
                this.gameState = GameState.GET_IN_SCOOPING_RANGE;
                this.getInScoopingRangeSince = System.currentTimeMillis();
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
        if (event instanceof MusicEvent) {
            if (MusicEvent.TRACK_DESTINATION_FROM_HYPERSPACE.equals(((MusicEvent) event).getMusicTrack()) && event.getTimestamp().isAfter(ZonedDateTime.now().minusMinutes(1))) {
                this.doEmergencyExit("End of plotted route");
            }
        } else if (event instanceof StartJumpEvent) {
            this.shipControl.stopTurning();
            this.inHyperspaceSince = System.currentTimeMillis();
            this.currentSystemName = "";
            this.currentSystemKnownBodies = new ArrayList<>();
            this.currentSystemScannedBodies = new ArrayList<>();
            this.currentSystemNumDiscoveredBodies = 0;
            this.sysmapScannerResult = null;
            this.lastScannedBodyDistanceFromArrival = 0;
            this.gameState = GameState.IN_HYPERSPACE;
            logger.debug("Jumping through hyperspace to " + ((StartJumpEvent) event).getStarSystem());
        } else if (event instanceof FSDJumpEvent) {
            this.loadRefImages();
            this.fuelLevel = ((FSDJumpEvent) event).getFuelLevel().floatValue();
            this.inHyperspaceSince = Long.MAX_VALUE;
            this.shipControl.honkDelayed(1000);
            this.currentSystemName = ((FSDJumpEvent) event).getStarSystem();
            this.currentSystemKnownBodies = this.universeService.findBodiesByStarSystemName(((FSDJumpEvent) event).getStarSystem());
            this.gameState = GameState.WAIT_FOR_FSD_COOLDOWN;
            logger.debug("Arrived at " + ((FSDJumpEvent) event).getStarSystem() + ", honking and waiting for FSD cooldown to start");
        } else if (event instanceof FuelScoopEvent) {
            this.fuelLevel = ((FuelScoopEvent) event).getTotal().floatValue();
        } else if (event instanceof DiscoveryScanEvent) {
            this.currentSystemNumDiscoveredBodies += MiscUtil.getAsInt(((DiscoveryScanEvent) event).getBodies(), 0);
        } else if (event instanceof ScanEvent) {
            this.lastScannedBodyDistanceFromArrival = MiscUtil.getAsFloat(((ScanEvent) event).getDistanceFromArrivalLS(), 0f);
            if (this.currentSysmapBody != null) {
                this.shipControl.setThrottle(0);
                this.shipControl.stopTurning();
                this.currentSysmapBody.unexplored = false;
                this.currentSystemScannedBodies.add((ScanEvent) event);

                // Learn
                if (this.currentSysmapBody.bestBodyMatch != null) {
                    String scannedBodyType = ((ScanEvent) event).getPlanetClass();
                    if (StringUtils.isEmpty(scannedBodyType)) {
                        scannedBodyType = ((ScanEvent) event).getStarType();
                    }
                    String guessedBodyType = this.currentSysmapBody.bestBodyMatch.getTemplate().getName();
                    if (StringUtils.isNotEmpty(scannedBodyType) && !scannedBodyType.equals(guessedBodyType)) {
                        logger.warn("Wrongly guessed " + guessedBodyType + ", but was " + scannedBodyType);
                        try {
                            BufferedImage planetImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(this.currentSysmapBody.bestBodyMatch.getImage()), null, true);
                            File refFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref/sysmapBodies/" + scannedBodyType);
                            if (!refFolder.exists()) {
                                refFolder.mkdirs();
                            }
                            final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());
                            ImageIO.write(planetImage, "PNG", new File(refFolder, scannedBodyType + " " + ts + " " + this.currentSystemName + ".png"));
                            this.sysmapScanner.reloadTemplates();
                        } catch (IOException e) {
                            logger.warn("Failed to write planet ref image", e);
                        }
                    } else {
                        logger.info("Correctly guessed " + guessedBodyType + ", and was " + scannedBodyType);
                        this.sysmapScanner.reloadTemplates();
                    }
                }

                this.currentSysmapBody = null;
                if (this.nextBodyToScan() == null) {
                    this.shipControl.setThrottle(100);
                    this.shipControl.selectNextSystemInRoute();
                    this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
                    logger.debug("All bodies scanned, aligning to next jump target at 100% throttle");
                } else {
                    this.shipControl.setThrottle(0);
                    this.shipControl.stopTurning();
                    logger.debug("Open system map");
                    this.robot.mouseMove(1, 1);
                    this.shipControl.toggleSystemMap();
                    this.gameState = GameState.WAIT_FOR_SYSTEM_MAP;
                    logger.debug(((ScanEvent) event).getBodyName() + " scanned, waiting for system map at stand-still");
                }
            }
        }
    }

    private void clickOnNextBodyOnSystemMap() {
        this.currentSysmapBody = this.nextBodyToScan();

        try {
            Thread.sleep(1000); // TODO Necessary?

            Random random = new Random();

            // Click on body
            this.robot.mouseMove((this.currentSysmapBody.centerOnScreen.x - 5) + random.nextInt(10), (this.currentSysmapBody.centerOnScreen.y - 5) + random.nextInt(10));
            Thread.sleep(100);
            this.robot.mouseMove((this.currentSysmapBody.centerOnScreen.x - 5) + random.nextInt(10), (this.currentSysmapBody.centerOnScreen.y - 5) + random.nextInt(10));
            Thread.sleep(100);
            this.robot.mousePress(InputEvent.getMaskForButton(1));
            Thread.sleep(100);
            this.robot.mouseRelease(InputEvent.getMaskForButton(1));

            Thread.sleep(2000); // Wait for map to scroll
            this.robot.mouseMove(this.screenRect.width / 2, this.screenRect.height / 2); // Move mouse to now centered planet
            Thread.sleep(500); // Wait for tooltip to appear

            // Select as target (ENTER, scroll right, scroll left, ENTER again)
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

            Thread.sleep(1000); // TODO Necessary?
        } catch (InterruptedException e) {
            logger.warn("Interrupted while clicking on system map", e);
        }
    }

    private SysmapBody nextBodyToScan() {
        final boolean alreadyScannedStar = this.currentSystemScannedBodies.stream().filter(e -> StringUtils.isNotEmpty(e.getStarType())).findFirst().isPresent();
        final boolean isCloseToEntryStar = this.lastScannedBodyDistanceFromArrival < 1000;
        final boolean allowStars = !alreadyScannedStar && !isCloseToEntryStar;
        return this.sysmapScannerResult.getBodies().stream().filter( //
                b -> b.unexplored && // Of course only unexplored
                b.moonMasses == null && // No belts please
                (!allowStars ? b.solarMasses == null : true) && // No further stars if already scanned one
                (b.earthMasses == null || b.earthMasses.floatValue() > 0.0099f) && // Not every mini-moon (0.99% and less earth masses) pls
                ((b.distanceLs != null && b.distanceLs.floatValue() <= 12345f) || (b.solarMasses != null))) // Only close distance (or stars)
                .sorted(new SensibleScanOrderComparator()).findFirst().orElse(null);
    }

}
