package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Floors that are not plain full blocks: soul sand and mud sink the player in, honey stops jumps, slime and soul sand slow
 * walking down, ice will not let the player stop. Trenches go down into the void, so a wrong move there is fatal.
 */
public class SurfaceGameTest extends BotTest {
	private double minY;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			load(ctx, sp, -10, -48, 230, 47);
			botOn(ctx);

			// walk over soul sand, then step up from it
			if (only("soulsand-walk")) run(ctx, sp, "soulsand-walk", "fill 5 -61 -40 15 -61 40 soul_sand\nfill 16 -60 -40 19 -60 40 stone",
				"tp @a 2.5 -60 0.5", "goto 18 -59 0", 18, -59, 0, 900, false);
			// bridge over the void when all there is to stand on is soul sand (or mud, same shape)
			if (only("soulsand-bridge")) run(ctx, sp, "soulsand-bridge", "fill 40 -61 -40 43 -61 40 soul_sand\nfill 44 -64 -40 48 -61 40 air",
				"tp @a 41.5 -60 0.5", "goto 51 -60 0", 51, -60, 0, 2000, false);
			if (only("mud-bridge")) run(ctx, sp, "mud-bridge", "fill 60 -61 -40 63 -61 40 mud\nfill 64 -64 -40 68 -61 40 air",
				"tp @a 61.5 -60 0.5", "goto 71 -60 0", 71, -60, 0, 2000, false);
			// a gap that needs a running jump, but the run-up is soul sand: too slow to make it
			if (only("soulsand-gap")) run(ctx, sp, "soulsand-gap", "fill 80 -61 -40 83 -61 40 soul_sand\nfill 84 -64 -40 85 -61 40 air",
				"tp @a 81.5 -60 0.5", "goto 88 -60 0", 88, -60, 0, 2000, false);
			// honey: no jumping off it, no pillars, no bridges from it
			if (only("honey")) run(ctx, sp, "honey", "fill 100 -61 -40 106 -61 40 honey_block\nfill 107 -60 -40 109 -60 40 stone",
				"tp @a 101.5 -60 0.5", "goto 108 -59 0", 108, -59, 0, 2000, false);
			if (only("honey-bridge")) run(ctx, sp, "honey-bridge", "fill 120 -61 -40 123 -61 40 honey_block\nfill 124 -64 -40 128 -61 40 air",
				"tp @a 121.5 -60 0.5", "goto 131 -60 0", 131, -60, 0, 2400, false);
			// blue ice path with a corner, 3 blocks above a pit: sliding off it is a failure
			if (only("ice")) run(ctx, sp, "ice", "fill 150 -63 -10 170 -61 12 air\nfill 150 -61 0 165 -61 0 blue_ice\nfill 165 -61 1 165 -61 8 blue_ice",
				"tp @a 147.5 -60 0.5", "goto 165 -60 8", 165, -60, 8, 1200, true);
			// drop onto slime (bounces) and walk across it (slow)
			if (only("slime")) run(ctx, sp, "slime", "fill 190 -60 -2 193 -58 2 stone\nfill 194 -61 -40 200 -61 40 slime_block",
				"tp @a 191.5 -57 0.5", "goto 202 -60 0", 202, -60, 0, 900, false);
			finish();
		}
	}

	private void run(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, String build, String tp, String command, int gx, int gy, int gz, int timeout,
		boolean noFall) {
		revive(ctx, sp);
		cmds(sp, "give @a iron_pickaxe", "give @a cobblestone 64");
		for (String c : build.split("\n")) cmd(sp, c);
		cmd(sp, tp);
		ctx.waitTicks(20);
		minY = Double.MAX_VALUE;
		ctx.runOnClient(mc -> {
			com.humanisedbaritone.Log.clear();
			Commands.run(command);
		});
		until(ctx, name, mc -> {
			minY = Math.min(minY, mc.player.getY());
			return !Bot.busy();
		}, timeout);
		double[] pos = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.isAlive() ? 1 : 0});
		double d = Math.hypot(pos[0] - (gx + 0.5), pos[2] - (gz + 0.5));
		// getting there after a few failed tries is not good enough: every step has to work the first time
		long failed = client(ctx, mc -> com.humanisedbaritone.Log.lines().stream().filter(l -> l.contains("step failed")).count());
		boolean ok = pos[3] > 0 && d < 1.2 && Math.abs(pos[1] - gy) < 1.0 && (!noFall || minY > gy - 0.6) && failed == 0;
		report(name, ok, String.format("pos=%.2f %.2f %.2f dist=%.2f minY=%.2f alive=%b failedSteps=%d state=%s %s", pos[0], pos[1], pos[2], d, minY, pos[3] > 0, failed,
			Bot.nav.state(), Bot.nav.failReason));
		shot(ctx, "surface-" + name);
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
	}
}
