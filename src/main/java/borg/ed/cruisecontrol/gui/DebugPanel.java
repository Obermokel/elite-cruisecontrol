package borg.ed.cruisecontrol.gui;

import java.awt.Color;
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
    private GrayF32 yellowHudImage = null;
    private GrayF32 blueWhiteHudImage = null;
    private GrayF32 redHudImage = null;
    private GrayF32 brightImage = null;

    public DebugPanel() {

    }

    public void updateScreenCapture(BufferedImage debugImage, GrayF32 orangeHudImage, GrayF32 yellowHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage) {
        this.debugImage = debugImage;

        this.orangeHudImage = orangeHudImage;
        this.yellowHudImage = yellowHudImage;
        this.blueWhiteHudImage = blueWhiteHudImage;
        this.redHudImage = redHudImage;
        this.brightImage = brightImage;

        this.repaint();
    }

    public void writeScreenCapture() {
        try {
            File debugFolder = new File(System.getProperty("user.home"), "Google Drive/Elite Dangerous/CruiseControl/debug");
            final String ts = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS").format(new Date());

            ImageIO.write(this.debugImage, "PNG", new File(debugFolder, "DEBUG " + ts + " cc_view_00_result.png"));

            ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.orangeHudImage), null), "PNG",
                    new File(debugFolder, "DEBUG " + ts + " cc_view_10_orange.png"));
            ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.yellowHudImage), null), "PNG",
                    new File(debugFolder, "DEBUG " + ts + " cc_view_11_yellow.png"));
            ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.blueWhiteHudImage), null), "PNG",
                    new File(debugFolder, "DEBUG " + ts + " cc_view_12_bluewhite.png"));
            ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.redHudImage), null), "PNG", new File(debugFolder, "DEBUG " + ts + " cc_view_13_red.png"));
            ImageIO.write(ConvertBufferedImage.convertTo(ImageUtil.denormalize255(this.brightImage), null), "PNG", new File(debugFolder, "DEBUG " + ts + " cc_view_20_bright.png"));
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (this.debugImage != null) {
            g.drawImage(ImageUtil.scaleTo(this.debugImage, this.getWidth(), this.getHeight()), 0, 0, null);
        } else {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, this.getWidth(), this.getHeight());
        }
    }

}
