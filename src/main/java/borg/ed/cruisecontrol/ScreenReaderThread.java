package borg.ed.cruisecontrol;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import borg.ed.cruisecontrol.util.ImageUtil;

public class ScreenReaderThread extends Thread {

	static final Logger logger = LoggerFactory.getLogger(ScreenReaderThread.class);

	public volatile boolean shutdown = false;

	private final ScreenReaderResult screenReaderResult = new ScreenReaderResult();

	@Autowired
	private Robot robot = null;
	private Rectangle screenRect = null;

	public ScreenReaderThread() {
		this.setName("SRThread");
		this.setDaemon(true);

		GraphicsDevice primaryScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
		this.screenRect = new Rectangle(primaryScreen.getDisplayMode().getWidth(), primaryScreen.getDisplayMode().getHeight());
		logger.debug("Primary screen resolution is " + this.screenRect.width + "x" + this.screenRect.height);
	}

	@Override
	public void run() {
		logger.info(this.getName() + " started");

		while (!Thread.currentThread().isInterrupted() && !this.shutdown) {
			// Take the next screen capture and resize it
			BufferedImage screenCapture = this.robot.createScreenCapture(this.screenRect);
			BufferedImage scaledScreenCapture = ImageUtil.scaleAndCrop(screenCapture, CruiseControlApplication.SCALED_WIDTH, CruiseControlApplication.SCALED_HEIGHT);

			// Notify waiting threads
			synchronized (this.screenReaderResult) {
				this.screenReaderResult.setScaledScreenCapture(scaledScreenCapture);
				this.screenReaderResult.notifyAll();
			}
		}

		logger.info(this.getName() + " stopped");
	}

	public ScreenReaderResult getScreenReaderResult() {
		return screenReaderResult;
	}

	public Rectangle getScreenRect() {
		return screenRect;
	}

}
