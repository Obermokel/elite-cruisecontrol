package borg.ed.cruisecontrol;

import java.awt.image.BufferedImage;

import boofcv.struct.image.GrayF32;

public interface DebugImageListener {

    void onNewDebugImage(BufferedImage debugImage, GrayF32 orangeHudImage, GrayF32 yellowHudImage, GrayF32 blueWhiteHudImage, GrayF32 redHudImage, GrayF32 brightImage);

}
