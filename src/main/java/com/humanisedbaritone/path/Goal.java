package com.humanisedbaritone.path;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Where the pathfinder should end up. Heuristics are in ticks and never overestimate. */
public interface Goal {
	boolean isAt(int x, int y, int z);

	/** Lower bound on the ticks still needed from this position. */
	double h(int x, int y, int z);

	/** A representative position for drawing and distance readouts. */
	BlockPos anchor();

	default String describe() {
		BlockPos a = anchor();
		return a.getX() + " " + a.getY() + " " + a.getZ();
	}

	static double xzCost(double dx, double dz) {
		double ax = Math.abs(dx), az = Math.abs(dz);
		double diag = Math.min(ax, az), straight = Math.max(ax, az) - diag;
		return (diag * Costs.SQRT2 + straight) * Costs.H_PER_BLOCK;
	}

	static double yCost(double dy) {
		return dy > 0 ? dy * Costs.JUMP : -dy * Costs.FALL[2] / 2;
	}

	/** An exact block position. */
	record At(int x, int y, int z) implements Goal {
		@Override public boolean isAt(int px, int py, int pz) {
			return px == x && py == y && pz == z;
		}

		@Override public double h(int px, int py, int pz) {
			return xzCost(x - px, z - pz) + yCost(y - py);
		}

		@Override public BlockPos anchor() {
			return new BlockPos(x, y, z);
		}
	}

	/** Any height at this column. */
	record Column(int x, int z) implements Goal {
		@Override public boolean isAt(int px, int py, int pz) {
			return px == x && pz == z;
		}

		@Override public double h(int px, int py, int pz) {
			return xzCost(x - px, z - pz);
		}

		@Override public BlockPos anchor() {
			return new BlockPos(x, 64, z);
		}

		@Override public String describe() {
			return x + " _ " + z;
		}
	}

	/** A height level. */
	record Level(int y) implements Goal {
		@Override public boolean isAt(int px, int py, int pz) {
			return py == y;
		}

		@Override public double h(int px, int py, int pz) {
			return yCost(y - py);
		}

		@Override public BlockPos anchor() {
			return new BlockPos(0, y, 0);
		}

		@Override public String describe() {
			return "y=" + y;
		}
	}

	/** Within {@code range} blocks (measured from the feet) of a position. */
	record Near(int x, int y, int z, double range) implements Goal {
		@Override public boolean isAt(int px, int py, int pz) {
			double dx = px - x, dy = py - y, dz = pz - z;
			return dx * dx + dy * dy + dz * dz <= range * range;
		}

		@Override public double h(int px, int py, int pz) {
			double dx = Math.max(0, Math.abs(px - x) - range), dz = Math.max(0, Math.abs(pz - z) - range);
			return xzCost(dx, dz) + yCost(Math.max(0, Math.abs(py - y) - range) * Math.signum(y - py));
		}

		@Override public BlockPos anchor() {
			return new BlockPos(x, y, z);
		}
	}

	/**
	 * Stand somewhere from where a block can be worked on: eyes within reach of its centre, but never inside the block
	 * itself. Used for mining, placing and opening things.
	 */
	record Reach(BlockPos target, double reach) implements Goal {
		@Override public boolean isAt(int px, int py, int pz) {
			if (px == target.getX() && pz == target.getZ() && (py == target.getY() || py + 1 == target.getY())) return false;
			double dx = px + 0.5 - (target.getX() + 0.5), dy = py + 1.62 - (target.getY() + 0.5), dz = pz + 0.5 - (target.getZ() + 0.5);
			return dx * dx + dy * dy + dz * dz <= reach * reach;
		}

		@Override public double h(int px, int py, int pz) {
			double dx = Math.max(0, Math.abs(px - target.getX()) - reach), dz = Math.max(0, Math.abs(pz - target.getZ()) - reach);
			return xzCost(dx, dz);
		}

		@Override public BlockPos anchor() {
			return target;
		}
	}

	/** The first goal that is reached wins. */
	record Any(List<Goal> goals) implements Goal {
		@Override public boolean isAt(int x, int y, int z) {
			for (Goal g : goals) if (g.isAt(x, y, z)) return true;
			return false;
		}

		@Override public double h(int x, int y, int z) {
			double best = Double.MAX_VALUE;
			for (Goal g : goals) best = Math.min(best, g.h(x, y, z));
			return best;
		}

		@Override public BlockPos anchor() {
			return goals.get(0).anchor();
		}

		@Override public String describe() {
			return goals.size() + " targets";
		}
	}

	/** Get at least {@code distance} blocks away from every threat. */
	record Away(List<Vec3> threats, double distance) implements Goal {
		private double nearest(int x, int y, int z) {
			double best = Double.MAX_VALUE;
			for (Vec3 t : threats) {
				double dx = x + 0.5 - t.x, dy = y - t.y, dz = z + 0.5 - t.z;
				best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
			return best;
		}

		@Override public boolean isAt(int x, int y, int z) {
			return nearest(x, y, z) >= distance;
		}

		@Override public double h(int x, int y, int z) {
			return Math.max(0, distance - nearest(x, y, z)) * Costs.H_PER_BLOCK;
		}

		@Override public BlockPos anchor() {
			return BlockPos.containing(threats.get(0));
		}

		@Override public String describe() {
			return "away from threats";
		}
	}
}
