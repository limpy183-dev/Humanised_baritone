package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.behave.ElytraJump;
import com.humanisedbaritone.human.Gaze;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Roam with no goal: one leg after another in a heading that bends a little each time, a look around in between, and never
 * further from the start than the wander radius. With an elytra worn a leg over clear, flat ground is bounced (timed jumps, see
 * {@link ElytraJump}) at 30 blocks a second and more; everywhere else, and without an elytra, the bot walks to a spot a few dozen
 * blocks on. Runs until it is stopped.
 */
public final class WanderTask extends Task {
	private static final Minecraft mc = Minecraft.getInstance();

	private enum Mode { PAUSE, BOUNCE, WALK }

	/** The radius asked for with the command, or negative for the setting. */
	private final double radiusArg;
	private Mode mode = Mode.PAUSE;
	private Vec3 home, legStart;
	private double heading, legLen;
	private int pause, ticks, legs, walkFails, bounceFails;

	public WanderTask(double radius) {
		this.radiusArg = radius;
	}

	@Override public String name() {
		return "Wandering";
	}

	@Override public String tag() {
		return "WANDER";
	}

	private double radius() {
		return radiusArg >= 0 ? radiusArg : Settings.WANDER_RADIUS.d();
	}

	@Override public void start() {
		LocalPlayer p = mc.player;
		home = p.position();
		heading = p.getYRot();
		mode = Mode.PAUSE;
		pause = Rng.range(10, 30);
		legs = walkFails = bounceFails = 0;
		if (Settings.ELYTRA.on() && ElytraJump.glider(p).isEmpty()) Log.info("No elytra worn: wandering on foot.");
	}

	@Override public void stop() {
		Bot.nav.cancel();
		ElytraJump.reset();
	}

	@Override public String status() {
		LocalPlayer p = mc.player;
		String what = switch (mode) {
			case BOUNCE -> "bouncing";
			case WALK -> "walking";
			default -> "looking around";
		};
		return "leg " + (legs + 1) + ", " + what + (radius() > 0 && home != null ? String.format(", %.0f of %.0f blocks from the start", Math.hypot(p.getX() - home.x, p.getZ() - home.z), radius()) : "");
	}

	@Override public boolean tick() {
		LocalPlayer p = mc.player;
		switch (mode) {
			case PAUSE -> {
				Gaze.tick(false, false);
				if (Gaze.glancing()) Bot.look.aim(p.getYRot() + Gaze.yaw(false), Gaze.pitch(false), 0.1, 30);
				if (--pause <= 0 && (p.onGround() || p.isInWater() || p.onClimbable())) return pick(); // not in mid-air: a plan made while falling starts from nowhere
			}
			case BOUNCE -> {
				double left = legLen - Math.hypot(p.getX() - legStart.x, p.getZ() - legStart.z);
				if (!ElytraJump.drive(heading, left)) {
					if (ticks == 0) bounceFails++; // it did not even get going
					else {
						bounceFails = 0;
						walkFails = 0;
					}
					endLeg();
				}
				ticks++;
			}
			case WALK -> {
				Nav.State s = Bot.nav.tick();
				if (s == Nav.State.ARRIVED || s == Nav.State.FAILED || ++ticks > 900) { // a leg that takes three quarters of a minute has gone wrong (a lake, a ravine): choose again
					if (s == Nav.State.FAILED) walkFails++;
					else walkFails = 0;
					endLeg();
				}
			}
		}
		return false;
	}

	private void endLeg() {
		if (mode == Mode.WALK) bounceFails = 0; // a walk later, the ground may be better for a bounce
		Bot.nav.cancel();
		mode = Mode.PAUSE;
		pause = Rng.range(25, 100);
		legs++;
	}

	/** Chooses the next leg. Returns true when the task is finished, which only happens when there is nowhere to go at all. */
	private boolean pick() {
		LocalPlayer p = mc.player;
		if (walkFails > 8) {
			Log.warn("Nowhere to wander to from here.");
			return true;
		}
		double r = radius();
		Vec3 pos = p.position();
		boolean bounce = ElytraJump.ready() && bounceFails < 2;
		double far = Math.hypot(home.x - pos.x, home.z - pos.z), toHome = Math.toDegrees(Math.atan2(-(home.x - pos.x), home.z - pos.z));
		double bestScore = -1e9, bestHeading = heading, bestLane = 0;
		for (int i = 0; i < 16; i++) {
			// mostly carry on roughly the way it was going, a few from anywhere, so that it can turn back
			double h = i < 11 ? heading + Rng.gauss(0, 50) : Rng.range(-180, 180);
			double room = limit(pos, h, r);
			double lane = bounce ? Math.min(ElytraJump.lane(h, 80), room) : Math.min(80, room);
			double score = Math.min(lane, 80) - 0.2 * Math.abs(Mth.wrapDegrees(h - heading)) + Rng.range(0, 14);
			if (r > 0 && far > r * 0.6) score -= 0.3 * Math.abs(Mth.wrapDegrees(h - toHome)) * Math.min(1, far / r); // near the edge of its range: turn for home
			if (score > bestScore) {
				bestScore = score;
				bestHeading = h;
				bestLane = lane;
			}
		}
		heading = Mth.wrapDegrees(bestHeading);
		ticks = 0;
		if (bounce && bestLane >= ElytraJump.WORTH) {
			mode = Mode.BOUNCE;
			legStart = pos;
			legLen = Math.min(Rng.range(120, 400), limit(pos, heading, r));
			return false;
		}
		// on foot: a spot a few dozen blocks on, on dry land (a random column can be in the middle of a lake), the chosen heading first and then a few others
		for (int k = 0; k < 8; k++) {
			double h = k == 0 ? heading : heading + Rng.gauss(0, 70), d = Math.min(Rng.range(18, 45), limit(pos, h, r));
			int x = Mth.floor(pos.x - Math.sin(Math.toRadians(h)) * d), z = Mth.floor(pos.z + Math.cos(Math.toRadians(h)) * d);
			if (d < 6 || !dry(x, z)) continue;
			heading = Mth.wrapDegrees(h);
			Bot.nav.setGoal(new Goal.Column(x, z));
			mode = Mode.WALK;
			return false;
		}
		walkFails++;
		endLeg();
		return false;
	}

	/** The column is in the loaded world and its top is not water or lava. */
	private static boolean dry(int x, int z) {
		if (mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null || !mc.level.getWorldBorder().isWithinBounds(x, z)) return false;
		int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1; // the height is the first free cell above the highest block
		return mc.level.getBlockState(new BlockPos(x, top, z)).getFluidState().isEmpty();
	}

	/** Distance along {@code h} from {@code pos} until the wander radius around the start is left (a very large number when there is no radius). */
	private double limit(Vec3 pos, double h, double r) {
		if (r <= 0) return 1e9;
		double ux = -Math.sin(Math.toRadians(h)), uz = Math.cos(Math.toRadians(h));
		double px = pos.x - home.x, pz = pos.z - home.z;
		double b = px * ux + pz * uz, c = px * px + pz * pz - r * r, disc = b * b - c;
		if (disc < 0) return 0;
		return Math.max(0, -b + Math.sqrt(disc));
	}
}
