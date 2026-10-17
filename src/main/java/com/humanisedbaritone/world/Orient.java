package com.humanisedbaritone.world;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Predicts what block state the game would produce for a placement, using the real {@code getStateForPlacement} with a
 * context that reports an arbitrary look direction. The placer uses it to choose where to click so that stairs, slabs,
 * logs and other directional blocks come out the way the plan wants them.
 */
public final class Orient {
	private static final Minecraft mc = Minecraft.getInstance();

	private Orient() {
	}

	/** Placement context whose "player" looks in the given direction. */
	private static final class Sim extends BlockPlaceContext {
		private final float yaw, pitch;

		Sim(ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean virtual) {
			super(mc.level, mc.player, InteractionHand.MAIN_HAND, stack, hit);
			this.yaw = yaw;
			this.pitch = pitch;
			if (virtual) replaceClicked = false; // the clicked cell is meant to hold a block that is not there yet: the new one goes beside it
		}

		@Override public Direction getHorizontalDirection() {
			return Direction.fromYRot(yaw);
		}

		@Override public float getRotation() {
			return yaw;
		}

		@Override public Direction getNearestLookingVerticalDirection() {
			return pitch < 0 ? Direction.UP : Direction.DOWN;
		}

		private Direction[] ordered() {
			float p = pitch * (float) (Math.PI / 180.0), y = -yaw * (float) (Math.PI / 180.0);
			float ps = Mth.sin(p), pc = Mth.cos(p), ys = Mth.sin(y), yc = Mth.cos(y);
			boolean xPos = ys > 0, yPos = ps < 0, zPos = yc > 0;
			float xYaw = xPos ? ys : -ys, yMag = yPos ? -ps : ps, zYaw = zPos ? yc : -yc;
			float xMag = xYaw * pc, zMag = zYaw * pc;
			Direction ax = xPos ? Direction.EAST : Direction.WEST, ay = yPos ? Direction.UP : Direction.DOWN, az = zPos ? Direction.SOUTH : Direction.NORTH;
			Direction a, b, c;
			if (xYaw > zYaw) {
				if (yMag > xMag) {
					a = ay;
					b = ax;
					c = az;
				} else if (zMag > yMag) {
					a = ax;
					b = az;
					c = ay;
				} else {
					a = ax;
					b = ay;
					c = az;
				}
			} else if (yMag > zMag) {
				a = ay;
				b = az;
				c = ax;
			} else if (xMag > yMag) {
				a = az;
				b = ax;
				c = ay;
			} else {
				a = az;
				b = ay;
				c = ax;
			}
			return new Direction[]{a, b, c, c.getOpposite(), b.getOpposite(), a.getOpposite()};
		}

		@Override public Direction getNearestLookingDirection() {
			return ordered()[0];
		}

		@Override public Direction[] getNearestLookingDirections() {
			Direction[] d = ordered();
			if (replacingClickedOnBlock()) return d;
			Direction clicked = getClickedFace().getOpposite();
			int i = 0;
			while (i < d.length && d[i] != clicked) i++;
			if (i > 0 && i < d.length) {
				System.arraycopy(d, 0, d, 1, i);
				d[0] = clicked;
			}
			return d;
		}
	}

	/** The state placing {@code stack} would create when clicking {@code hit} while looking along yaw/pitch, or null. */
	public static BlockState predict(ItemStack stack, BlockHitResult hit, float yaw, float pitch) {
		return predict(stack, hit, yaw, pitch, false);
	}

	/** @param virtual the clicked cell is empty now but is imagined to hold a block (the support a temporary block would give); the new block's cell is not checked for being free */
	public static BlockState predict(ItemStack stack, BlockHitResult hit, float yaw, float pitch, boolean virtual) {
		if (!(stack.getItem() instanceof BlockItem bi)) return null;
		try {
			Sim ctx = new Sim(stack, hit, yaw, pitch, virtual);
			if (!virtual && !ctx.canPlace()) return null;
			return bi.getBlock().getStateForPlacement(ctx);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The properties a player can influence by where they stand, look and click; everything else follows from the neighbours or the block's own logic. */
	private static final java.util.Set<Property<?>> CLICK_DEPENDENT = java.util.Set.of(BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING,
		BlockStateProperties.FACING_HOPPER, BlockStateProperties.HALF, BlockStateProperties.SLAB_TYPE, BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS,
		BlockStateProperties.ATTACH_FACE, BlockStateProperties.ORIENTATION, BlockStateProperties.DOOR_HINGE, BlockStateProperties.ROTATION_16,
		BlockStateProperties.RAIL_SHAPE_STRAIGHT, BlockStateProperties.RAIL_SHAPE);

	/** Properties that count how often the item was placed into the same cell: sea pickles, candles, turtle eggs. */
	private static final java.util.List<net.minecraft.world.level.block.state.properties.IntegerProperty> COUNTED = java.util.List.of(BlockStateProperties.PICKLES, BlockStateProperties.CANDLES, BlockStateProperties.EGGS);

	/** How many of the counted things (pickles ...) the state holds: 1 for every other block. */
	public static int countOf(BlockState s) {
		int n = 1;
		for (var p : COUNTED) if (s.hasProperty(p)) n = Math.max(n, s.getValue(p));
		return n;
	}

	/** True when {@code have} is the planned block with fewer of the counted things (pickles ...) than planned: another click with the item on the block adds one. */
	public static boolean shortCount(BlockState have, BlockState want) {
		if (have.getBlock() != want.getBlock()) return false;
		for (var p : COUNTED) if (want.hasProperty(p) && have.getValue(p) < want.getValue(p)) return true;
		return false;
	}

	/** True when the player's position, view or click decides something about this state (stairs, slabs, logs, wall blocks ...). */
	public static boolean sensitive(BlockState s) {
		for (Property<?> p : s.getProperties()) if (CLICK_DEPENDENT.contains(p)) return true;
		return false;
	}

	/**
	 * Does {@code have} count as the planned block: the same block, the same properties a click decides (facing, half, axis ...), and water
	 * in it where the plan has water (a source for a water block, a waterlogged block where it is waterlogged).
	 */
	public static boolean built(BlockState have, BlockState want) {
		if (have.getBlock() != want.getBlock()) return false;
		if (want.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return have.getFluidState().isSource();
		if (match(have, want) < 1 - 1e-9 || shortCount(have, want)) return false;
		var w = BlockStateProperties.WATERLOGGED;
		return !want.hasProperty(w) || !want.getValue(w) || have.getValue(w); // water in a block that is not meant to hold any is the sea's doing, not a mistake
	}

	/** True when {@code have} is the planned block in every way but the water in it, which a bucket can fix. */
	public static boolean waterMissing(BlockState have, BlockState want) {
		return have.getBlock() == want.getBlock() && !built(have, want) && match(have, want) >= 1 - 1e-9 && !shortCount(have, want) && want.hasProperty(BlockStateProperties.WATERLOGGED) && want.getValue(BlockStateProperties.WATERLOGGED);
	}

	/** How well {@code got} matches {@code want} in the properties a click decides: 0..1, where 1 is identical. A different block is 0. */
	public static double match(BlockState got, BlockState want) {
		if (got == null || got.getBlock() != want.getBlock()) return 0;
		int total = 0, same = 0;
		for (Property<?> p : want.getProperties()) {
			if (!CLICK_DEPENDENT.contains(p)) continue;
			total++;
			if (got.hasProperty(p) && got.getValue(p).equals(want.getValue(p))) same++;
		}
		return total == 0 ? 1 : (double) same / total;
	}
}
