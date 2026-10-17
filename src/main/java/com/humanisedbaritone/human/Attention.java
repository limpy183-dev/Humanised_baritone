package com.humanisedbaritone.human;

import com.humanisedbaritone.settings.Settings;

/**
 * Slowly wandering "focus level". A focused player reacts fast and keeps steady, a distracted one is slower and
 * sloppier. Everything in the humanising layer reads it so behaviour drifts over minutes instead of being constant.
 */
public final class Attention {
	private static final Rng.Ou focus = new Rng.Ou(0.012, 0.045);
	private static double lastTickMs = System.nanoTime() / 1e6;

	private Attention() {
	}

	public static void tick() {
		double now = System.nanoTime() / 1e6;
		double dt = Math.min(0.2, (now - lastTickMs) / 1000.0);
		lastTickMs = now;
		focus.step(dt * 20);
	}

	/** -1 (very distracted) .. +1 (very focused). */
	public static double level() {
		if (!Settings.VAR_ATTENTION.on()) return 0;
		return Math.max(-1, Math.min(1, focus.value() * 2.2)) * Settings.VAR_ATTENTION_AMT.d();
	}

	/** Multiplier for delays: >1 when distracted, <1 when focused. */
	public static double slowFactor() {
		return 1.0 - 0.3 * level();
	}

	/** Multiplier for wobble amplitudes. */
	public static double noiseFactor() {
		return 1.0 - 0.45 * level();
	}

	public static double spreadFactor() {
		return 1.0 - 0.3 * level();
	}
}
