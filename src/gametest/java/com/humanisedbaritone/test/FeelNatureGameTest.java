package com.humanisedbaritone.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The numbers of {@link FeelGameTest} in a normally generated world: hills, trees, water. The start is fixed (the player
 * is dropped from the sky with fall damage switched off) and the bot's randomness is seeded, so two builds walk the same
 * routes and can be compared.
 */
public class FeelNatureGameTest extends FeelGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("hbnature")).create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(120);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful", "gamerule spawn_mobs false");
			cmd(sp, "forceload add -60 -130 140 70");
			ctx.waitTicks(40);
			botOn(ctx);
			ctx.runOnClient(mc -> {
				com.humanisedbaritone.settings.Settings.VAR_SEED.set(4242.0);
				com.humanisedbaritone.human.Rng.reseed();
			});
			cmds(sp, "effect give @a minecraft:resistance 600 4 true", "tp @a 25.5 250 -50.5");
			until(ctx, "teleport", mc -> mc.player.getY() > 200, 100);
			until(ctx, "landing", mc -> mc.player.getY() < 200 && mc.player.onGround(), 600);
			ctx.waitTicks(20);
			int[][] legs = {{100, 0}, {0, 100}, {-100, 0}, {0, -100}, {60, 60}, {-60, -60}};
			for (int n = 0; n < legs.length; n++) {
				double[] from = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()});
				int gx = (int) Math.floor(from[0]) + legs[n][0], gz = (int) Math.floor(from[1]) + legs[n][1];
				record(ctx, "nature-leg" + n, "goto " + gx + " " + gz, gx, Integer.MIN_VALUE, gz, 3000, Limits.NONE);
			}
			finish();
		}
	}
}
