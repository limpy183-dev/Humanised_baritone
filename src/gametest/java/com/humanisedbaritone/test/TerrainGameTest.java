package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Everyday obstacles in a flat world: doors, gates, slabs, stairs, carpets, snow. */
public class TerrainGameTest extends BotTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			revive(ctx, sp);
			load(ctx, sp, -20, -80, 120, 80);
			cmds(sp, "give @a iron_pickaxe");
			botOn(ctx);

			if (only("door")) {
				cmds(sp, "fill 10 -60 -70 10 -58 70 stone",
					"setblock 10 -60 0 oak_door[facing=east,half=lower,hinge=left,open=false]",
					"setblock 10 -59 0 oak_door[facing=east,half=upper,hinge=left,open=false]");
				run(ctx, sp, "door", "tp @a 5.5 -60 0.5", "goto 15 -60 0", 15, -60, 0, 600);
			}
			if (only("gate")) {
				cmds(sp, "fill 30 -60 -70 30 -60 70 oak_fence", "setblock 30 -60 0 oak_fence_gate[facing=east,open=false]");
				run(ctx, sp, "gate", "tp @a 25.5 -60 0.5", "goto 35 -60 0", 35, -60, 0, 600);
			}
			if (only("slabs")) {
				cmds(sp, "fill 50 -60 0 52 -60 0 oak_slab[type=bottom]", "setblock 53 -60 0 oak_stairs[facing=east,half=bottom]", "setblock 54 -60 0 stone",
					"fill 48 -60 -3 56 -60 -3 stone", "fill 48 -60 3 56 -60 3 stone");
				run(ctx, sp, "slabs-stairs", "tp @a 47.5 -60 0.5", "goto 54 -59 0", 54, -59, 0, 600);
			}
			if (only("carpet")) {
				cmds(sp, "fill 70 -60 0 75 -60 0 white_carpet", "setblock 76 -60 0 snow[layers=3]", "setblock 77 -60 0 snow[layers=2]");
				run(ctx, sp, "carpet-snow", "tp @a 67.5 -60 0.5", "goto 80 -60 0", 80, -60, 0, 600);
			}
			finish();
		}
	}

	private void run(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, String tp, String command, int gx, int gy, int gz, int timeout) {
		cmd(sp, tp);
		ctx.waitTicks(20);
		ctx.runOnClient(mc -> {
			com.humanisedbaritone.Log.clear();
			Commands.run(command);
		});
		until(ctx, name, mc -> !Bot.busy(), timeout);
		double[] pos = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		double d = Math.hypot(pos[0] - (gx + 0.5), pos[2] - (gz + 0.5));
		report(name, d < 1.5 && Math.abs(pos[1] - gy) < 1.0, String.format("pos=%.2f %.2f %.2f dist=%.2f", pos[0], pos[1], pos[2], d));
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
	}
}
