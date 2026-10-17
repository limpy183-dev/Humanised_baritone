package com.humanisedbaritone.task;

import com.humanisedbaritone.build.Placement;
import com.humanisedbaritone.build.Scaffold;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.WorkTask.Job;
import com.humanisedbaritone.world.Blk;
import com.humanisedbaritone.world.Orient;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Builds the job lists for clearing, filling and building. */
public final class Work {
	private static final Minecraft mc = Minecraft.getInstance();

	private Work() {
	}

	private static final Comparator<Job> TOP_DOWN = Comparator.comparingInt((Job j) -> -j.pos().getY());
	/** Layer by layer; inside a layer what has to come out first, then the blocks, and water last: it is poured once the pond is shaped. */
	private static final Comparator<Job> BOTTOM_UP = Comparator.comparingInt((Job j) -> j.pos().getY()).thenComparingInt(j -> j.isBreak() ? 0 : j.want().getBlock() instanceof LiquidBlock ? 2 : 1);

	static boolean protectedBlock(BlockState s) {
		if ((Blk.f(s) & Blk.UNBREAKABLE) != 0) return true;
		var k = BuiltInRegistries.BLOCK.getKey(s.getBlock());
		return k != null && Settings.AVOID_BREAK.has(k.toString());
	}

	/** Break everything inside the box, from the top layer down. */
	public static WorkTask clear(BlockPos a, BlockPos b) {
		List<Job> jobs = new ArrayList<>();
		forBox(a, b, p -> {
			BlockState s = mc.level.getBlockState(p);
			if (!s.isAir() && (Blk.f(s) & Blk.LIQUID) == 0 && !protectedBlock(s)) jobs.add(new Job(p, null));
		});
		return new WorkTask("Clearing " + jobs.size() + " blocks", "CLEAR", jobs, TOP_DOWN);
	}

	/** Fill the box (or only its outer shell) with one block, from the bottom layer up. */
	public static WorkTask fill(BlockPos a, BlockPos b, Block block, boolean shellOnly) {
		List<Job> jobs = new ArrayList<>();
		BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		BlockState want = block.defaultBlockState();
		forBox(a, b, p -> {
			if (shellOnly && p.getX() > lo.getX() && p.getX() < hi.getX() && p.getY() > lo.getY() && p.getY() < hi.getY() && p.getZ() > lo.getZ() && p.getZ() < hi.getZ()) return;
			BlockState s = mc.level.getBlockState(p);
			if (s.getBlock() == block) return;
			if (!s.isAir() && (Blk.f(s) & (Blk.REPLACEABLE | Blk.LIQUID)) == 0) {
				if (!Settings.BUILD_CLEAR.on() || protectedBlock(s)) return;
				jobs.add(new Job(p, null));
			}
			jobs.add(new Job(p, want));
		});
		return new WorkTask("Filling " + jobs.size() + " blocks with " + block.getName().getString(), "BUILD", jobs, BOTTOM_UP);
	}

	/**
	 * Builds the placement on show: breaks what is wrong or does not belong, places what is missing, then looks at the world again
	 * until nothing is left that can be done, and finally takes the scaffolding away.
	 */
	public static WorkTask build(Placement p) {
		Comparator<Job> base = switch (Settings.BUILD_ORDER.get()) {
			case TOP_DOWN -> TOP_DOWN;
			case NEAREST -> (x, y) -> 0;
			default -> BOTTOM_UP;
		};
		Comparator<Job> order = Comparator.comparingInt((Job j) -> !j.isBreak() && WorkTask.looksUp(j.want()) ? 0 : 1).thenComparing(base); // a piston that is to face down needs a player who looks up at it: first, while the space below it is free
		Set<BlockPos> wet = new HashSet<>(); // where the schematic has water, in the open or inside a block
		for (int i = 0; i < p.size(); i++) {
			BlockState w = p.want(i);
			if (w.getBlock() instanceof LiquidBlock || w.hasProperty(BlockStateProperties.WATERLOGGED) && w.getValue(BlockStateProperties.WATERLOGGED)) wet.add(p.pos(i));
		}
		return new WorkTask("Building " + p.name, "BUILD", jobs(p), order, () -> jobs(p), true).leaking(at -> leaks(at, wet));
	}

	/**
	 * Would water poured at {@code start} run out of the cells where the plan has water? It does what the game does: it falls and spreads
	 * sideways into every cell that can hold it unless the faces of the two cells seal against each other (a top slab or a closed top-half
	 * trapdoor under it holds it back, a chest or a bottom slab beside it does not), and never goes up. Looks at the world as it is now.
	 */
	static boolean leaks(BlockPos start, Set<BlockPos> wet) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		Direction[] flow = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
		while (!queue.isEmpty()) {
			if (seen.size() > 600) return true;
			BlockPos c = queue.poll();
			BlockState from = mc.level.getBlockState(c);
			for (Direction d : flow) {
				BlockPos n = c.relative(d);
				if (!seen.add(n)) continue;
				BlockState s = mc.level.getBlockState(n);
				if (!passes(d, c, from, n, s) || s.getFluidState().isEmpty() && !holdsWater(n, s)) continue; // a block holds it back
				if (!wet.contains(n)) return true;
				queue.add(n);
			}
		}
		return false;
	}

	/** FlowingFluid.canPassThroughWall: water goes from one cell into the next unless a face of one of them covers the shared side. */
	private static boolean passes(Direction d, BlockPos from, BlockState fs, BlockPos to, BlockState ts) {
		VoxelShape target = ts.getCollisionShape(mc.level, to);
		if (target == Shapes.block()) return false;
		VoxelShape source = fs.getCollisionShape(mc.level, from);
		if (source == Shapes.block()) return false;
		if (source == Shapes.empty() && target == Shapes.empty()) return true;
		return !Shapes.mergedFaceOccludes(source, target, d);
	}

	/** FlowingFluid.canHoldFluid: a block that can be waterlogged takes it, so does anything that does not stop movement (not doors, signs, ladders ...). */
	private static boolean holdsWater(BlockPos p, BlockState s) {
		if (s.getBlock() instanceof LiquidBlockContainer c) return c.canPlaceLiquid(null, mc.level, p, s, Fluids.WATER);
		Block b = s.getBlock();
		return !s.blocksMotion() && !(b instanceof DoorBlock) && !s.is(BlockTags.SIGNS) && b != Blocks.LADDER && b != Blocks.SUGAR_CANE && b != Blocks.BUBBLE_COLUMN
			&& b != Blocks.NETHER_PORTAL && b != Blocks.END_PORTAL && b != Blocks.END_GATEWAY && b != Blocks.STRUCTURE_VOID;
	}

	/** What it takes to make the world look like the placement right now. */
	private static List<Job> jobs(Placement p) {
		p.refresh();
		List<Job> jobs = new ArrayList<>();
		boolean clear = Settings.BUILD_CLEAR.on();
		for (int i = 0; i < p.size(); i++) {
			BlockPos pos = p.pos(i);
			BlockState want = p.want(i), have = mc.level.getBlockState(pos);
			switch (p.kind(i)) {
				case Placement.MISSING -> jobs.add(new Job(pos, want));
				case Placement.WRONG, Placement.STATE -> {
					if (Orient.shortCount(have, want) || Orient.waterMissing(have, want) && (Blk.f(have) & Blk.INTERACT) == 0) jobs.add(new Job(pos, want)); // one more pickle goes on top of the ones that stand; or only the water is missing: a bucket does it (a chest would open instead)
					else if (clear && !protectedBlock(have)) {
						jobs.add(new Job(pos, null));
						jobs.add(new Job(pos, want));
					}
				}
				default -> {
				}
			}
		}
		if (clear) for (BlockPos b : p.extras()) if (!protectedBlock(mc.level.getBlockState(b))) jobs.add(new Job(b, null));
		if (jobs.isEmpty() && Settings.BUILD_TIDY.on()) for (BlockPos b : Scaffold.standing()) if (!p.inside(b)) jobs.add(new Job(b, null));
		return jobs;
	}

	private static void forBox(BlockPos a, BlockPos b, java.util.function.Consumer<BlockPos> f) {
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		for (int y = y1; y >= y0; y--) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) f.accept(new BlockPos(x, y, z));
	}
}
