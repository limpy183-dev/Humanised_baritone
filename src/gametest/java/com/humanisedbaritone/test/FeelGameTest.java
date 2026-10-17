package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.path.Nav;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * How the walking looks and how fast it is: records the player every tick while the bot travels and prints speed, how
 * much of the time the forward and sprint keys were held, stops, turning and backtracking. Writes the raw trace to
 * build/feel/NAME.csv.
 */
public class FeelGameTest extends BotTest {
	/** tick, x, y, z, yaw, onGround, forward, sprintKey, isSprinting, jump, moving(nav state), label */
	private final List<double[]> rows = new ArrayList<>();
	private final List<String> labels = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			load(ctx, sp, -20, -60, 260, 60);
			load(ctx, sp, -20, 80, 260, 215);
			botOn(ctx);
			// HB_NOHOP=1 runs everything as a plain sprint, for comparing a scenario with and without the sprint jump
			if (System.getenv("HB_NOHOP") != null) ctx.runOnClient(mc -> com.humanisedbaritone.settings.Settings.SPRINT_JUMP.set(false));

			// open ground: it must be run flat out in a straight line, sprint held the whole way (the planner's own cost noise makes a path up to ~1.5% longer than the line; a staircase would be 8%)
			Limits open = new Limits(5.0, 97, 0, 0, 1.03);
			// 80 blocks of nothing: the baseline for speed and for holding sprint
			if (only("straight")) trace(ctx, sp, "straight", "", "tp @a 0.5 -60 0.5", "goto 80 -60 0", 80, -60, 0, 1000, open);
			// the goal lies at an angle, so the plan mixes diagonal and straight moves
			if (only("angled")) {
				String rep = System.getenv("HB_REPEAT");
				for (int r = rep == null ? 1 : Integer.parseInt(rep); r > 0; r--) trace(ctx, sp, "angled", "", "tp @a 0.5 -60 30.5", "goto 60 -60 50", 60, -60, 50, 1000, open);
			}
			// a shallow angle: long straight stretches with only a few diagonal steps in between
			if (only("shallow")) trace(ctx, sp, "shallow", "", "tp @a 0.5 -60 90.5", "goto 70 -60 98", 70, -60, 98, 1000, open);
			// a long trip over open ground
			if (only("long")) trace(ctx, sp, "long", "", "tp @a -10.5 -60 150.5", "goto 150 -60 150", 150, -60, 150, 1800, open);
			// two walls the route has to go round: it may bend, but only where it must, and still at speed
			if (only("walls")) {
				// otherwise it digs through the thin walls, or builds a staircase over them
				ctx.runOnClient(mc -> {
					com.humanisedbaritone.settings.Settings.BREAK.set(false);
					com.humanisedbaritone.settings.Settings.PLACE.set(false);
				});
				trace(ctx, sp, "walls", "fill 20 -60 100 20 -58 125 stone\nfill 40 -60 115 40 -58 140 stone\nfill 60 -60 100 60 -58 125 stone", "tp @a 0.5 -60 120.5", "goto 80 -60 120", 80, -60, 120, 1200,
					new Limits(3.8, 80, 0, 20, 2.2));
				ctx.runOnClient(mc -> {
					com.humanisedbaritone.settings.Settings.BREAK.set(true);
					com.humanisedbaritone.settings.Settings.PLACE.set(true);
				});
			}
			// drop onto slime (bounces) and walk across it: never turn back for a block that was passed
			if (only("slime")) trace(ctx, sp, "slime", "fill 190 -60 -2 193 -58 2 stone\nfill 194 -61 -40 200 -61 40 slime_block", "tp @a 191.5 -57 0.5", "goto 202 -60 0", 202, -60, 0, 900,
				new Limits(0, 0, 3, 2, 1.5));
			// a one-wide blue ice lane with a corner, over a pit: speed has to be held back, or the player slides off at the corner
			if (only("ice")) trace(ctx, sp, "ice", "fill 150 -63 -10 170 -61 12 air\nfill 150 -61 0 165 -61 0 blue_ice\nfill 165 -61 1 165 -61 8 blue_ice", "tp @a 147.5 -60 0.5", "goto 165 -60 8", 165, -60, 8, 1200,
				new Limits(0, 0, 5, 3, 1.4));
			// a terraced slope going up diagonally: a diagonal run into a step up must not end beside the block and turn round
			if (only("hill")) trace(ctx, sp, "hill", stairs(), "tp @a 0.5 -60 170.5", "goto 24 -53 194", 24, -53, 194, 900, new Limits(3.5, 0, 6, 6, 1.25));
			// sprint jump: bunny-hopping over open ground beats a plain sprint (5.6 b/s) by a wide margin, the jump key is used, and the bot still ends up where it should
			if (only("hop")) {
				jumps(10, 1000);
				trace(ctx, sp, "hop", "", "tp @a 0.5 -60 -9.5", "goto 80 -60 -10", 80, -60, -10, 1000, new Limits(6.2, 97, 0, 0, 1.03));
			}
			// the toggle off: the same run is a plain sprint, not one jump
			if (only("nohop")) {
				ctx.runOnClient(mc -> com.humanisedbaritone.settings.Settings.SPRINT_JUMP.set(false));
				jumps(0, 0);
				trace(ctx, sp, "nohop", "", "tp @a 0.5 -60 -9.5", "goto 80 -60 -10", 80, -60, -10, 1000, new Limits(5.0, 97, 0, 0, 1.03));
				ctx.runOnClient(mc -> com.humanisedbaritone.settings.Settings.SPRINT_JUMP.set(true));
			}
			// a ceiling two blocks above the floor all along the way: a hop would bonk it, so there must be none
			if (only("lowroof")) {
				jumps(0, 0);
				trace(ctx, sp, "lowroof", "fill -2 -58 -34 72 -58 -26 stone", "tp @a 0.5 -60 -29.5", "goto 70 -60 -30", 70, -60, -30, 1000, new Limits(5.0, 97, 0, 0, 1.03));
			}
			finish();
		}
	}

	/** Bounds for the number of ticks the jump key was pressed while cruising, applied to the next trace only. */
	private int minJumps, maxJumps = Integer.MAX_VALUE;

	private void jumps(int min, int max) {
		minJumps = min;
		maxJumps = max;
	}

	/** What a trace has to achieve to pass (zero or one disables a limit): mean speed in blocks per second, percent of ticks with forward held, ticks moving away from the goal, turns of more than 40 degrees, and the walked distance relative to the straight line. */
	protected record Limits(double speed, double fwd, int back, int sharp, double detour) {
		static final Limits NONE = new Limits(0, 0, 100000, 100000, 100);
	}

	protected double mSpeed, mFwd, mDetour;
	protected int mBack, mSharp, mJumps;

	private static String stairs() {
		StringBuilder sb = new StringBuilder();
		// a gentle slope rising one block every 3 blocks to the south-east, 7 levels
		for (int k = 1; k <= 7; k++) sb.append("fill ").append(3 * k).append(" -60 ").append(170 + 3 * k).append(' ').append(27).append(" ").append(-60 + k - 1).append(' ').append(200).append(" stone\n");
		return sb.toString();
	}

	private void trace(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, String build, String tp, String command, int gx, int gy, int gz, int timeout, Limits lim) {
		revive(ctx, sp);
		cmds(sp, "give @a iron_pickaxe", "give @a cobblestone 64");
		for (String c : build.split("\n")) if (!c.isBlank()) cmd(sp, c);
		cmd(sp, tp);
		ctx.waitTicks(25);
		record(ctx, name, command, gx, gy, gz, timeout, lim);
	}

	/** Runs the command, records the player until the bot is done and reports the numbers. {@code gy} is ignored when it is Integer.MIN_VALUE. */
	protected void record(ClientGameTestContext ctx, String name, String command, int gx, int gy, int gz, int timeout, Limits lim) {
		rows.clear();
		labels.clear();
		double[] start = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		ctx.runOnClient(mc -> {
			com.humanisedbaritone.Log.clear();
			Commands.run(command);
		});
		int[] n = {0};
		boolean arrived = until(ctx, name, mc -> {
			sample(mc, n[0]++);
			return !Bot.busy();
		}, timeout);
		double[] end = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		double d = Math.hypot(end[0] - (gx + 0.5), end[2] - (gz + 0.5));
		boolean ok = arrived && d < (gy == Integer.MIN_VALUE ? 3.0 : 1.5) && (gy == Integer.MIN_VALUE || Math.abs(end[1] - gy) < 1.0);
		String stats = analyse(name, start, gx + 0.5, gz + 0.5);
		StringBuilder why = new StringBuilder();
		if (mSpeed < lim.speed()) why.append(String.format(" [too slow: %.2f < %.2f b/s]", mSpeed, lim.speed()));
		if (mFwd < lim.fwd()) why.append(String.format(" [forward held only %.0f%%]", mFwd));
		if (mBack > lim.back()) why.append(String.format(" [%d ticks going away from the goal]", mBack));
		if (mSharp > lim.sharp()) why.append(String.format(" [%d sharp turns]", mSharp));
		if (mDetour > lim.detour()) why.append(String.format(" [detour %.2f]", mDetour));
		if (mJumps < minJumps || mJumps > maxJumps) why.append(String.format(" [%d jumps, wanted %d..%d]", mJumps, minJumps, maxJumps));
		jumps(0, Integer.MAX_VALUE);
		ok &= why.length() == 0;
		report(name, ok, String.format("dist=%.2f%s %s", d, why, stats));
		dump(name);
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
	}

	private void sample(net.minecraft.client.Minecraft mc, int tick) {
		var p = mc.player;
		var c = Bot.ctl;
		boolean moving = Bot.nav.state() == Nav.State.MOVING;
		rows.add(new double[]{tick, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.onGround() ? 1 : 0, c.forward ? 1 : 0, c.sprint ? 1 : 0, p.isSprinting() ? 1 : 0, c.jump ? 1 : 0, moving ? 1 : 0});
		labels.add(Bot.nav.describe());
	}

	private static double wrap(double d) {
		d %= 360;
		if (d >= 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}

	private String analyse(String name, double[] start, double gx, double gz) {
		int n = rows.size();
		mSpeed = mFwd = mDetour = 0;
		mBack = mSharp = mJumps = 0;
		if (n < 3) return "no data";
		double dist = 0, camTurn = 0;
		int firstMove = -1, lastMove = n - 1;
		for (int i = 1; i < n; i++) {
			double[] a = rows.get(i - 1), b = rows.get(i);
			double sp = Math.hypot(b[1] - a[1], b[3] - a[3]);
			dist += sp;
			camTurn += Math.abs(wrap(b[4] - a[4]));
			if (firstMove < 0 && sp > 0.03) firstMove = i;
		}
		double straight = Math.hypot(gx - start[0], gz - start[2]);
		double[] last = rows.get(n - 1);
		for (int i = n - 1; i > 0; i--) {
			double[] a = rows.get(i - 1), b = rows.get(i);
			if (Math.hypot(b[1] - a[1], b[3] - a[3]) > 0.03) {
				lastMove = i;
				break;
			}
		}
		int moveTicks = Math.max(1, lastMove - Math.max(firstMove, 0));
		// cruise: everything more than 3 blocks from both ends of the trip, while the navigator is moving
		int cruise = 0, fwd = 0, spr = 0, sprKey = 0, slow = 0, backTicks = 0, jumps = 0;
		double cruiseDist = 0;
		int stopRuns = 0, run = 0, fwdGaps = 0, fwdRun = 0, airTicks = 0;
		for (int i = 1; i < n; i++) {
			double[] a = rows.get(i - 1), b = rows.get(i);
			double sp = Math.hypot(b[1] - a[1], b[3] - a[3]);
			double toGoal = Math.hypot(gx - b[1], gz - b[3]), fromStart = Math.hypot(b[1] - start[0], b[3] - start[2]);
			if (b[10] == 0 || i < firstMove || toGoal < 3 || fromStart < 3) {
				run = 0;
				fwdRun = 0;
				continue;
			}
			cruise++;
			cruiseDist += sp;
			if (b[6] > 0) fwd++;
			if (b[8] > 0) spr++;
			if (b[7] > 0) sprKey++;
			if (b[9] > 0) jumps++;
			if (b[5] == 0) airTicks++;
			if (sp < 0.04) slow++;
			// moving away from the goal
			double gdx = gx - a[1], gdz = gz - a[3], gl = Math.hypot(gdx, gdz);
			if (gl > 1e-6 && ((b[1] - a[1]) * gdx + (b[3] - a[3]) * gdz) / gl < -0.02) backTicks++;
			if (sp < 0.04) {
				if (++run == 3) stopRuns++;
			} else run = 0;
			if (b[6] == 0) {
				if (++fwdRun == 2) fwdGaps++;
			} else fwdRun = 0;
		}
		// heading changes between consecutive 5-tick windows
		int sharp = 0, turns20 = 0;
		double turnTotal = 0, prevH = Double.NaN;
		for (int i = 5; i < n; i += 5) {
			double[] a = rows.get(i - 5), b = rows.get(i);
			double mx = b[1] - a[1], mz = b[3] - a[3];
			if (Math.hypot(mx, mz) < 0.5) continue;
			double h = Math.toDegrees(Math.atan2(-mx, mz));
			if (!Double.isNaN(prevH)) {
				double dh = Math.abs(wrap(h - prevH));
				turnTotal += dh;
				if (dh > 20) turns20++;
				if (dh > 40) sharp++;
			}
			prevH = h;
		}
		double meanSpeed = dist / Math.max(1, last[0] - rows.get(0)[0]) * 20;
		double runSpeed = dist / moveTicks * 20;
		double cruiseSpeed = cruise == 0 ? 0 : cruiseDist / cruise * 20;
		mSpeed = meanSpeed;
		mFwd = pct(fwd, cruise);
		mDetour = dist / Math.max(1, straight);
		mBack = backTicks;
		mSharp = sharp;
		mJumps = jumps;
		return String.format("ticks=%d startWait=%d len=%.1f straightLine=%.1f detour=%.3f meanSpeed=%.2f b/s runSpeed=%.2f b/s cruiseSpeed=%.2f b/s (sprint ideal 5.61) | cruise: fwd=%.0f%% sprint=%.0f%% sprintKey=%.0f%% stopRuns=%d slowTicks=%d fwdGaps=%d jumps=%d air=%d backTicks=%d"
				+ " | heading: turns>20=%d sharp>40=%d totalTurn=%.0f deg camTurn=%.0f deg",
			n, firstMove, dist, straight, dist / Math.max(1, straight), meanSpeed, runSpeed, cruiseSpeed, pct(fwd, cruise), pct(spr, cruise), pct(sprKey, cruise), stopRuns, slow, fwdGaps, jumps, airTicks, backTicks,
			turns20, sharp, turnTotal, camTurn);
	}

	private static double pct(int a, int b) {
		return b == 0 ? 0 : 100.0 * a / b;
	}

	private void dump(String name) {
		try {
			Path dir = Path.of("..", "..", "feel"); // the game runs in build/run/clientGameTest, which Gradle wipes before every run: keep the traces in build/feel
			Files.createDirectories(dir);
			StringBuilder sb = new StringBuilder("tick,x,y,z,yaw,ground,fwd,sprintKey,sprinting,jump,moving,label\n");
			for (int i = 0; i < rows.size(); i++) {
				double[] r = rows.get(i);
				sb.append(String.format(java.util.Locale.ROOT, "%d,%.3f,%.3f,%.3f,%.1f,%d,%d,%d,%d,%d,%d,%s%n", (int) r[0], r[1], r[2], r[3], r[4], (int) r[5], (int) r[6], (int) r[7], (int) r[8], (int) r[9], (int) r[10], labels.get(i)));
			}
			Files.writeString(dir.resolve(name + ".csv"), sb.toString());
		} catch (IOException e) {
			System.out.println("[HBTEST] could not write the trace: " + e);
		}
	}
}
