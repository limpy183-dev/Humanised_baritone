package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Interact;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.Render;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.BlockScan;
import com.humanisedbaritone.world.Inv;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Harvests ripe crops around the start point and plants them again. */
public final class FarmTask extends Task {
	private enum Ph { PICK, GOTO, BREAK, PLANT, LOOT }

	private static final Minecraft mc = Minecraft.getInstance();
	private final BlockPos origin;
	private final int radius;
	private Ph ph = Ph.PICK;
	private BlockPos cur;
	private Item seed;
	/** The current job is only planting (bare farmland), not harvesting. */
	private boolean plantOnly;
	private Interact.BreakOp op;
	private Interact.PlaceOp plant;
	private Looter loot;
	private final Set<BlockPos> bad = new HashSet<>();
	/** How often planting a bare cell did not work out in the final sweep; three misses write it off. */
	private final Map<BlockPos, Integer> fails = new HashMap<>();
	private int harvested, ticks, pause;

	public FarmTask(int radius) {
		this.radius = radius;
		this.origin = mc.player.blockPosition();
	}

	@Override public String name() {
		return "Farming";
	}

	@Override public String tag() {
		return "FARM";
	}

	@Override public void start() { // also runs again after a respawn, from a clean slate
		ph = Ph.PICK;
		cur = null;
		op = null;
		plant = null;
		loot = null;
		pause = 0;
		bad.clear();
		fails.clear();
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	@Override public String status() {
		return "harvested " + harvested + " crops";
	}

	/** The seed or root vegetable the player carries the most of. */
	private static Item bestSeed() {
		Item best = null;
		int bestN = 0;
		for (Item it : new Item[]{Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS}) {
			int n = Inv.count(st -> st.getItem() == it);
			if (n > bestN) {
				bestN = n;
				best = it;
			}
		}
		return best;
	}

	private static boolean ripe(BlockState s) {
		Block b = s.getBlock();
		if (b instanceof CropBlock cb) return cb.isMaxAge(s);
		if (b == Blocks.NETHER_WART) return s.getValue(NetherWartBlock.AGE) >= 3;
		return b == Blocks.PUMPKIN || b == Blocks.MELON;
	}

	@Override public boolean tick() {
		if (pause > 0) {
			pause--;
			return false;
		}
		if (cur != null && ph != Ph.LOOT) Render.markBreak(cur);
		switch (ph) {
			case PICK -> {
				List<BlockPos> all = BlockScan.find(origin, radius, FarmTask::ripe, 200);
				cur = null;
				double best = 1e18;
				for (BlockPos p : all) {
					if (bad.contains(p)) continue;
					double d = p.distSqr(mc.player.blockPosition()) + Rng.range(0, 6);
					if (d < best) {
						best = d;
						cur = p;
					}
				}
				plantOnly = false;
				if (cur == null && Settings.FARM_REPLANT.on()) {
					// harvesting is done: use the seeds that were collected on the way to fill bare farmland
					seed = bestSeed();
					if (seed != null) {
						for (BlockPos f : BlockScan.find(origin, radius, st -> st.is(Blocks.FARMLAND), 400)) {
							BlockPos above = f.above();
							if (bad.contains(above) || !mc.level.getBlockState(above).isAir()) continue;
							double d = above.distSqr(mc.player.blockPosition()) + Rng.range(0, 6);
							if (d < best) {
								best = d;
								cur = above;
							}
						}
						plantOnly = cur != null;
					}
				}
				if (cur == null) {
					Log.info("Nothing left to do. Harvested " + harvested + (bad.isEmpty() ? "." : ", gave up on " + bad.size() + " spot(s)."));
					return true;
				}
				if (!plantOnly) seed = mc.level.getBlockState(cur).getBlock().asItem();
				Bot.nav.setGoal(new Goal.Reach(cur, 2.7));
				ph = Ph.GOTO;
				ticks = 0;
			}
			case GOTO -> {
				ticks++;
				Nav.State s = Bot.nav.tick();
				boolean there = s == Nav.State.ARRIVED || Interact.pickAim(plantOnly ? cur.below() : cur, plantOnly ? Direction.UP : null) != null && ticks > 3; // planting needs the top of the soil, not any side of it
				if (there) {
					Bot.nav.cancel();
					if (plantOnly) {
						Item it = seed;
						plant = new Interact.PlaceOp(cur, st -> st.getItem() == it, Direction.DOWN);
						ph = Ph.PLANT;
						ticks = 0;
					} else {
						op = new Interact.BreakOp(cur);
						ph = Ph.BREAK;
					}
				} else if (s == Nav.State.FAILED || ticks > 900) {
					bad.add(cur);
					ph = Ph.PICK;
				}
			}
			case BREAK -> {
				Interact.R r = op.tick();
				if (r == Interact.R.DONE) {
					harvested++;
					pause = Rng.delayTicks(Settings.ACT_DELAY.d());
					Item it = seed;
					boolean plantable = it != null && (mc.level.getBlockState(cur.below()).getBlock() == Blocks.FARMLAND || mc.level.getBlockState(cur.below()).getBlock() == Blocks.SOUL_SAND);
					// a ripe neighbour (its hitbox is a full block) can hide the soil from here: then the sweep at the end plants it, once they are all gone
					if (Settings.FARM_REPLANT.on() && plantable && Inv.find(st -> st.getItem() == it) >= 0 && Interact.pickAim(cur.below(), Direction.UP) != null) {
						plant = new Interact.PlaceOp(cur, st -> st.getItem() == it, Direction.DOWN);
						ph = Ph.PLANT;
						ticks = 0;
					} else {
						loot = new Looter(cur, 6);
						ph = Ph.LOOT;
					}
				} else if (r == Interact.R.FAIL || r == Interact.R.NO_AIM && ++ticks > 60) {
					bad.add(cur);
					ph = Ph.PICK;
				}
			}
			case PLANT -> {
				Interact.R r = plant.tick();
				if (r == Interact.R.DONE || r == Interact.R.FAIL || r == Interact.R.NO_AIM && ++ticks > 40) {
					// a miss is not final: the sweep at the end tries bare farmland again, and only repeated misses there write a spot off
					if (r != Interact.R.DONE && plantOnly && fails.merge(cur, 1, Integer::sum) >= 3) bad.add(cur);
					if (plantOnly) {
						cur = null;
						ph = Ph.PICK;
					} else {
						loot = new Looter(cur, 6);
						ph = Ph.LOOT;
					}
					pause = Rng.delayTicks(Settings.ACT_DELAY.d());
				}
			}
			case LOOT -> {
				if (loot.tick()) {
					cur = null;
					ph = Ph.PICK;
				}
			}
		}
		return false;
	}
}
