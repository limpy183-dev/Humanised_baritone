package com.humanisedbaritone.path;

import com.humanisedbaritone.path.Moves.Kind;
import com.humanisedbaritone.world.Blk;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Straightens a planned route over open ground. The planner works on a grid, so across a field it returns a staircase of
 * straight and diagonal moves in some arbitrary order, while a person simply runs the straight line. Every stretch of
 * flat, plain ground (full floor, nothing to touch, no water, lava or hostile mob nearby) is merged into one
 * {@link Kind#RUN} that ends at the farthest waypoint still reachable in a straight line. Anywhere that is not plain
 * (ledges, walls, jumps, bridges, hazards) the original moves stay, and the executor keeps to them closely.
 */
public final class Smooth {
	/** The strip this wide each side of a run must be plain: the body (0.3) plus a margin. */
	private static final double CORE = 0.65, MID = 1.15, WIDE = 1.65;
	/** How far the body may stray from the line when the strip is that wide (it keeps a quarter block from anything not plain). */
	private static final double BODY = 0.3, KEEP = 0.25;
	private static final double SAMPLE = 0.4;
	private static final int MAX_RUN = 48, MAX_MISSES = 3;

	private final Ctx c;
	/** Keep clear of hostile mobs. Only when planning: a mob that wanders near later is the fighting code's business, it is no reason to give up a run that is still plain ground. */
	private final boolean mobs;
	private final Long2ByteOpenHashMap plainMemo = new Long2ByteOpenHashMap(), calmMemo = new Long2ByteOpenHashMap();

	private Smooth(Ctx c, boolean mobs) {
		this.c = c;
		this.mobs = mobs;
	}

	/** Merges the open stretches of {@code p} into straight runs. Runs on the planner thread, with the planner's own context. */
	public static Path apply(Ctx c, Path p) {
		List<Path.Step> in = p.steps;
		int n = in.size();
		if (n < 3) return p;
		Smooth s = new Smooth(c, true);
		List<Path.Step> out = new ArrayList<>(n);
		int i = 0;
		while (i < n) {
			int j = s.groupEnd(in, i);
			if (j <= i) { // a lone step, or not open ground: nothing to merge
				out.add(in.get(i++));
				continue;
			}
			int y = in.get(i).sy();
			int k = i;
			while (k <= j) {
				int best = k + 1;
				for (int m = k + 2, misses = 0; m <= j + 1 && m - k <= MAX_RUN; m++) {
					if (s.slack(y, wx(in, i, k), wz(in, i, k), wx(in, i, m), wz(in, i, m)) >= 0) {
						best = m;
						misses = 0;
					} else if (++misses > MAX_MISSES) break;
				}
				if (best - k >= 2) {
					double cost = 0;
					for (int t = k; t < best; t++) cost += in.get(t).cost();
					Path.Step a = in.get(k), b = in.get(best - 1);
					out.add(new Path.Step(Kind.RUN, a.sx(), a.sy(), a.sz(), b.dx(), b.dy(), b.dz(), cost));
				} else out.add(in.get(k));
				k = best;
			}
			i = j + 1;
		}
		return new Path(out, p.complete, p.ex, p.ey, p.ez, p.cost);
	}

	/** Centre of waypoint {@code t} of the group that starts at step {@code first}: the start of the first step, then the end of each. */
	private static double wx(List<Path.Step> in, int first, int t) {
		return (t == first ? in.get(t).sx() : in.get(t - 1).dx()) + 0.5;
	}

	private static double wz(List<Path.Step> in, int first, int t) {
		return (t == first ? in.get(t).sz() : in.get(t - 1).dz()) + 0.5;
	}

	/** Last step of the open-ground group that begins at {@code i}; {@code i - 1} when step {@code i} is not open ground. */
	private int groupEnd(List<Path.Step> in, int i) {
		int j = i - 1;
		while (j + 1 < in.size() && open(in.get(j + 1))) j++;
		return j;
	}

	private boolean open(Path.Step st) {
		return (st.kind() == Kind.TRAVERSE || st.kind() == Kind.DIAGONAL) && cell(st.sx(), st.sy(), st.sz()) && cell(st.dx(), st.dy(), st.dz());
	}

	/** The room either side of the run {@code st} that is about to be walked: negative if it is no longer plain. */
	public static double slack(Ctx c, Path.Step st) {
		return new Smooth(c, false).slack(st.sy(), st.sx() + 0.5, st.sz() + 0.5, st.dx() + 0.5, st.dz() + 0.5);
	}

	/**
	 * How far the body may stray from the straight line between two points at level {@code y}: negative when the line is
	 * not plain enough to run along, otherwise up to about a block and a quarter.
	 */
	public double slack(int y, double x0, double z0, double x1, double z1) {
		double dx = x1 - x0, dz = z1 - z0;
		int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / SAMPLE));
		double room = WIDE;
		for (int i = 0; i <= n; i++) {
			double x = x0 + dx * i / n, z = z0 + dz * i / n;
			if (!square(y, x, z, CORE, true)) return -1;
			if (room > CORE && !square(y, x, z, MID, false)) room = CORE;
			else if (room > MID && !square(y, x, z, WIDE, false)) room = MID;
		}
		return room - BODY - KEEP;
	}

	/** Every cell the square of half-size {@code r} around the point touches is open ground (and calm, when asked). */
	private boolean square(int y, double x, double z, double r, boolean calm) {
		int x0 = (int) Math.floor(x - r), x1 = (int) Math.floor(x + r), z0 = (int) Math.floor(z - r), z1 = (int) Math.floor(z + r);
		for (int ix = x0; ix <= x1; ix++) {
			for (int iz = z0; iz <= z1; iz++) if (!(calm ? cell(ix, y, iz) : plain(ix, y, iz))) return false;
		}
		return true;
	}

	private boolean cell(int x, int y, int z) {
		return plain(x, y, z) && calm(x, y, z);
	}

	/** Level, solid and empty: a full block to stand on, nothing in the way at body height, nothing that hurts or slows. */
	private boolean plain(int x, int y, int z) {
		long k = AStar.key(x, y, z);
		byte m = plainMemo.get(k);
		if (m != 0) return m > 0;
		boolean ok = computePlain(x, y, z);
		plainMemo.put(k, (byte) (ok ? 1 : -1));
		return ok;
	}

	private boolean computePlain(int x, int y, int z) {
		int body = c.f(x, y, z), head = c.f(x, y + 1, z), floor = c.f(x, y - 1, z);
		if ((body & head & Blk.CLEAR) == 0 || ((body | head) & (Blk.LIQUID | Blk.CLIMB | Blk.FIRE)) != 0) return false;
		if (!c.passable(x, y, z) || !c.passable(x, y + 1, z)) return false; // cactus, berry bushes and the like have no collision but hurt
		if ((floor & Blk.FULL) == 0 || (floor & (Blk.SLOW | Blk.SLIPPERY | Blk.NO_JUMP | Blk.LIQUID | Blk.FIRE)) != 0 || !c.canStandOn(x, y - 1, z)) return false;
		return (!mobs || c.mobPenalty(x, y, z) == 0) && c.lavaPenalty(x, y, z) == 0;
	}

	/**
	 * Stepping onto the cell {@code (x, y, z)} (feet level {@code y}) means a fall that hurts: nothing to stand on there and
	 * nothing but air for another three blocks below (or the void). A step down of a block or two, or into water, is no danger.
	 */
	static boolean drop(Ctx c, int x, int y, int z) {
		if (!c.passable(x, y, z) || !c.passable(x, y - 1, z) || c.water(x, y - 1, z)) return false;
		for (int k = 2; k <= 4; k++) if (!c.passable(x, y - k, z) || c.water(x, y - k, z)) return false;
		return true;
	}

	/**
	 * No lava, fire, water or hazard block in the cells around, and no drop beside it either: a pond, a lava pool or a
	 * ledge close by is not a place to run flat out.
	 */
	private boolean calm(int x, int y, int z) {
		long k = AStar.key(x, y, z);
		byte m = calmMemo.get(k);
		if (m != 0) return m > 0;
		boolean ok = true;
		scan:
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockState s = c.get(x + dx, y + dy, z + dz);
					int f = Blk.f(s);
					if ((f & (Blk.LAVA | Blk.LIQUID | Blk.FIRE)) != 0 || c.hazard(s, f)) {
						ok = false;
						break scan;
					}
				}
				if (drop(c, x + dx, y, z + dz)) {
					ok = false;
					break scan;
				}
			}
		}
		calmMemo.put(k, (byte) (ok ? 1 : -1));
		return ok;
	}
}
