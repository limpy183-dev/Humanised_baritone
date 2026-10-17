package com.humanisedbaritone.human;

import com.humanisedbaritone.settings.Settings;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the camera like a hand on a mouse.
 *
 * <p>Behaviours call {@link #aim} every tick with where they want to look. Every rendered frame {@link #frame} then
 * moves the real camera towards a <i>delayed, noisy</i> copy of that target using a damped spring whose speed follows
 * Fitts' law, sometimes overshooting and correcting, and applies the movement in whole mouse counts at the player's
 * sensitivity through {@code Entity.turn} - the exact path a physical mouse takes. Nothing ever snaps.
 */
public final class LookController {
	private static final Minecraft mc = Minecraft.getInstance();

	// request made by behaviours
	private double reqYaw, reqPitch, urgency, tol = 2;
	private int ttl;
	private boolean hadReq;
	private final ArrayDeque<double[]> hist = new ArrayDeque<>();

	// motion state
	private double vYaw, vPitch;
	private double omega = 14, zeta = 1, lagMs = 120;
	private double carryYaw, carryPitch;
	/** The target after a short low-pass filter: makes acceleration ramp up instead of jumping (a hand has inertia). */
	private double fYaw, fPitch;
	private boolean fValid;
	private final Rng.Ou oy = new Rng.Ou(5, 0.30), op = new Rng.Ou(5, 0.22);
	private long lastFrame = System.nanoTime();
	private double speedNow;

	/** Consecutive ticks the real view has been within tolerance of the requested direction. */
	public int onTicks;

	/** Test hook: receives {nanoTime, yaw, pitch, mouseCountsX, mouseCountsY, degreesPerCount} after every applied camera movement. */
	public static java.util.function.Consumer<double[]> probe;

	/** Asks the camera to look in this direction. Must be refreshed every tick; it lapses after three ticks. */
	public void aim(double yaw, double pitch, double urgency, double tolerance) {
		if (mc.player == null) return;
		pitch = Mth.clamp(pitch, -90, 90);
		double jump = hadReq && ttl > 0 ? dist(reqYaw, reqPitch, yaw, pitch) : 999;
		if (jump > 6.0) newEpisode(yaw, pitch, urgency);
		reqYaw = yaw;
		reqPitch = pitch;
		this.urgency = urgency;
		this.tol = tolerance;
		ttl = 3;
		hadReq = true;
		hist.addLast(new double[]{nowMs(), yaw, pitch});
		while (hist.size() > 40) hist.removeFirst();
	}

	public void aimAt(Vec3 point, double urgency, double tolerance) {
		Vec3 eye = mc.player.getEyePosition();
		double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		aim(Math.toDegrees(Math.atan2(-dx, dz)), Math.toDegrees(-Math.atan2(dy, h)), urgency, tolerance);
	}

	/** Drop the request: the player regains full mouse control. */
	public void release() {
		fValid = false;
		ttl = 0;
		hadReq = false;
		vYaw = vPitch = 0;
		onTicks = 0;
		hist.clear();
	}

	public boolean active() {
		return ttl > 0;
	}

	/** Angle in degrees between the real view direction and the requested direction. */
	public double error() {
		if (mc.player == null || !hadReq) return 999;
		return dist(mc.player.getYRot(), mc.player.getXRot(), reqYaw, reqPitch);
	}

	public boolean settled(int ticks) {
		return onTicks >= ticks;
	}

	public double speed() {
		return speedNow;
	}

	/** Called once per game tick. */
	public void tick() {
		if (ttl > 0) {
			ttl--;
			onTicks = error() <= tol ? onTicks + 1 : 0;
		} else {
			onTicks = 0;
		}
	}

	private void newEpisode(double yaw, double pitch, double urgency) {
		LocalPlayer p = mc.player;
		double d = dist(p.getYRot(), p.getXRot(), yaw, pitch);
		double ms = (40 + 70 * log2(1 + d / 3.0)) * Math.exp(Rng.gauss() * 0.12);
		ms *= 1.0 - 0.45 * urgency;
		ms /= Settings.LOOK_SPEED.d();
		ms *= Attention.slowFactor();
		double om = 4.74 / (Math.max(ms, 40) / 1000.0);
		double maxSpeed = Settings.LOOK_MAX.d();
		if (d > 0.5) om = Math.min(om, maxSpeed * Math.E / d);
		omega = Mth.clamp(om, 6, 38);
		boolean over = d > 18 && Rng.chance(Settings.LOOK_OVERSHOOT.d());
		zeta = over ? Rng.range(0.55, 0.78) : Rng.range(0.97, 1.03);
		lagMs = Rng.delayMs(Settings.LOOK_REACTION.d() * (1.0 - 0.7 * urgency));
	}

	/** Runs every rendered frame (mixin into the mouse handler, right where a real mouse would be applied). */
	public void frame() {
		long now = System.nanoTime();
		double dt = Mth.clamp((now - lastFrame) / 1e9, 0.0005, 0.1);
		lastFrame = now;
		LocalPlayer p = mc.player;
		if (p == null || ttl <= 0 || mc.gui.screen() != null && !(mc.gui.screen() instanceof com.humanisedbaritone.gui.BotScreen)) {
			vYaw = vPitch = 0;
			fValid = false;
			return;
		}
		double yaw = p.getYRot(), pitch = p.getXRot();
		if (!Settings.LOOK_SMOOTH.on()) { // debugging mode: look exactly where asked, instantly
			apply(Mth.wrapDegrees(reqYaw - yaw), reqPitch - pitch);
			return;
		}
		double[] tg = delayed(nowMs() - lagMs);
		if (!fValid) {
			fYaw = yaw;
			fPitch = pitch;
			fValid = true;
		}
		double tau = 0.035 * (1.0 - 0.6 * urgency);
		double a = 1.0 - Math.exp(-dt / tau);
		fYaw += Mth.wrapDegrees(tg[0] - fYaw) * a;
		fPitch += (tg[1] - fPitch) * a;
		tg = new double[]{fYaw, fPitch};

		double amp = Settings.LOOK_NOISE.d() * Attention.noiseFactor() * (1 + speedNow / 140.0);
		double ey = Mth.wrapDegrees(tg[0] + oy.step(dt) * amp - yaw);
		double ep = tg[1] + op.step(dt) * amp * 0.8 - pitch;

		double maxSp = Settings.LOOK_MAX.d() * 1.15;
		double om = Math.max(omega, 9 + 8 * urgency);
		double dY = 0, dP = 0;
		int steps = (int) Math.ceil(dt / 0.008);
		double h = dt / steps;
		for (int i = 0; i < steps; i++) {
			vYaw += (om * om * ey - 2 * zeta * om * vYaw) * h;
			vPitch += (om * om * 0.9 * ep - 2 * zeta * om * 0.95 * vPitch) * h;
			double sp = Math.hypot(vYaw, vPitch);
			if (sp > maxSp) {
				vYaw *= maxSp / sp;
				vPitch *= maxSp / sp;
			}
			double sy = vYaw * h, sp2 = vPitch * h;
			ey -= sy;
			ep -= sp2;
			dY += sy;
			dP += sp2;
		}
		speedNow = Math.hypot(vYaw, vPitch);
		apply(dY, dP);
	}

	/** Moves the camera by a yaw/pitch delta in degrees, in whole mouse counts. */
	private void apply(double dYaw, double dPitch) {
		LocalPlayer p = mc.player;
		if (Settings.LOOK_QUANTISE.on() && Settings.LOOK_SMOOTH.on()) {
			double ss = mc.options.sensitivity().get() * 0.6 + 0.2;
			double unit = ss * ss * ss * 8.0; // mouse units per count, see MouseHandler.turnPlayer
			double q = unit * 0.15; // degrees per count
			carryYaw += dYaw / q;
			carryPitch += dPitch / q;
			int cx = (int) Math.round(carryYaw), cy = (int) Math.round(carryPitch);
			carryYaw -= cx;
			carryPitch -= cy;
			if (cx != 0 || cy != 0) p.turn(cx * unit, cy * unit);
			if (probe != null) probe.accept(new double[]{System.nanoTime(), p.getYRot(), p.getXRot(), cx, cy, q});
		} else {
			p.turn(dYaw / 0.15, dPitch / 0.15);
		}
	}

	private double[] delayed(double t) {
		double[] best = null;
		for (double[] e : hist) {
			if (e[0] <= t) best = e;
			else break;
		}
		if (best == null) best = hist.isEmpty() ? new double[]{0, reqYaw, reqPitch} : hist.peekFirst();
		return new double[]{best[1], best[2]};
	}

	private static double nowMs() {
		return System.nanoTime() / 1e6;
	}

	private static double log2(double v) {
		return Math.log(v) / Math.log(2);
	}

	public static double dist(double y1, double p1, double y2, double p2) {
		return Math.hypot(Mth.wrapDegrees(y2 - y1), p2 - p1);
	}
}
