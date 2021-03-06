package borg.ed.cruisecontrol;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.ddogleg.struct.FastQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import boofcv.abst.feature.associate.AssociateDescription;
import boofcv.abst.feature.associate.ScoreAssociation;
import boofcv.abst.feature.detdesc.DetectDescribePoint;
import boofcv.abst.feature.detect.interest.ConfigFastHessian;
import boofcv.alg.descriptor.UtilFeature;
import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.core.image.ConvertImage;
import boofcv.factory.feature.associate.FactoryAssociation;
import boofcv.factory.feature.detdesc.FactoryDetectDescribe;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.feature.AssociatedIndex;
import boofcv.struct.feature.BrightFeature;
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
import borg.ed.galaxy.constants.PlanetClass;
import borg.ed.galaxy.constants.StarClass;
import borg.ed.galaxy.data.Coord;
import borg.ed.galaxy.journal.JournalReaderThread;
import borg.ed.galaxy.journal.JournalUpdateListener;
import borg.ed.galaxy.journal.Status;
import borg.ed.galaxy.journal.StatusReaderThread;
import borg.ed.galaxy.journal.StatusUpdateListener;
import borg.ed.galaxy.journal.events.AbstractJournalEvent;
import borg.ed.galaxy.journal.events.DiscoveryScanEvent;
import borg.ed.galaxy.journal.events.FSDJumpEvent;
import borg.ed.galaxy.journal.events.FSSDiscoveryScanEvent;
import borg.ed.galaxy.journal.events.FuelScoopEvent;
import borg.ed.galaxy.journal.events.ReceiveTextEvent;
import borg.ed.galaxy.journal.events.ScanEvent;
import borg.ed.galaxy.journal.events.StartJumpEvent;
import borg.ed.galaxy.model.Body;
import borg.ed.galaxy.model.StarSystem;
import borg.ed.galaxy.robot.ShipControl;
import borg.ed.galaxy.service.GalaxyService;
import borg.ed.galaxy.util.BodyUtil;
import borg.ed.galaxy.util.MiscUtil;
import georegression.struct.point.Point2D_F64;

public class CruiseControlThread extends Thread implements JournalUpdateListener, StatusUpdateListener {

	static final Logger logger = LoggerFactory.getLogger(CruiseControlThread.class);

	private static final String REASON_END_OF_PLOTTED_ROUTE = "End of plotted route";
	private static final String REASON_COMBAT_LOG = "Combat log";

	@Autowired
	private GalaxyService galaxyService;
	@Autowired
	private ScreenConverterThread screenConverterThread;
	@Autowired
	private ShipControl shipControl;
	@Autowired
	private SysmapScanner sysmapScanner;

	private CruiseSettings cruiseSettings = null;

	private Template refCompass = null;
	private Template refCompassType9 = null;
	private Template refCompassDotFilled = null;
	private Template refCompassDotHollow = null;
	private Template refShipHud = null;
	private Template refShipHudType9 = null;
	private Template refSixSeconds = null;
	private Template refSevenSeconds = null;
	private Template refEightSeconds = null;
	private Template refNineSeconds = null;
	private Template refTenSeconds = null;
	private Template refElevenSeconds = null;

	private GameState gameState = GameState.UNKNOWN;
	private Float xPercent = null;
	private Float yPercent = null;
	private float brightnessAhead = 0;
	private float brightnessAheadLeft = 0;
	private float brightnessAheadRight = 0;
	private boolean scoopingFuel = false;
	private boolean fsdCooldown = false;
	private float fuelLevel = 0;
	private long inHyperspaceSince = Long.MAX_VALUE; // Timestamp when the FSD was charged and the countdown started
	private long escapingFromStarSince = Long.MAX_VALUE;
	private long honkingSince = Long.MAX_VALUE;
	private boolean fsdCharging = false;
	private long fsdChargingSince = Long.MAX_VALUE;
	private long getInScoopingRangeSince = Long.MAX_VALUE;
	private long waitForFsdCooldownSince = Long.MAX_VALUE;
	private boolean jumpTargetIsScoopable = false;
	private StarClass nextStarClass = null;
	private long escapeFromNonScoopableSince = Long.MAX_VALUE;
	private long approachNextBodySince = Long.MAX_VALUE;
	private String currentSystemName = "";
	private Coord currentSystemCoord = new Coord();
	private List<Body> currentSystemKnownBodies = new ArrayList<>();
	private List<ScanEvent> currentSystemScannedBodies = new ArrayList<>();
	private int currentSystemNumDiscoveredBodies = 0;
	private SysmapBody currentSysmapBody = null;
	private SysmapScannerResult sysmapScannerResult = null;
	private long lastScannedBodyAt = 0;
	private float lastScannedBodyDistanceFromArrival = 0;
	private int lastValuableSystemsRadiusLy = 500;
	private List<ValuableSystem> valuableSystems = new ArrayList<>();
	private ValuableSystem nextValuableSystem = null;
	private long lastTick = System.currentTimeMillis();

	private static final int COMPASS_REGION_X = 610;
	private static final int COMPASS_REGION_Y = 767;
	private static final int COMPASS_REGION_WIDTH = 200;
	private static final int COMPASS_REGION_HEIGHT = 230;
	private TemplateMatch compassMatch = null;

	private static final int TARGET_REGION_X = 555;
	private static final int TARGET_REGION_Y = 190;
	private static final int TARGET_REGION_WIDTH = 810;
	private static final int TARGET_REGION_HEIGHT = 570;
	//private TemplateMatch targetMatch = null;

	private TemplateMatch sixSecondsMatch = null;

	private static Boolean inEmergencyExit = Boolean.FALSE;

	private List<DebugImageListener> debugImageListeners = new ArrayList<>();

	public CruiseControlThread() {
		this.setName("CCThread");
		this.setDaemon(false);
	}

	@Override
	public void run() {
		logger.info(this.getName() + " started");

		try {
			this.cruiseSettings = CruiseSettings.load();
			if (this.cruiseSettings == null) {
				this.cruiseSettings = new CruiseSettings();
				CruiseSettings.save(this.cruiseSettings);
			}
		} catch (IOException e1) {
			throw new RuntimeException("Failed to load cruise settings", e1);
		}

		this.loadRefImages();

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
		GrayF32 yellowHudImage = orangeHudImage.createSameShape();
		GrayF32 blueWhiteHudImage = orangeHudImage.createSameShape();
		GrayF32 redHudImage = orangeHudImage.createSameShape();
		GrayF32 brightImage = orangeHudImage.createSameShape();
		BufferedImage debugImage = new BufferedImage(CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, BufferedImage.TYPE_INT_RGB);

		final ScreenConverterResult screenConverterResult = this.screenConverterThread.getScreenConverterResult();
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
						yellowHudImage = screenConverterResult.getYellowHudImage().clone();
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
				this.computeBrightnessAhead(brightImage);
				TemplateMatch compassDotMatch = this.searchForCompassAndTarget(orangeHudImage, yellowHudImage, blueWhiteHudImage, threadPool);
				this.searchForSixSecondsOrScanning(orangeHudImage, yellowHudImage);
				this.handleGameState(rgb, hsv, orangeHudImage, compassDotMatch, screenConverterResult);
				// <<<< PROCESSING THE DATA <<<<

				// >>>> DEBUG IMAGE >>>>
				if (CruiseControlApplication.SHOW_LIVE_DEBUG_IMAGE) {
					this.drawColoredDebugImage(debugImage, orangeHudImage, yellowHudImage, blueWhiteHudImage, redHudImage, brightImage);
					this.drawDebugInfoOnImage(debugImage, compassDotMatch);
					for (DebugImageListener listener : this.debugImageListeners) {
						listener.onNewDebugImage(debugImage, orangeHudImage, yellowHudImage, blueWhiteHudImage, redHudImage, brightImage);
					}
				}
				// <<<< DEBUG IMAGE <<<<
			} catch (Exception e) {
				logger.error(this.getName() + " crashed", e);
				System.exit(-2);
				break; // Quit
			}
		}

		logger.info(this.getName() + " stopped");
	}

	private TemplateMatch searchForCompassAndTarget(GrayF32 orangeHudImage, GrayF32 yellowHudImage, GrayF32 blueWhiteHudImage, final ExecutorService threadPool)
			throws InterruptedException, ExecutionException {
		compassMatch = null;
		//targetMatch = null;
		if (gameState == GameState.UNKNOWN || gameState == GameState.ALIGN_TO_NEXT_SYSTEM || gameState == GameState.FSD_CHARGING || gameState == GameState.ALIGN_TO_NEXT_BODY
				|| gameState == GameState.APPROACH_NEXT_BODY || gameState == GameState.ESCAPE_FROM_STAR_PER_BODY) {
			final GrayF32 myYellowHudImage = yellowHudImage;
			Future<Point> futureTarget = threadPool.submit(new Callable<Point>() {
				@Override
				public Point call() throws Exception {
					return locateTargetFeature(myYellowHudImage);
				}
			});
			final GrayF32 myOrangeHudImage = orangeHudImage;
			Future<TemplateMatch> futureCompass = threadPool.submit(new Callable<TemplateMatch>() {
				@Override
				public TemplateMatch call() throws Exception {
					return locateCompassSmart(myOrangeHudImage);
				}
			});
			futureTarget.get();
			//targetMatch = futureTarget.get();
			compassMatch = futureCompass.get();
		} else {
			compassMatch = null;
			//targetMatch = null;
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

	Integer sixSecondsRegionX = null;
	Integer sixSecondsRegionY = null;
	final int sixSecondsRegionWidth = 76;
	final int sixSecondsRegionHeight = 34;
	final float sixSecondsMaxErrorPerPixel = 0.045f;

	private void searchForSixSecondsOrScanning(GrayF32 orangeHudImage, GrayF32 yellowHudImage) {
		sixSecondsRegionX = null;
		sixSecondsRegionY = null;
		if (gameState == GameState.APPROACH_NEXT_BODY) {
			if (targetX == null || targetY == null) {
				sixSecondsMatch = null;
			} else {
				sixSecondsRegionX = targetX + 40;
				sixSecondsRegionY = targetY + 20;
				sixSecondsMatch = TemplateMatcher.findBestMatchingLocationInRegion(yellowHudImage, sixSecondsRegionX, sixSecondsRegionY, sixSecondsRegionWidth, sixSecondsRegionHeight,
						this.refSixSeconds);
				if (sixSecondsMatch.getErrorPerPixel() > sixSecondsMaxErrorPerPixel) {
					sixSecondsMatch = TemplateMatcher.findBestMatchingLocationInRegion(yellowHudImage, sixSecondsRegionX, sixSecondsRegionY, sixSecondsRegionWidth, sixSecondsRegionHeight,
							this.refSevenSeconds);
					if (sixSecondsMatch.getErrorPerPixel() > sixSecondsMaxErrorPerPixel) {
						sixSecondsMatch = TemplateMatcher.findBestMatchingLocationInRegion(yellowHudImage, sixSecondsRegionX, sixSecondsRegionY, sixSecondsRegionWidth, sixSecondsRegionHeight,
								this.refEightSeconds);
						if (sixSecondsMatch.getErrorPerPixel() > sixSecondsMaxErrorPerPixel) {
							sixSecondsMatch = TemplateMatcher.findBestMatchingLocationInRegion(yellowHudImage, sixSecondsRegionX, sixSecondsRegionY, sixSecondsRegionWidth,
									sixSecondsRegionHeight, this.refNineSeconds);
							if (sixSecondsMatch.getErrorPerPixel() > sixSecondsMaxErrorPerPixel) {
								sixSecondsMatch = TemplateMatcher.findBestMatchingLocationInRegion(yellowHudImage, sixSecondsRegionX, sixSecondsRegionY, sixSecondsRegionWidth,
										sixSecondsRegionHeight, this.refTenSeconds);
								if (sixSecondsMatch.getErrorPerPixel() > sixSecondsMaxErrorPerPixel) {
									sixSecondsMatch = TemplateMatcher.findBestMatchingLocationInRegion(yellowHudImage, sixSecondsRegionX, sixSecondsRegionY, sixSecondsRegionWidth,
											sixSecondsRegionHeight, this.refElevenSeconds);
									if (sixSecondsMatch.getErrorPerPixel() > sixSecondsMaxErrorPerPixel) {
										sixSecondsMatch = null;
									}
								}
							}
						}
					}
				}
			}

			locateScanningFeature(orangeHudImage);
		} else {
			sixSecondsMatch = null;
			scanningX = null;
			scanningY = null;
		}
	}

	private void handleGameState(Planar<GrayF32> rgb, Planar<GrayF32> hsv, GrayF32 orangeHudImage, TemplateMatch compassDotMatch, ScreenConverterResult screenConverterResult)
			throws InterruptedException {
		switch (this.gameState) {
		case UNKNOWN:
			this.shipControl.releaseAllKeys();
			break;
		case FSD_CHARGING:
			if (this.brightnessAhead > 0.15f) {
				this.shipControl.setPitchDown(100);
				if (this.brightnessAheadLeft > this.brightnessAheadRight) {
					this.shipControl.setRollLeft((int) (10 * Math.random()));
				} else {
					this.shipControl.setRollRight((int) (10 * Math.random()));
				}
				if (this.shipControl.getThrottle() != 75) {
					this.shipControl.setThrottle(75);
				}
			} else {
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
				if (targetPercentX != null && targetPercentY != null) {
					this.alignToTargetInHud();
				} else {
					this.alignToTargetInCompass(compassMatch, compassDotMatch);
				}
			}
			break;
		case IN_HYPERSPACE:
			if (System.currentTimeMillis() - this.inHyperspaceSince > 5000 && this.shipControl.getThrottle() > 0) {
				this.shipControl.setThrottle(0);
			}
			break;
		case PLOT_TO_NEXT_VALUABLE_SYSTEM:
			if (System.currentTimeMillis() - this.honkingSince > 8000) {
				this.plotToNextValuableSystem();
			}
			break;
		case WAIT_FOR_FSD_COOLDOWN:
			if (this.shipControl.getThrottle() > 0) {
				this.shipControl.setThrottle(0);
			}
			if (System.currentTimeMillis() - waitForFsdCooldownSince > 10000) {
				logger.warn("FSD cooldown not triggered within 10 seconds, getting in scooping range now");
				this.shipControl.setThrottle(0);
				this.getInScoopingRangeSince = System.currentTimeMillis();
				this.gameState = GameState.GET_IN_SCOOPING_RANGE;
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
			if (this.fuelLevel >= (CruiseControlApplication.maxFuel / 2)) {
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
			if (this.fuelLevel >= CruiseControlApplication.maxFuel) {
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
				if (this.brightnessAheadLeft > this.brightnessAheadRight) {
					this.shipControl.setRollLeft((int) (10 * Math.random()));
				} else {
					this.shipControl.setRollRight((int) (10 * Math.random()));
				}
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
			if (this.brightnessAhead > 0.15f) {
				if (this.shipControl.getThrottle() != 75) {
					this.shipControl.setThrottle(75);
				}
				this.shipControl.setPitchDown(100);
				if (this.brightnessAheadLeft > this.brightnessAheadRight) {
					this.shipControl.setRollLeft((int) (10 * Math.random()));
				} else {
					this.shipControl.setRollRight((int) (10 * Math.random()));
				}
			} else {
				this.shipControl.stopTurning();
				if (this.shipControl.getThrottle() != 100) {
					this.shipControl.setThrottle(100);
				}
			}
			if (System.currentTimeMillis() - this.escapingFromStarSince > 10000) {
				this.escapingFromStarSince = System.currentTimeMillis();
				this.gameState = GameState.ESCAPE_FROM_STAR_PER_BODY;
				logger.debug("Fast escape done, now doing slow escape per body");
			}
			break;
		case ESCAPE_FROM_STAR_PER_BODY:
			if (this.shipControl.getThrottle() != 25) {
				this.shipControl.setThrottle(25);
			}
			if (this.brightnessAhead > 0.15f) {
				this.shipControl.setPitchDown(100);
				if (this.brightnessAheadLeft > this.brightnessAheadRight) {
					this.shipControl.setRollLeft((int) (10 * Math.random()));
				} else {
					this.shipControl.setRollRight((int) (10 * Math.random()));
				}
			} else {
				if (targetPercentX != null && targetPercentY != null) {
					this.alignToTargetInHud();
				} else {
					this.alignToTargetInCompass(compassMatch, compassDotMatch);
				}
			}
			if (System.currentTimeMillis() - this.escapingFromStarSince > this.currentSystemNumDiscoveredBodies * 1000L
					* MiscUtil.getAsLong(this.cruiseSettings.getEscapeFromStarPerBodySeconds(), 0L)) {
				this.escapingFromStarSince = Long.MAX_VALUE;
				this.shipControl.stopTurning();
				if (this.currentSystemNumDiscoveredBodies > 1 && !this.cruiseSettings.isJonkMode()) {
					this.shipControl.setThrottle(0);
					logger.debug("Open system map");
					this.shipControl.toggleSystemMap();
					this.gameState = GameState.SCAN_SYSTEM_MAP;
					logger.debug("Escaped from entry star, " + this.currentSystemNumDiscoveredBodies + " bodies discovered, throttle to 0% and scan system map");
				} else {
					this.shipControl.setThrottle(100);
					this.shipControl.selectNextSystemInRoute();
					this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
					if (this.cruiseSettings.isJonkMode()) {
						logger.debug("Escaped from entry star, jonk mode, aligning to next jump target at 100% throttle");
					} else {
						logger.debug("Escaped from entry star, no other bodies discovered, aligning to next jump target at 100% throttle");
					}
				}
			}
			break;
		case ESCAPE_FROM_NON_SCOOPABLE:
			long escapingSince = System.currentTimeMillis() - this.escapeFromNonScoopableSince;
			long pitch180TimeMillis = this.shipControl.getPitch180TimeMillis();
			if (escapingSince < pitch180TimeMillis) {
				if (this.shipControl.getThrottle() != 0) {
					this.shipControl.setThrottle(0);
				}
				if (this.shipControl.getPitchDown() != 100) {
					this.shipControl.setPitchDown(100);
				}
			} else {
				if (escapingSince < pitch180TimeMillis + 15000) { // Flee for 15 seocnds
					if (this.brightnessAhead > 0.15f) {
						if (this.shipControl.getThrottle() != 75) {
							this.shipControl.setThrottle(75);
						}
						this.shipControl.setPitchDown(100);
						if (this.brightnessAheadLeft > this.brightnessAheadRight) {
							this.shipControl.setRollLeft((int) (10 * Math.random()));
						} else {
							this.shipControl.setRollRight((int) (10 * Math.random()));
						}
					} else {
						this.shipControl.stopTurning();
						if (this.shipControl.getThrottle() != 100) {
							this.shipControl.setThrottle(100);
						}
					}
				} else {
					this.escapeFromNonScoopableSince = Long.MAX_VALUE;
					this.shipControl.stopTurning();
					if (this.currentSystemNumDiscoveredBodies > 1 && !this.cruiseSettings.isJonkMode()) {
						this.shipControl.setThrottle(0);
						logger.debug("Open system map");
						this.shipControl.toggleSystemMap();
						this.gameState = GameState.SCAN_SYSTEM_MAP;
						logger.debug("Escaped from non-scoopable star, " + this.currentSystemNumDiscoveredBodies + " bodies discovered, throttle to 0% and scan system map");
					} else {
						this.shipControl.setThrottle(100);
						this.shipControl.selectNextSystemInRoute();
						this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
						if (this.cruiseSettings.isJonkMode()) {
							logger.debug("Escaped from non-scoopable star, jonk mode, aligning to next jump target at 100% throttle");
						} else {
							logger.debug("Escaped from non-scoopable star, no other bodies discovered, aligning to next jump target at 100% throttle");
						}
					}
				}
			}
			break;
		case SCAN_SYSTEM_MAP:
			this.sysmapScannerResult = this.sysmapScanner.scanSystemMap(rgb, hsv, this.currentSystemName, this.cruiseSettings.isCreditsMode(), this.nextStarClass);
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
					if (!this.clickOnNextBodyOnSystemMap(screenConverterResult)) {
						this.currentSysmapBody.unexplored = false; // Mark as explored
						if (this.nextBodyToScan() != null) {
							this.gameState = GameState.WAIT_FOR_SYSTEM_MAP; // Choose next body
							break;
						} else {
							logger.debug("Close system map");
							this.shipControl.toggleSystemMap();
							Thread.sleep(1000);
							this.shipControl.setThrottle(0);
							this.shipControl.selectNextSystemInRoute();
							this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
							logger.debug("All bodies scanned, aligning to next jump target at 0% throttle");
							break;
						}
					}
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
				// If it is a star then we are too close. Discard this star.
				if (this.currentSysmapBody.solarMasses != null) {
					this.currentSysmapBody.unexplored = false; // Mark as explored
					if (this.nextBodyToScan() == null) {
						this.shipControl.setThrottle(0);
						this.shipControl.selectNextSystemInRoute();
						this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
						logger.debug("Discarded a star to scan, no other body to scan, aligning to next jump target at 0% throttle");
					} else {
						this.shipControl.setThrottle(0);
						this.shipControl.stopTurning();
						logger.debug("Open system map");
						this.shipControl.toggleSystemMap();
						this.gameState = GameState.WAIT_FOR_SYSTEM_MAP;
						logger.debug("Discarded a star to scan, waiting for system map at 0% throttle");
					}
				} else {
					if (this.shipControl.getThrottle() != 25) {
						this.shipControl.setThrottle(25);
					}
					this.shipControl.setPitchDown(100);
					if (this.brightnessAheadLeft > this.brightnessAheadRight) {
						this.shipControl.setRollLeft((int) (10 * Math.random()));
					} else {
						this.shipControl.setRollRight((int) (10 * Math.random()));
					}
				}
			} else {
				if (targetPercentX != null && targetPercentY != null) {
					if (this.shipControl.getThrottle() != 0) {
						this.shipControl.setThrottle(0);
					}
					if (this.alignToTargetInHud()) {
						this.shipControl.setThrottle(75);
						this.approachNextBodySince = System.currentTimeMillis();
						this.gameState = GameState.APPROACH_NEXT_BODY;
						logger.debug("Next body in sight, accelerating to 75% and waiting for detailed surface scan");
					}
				} else if (compassMatch != null && compassDotMatch != null) {
					if (this.shipControl.getThrottle() != 0) {
						this.shipControl.setThrottle(0);
					}
					if (this.alignToTargetInCompass(compassMatch, compassDotMatch)) {
						this.shipControl.setThrottle(75);
						this.approachNextBodySince = System.currentTimeMillis();
						this.gameState = GameState.APPROACH_NEXT_BODY;
						logger.debug("Next body in sight, accelerating to 75% and waiting for detailed surface scan");
					}
				} else {
					// Neither HUD target nor compass visible, probably hard to recognize because our cockpit is full of
					// light from a nearby star. Therefore carefully throttle ahead without turning.
					this.shipControl.stopTurning();
					if (this.shipControl.getThrottle() != 25) {
						this.shipControl.setThrottle(25);
					}
				}
			}
			break;
		case APPROACH_NEXT_BODY:
			if (System.currentTimeMillis() - this.approachNextBodySince > 600_000) {
				logger.warn("Approaching next body took too long, aligning to next system now");
				this.shipControl.selectNextSystemInRoute();
				this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
				return;
			}
			if (this.brightnessAhead > 0.15f) {
				// If it is a star then we are too close. Discard this star.
				if (this.currentSysmapBody.solarMasses != null) {
					this.currentSysmapBody.unexplored = false; // Mark as explored
					if (this.nextBodyToScan() == null) {
						this.shipControl.setThrottle(0);
						this.shipControl.selectNextSystemInRoute();
						this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
						logger.debug("Discarded a star to scan, no other body to scan, aligning to next jump target at 0% throttle");
					} else {
						this.shipControl.setThrottle(0);
						this.shipControl.stopTurning();
						logger.debug("Open system map");
						this.shipControl.toggleSystemMap();
						this.gameState = GameState.WAIT_FOR_SYSTEM_MAP;
						logger.debug("Discarded a star to scan, waiting for system map at 0% throttle");
					}
				} else {
					if (this.shipControl.getThrottle() != 25) {
						this.shipControl.setThrottle(25);
					}
					this.shipControl.setPitchDown(100);
					if (this.brightnessAheadLeft > this.brightnessAheadRight) {
						this.shipControl.setRollLeft((int) (10 * Math.random()));
					} else {
						this.shipControl.setRollRight((int) (10 * Math.random()));
					}
				}
			} else {
				if (scanningX != null && scanningY != null) {
					if (this.shipControl.getThrottle() != 0) {
						this.shipControl.setThrottle(0);
					}
				} else if (sixSecondsMatch != null) {
					if (this.shipControl.getThrottle() != 75) {
						this.shipControl.setThrottle(75);
					}
				} else if (targetPercentX == null || targetPercentY == null) {
					// May be occluded by a star, we must keep going at least slow.
					// Otherwise we overshot a planet and will do one loop of shame after the other if we do not stand still...
					if (this.shipControl.getThrottle() != 0) {
						this.shipControl.setThrottle(0);
					}
				} else {
					if (this.shipControl.getThrottle() != 100) {
						this.shipControl.setThrottle(100);
					}
				}
				if (targetPercentX != null && targetPercentY != null) {
					this.alignToTargetInHud();
				} else {
					this.alignToTargetInCompass(compassMatch, compassDotMatch);
				}
			}
			break;
		case WAIT_FOR_SYSTEM_MAP:
			if (this.sysmapScanner.isUniversalCartographicsLogoVisible(rgb)) {
				Thread.sleep(3000); // Wait for graphics to settle
				if (!this.clickOnNextBodyOnSystemMap(screenConverterResult)) {
					this.currentSysmapBody.unexplored = false; // Mark as explored
					if (this.nextBodyToScan() != null) {
						logger.debug("Next body to scan would be " + this.nextBodyToScan());
						this.gameState = GameState.WAIT_FOR_SYSTEM_MAP; // Choose next body
						break;
					} else {
						logger.debug("Close system map");
						this.shipControl.toggleSystemMap();
						Thread.sleep(1000);
						this.shipControl.setThrottle(0);
						this.shipControl.selectNextSystemInRoute();
						this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
						logger.debug("All bodies scanned, aligning to next jump target at 0% throttle");
						break;
					}
				}
				logger.debug("Close system map");
				this.shipControl.toggleSystemMap();
				this.shipControl.setThrottle(0);
				this.gameState = GameState.WAIT_FOR_SHIP_HUD;
				logger.debug("Clicked on next body, waiting for ship HUD at 0% throttle");
			}
			break;
		case WAIT_FOR_SHIP_HUD:
			if (this.isShipHudVisible(orangeHudImage)) {
				this.shipControl.setThrottle(0);
				this.gameState = GameState.ALIGN_TO_NEXT_BODY;
				logger.debug("Ship HUD visible, aligning to next body at 0% throttle");
			}
			break;
		case ALIGN_TO_NEXT_SYSTEM:
			if (this.brightnessAhead > 0.15f) {
				if (this.shipControl.getThrottle() != 0) {
					this.shipControl.setThrottle(0);
				}
				this.shipControl.setPitchDown(100);
				if (this.brightnessAheadLeft > this.brightnessAheadRight) {
					this.shipControl.setRollLeft((int) (10 * Math.random()));
				} else {
					this.shipControl.setRollRight((int) (10 * Math.random()));
				}
			} else {
				if (targetPercentX != null && targetPercentY != null) {
					if (this.shipControl.getThrottle() != 50) {
						this.shipControl.setThrottle(50);
					}
					if (this.alignToTargetInHud() && System.currentTimeMillis() - this.lastScannedBodyAt > 2000) {
						this.shipControl.toggleFsd();
						this.gameState = GameState.FSD_CHARGING;
						logger.debug("Next system in sight, charging FSD");
					}
				} else {
					if (this.shipControl.getThrottle() != 25) {
						this.shipControl.setThrottle(25);
					}
					if (this.alignToTargetInCompass(compassMatch, compassDotMatch) && System.currentTimeMillis() - this.lastScannedBodyAt > 2000) {
						this.shipControl.toggleFsd();
						this.gameState = GameState.FSD_CHARGING;
						logger.debug("Next system in sight, charging FSD");
					}
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

	private void plotToNextValuableSystem() throws InterruptedException {
		this.shipControl.toggleGalaxyMap();

		//		ScreenConverterResult screenConverterResult = this.screenConverterThread.getScreenConverterResult();
		//		Planar<GrayF32> rgb = screenConverterResult.getRgb().clone();
		//		while (!this.sysmapScanner.isUniversalCartographicsLogoVisible(rgb)) {
		//			synchronized (screenConverterResult) {
		//				screenConverterResult.wait();
		//				rgb = screenConverterResult.getRgb().clone();
		//			}
		//		}

		Thread.sleep(5000 + (long) (Math.random() * 1000));
		this.shipControl.uiNextTab();
		//this.shipControl.leftClick(160, 144);
		Thread.sleep(1000 + (long) (Math.random() * 100));
		this.shipControl.uiSelect();
		Thread.sleep(1000 + (long) (Math.random() * 100));
		this.shipControl.type(this.nextValuableSystem.getName());
		Thread.sleep(250 + (long) (Math.random() * 100));
		this.shipControl.uiSelect();
		Thread.sleep(5000 + (long) (Math.random() * 1000)); // Wait for scroll
		this.shipControl.uiRight();
		Thread.sleep(250 + (long) (Math.random() * 100));
		this.shipControl.uiSelect();
		Thread.sleep(15000 + (long) (Math.random() * 3000)); // Wait for route plotter

		this.shipControl.toggleGalaxyMap();
		Thread.sleep(1000 + (long) (Math.random() * 200));

		if (!this.jumpTargetIsScoopable) {
			this.escapeFromNonScoopableSince = System.currentTimeMillis();
			this.gameState = GameState.ESCAPE_FROM_NON_SCOOPABLE;
		} else {
			this.getInScoopingRangeSince = System.currentTimeMillis();
			this.gameState = GameState.GET_IN_SCOOPING_RANGE;
		}
	}

	private boolean isShipHudVisible(GrayF32 orangeHudImage) {
		TemplateMatch mShipHud = TemplateMatcher.findBestMatchingLocation(orangeHudImage.subimage(1650, 900, 1900, 1050), this.refShipHud);
		TemplateMatch mShipHudType9 = TemplateMatcher.findBestMatchingLocation(orangeHudImage.subimage(1650, 900, 1900, 1050), this.refShipHudType9);
		if (mShipHudType9.getErrorPerPixel() < mShipHud.getErrorPerPixel()) {
			mShipHud = mShipHudType9;
		}
		return mShipHud.getErrorPerPixel() <= 0.1f;
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

					// Deploy heatsink, full stop, then exit
					if (!REASON_END_OF_PLOTTED_ROUTE.equals(reason) && !REASON_COMBAT_LOG.equals(reason)) {
						this.shipControl.deployHeatsink();
						try {
							Thread.sleep(250);
						} catch (InterruptedException e) {
							logger.warn("Interrupted while waiting after deploying heatsink");
						}
					}
					this.shipControl.fullStop();
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						logger.warn("Interrupted while waiting after stopping ship");
					}
					if (REASON_COMBAT_LOG.equals(reason)) {
						this.shipControl.exitToMainMenu(500);
					} else {
						this.shipControl.exitToMainMenu();
					}

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

	private void computeBrightnessAhead(GrayF32 brightImage) {
		final int halfWidth = brightImage.width / 2;

		// Upper 2/3 of the screen
		int stepX = (int) (brightImage.width / 32.0f);
		int offX = stepX / 2;
		int stepY = (int) (brightImage.height / 18.0f);
		int offY = stepY / 2;

		float total = 32.0f * 12.0f;
		float bright = 0.0f;
		float brightLeft = 0.0f;
		float brightRight = 0.0f;

		for (int x = 0; x < 32; x++) {
			for (int y = 0; y < 12; y++) {
				int myX = offX + x * stepX;
				int myY = offY + y * stepY;
				if (brightImage.unsafe_get(myX, myY) > 0) {
					bright++;
					if (myX < halfWidth) {
						brightLeft++;
					} else {
						brightRight++;
					}
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
					if (myX < halfWidth) {
						brightLeft++;
					} else {
						brightRight++;
					}
				}
			}
		}

		// Center 20% square of the screen
		int squareSize = brightImage.height / 5;
		stepX = squareSize / 10;
		offX = halfWidth - (int) (4.5f * stepX);
		stepY = squareSize / 10;
		offY = (brightImage.height / 2) - (int) (4.5f * stepY);

		total += 10.0f * 10.0f;

		for (int x = 0; x < 10; x++) {
			for (int y = 0; y < 10; y++) {
				int myX = offX + x * stepX;
				int myY = offY + y * stepY;
				if (brightImage.unsafe_get(myX, myY) > 0) {
					bright++;
					if (myX < halfWidth) {
						brightLeft++;
					} else {
						brightRight++;
					}
				}
			}
		}

		// Result
		this.brightnessAhead = bright / total;
		this.brightnessAheadLeft = brightLeft / (total / 2);
		this.brightnessAheadRight = brightRight / (total / 2);
	}

	private boolean alignToTargetInHud() {
		if (targetPercentX == null || targetPercentY == null) {
			this.shipControl.stopTurning();
		} else {
			xPercent = targetPercentX;
			yPercent = targetPercentY;

			if (xPercent >= 49.0f && xPercent <= 51.0f && yPercent >= 49.0f && yPercent <= 51.0f) {
				this.shipControl.stopTurning();
				return true;
			}

			// In any case stop rolling!
			this.shipControl.setRollLeft(0);
			this.shipControl.setRollRight(0);

			// If the target indicator is visible it is generally already quite in the center.
			// It will never be located at y=0% at the very top or any other edge, but rather in a range of
			// 25% to 75%.

			// >>>> Y <<<<
			if (yPercent < 49.0f) {
				// Target is _above_ center, we need to pitch up
				if (yPercent < 25) {
					this.shipControl.setPitchUp(75);
				} else if (yPercent < 30) {
					this.shipControl.setPitchUp(50);
				} else if (yPercent < 35) {
					this.shipControl.setPitchUp(30);
				} else if (yPercent < 40) {
					this.shipControl.setPitchUp(20);
				} else if (yPercent < 45) {
					this.shipControl.setPitchUp(10);
				} else {
					this.shipControl.setPitchUp(5);
				}
			} else if (yPercent > 51.0f) {
				// Target is _below_ center, we need to pitch down
				if (yPercent > 75) {
					this.shipControl.setPitchDown(75);
				} else if (yPercent > 70) {
					this.shipControl.setPitchDown(50);
				} else if (yPercent > 65) {
					this.shipControl.setPitchDown(30);
				} else if (yPercent > 60) {
					this.shipControl.setPitchDown(20);
				} else if (yPercent > 55) {
					this.shipControl.setPitchDown(10);
				} else {
					this.shipControl.setPitchDown(5);
				}
			} else {
				this.shipControl.setPitchUp(0);
				this.shipControl.setPitchDown(0);
			}

			// >>>> X <<<<
			if (xPercent < 49.0f) {
				if (xPercent < 25) {
					this.shipControl.setYawLeft(75);
				} else if (xPercent < 30) {
					this.shipControl.setYawLeft(50);
				} else if (xPercent < 35) {
					this.shipControl.setYawLeft(30);
				} else if (xPercent < 40) {
					this.shipControl.setYawLeft(20);
				} else if (xPercent < 45) {
					this.shipControl.setYawLeft(10);
				} else {
					this.shipControl.setYawLeft(5);
				}
			} else if (xPercent > 51.0f) {
				if (xPercent > 75) {
					this.shipControl.setYawRight(75);
				} else if (xPercent > 70) {
					this.shipControl.setYawRight(50);
				} else if (xPercent > 65) {
					this.shipControl.setYawRight(30);
				} else if (xPercent > 60) {
					this.shipControl.setYawRight(20);
				} else if (xPercent > 55) {
					this.shipControl.setYawRight(10);
				} else {
					this.shipControl.setYawRight(5);
				}
			} else {
				this.shipControl.setYawLeft(0);
				this.shipControl.setYawRight(0);
			}

			// Keep turning but already return true if we are almost centered
			if (xPercent >= 45 && xPercent <= 55 && yPercent >= 45 && yPercent <= 55) {
				return true;
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
			xPercent = (x * 100f) / width;
			yPercent = (y * 100f) / height;

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
				this.shipControl.setRollLeft(0);
				this.shipControl.setRollRight(0);
			} else {
				// Controlled pitch
				if (yPercent < 50) {
					if (yPercent < 25) {
						this.shipControl.setPitchUp(100);
					} else {
						this.shipControl.setPitchUp(Math.round(Math.max(10, (50 - yPercent) * 2)));
					}
				} else {
					if (yPercent > 75) {
						this.shipControl.setPitchDown(100);
					} else {
						this.shipControl.setPitchDown(Math.round(Math.max(10, (yPercent - 50) * 2)));
					}
				}
				// Roll/yaw to target
				if (xPercent < 50) {
					if (xPercent < 40) {
						this.shipControl.setRollLeft(yPercent <= 50 ? 50 : -50);
						this.shipControl.setYawLeft(50);
					} else {
						this.shipControl.setRollLeft(0);
						this.shipControl.setYawLeft(Math.round(Math.max(25, (50 - xPercent) * 2)));
					}
				} else {
					if (xPercent > 60) {
						this.shipControl.setRollRight(yPercent <= 50 ? 50 : -50);
						this.shipControl.setYawRight(50);
					} else {
						this.shipControl.setRollRight(0);
						this.shipControl.setYawRight(Math.round(Math.max(25, (xPercent - 50) * 2)));
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
			xPercent = (x * 100f) / width;
			yPercent = (y * 100f) / height;

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
					this.shipControl.setPitchUp(Math.round(Math.max(10, yPercent * 2)));
				} else {
					// 60: fast pitch down
					// 90: slow pitch down
					this.shipControl.setPitchDown(Math.round(Math.max(10, (50 - (yPercent - 50)) * 2)));
				}
				// Roll to target
				if (xPercent < 50) {
					this.shipControl.setYawRight(Math.round(Math.max(25, (50 - xPercent) * 2)));
				} else {
					this.shipControl.setYawLeft(Math.round(Math.max(25, (xPercent - 50) * 2)));
				}
			}
		}

		return false;
	}

	private TemplateMatch locateCompassSmart(GrayF32 orangeHudImage) {
		int startX = this.compassMatch == null ? COMPASS_REGION_WIDTH / 2 : this.compassMatch.getX() - COMPASS_REGION_X;
		int startY = this.compassMatch == null ? COMPASS_REGION_HEIGHT / 2 : this.compassMatch.getY() - COMPASS_REGION_Y;

		TemplateMatch mAnaconda = TemplateMatcher.findBestMatchingLocationInRegionSmart(orangeHudImage, COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT,
				this.refCompass, startX, startY, 0.25f);
		TemplateMatch mType9 = TemplateMatcher.findBestMatchingLocationInRegionSmart(orangeHudImage, COMPASS_REGION_X, COMPASS_REGION_Y, COMPASS_REGION_WIDTH, COMPASS_REGION_HEIGHT,
				this.refCompassType9, startX, startY, 0.25f);

		if (mAnaconda != null && (mType9 == null || mAnaconda.getErrorPerPixel() <= mType9.getErrorPerPixel())) {
			return mAnaconda;
		} else if (mType9 != null && (mAnaconda == null || mType9.getErrorPerPixel() <= mAnaconda.getErrorPerPixel())) {
			return mType9;
		} else {
			return null;
		}
	}

	//	private TemplateMatch locateTargetSmart(GrayF32 yellowHudImage) {
	//		int startX = this.targetMatch == null ? TARGET_REGION_WIDTH / 2 : this.targetMatch.getX() - TARGET_REGION_X;
	//		int startY = this.targetMatch == null ? TARGET_REGION_HEIGHT / 2 : this.targetMatch.getY() - TARGET_REGION_Y;
	//		TemplateMatch m = TemplateMatcher.findBestMatchingLocationInRegionSmart(yellowHudImage, TARGET_REGION_X, TARGET_REGION_Y, TARGET_REGION_WIDTH, TARGET_REGION_HEIGHT, this.refTarget,
	//				startX, startY, 0.15f);
	//		return m.getErrorPerPixel() < 0.15f ? m : null;
	//	}

	DetectDescribePoint<GrayF32, BrightFeature> detDescScanning = FactoryDetectDescribe.surfStable(new ConfigFastHessian(0.0008f, 2, -1, 1, 9, 4, 4), null, null, GrayF32.class);
	ScoreAssociation<BrightFeature> scorerScanning = FactoryAssociation.defaultScore(detDescScanning.getDescriptionType());
	AssociateDescription<BrightFeature> associateScanning = FactoryAssociation.greedy(scorerScanning, Double.MAX_VALUE, true);

	List<Point2D_F64> pointsScanningRef = new ArrayList<>();
	FastQueue<BrightFeature> descScanningRef = UtilFeature.createQueue(detDescScanning, 100);

	int nFeaturesScanning = -1; // Number of features detected in the captured (sub)image of the HUD
	int nMatchesScanning = -1; // Number of features which could be matched to the reference image
	int nMedianScanning = -1; // Number of matched features which are close to the median translation

	Integer scanningX = null; // x of center of scanning indicator in image
	Integer scanningY = null; // y of center of scanning indicator in image

	private static final int SCANNING_REGION_X = 0;
	private static final int SCANNING_REGION_Y = 765;
	private static final int SCANNING_REGION_WIDTH = 375;
	private static final int SCANNING_REGION_HEIGHT = 315;

	private Point locateScanningFeature(GrayF32 orangeHudImage) {
		nFeaturesScanning = -1;
		nMatchesScanning = -1;
		nMedianScanning = -1;
		scanningX = null;
		scanningY = null;

		detDescScanning.detect(orangeHudImage.subimage(SCANNING_REGION_X, SCANNING_REGION_Y, SCANNING_REGION_X + SCANNING_REGION_WIDTH, SCANNING_REGION_Y + SCANNING_REGION_HEIGHT));
		nFeaturesScanning = detDescScanning.getNumberOfFeatures();
		if (nFeaturesScanning <= 0) {
			return null;
		}
		List<Point2D_F64> pointsImage = new ArrayList<>();
		FastQueue<BrightFeature> descImage = UtilFeature.createQueue(detDescScanning, 100);
		for (int i = 0; i < detDescScanning.getNumberOfFeatures(); i++) {
			pointsImage.add(detDescScanning.getLocation(i).copy());
			descImage.grow().setTo(detDescScanning.getDescription(i));
		}
		associateScanning.setSource(descScanningRef);
		associateScanning.setDestination(descImage);
		associateScanning.associate();
		nMatchesScanning = associateScanning.getMatches().size();
		if (nMatchesScanning < 7) { // We need something to work with
			return null;
		}
		List<Double> distances = new ArrayList<>();
		for (int i = 0; i < associateScanning.getMatches().size(); i++) {
			AssociatedIndex ai = associateScanning.getMatches().get(i);
			Point2D_F64 refPoint = pointsScanningRef.get(ai.src);
			Point2D_F64 imagePoint = pointsImage.get(ai.dst);
			distances.add(refPoint.distance(imagePoint));
		}
		Collections.sort(distances);
		double medianDistance = distances.get(distances.size() / 2);
		List<Integer> dxList = new ArrayList<>();
		List<Integer> dyList = new ArrayList<>();
		for (int i = 0; i < associateScanning.getMatches().size(); i++) {
			AssociatedIndex ai = associateScanning.getMatches().get(i);
			Point2D_F64 refPoint = pointsScanningRef.get(ai.src);
			Point2D_F64 imagePoint = pointsImage.get(ai.dst);
			if (Math.abs(refPoint.distance(imagePoint) - medianDistance) < 10) {
				dxList.add(((int) imagePoint.x + SCANNING_REGION_X) - (int) refPoint.x);
				dyList.add(((int) imagePoint.y + SCANNING_REGION_Y) - (int) refPoint.y);
			}
		}
		nMedianScanning = dxList.size();
		if (nMedianScanning < (nMatchesScanning / 3)) { // If scattered all around the feature probably isn't visible
			return null;
		}
		Collections.sort(dxList);
		Collections.sort(dyList);
		int medianDx = dxList.get(dxList.size() / 2);
		int medianDy = dyList.get(dyList.size() / 2);
		scanningX = (CruiseControlApplication.SCALED_WIDTH / 2) + medianDx;
		scanningY = (CruiseControlApplication.SCALED_HEIGHT / 2) + medianDy;
		return new Point(scanningX, scanningY);
	}

	DetectDescribePoint<GrayF32, BrightFeature> detDescTarget = FactoryDetectDescribe.surfStable(new ConfigFastHessian(0.0008f, 2, -1, 1, 9, 4, 4), null, null, GrayF32.class);
	ScoreAssociation<BrightFeature> scorerTarget = FactoryAssociation.defaultScore(detDescTarget.getDescriptionType());
	AssociateDescription<BrightFeature> associateTarget = FactoryAssociation.greedy(scorerTarget, Double.MAX_VALUE, true);

	List<Point2D_F64> pointsTargetRef = new ArrayList<>();
	FastQueue<BrightFeature> descTargetRef = UtilFeature.createQueue(detDescTarget, 100);

	int nFeaturesTarget = -1; // Number of features detected in the captured (sub)image of the HUD
	int nMatchesTarget = -1; // Number of features which could be matched to the reference image
	int nMedianTarget = -1; // Number of matched features which are close to the median translation

	Integer targetX = null; // x of center of target indicator in image
	Integer targetY = null; // y of center of target indicator in image
	Float targetPercentX = null; // percent to the right - 0%=left, 50%=centered, 100%=right
	Float targetPercentY = null; // percent to the bottom - 0%=top, 50%=centered, 100%=bottom

	private Point locateTargetFeature(GrayF32 yellowHudImage) {
		nFeaturesTarget = -1;
		nMatchesTarget = -1;
		nMedianTarget = -1;
		targetX = null;
		targetY = null;
		targetPercentX = null;
		targetPercentY = null;

		detDescTarget.detect(yellowHudImage.subimage(TARGET_REGION_X, TARGET_REGION_Y, TARGET_REGION_X + TARGET_REGION_WIDTH, TARGET_REGION_Y + TARGET_REGION_HEIGHT));
		nFeaturesTarget = detDescTarget.getNumberOfFeatures();
		if (nFeaturesTarget <= 0) {
			return null;
		}
		List<Point2D_F64> pointsImage = new ArrayList<>();
		FastQueue<BrightFeature> descImage = UtilFeature.createQueue(detDescTarget, 100);
		for (int i = 0; i < detDescTarget.getNumberOfFeatures(); i++) {
			pointsImage.add(detDescTarget.getLocation(i).copy());
			descImage.grow().setTo(detDescTarget.getDescription(i));
		}
		associateTarget.setSource(descTargetRef);
		associateTarget.setDestination(descImage);
		associateTarget.associate();
		nMatchesTarget = associateTarget.getMatches().size();
		if (nMatchesTarget < 7) { // We need something to work with
			return null;
		}
		List<Double> distances = new ArrayList<>();
		for (int i = 0; i < associateTarget.getMatches().size(); i++) {
			AssociatedIndex ai = associateTarget.getMatches().get(i);
			Point2D_F64 refPoint = pointsTargetRef.get(ai.src);
			Point2D_F64 imagePoint = pointsImage.get(ai.dst);
			distances.add(refPoint.distance(imagePoint));
		}
		Collections.sort(distances);
		double medianDistance = distances.get(distances.size() / 2);
		List<Integer> dxList = new ArrayList<>();
		List<Integer> dyList = new ArrayList<>();
		for (int i = 0; i < associateTarget.getMatches().size(); i++) {
			AssociatedIndex ai = associateTarget.getMatches().get(i);
			Point2D_F64 refPoint = pointsTargetRef.get(ai.src);
			Point2D_F64 imagePoint = pointsImage.get(ai.dst);
			if (Math.abs(refPoint.distance(imagePoint) - medianDistance) < 10) {
				dxList.add(((int) imagePoint.x + TARGET_REGION_X) - (int) refPoint.x);
				dyList.add(((int) imagePoint.y + TARGET_REGION_Y) - (int) refPoint.y);
			}
		}
		nMedianTarget = dxList.size();
		if (nMedianTarget < (nMatchesTarget / 2)) { // If scattered all around the feature probably isn't visible
			return null;
		}
		Collections.sort(dxList);
		Collections.sort(dyList);
		int medianDx = dxList.get(dxList.size() / 2);
		int medianDy = dyList.get(dyList.size() / 2);
		targetX = (CruiseControlApplication.SCALED_WIDTH / 2) + medianDx;
		targetY = (CruiseControlApplication.SCALED_HEIGHT / 2) + medianDy;
		targetPercentX = (targetX * 100f) / CruiseControlApplication.SCALED_WIDTH;
		targetPercentY = (targetY * 100f) / CruiseControlApplication.SCALED_HEIGHT;
		return new Point(targetX, targetY);
	}

	private void loadRefImages() {
		// REF IMAGES MUST BE 1080p!!!!
		File refDir = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref");
		try {
			this.refCompass = Template.fromFile(new File(refDir, "compass_orange_blurred.png"));
			this.refCompassType9 = Template.fromFile(new File(refDir, "compass_type9_blurred.png"));
			this.refCompassDotFilled = Template.fromFile(new File(refDir, "compass_dot_filled_bluewhite_blurred.png"));
			this.refCompassDotHollow = Template.fromFile(new File(refDir, "compass_dot_hollow_bluewhite_blurred.png"));
			this.refShipHud = Template.fromFile(new File(refDir, "ship_hud_blurred.png"));
			this.refShipHudType9 = Template.fromFile(new File(refDir, "ship_hud_type9_blurred.png"));
			this.refSixSeconds = Template.fromFile(new File(refDir, "six_seconds.png"));
			this.refSevenSeconds = Template.fromFile(new File(refDir, "seven_seconds.png"));
			this.refEightSeconds = Template.fromFile(new File(refDir, "eight_seconds.png"));
			this.refNineSeconds = Template.fromFile(new File(refDir, "nine_seconds.png"));
			this.refTenSeconds = Template.fromFile(new File(refDir, "ten_seconds.png"));
			this.refElevenSeconds = Template.fromFile(new File(refDir, "eleven_seconds.png"));

			detDescTarget.detect(ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "target_yellow_feature.png")), (GrayF32) null)));
			for (int i = 0; i < detDescTarget.getNumberOfFeatures(); i++) {
				pointsTargetRef.add(detDescTarget.getLocation(i).copy());
				descTargetRef.grow().setTo(detDescTarget.getDescription(i));
			}
			logger.debug("Found " + detDescTarget.getNumberOfFeatures() + " features in target_yellow_feature");

			detDescScanning.detect(ImageUtil.normalize255(ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "scanning_feature.png")), (GrayF32) null)));
			for (int i = 0; i < detDescScanning.getNumberOfFeatures(); i++) {
				pointsScanningRef.add(detDescScanning.getLocation(i).copy());
				descScanningRef.grow().setTo(detDescScanning.getDescription(i));
			}
			logger.debug("Found " + detDescScanning.getNumberOfFeatures() + " features in scanning_feature");
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

		if (targetX != null && targetY != null) {
			g.setColor(Color.YELLOW);
			g.setStroke(new BasicStroke(3));
			g.drawLine(CruiseControlApplication.SCALED_WIDTH / 2, CruiseControlApplication.SCALED_HEIGHT / 2, targetX, targetY);
			g.fillOval(CruiseControlApplication.SCALED_WIDTH / 2 - 3, CruiseControlApplication.SCALED_HEIGHT / 2 - 3, 7, 7);
			g.setStroke(new BasicStroke(1));
			//g.drawString(String.format(Locale.US, "%d,%d", targetX, targetY), targetX, targetY);
		}

		if (sixSecondsRegionX != null && sixSecondsRegionY != null) {
			g.setColor(new Color(0, 0, 127));
			g.drawRect(sixSecondsRegionX, sixSecondsRegionY, sixSecondsRegionWidth, sixSecondsRegionHeight);
		}

		if (sixSecondsMatch != null) {
			g.setColor(Color.YELLOW);
			g.drawRect(sixSecondsMatch.getX(), sixSecondsMatch.getY(), sixSecondsMatch.getWidth(), sixSecondsMatch.getHeight());
			g.drawString(String.format(Locale.US, "%.4f", sixSecondsMatch.getErrorPerPixel()), sixSecondsMatch.getX(), sixSecondsMatch.getY());
		}

		if (scanningX != null && scanningY != null) {
			g.setColor(Color.RED);
			g.drawRect(scanningX - 60, scanningY - 25, 120, 50);
			g.drawString("SCANNING...", scanningX - 60, scanningY - 25);
		}

		g.setColor(Color.BLACK);
		g.fillRect(debugImage.getWidth() - 20, 0, 20, debugImage.getHeight());
		if (brightnessAheadLeft > 0) {
			int brightnessRed = 127 + (int) (128.0f * brightnessAheadLeft);
			int brightnessHeight = (int) (brightnessAheadLeft * debugImage.getHeight());
			g.setColor(new Color(brightnessRed, 127, 127));
			g.fillRect(debugImage.getWidth() - 20, debugImage.getHeight() - brightnessHeight, 10, brightnessHeight);
		}
		if (brightnessAheadRight > 0) {
			int brightnessRed = 127 + (int) (128.0f * brightnessAheadRight);
			int brightnessHeight = (int) (brightnessAheadRight * debugImage.getHeight());
			g.setColor(new Color(brightnessRed, 127, 127));
			g.fillRect(debugImage.getWidth() - 10, debugImage.getHeight() - brightnessHeight, 10, brightnessHeight);
		}
		g.setColor(Color.YELLOW);
		g.drawString(String.format(Locale.US, "%.0f%%", brightnessAhead * 100), debugImage.getWidth() - 25, 25);

		long millis = System.currentTimeMillis() - this.lastTick;
		double fps = 1000.0 / Math.max(1, millis);
		g.setColor(new Color(170, 170, 250));
		g.setFont(new Font("Sans Serif", Font.BOLD, 20));
		g.drawString(String.format(Locale.US, "%.2f FPS / %s", fps, this.gameState), 10, 30);
		g.drawString(String.format(Locale.US, "x=%.1f%% / y=%.1f%%", this.xPercent, this.yPercent), 10, 60);
		g.drawString(String.format(Locale.US, "pitchUp=%d%% / pitchDown=%d%%", this.shipControl.getPitchUp(), this.shipControl.getPitchDown()), 10, 90);
		g.drawString(String.format(Locale.US, "rollRight=%d%% / rollLeft=%d%%", this.shipControl.getRollRight(), this.shipControl.getRollLeft()), 10, 120);
		g.drawString(String.format(Locale.US, "yawRight=%d%% / yawLeft=%d%%", this.shipControl.getYawRight(), this.shipControl.getYawLeft()), 10, 150);
		g.drawString(String.format(Locale.US, "throttle=%d%%", this.shipControl.getThrottle()), 10, 180);
		g.drawString(String.format(Locale.US, "fuel=%.1ft / %.1ft (%s)", this.fuelLevel, CruiseControlApplication.maxFuel, CruiseControlApplication.myShip), 10, 210);
		// system=Name ab-c d10
		// known=9 | 1x ELW | 1x WW-TF | 2x HMC | 1x M | 4x Icy
		// guessed=9 | 1x ELW | 1x WW-TF | 1x HMC-TF | 1x HMC | 1x M | 4x Icy
		// scanned=1/9 | 1x ELW
		g.drawString(String.format(Locale.US, "%s | Sol: %d Ly | Colonia: %d Ly", this.currentSystemName, (int) this.currentSystemCoord.distanceTo(new Coord(0, 0, 0)),
				(int) this.currentSystemCoord.distanceTo(new Coord(-9530.5f, -910.28125f, 19808.125f))), 10, 240);
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
					.sorted((b1, b2) -> -1 * new Long(SysmapBody.estimatePayout(b1, this.nextStarClass)).compareTo(new Long(SysmapBody.estimatePayout(b2, this.nextStarClass))))
					.collect(Collectors.toList());
			LinkedHashMap<String, Integer> countByType = new LinkedHashMap<>();
			for (SysmapBody b : bodiesSortedByValue) {
				String abbrType = SysmapBody.getAbbreviatedType(b, this.nextStarClass);
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
		g.drawString(String.format(Locale.US, "[target] feat=%d / match=%d / med=%d", this.nFeaturesTarget, this.nMatchesTarget, this.nMedianTarget), 10, 360);
		g.drawString(String.format(Locale.US, "[scanning] feat=%d / match=%d / med=%d", this.nFeaturesScanning, this.nMatchesScanning, this.nMedianScanning), 10, 390);

		// Valuable systems
		String nextValuable = "";
		if (this.nextValuableSystem != null) {
			nextValuable = this.nextValuableSystem.getName() + " (" + String.format(Locale.US, "%,d CR", this.nextValuableSystem.getPayout()) + ")";
		}
		g.drawString(String.format(Locale.US, "valuable=%d in %d Ly | next=%s", this.valuableSystems.size(), this.lastValuableSystemsRadiusLy, nextValuable), 10, 420);

		// Payouts
		g.setFont(new Font("Monospaced", Font.BOLD, 20));
		String payoutSession = String.format(Locale.US, "+%,12dCR", CruiseControlApplication.explorationPayoutSession);
		String payoutTotal = String.format(Locale.US, "=%,12dCR", CruiseControlApplication.explorationPayoutTotal);
		int minX = Integer.MAX_VALUE;
		int width = payoutTotal.length() * 18;
		for (int y = 75; y <= 100; y += 25) {
			for (int c = 0; c < payoutTotal.length(); c++) {
				int x = ((1920 / 2) - ((payoutTotal.length() / 2) + 1) * 18) + (c * 18);
				minX = Math.min(x, minX);
				g.setColor(new Color(64, 64, 64, 128));
				g.fillRect(x, y, 16, 22);
				g.setColor(Color.RED);
				if (y == 75) {
					g.drawString(Character.toString(payoutSession.charAt(c)), x + 3, y + 17);
				} else if (y == 100) {
					g.drawString(Character.toString(payoutTotal.charAt(c)), x + 3, y + 17);
				}
			}
		}

		g.setColor(new Color(64, 64, 64, 128));
		g.fillRect(minX, 50, width - 2, 22);
		g.setFont(new Font("Sans Serif", Font.BOLD, 14));
		g.setColor(Color.RED);
		double hoursSinceStart = (System.currentTimeMillis() - CruiseControlApplication.APPLICATION_START) / (double) DateUtils.MILLIS_PER_HOUR;
		g.drawString(String.format(Locale.US, "%.1f jumps/h, %.0f Ly/h, %.2fM CR/h", CruiseControlApplication.jumpsSession / hoursSinceStart,
				CruiseControlApplication.lightyearsSession / hoursSinceStart, (CruiseControlApplication.explorationPayoutSession / hoursSinceStart) / 1_000_000), minX + 6, 66);
		g.setColor(new Color(64, 64, 64, 128));
		g.fillRect(minX, 125, width - 2, 22);
		g.setFont(new Font("Sans Serif", Font.BOLD, 14));
		g.setColor(Color.RED);
		g.drawString(String.format(Locale.US, "%d jumps, %,.0f Ly, %s h", CruiseControlApplication.jumpsTotal, CruiseControlApplication.lightyearsTotal,
				DurationFormatUtils.formatDuration(CruiseControlApplication.playtimeMillisLastDock + CruiseControlApplication.playtimeMillisSession, "H:mm")), minX + 6, 66 + 75);

		g.dispose();
	}

	private void drawColoredDebugImage(BufferedImage debugImage, GrayF32 orangeHudImage, GrayF32 yellowHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
		for (int y = 0; y < debugImage.getHeight(); y++) {
			for (int x = 0; x < debugImage.getWidth(); x++) {
				float r = redHudImage.unsafe_get(x, y) * 255;
				float bw = blueWhiteHudImage.unsafe_get(x, y) * 255;
				float ye = yellowHudImage.unsafe_get(x, y) * 255;
				float o = orangeHudImage.unsafe_get(x, y) * 255;
				float b = brightImage.unsafe_get(x, y) * 255;
				if (r > 0) {
					debugImage.setRGB(x, y, new Color((int) r, (int) (r * 0.15f), (int) (r * 0.15f)).getRGB());
				} else if (bw > 0) {
					debugImage.setRGB(x, y, new Color((int) (bw * 0.66f), (int) (bw * 0.66f), (int) bw).getRGB());
				} else if (ye > 0) {
					debugImage.setRGB(x, y, new Color((int) (ye * 0.77f), (int) (ye * 0.66f), 0).getRGB());
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
		} else if (status.getTimestamp().toEpochSecond() * 1000 < CruiseControlApplication.APPLICATION_START) {
			logger.debug(status.getTimestamp() + " = " + status.getTimestamp().toEpochSecond() * 1000 + " < " + CruiseControlApplication.APPLICATION_START);
			return;
		} else {
			CruiseControlApplication.playtimeMillisSession = status.getTimestamp().toEpochSecond() * 1000 - CruiseControlApplication.APPLICATION_START;

			if (status.isLowFuel()) {
				this.doEmergencyExit("Low fuel");
			} else if (status.isInDanger()) {
				this.doEmergencyExit("In danger");
			} else if (!status.isInSupercruise()) {
				this.doEmergencyExit("Dropped from supercruise");
			}

			if (status.isFsdCooldown() && !this.fsdCooldown && this.gameState == GameState.WAIT_FOR_FSD_COOLDOWN) {
				if (!this.jumpTargetIsScoopable) {
					this.gameState = GameState.ESCAPE_FROM_NON_SCOOPABLE;
					this.escapeFromNonScoopableSince = System.currentTimeMillis();
					logger.debug("Jumped in at a non-scoopable star, directly aligning to star escape vector");
				} else {
					this.gameState = GameState.GET_IN_SCOOPING_RANGE;
					this.getInScoopingRangeSince = System.currentTimeMillis();
					logger.debug("FSD cooldown started, getting in scooping range");
				}
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
		try {
			if (event.getTimestamp().toEpochSecond() * 1000 < CruiseControlApplication.APPLICATION_START) {
				logger.debug(event.getTimestamp() + " = " + event.getTimestamp().toEpochSecond() * 1000 + " < " + CruiseControlApplication.APPLICATION_START);
				return;
			} else {
				CruiseControlApplication.playtimeMillisSession = event.getTimestamp().toEpochSecond() * 1000 - CruiseControlApplication.APPLICATION_START;
			}

			if (event instanceof StartJumpEvent) {
				StartJumpEvent startJumpEvent = (StartJumpEvent) event;
				this.shipControl.stopTurning();
				this.inHyperspaceSince = System.currentTimeMillis();
				this.currentSystemName = "";
				this.currentSystemKnownBodies = new ArrayList<>();
				this.currentSystemScannedBodies = new ArrayList<>();
				this.currentSystemNumDiscoveredBodies = 0;
				this.sysmapScannerResult = null;
				this.lastScannedBodyAt = 0;
				this.lastScannedBodyDistanceFromArrival = 0;
				this.jumpTargetIsScoopable = StringUtils.isEmpty(startJumpEvent.getStarClass()) ? false : StarClass.fromJournalValue(startJumpEvent.getStarClass()).isScoopable();
				this.nextStarClass = StringUtils.isEmpty(startJumpEvent.getStarClass()) ? null : StarClass.fromJournalValue(startJumpEvent.getStarClass());
				this.gameState = GameState.IN_HYPERSPACE;
				logger.debug("Jumping through hyperspace to " + startJumpEvent.getStarSystem());
			} else if (event instanceof FSDJumpEvent) {
				FSDJumpEvent fsdJumpEvent = (FSDJumpEvent) event;
				this.cruiseSettings = CruiseSettings.load(); // Reload on every jump
				if (fsdJumpEvent.getStarSystem().equals(this.cruiseSettings.getWaypoints().get(0))) {
					this.cruiseSettings.getWaypoints().remove(0);
					CruiseSettings.save(this.cruiseSettings);
				}
				if (this.cruiseSettings.getWaypoints().isEmpty()) {
					this.doEmergencyExit(REASON_END_OF_PLOTTED_ROUTE);
					return;
				}
				CruiseControlApplication.myVisitedSystems.add(fsdJumpEvent.getStarSystem());
				CruiseControlApplication.jumpsSession++;
				CruiseControlApplication.jumpsTotal++;
				CruiseControlApplication.lightyearsSession += fsdJumpEvent.getJumpDist().floatValue();
				CruiseControlApplication.lightyearsTotal += fsdJumpEvent.getJumpDist().floatValue();
				CruiseControlApplication.explorationPayoutSession += 2000;
				CruiseControlApplication.explorationPayoutTotal += 2000;
				this.fuelLevel = fsdJumpEvent.getFuelLevel().floatValue();
				this.inHyperspaceSince = Long.MAX_VALUE;
				this.waitForFsdCooldownSince = Long.MAX_VALUE;
				this.shipControl.honkDelayed(2000);
				this.honkingSince = System.currentTimeMillis() + 2000;
				this.currentSystemName = fsdJumpEvent.getStarSystem();
				this.currentSystemCoord = fsdJumpEvent.getStarPos();
				this.currentSystemKnownBodies = this.galaxyService.findBodiesByStarSystemName(fsdJumpEvent.getStarSystem());
				if (this.nextValuableSystem != null && fsdJumpEvent.getStarSystem().equals(this.nextValuableSystem.getName())) {
					this.nextValuableSystem = null;
				}
				this.lastValuableSystemsRadiusLy = 75;
				this.valuableSystems = this.lookForValuableSystems(fsdJumpEvent.getStarPos(), this.lastValuableSystemsRadiusLy, this.cruiseSettings.getWaypoints().get(0));
				if (this.valuableSystems.isEmpty()) {
					this.lastValuableSystemsRadiusLy = 150;
					this.valuableSystems = this.lookForValuableSystems(fsdJumpEvent.getStarPos(), this.lastValuableSystemsRadiusLy, this.cruiseSettings.getWaypoints().get(0));
				}
				if (this.valuableSystems.isEmpty()) {
					this.lastValuableSystemsRadiusLy = 300;
					this.valuableSystems = this.lookForValuableSystems(fsdJumpEvent.getStarPos(), this.lastValuableSystemsRadiusLy, this.cruiseSettings.getWaypoints().get(0));
				}
				if (this.valuableSystems.isEmpty()) {
					this.lastValuableSystemsRadiusLy = 600;
					this.valuableSystems = this.lookForValuableSystems(fsdJumpEvent.getStarPos(), this.lastValuableSystemsRadiusLy, this.cruiseSettings.getWaypoints().get(0));
				}
				if (this.valuableSystems.isEmpty()) {
					this.lastValuableSystemsRadiusLy = 1200;
					this.valuableSystems = this.lookForValuableSystems(fsdJumpEvent.getStarPos(), this.lastValuableSystemsRadiusLy, this.cruiseSettings.getWaypoints().get(0));
				}
				if (!this.valuableSystems.isEmpty()) {
					ValuableSystem bestValuableSystem = this.valuableSystems.get(0);
					if (this.nextValuableSystem == null || bestValuableSystem.getPayout() > this.nextValuableSystem.getPayout()) {
						if (this.nextValuableSystem == null || !bestValuableSystem.getName().equals(this.nextValuableSystem.getName())) { // A rare case, but another explorer could just have increased the value of the system we already head to
							this.nextValuableSystem = bestValuableSystem;
							this.gameState = GameState.PLOT_TO_NEXT_VALUABLE_SYSTEM;
							logger.debug("Arrived at " + fsdJumpEvent.getStarSystem() + ", honking and plotting to a new valuable system");
							return;
						}
					}
				} else if (this.nextValuableSystem == null) {
					this.nextValuableSystem = new ValuableSystem(this.cruiseSettings.getWaypoints().get(0), new Coord(), 0);
					this.gameState = GameState.PLOT_TO_NEXT_VALUABLE_SYSTEM;
					logger.debug("Arrived at " + fsdJumpEvent.getStarSystem() + ", honking and plotting to the next waypoint system");
					return;
				}
				this.gameState = GameState.WAIT_FOR_FSD_COOLDOWN;
				this.waitForFsdCooldownSince = System.currentTimeMillis();
				logger.debug("Arrived at " + fsdJumpEvent.getStarSystem() + ", honking and waiting for FSD cooldown to start");
			} else if (event instanceof FuelScoopEvent) {
				this.fuelLevel = ((FuelScoopEvent) event).getTotal().floatValue();
			} else if (event instanceof DiscoveryScanEvent) {
				this.currentSystemNumDiscoveredBodies += MiscUtil.getAsInt(((DiscoveryScanEvent) event).getBodies(), 0);
			} else if (event instanceof FSSDiscoveryScanEvent) {
				this.currentSystemNumDiscoveredBodies = MiscUtil.getAsInt(((FSSDiscoveryScanEvent) event).getBodyCount(), 0);
			} else if (event instanceof ScanEvent) {
				ScanEvent scanEvent = (ScanEvent) event;
				this.shipControl.stopTurning();
				CruiseControlApplication.explorationPayoutSession += BodyUtil.estimatePayout(scanEvent);
				CruiseControlApplication.explorationPayoutTotal += BodyUtil.estimatePayout(scanEvent);
				this.lastScannedBodyAt = System.currentTimeMillis();
				this.lastScannedBodyDistanceFromArrival = MiscUtil.getAsFloat(scanEvent.getDistanceFromArrivalLS(), 0f);
				this.shipControl.selectNextSystemInRoute();
				this.currentSystemScannedBodies.add((ScanEvent) event);
				if (this.currentSysmapBody != null) {
					this.shipControl.setThrottle(0);
					this.shipControl.stopTurning();
					this.currentSysmapBody.unexplored = false;

					String scannedBodyType = scanEvent.getPlanetClass();
					if (StringUtils.isEmpty(scannedBodyType)) {
						scannedBodyType = scanEvent.getStarType();
					}

					// Learn
					if (this.currentSysmapBody.bestBodyMatch != null) {
						String guessedBodyType = this.currentSysmapBody.bestBodyMatch.getTemplate().getName();
						if (StringUtils.isNotEmpty(scannedBodyType) && !scannedBodyType.equals(guessedBodyType)) {
							logger.warn("Wrongly guessed " + guessedBodyType + ", but was " + scannedBodyType);
							if (this.currentSysmapBody.bestBodyMatch.getErrorPerPixel() <= 0.001f) {
								logger.debug("Deleting " + this.currentSysmapBody.bestBodyMatch.getTemplate().getFile() + " because the error/pixel was very low, but the result was wrong");
								this.currentSysmapBody.bestBodyMatch.getTemplate().getFile().delete();
							}
							try {
								BufferedImage planetImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(this.currentSysmapBody.bestBodyMatch.getImage()), null, true);
								File refFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref/sysmapBodies/" + scannedBodyType);
								if (!refFolder.exists()) {
									refFolder.mkdirs();
								}
								final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());
								ImageIO.write(planetImage, "PNG", new File(refFolder, scannedBodyType + " " + ts + " " + this.currentSystemName + ".png"));
								this.sysmapScanner.reloadTemplates();
								this.sysmapScanner.guessBodyTypes(this.sysmapScannerResult);
							} catch (IOException e) {
								logger.warn("Failed to write planet ref image", e);
							}
						} else {
							logger.info("Correctly guessed " + guessedBodyType + ", and was " + scannedBodyType);
						}
					} else {
						logger.warn("Had no idea that it was " + scannedBodyType);
						try {
							int x0 = this.currentSysmapBody.areaInImage.x;
							int y0 = this.currentSysmapBody.areaInImage.y;
							int x1 = this.currentSysmapBody.areaInImage.x + this.currentSysmapBody.areaInImage.width;
							int y1 = this.currentSysmapBody.areaInImage.y + this.currentSysmapBody.areaInImage.height;
							BufferedImage planetImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(this.sysmapScannerResult.getRgb().subimage(x0, y0, x1, y1)), null, true);
							File refFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref/sysmapBodies/" + scannedBodyType);
							if (!refFolder.exists()) {
								refFolder.mkdirs();
							}
							final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());
							ImageIO.write(planetImage, "PNG", new File(refFolder, scannedBodyType + " " + ts + " " + this.currentSystemName + ".png"));
							this.sysmapScanner.reloadTemplates();
							this.sysmapScanner.guessBodyTypes(this.sysmapScannerResult);
						} catch (IOException e) {
							logger.warn("Failed to write planet ref image", e);
						}
					}

					this.currentSysmapBody = null;
					if (this.nextBodyToScan() == null) {
						this.shipControl.setThrottle(0);
						this.shipControl.selectNextSystemInRoute();
						this.gameState = GameState.ALIGN_TO_NEXT_SYSTEM;
						logger.debug("All bodies scanned, aligning to next jump target at 0% throttle");
					} else {
						this.shipControl.setThrottle(0);
						this.shipControl.stopTurning();
						logger.debug("Open system map");
						this.shipControl.toggleSystemMap();
						this.gameState = GameState.WAIT_FOR_SYSTEM_MAP;
						logger.debug(scanEvent.getBodyName() + " scanned, waiting for system map at stand-still");
					}
				}
			} else if (event instanceof ReceiveTextEvent) {
				if ("npc".equalsIgnoreCase(((ReceiveTextEvent) event).getChannel()) && ((ReceiveTextEvent) event).getMessage() != null
						&& !((ReceiveTextEvent) event).getMessage().startsWith("$COMMS")) {
					this.doEmergencyExit(REASON_COMBAT_LOG);
					return;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private List<ValuableSystem> lookForValuableSystems(final Coord myCoord, int rangeLy, String nextWaypoint) {
		try {
			logger.debug("Searching for valuable systems within " + rangeLy + " Ly around " + myCoord + " on the way to " + nextWaypoint);
			final Coord nextWaypointCoord = this.galaxyService.findStarSystemByName(nextWaypoint).getCoord();
			final float nextWaypointDistance = myCoord.distanceTo(nextWaypointCoord);

			Map<String, Coord> candidateSystems = new HashMap<>();
			Page<Body> page = this.galaxyService.findPlanetsNear(myCoord, rangeLy, null, Arrays.asList(PlanetClass.EARTHLIKE_BODY, PlanetClass.AMMONIA_WORLD, PlanetClass.WATER_WORLD),
					PageRequest.of(0, 10000));
			page.getContent().stream().forEach(b -> candidateSystems.put(b.getStarSystemName(), b.getCoord()));
			logger.debug("Found " + candidateSystems.size() + " candidate system(s)");

			List<ValuableSystem> valuableSystems = new ArrayList<>();
			int nVisited = 0;
			int nStupidName = 0;
			int nTooFar = 0;
			int nNotOnWay = 0;
			int nNonScoopable = 0;
			int nMissingStarSystem = 0;
			int nPopulated = 0;
			int nNotValuable = 0;
			for (String candidateSystemName : candidateSystems.keySet()) {
				if (CruiseControlApplication.myVisitedSystems.contains(candidateSystemName)) {
					nVisited++;
					continue;
				} else if (candidateSystemName.contains("+") || candidateSystemName.contains("(") || candidateSystemName.contains(")")) {
					nStupidName++;
					continue;
				}
				Coord candidateSystemCoord = candidateSystems.get(candidateSystemName);
				float distanceToCandidate = myCoord.distanceTo(candidateSystemCoord);
				if (distanceToCandidate > rangeLy) {
					nTooFar++;
					continue; // The ES query uses a box, not direct distance
				}
				float candidateDistanceToNextWaypoint = candidateSystemCoord.distanceTo(nextWaypointCoord);
				float maxAllowedDistance = nextWaypointDistance - (distanceToCandidate / 2); // At least half of the way must be towards our next waypoint
				if (candidateDistanceToNextWaypoint > maxAllowedDistance) {
					nNotOnWay++;
				} else {
					// Check scoopable
					List<Body> bodies = this.galaxyService.findBodiesByStarSystemName(candidateSystemName);
					List<Body> arrivalStars = bodies.stream().filter(b -> b.getStarClass() != null && (b.getDistanceToArrivalLs() == null || b.getDistanceToArrivalLs().floatValue() == 0.0f))
							.collect(Collectors.toList());
					boolean nonScoopableArrivalStar = arrivalStars.size() == 1 && !arrivalStars.get(0).getStarClass().isScoopable();
					if (nonScoopableArrivalStar) {
						nNonScoopable++;
					} else {
						// Check population
						StarSystem starSystem = this.galaxyService.findStarSystemByName(candidateSystemName);
						if (starSystem == null) {
							nMissingStarSystem++;
						} else if (starSystem.getPopulation().compareTo(BigDecimal.ZERO) > 0) {
							nPopulated++;
						} else {
							List<Body> valuableBodies = bodies.stream()
									.filter(b -> b.getDistanceToArrivalLs() != null && ((BodyUtil.estimatePayout(b) >= 200000 && b.getDistanceToArrivalLs().intValue() < 23456)
											|| (BodyUtil.estimatePayout(b) >= 500000 && b.getDistanceToArrivalLs().intValue() < 56789)))
									.collect(Collectors.toList());
							long payout = 0;
							for (Body b : valuableBodies) {
								payout += BodyUtil.estimatePayout(b);
							}
							if (payout < 500_000) {
								//logger.debug(String.format(Locale.US, "%,20d CR: %s", payout, candidateSystemName));
								nNotValuable++;
							} else {
								valuableSystems.add(new ValuableSystem(candidateSystemName, candidateSystemCoord, payout));
							}
						}
					}
				}
			}
			logger.debug("Kept " + valuableSystems.size() + " system(s) which are indeed valuable and on our way" + " (nVisited=" + nVisited + ", nStupidName=" + nStupidName + ", nTooFar="
					+ nTooFar + ", nNotOnWay=" + nNotOnWay + ", nNonScoopable=" + nNonScoopable + ", nMissingStarSystem=" + nMissingStarSystem + ", nPopulated=" + nPopulated
					+ ", nNotValuable=" + nNotValuable + ")");

			// Sort by distance (in 25 ly increments), but finally by value
			Collections.sort(valuableSystems, new Comparator<ValuableSystem>() {
				@Override
				public int compare(ValuableSystem s1, ValuableSystem s2) {
					Float d1 = new Float(s1.getCoord().distanceTo(myCoord));
					Float d2 = new Float(s2.getCoord().distanceTo(myCoord));
					Integer di1 = new Integer(d1.intValue() / 25);
					Integer di2 = new Integer(d2.intValue() / 25);
					return di1.compareTo(di2);
				}
			});
			Collections.sort(valuableSystems, new Comparator<ValuableSystem>() {
				@Override
				public int compare(ValuableSystem s1, ValuableSystem s2) {
					return -1 * new Long(s1.getPayout()).compareTo(new Long(s2.getPayout()));
				}
			});

			return valuableSystems;
		} catch (Exception e) {
			logger.error("Failed to look for valuable systems", e);
			return new ArrayList<>();
		}
	}

	private boolean clickOnNextBodyOnSystemMap(ScreenConverterResult screenConverterResult) {
		final long start = System.currentTimeMillis();
		final Random random = new Random();
		this.currentSysmapBody = this.nextBodyToScan();
		SysmapBody b = this.currentSysmapBody;

		SysmapBody hovered = new SysmapBody(new Rectangle(b.areaInImage));
		hovered.centerOnScreen = new Point(b.centerOnScreen);

		try {
			this.sysmapScanner.ensureDetailsTabIsVisible();

			Planar<GrayF32> rgb = null;
			Planar<GrayF32> hsv = null;
			// Hover over body, wait until data is displayed and extract
			this.shipControl.mouseMoveOnScreen((b.centerOnScreen.x - 5) + random.nextInt(10), (b.centerOnScreen.y - 5) + random.nextInt(10));
			Thread.sleep(250 + random.nextInt(250));
			while ((System.currentTimeMillis() - start) < 1500L) {
				synchronized (screenConverterResult) {
					screenConverterResult.wait();
					rgb = screenConverterResult.getRgb().clone();
					hsv = screenConverterResult.getHsv().clone();
				}
				if (this.sysmapScanner.extractBodyData(rgb, hsv, hovered)) {
					break;
				}
			}

			// Check
			if (!hovered.hasSameData(b)) {
				final Point p0 = new Point(0, 0);
				SysmapBody leftmostBody = this.sysmapScannerResult.getBodies().stream()
						.sorted((b1, b2) -> new Double(b1.centerOnScreen.distance(p0)).compareTo(new Double(b2.centerOnScreen.distance(p0)))).findFirst().orElse(null);
				this.shipControl.mouseMoveOnScreen((leftmostBody.centerOnScreen.x - 5) + random.nextInt(10), (leftmostBody.centerOnScreen.y - 5) + random.nextInt(10));
				Thread.sleep(250 + random.nextInt(250));

				// Try again
				this.shipControl.mouseMoveOnScreen((b.centerOnScreen.x - 5) + random.nextInt(10), (b.centerOnScreen.y - 5) + random.nextInt(10));
				Thread.sleep(250 + random.nextInt(250));
				while ((System.currentTimeMillis() - start) < 4500L) {
					synchronized (screenConverterResult) {
						screenConverterResult.wait();
						rgb = screenConverterResult.getRgb().clone();
						hsv = screenConverterResult.getHsv().clone();
					}
					if (this.sysmapScanner.extractBodyData(rgb, hsv, hovered)) {
						break;
					}
				}
			}

			// Check again
			if (!hovered.hasSameData(b)) {
				logger.warn("Did not find " + b + " in sysmap, instead found " + hovered);
				try {
					File debugFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/debug");
					if (!debugFolder.exists()) {
						debugFolder = new File(System.getProperty("user.home"), "CruiseControl/debug");
					}
					final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());

					BufferedImage debugImage = ConvertBufferedImage.convertTo_F32(ImageUtil.denormalize255(rgb), null, true);
					ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + CruiseControlApplication.myCommanderName + " " + ts + " WRONG_BODY_DATA_RGB.png"));

					GrayF32 grayDebugImage = ConvertImage.average(rgb, null);
					grayDebugImage = GBlurImageOps.gaussian(grayDebugImage, null, -1, 3, null);
					for (int y = 0; y < hsv.height; y++) {
						for (int x = 0; x < hsv.width; x++) {
							float v = hsv.bands[2].unsafe_get(x, y);
							float s = hsv.bands[1].unsafe_get(x, y);
							if (v < 0.45f || s > 0.2f) {
								grayDebugImage.unsafe_set(x, y, 0f);
							}
						}
					}

					debugImage = ConvertBufferedImage.convertTo(ImageUtil.denormalize255(grayDebugImage), null);
					ImageIO.write(debugImage, "PNG", new File(debugFolder, "DEBUG " + CruiseControlApplication.myCommanderName + " " + ts + " WRONG_BODY_DATA_GRAY.png"));
				} catch (IOException e) {
					logger.error("Failed to write debug image", e);
				}
				return false;
			} else {
				logger.debug("Found " + b + " and clicked on it, now waiting for target button to click on");
				return this.sysmapScanner.clickOnTargetButton();
			}
		} catch (InterruptedException e) {
			logger.warn("Interrupted while clicking on system map", e);
			return false;
		} catch (Exception e) {
			logger.error("Exception while clicking on system map", e);
			return false;
		}
	}

	private SysmapBody nextBodyToScan() {
		if (this.cruiseSettings.isCreditsMode()) {
			return this.sysmapScannerResult.getBodies().stream().filter( //
					b -> b.unexplored && // Of course only unexplored
							((b.distanceLs != null && ((SysmapBody.estimatePayout(b, this.nextStarClass) >= 200000 && b.distanceLs.intValue() < 23456)
									|| (SysmapBody.estimatePayout(b, this.nextStarClass) >= 500000 && b.distanceLs.intValue() < 56789)))))
					.sorted(new SensibleScanOrderComparator()).findFirst().orElse(null);
		} else {
			final boolean alreadyScannedStar = false; //this.currentSystemScannedBodies.stream().filter(e -> StringUtils.isNotEmpty(e.getStarType())).findFirst().isPresent();
			final boolean isCloseToEntryStar = this.lastScannedBodyDistanceFromArrival < 1000;
			final boolean allowStars = !alreadyScannedStar && !isCloseToEntryStar;
			return this.sysmapScannerResult.getBodies().stream().filter( //
					b -> b.unexplored && // Of course only unexplored
							b.moonMasses == null && // No belts please
							(!allowStars ? b.solarMasses == null : true) && // No further stars if already scanned one
							(b.earthMasses == null || b.earthMasses.floatValue() > 0.0099f) && // Not every mini-moon (0.99% and less earth masses) pls
							(b.bestBodyMatch == null || b.bestBodyMatch.getErrorPerPixel() > 0.01f || SysmapBody.estimatePayout(b, this.nextStarClass) > 1000) && // No icy/rocky/ricer
							((b.distanceLs != null && (b.distanceLs.intValue() <= 12345 || (SysmapBody.estimatePayout(b, this.nextStarClass) >= 200000 && b.distanceLs.intValue() < 23456)
									|| (SysmapBody.estimatePayout(b, this.nextStarClass) >= 500000 && b.distanceLs.intValue() < 56789))) || (b.solarMasses != null))) // Only close distance (or stars)
					.sorted(new SensibleScanOrderComparator()).findFirst().orElse(null);
		}
	}

}
