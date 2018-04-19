package borg.ed.cruisecontrol.gui;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.image.GrayF32;
import borg.ed.cruisecontrol.util.ImageUtil;

public class DebugPanel extends JPanel {

    private static final long serialVersionUID = 8584552527591588384L;

    private BufferedImage debugImage = null;

    private GrayF32 orangeHudImage = null;
    private GrayF32 blueWhiteHudImage = null;
    private GrayF32 redHudImage = null;
    private GrayF32 brightImage = null;

    public DebugPanel() {
    }

    public void updateScreenCapture(BufferedImage debugImage, GrayF32 orangeHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
        this.debugImage = debugImage;

        this.orangeHudImage = orangeHudImage;
        this.blueWhiteHudImage = blueWhiteHudImage;
        this.redHudImage = redHudImage;
        this.brightImage = brightImage;

        this.repaint();
    }

    public void writeScreenCapture() {
        try {
            File debugFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/debug");
            final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());

            ImageIO.write(this.debugImage, "PNG", new File(debugFolder, ts + " 000 debug.png"));

            ImageIO.write(ConvertBufferedImage.convertTo(this.orangeHudImage, null), "PNG",
                    new File(System.getProperty("user.home"), ts + " CruiseControlDebug 010 orange.png"));
            ImageIO.write(ConvertBufferedImage.convertTo(this.blueWhiteHudImage, null), "PNG",
                    new File(System.getProperty("user.home"), ts + " CruiseControlDebug 011 blue-white.png"));
            ImageIO.write(ConvertBufferedImage.convertTo(this.redHudImage, null), "PNG",
                    new File(System.getProperty("user.home"), ts + " CruiseControlDebug 012 red.png"));
            ImageIO.write(ConvertBufferedImage.convertTo(this.brightImage, null), "PNG", new File(System.getProperty("user.home"), ts + " CruiseControlDebug 013 bright.png"));
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (this.debugImage != null) {
            g.drawImage(ImageUtil.scaleTo(this.debugImage, this.getWidth(), this.getHeight()), 0, 0, null);
        }
    }

}
