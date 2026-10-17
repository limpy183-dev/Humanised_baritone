package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.Tasks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

public class MoveGameTest implements FabricClientGameTest {
	private int failures;
	private double maxYawStep, maxPitchStep;
	private float lastYaw, lastPitch;
	private boolean haveLast;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "time set day");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "give @a iron_pickaxe");
			cmd(sp, "give @a cobblestone 64");
			ctx.runOnClient(mc -> {
				Settings.FEEDBACK.set(false);
				Settings.DEBUG.set(true);
				Bot.setEnabled(true);
			});

			// A: flat ground
			scenario(ctx, sp, "flat", "tp @a 0.5 -60 0.5", "", 0.5, -60, 0.5, "goto 18 -60 0", 18, -60, 0, 400);

			// B: a staircase of solid blocks
			scenario(ctx, sp, "stairs", "tp @a 0.5 -60 6.5",
				"fill 3 -60 5 3 -60 7 stone\nfill 4 -60 5 4 -59 7 stone\nfill 5 -60 5 5 -58 7 stone\nfill 6 -60 5 8 -58 7 stone",
				0.5, -60, 6.5, "goto 7 -57 6", 7, -57, 6, 400);

			// C: a trench that has to be jumped
			scenario(ctx, sp, "gap", "tp @a 300.5 -60 0.5", "fill 303 -63 -100 304 -61 100 air",
				300.5, -60, 0.5, "goto 309 -60 0", 309, -60, 0, 600);

			// D: a cavity inside solid stone: must be mined into
			scenario(ctx, sp, "mine-in", "tp @a 0.5 -60 30.5",
				"fill 10 -60 28 16 -57 34 stone\nfill 13 -60 31 13 -59 31 air",
				0.5, -60, 30.5, "goto 13 -60 31", 13, -60, 31, 1500);

			// E: too wide to jump: bridge with placed blocks
			ctx.runOnClient(mc -> Settings.PARKOUR.set(false));
			scenario(ctx, sp, "bridge", "tp @a 400.5 -60 0.5", "fill 403 -63 -100 407 -61 100 air",
				400.5, -60, 0.5, "goto 411 -60 0", 411, -60, 0, 1800);
			ctx.runOnClient(mc -> Settings.PARKOUR.set(true));

			// F: a tower: pillar up next to it
			scenario(ctx, sp, "pillar", "tp @a 0.5 -60 -80.5", "fill 6 -60 -82 8 -54 -79 stone",
				0.5, -60, -80.5, "goto 7 -53 -80", 7, -53, -80, 2000);

			// G: dig down into a sealed cavity
			scenario(ctx, sp, "dig-down", "tp @a 0.5 -60 -100.5", "fill 12 -63 -102 12 -62 -102 air",
				0.5, -60, -100.5, "goto 12 -63 -102", 12, -63, -102, 1200);

			// H: climb a ladder
			scenario(ctx, sp, "ladder", "tp @a 0.5 -60 -120.5",
				"fill 20 -61 -121 20 -50 -121 stone\nfill 19 -60 -121 19 -50 -121 ladder[facing=west]",
				0.5, -60, -120.5, "goto 20 -49 -121", 20, -49, -121, 1500);

			// I: swim across a pond
			scenario(ctx, sp, "swim", "tp @a 500.5 -60 0.5", "fill 503 -63 -100 511 -61 100 water",
				500.5, -60, 0.5, "goto 515 -60 0", 515, -60, 0, 1200);

			// J: jump off a tall tower and cancel the fall with a bucket of water
			scenario(ctx, sp, "clutch", "tp @a 51.5 -50 -148.5", "fill 50 -60 -150 52 -51 -148 stone\ngive @a water_bucket",
				51.5, -50, -148.5, "goto 51 -60 -144", 51, -60, -144, 800);

			System.out.println("[HBTEST] max yaw step per tick = " + maxYawStep + " deg, max pitch step = " + maxPitchStep + " deg");
			System.out.println("[HBTEST] DONE failures=" + failures);
			System.out.flush();
			Runtime.getRuntime().halt(failures > 0 ? 1 : 0); // closing the world can deadlock inside the gametest framework
		}
	}

	private void scenario(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, String tp, String build, double sx, double sy, double sz,
		String command, int gx, int gy, int gz, int timeout) {
		for (String c : build.split("\n")) if (!c.isBlank()) cmd(sp, c);
		ctx.runOnClient(mc -> {
			if (mc.player != null && !mc.player.isAlive()) mc.player.respawn();
		});
		ctx.waitTicks(10);
		cmd(sp, "gamemode survival @a");
		cmd(sp, "effect give @a minecraft:instant_health 1 10");
		int x1 = (int) Math.min(sx, gx) - 16, x2 = (int) Math.max(sx, gx) + 16, z1 = (int) Math.min(sz, gz) - 110, z2 = (int) Math.max(sz, gz) + 110;
		cmd(sp, "forceload add " + x1 + " " + z1 + " " + x2 + " " + z2);
		final int cx = (int) Math.floor((sx + gx) / 2), cz = (int) Math.floor((sz + gz) / 2);
		sp.getServer().waitFor(server -> server.overworld().hasChunkAt(new net.minecraft.core.BlockPos(cx, -60, cz))
			&& server.overworld().hasChunkAt(new net.minecraft.core.BlockPos(x1, -60, z1)) && server.overworld().hasChunkAt(new net.minecraft.core.BlockPos(x2, -60, z2)), 400);
		ctx.waitTicks(10);
		for (String c : build.split("\n")) if (!c.isBlank()) cmd(sp, c);
		ctx.waitTicks(10);
		cmd(sp, tp);
		ctx.waitTicks(25);
		ctx.runOnClient(mc -> {
			Log.clear();
			Commands.run(command);
			haveLast = false;
		});
		boolean ok;
		try {
			ctx.waitFor(mc -> {
				sampleLook(mc);
				return Bot.nav.state() == Nav.State.ARRIVED || Bot.nav.state() == Nav.State.FAILED || !Bot.busy();
			}, timeout);
		} catch (Throwable t) {
			System.out.println("[HBTEST] " + name + ": timed out (" + t.getMessage() + ")");
		}
		double[] pos = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		double d = Math.sqrt(Math.pow(pos[0] - (gx + 0.5), 2) + Math.pow(pos[2] - (gz + 0.5), 2));
		ok = d < 1.2 && Math.abs(pos[1] - gy) < 1.0;
		System.out.println("[HBTEST] " + name + (ok ? ": PASS" : ": FAIL") + " pos=" + String.format("%.2f %.2f %.2f", pos[0], pos[1], pos[2]) + " dist=" + String.format("%.2f", d)
			+ " state=" + Bot.nav.state() + " reason=" + Bot.nav.failReason);
		for (String l : Log.lines()) System.out.println("[HBTEST]    " + l);
		ctx.takeScreenshot(TestScreenshotOptions.of("move-" + name).withSize(1280, 720));
		if (!ok) failures++;
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
	}

	private void sampleLook(net.minecraft.client.Minecraft mc) {
		if (mc.player == null) return;
		float y = mc.player.getYRot(), p = mc.player.getXRot();
		if (haveLast) {
			maxYawStep = Math.max(maxYawStep, Math.abs(net.minecraft.util.Mth.wrapDegrees(y - lastYaw)));
			maxPitchStep = Math.max(maxPitchStep, Math.abs(p - lastPitch));
		}
		lastYaw = y;
		lastPitch = p;
		haveLast = true;
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		sp.getServer().runCommand(c);
	}
}
