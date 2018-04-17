package borg.ed.cruisecontrol.templatematching;

public class TemplateMatch {

	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final float error;
	private final float errorPerPixel;

	public TemplateMatch(int x, int y, int width, int height, float error, float errorPerPixel) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.error = error;
		this.errorPerPixel = errorPerPixel;
	}

	public int getX() {
		return x;
	}

	public int getY() {
		return y;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public float getError() {
		return error;
	}

	public float getErrorPerPixel() {
		return errorPerPixel;
	}

}
