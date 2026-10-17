package com.humanisedbaritone.path;

/** Action costs in game ticks, derived from vanilla movement speeds and gravity. */
public final class Costs {
	public static final double INF = 1_000_000;
	public static final double WALK = 20.0 / 4.317;
	public static final double SPRINT = 20.0 / 5.612;
	public static final double SNEAK = 20.0 / 1.3;
	public static final double WATER = 20.0 / 2.2;
	public static final double LADDER_UP = 20.0 / 2.35;
	public static final double LADDER_DOWN = 20.0 / 3.0;
	public static final double SQRT2 = Math.sqrt(2);
	/** Time to fall n blocks. */
	public static final double[] FALL = new double[256];
	public static final double JUMP;
	public static final double WALK_OFF = WALK * 0.8;
	public static final double CENTER_AFTER_FALL = WALK - WALK_OFF;
	/** Heuristic cost per block: a little below the fastest possible travel so the search stays admissible. */
	public static final double H_PER_BLOCK = 3.563;
	/** Overheads that the vanilla rules add to interacting with blocks. */
	public static final double BREAK_OVERHEAD = 8, PLACE_OVERHEAD = 6, DOOR = 9, CLUTCH = 26, PARKOUR = 5;

	static {
		for (int i = 0; i < FALL.length; i++) FALL[i] = fallTicks(i);
		JUMP = fallTicks(1.25) - fallTicks(0.25);
	}

	private Costs() {
	}

	private static double vel(int t) {
		return (Math.pow(0.98, t) - 1) * -3.92;
	}

	public static double fallTicks(double distance) {
		if (distance <= 0) return 0;
		double left = distance;
		int t = 0;
		while (true) {
			double d = vel(t + 1);
			if (left <= d) return t + left / d;
			left -= d;
			t++;
		}
	}
}
