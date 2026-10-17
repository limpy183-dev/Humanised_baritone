package com.humanisedbaritone.path;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.behave.ElytraJump;
import com.humanisedbaritone.human.Gaze;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the player moving towards a {@link Goal}: plans paths on a worker thread, follows them step by step, plans the
 * next stretch before a partial path ends, and re-plans when the world or the player disagrees with the plan.
 */
public final class Nav {
	public enum State { IDLE, PLANNING, MOVING, ARRIVED, FAILED }

	private static final Minecraft mc = Minecraft.getInstance();
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HB-Planner");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	private Goal goal;
	private Path path, next;
	private int index;
	private Exec exec;
	private Future<Path> planning;
	private AStar search;
	private boolean planningNext;
	private State state = State.IDLE;
	private int failures, planFails, idleTicks, deadEnds;
	private long failWindow;
	private double h0 = 1;
	private Ctx live;
	private int liveAge;
	private final LongOpenHashSet banned = new LongOpenHashSet();
	private long bannedAt;
	private long lastFailKey = Long.MIN_VALUE;
	private long failedAt;
	private int sameFail;
	/** Per kind of move: consecutive failures, and until when the planner must not use it. */
	private final int[] kindFails = new int[Moves.Kind.values().length];
	private final long[] kindBan = new long[Moves.Kind.values().length];
	/** Shortest stretch worth bouncing: the speed builds up over about a hundred blocks. */
	private static final double BOUNCE_MIN = 50;
	public int movingTicks, sprintDelay;
	/** While positive the bot walks instead of sprinting: people do not sprint every single block. */
	public int noSprintTicks;
	private int startWait;
	public String failReason = "";
	/** Real horizontal speed in blocks per tick, from how far the player actually moved since the last tick. Shared by every step, so it survives from one step to the next. */
	public double speed;
	/** The same as a vector, blocks per tick: where the player is really heading, which can differ from where it looks. */
	public double velX, velZ;
	private Vec3 lastPos;
	private int lastTick = -1000;
	/** A long straight stretch of the route is being bounced with an elytra: where from, which way, how far (see {@link #bounceStretch}); not before {@code bounceAfter} (ms) after a start that did not work. */
	private boolean bouncing;
	private double bounceYaw, bounceX, bounceZ, bounceLen;
	private int bounceTicks;
	private long bounceAfter;

	// ------------------------------------------------------------------- api

	public State state() {
		return state;
	}

	public Goal goal() {
		return goal;
	}

	public boolean active() {
		return goal != null && state != State.ARRIVED && state != State.FAILED;
	}

	public Path path() {
		return path;
	}

	public int index() {
		return index;
	}

	public void setGoal(Goal g) {
		if (g == null) {
			cancel();
			return;
		}
		if (g.equals(goal) && state != State.FAILED && state != State.ARRIVED) return;
		if (g.equals(goal) && state == State.FAILED && System.currentTimeMillis() - failedAt < 2500) return; // do not hammer the planner
		goal = g;
		cancelPlanning();
		next = null;
		failures = planFails = deadEnds = 0;
		failReason = "";
		state = State.PLANNING;
		BlockPos f = feet();
		h0 = Math.max(1, g.h(f.getX(), f.getY(), f.getZ()));
		if (mc.player != null) startPlan(f.getX(), f.getY(), f.getZ(), false);
	}

	public void cancel() {
		cancelPlanning();
		goal = null;
		path = next = null;
		exec = null;
		index = 0;
		state = State.IDLE;
		movingTicks = 0;
		startWait = 0;
		bouncing = false;
	}

	private void cancelPlanning() {
		if (search != null) search.cancelled = true;
		if (planning != null) planning.cancel(true);
		planning = null;
		search = null;
	}

	public double progress() {
		if (goal == null || mc.player == null) return -1;
		BlockPos f = feet();
		return Mth.clamp(1 - goal.h(f.getX(), f.getY(), f.getZ()) / h0, 0, 1);
	}

	/** Estimated seconds until the goal, or -1. */
	public double eta() {
		if (path == null) return -1;
		return path.remainingCost(index) / 20.0 + (path.complete ? 0 : 5);
	}

	public String describe() {
		return switch (state) {
			case PLANNING -> "planning route";
			case MOVING -> bouncing ? "bouncing" : exec != null ? exec.st.kind().name().toLowerCase().replace('_', ' ') : "moving";
			case ARRIVED -> "arrived";
			case FAILED -> "no route: " + failReason;
			default -> "idle";
		};
	}

	// ---------------------------------------------------------- environment

	/** Main-thread planner context, refreshed every few ticks. */
	public Ctx live() {
		if (live == null || ++liveAge > 8) {
			live = new Ctx();
			liveAge = 0;
		}
		return live;
	}

	/** The cell of the player's feet; standing on a slab counts as the cell above it. */
	public BlockPos feet() {
		LocalPlayer p = mc.player;
		BlockPos b = BlockPos.containing(p.getX(), p.getY() + 0.1251, p.getZ());
		int f = Blk.f(mc.level.getBlockState(b));
		if ((f & (Blk.PASS | Blk.CLIMB)) == 0 && (f & Blk.FLOOR) != 0) return b.above();
		return b;
	}

	private boolean grounded() {
		LocalPlayer p = mc.player;
		return p.onGround() || p.isInWater() || p.onClimbable();
	}

	// ------------------------------------------------------------- planning

	private void startPlan(int sx, int sy, int sz, boolean asNext) {
		cancelPlanning();
		if (System.currentTimeMillis() - bannedAt > 25000) banned.clear();
		Ctx c = new Ctx();
		c.banned = banned.isEmpty() ? null : new LongOpenHashSet(banned);
		long now = System.currentTimeMillis();
		if (now < kindBan[Moves.Kind.DIG_DOWN.ordinal()]) c.digDown = false;
		if (now < kindBan[Moves.Kind.PILLAR.ordinal()]) c.pillar = false;
		if (now < kindBan[Moves.Kind.PARKOUR.ordinal()]) c.parkour = false;
		AStar s = new AStar(c, goal, sx, sy, sz);
		search = s;
		planningNext = asNext;
		int pt = Settings.PLAN_TIME.i(), ft = Settings.PLAN_FAIL.i(), mn = Settings.MAX_NODES.i();
		double hw = Settings.HEURISTIC.d();
		boolean straight = Settings.MOTION_STRAIGHT.on();
		planning = POOL.submit(() -> {
			Path found = s.run(pt, ft, mn, hw);
			if (found == null || !straight) return found;
			try {
				return Smooth.apply(c, found);
			} catch (RuntimeException e) { // straightening is only polish: the plain route is still good
				Log.debug("could not straighten the route: " + e);
				return found;
			}
		});
		if (!asNext && path == null) state = State.PLANNING;
	}

	private void intake() {
		if (planning == null || !planning.isDone()) return;
		Path p = null;
		try {
			p = planning.get();
		} catch (Exception e) {
			Log.debug("planner error: " + e);
		}
		boolean wasNext = planningNext;
		planning = null;
		search = null;
		if (p == null) {
			if (wasNext && path != null) return; // the current path can still be followed
			planFails++;
			path = null;
			exec = null;
			if (planFails >= 2) fail("no path found");
			else {
				BlockPos f = feet();
				startPlan(f.getX(), f.getY(), f.getZ(), false);
			}
			return;
		}
		if (wasNext && path != null) {
			next = p;
			return;
		}
		planFails = 0; // a look-ahead result that arrives after the old path ended is simply the new path
		adopt(p);
	}

	private void adopt(Path p) {
		BlockPos f = feet();
		if (p.steps.isEmpty()) {
			if (goal.isAt(f.getX(), f.getY(), f.getZ())) return;
			fail("already as close as possible");
			return;
		}
		int j = locate(p);
		if (j < 0) { // the player moved away from where the plan began
			path = null;
			exec = null;
			startPlan(f.getX(), f.getY(), f.getZ(), false);
			return;
		}
		if (p.complete) deadEnds = 0;
		if (Settings.DEBUG.on()) {
			StringBuilder sb = new StringBuilder("path " + (p.complete ? "complete" : "partial") + " " + p.steps.size() + " steps, cost " + (int) p.cost + ": ");
			for (int i = 0; i < Math.min(14, p.steps.size()); i++) {
				Path.Step st = p.steps.get(i);
				sb.append(st.kind().name().charAt(0)).append(st.kind().name().charAt(1)).append(st.dx()).append(',').append(st.dy()).append(',').append(st.dz()).append(' ');
			}
			Log.debug(sb.toString());
		}
		if (path == null && movingTicks == 0 && exec == null) startWait = Rng.delayTicks(Settings.ACT_DELAY.d() * 1.2);
		path = p;
		index = j;
		if (exec != null && exec.st != p.steps.get(j)) exec = null;
		state = State.MOVING;
	}

	/** Index of the step of {@code p} to continue with from where the player is, or -1. */
	private int locate(Path p) {
		return locate(p, 0);
	}

	/**
	 * The step of {@code p} (looking at the 14 from {@code from} on) that the player is on or beside, or -1. A step counts along its
	 * whole length, so a straight run can be picked up in the middle, and when the player is at the end of one step and the start of
	 * the next the later one wins: that one is still to do.
	 */
	private int locate(Path p, int from) {
		LocalPlayer pl = mc.player;
		int best = -1;
		double bestD = 1.9;
		for (int i = Math.max(0, from); i < Math.min(p.steps.size(), from + 14); i++) {
			Path.Step s = p.steps.get(i);
			if (!levelOk(s)) continue;
			double d = distToStep(s, pl.getX(), pl.getZ());
			if (d <= bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	/** Horizontal distance from a point to the line a step travels along, from the middle of its first block to the middle of its last. */
	private static double distToStep(Path.Step s, double x, double z) {
		double ax = s.sx() + 0.5, az = s.sz() + 0.5, bx = s.dx() + 0.5, bz = s.dz() + 0.5;
		double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
		double t = l2 < 1e-9 ? 0 : Mth.clamp(((x - ax) * vx + (z - az) * vz) / l2, 0, 1);
		return Math.hypot(x - (ax + vx * t), z - (az + vz * t));
	}

	/** The player is at the height this step starts from: on the floor there, or still in the air after a drop or a jump, or bouncing on slime. */
	private boolean levelOk(Path.Step s) {
		LocalPlayer p = mc.player;
		double above = p.getY() - s.sy();
		return Math.abs(above) < 0.75 || !p.onGround() && above > 0 && above < 3.5;
	}

	private void fail(String why) {
		failReason = why;
		failedAt = System.currentTimeMillis();
		state = State.FAILED;
		cancelPlanning();
		path = next = null;
		exec = null;
	}

	private void failStep(Path.Step s) {
		long key = ((long) s.dx() & 0x3FFFFFF) << 38 | ((long) s.dz() & 0x3FFFFFF) << 12 | ((long) (s.dy() + 2048) & 0xFFF);
		if (key == lastFailKey) sameFail++;
		else {
			lastFailKey = key;
			sameFail = 1;
		}
		if (sameFail >= 2) {
			banned.add(key);
			bannedAt = System.currentTimeMillis();
		}
		long now = System.currentTimeMillis();
		if (now - failWindow > 30000) {
			failWindow = now;
			failures = 0;
		}
		failures++;
		Log.debug("step failed: " + s.kind() + " " + s.sx() + "," + s.sy() + "," + s.sz() + " -> " + s.dx() + "," + s.dy() + "," + s.dz());
		Moves.Kind kind = s.kind();
		if ((kind == Moves.Kind.DIG_DOWN || kind == Moves.Kind.PILLAR || kind == Moves.Kind.PARKOUR) && ++kindFails[kind.ordinal()] >= 3) {
			kindFails[kind.ordinal()] = 0;
			kindBan[kind.ordinal()] = now + 45000; // it does not work here: plan around it for a while
			Log.debug(kind + " keeps failing: not used for the next 45 seconds");
		}
		exec = null;
		path = null;
		next = null;
		if (failures > 10) {
			fail("keeps getting stuck");
			return;
		}
		BlockPos f = feet();
		startPlan(f.getX(), f.getY(), f.getZ(), false);
	}

	// ---------------------------------------------------------------- tick

	/** Drives the player towards the goal for this tick. */
	public State tick() {
		if (goal == null || mc.player == null) return state;
		if (state == State.FAILED || state == State.ARRIVED) {
			if (!goal.isAt(feet().getX(), feet().getY(), feet().getZ()) || state == State.ARRIVED) movingTicks = 0;
			return state;
		}
		BlockPos f = feet();
		if (grounded() && goal.isAt(f.getX(), f.getY(), f.getZ())) {
			state = State.ARRIVED;
			path = next = null;
			exec = null;
			cancelPlanning();
			movingTicks = 0;
			return state;
		}
		trackSpeed();
		intake();
		if (state == State.FAILED) return state;
		if (path == null) {
			if (planning == null && grounded()) {
				startPlan(f.getX(), f.getY(), f.getZ(), false);
			}
			idle(false);
			return state = State.PLANNING;
		}
		state = State.MOVING;

		// plan the next stretch while walking the end of a partial path
		if (!path.complete && next == null && planning == null && path.blocksFrom(index) <= Settings.SPLICE.i()) startPlan(path.ex, path.ey, path.ez, true);

		if (exec == null && startWait > 0 && index < path.steps.size()) {
			// set off like a person: first turn to face the way, then walk
			startWait--;
			Path.Step first = path.steps.get(index);
			double heading = Math.toDegrees(Math.atan2(-(first.dx() + 0.5 - mc.player.getX()), first.dz() + 0.5 - mc.player.getZ()));
			Exec.standStill();
			Bot.look.aim(heading, 6, 0.3, 8);
			return state;
		}
		if (this == Bot.nav && bounceStretch()) return state;
		// A step that finishes hands over to the next one within the same tick. A tick without keys lets go of forward, and with
		// it of the sprint, at every single block: the player loses half of the speed it just built up and stutters along.
		for (int chain = 0; chain < 4; chain++) {
			if (exec == null) {
				if (index >= path.steps.size()) {
					finishPath(f);
					if (path == null || state != State.MOVING) break;
					continue;
				}
				Path.Step s = path.steps.get(index);
				if (!startsHere(s)) {
					int j = locate(path, index);
					if (j < 0) {
						path = null;
						return state;
					}
					index = j;
					s = path.steps.get(index);
				}
				exec = Exec.create(this, s, index + 1 < path.steps.size() ? path.steps.get(index + 1) : next != null && !next.steps.isEmpty() ? next.steps.get(0) : null);
			}
			Exec.Res r = exec.tick();
			if (r == Exec.Res.LOST) { // on another level of the route than this step expects: carry on from the step the player is standing at
				int j = locate(path, index);
				if (j >= 0) {
					Log.debug("  " + exec.st.kind() + " " + exec.st.sx() + "," + exec.st.sy() + "," + exec.st.sz() + " left behind: the player is at " + String.format("%.1f %.1f %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ()) + ", on to step " + j);
					exec = null;
					index = j;
					continue;
				}
				r = Exec.Res.FAIL;
			}
			if (r == Exec.Res.FAIL) {
				failStep(exec.st);
				break;
			}
			if (r == Exec.Res.RUN) break;
			kindFails[exec.st.kind().ordinal()] = 0;
			exec = null;
			index++;
			sameFail = 0;
		}
		if (noSprintTicks > 0) noSprintTicks--;
		else if (movingTicks > 60 && Rng.chance(0.0002 + 0.0008 * Settings.MOTION_HESITATE.d())) noSprintTicks = Rng.range(20, 70);
		if (Bot.ctl.forward) {
			movingTicks++;
			idleTicks = 0;
		} else if (++idleTicks > 6) {
			movingTicks = 0;
			sprintDelay = Rng.delayTicks(Settings.MOTION_SPRINT_DELAY.d());
		}
		return state;
	}

	/**
	 * Wearing an elytra, a long run of straight flat ground ahead on the way to an exact goal is bounced (jumps timed to the tick of
	 * every landing, see {@link ElytraJump}) instead of walked: five times the speed. The route only says where that ground is; the
	 * bounce looks at the ground itself, and when it has come to a halt the rest is planned afresh from where the player stands.
	 * Returns true while it has the player.
	 */
	private boolean bounceStretch() {
		LocalPlayer p = mc.player;
		if (!bouncing) {
			if (!(goal instanceof Goal.At || goal instanceof Goal.Column) || path == null || index >= path.steps.size() || System.currentTimeMillis() < bounceAfter) return false;
			if (!p.onGround() || !ElytraJump.ready()) return false;
			double len = stretch(p);
			if (len < BOUNCE_MIN || !ElytraJump.canStart(bounceYaw, len)) return false;
			bouncing = true;
			bounceX = p.getX();
			bounceZ = p.getZ();
			bounceLen = len;
			bounceTicks = 0;
			cancelPlanning();
			next = null;
		}
		if (ElytraJump.drive(bounceYaw, bounceLen - Math.hypot(p.getX() - bounceX, p.getZ() - bounceZ))) {
			bounceTicks++;
			exec = null;
			return true;
		}
		bouncing = false;
		if (bounceTicks == 0) { // it did not get going here: walk, and try again later
			bounceAfter = System.currentTimeMillis() + 4000;
			return false;
		}
		// it went and has stopped: the rest of the route is a new plan from here, made from the next tick on. A short bounce is not worth another one right away (stop, plan, start again)
		if (Math.hypot(p.getX() - bounceX, p.getZ() - bounceZ) < 60) bounceAfter = System.currentTimeMillis() + 3000;
		path = next = null;
		exec = null;
		return true;
	}

	/** Blocks of straight run from the player to where the runs that carry on in line from the current step end; sets {@link #bounceYaw}. */
	private double stretch(LocalPlayer p) {
		Path.Step s = path.steps.get(index);
		if (s.kind() != Moves.Kind.RUN) return 0;
		double ex = s.dx() + 0.5, ez = s.dz() + 0.5;
		bounceYaw = Math.toDegrees(Math.atan2(-(ex - p.getX()), ez - p.getZ()));
		for (int k = index + 1; k < path.steps.size(); k++) {
			Path.Step a = path.steps.get(k - 1), n = path.steps.get(k);
			if (n.kind() != Moves.Kind.RUN || n.sx() != a.dx() || n.sz() != a.dz() || n.sy() != s.sy() || n.dy() != s.sy()) break;
			double h = Math.toDegrees(Math.atan2(-(n.dx() - n.sx()), n.dz() - n.sz()));
			if (Math.abs(Mth.wrapDegrees(h - bounceYaw)) > 6) break;
			ex = n.dx() + 0.5;
			ez = n.dz() + 0.5;
		}
		// the goal lies straight on: the route may only end here because it was planned no further, so go on to the goal (the bounce looks at the ground itself and stops where it ends)
		BlockPos a = goal.anchor();
		double gx = a.getX() + 0.5 - p.getX(), gz = a.getZ() + 0.5 - p.getZ();
		if (Math.abs(Mth.wrapDegrees(Math.toDegrees(Math.atan2(-gx, gz)) - bounceYaw)) < 6) return Math.hypot(gx, gz);
		return Math.hypot(ex - p.getX(), ez - p.getZ());
	}

	private boolean startsHere(Path.Step s) {
		LocalPlayer p = mc.player;
		return levelOk(s) && Math.hypot(p.getX() - (s.sx() + 0.5), p.getZ() - (s.sz() + 0.5)) < 1.6;
	}

	/** Horizontal speed over the last tick, measured once per tick however many steps run in it. */
	private void trackSpeed() {
		LocalPlayer p = mc.player;
		if (p.tickCount == lastTick) return;
		Vec3 now = p.position();
		int gap = p.tickCount - lastTick;
		boolean known = lastPos != null && gap >= 1 && gap <= 2;
		velX = known ? (now.x - lastPos.x) / gap : 0;
		velZ = known ? (now.z - lastPos.z) / gap : 0;
		speed = Math.hypot(velX, velZ);
		lastPos = now;
		lastTick = p.tickCount;
	}

	private State finishPath(BlockPos f) {
		exec = null;
		if (goal.isAt(f.getX(), f.getY(), f.getZ())) {
			state = State.ARRIVED;
			return state;
		}
		if (path.complete) {
			// finished a full path but not quite at the goal (cut a corner): try once more from here
			path = null;
			if (++deadEnds > 4) fail("cannot reach the exact goal");
			return state;
		}
		Path cont = next;
		next = null;
		path = null;
		if (cont != null && !cont.steps.isEmpty()) {
			deadEnds = 0;
			int j = locate(cont);
			if (j >= 0) {
				path = cont;
				index = j;
				return state;
			}
		} else if (++deadEnds > 6) {
			fail("partial paths are not getting closer");
		}
		return state;
	}

	/** Nothing to execute this tick (planning or waiting): stand still and let the eyes wander a little. */
	private void idle(boolean busy) {
		Exec.standStill();
		Gaze.tick(false, busy);
		if (Gaze.glancing() && mc.player != null) Bot.look.aim(mc.player.getYRot() + Gaze.yaw(false), Mth.clamp(mc.player.getXRot() * 0.5 + Gaze.pitch(false), -40, 40), 0.1, 20);
	}

	public Vec3 goalPoint() {
		if (goal == null) return null;
		return Vec3.atBottomCenterOf(goal.anchor());
	}
}
