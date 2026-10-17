package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Shared helpers for the in-game tests. */
public abstract class BotTest implements FabricClientGameTest {
	protected int failures;

	/** True if the scenario should run; set HB_ONLY to a comma separated list of names to run just those. */
	protected static boolean only(String name) {
		String o = System.getenv("HB_ONLY");
		return o == null || o.isBlank() || java.util.Arrays.asList(o.split(",")).contains(name);
	}

	protected static void cmd(TestSingleplayerContext sp, String c) {
		sp.getServer().runCommand(c);
	}

	protected static void cmds(TestSingleplayerContext sp, String... cs) {
		for (String c : cs) cmd(sp, c);
	}

	/** Loads the chunks around a region and waits until the server really has them. */
	protected static void load(ClientGameTestContext ctx, TestSingleplayerContext sp, int x1, int z1, int x2, int z2) {
		cmd(sp, "forceload add " + x1 + " " + z1 + " " + x2 + " " + z2);
		sp.getServer().waitFor(s -> s.overworld().hasChunkAt(new BlockPos(x1, -60, z1)) && s.overworld().hasChunkAt(new BlockPos(x2, -60, z2)), 600);
		ctx.waitTicks(10);
	}

	protected static void revive(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ctx.runOnClient(mc -> {
			if (mc.player != null && !mc.player.isAlive()) mc.player.respawn(); // what the respawn button does
		});
		ctx.waitTicks(10);
		try {
			ctx.waitFor(mc -> mc.player.isAlive(), 200); // the new life begins when the server has answered
		} catch (Throwable ignored) {
		}
		cmds(sp, "gamemode survival @a", "effect clear @a", "effect give @a minecraft:instant_health 1 10", "effect give @a minecraft:saturation 2 20", "clear @a");
		ctx.waitTicks(5); // instant effects are applied on the next server ticks: a test that sets its own health or food must not be overwritten by them
	}

	/** Writes a Sponge .schem into the schematics folder; {@code at} gives the block state string of a cell, or null for air. */
	protected static void writeSchem(String name, int w, int h, int l, java.util.function.Function<int[], String> at) {
		try {
			var root = new net.minecraft.nbt.CompoundTag();
			root.putInt("Version", 2);
			root.putShort("Width", (short) w);
			root.putShort("Height", (short) h);
			root.putShort("Length", (short) l);
			var pal = new net.minecraft.nbt.CompoundTag();
			pal.putInt("minecraft:air", 0);
			byte[] data = new byte[w * h * l];
			for (int y = 0; y < h; y++) {
				for (int z = 0; z < l; z++) {
					for (int x = 0; x < w; x++) {
						String s = at.apply(new int[]{x, y, z});
						if (s == null) continue;
						if (!pal.contains(s)) pal.putInt(s, pal.size());
						data[x + z * w + y * w * l] = (byte) pal.getIntOr(s, 0);
					}
				}
			}
			root.put("Palette", pal);
			root.putByteArray("BlockData", data);
			java.nio.file.Files.createDirectories(com.humanisedbaritone.build.Schematics.dir());
			net.minecraft.nbt.NbtIo.writeCompressed(root, com.humanisedbaritone.build.Schematics.dir().resolve(name + ".schem"));
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	protected static <T> T client(ClientGameTestContext ctx, Function<Minecraft, T> f) {
		return ctx.computeOnClient(f::apply);
	}

	protected boolean until(ClientGameTestContext ctx, String what, Predicate<Minecraft> p, int ticks) {
		try {
			ctx.waitFor(p::test, ticks);
			return true;
		} catch (Throwable t) {
			System.out.println("[HBTEST]    timed out waiting for " + what);
			return false;
		}
	}

	protected void report(String name, boolean ok, String detail) {
		System.out.println("[HBTEST] " + name + (ok ? ": PASS " : ": FAIL ") + detail);
		for (String l : Log.lines()) System.out.println("[HBTEST]    " + l);
		if (!ok) failures++;
	}

	protected void shot(ClientGameTestContext ctx, String name) {
		ctx.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 720));
	}

	protected void finish() {
		System.out.println("[HBTEST] DONE failures=" + failures);
		System.out.flush();
		Runtime.getRuntime().halt(failures > 0 ? 1 : 0); // closing the world can deadlock inside the gametest framework
	}

	protected static void botOn(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			com.humanisedbaritone.settings.Settings.FEEDBACK.set(false);
			com.humanisedbaritone.settings.Settings.DEBUG.set(true);
			Log.clear();
			Bot.setEnabled(true);
		});
	}
}
