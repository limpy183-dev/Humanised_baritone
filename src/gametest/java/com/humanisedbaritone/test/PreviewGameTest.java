package com.humanisedbaritone.test;

import com.humanisedbaritone.Commands;
import com.humanisedbaritone.build.Placement;
import com.humanisedbaritone.settings.Settings;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The in-world preview of a loaded schematic: ghost blocks, wrong (red), orientation (orange) and extra (purple) blocks, and that a block disappears from it once it is right. */
public class PreviewGameTest extends BotTest {
	private static final String NAME = "Pickle_Farm";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful", "gamemode creative @a");
			PickleGameTest.install();
			load(ctx, sp, 190, 190, 240, 240);
			int ox = 210, oy = -60, oz = 210;
			ctx.runOnClient(mc -> mc.player.getAbilities().flying = true);
			if (System.getenv("HB_PERF") != null) {
				perf(ctx, sp);
				finish();
			}
			cmds(sp, "tp @a 203.5 -58 213.5 -90 10");
			ctx.waitTicks(20);
			botOn(ctx);
			ctx.runOnClient(mc -> Commands.run("build " + NAME + " " + ox + " " + oy + " " + oz));
			ctx.waitTicks(20);
			report("loaded", client(ctx, mc -> Placement.current != null && Placement.current.size() == 170 && !Placement.current.confirmed),
				"placement=" + client(ctx, mc -> Placement.current == null ? "none" : Placement.current.info()));
			int[] c0 = client(ctx, mc -> Placement.current.count.clone());
			report("all-missing", c0[Placement.MISSING] == 170, "counts=" + java.util.Arrays.toString(c0));
			shot(ctx, "preview-front");

			cmds(sp, "tp @a 222.5 -50 205.5 40 30");
			ctx.waitTicks(15);
			shot(ctx, "preview-corner");

			// a right block, a wrong block, a wrong orientation, an extra block, all next to each other
			BlockPos[] cells = client(ctx, mc -> {
				Placement p = Placement.current;
				BlockPos ok = null, wrong = null, state = null;
				for (int i = 0; i < p.size(); i++) {
					BlockState w = p.want(i);
					if (ok == null && w.is(Blocks.DEEPSLATE_BRICKS)) ok = p.pos(i);
					else if (wrong == null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(w.getBlock()).getPath().equals("white_stained_glass")) wrong = p.pos(i);
					else if (state == null && w.is(Blocks.HOPPER)) state = p.pos(i);
				}
				return new BlockPos[]{ok, wrong, state};
			});
			String okState = client(ctx, mc -> {
				for (int i = 0; i < Placement.current.size(); i++) if (Placement.current.pos(i).equals(cells[0])) return BlockStateParser.serialize(Placement.current.want(i));
				return "?";
			});
			String hopperUp = "minecraft:hopper[facing=north]";
			cmds(sp, "setblock " + cells[0].toShortString().replace(",", "") + " " + okState,
				"setblock " + cells[1].toShortString().replace(",", "") + " minecraft:stone",
				"setblock " + cells[2].toShortString().replace(",", "") + " " + hopperUp,
				"setblock 210 -60 210 minecraft:stone");
			ctx.waitTicks(15);
			int[] c = client(ctx, mc -> Placement.current.count.clone());
			report("diff", c[Placement.MISSING] == 167 && c[Placement.WRONG] == 1 && c[Placement.STATE] == 1 && c[Placement.OK] == 1 && c[Placement.EXTRA] == 1, "counts=" + java.util.Arrays.toString(c));
			cmds(sp, "tp @a 203.5 -58 213.5 -90 10");
			ctx.waitTicks(15);
			shot(ctx, "preview-marks");
			finish();
		}
	}

	/** A big schematic (about 6000 blocks in a hollow hall): the frame rate with no preview, with the preview and with its parts switched off. */
	private void perf(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		try {
			var root = new net.minecraft.nbt.CompoundTag();
			root.putInt("Version", 2);
			int w = 40, h = 20, l = 40;
			root.putShort("Width", (short) w);
			root.putShort("Height", (short) h);
			root.putShort("Length", (short) l);
			var pal = new net.minecraft.nbt.CompoundTag();
			pal.putInt("minecraft:air", 0);
			pal.putInt("minecraft:stone_bricks", 1);
			pal.putInt("minecraft:glass", 2);
			pal.putInt("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", 3);
			root.put("Palette", pal);
			byte[] data = new byte[w * h * l];
			for (int y = 0; y < h; y++) {
				for (int z = 0; z < l; z++) {
					for (int x = 0; x < w; x++) {
						boolean wall = x == 0 || x == w - 1 || z == 0 || z == l - 1;
						int v = 0;
						if (y == 0) v = 1;
						else if (y == h - 1) v = 2;
						else if (wall) v = y % 5 == 3 ? 2 : 1;
						else if (x % 8 == 4 && z % 8 == 4) v = 3;
						data[x + z * w + y * w * l] = (byte) v;
					}
				}
			}
			root.putByteArray("BlockData", data);
			java.nio.file.Files.createDirectories(com.humanisedbaritone.build.Schematics.dir());
			net.minecraft.nbt.NbtIo.writeCompressed(root, com.humanisedbaritone.build.Schematics.dir().resolve("hall.schem"));
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		cmds(sp, "tp @a 205.5 -58 225.5 -90 5");
		botOn(ctx);
		jiggle(ctx, 80);
		report("fps-no-preview", true, "fps " + client(ctx, mc -> mc.getFps()));
		ctx.runOnClient(mc -> Commands.run("build hall 210 -60 205"));
		jiggle(ctx, 80);
		report("fps-preview", true, "fps " + client(ctx, mc -> mc.getFps()) + " blocks=" + client(ctx, mc -> Placement.current.size()) + " missing=" + client(ctx, mc -> Placement.current.count[Placement.MISSING]));
		shot(ctx, "perf-hall");
		ctx.runOnClient(mc -> Settings.PREVIEW_MAX.set(6000.0));
		jiggle(ctx, 80);
		report("fps-preview-6000", true, "fps " + client(ctx, mc -> mc.getFps()));
		ctx.runOnClient(mc -> Settings.PREVIEW_GHOST.set(false));
		jiggle(ctx, 80);
		report("fps-overlay-only", true, "fps " + client(ctx, mc -> mc.getFps()));
		ctx.runOnClient(mc -> {
			Settings.PREVIEW_GHOST.set(true);
			Settings.PREVIEW_MAX.set(1500.0);
		});
		cmds(sp, "tp @a 230.5 -45 225.5 90 20");
		jiggle(ctx, 60);
		shot(ctx, "perf-hall-inside");
		report("fps-inside", true, "fps " + client(ctx, mc -> mc.getFps()));
	}

	private static void jiggle(ClientGameTestContext ctx, int ticks) {
		for (int i = 0; i < ticks; i += 10) {
			ctx.getInput().setCursorPos(640 + (i / 10 % 2), 360);
			ctx.waitTicks(10);
		}
	}
}
