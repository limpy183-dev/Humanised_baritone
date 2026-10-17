package com.humanisedbaritone.human;

import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Where a relaxed player's eyes wander to: occasional glances to the sides and up, a slight downward tilt while
 * travelling and a faint rhythmic sway. The result is an offset that is added to whatever direction the current
 * behaviour wants to look in.
 */
public final class Gaze {
	private static final Minecraft mc = Minecraft.getInstance();
	private static double glanceYaw, glancePitch;
	private static long glanceUntil, nextGlance = System.currentTimeMillis() + 4000;
	private static final Rng.Ou driftYaw = new Rng.Ou(0.25, 0.9), driftPitch = new Rng.Ou(0.3, 0.6);
	private static long last = System.nanoTime();
	private static double phase;

	private Gaze() {
	}

	/**
	 * @param moving   the player is walking (enables sway and the travel pitch)
	 * @param busy     an action needs precise aim: no glances
	 */
	public static void tick(boolean moving, boolean busy) {
		long now = System.currentTimeMillis();
		double dt = Mth.clamp((System.nanoTime() - last) / 1e9, 0, 0.2);
		last = System.nanoTime();
		driftYaw.step(dt);
		driftPitch.step(dt);
		phase += dt * (moving ? 1 : 0.2);
		if (busy) {
			glanceUntil = 0;
			return;
		}
		if (glanceUntil != 0 && now > glanceUntil) {
			glanceUntil = 0;
			glanceYaw = glancePitch = 0;
			nextGlance = now + (long) Rng.delayMs(glanceGap());
		}
		if (glanceUntil == 0 && now > nextGlance) {
			double f = Settings.LOOK_GLANCE.d();
			if (f <= 0) {
				nextGlance = now + 10000;
				return;
			}
			glanceYaw = Rng.gauss(0, 22) * (Rng.chance(0.5) ? 1 : -1) * Math.max(0.4, f);
			glanceYaw = Mth.clamp(glanceYaw, -45, 45);
			glancePitch = Rng.chance(0.25) ? Rng.range(-18, -4) : Rng.range(-4, 10);
			glanceUntil = now + (long) Rng.delayMs(Rng.range(450, 1300));
		}
	}

	private static double glanceGap() {
		double f = Settings.LOOK_GLANCE.d();
		return f <= 0 ? 60000 : 14000 / (0.2 + f);
	}

	private static boolean swayOn() {
		return Settings.LOOK_SWAY.on() && Settings.LOOK_SMOOTH.on();
	}

	public static double yaw(boolean moving) {
		double y = glanceYaw + driftYaw.value() * 0.8;
		if (moving && swayOn()) y += 0.7 * Math.sin(phase * 2 * Math.PI * 0.9);
		return y;
	}

	public static double pitch(boolean moving) {
		double p = glancePitch + driftPitch.value() * 0.7;
		if (moving) {
			p += Settings.LOOK_PITCH.d();
			if (swayOn()) p += 0.55 * Math.sin(phase * 2 * Math.PI * 1.8 + 1.0);
		}
		return p;
	}

	/** True while a glance is under way (the bot should not sprint-jump or need precise aim). */
	public static boolean glancing() {
		return glanceUntil != 0;
	}

	public static void reset() {
		glanceUntil = 0;
		glanceYaw = glancePitch = 0;
		nextGlance = System.currentTimeMillis() + 3000;
	}

	public static LocalPlayer player() {
		return mc.player;
	}
}
