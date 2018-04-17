package borg.ed.cruisecontrol;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.Planar;
import borg.ed.cruisecontrol.templatematching.TemplateMatch;
import borg.ed.cruisecontrol.templatematching.TemplateMatcher;

public class CruiseControlThread extends Thread {

	static final Logger logger = LoggerFactory.getLogger(CruiseControlThread.class);

	private final Robot robot;
	private final Rectangle screenRect;
	private final ShipControl shipControl;

	private GrayF32 refCompass = null;
	private GrayF32 refCompassMask = null;
	private GrayF32 refCompassDotFilled = null;
	private GrayF32 refCompassDotHollow = null;
	private GrayF32 refTarget = null;
	private GrayF32 refTargetMask = null;
	private GrayF32 refImpact = null;
	private GrayF32 refImpactMask = null;
	private GrayF32 refCruise30kms = null;
	private GrayF32 refCruise30kmsMask = null;

	private GameState gameState = GameState.UNKNOWN;
	private long jumpInitiated = 0;
	private long honkInitiated = 0;
	private long escapeInitiated = 0;
	private int xPercent = 0;
	private int yPercent = 0;
	private boolean hollow = false;
	private float brightnessAhead = 0;
	private long lastTick = System.currentTimeMillis();

	private static final int STAR_IN_FRONT_REGION_X = 926 - 40;
	private static final int STAR_IN_FRONT_REGION_Y = 812 - 25;
	private static final int STAR_IN_FRONT_REGION_WIDTH = 64 + 80;
	private static final int STAR_IN_FRONT_REGION_HEIGHT = 60 + 125;
	private boolean starInFront = false;

	private static final int IMPACT_REGION_X = 1605 - 20;
	private static final int IMPACT_REGION_Y = 45 - 20;
	private static final int IMPACT_REGION_WIDTH = 90 + 40;
	private static final int IMPACT_REGION_HEIGHT = 30 + 40;
	private TemplateMatch impactMatch = null;

	private static final int COMPASS_REGION_X = 620;
	private static final int COMPASS_REGION_Y = 777;
	private static final int COMPASS_REGION_WIDTH = 190;
	private static final int COMPASS_REGION_HEIGHT = 200;
	private TemplateMatch compassMatch = null;

	private static final int TARGET_REGION_X = 600;
	private static final int TARGET_REGION_Y = 190;
	private static final int TARGET_REGION_WIDTH = 700;
	private static final int TARGET_REGION_HEIGHT = 500;
	private TemplateMatch targetMatch = null;

	private static final int CRUISE30KMS_REGION_X = 1040;
	private static final int CRUISE30KMS_REGION_Y = 820;
	private static final int CRUISE30KMS_REGION_WIDTH = 130;
	private static final int CRUISE30KMS_REGION_HEIGHT = 80;
	private TemplateMatch cruise30kmsMatch = null;

	private List<DebugImageListener> debugImageListeners = new ArrayList<>();

	public CruiseControlThread(Robot robot, Rectangle screenRect) {
		this.setName("CCThread");
		this.setDaemon(true);

		this.robot = robot;
		this.screenRect = screenRect;
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

		//		Planar<GrayF32> rgb = new Planar<>(GrayF32.class, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, 3);
		//		Planar<GrayF32> hsv = rgb.createSameShape();
		GrayF32 orangeHudImage = new GrayF32(CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);
		GrayF32 blueWhiteHudImage = orangeHudImage.createSameShape();
		GrayF32 redHudImage = orangeHudImage.createSameShape();
		GrayF32 brightImage = orangeHudImage.createSameShape();
		BufferedImage debugImage = new BufferedImage(CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT, BufferedImage.TYPE_INT_RGB);

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
				impactMatch = null; //locateImpact(redHudImage);
				starInFront = this.isCloseToStarInFront(redHudImage);
				if (gameState == GameState.UNKNOWN || gameState == GameState.COMPASS_ALIGNING_JUMP || gameState == GameState.COMPASS_ALIGNING_STAR
						|| gameState == GameState.COMPASS_ALIGNING_ESCAPE || gameState == GameState.COMPASS_ALIGNED_STAR) {
					compassMatch = locateCompassSmart(orangeHudImage);
					targetMatch = locateTargetSmart(orangeHudImage);
				} else {
					compassMatch = null;
					targetMatch = null;
				}
				if (gameState == GameState.JUMPING) {
					cruise30kmsMatch = locateCruise30kms(orangeHudImage);
				} else {
					cruise30kmsMatch = null;
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
					listener.onNewDebugImage(debugImage, orangeHudImage, blueWhiteHudImage, redHudImage);
				}
				// <<<< DEBUG IMAGE <<<<

				switch (this.gameState) {
				case UNKNOWN:
					this.shipControl.releaseAllKeys();
					if (compassMatch != null) {
						this.gameState = GameState.COMPASS_ALIGNING_JUMP;
					}
					break;
				case COMPASS_ALIGNING_JUMP:
					if (this.alignToTargetInCompass(compassMatch, compassDotMatch, hollow)) {
						this.gameState = GameState.COMPASS_ALIGNED_JUMP;
					}
					break;
				case COMPASS_ALIGNED_JUMP:
					this.shipControl.stopTurning();
					this.shipControl.setThrottle(100);
					this.shipControl.toggleFsd();
					this.gameState = GameState.JUMPING;
					this.jumpInitiated = System.currentTimeMillis();
					break;
				case JUMPING:
					// 30s from normal space 0 m/s to being in hyperspace
					// 22s from supercruise 30 km/s to being in hyperspace, dropout > 30s
					if (System.currentTimeMillis() - this.jumpInitiated > 30000 && this.shipControl.getThrottle() > 0) {
						this.shipControl.setThrottle(0);
					}
					if (cruise30kmsMatch != null) {
						this.gameState = GameState.HONK_AND_SELECT;
						this.honkInitiated = System.currentTimeMillis();
						this.honkAndSelect();
					}
					break;
				case HONK_AND_SELECT:
					if (System.currentTimeMillis() - this.honkInitiated > 18000) {
						this.gameState = GameState.COMPASS_ALIGNING_STAR;
					}
					break;
				case COMPASS_ALIGNING_STAR:
					if (this.alignToTargetInCompass(compassMatch, compassDotMatch, hollow)) {
						this.gameState = GameState.COMPASS_ALIGNED_STAR;
					}
					break;
				case COMPASS_ALIGNED_STAR:
					// Continue aligning
					this.alignToTargetInCompass(compassMatch, compassDotMatch, hollow);
					// Accelerate until in scooping range
					if (!this.starInFront && this.shipControl.getThrottle() < 25) {
						this.shipControl.setThrottle(25);
					} else if (this.starInFront && this.shipControl.getThrottle() > 0) {
						this.shipControl.setThrottle(0);
						this.gameState = GameState.COMPASS_ALIGNING_ESCAPE;
					}
					break;
				case COMPASS_ALIGNING_ESCAPE:
					if (this.starInFront) {
						this.shipControl.setPitchUp(100);
					} else if (this.alignToEscapeInCompass(compassMatch, compassDotMatch, hollow)) {
						this.gameState = GameState.COMPASS_ALIGNED_ESCAPE;
						this.escapeInitiated = System.currentTimeMillis();
						this.shipControl.stopTurning();
					}
					break;
				case COMPASS_ALIGNED_ESCAPE:
					if (System.currentTimeMillis() - this.escapeInitiated < 15000) {
						if (this.shipControl.getThrottle() != 25) {
							this.shipControl.setThrottle(25);
						}
					} else if (System.currentTimeMillis() - this.escapeInitiated < 23000) {
						if (this.shipControl.getThrottle() != 50) {
							this.shipControl.setThrottle(50);
						}
					} else if (System.currentTimeMillis() - this.escapeInitiated < 30000) {
						if (this.shipControl.getThrottle() != 75) {
							this.shipControl.setThrottle(75);
						}
					} else {
						this.shipControl.selectNextSystemInRoute();
						this.gameState = GameState.COMPASS_ALIGNING_JUMP;
					}
					if (this.starInFront) {
						this.shipControl.setPitchUp(100);
						this.shipControl.setRollRight(25);
					} else {
						this.shipControl.setPitchUp(0);
						this.shipControl.setRollRight(0);
					}
					break;
				default:
					// Honk: 6s
					this.shipControl.fullStop();
					System.exit(-1);
					break;
				}

				logger.debug("Finished processing HUD images");
				long millis = System.currentTimeMillis() - this.lastTick;
				double fps = 1000.0 / Math.max(1, millis);
				System.out.println(String.format(Locale.US, "%6.1f", fps));
				//			} catch (InterruptedException e) {
				//				break; // Quit
			} catch (Exception e) {
				logger.error(this.getName() + " crashed", e);
				break; // Quit
			}
		}

		logger.info(this.getName() + " stopped");
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
				if (yPercent < 50) {
					if (yPercent < 25) {
						this.shipControl.setPitchUp(100);
					} else {
						this.shipControl.setPitchUp(Math.max(10, (50 - yPercent) * 2));
					}
				} else {
					if (yPercent > 75) {
						this.shipControl.setPitchDown(100);
					} else {
						this.shipControl.setPitchDown(Math.max(10, (yPercent - 50) * 2));
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

	private boolean isCloseToStarInFront(GrayF32 redHudImage) {
		float total = 0;
		for (int x = STAR_IN_FRONT_REGION_X; x < STAR_IN_FRONT_REGION_X + STAR_IN_FRONT_REGION_WIDTH; x++) {
			for (int y = STAR_IN_FRONT_REGION_Y; y < STAR_IN_FRONT_REGION_Y + STAR_IN_FRONT_REGION_HEIGHT; y++) {
				total += redHudImage.unsafe_get(x, y) / 255f;
				if (total >= 500f) {
					System.out.println(total);
					return true;
				}
			}
		}
		System.out.println(total);
		return false;
	}

	private TemplateMatch locateImpact(GrayF32 redHudImage) {
		TemplateMatch m = this.locateTemplateInRegion(redHudImage, IMPACT_REGION_X, IMPACT_REGION_Y, IMPACT_REGION_WIDTH, IMPACT_REGION_HEIGHT, this.refImpact, this.refImpactMask);
		return m.getErrorPerPixel() < 0.08f ? m : null;
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
				startX, startY, 0.08f);
		return m.getErrorPerPixel() < 0.08f ? m : null;
	}

	private TemplateMatch locateCruise30kms(GrayF32 orangeHudImage) {
		TemplateMatch m = this.locateTemplateInRegion(orangeHudImage, CRUISE30KMS_REGION_X, CRUISE30KMS_REGION_Y, CRUISE30KMS_REGION_WIDTH, CRUISE30KMS_REGION_HEIGHT, this.refCruise30kms,
				this.refCruise30kmsMask);
		return m.getErrorPerPixel() < 0.10f ? m : null;
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
		// REF IMAGES MUST BE 1440p!!!!
		File refDir = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/ref");
		try {
			this.refImpact = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "impact.png")), (GrayF32) null);
			this.refImpactMask = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "impact_mask.png")), (GrayF32) null);
			this.refCompass = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_orange.png")), (GrayF32) null);
			this.refCompassMask = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_orange_mask.png")), (GrayF32) null);
			this.refCompassDotFilled = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_dot_filled_bluewhite.png")), (GrayF32) null);
			this.refCompassDotHollow = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "compass_dot_hollow_bluewhite.png")), (GrayF32) null);
			this.refTarget = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "target.png")), (GrayF32) null);
			this.refTargetMask = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "target_mask.png")), (GrayF32) null);
			this.refCruise30kms = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "cruise_30kms.png")), (GrayF32) null);
			this.refCruise30kmsMask = ConvertBufferedImage.convertFrom(ImageIO.read(new File(refDir, "cruise_30kms_mask.png")), (GrayF32) null);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load ref images", e);
		}
	}

	private void hsvToHudImages(Planar<GrayF32> hsv, GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage) {
		for (int y = 0; y < hsv.height; y++) {
			for (int x = 0; x < hsv.width; x++) {
				float h = hsv.bands[0].unsafe_get(x, y);
				float s = hsv.bands[1].unsafe_get(x, y);
				float v = hsv.bands[2].unsafe_get(x, y) / 255f;

				if ((s > 0.70f) && (v >= 0.50f) && (h >= 0.25f && h < 1.00f)) {
					// Orange
					orangeHudImage.unsafe_set(x, y, v * 255);
				} else {
					orangeHudImage.unsafe_set(x, y, 0);
				}

				if (v >= 0.75f && s < 0.15f) {
					// White
					blueWhiteHudImage.unsafe_set(x, y, v * v * 255);
				} else if ((h > 3.14f && h < 3.84f) && s > 0.15f) {
					// Blue-white
					blueWhiteHudImage.unsafe_set(x, y, v * v * 255);
				} else {
					blueWhiteHudImage.unsafe_set(x, y, 0);
				}

				if ((s > 0.80f) && (v >= 0.70f) && (h < 0.25f || h > 6.0f)) {
					// Red
					redHudImage.unsafe_set(x, y, v * v * v * 255);
				} else {
					redHudImage.unsafe_set(x, y, 0);
				}
			}
		}
	}

	private void drawDebugInfoOnImage(BufferedImage debugImage, TemplateMatch compassDotMatch, boolean hollow) {
		Graphics2D g = debugImage.createGraphics();

		g.setColor(new Color(127, 0, 0));
		g.drawRect(STAR_IN_FRONT_REGION_X, STAR_IN_FRONT_REGION_Y, STAR_IN_FRONT_REGION_WIDTH, STAR_IN_FRONT_REGION_HEIGHT);

		g.setColor(new Color(127, 0, 0));
		g.drawRect(IMPACT_REGION_X, IMPACT_REGION_Y, IMPACT_REGION_WIDTH, IMPACT_REGION_HEIGHT);

		if (impactMatch != null) {
			g.setColor(new Color(255, 0, 0));
			g.drawRect(impactMatch.getX(), impactMatch.getY(), impactMatch.getWidth(), impactMatch.getHeight());
			g.drawString(String.format(Locale.US, "%.4f", impactMatch.getErrorPerPixel()), impactMatch.getX(), impactMatch.getY());
		}

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

		g.setColor(new Color(127, 63, 0));
		g.drawRect(CRUISE30KMS_REGION_X, CRUISE30KMS_REGION_Y, CRUISE30KMS_REGION_WIDTH, CRUISE30KMS_REGION_HEIGHT);

		if (cruise30kmsMatch != null) {
			g.setColor(new Color(255, 127, 0));
			g.drawRect(cruise30kmsMatch.getX(), cruise30kmsMatch.getY(), cruise30kmsMatch.getWidth(), cruise30kmsMatch.getHeight());
			g.drawString(String.format(Locale.US, "%.4f", cruise30kmsMatch.getErrorPerPixel()), cruise30kmsMatch.getX(), cruise30kmsMatch.getY());
		}

		g.setColor(Color.BLACK);
		g.fillRect(debugImage.getWidth() - 20, 0, 20, debugImage.getHeight());
		if (brightnessAhead > 0) {
			int brightnessRed = 127 + (int) (128.0f * brightnessAhead);
			int brightnessHeight = (int) (brightnessAhead * debugImage.getHeight());
			g.setColor(new Color(brightnessRed, 127, 127));
			g.fillRect(debugImage.getWidth() - 20, debugImage.getHeight() - brightnessHeight, 20, brightnessHeight);
		}

		long millis = System.currentTimeMillis() - this.lastTick;
		double fps = 1000.0 / Math.max(1, millis);
		g.setColor(Color.YELLOW);
		g.setFont(new Font("Sans Serif", Font.BOLD, 20));
		g.drawString(String.format(Locale.US, "%.2f FPS / %s", fps, this.gameState), 10, 30);
		g.drawString(String.format(Locale.US, "x=%d%% / y=%d%% / hollow=%s", this.xPercent, this.yPercent, this.hollow), 10, 60);
		g.drawString(String.format(Locale.US, "pitchUp=%d%% / pitchDown=%d%%", this.shipControl.getPitchUp(), this.shipControl.getPitchDown()), 10, 90);
		g.drawString(String.format(Locale.US, "rollRight=%d%% / rollLeft=%d%%", this.shipControl.getRollRight(), this.shipControl.getRollLeft()), 10, 120);
		g.drawString(String.format(Locale.US, "yawRight=%d%% / yawLeft=%d%%", this.shipControl.getYawRight(), this.shipControl.getYawLeft()), 10, 150);
		g.drawString(String.format(Locale.US, "throttle=%d%%", this.shipControl.getThrottle()), 10, 180);

		g.dispose();
	}

	private void drawColoredDebugImage(BufferedImage debugImage, GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
		for (int y = 0; y < debugImage.getHeight(); y++) {
			for (int x = 0; x < debugImage.getWidth(); x++) {
				float r = redHudImage.unsafe_get(x, y);
				float bw = blueWhiteHudImage.unsafe_get(x, y);
				float o = orangeHudImage.unsafe_get(x, y);
				float b = brightImage.unsafe_get(x, y);
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

}
