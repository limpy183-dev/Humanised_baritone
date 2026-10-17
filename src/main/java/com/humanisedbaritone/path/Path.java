package com.humanisedbaritone.path;

import com.humanisedbaritone.path.Moves.Kind;
import java.util.List;

/** A planned route: a list of moves, each from one standing position to the next. */
public final class Path {
	public record Step(Kind kind, int sx, int sy, int sz, int dx, int dy, int dz, double cost) {
	}

	public final List<Step> steps;
	/** True when the last step reaches the goal; false for a partial path towards it. */
	public final boolean complete;
	public final int ex, ey, ez;
	public final double cost;

	public Path(List<Step> steps, boolean complete, int ex, int ey, int ez, double cost) {
		this.steps = steps;
		this.complete = complete;
		this.ex = ex;
		this.ey = ey;
		this.ez = ez;
		this.cost = cost;
	}

	/** Horizontal distance of the steps from {@code fromIndex} on, in blocks. A straight run is one step, so counting steps says little. */
	public int blocksFrom(int fromIndex) {
		double d = 0;
		for (int i = Math.max(0, fromIndex); i < steps.size(); i++) {
			Step s = steps.get(i);
			d += Math.hypot(s.dx() - s.sx(), s.dz() - s.sz());
		}
		return (int) Math.round(d);
	}

	public double remainingCost(int fromIndex) {
		double c = 0;
		for (int i = Math.max(0, fromIndex); i < steps.size(); i++) c += steps.get(i).cost();
		return c;
	}
}
