package borg.ed.cruisecontrol;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import borg.ed.cruisecontrol.util.ImageUtil;

public class ScreenReaderThread extends Thread {

    static final Logger logger = LoggerFactory.getLogger(ScreenReaderThread.class);

    public volatile boolean shutdown = false;

    private final Robot robot;
    private final Rectangle screenRect;
    private final ScreenReaderResult screenReaderResult;

    public ScreenReaderThread(Robot robot, Rectangle screenRect, ScreenReaderResult screenReaderResult) {
        this.setName("SRThread");
        this.setDaemon(true);

        this.robot = robot;
        this.screenRect = screenRect;
        this.screenReaderResult = screenReaderResult;
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

}
