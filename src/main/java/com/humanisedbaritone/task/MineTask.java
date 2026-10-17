package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Interact;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.Render;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import com.humanisedbaritone.world.BlockScan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

/** Finds the wanted blocks in the loaded world, walks up to them, mines them and collects what drops. */
public final class MineTask extends Task {
	private enum Ph { PICK, GOTO, BREAK, LOOT, EXPLORE }

	private static final Minecraft mc = Minecraft.getInstance();
	private final Set<Block> targets;
	private final int limit;
	private final String label;
	private Ph ph = Ph.PICK;
	private List<BlockPos> cache = new ArrayList<>();
	private final Set<BlockPos> bad = new HashSet<>();
	private BlockPos cur, last;
	private Interact.BreakOp op;
	private Looter loot;
	private int mined, scanCd, ticks, noAim, exploreTicks, scans;

	public MineTask(Set<Block> targets, int limit, String label) {
		this.targets = targets;
		this.limit = limit;
		this.label = label;
	}

	@Override public String name() {
		return "Mining " + label;
	}

	@Override public String tag() {
		return "MINING";
	}

	@Override public void start() { // also runs again after a respawn, from a clean slate
		Explorer.reset();
		ph = Ph.PICK;
		cur = null;
		op = null;
		loot = null;
		cache = new ArrayList<>();
		bad.clear();
		scanCd = 0;
		scans = 0;
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	@Override public String status() {
		return switch (ph) {
			case PICK -> "looking for " + label;
			case GOTO -> "walking to the next block  -  mined " + mined;
			case BREAK -> "mining  -  mined " + mined;
			case LOOT -> "picking up drops  -  mined " + mined;
			case EXPLORE -> "exploring for " + label + "  -  mined " + mined;
		};
	}

	@Override public double progress() {
		return limit > 0 ? Math.min(1.0, mined / (double) limit) : -1;
	}

	private boolean isTarget(BlockPos p) {
		return targets.contains(mc.level.getBlockState(p).getBlock());
	}

	private boolean allowed(BlockPos p) {
		if (bad.contains(p) || !isTarget(p)) return false;
		var st = mc.level.getBlockState(p);
		if ((Blk.f(st) & Blk.UNBREAKABLE) != 0) return false;
		if (Settings.MINE_VISIBLE.on() && !BlockScan.exposed(p)) return false;
		if (Settings.NO_LIQUID.on()) {
			for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
				if (Blk.isLiquid(mc.level.getBlockState(p.relative(d)))) return false;
			}
		}
		return true;
	}

	private BlockPos choose() {
		LocalPlayer pl = mc.player;
		BlockPos me = pl.blockPosition();
		BlockPos best = null;
		double bestScore = 1e18;
		for (BlockPos p : cache) {
			if (!allowed(p)) continue;
			double d = Math.sqrt(p.distSqr(me)) + Math.abs(p.getY() - me.getY()) * 1.5;
			if (Settings.MINE_VEIN.on() && last != null) d = Math.min(d, Math.sqrt(p.distSqr(last)) * 0.6 + 2);
			d += Rng.range(0, 2.0); // do not always take the mathematically nearest one
			if (d < bestScore) {
				bestScore = d;
				best = p;
			}
		}
		return best;
	}

	/** Positions from which {@code p} can be worked on: the four neighbours at feet or head height. */
	private Goal standGoal(BlockPos p) {
		List<Goal> g = new ArrayList<>();
		for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			g.add(new Goal.At(p.getX() + d[0], p.getY(), p.getZ() + d[1]));
			g.add(new Goal.At(p.getX() + d[0], p.getY() - 1, p.getZ() + d[1]));
		}
		return new Goal.Any(g);
	}

	@Override public boolean tick() {
		LocalPlayer pl = mc.player;
		if (limit > 0 && mined >= limit && ph == Ph.PICK) { // not before the last drop has been picked up
			Log.info("Mined " + mined + " " + label + ".");
			return true;
		}
		Explorer.mark();
		if (cur != null && ph != Ph.LOOT) Render.markBreak(cur);
		switch (ph) {
			case PICK -> {
				if (--scanCd <= 0 || cache.isEmpty()) {
					cache = BlockScan.find(pl.blockPosition(), Settings.MINE_RADIUS.i(), s -> targets.contains(s.getBlock()), 300);
					scanCd = 40;
					scans++;
				}
				cur = choose();
				if (cur == null) {
					if (cache.isEmpty() || scans > 1) {
						if (Settings.MINE_EXPLORE.on()) {
							ph = Ph.EXPLORE;
							exploreTicks = 0;
							Goal g = Explorer.pick(30, 70);
							if (g == null) {
								Log.warn("No " + label + " around and nowhere new to explore.");
								return true;
							}
							Bot.nav.setGoal(g);
							return false;
						}
						Log.info("No " + label + " in range (" + mined + " mined).");
						return true;
					}
					scanCd = 0;
					return false;
				}
				ticks = 0;
				noAim = 0;
				Log.debug("mine: next " + cur.toShortString() + " of " + cache.size() + " known");
				// already in a good spot? then start right away
				if (Interact.pickAim(cur, null) != null) {
					op = new Interact.BreakOp(cur);
					ph = Ph.BREAK;
				} else {
					Bot.nav.setGoal(standGoal(cur));
					ph = Ph.GOTO;
				}
			}
			case GOTO -> {
				ticks++;
				if (!allowed(cur)) {
					ph = Ph.PICK;
					return false;
				}
				Nav.State s = Bot.nav.tick();
				if (s == Nav.State.ARRIVED) {
					op = new Interact.BreakOp(cur);
					ph = Ph.BREAK;
					Bot.nav.cancel();
				} else if (s == Nav.State.FAILED || ticks > 1600) {
					Log.debug("mine: cannot get to " + cur.toShortString() + " (" + (s == Nav.State.FAILED ? Bot.nav.failReason : "took too long") + ")");
					bad.add(cur);
					cur = null;
					Bot.nav.cancel();
					ph = Ph.PICK;
				}
			}
			case BREAK -> {
				Interact.R r = op.tick();
				if (r == Interact.R.DONE) {
					mined++;
					last = cur;
					cache.remove(last);
					if (Settings.MINE_PICKUP.on()) {
						loot = new Looter(last, Rng.delayTicks(Settings.ACT_DELAY.d()) + 6);
						ph = Ph.LOOT;
					} else {
						cur = null;
						ph = Ph.PICK;
					}
				} else if (r == Interact.R.FAIL || r == Interact.R.NO_AIM && ++noAim > 50) {
					Log.debug("mine: giving up on " + cur.toShortString() + " (" + (r == Interact.R.FAIL ? "breaking failed" : "no way to aim at it") + ") from " + pl.blockPosition().toShortString());
					bad.add(cur);
					cur = null;
					ph = Ph.PICK;
				}
			}
			case LOOT -> {
				if (loot.tick()) {
					cur = null;
					ph = Ph.PICK;
				}
			}
			case EXPLORE -> {
				exploreTicks++;
				Nav.State s = Bot.nav.tick();
				if (exploreTicks % 60 == 0) cache = BlockScan.find(pl.blockPosition(), Settings.MINE_RADIUS.i(), st -> targets.contains(st.getBlock()), 300);
				boolean found = false;
				for (BlockPos p : cache) if (allowed(p)) {
					found = true;
					break;
				}
				if (found || s == Nav.State.ARRIVED || s == Nav.State.FAILED || exploreTicks > 2400) {
					Bot.nav.cancel();
					scanCd = 0;
					scans = 0;
					ph = Ph.PICK;
				}
			}
		}
		return false;
	}
}
