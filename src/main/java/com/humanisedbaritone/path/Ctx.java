package com.humanisedbaritone.path;

import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.settings.Setting;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import com.humanisedbaritone.world.Inv;
import com.humanisedbaritone.world.Tools;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Everything the planner needs to judge a position: a chunk-cached view of the world, a snapshot of the settings and
 * of what the player carries. One instance belongs to one thread.
 */
public final class Ctx {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState(), VOID = Blocks.VOID_AIR.defaultBlockState();

	public final ClientLevel level;
	public final int minY, maxY;
	/** The schematic being built, if any: scaffolding stays out of the cells it still needs. */
	private final com.humanisedbaritone.build.Placement building;
	private LevelChunk chunk;
	private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
	private final Long2ByteOpenHashMap lavaChunk = new Long2ByteOpenHashMap();

	// settings snapshot
	public final boolean sprint, parkourUp, diagonal, swim, climb, doors, allowBreak, allowPlace, noLiquid, fallGuard, lavaAvoid, fireAvoid, clutch;
	/** Not final: the navigator switches a kind of move off for a while when it keeps failing. */
	public boolean parkour, pillar, digDown, bridge;
	public final int maxGap, maxFall, maxFallBucket, lavaMargin;
	public final double jumpPenalty, breakPenalty, placePenalty, noise, mobRadius;
	public final long seed;
	/** Positions that recently made a step fail; the planner will not route through them. */
	public it.unimi.dsi.fastutil.longs.LongSet banned;
	private final Set<Block> hazards = new HashSet<>(), noBreak = new HashSet<>(), placeable = new HashSet<>();

	// what the player carries
	public final boolean hasBlocks, hasWaterBucket;
	private final List<ItemStack> tools = new ArrayList<>();
	private final List<Integer> toolEff = new ArrayList<>();
	private final Tools.Mods mods;
	private final IdentityHashMap<BlockState, Double> breakCache = new IdentityHashMap<>();

	// nearby hostile mobs (x, y, z triples)
	private final double[] mobs;
	private final int mobCount;

	public Ctx() {
		level = mc.level;
		building = com.humanisedbaritone.build.Placement.current != null && com.humanisedbaritone.build.Placement.current.confirmed ? com.humanisedbaritone.build.Placement.current : null;
		minY = level.getMinY();
		maxY = level.getMaxY();
		sprint = Settings.SPRINT.on();
		parkour = Settings.PARKOUR.on();
		parkourUp = Settings.PARKOUR_UP.on();
		diagonal = Settings.DIAGONAL.on();
		swim = Settings.SWIM.on();
		climb = Settings.CLIMB.on();
		doors = Settings.DOORS.on();
		allowBreak = Settings.BREAK.on();
		allowPlace = Settings.PLACE.on();
		pillar = Settings.PILLAR.on();
		digDown = Settings.DIG_DOWN.on();
		bridge = Settings.BRIDGE.on();
		noLiquid = Settings.NO_LIQUID.on();
		fallGuard = Settings.FALL_GUARD.on();
		lavaAvoid = Settings.LAVA.on();
		fireAvoid = Settings.FIRE.on();
		clutch = Settings.CLUTCH.on();
		maxGap = Settings.MAX_GAP.i();
		maxFall = Settings.MAX_FALL.i();
		maxFallBucket = Settings.MAX_FALL_BUCKET.i();
		lavaMargin = Settings.LAVA_MARGIN.i();
		jumpPenalty = Settings.JUMP_PENALTY.d();
		breakPenalty = Settings.BREAK_PENALTY.d();
		placePenalty = Settings.PLACE_PENALTY.d();
		noise = Settings.VAR_PATH.d();
		mobRadius = Settings.MOB_AVOID.d();
		seed = (long) (Rng.unit() * Long.MAX_VALUE);
		resolve(Settings.HAZARDS, hazards);
		resolve(Settings.AVOID_BREAK, noBreak);
		resolve(Settings.PLACEABLE, placeable);

		boolean blocks = false;
		LocalPlayer p = mc.player;
		mods = Tools.Mods.now();
		for (int i = 0; i < 36; i++) {
			ItemStack st = Inv.get(i);
			if (st.isEmpty()) continue;
			if (st.getItem() instanceof BlockItem bi && placeable.contains(bi.getBlock())) blocks = true;
			if (!Tools.worn(st) && (st.getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1 || st.getDestroySpeed(Blocks.DIRT.defaultBlockState()) > 1
				|| st.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) > 1 || st.getDestroySpeed(Blocks.COBWEB.defaultBlockState()) > 1)) {
				tools.add(st.copy());
				toolEff.add(Tools.efficiencyBonus(st));
			}
		}
		hasBlocks = blocks;
		hasWaterBucket = Inv.find(st -> st.is(Items.WATER_BUCKET)) >= 0;

		double[] m = new double[3 * 24];
		int n = 0;
		if (mobRadius > 0 && p != null) {
			for (Entity e : level.entitiesForRendering()) {
				if (n >= 24) break;
				if (e instanceof Monster && e.isAlive() && e.distanceToSqr(p) < 48 * 48) {
					var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
					if (key != null && !Settings.IGNORE.has(key.toString())) {
						m[n * 3] = e.getX();
						m[n * 3 + 1] = e.getY();
						m[n * 3 + 2] = e.getZ();
						n++;
					}
				}
			}
		}
		mobs = m;
		mobCount = n;
	}

	private static void resolve(Setting.IdSet set, Set<Block> out) {
		for (String id : set.get()) {
			Identifier i = Identifier.tryParse(id);
			if (i != null && BuiltInRegistries.BLOCK.containsKey(i)) out.add(BuiltInRegistries.BLOCK.getValue(i));
		}
	}

	public boolean isBanned(int x, int y, int z) {
		return banned != null && banned.contains(AStar.key(x, y, z));
	}

	public Set<Block> placeableBlocks() {
		return placeable;
	}

	// ------------------------------------------------------------ world access

	public BlockState get(int x, int y, int z) {
		if (y < minY) return VOID;
		if (y > maxY) return AIR;
		int nx = x >> 4, nz = z >> 4;
		if (nx != cx || nz != cz) {
			chunk = level.getChunkSource().getChunkNow(nx, nz);
			cx = nx;
			cz = nz;
		}
		if (chunk == null) return AIR;
		LevelChunkSection sec = chunk.getSection(chunk.getSectionIndex(y));
		return sec.getBlockState(x & 15, y & 15, z & 15);
	}

	public boolean loaded(int x, int z) {
		int nx = x >> 4, nz = z >> 4;
		if (nx != cx || nz != cz) {
			chunk = level.getChunkSource().getChunkNow(nx, nz);
			cx = nx;
			cz = nz;
		}
		return chunk != null;
	}

	public int f(int x, int y, int z) {
		return Blk.f(get(x, y, z));
	}

	public boolean hazard(BlockState s, int f) {
		if ((f & Blk.LAVA) != 0) return true; // walking into lava is never acceptable
		if (hazards.contains(s.getBlock())) return true;
		return fireAvoid && (f & Blk.FIRE) != 0;
	}

	/** The body (2 blocks tall) may occupy this cell right now. */
	public boolean passable(int x, int y, int z) {
		BlockState s = get(x, y, z);
		int f = Blk.f(s);
		if ((f & (Blk.PASS | Blk.CLIMB)) == 0) return false;
		return !hazard(s, f);
	}

	public boolean water(int x, int y, int z) {
		return (f(x, y, z) & Blk.WATER) != 0;
	}

	public boolean climbable(int x, int y, int z) {
		return climb && (f(x, y, z) & Blk.CLIMB) != 0;
	}

	public boolean canStandOn(int x, int y, int z) {
		BlockState s = get(x, y, z);
		int f = Blk.f(s);
		return (f & Blk.FLOOR) != 0 && (f & Blk.LAVA) == 0 && !hazard(s, f);
	}

	/** A position the player can be in: clear body, and either a floor, water or a ladder to hold on to. */
	public boolean valid(int x, int y, int z) {
		if (!passable(x, y, z) || !passable(x, y + 1, z)) return false;
		return canStandOn(x, y - 1, z) || (swim && water(x, y, z)) || climbable(x, y, z);
	}

	// ------------------------------------------------------------ breaking

	public double breakTicks(BlockState s) {
		Double c = breakCache.get(s);
		if (c != null) return c;
		double best = Tools.ticks(s, ItemStack.EMPTY, 0, mods);
		for (int i = 0; i < tools.size(); i++) best = Math.min(best, Tools.ticks(s, tools.get(i), toolEff.get(i), mods));
		breakCache.put(s, best);
		return best;
	}

	private boolean breakSafe(int x, int y, int z) {
		if (!noLiquid) return true;
		return !Blk.isLiquid(get(x, y + 1, z)) && !Blk.isLiquid(get(x + 1, y, z)) && !Blk.isLiquid(get(x - 1, y, z))
			&& !Blk.isLiquid(get(x, y, z + 1)) && !Blk.isLiquid(get(x, y, z - 1));
	}

	/** Cost of making this cell passable: 0 if it already is, a door opening, breaking it, or {@link Costs#INF}. */
	public double clearCost(int x, int y, int z) {
		BlockState s = get(x, y, z);
		int f = Blk.f(s);
		if ((f & (Blk.PASS | Blk.CLIMB)) != 0) return hazard(s, f) ? Costs.INF : 0;
		if ((f & Blk.LAVA) != 0 || hazard(s, f)) return Costs.INF;
		if (doors && Blk.openable(s) && (f & Blk.OPEN) == 0) return Costs.DOOR;
		if (!allowBreak || (f & Blk.UNBREAKABLE) != 0 || noBreak.contains(s.getBlock()) || !breakSafe(x, y, z)) return Costs.INF;
		double t = breakTicks(s);
		if (t >= Tools.INF) return Costs.INF;
		double c = t + breakPenalty + Costs.BREAK_OVERHEAD;
		// sand and gravel above would fall into the space we just cleared
		if (fallGuard) {
			for (int k = 1; k <= 4; k++) {
				BlockState a = get(x, y + k, z);
				if (!Blk.has(a, Blk.FALLING)) break;
				c += breakTicks(a) + Costs.BREAK_OVERHEAD;
			}
		}
		return c;
	}

	/** Cost of clearing a whole 2-high column; a door counts once. */
	public double clearColumn(int x, int y, int z) {
		double a = clearCost(x, y, z);
		if (a >= Costs.INF) return Costs.INF;
		BlockState upper = get(x, y + 1, z);
		double b;
		if (a == Costs.DOOR && Blk.has(upper, Blk.DOOR)) b = 0;
		else b = clearCost(x, y + 1, z);
		if (b >= Costs.INF) return Costs.INF;
		return a + b;
	}

	// ------------------------------------------------------------ placing

	public boolean replaceable(int x, int y, int z) {
		BlockState s = get(x, y, z);
		int f = Blk.f(s);
		return (f & (Blk.AIR | Blk.REPLACEABLE)) != 0 && (f & Blk.LAVA) == 0;
	}

	public boolean hasSupport(int x, int y, int z) {
		return solidFace(x, y - 1, z) || solidFace(x + 1, y, z) || solidFace(x - 1, y, z) || solidFace(x, y, z + 1) || solidFace(x, y, z - 1) || solidFace(x, y + 1, z);
	}

	private boolean solidFace(int x, int y, int z) {
		int f = f(x, y, z);
		return (f & Blk.FULL) != 0 && (f & Blk.LIQUID) == 0;
	}

	public boolean canPlace(int x, int y, int z) {
		return allowPlace && hasBlocks && replaceable(x, y, z) && hasSupport(x, y, z) && !reserved(x, y, z);
	}

	/** A schematic is being built: the planner prefers what is dependable (a pillar) to what is not (a staircase of placed blocks). */
	public boolean building() {
		return building != null;
	}

	/** A cell the schematic being built still needs a block in: a bridge or a pillar put there would only be in the way. */
	public boolean reserved(int x, int y, int z) {
		return building != null && building.pending(x, y, z);
	}

	public double placeCost() {
		return placePenalty + Costs.PLACE_OVERHEAD;
	}

	// ------------------------------------------------------------ danger costs

	private boolean chunkHasLava(int ccx, int ccz) {
		long k = ((long) ccx << 32) ^ (ccz & 0xFFFFFFFFL);
		byte v = lavaChunk.getOrDefault(k, (byte) -1);
		if (v >= 0) return v == 1;
		boolean has = false;
		LevelChunk c = level.getChunkSource().getChunkNow(ccx, ccz);
		if (c != null) {
			for (LevelChunkSection sec : c.getSections()) {
				if (!sec.hasOnlyAir() && sec.maybeHas(st -> st.getFluidState().is(FluidTags.LAVA))) {
					has = true;
					break;
				}
			}
		}
		lavaChunk.put(k, (byte) (has ? 1 : 0));
		return has;
	}

	/** Extra cost for standing close to lava. */
	public double lavaPenalty(int x, int y, int z) {
		if (!lavaAvoid || lavaMargin <= 0) return 0;
		int ccx = x >> 4, ccz = z >> 4;
		boolean any = false;
		for (int a = -1; a <= 1 && !any; a++) for (int b = -1; b <= 1; b++) if (chunkHasLava(ccx + a, ccz + b)) {
			any = true;
			break;
		}
		if (!any) return 0;
		double pen = 0;
		for (int dx = -lavaMargin; dx <= lavaMargin; dx++) {
			for (int dz = -lavaMargin; dz <= lavaMargin; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					if (Blk.isLava(get(x + dx, y + dy, z + dz))) {
						int d = Math.max(Math.abs(dx), Math.abs(dz));
						pen = Math.max(pen, 24.0 * (lavaMargin + 1 - d));
					}
				}
			}
		}
		return pen;
	}

	public double mobPenalty(int x, int y, int z) {
		if (mobCount == 0) return 0;
		double pen = 0, r2 = mobRadius * mobRadius;
		for (int i = 0; i < mobCount; i++) {
			double dx = x + 0.5 - mobs[i * 3], dy = y - mobs[i * 3 + 1], dz = z + 0.5 - mobs[i * 3 + 2];
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 < r2) pen += (mobRadius - Math.sqrt(d2)) * 4.0;
		}
		return Math.min(pen, 120);
	}

	/** Deterministic per-position multiplier that makes equal routes differ from run to run. */
	public double jitter(int x, int y, int z) {
		if (noise <= 0) return 1;
		long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ seed;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		double n = (h >>> 11) * 0x1.0p-53;
		return 1 + noise * (2 * n - 1);
	}
}
