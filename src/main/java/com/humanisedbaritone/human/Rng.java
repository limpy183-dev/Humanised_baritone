package com.humanisedbaritone.human;

import com.humanisedbaritone.settings.Settings;
import java.util.Random;

/** All randomness of the bot goes through here so a seed can reproduce a whole session. */
public final class Rng {
	private static Random r = new Random();

	private Rng() {
	}

	public static void reseed() {
		int seed = Settings.VAR_SEED.i();
		r = seed == 0 ? new Random() : new Random(seed);
	}

	public static double unit() {
		return r.nextDouble();
	}

	public static double range(double lo, double hi) {
		return lo + r.nextDouble() * (hi - lo);
	}

	public static int range(int lo, int hiInclusive) {
		return lo + r.nextInt(hiInclusive - lo + 1);
	}

	public static double gauss() {
		return r.nextGaussian();
	}

	/** Normal distribution limited to 2.5 standard deviations so rare outliers stay plausible. */
	public static double gauss(double mean, double sd) {
		double g = Math.max(-2.5, Math.min(2.5, r.nextGaussian()));
		return mean + g * sd;
	}

	public static boolean chance(double p) {
		return r.nextDouble() < p;
	}

	public static int index(int n) {
		return r.nextInt(n);
	}

	public static <T> T pick(java.util.List<T> l) {
		return l.get(r.nextInt(l.size()));
	}

	/**
	 * A human style delay: log-normal around {@code meanMs}, spread scaled by the "timing variance" setting.
	 * Real reaction times are right-skewed, never negative and occasionally long.
	 */
	public static double delayMs(double meanMs) {
		if (meanMs <= 0) return 0;
		double spread = 0.08 + 0.55 * Settings.ACT_JITTER.d() * Attention.spreadFactor();
		double ln = Math.exp(r.nextGaussian() * spread - spread * spread / 2);
		return meanMs * Attention.slowFactor() * Math.min(ln, 2.6);
	}

	public static int delayTicks(double meanMs) {
		return (int) Math.max(0, Math.round(delayMs(meanMs) / 50.0));
	}

	/** Ornstein-Uhlenbeck process: smooth, mean reverting noise (hand tremor, slow drift). */
	public static final class Ou {
		private final double theta, sigma;
		private double x;

		public Ou(double theta, double sigma) {
			this.theta = theta;
			this.sigma = sigma;
		}

		public double step(double dt) {
			x += -theta * x * dt + sigma * Math.sqrt(dt) * r.nextGaussian();
			return x;
		}

		public double value() {
			return x;
		}
	}
}
