package com.humanisedbaritone.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Cached classification of block states. Everything the pathfinder asks about a block ("can I stand on it?", "can I
 * walk through it?", "is it lava?") is answered from a flag word computed once per state from its collision shape,
 * fluid, tags and hardness. Thread safe: racing writers compute the same value.
 */
public final class Blk {
	public static final int INIT = 1, AIR = 1 << 1, PASS = 1 << 2, FULL = 1 << 3, FLOOR = 1 << 4, WATER = 1 << 5, LAVA = 1 << 6, CLIMB = 1 << 7,
		FALLING = 1 << 8, DOOR = 1 << 9, GATE = 1 << 10, TRAPDOOR = 1 << 11, OPEN = 1 << 12, LIQUID = 1 << 13, UNBREAKABLE = 1 << 14, FIRE = 1 << 15,
		SLOW = 1 << 16, TALL = 1 << 17, INTERACT = 1 << 18, REPLACEABLE = 1 << 19, IRON = 1 << 20, SOURCE = 1 << 21,
		/** A floor lower than a full block (soul sand, mud, chests, beds): the player's feet sit inside its cell, so nothing can be placed beside it from its edge. */
		SUNK = 1 << 22,
		/** Standing on it, a jump cannot get onto a block one higher (honey, beds): no climbing, pillaring or running jumps from here. */
		NO_JUMP = 1 << 23,
		/** Ice: the player keeps sliding long after letting go of the keys. */
		SLIPPERY = 1 << 24,
		/** No collision box at all (air, grass, flowers, water): nothing to brush against. */
		CLEAR = 1 << 25;

	private static volatile int[] cache;

	private Blk() {
	}

	public static int f(BlockState s) {
		int[] c = cache;
		int id = Block.getId(s);
		if (c == null || id >= c.length) {
			c = new int[Math.max(Block.BLOCK_STATE_REGISTRY.size(), id + 1) + 64];
			cache = c;
		}
		int v = c[id];
		if (v == 0) c[id] = v = compute(s);
		return v;
	}

	public static boolean has(BlockState s, int flag) {
		return (f(s) & flag) != 0;
	}

	private static VoxelShape collision(BlockState s) {
		try {
			return s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (Throwable t) {
			return net.minecraft.world.phys.shapes.Shapes.block();
		}
	}

	private static int compute(BlockState s) {
		int f = INIT;
		Block b = s.getBlock();
		if (s.isAir()) return f | AIR | PASS | REPLACEABLE | CLEAR;
		FluidState fl = s.getFluidState();
		if (!fl.isEmpty()) {
			f |= LIQUID;
			if (fl.is(FluidTags.WATER)) f |= WATER;
			else if (fl.is(FluidTags.LAVA)) f |= LAVA;
			if (fl.isSource()) f |= SOURCE;
		}
		if (s.is(BlockTags.FIRE) || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE || b == Blocks.MAGMA_BLOCK) f |= FIRE;
		if (b instanceof FallingBlock) f |= FALLING;
		if (s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) f |= UNBREAKABLE;
		if (b.getSpeedFactor() < 1 || b == Blocks.SLIME_BLOCK) f |= SLOW; // slime brakes anyone walking on it without sneaking
		if (b.getJumpFactor() < 1) f |= NO_JUMP;
		if (b.getFriction() > 0.9f) f |= SLIPPERY;
		if (s.canBeReplaced() || b == Blocks.SNOW) f |= REPLACEABLE;

		boolean door = s.is(BlockTags.DOORS);
		boolean gate = s.is(BlockTags.FENCE_GATES);
		boolean trap = s.is(BlockTags.TRAPDOORS);
		boolean open = (door || gate || trap) && s.hasProperty(BlockStateProperties.OPEN) && s.getValue(BlockStateProperties.OPEN);
		if (door && !s.is(BlockTags.WOODEN_DOORS)) f |= IRON;
		if (trap && !s.is(BlockTags.WOODEN_TRAPDOORS)) f |= IRON;
		if (door) f |= DOOR;
		if (gate) f |= GATE;
		if (trap) f |= TRAPDOOR;
		if (open) f |= OPEN;
		if (door || gate || trap || s.is(BlockTags.BUTTONS) || s.is(BlockTags.BEDS) || s.hasBlockEntity() || b == Blocks.LEVER || b == Blocks.CRAFTING_TABLE
			|| b == Blocks.ANVIL || b == Blocks.NOTE_BLOCK || b == Blocks.LOOM || b == Blocks.STONECUTTER || b == Blocks.SMITHING_TABLE || b == Blocks.GRINDSTONE
			|| b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.FLETCHING_TABLE || b == Blocks.COMPOSTER || b == Blocks.BELL || b == Blocks.REPEATER
			|| b == Blocks.COMPARATOR || s.is(BlockTags.CAULDRONS) || b == Blocks.DAYLIGHT_DETECTOR || b == Blocks.CAKE) {
			f |= INTERACT;
		}

		boolean climb = s.is(BlockTags.CLIMBABLE);
		if (climb) f |= CLIMB;

		VoxelShape col = collision(s);
		if (col.isEmpty()) return f | PASS | CLEAR;
		AABB bb = col.bounds();
		double top = bb.maxY;
		if (top > 1.0001) f |= TALL;
		boolean full = Block.isShapeFullBlock(col);
		if (full) f |= FULL;
		if ((open && (door || trap)) || climb) return f | PASS; // thin leaf / ladder wall: the cell can be occupied
		if (top <= 0.5) return f | PASS | (top >= 0.5 ? FLOOR : 0); // carpets, low snow, bottom trapdoors: walk over them
		double w = bb.maxX - bb.minX, d = bb.maxZ - bb.minZ;
		boolean bowl = b instanceof net.minecraft.world.level.block.HopperBlock || b instanceof net.minecraft.world.level.block.AbstractCauldronBlock || b == Blocks.COMPOSTER; // a rim around a lower floor: the feet end up in it, not on top
		if (top <= 1.0001 && w >= 0.74 && d >= 0.74 && !bowl) {
			f |= FLOOR;
			if (top < 0.999) f |= SUNK | (top < 0.85 ? NO_JUMP : 0); // a jump peaks 1.25 above the feet
		}
		return f;
	}

	// ------------------------------------------------------------ convenience

	public static boolean isAir(BlockState s) {
		return has(s, AIR);
	}

	public static boolean isWater(BlockState s) {
		return has(s, WATER);
	}

	public static boolean isLava(BlockState s) {
		return has(s, LAVA);
	}

	public static boolean isLiquid(BlockState s) {
		return has(s, LIQUID);
	}

	/** A door, gate or trapdoor that a hand can open (not iron). */
	public static boolean openable(BlockState s) {
		int f = f(s);
		return (f & (DOOR | GATE | TRAPDOOR)) != 0 && (f & IRON) == 0;
	}

	public static boolean isOpen(BlockState s) {
		return has(s, OPEN);
	}

	/** Height of the top of the collision shape inside the block (0..1+). */
	public static double top(BlockState s) {
		VoxelShape c = collision(s);
		return c.isEmpty() ? 0 : c.max(Direction.Axis.Y);
	}
}
