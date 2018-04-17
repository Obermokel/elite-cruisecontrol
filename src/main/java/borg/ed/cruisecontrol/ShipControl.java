package borg.ed.cruisecontrol;

import java.awt.Robot;
import java.awt.event.KeyEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShipControl {

	static final Logger logger = LoggerFactory.getLogger(ShipControl.class);

	private final Robot robot;

	private int pitchUp = 0;
	private int pitchDown = 0;
	private int rollRight = 0;
	private int rollLeft = 0;
	private int yawRight = 0;
	private int yawLeft = 0;
	private int throttle = 0;

	public ShipControl(Robot robot) {
		this.robot = robot;
	}

	public Robot getRobot() {
		return robot;
	}

	public void releaseAllKeys() {
		KeyDownThread.interruptAll();
	}

	public void fullStop() {
		this.stopTurning();
		this.setThrottle(0);
		this.releaseAllKeys();
	}

	public void stopTurning() {
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

	public void setPitchUp(int percent) {
		if (percent < 0) {
			this.setPitchDown(-1 * percent);
		} else {
			this.pitchDown = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_X);

			this.pitchUp = percent;
			this.pressKeyPercentOfTime(percent, KeyEvent.VK_W);
		}
	}

	public int getPitchDown() {
		return pitchDown;
	}

	public void setPitchDown(int percent) {
		if (percent < 0) {
			this.setPitchUp(-1 * percent);
		} else {
			this.pitchUp = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_W);

			this.pitchDown = percent;
			this.pressKeyPercentOfTime(percent, KeyEvent.VK_X);
		}
	}

	public int getRollRight() {
		return rollRight;
	}

	public void setRollRight(int percent) {
		if (percent < 0) {
			this.setRollLeft(-1 * percent);
		} else {
			this.rollLeft = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_Q);

			this.rollRight = percent;
			this.pressKeyPercentOfTime(percent, KeyEvent.VK_E);
		}
	}

	public int getRollLeft() {
		return rollLeft;
	}

	public void setRollLeft(int percent) {
		if (percent < 0) {
			this.setRollRight(-1 * percent);
		} else {
			this.rollRight = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_E);

			this.rollLeft = percent;
			this.pressKeyPercentOfTime(percent, KeyEvent.VK_Q);
		}
	}

	public int getYawRight() {
		return yawRight;
	}

	public void setYawRight(int percent) {
		if (percent < 0) {
			this.setYawLeft(-1 * percent);
		} else {
			this.yawLeft = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_A);

			this.yawRight = percent;
			this.pressKeyPercentOfTime(percent, KeyEvent.VK_D);
		}
	}

	public int getYawLeft() {
		return yawLeft;
	}

	public void setYawLeft(int percent) {
		if (percent < 0) {
			this.setYawRight(-1 * percent);
		} else {
			this.yawRight = 0;
			this.pressKeyPercentOfTime(0, KeyEvent.VK_D);

			this.yawLeft = percent;
			this.pressKeyPercentOfTime(percent, KeyEvent.VK_A);
		}
	}

	public int getThrottle() {
		return throttle;
	}

	public void setThrottle(int throttle) {
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

	public void toggleFsd() {
		this.pressKey(KeyEvent.VK_J);
	}

	public void honk() {
		this.pressKey(KeyEvent.VK_SPACE, 7000);
	}

	public void uiLeftPanel() {
		this.pressKey(KeyEvent.VK_1);
	}

	public void uiUp() {
		this.pressKey(KeyEvent.VK_NUMPAD8);
	}

	public void uiDown() {
		this.pressKey(KeyEvent.VK_NUMPAD2);
	}

	public void uiRight() {
		this.pressKey(KeyEvent.VK_NUMPAD6);
	}

	public void uiLeft() {
		this.pressKey(KeyEvent.VK_NUMPAD4);
	}

	public void uiUp(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD8, millis);
	}

	public void uiDown(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD2, millis);
	}

	public void uiRight(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD6, millis);
	}

	public void uiLeft(long millis) {
		this.pressKey(KeyEvent.VK_NUMPAD4, millis);
	}

	public void uiSelect() {
		this.pressKey(KeyEvent.VK_ENTER);
	}

	public void selectNextSystemInRoute() {
		this.pressKey(KeyEvent.VK_R);
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
					Thread.sleep(this.percent * 10);
					//					}

					if (this.percent < 100) {
						this.robot.keyRelease(this.keyCode);
						Thread.sleep((100 - this.percent) * 10);
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
