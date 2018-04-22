package borg.ed.cruisecontrol;

import java.io.Serializable;

public class ScreenCoord implements Serializable, Comparable<ScreenCoord> {

	private static final long serialVersionUID = -4376817152978703581L;

	public int x = 0;
	public int y = 0;

	public ScreenCoord() {
		// Default
	}

	public ScreenCoord(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public int getX() {
		return x;
	}

	public void setX(int x) {
		this.x = x;
	}

	public int getY() {
		return y;
	}

	public void setY(int y) {
		this.y = y;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ScreenCoord other = (ScreenCoord) obj;
		if (x != other.x)
			return false;
		if (y != other.y)
			return false;
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + x;
		result = prime * result + y;
		return result;
	}

	@Override
	public String toString() {
		return this.x + "/" + this.y;
	}

	@Override
	public int compareTo(ScreenCoord other) {
		if (this.x < other.x) {
			return -1;
		} else if (this.x > other.x) {
			return 1;
		} else if (this.y < other.y) {
			return -1;
		} else if (this.y > other.y) {
			return 1;
		} else {
			return 0;
		}
	}

}
