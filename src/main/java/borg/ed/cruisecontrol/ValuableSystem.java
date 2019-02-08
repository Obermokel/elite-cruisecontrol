package borg.ed.cruisecontrol;

import borg.ed.galaxy.data.Coord;

public class ValuableSystem {

	private String name = null;
	private Coord coord = null;
	private long payout = 0;

	public ValuableSystem(String name, Coord coord, long payout) {
		this.setName(name);
		this.setCoord(coord);
		this.setPayout(payout);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ValuableSystem other = (ValuableSystem) obj;
		if (coord == null) {
			if (other.coord != null)
				return false;
		} else if (!coord.equals(other.coord))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((coord == null) ? 0 : coord.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	@Override
	public String toString() {
		return this.name + " (" + this.coord + ")";
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Coord getCoord() {
		return coord;
	}

	public void setCoord(Coord coord) {
		this.coord = coord;
	}

	public long getPayout() {
		return payout;
	}

	public void setPayout(long payout) {
		this.payout = payout;
	}

}
