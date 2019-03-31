package borg.ed.cruisecontrol.fss;

import borg.ed.cruisecontrol.templatematching.TemplateMatch;

public class FssBodyLocation {

	/**
	 * x position on the screen, given from -1..0..+1, meaning -1=left and +1=right.
	 */
	public final float x;

	/**
	 * y position on the screen, given from -1..0..+1, meaning -1=top and +1=bottom.
	 */
	public final float y;

	/**
	 * Template matching error of a blue bubble. The less the better.
	 */
	public final float error;

	/**
	 * lastSeen timestamp of a blue bubble. Can be ignored when one or both of the reticules are lighting up.
	 */
	public final long lastSeen;

	public FssBodyLocation(float x, float y, float error, long lastSeen) {
		this.x = x;
		this.y = y;
		this.error = error;
		this.lastSeen = lastSeen;
	}

	public static FssBodyLocation fromBubble(TemplateMatch m) {
		// Middle position of the template match, not upper left
		float xMiddle = m.getX() + m.getWidth() / 2f;
		float yMiddle = m.getY() + m.getHeight() / 2f;

		float xPercent = xMiddle / m.getImage().getWidth();
		float yPercent = yMiddle / m.getImage().getHeight();

		float xPosition = Math.min(1.0f, Math.max(-1.0f, (xPercent - 0.5f) * 2.0f));
		float yPosition = Math.min(1.0f, Math.max(-1.0f, (yPercent - 0.5f) * 2.0f));

		return new FssBodyLocation(xPosition, yPosition, m.getErrorPerPixel(), System.currentTimeMillis());
	}

}