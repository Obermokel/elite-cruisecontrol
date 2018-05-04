package borg.ed.cruisecontrol;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import borg.ed.cruisecontrol.util.MouseUtil;

public class ShipControl {

	static final Logger logger = LoggerFactory.getLogger(ShipControl.class);

	public static final String SHIP_ANACONDA = "Anaconda";
	public static final String SHIP_ORCA = "Orca";
	public static final String SHIP_TYPE9 = "Type9";

	public static final Map<String, Float> PITCH_FACTOR = new HashMap<>();
	public static final Map<String, Float> ROLL_FACTOR = new HashMap<>();
	public static final Map<String, Float> YAW_FACTOR = new HashMap<>();

	static {
		PITCH_FACTOR.put(SHIP_ORCA, 2.5f);
		ROLL_FACTOR.put(SHIP_ORCA, 1.5f);
		YAW_FACTOR.put(SHIP_ORCA, 4.5f);

		PITCH_FACTOR.put(SHIP_ORCA, 1.0f);
		ROLL_FACTOR.put(SHIP_ORCA, 1.0f);
		YAW_FACTOR.put(SHIP_ORCA, 1.0f);

		PITCH_FACTOR.put(SHIP_TYPE9, 3.0f);
		ROLL_FACTOR.put(SHIP_TYPE9, 4.0f);
		YAW_FACTOR.put(SHIP_TYPE9, 4.5f);
	}

	public static final int UI_NEXT_TAB = KeyEvent.VK_PAGE_UP;
	public static final int UI_PREV_TAB = KeyEvent.VK_PAGE_DOWN;
	public static final int DEPLOY_HEATSINK = KeyEvent.VK_H;

	private final Robot robot;

	private int pitchUp = 0;
	private int pitchDown = 0;
	private int rollRight = 0;
	private int rollLeft = 0;
	private int yawRight = 0;
	private int yawLeft = 0;
	private int throttle = 0;
	private long lastThrottleChange = 0;

	public ShipControl(Robot robot) {
		this.robot = robot;
	}

	public Robot getRobot() {
		return robot;
	}

	/**
	 * Sleeps between mouse move and click, but not before and after
	 */
	public synchronized void leftClick(int xOnScreen, int yOnScreen) throws InterruptedException {
		this.robot.mouseMove(xOnScreen, yOnScreen);
		Thread.sleep(400 + (long) (Math.random() * 100));
		this.leftClick();
	}

	/**
	 * Sleeps between button press and release, but not before and after
	 */
	public synchronized void leftClick() throws InterruptedException {
		this.robot.mousePress(InputEvent.getMaskForButton(1));
		Thread.sleep(200 + (long) (Math.random() * 50));
		this.robot.mouseRelease(InputEvent.getMaskForButton(1));
	}

	public synchronized void releaseAllKeys() {
		KeyDownThread.interruptAll();
	}

	public synchronized void fullStop() throws InterruptedException {
		logger.warn("Full stop requested. Stop turning!");
		this.stopTurning();
		logger.warn("Full stop requested. Throttle to 0!");
		this.setThrottle(0);
		logger.warn("Full stop requested. Release all keys!");
		this.releaseAllKeys();
	}

	public synchronized void stopTurning() {
		this.setPitchUp(0);
		this.setPitchDown(0);
		this.setRollRight(0);
		this.setRollLeft(0);
		this.setYawRight(0);
		this.setYawLeft(0);
	}

	public int getPitchUp() {
		return pitchUp;
	}

	public synchronized void setPitchUp(int percent) {
		if (percent < 0) {
			this.setPitchDown(-1 * percent);
		} else {
			this.pitchDown = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_X);

			this.pitchUp = Math.min(100, Math.max(0, Math.round(PITCH_FACTOR.getOrDefault(CruiseControlApplication.myShip, 1.0f) * percent)));
			this.pressKeyPercentOfTime(this.pitchUp, KeyEvent.VK_W);
		}
	}

	public int getPitchDown() {
		return pitchDown;
	}

	public synchronized void setPitchDown(int percent) {
		if (percent < 0) {
			this.setPitchUp(-1 * percent);
		} else {
			this.pitchUp = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_W);

			this.pitchDown = Math.min(100, Math.max(0, Math.round(PITCH_FACTOR.getOrDefault(CruiseControlApplication.myShip, 1.0f) * percent)));
			this.pressKeyPercentOfTime(this.pitchDown, KeyEvent.VK_X);
		}
	}

	public int getRollRight() {
		return rollRight;
	}

	public synchronized void setRollRight(int percent) {
		if (percent < 0) {
			this.setRollLeft(-1 * percent);
		} else {
			this.rollLeft = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_Q);

			this.rollRight = Math.min(100, Math.max(0, Math.round(ROLL_FACTOR.getOrDefault(CruiseControlApplication.myShip, 1.0f) * percent)));
			this.pressKeyPercentOfTime(this.rollRight, KeyEvent.VK_E);
		}
	}

	public int getRollLeft() {
		return rollLeft;
	}

	public synchronized void setRollLeft(int percent) {
		if (percent < 0) {
			this.setRollRight(-1 * percent);
		} else {
			this.rollRight = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_E);

			this.rollLeft = Math.min(100, Math.max(0, Math.round(ROLL_FACTOR.getOrDefault(CruiseControlApplication.myShip, 1.0f) * percent)));
			this.pressKeyPercentOfTime(this.rollLeft, KeyEvent.VK_Q);
		}
	}

	public int getYawRight() {
		return yawRight;
	}

	public synchronized void setYawRight(int percent) {
		if (percent < 0) {
			this.setYawLeft(-1 * percent);
		} else {
			this.yawLeft = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_A);

			this.yawRight = Math.min(100, Math.max(0, Math.round(YAW_FACTOR.getOrDefault(CruiseControlApplication.myShip, 1.0f) * percent)));
			this.pressKeyPercentOfTime(this.yawRight, KeyEvent.VK_D);
		}
	}

	public int getYawLeft() {
		return yawLeft;
	}

	public synchronized void setYawLeft(int percent) {
		if (percent < 0) {
			this.setYawRight(-1 * percent);
		} else {
			this.yawRight = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_D);

			this.yawLeft = Math.min(100, Math.max(0, Math.round(YAW_FACTOR.getOrDefault(CruiseControlApplication.myShip, 1.0f) * percent)));
			this.pressKeyPercentOfTime(this.yawLeft, KeyEvent.VK_A);
		}
	}

	public int getThrottle() {
		return throttle;
	}

	public synchronized void setThrottle(int throttle) throws InterruptedException {
		long millisSinceLastChange = System.currentTimeMillis() - this.lastThrottleChange;
		if (millisSinceLastChange < 35) {
			Thread.sleep(35 - millisSinceLastChange);
		}
		this.lastThrottleChange = System.currentTimeMillis();

		if (throttle < 25) {
			this.throttle = 0;
			this.pressKey(KeyEvent.VK_6);
		} else if (throttle < 50) {
			this.throttle = 25;
			this.pressKey(KeyEvent.VK_7);
		} else if (throttle < 75) {
			this.throttle = 50;
			this.pressKey(KeyEvent.VK_8);
		} else if (throttle < 100) {
			this.throttle = 75;
			this.pressKey(KeyEvent.VK_9);
		} else {
			this.throttle = 100;
			this.pressKey(KeyEvent.VK_0);
		}
	}

	public synchronized void deployHeatsink() {
		this.pressKey(DEPLOY_HEATSINK);
	}

	public synchronized void toggleFsd() {
		this.pressKey(KeyEvent.VK_J);
	}

	public synchronized void toggleSystemMap() {
		this.pressKey(KeyEvent.VK_S);
	}

	public synchronized void honk() {
		this.pressKey(KeyEvent.VK_SPACE, 7000);
	}

	public synchronized void honkDelayed(final long millis) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(millis);
					ShipControl.this.honk();
				} catch (InterruptedException e) {
					// Quit
				}
			}
		}).start();
	}

	public synchronized void uiLeftPanel() {
		this.pressKey(KeyEvent.VK_1);
	}

	public synchronized void uiUp() {
		this.pressKey(KeyEvent.VK_NUMPAD8);
	}

	public synchronized void uiDown() {
		this.pressKey(KeyEvent.VK_NUMPAD2);
	}

	public synchronized void uiRight() {
		this.pressKey(KeyEvent.VK_NUMPAD6);
	}

	public synchronized void uiLeft() {
		this.pressKey(KeyEvent.VK_NUMPAD4);
	}

	public synchronized void uiUp(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD8, millis);
	}

	public synchronized void uiDown(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD2, millis);
	}

	public synchronized void uiRight(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD6, millis);
	}

	public synchronized void uiLeft(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD4, millis);
	}

	public synchronized void uiSelect() {
		this.pressKey(KeyEvent.VK_ENTER);
	}

	public synchronized void selectNextSystemInRoute() {
		this.pressKey(KeyEvent.VK_R);
	}

	public synchronized void saveShadowplay() {
		logger.info("Saving shadowplay");
		this.pressKey(KeyEvent.VK_F8);
	}

	public synchronized void exitToMainMenu(Rectangle screenRect) {
		try {
			MouseUtil mouseUtil = new MouseUtil(screenRect.width, screenRect.height, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);
			Point pExitToMainMenu = mouseUtil.imageToScreen(new Point(200, 390));
			Point pYes = mouseUtil.imageToScreen(new Point(900, 660));

			this.robot.mouseMove(1, 1);
			logger.warn("ESC");
			this.pressKey(KeyEvent.VK_ESCAPE, 250);
			Thread.sleep(500);
			this.robot.mouseMove(pExitToMainMenu.x, pExitToMainMenu.y);
			Thread.sleep(500);
			this.leftClick();
			Thread.sleep(16000); // Wait more than 15s because most likely we are in danger
			this.robot.mouseMove(pYes.x, pYes.y);
			Thread.sleep(500);
			this.leftClick();
			logger.warn("Exited to main menu");
		} catch (InterruptedException e) {
			logger.error("Interrupted while exiting the game", e);
		}
	}

	private void pressKey(int keyCode) {
		this.pressKey(keyCode, 250);
	}

	private void pressKey(int keyCode, long millis) {
		new KeyPressThread(this.robot, keyCode, millis).start();
	}

	private void pressKeyPercentOfTime(int percent, int keyCode) {
		KeyDownThread kdt = KeyDownThread.lookup(keyCode);
		if (kdt == null) {
			new KeyDownThread(this.robot, keyCode, percent).start();
		} else {
			kdt.setPercent(percent);
		}
	}

	public static class KeyPressThread extends Thread {

		private final Robot robot;
		private final int keyCode;
		private final long millis;

		public KeyPressThread(Robot robot, int keyCode, long millis) {
			this.setName(generateThreadName(keyCode));
			this.setDaemon(true);

			this.robot = robot;
			this.keyCode = keyCode;
			this.millis = millis;
		}

		private static String generateThreadName(int keyCode) {
			return "KeyPressThread-" + keyCode;
		}

		public static void interruptAll() {
			Thread[] tarray = new Thread[Thread.activeCount() + 100];
			Thread.enumerate(tarray);
			for (Thread t : tarray) {
				if (t != null && t instanceof KeyPressThread) {
					t.interrupt();
				}
			}
		}

		public static KeyPressThread lookup(int keyCode) {
			String name = generateThreadName(keyCode);
			Thread[] tarray = new Thread[Thread.activeCount() + 100];
			Thread.enumerate(tarray);
			for (Thread t : tarray) {
				if (t != null && name.equals(t.getName())) {
					return (KeyPressThread) t;
				}
			}
			return null;
		}

		@Override
		public void run() {
			try {
				this.robot.keyPress(this.keyCode);
				Thread.sleep(this.millis);
			} catch (InterruptedException e) {
				// Quit
			}
			this.robot.keyRelease(this.keyCode);
		}

	}

	public static class KeyDownThread extends Thread {

		private final Robot robot;
		private final int keyCode;
		private int percent;

		public KeyDownThread(Robot robot, int keyCode, int percent) {
			this.setName(generateThreadName(keyCode));
			this.setDaemon(true);

			this.robot = robot;
			this.keyCode = keyCode;
			this.percent = percent;
		}

		private static String generateThreadName(int keyCode) {
			return "KeyDownThread-" + keyCode;
		}

		public static void interruptAll() {
			Thread[] tarray = new Thread[Thread.activeCount() + 100];
			Thread.enumerate(tarray);
			for (Thread t : tarray) {
				if (t != null && t instanceof KeyDownThread) {
					t.interrupt();
				}
			}
		}

		public static KeyDownThread lookup(int keyCode) {
			String name = generateThreadName(keyCode);
			Thread[] tarray = new Thread[Thread.activeCount() + 100];
			Thread.enumerate(tarray);
			for (Thread t : tarray) {
				if (t != null && name.equals(t.getName())) {
					return (KeyDownThread) t;
				}
			}
			return null;
		}

		@Override
		public void run() {
			int lastPercent = 0;
			while (this.percent > 0 && this.percent <= 100) {
				try {
					//					if (lastPercent == 100 && this.percent == 100) {
					lastPercent = this.percent;
					this.robot.keyPress(this.keyCode);
					Thread.sleep(this.percent * 2);
					//					}

					if (this.percent < 100) {
						this.robot.keyRelease(this.keyCode);
						Thread.sleep((100 - this.percent) * 2);
					}
				} catch (InterruptedException e) {
					break;
				}
			}
			this.robot.keyRelease(this.keyCode);
		}

		public int getPercent() {
			return percent;
		}

		public void setPercent(int percent) {
			this.percent = percent;
		}

	}

}
