package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.build.Placement;
import com.humanisedbaritone.build.Schematics;
import com.humanisedbaritone.task.Tasks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;

public class BuildGameTest extends BotTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			if (only("clear")) clearing(ctx, sp);
			if (only("fill")) filling(ctx, sp);
			if (only("build")) building(ctx, sp);
			if (only("orient")) orienting(ctx, sp);
			if (only("grassfill")) grassFill(ctx, sp);
			if (only("grassdig")) grassDig(ctx, sp);
			if (only("tower")) tower(ctx, sp);
			if (only("pond")) pond(ctx, sp);
			if (only("wet")) wet(ctx, sp);
			if (only("float")) floating(ctx, sp);
			if (only("trap")) trap(ctx, sp);
			finish();
		}
	}

	private static long count(net.minecraft.client.Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2, net.minecraft.world.level.block.Block b) {
		long n = 0;
		for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) if (mc.level.getBlockState(new BlockPos(x, y, z)).is(b)) n++;
		return n;
	}

	private void clearing(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, 100, 100, 160, 160);
		cmds(sp, "fill 120 -60 120 123 -58 123 stone", "tp @a 117.5 -60 121.5", "give @a iron_pickaxe");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> {
			Commands.run("sel pos1 120 -60 120");
			Commands.run("sel pos2 123 -58 123");
			Commands.run("sel clear");
		});
		until(ctx, "clear task", mc -> !Bot.busy(), 4000);
		long left = client(ctx, mc -> count(mc, 120, -60, 120, 123, -58, 123, Blocks.STONE));
		report("clear", left == 0, "stone left=" + left);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	private void filling(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmds(sp, "tp @a 128.5 -60 129.5", "give @a cobblestone 64");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> {
			Commands.run("sel pos1 130 -60 130");
			Commands.run("sel pos2 133 -60 133");
			Commands.run("sel fill cobblestone");
		});
		until(ctx, "fill task", mc -> !Bot.busy(), 4000);
		long n = client(ctx, mc -> count(mc, 130, -60, 130, 133, -60, 133, Blocks.COBBLESTONE));
		report("fill", n == 16, "cobblestone=" + n);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	private void building(ClientGameTestContext ctx, TestSingleplayerContext sp) throws RuntimeException {
		revive(ctx, sp);
		try {
			CompoundTag root = new CompoundTag();
			root.putInt("Version", 2);
			root.putShort("Width", (short) 3);
			root.putShort("Height", (short) 3);
			root.putShort("Length", (short) 3);
			CompoundTag pal = new CompoundTag();
			pal.putInt("minecraft:cobblestone", 0);
			pal.putInt("minecraft:oak_log[axis=y]", 1);
			pal.putInt("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", 2);
			pal.putInt("minecraft:air", 3);
			root.put("Palette", pal);
			byte[] data = new byte[27];
			for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
				int v = 3;
				boolean corner = (x == 0 || x == 2) && (z == 0 || z == 2);
				if (y == 0) v = 0;
				else if (corner) v = 1;
				else if (y == 1 && x == 1 && z == 1) v = 2;
				data[x + z * 3 + y * 9] = (byte) v;
			}
			root.putByteArray("BlockData", data);
			java.nio.file.Files.createDirectories(Schematics.dir());
			NbtIo.writeCompressed(root, Schematics.dir().resolve("hbtest.schem"));
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		cmds(sp, "tp @a 138.5 -60 141.5", "give @a cobblestone 64", "give @a oak_log 32", "give @a oak_stairs 8");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> {
			Commands.run("build hbtest 140 -60 140"); // shows the preview ...
			report("preview-waits", Tasks.current() == null && Placement.current != null && !Placement.current.confirmed, "nothing is built before the confirm");
			Commands.run("build confirm"); // ... and this is the confirm button
		});
		until(ctx, "build task", mc -> !Bot.busy(), 6000);
		long cobble = client(ctx, mc -> count(mc, 140, -60, 140, 142, -60, 142, Blocks.COBBLESTONE));
		long logs = client(ctx, mc -> count(mc, 140, -59, 140, 142, -58, 142, Blocks.OAK_LOG));
		long stairs = client(ctx, mc -> count(mc, 141, -59, 141, 141, -59, 141, Blocks.OAK_STAIRS));
		report("build", cobble == 9 && logs == 8 && stairs == 1, "cobble=" + cobble + " logs=" + logs + " stairs=" + stairs);
		shot(ctx, "build-result");
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/** Directional blocks must come out the way the plan wants them: stairs in all four directions, a top slab, a log on its side. */
	private void orienting(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int reps = Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "1")); // the slab used to come out wrong about one run in four
		try {
			CompoundTag root = new CompoundTag();
			root.putInt("Version", 2);
			root.putShort("Width", (short) 5);
			root.putShort("Height", (short) 2);
			root.putShort("Length", (short) 3);
			CompoundTag pal = new CompoundTag();
			pal.putInt("minecraft:cobblestone", 0);
			pal.putInt("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", 1);
			pal.putInt("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]", 2);
			pal.putInt("minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]", 3);
			pal.putInt("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", 4);
			pal.putInt("minecraft:oak_slab[type=top,waterlogged=false]", 5);
			pal.putInt("minecraft:oak_log[axis=z]", 6);
			pal.putInt("minecraft:air", 7);
			root.put("Palette", pal);
			byte[] data = new byte[5 * 2 * 3];
			for (int i = 0; i < data.length; i++) data[i] = 7;
			for (int z = 0; z < 3; z++) for (int x = 0; x < 5; x++) data[x + z * 5] = 0; // the floor
			int y1 = 5 * 3;
			data[y1 + 0 + 0 * 5] = 1; // stairs facing east
			data[y1 + 2 + 0 * 5] = 2; // south
			data[y1 + 4 + 0 * 5] = 3; // west
			data[y1 + 0 + 2 * 5] = 4; // north
			data[y1 + 1 + 2 * 5] = 0; // neighbours the slab and the log can be placed against
			data[y1 + 3 + 2 * 5] = 0;
			data[y1 + 4 + 1 * 5] = 0;
			data[y1 + 2 + 2 * 5] = 5; // top slab
			data[y1 + 4 + 2 * 5] = 6; // log lying along z
			root.putByteArray("BlockData", data);
			java.nio.file.Files.createDirectories(Schematics.dir());
			NbtIo.writeCompressed(root, Schematics.dir().resolve("hbtest2.schem"));
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		load(ctx, sp, 150, 130, 175, 150);
		for (int r = 0; r < reps; r++) {
			revive(ctx, sp);
			cmds(sp, "kill @e[type=item]", "fill 158 -60 138 166 -58 144 air", "tp @a 157.5 -60 141.5", "give @a cobblestone 64", "give @a oak_stairs 16", "give @a oak_slab 8", "give @a oak_log 8");
			ctx.waitTicks(20);
			botOn(ctx);
			ctx.runOnClient(mc -> {
				Commands.run("build hbtest2 160 -60 140");
				Commands.run("build confirm");
			});
			until(ctx, "orientation build", mc -> !Bot.busy(), 8000);
			String got = client(ctx, mc -> {
				StringBuilder sb = new StringBuilder();
				int[][] at = {{160, 140}, {162, 140}, {164, 140}, {160, 142}};
				for (int[] a : at) {
					var st = mc.level.getBlockState(new BlockPos(a[0], -59, a[1]));
					sb.append(st.is(Blocks.OAK_STAIRS) ? st.getValue(net.minecraft.world.level.block.StairBlock.FACING).getName() + "/" + st.getValue(net.minecraft.world.level.block.StairBlock.HALF).getSerializedName() : "?").append(' ');
				}
				var slab = mc.level.getBlockState(new BlockPos(162, -59, 142));
				sb.append(slab.is(Blocks.OAK_SLAB) ? slab.getValue(net.minecraft.world.level.block.SlabBlock.TYPE).getSerializedName() : "?").append(' ');
				var log = mc.level.getBlockState(new BlockPos(164, -59, 142));
				sb.append(log.is(Blocks.OAK_LOG) ? log.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS).getSerializedName() : "?");
				return sb.toString();
			});
			report("orient", got.equals("east/bottom south/bottom west/bottom north/bottom top z"), got);
			if (r == 0) shot(ctx, "build-orient");
			ctx.runOnClient(mc -> Commands.run("stop"));
		}
	}

	/** Builds the placement and waits until the bot is done; returns false on a timeout. */
	private boolean buildIt(ClientGameTestContext ctx, String file, int x, int y, int z, int ticks) {
		ctx.runOnClient(mc -> {
			Commands.run("build " + file + " " + x + " " + y + " " + z);
			Commands.run("build confirm");
		});
		return until(ctx, "build " + file, mc -> !Bot.busy(), ticks);
	}

	private static long blocks(net.minecraft.client.Minecraft mc, int x1, int y1, int z1, int x2, int y2, int z2, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> p) {
		long n = 0;
		for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) if (p.test(mc.level.getBlockState(new BlockPos(x, y, z)))) n++;
		return n;
	}

	/**
	 * A column of glass nine blocks high: the top can only be reached by climbing, so the bot pillars up with cobblestone. That scaffolding
	 * shows as temporary (purple) while it stands and has to be gone when the build is done.
	 */
	private void tower(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		writeSchem("hbtower", 1, 9, 1, c -> "minecraft:glass");
		load(ctx, sp, 230, 100, 260, 130);
		revive(ctx, sp);
		cmds(sp, "gamemode creative @a", "tp @a 238.5 -60 105.5", "give @a minecraft:cobblestone 64", "give @a minecraft:iron_pickaxe");
		ctx.waitTicks(20);
		botOn(ctx);
		boolean seen = false;
		ctx.runOnClient(mc -> {
			Commands.run("build hbtower 240 -60 110");
			Commands.run("build confirm");
		});
		int peak = 0;
		for (int i = 0; i < 160 && Bot.busy(); i++) { // while it builds: temporary blocks are counted as extra by the placement
			ctx.waitTicks(20);
			peak = Math.max(peak, client(ctx, mc -> Placement.current == null ? 0 : Placement.current.count[Placement.EXTRA]));
		}
		until(ctx, "tower", mc -> !Bot.busy(), 4000);
		ctx.waitTicks(40);
		long glass = client(ctx, mc -> blocks(mc, 240, -60, 110, 240, -52, 110, st -> st.is(Blocks.GLASS)));
		long cobble = client(ctx, mc -> blocks(mc, 230, -61, 100, 250, -45, 120, st -> st.is(Blocks.COBBLESTONE)));
		report("tower", glass == 9 && cobble == 0, "glass=" + glass + " cobblestone left=" + cobble + " (most temporary blocks marked at once: " + peak + ")");
		report("tower-marked", peak > 0, "the scaffolding must show up as extra blocks while it stands");
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/**
	 * Blocks with nothing to click against: three top slabs in an L on the ground (a top slab comes from a click on the upper half of a neighbour's side, and there is
	 * no neighbour yet) and a top-half trapdoor two blocks up in the air. The bot puts temporary blocks where a click on them gives exactly the planned state, and takes them away again.
	 */
	private void floating(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		String top = "minecraft:deepslate_brick_slab[type=top,waterlogged=false]";
		writeSchem("hbfloat", 7, 3, 3, c -> {
			if (c[1] == 0 && (c[0] == 0 && c[2] == 0 || c[0] == 1 && c[2] == 0 || c[0] == 0 && c[2] == 1)) return top;
			if (c[0] == 6 && c[2] == 1 && c[1] == 0) return "minecraft:stone";
			if (c[0] == 6 && c[2] == 1 && c[1] == 2) return "minecraft:iron_trapdoor[half=top,facing=west,open=false,powered=false,waterlogged=false]";
			return null;
		});
		load(ctx, sp, 260, 100, 300, 130);
		revive(ctx, sp);
		cmds(sp, "gamemode creative @a", "tp @a 263.5 -60 105.5", "give @a minecraft:cobblestone 64", "give @a minecraft:iron_pickaxe");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> {
			Commands.run("build hbfloat 265 -60 110");
			Commands.run("build confirm");
		});
		until(ctx, "floating blocks", mc -> !Bot.busy(), 6000);
		ctx.waitTicks(40);
		long slabs = client(ctx, mc -> blocks(mc, 265, -60, 110, 266, -60, 111, st -> st.is(Blocks.DEEPSLATE_BRICK_SLAB) && st.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.TOP));
		var trap = client(ctx, mc -> mc.level.getBlockState(new BlockPos(271, -58, 111)));
		long cobble = client(ctx, mc -> blocks(mc, 255, -61, 100, 285, -45, 125, st -> st.is(Blocks.COBBLESTONE)));
		boolean trapOk = trap.is(Blocks.IRON_TRAPDOOR) && trap.getValue(net.minecraft.world.level.block.TrapDoorBlock.HALF) == net.minecraft.world.level.block.state.properties.Half.TOP && trap.getValue(net.minecraft.world.level.block.TrapDoorBlock.FACING) == net.minecraft.core.Direction.WEST;
		report("float", slabs == 3 && trapOk && cobble == 0, "top slabs=" + slabs + "/3 trapdoor=" + trap + " cobblestone left=" + cobble);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/**
	 * The middle of the pickle farm in small: a trapdoor (top half, facing west) in the cross of eight rails over eight hoppers, an empty pit under it, and a source of water
	 * on top of it between four glass blocks. The trapdoor can only get its half from a click on a temporary block beside it (the rails are too thin), and the water may
	 * only be poured once the trapdoor seals the pit: a top-half trapdoor holds the water back, though it is a block that could be waterlogged.
	 */
	private void trap(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		String hop = "minecraft:hopper[enabled=true,facing=down]";
		writeSchem("hbtrap", 7, 4, 7, c -> {
			boolean armX = c[2] == 3 && c[0] != 3 && c[0] >= 1 && c[0] <= 5, armZ = c[0] == 3 && c[2] != 3 && c[2] >= 1 && c[2] <= 5, centre = c[0] == 3 && c[2] == 3;
			return switch (c[1]) {
				case 0 -> armX || armZ || centre ? "minecraft:stone" : null;
				case 1 -> armX || armZ ? hop : null;
				case 2 -> armX ? "minecraft:powered_rail[powered=false,shape=east_west,waterlogged=false]" : armZ ? "minecraft:powered_rail[powered=false,shape=north_south,waterlogged=false]"
					: centre ? "minecraft:iron_trapdoor[half=top,facing=west,open=false,powered=false,waterlogged=false]" : null;
				default -> centre ? "minecraft:water[level=0]" : Math.abs(c[0] - 3) + Math.abs(c[2] - 3) == 1 ? "minecraft:glass" : null;
			};
		});
		load(ctx, sp, 350, 100, 380, 130);
		revive(ctx, sp);
		cmds(sp, "gamemode creative @a", "tp @a 356.5 -60 108.5", "give @a minecraft:cobblestone 64");
		ctx.waitTicks(20);
		ctx.runOnClient(mc -> mc.player.getAbilities().flying = true); // a creative player who flies: the bot has to come down, its plans assume gravity
		botOn(ctx);
		boolean done = buildIt(ctx, "hbtrap", 360, -60, 110, 9000);
		ctx.waitTicks(100); // let the water settle
		BlockPos origin = new BlockPos(360, -60, 110);
		var bad = client(ctx, mc -> {
			try {
				return PickleGameTest.compare(Schematics.load("hbtrap"), origin);
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		});
		long water = client(ctx, mc -> blocks(mc, 355, -62, 105, 371, -52, 121, st -> !st.getFluidState().isEmpty()));
		long cobble = client(ctx, mc -> blocks(mc, 355, -62, 105, 371, -52, 121, st -> st.is(Blocks.COBBLESTONE)));
		boolean flying = client(ctx, mc -> mc.player.getAbilities().flying);
		report("trap", done && bad.isEmpty() && water == 1 && cobble == 0 && !flying, "mismatches=" + bad + " water cells=" + water + "/1 cobblestone left=" + cobble + " flying=" + flying);
		shot(ctx, "build-trap");
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/** Water in an enclosed pond is poured once the walls are up, as sources, and not a drop leaves it. */
	private void pond(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		writeSchem("hbpond", 5, 3, 5, c -> {
			boolean ring = c[0] == 0 || c[0] == 4 || c[2] == 0 || c[2] == 4;
			if (c[1] == 0) return "minecraft:stone_bricks";
			if (c[1] == 1) return ring ? "minecraft:stone_bricks" : "minecraft:water[level=0]";
			return null;
		});
		load(ctx, sp, 270, 100, 300, 130);
		revive(ctx, sp);
		cmds(sp, "gamemode creative @a", "tp @a 278.5 -60 105.5");
		ctx.waitTicks(20);
		botOn(ctx);
		boolean done = buildIt(ctx, "hbpond", 280, -60, 110, 5000);
		ctx.waitTicks(100); // let the water settle
		long sources = client(ctx, mc -> blocks(mc, 281, -59, 111, 283, -59, 113, st -> st.getFluidState().isSource()));
		long outside = client(ctx, mc -> blocks(mc, 276, -61, 106, 288, -57, 118, st -> !st.getFluidState().isEmpty()) - blocks(mc, 281, -59, 111, 283, -59, 113, st -> !st.getFluidState().isEmpty()));
		report("pond", done && sources == 9 && outside == 0, "sources=" + sources + "/9, water outside the pond=" + outside);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/** Waterlogged blocks: a slab (placed, then the bucket) and a chest (water first, the chest into it), in a sealed pond. */
	private void wet(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		writeSchem("hbwet", 5, 3, 5, c -> {
			boolean ring = c[0] == 0 || c[0] == 4 || c[2] == 0 || c[2] == 4;
			if (c[1] == 0) return "minecraft:stone_bricks";
			if (c[1] == 1 && ring) return "minecraft:stone_bricks";
			if (c[1] == 1 && c[0] == 2 && c[2] == 2) return "minecraft:oak_slab[type=bottom,waterlogged=true]";
			if (c[1] == 1 && c[0] == 1 && c[2] == 2) return "minecraft:chest[facing=east,type=single,waterlogged=true]";
			if (c[1] == 1) return "minecraft:water[level=0]";
			return null;
		});
		load(ctx, sp, 310, 100, 340, 130);
		revive(ctx, sp);
		cmds(sp, "gamemode creative @a", "tp @a 318.5 -60 105.5");
		ctx.waitTicks(20);
		botOn(ctx);
		boolean done = buildIt(ctx, "hbwet", 320, -60, 110, 6000);
		ctx.waitTicks(60);
		var slab = client(ctx, mc -> mc.level.getBlockState(new BlockPos(322, -59, 112)));
		var chest = client(ctx, mc -> mc.level.getBlockState(new BlockPos(321, -59, 112)));
		boolean ok = slab.is(Blocks.OAK_SLAB) && slab.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) && chest.is(Blocks.CHEST) && chest.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
			&& chest.getValue(net.minecraft.world.level.block.ChestBlock.FACING) == net.minecraft.core.Direction.EAST;
		report("wet", done && ok, "slab=" + slab + " chest=" + chest);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/** Natural ground is covered in grass: placing blocks into it must work. */
	private void grassFill(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, 170, 130, 200, 160);
		cmds(sp, "fill 180 -60 140 183 -60 143 short_grass", "tp @a 178.5 -60 141.5", "give @a cobblestone 64");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> {
			Commands.run("sel pos1 180 -60 140");
			Commands.run("sel pos2 183 -60 143");
			Commands.run("sel fill cobblestone");
		});
		until(ctx, "fill over grass", mc -> !Bot.busy(), 5000);
		long n = client(ctx, mc -> count(mc, 180, -60, 140, 183, -60, 143, Blocks.COBBLESTONE));
		report("grass-fill", n == 16, "cobblestone=" + n);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/** Digging down while standing in tall grass: the plant is in the line of sight to the floor. */
	private void grassDig(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, 190, 130, 220, 160);
		cmds(sp, "fill 198 -60 138 204 -60 144 short_grass", "tp @a 201.5 -60 141.5", "give @a iron_pickaxe");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("goto 201 -62 141"));
		until(ctx, "dig down through grass", mc -> !Bot.busy(), 1500);
		double[] pos = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		report("grass-dig", Math.abs(pos[1] - -62) < 0.6 && Math.hypot(pos[0] - 201.5, pos[2] - 141.5) < 1.5, String.format("pos=%.2f %.2f %.2f", pos[0], pos[1], pos[2]));
		ctx.runOnClient(mc -> Commands.run("stop"));
	}
}
