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
import com.humanisedbaritone.world.Inv;
import com.humanisedbaritone.world.Orient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Works through a list of jobs, each one "make this position look like that": a block to break (want == null) or a block
 * state to place. Clearing an area, filling a box and building a schematic are all just different job lists.
 */
public class WorkTask extends Task {
	public record Job(BlockPos pos, BlockState want) {
		boolean isBreak() {
			return want == null;
		}
	}

	private enum Ph { PICK, GOTO, WORK, PAUSE, LOOT }

	private static final Minecraft mc = Minecraft.getInstance();
	private final String title, tag;
	private final List<Job> jobs;
	private final Comparator<Job> order;
	private final Set<BlockPos> skipped = new HashSet<>();
	private final Map<BlockPos, Integer> tries = new HashMap<>();
	/** Standing spots that turned out not to work for each job, and the ones of the job at hand. */
	private final Map<BlockPos, Set<BlockPos>> bad = new HashMap<>();
	private Set<BlockPos> badStands = new HashSet<>();
	private final Map<Item, Integer> missing = new LinkedHashMap<>();
	/** Jobs whose block cannot come out as planned yet (the neighbour it should be placed against is still missing): the others go first. Value: how many jobs were done then; after a few more the job is looked at again. */
	private final Map<BlockPos, Integer> deferred = new HashMap<>();
	private double lastBestQ = -1;
	private Ph ph = Ph.PICK;
	private Job cur;
	/** The steps of the job at hand: a waterlogged chest is water first, then the chest; a slab is the slab, then the water. */
	private final java.util.ArrayDeque<Object> ops = new java.util.ArrayDeque<>();
	private Looter loot;
	private int done, ticks, pause, noAim, total, sweeps, held, spots, crooked, beat, doneAtSweep;
	/** Jobs left alone because nothing could be done for them yet (no click gives the planned state, no spot to work from, the pond is not sealed): tried again after the next round if the world has moved on. */
	private final Set<BlockPos> retryLater = new HashSet<>();
	/** How often a block was placed at each position: one that keeps coming undone (a coral without water) is not worth endless rounds. */
	private final Map<BlockPos, Integer> placedAt = new HashMap<>();
	/** Temporary blocks put down so that a floating block (a trapdoor among rails, a top slab in the air) has something to be clicked against: the cell, and the job it is there for. They stay until that job is done. */
	private final Map<BlockPos, List<Job>> holds = new HashMap<>();
	/** The temporary blocks still to be placed before the job that needs them, the lowest first; and all temporary jobs ever made. */
	private final java.util.ArrayDeque<Job> support = new java.util.ArrayDeque<>();
	private final Set<Job> tempJobs = new HashSet<>();
	private final Map<BlockPos, Integer> supportTries = new HashMap<>(), supportLook = new HashMap<>();
	/** Cells where a temporary block did not make the job possible after all (the click from there does not work in practice): not tried again. */
	private final Map<BlockPos, Set<BlockPos>> supportTried = new HashMap<>();
	/** For the log: the standing spot the last search found best, and the one that made a temporary block's click work. */
	private BlockPos lastBestSpot, virtualSpot;
	/** What each position is to become, for the jobs of the current round: a break job of a dead coral must know the live one that is to replace it. */
	private final Map<BlockPos, BlockState> wantAt = new HashMap<>();
	/** Asked for new jobs when the list is used up: the world is looked at again, and what is still not right (a block someone put in the way, a scaffold) gets its own jobs. */
	private final Supplier<List<Job>> resweep;
	/** A placed block only counts when its facing, half and water are as planned too (schematics), not just its type (filling a box). */
	private final boolean exact;
	/** Would water poured at this position run out of the cells that are meant to be wet? Water is held back until the pond is sealed, or it floods the build site (and sweeps the bot off its feet). */
	private java.util.function.Predicate<BlockPos> leaks;

	/** @param order how jobs are prioritised; ties are broken by distance to the player */
	public WorkTask(String title, String tag, List<Job> jobs, Comparator<Job> order) {
		this(title, tag, jobs, order, null, false);
	}

	public WorkTask(String title, String tag, List<Job> jobs, Comparator<Job> order, Supplier<List<Job>> resweep, boolean exact) {
		this.title = title;
		this.tag = tag;
		this.jobs = new ArrayList<>(jobs);
		this.order = order;
		this.total = jobs.size();
		this.resweep = resweep;
		this.exact = exact;
		indexJobs();
	}

	private void indexJobs() {
		wantAt.clear();
		for (Job j : jobs) if (!j.isBreak()) wantAt.put(j.pos(), j.want());
	}

	public WorkTask leaking(java.util.function.Predicate<BlockPos> leaks) {
		this.leaks = leaks;
		return this;
	}

	@Override public String name() {
		return title;
	}

	@Override public String tag() {
		return tag;
	}

	@Override public void start() { // also runs again after a respawn, from a clean slate
		ph = Ph.PICK;
		cur = null;
		ops.clear();
		loot = null;
		pause = 0;
		bad.clear();
		badStands = new HashSet<>();
		deferred.clear();
		support.clear();
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	@Override public double progress() {
		return total == 0 ? 1 : (double) done / total;
	}

	@Override public String status() {
		return done + " of " + total + " done" + (missing.isEmpty() ? "" : "  -  missing " + missing.size() + " material(s)") + "  (" + ph.name().toLowerCase() + ")";
	}

	private boolean satisfied(Job j) {
		BlockState s = mc.level.getBlockState(j.pos());
		if (j.isBreak()) {
			if (s.isAir() || (Blk.f(s) & (Blk.LIQUID | Blk.REPLACEABLE)) != 0 && (Blk.f(s) & Blk.FULL) == 0) return true;
			BlockState w = wantAt.get(j.pos());
			if (exact && w != null && !tempJobs.contains(j) && Orient.built(s, w)) return true;
			return exact && w != null && deadCoralOf(s, w) && !waterNextTo(j.pos()); // the dead coral stays until there is water beside it to keep the new one alive
		}
		if (exact ? Orient.built(s, j.want()) : s.getBlock() == j.want().getBlock()) return true;
		return exact && deadCoralOf(s, j.want()) && !waterNextTo(j.pos());
	}

	/** A coral block that died because no water touched it: still a block of the right shape, which seals the pond until the live one can replace it. */
	private static boolean deadCoralOf(BlockState have, BlockState want) {
		String h = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(have.getBlock()).getPath(), w = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
		return w.endsWith("_coral_block") && h.equals("dead_" + w);
	}

	/** Does water touch this cell? A coral block dies a few seconds after it was placed if not (CoralBlock.scanForWater). */
	private static boolean waterNextTo(BlockPos p) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (mc.level.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.WATER)) return true;
		return false;
	}

	/** What goes into the hand for a block: water comes from a bucket, everything else from its own item. */
	private static Item itemFor(BlockState want) {
		return want.getBlock() instanceof LiquidBlock ? Items.WATER_BUCKET : want.getBlock().asItem();
	}

	private boolean has(Item it) {
		if (mc.player.hasInfiniteMaterials()) {
			if (Inv.find(st -> st.getItem() == it) < 0) Inv.give(it); // creative: take the item from the creative inventory
			return true;
		}
		return Inv.find(st -> st.getItem() == it) >= 0;
	}

	private static boolean wantsWater(BlockState s) {
		return s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED);
	}

	/** A chest, a trapdoor, a hopper ...: a click with a bucket would open it instead of filling it. */
	private static boolean interactive(BlockState s) {
		return (Blk.f(s) & Blk.INTERACT) != 0;
	}

	/** Does something that does not belong stand in the cell (a coral that died, a block turned the wrong way, a temporary support)? It is broken first, then the right one goes in. */
	private boolean breaksFirst(BlockState have, BlockState want) {
		return exact && (Blk.f(have) & (Blk.AIR | Blk.REPLACEABLE)) == 0 && !Orient.waterMissing(have, want) && !Orient.built(have, want) && !Orient.shortCount(have, want) && Settings.BUILD_CLEAR.on() && !Work.protectedBlock(have);
	}

	/** What the job needs and the player does not have, or null. */
	private Item lacking(Job j) {
		if (j.isBreak()) return null;
		Item it = itemFor(j.want());
		if (!has(it)) return it;
		if (!(j.want().getBlock() instanceof LiquidBlock) && wantsWater(j.want()) && !has(Items.WATER_BUCKET)) return Items.WATER_BUCKET;
		return null;
	}

	/** Creative mode: the path planner only bridges and pillars with what is in the inventory, so a stack of cobblestone is always there. */
	private void ensureScaffoldBlocks() {
		if (!mc.player.hasInfiniteMaterials()) return;
		if (Inv.find(st -> st.getItem() instanceof net.minecraft.world.item.BlockItem bi && Settings.PLACEABLE.has(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString())) < 0) Inv.give(Items.COBBLESTONE);
	}

	private Job choose() {
		while (!support.isEmpty()) { // the temporary blocks for a floating block come first
			Job s = support.peek();
			if (satisfied(s)) support.poll(); // (not "skipped": that is about the job of the plan at this position, a water job perhaps, not about the temporary block)
			else return s;
		}
		BlockPos me = mc.player.blockPosition();
		Job best = null;
		boolean bestDeferred = true;
		for (Job j : jobs) {
			if (skipped.contains(j.pos()) || satisfied(j) || heldCell(j.pos()) || waitsForBreak(j)) continue;
			boolean d = deferred(j.pos());
			if (best == null || bestDeferred && !d) {
				best = j;
				bestDeferred = d;
				continue;
			}
			if (d && !bestDeferred) continue;
			int c = order.compare(j, best);
			if (c < 0 || c == 0 && j.pos().distSqr(me) < best.pos().distSqr(me)) {
				best = j;
				bestDeferred = d;
			}
		}
		return best;
	}

	/** A block that replaces another one: it waits until the job that breaks the old one has been done (the cell is not free before, and the click is planned for a free cell). */
	private boolean waitsForBreak(Job j) {
		if (j.isBreak() || !exact) return false;
		for (Job b : jobs) if (b.isBreak() && b.pos().equals(j.pos()) && !skipped.contains(b.pos()) && !satisfied(b)) return true;
		return false;
	}

	/** A cell with a temporary block that a job still needs: nothing else is done there until that job is. */
	private boolean heldCell(BlockPos p) {
		List<Job> deps = holds.get(p);
		if (deps == null) return false;
		deps.removeIf(d -> satisfied(d) || skipped.contains(d.pos()));
		if (deps.isEmpty()) {
			holds.remove(p);
			return false;
		}
		return true;
	}

	private boolean deferred(BlockPos p) {
		Integer at = deferred.get(p);
		return at != null && done - at < 6;
	}

	/** Is there any other job that is not waiting for something? */
	private boolean othersPending(Job self) {
		for (Job j : jobs) if (j != self && !skipped.contains(j.pos()) && !deferred(j.pos()) && !satisfied(j)) return true;
		return false;
	}

	@Override public boolean tick() {
		LocalPlayer p = mc.player;
		if (pause > 0) {
			pause--;
			return false;
		}
		if (++beat % 200 == 0 && Settings.DEBUG.on()) Log.debug(String.format("work: %s %s at %s, ops %s, player %.1f %.1f %.1f", ph, cur == null ? "-" : cur.isBreak() ? "break " + cur.pos().toShortString() : cur.pos().toShortString() + " " + cur.want().getBlock().getDescriptionId().replaceAll("^.*\\.", ""), cur == null ? "-" : "tries " + tries.getOrDefault(cur.pos(), 0),
			ops.isEmpty() ? "none" : ops.peek().getClass().getSimpleName(), p.getX(), p.getY(), p.getZ()));
		if (cur != null && ph != Ph.LOOT) {
			if (cur.isBreak()) Render.markBreak(cur.pos());
			else Render.markPlace(cur.pos());
		}
		switch (ph) {
			case PICK -> {
				ensureScaffoldBlocks();
				cur = choose();
				if (cur == null) return finish();
				Item lack = lacking(cur);
				if (lack != null) {
					missing.merge(lack, 1, Integer::sum);
					skipped.add(cur.pos());
					cur = null;
					return false;
				}
				if (leaks != null && !cur.isBreak() && waterNext(cur) && leaks.test(cur.pos())) {
					if (othersPending(cur) && !deferred(cur.pos())) {
						deferred.put(cur.pos(), done); // the walls of the pond are not all there yet
					} else {
						Log.debug("water not poured at " + cur.pos() + ": it would run out of the pond");
						skipped.add(cur.pos());
						retryLater.add(cur.pos());
					}
					cur = null;
					return false;
				}
				ticks = 0;
				noAim = 0;
				badStands = bad.computeIfAbsent(cur.pos(), k -> new HashSet<>());
				boolean inTheWay = !cur.isBreak() && !(cur.want().getBlock() instanceof LiquidBlock) && p.getBoundingBox().intersects(new AABB(cur.pos()));
				double here = inTheWay ? -1 : quality(p.getEyePosition(), cur);
				lastBestQ = -1;
				Goal g = here >= 1 - 1e-9 ? null : standGoal(cur, here);
				if (!cur.isBreak() && Settings.DEBUG.on() && cur.want().getProperties().size() > 1) Log.debug(String.format("job %s %s: quality here %.2f, best spot %.2f%s%s", cur.pos().toShortString(), cur.want(), here, lastBestQ, lastBestSpot == null ? "" : " at " + lastBestSpot.toShortString(), deferred.containsKey(cur.pos()) ? " (was deferred)" : ""));
				double best = Math.max(here, lastBestQ);
				boolean stuck = exact && best < 1 - 1e-9 && needsSupport(cur);
				if (stuck && (looksUp(cur.want()) || !hopeful(cur)) && trySupport(cur)) { // no block that is still to come will give it the click it needs (a trapdoor among rails): its neighbours are free now, and will not be later; the look-up blocks (pistons) come first and wait for nobody
					cur = null;
					return false;
				}
				if (!cur.isBreak() && best < 1 - 1e-9 && !deferred(cur.pos()) && othersPending(cur)) {
					deferred.put(cur.pos(), done); // it would come out wrong now: maybe the blocks it needs will exist once the others are done
					cur = null;
					return false;
				}
				if (stuck && trySupport(cur)) { // nothing to click that gives the planned state: a temporary block beside it will
					cur = null;
					return false;
				}
				if (exact && !cur.isBreak() && best >= 0 && best < 1 - 1e-9) { // nothing is left to wait for, and every click that can be made gives another state than the planned one: better no block than a wrong one
					Log.debug("cannot place " + cur.pos() + " " + cur.want() + " the planned way from anywhere");
					skipped.add(cur.pos());
					retryLater.add(cur.pos());
					cur = null;
					return false;
				}
				spots = 0;
				if (g == null) {
					startWork();
				} else {
					Bot.nav.setGoal(g);
					ph = Ph.GOTO;
				}
			}
			case GOTO -> {
				ticks++;
				if (satisfied(cur)) {
					ph = Ph.PICK;
					return false;
				}
				Nav.State s = Bot.nav.tick();
				if (s == Nav.State.ARRIVED) {
					Bot.nav.cancel();
					startWork();
				} else if (s == Nav.State.FAILED || ticks > 1500) {
					giveUp(cur, "no route");
				}
			}
			case WORK -> {
				if (++ticks > 2400) { // a job that takes two minutes is stuck
					giveUp(cur, "timed out");
					return false;
				}
				Object op = ops.peek();
				Interact.R r = op == null ? Interact.R.DONE : op instanceof Interact.BreakOp b ? b.tick() : op instanceof Interact.BucketOp u ? u.tick() : ((Interact.PlaceOp) op).tick();
				switch (r) {
					case DONE -> {
						if (op != null) ops.poll();
						if (!ops.isEmpty() && ops.peek() instanceof Interact.BucketOp && leaks != null && leaks.test(cur.pos())) { // the block stands, but its water would run out: it waits until the pond is sealed
							ops.clear();
							deferred.put(cur.pos(), done);
							retryLater.add(cur.pos());
							ph = Ph.PICK;
						} else if (!ops.isEmpty()) { // the next step of the same job
							noAim = 0;
						} else if (tempJobs.contains(cur)) { // a temporary support: not part of the plan
							if (!cur.isBreak()) com.humanisedbaritone.build.Scaffold.placed(cur.pos(), mc.level.getBlockState(cur.pos()).getBlock());
							pause = Rng.delayTicks(Settings.ACT_DELAY.d());
							ph = Ph.PICK;
						} else if (!cur.isBreak() && !satisfied(cur)) {
							giveUp(cur, "the block that ended up there is not the planned one"); // e.g. a standing torch instead of a wall torch
						} else {
							done++;
							if (!cur.isBreak() && placedAt.merge(cur.pos(), 1, Integer::sum) >= 4) {
								skipped.add(cur.pos());
								retryLater.add(cur.pos());
							}
							pause = Rng.delayTicks(Settings.ACT_DELAY.d());
							if (cur.isBreak() && Settings.MINE_PICKUP.on()) {
								loot = new Looter(cur.pos(), 5);
								ph = Ph.LOOT;
							} else ph = Ph.PICK;
						}
					}
					case FAIL -> {
						if (op instanceof Interact.PlaceOp po && po.clicks() == 0 || op instanceof Interact.BucketOp bo && bo.clicks() == 0) badStands.add(Bot.nav.feet()); // never found a click to make from here: do not come back to this spot
						giveUp(cur, "operation failed");
					}
					case NO_AIM -> {
						if (++noAim > 14) {
							// this spot does not give a clear view: try another one, and give up on the job after a few tries
							if (Settings.DEBUG.on() && !cur.isBreak()) Log.debug("no click for " + cur.pos().toShortString() + " " + cur.want().getBlock().getDescriptionId().replaceAll("^.*\\.", "") + " from " + Bot.nav.feet().toShortString() + ":" + Interact.explain(p.getEyePosition(), cur.pos()));
							badStands.add(Bot.nav.feet());
							noAim = 0;
							if (++spots > 4 || badStands.size() > 6) {
								giveUp(cur, "no workable position");
							} else {
								Bot.nav.setGoal(standGoal(cur, -1));
								ph = Ph.GOTO;
							}
						}
					}
					default -> {
					}
				}
			}
			case LOOT -> {
				if (loot.tick()) ph = Ph.PICK;
			}
			default -> ph = Ph.PICK;
		}
		return false;
	}

	/** Is the water the next thing this job has to do? A waterlogged slab is placed dry first: its water waits for a sealed pond, the slab itself is part of the wall. */
	private boolean waterNext(Job j) {
		BlockState want = j.want();
		if (want.getBlock() instanceof LiquidBlock) return true;
		return wantsWater(want) && (interactive(want) || mc.level.getBlockState(j.pos()).getBlock() == want.getBlock());
	}

	/**
	 * A block that the game turns by where the player LOOKS and that is to face down (a piston, a dispenser, an observer): the player has to look up at
	 * it, steeply, so the spot to do it from is on the ground below it, with a free line of sight. Once the layers underneath are built there is none.
	 */
	public static boolean looksUp(BlockState want) {
		if (want == null || !want.hasProperty(BlockStateProperties.FACING) || want.getValue(BlockStateProperties.FACING) != net.minecraft.core.Direction.DOWN) return false;
		var b = want.getBlock();
		return b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock || b instanceof net.minecraft.world.level.block.DispenserBlock || b instanceof net.minecraft.world.level.block.ObserverBlock;
	}

	/** How far below a job to look for spots to stand on: the look-up blocks are done from the ground far under them. */
	private static int lookLow(Job j) {
		return !j.isBreak() && looksUp(j.want()) ? -7 : -3;
	}

	/**
	 * Is a block that is still to be built beside the job one that a click could be made on: a full cube, a slab, a stair? Then the job is
	 * worth waiting for (the top slab under a block that goes above it). Rails and water are no help, and neither are cells that stay empty.
	 */
	private boolean hopeful(Job j) {
		if (j.want().getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) return true; // a rail comes out as the rails around it make it: a temporary block does not change that
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			BlockPos n = j.pos().relative(d);
			BlockState w = wantAt.get(n);
			if (w == null || Orient.built(mc.level.getBlockState(n), w)) continue;
			if ((Blk.f(w) & Blk.FULL) != 0 || w.getBlock() instanceof net.minecraft.world.level.block.SlabBlock || w.getBlock() instanceof net.minecraft.world.level.block.StairBlock) return true;
		}
		return false;
	}

	/** A block of the plan that stands where a temporary support is wanted and can be put back afterwards: a rail, a slab, a plain block. Not a chest, a hopper or a trapdoor: those take their own aiming. */
	private boolean redoable(BlockPos s) {
		BlockState have = mc.level.getBlockState(s);
		BlockState w = wantAt.get(s);
		if (w == null && com.humanisedbaritone.build.Placement.current != null) w = com.humanisedbaritone.build.Placement.current.wantAt(s);
		return Settings.BUILD_CLEAR.on() && w != null && Orient.built(have, w) && (Blk.f(have) & (Blk.INTERACT | Blk.LIQUID | Blk.AIR)) == 0 && !Work.protectedBlock(have) && !mc.player.getBoundingBox().intersects(new AABB(s));
	}

	/** Can a block be put into this cell by a click: does any cell beside it have a face to click (a hopper's rim will do, not only a full cube)? */
	private static boolean anchored(com.humanisedbaritone.path.Ctx c, BlockPos p) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			BlockPos n = p.relative(d);
			if ((c.f(n.getX(), n.getY(), n.getZ()) & (Blk.AIR | Blk.REPLACEABLE | Blk.LIQUID)) == 0) return true;
		}
		return false;
	}

	/** A block to put into an empty cell by a click (no water to pour, nothing to break first), and not itself a temporary support. */
	private boolean needsSupport(Job j) {
		if (j.isBreak() || tempJobs.contains(j) || j.want().getBlock() instanceof LiquidBlock) return false;
		BlockState have = mc.level.getBlockState(j.pos());
		return (Blk.f(have) & (Blk.AIR | Blk.REPLACEABLE)) != 0 && !Orient.waterMissing(have, j.want());
	}

	/** The block a temporary support is made of: whatever building block the player carries (creative: cobblestone). */
	private BlockState scaffoldState() {
		int slot = Inv.find(st -> st.getItem() instanceof net.minecraft.world.item.BlockItem bi && Settings.PLACEABLE.has(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString()));
		if (slot >= 0) return ((net.minecraft.world.item.BlockItem) Inv.get(slot).getItem()).getBlock().defaultBlockState();
		return mc.player.hasInfiniteMaterials() ? net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState() : null;
	}

	/** An empty cell a temporary block can be put into. */
	private static boolean free(com.humanisedbaritone.path.Ctx c, BlockPos p) {
		return c.replaceable(p.getX(), p.getY(), p.getZ()) && (c.f(p.getX(), p.getY(), p.getZ()) & Blk.LIQUID) == 0 && !mc.player.getBoundingBox().intersects(new AABB(p));
	}

	/**
	 * Queues temporary blocks for a job nothing can be clicked against from anywhere (a trapdoor among rails, a top slab in the air): in an empty
	 * cell beside it, one whose face gives exactly the planned state when clicked from some standing spot, plus the cells below it that it needs
	 * to rest on. Returns false when there is no such cell.
	 */
	private boolean trySupport(Job j) {
		if (supportTries.getOrDefault(j.pos(), 0) >= 3) return false; // three temporary supports did not do it: leave the job
		Integer looked = supportLook.get(j.pos());
		if (looked != null && done - looked < 3) return false; // looked a moment ago and found nothing: the world has hardly changed since
		supportLook.put(j.pos(), done);
		BlockState st = scaffoldState();
		var c = Bot.nav.live();
		if (st == null || !c.allowPlace) return false;
		BlockPos t = j.pos();
		Set<BlockPos> tried = supportTried.computeIfAbsent(t, k -> new HashSet<>());
		List<BlockPos> bestChain = null;
		boolean bestUndo = false;
		double bestCost = Double.MAX_VALUE;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			BlockPos s = t.relative(d);
			if (tried.contains(s)) continue;
			boolean undo = !free(c, s); // taken by a block of the plan that can be put back afterwards (a rail): it comes out for the time being
			if (undo && !redoable(s)) continue;
			List<BlockPos> chain = new ArrayList<>(); // the cell, and the ones under it until something holds them
			if (undo) chain.add(s);
			else {
				BlockPos p = s;
				for (int k = 0; k < 9 && chain.size() == k; k++) {
					chain.add(p);
					if (anchored(c, p)) break;
					p = p.below();
					if (p.equals(t) || !free(c, p)) break;
				}
				if (!anchored(c, chain.get(chain.size() - 1))) continue;
			}
			double cost = chain.size() * 2 + (c.reserved(s.getX(), s.getY(), s.getZ()) ? 3 : 0) + (undo ? 6 : 0);
			if (cost >= bestCost) continue;
			double q = virtualQuality(c, j, s);
			Log.debug(String.format("  support cell %s%s: click quality %.2f%s", s.toShortString(), undo ? " (a block of the plan stands there)" : "", q, virtualSpot == null || q < 1 - 1e-9 ? "" : " from " + virtualSpot.toShortString()));
			if (q < 1 - 1e-9) continue;
			bestCost = cost;
			bestChain = chain;
			bestUndo = undo;
		}
		if (bestChain == null) return false;
		tried.add(bestChain.get(0));
		supportTries.merge(j.pos(), 1, Integer::sum);
		if (bestUndo) { // out with the block of the plan first; the normal jobs put it back when this one is done
			Job out = new Job(bestChain.get(0), null);
			tempJobs.add(out);
			support.add(out);
		}
		for (int i = bestChain.size() - 1; i >= 0; i--) { // the lowest first
			Job s = new Job(bestChain.get(i), st);
			tempJobs.add(s);
			support.add(s);
			holds.computeIfAbsent(bestChain.get(i), k -> new ArrayList<>()).add(j);
		}
		deferred.remove(j.pos());
		Log.debug("support for " + j.pos() + " " + j.want() + ": temporary " + st.getBlock().getName().getString() + " at " + bestChain + (bestUndo ? " (the block of the plan there comes out first)" : ""));
		return true;
	}

	/** The best a click on the face of an imagined block at {@code s} can do for the job from any spot to stand on. */
	private double virtualQuality(com.humanisedbaritone.path.Ctx c, Job j, BlockPos s) {
		BlockPos t = j.pos();
		double best = -1;
		virtualSpot = null;
		for (int pass = 0; pass < 2 && best < 1 - 1e-9; pass++) { // the second look also counts spots that have to be built first (a pillar to climb): the cells around a trapdoor among rails are all above the ground
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					for (int dy = lookLow(j); dy <= 1; dy++) {
						BlockPos sp = t.offset(dx, dy, dz);
						if (dx == 0 && dz == 0 && dy >= -1 && dy <= 0 || sp.equals(s) || sp.above().equals(s)) continue;
						if (pass == 0 ? !c.valid(sp.getX(), sp.getY(), sp.getZ()) || walledIn(c, sp) : c.valid(sp.getX(), sp.getY(), sp.getZ()) || !buildable(c, sp)) continue;
						double v = Interact.placeMatchVirtualRobust(new Vec3(sp.getX() + 0.5, sp.getY() + 1.62, sp.getZ() + 0.5), t, j.want(), s);
						if (v > best) {
							best = v;
							virtualSpot = sp;
						}
						if (best >= 1 - 1e-9) return best;
					}
				}
			}
		}
		return best;
	}

	/** How well the job can be done from an eye at {@code eye}: -1 not at all, otherwise up to 1, meaning a placed block comes out exactly as planned. */
	private double quality(Vec3 eye, Job j) {
		if (j.isBreak()) return Interact.pickAim(j.pos(), null, eye) != null ? 1 : -1;
		BlockState want = j.want();
		if (Orient.shortCount(mc.level.getBlockState(j.pos()), want) || breaksFirst(mc.level.getBlockState(j.pos()), want)) return Interact.pickAim(j.pos(), null, eye) != null ? 1 : -1; // one more on top of the block that is there; or the block in the way comes out first (the clicks the cell allows are judged once it is empty)
		if (want.getBlock() instanceof LiquidBlock) return Interact.BucketOp.feasible(eye, j.pos(), Interact.BucketOp.Mode.PLACE);
		if (Orient.waterMissing(mc.level.getBlockState(j.pos()), want)) return Interact.BucketOp.feasible(eye, j.pos(), Interact.BucketOp.Mode.WATERLOG);
		double q = Interact.placeMatchRobust(eye, j.pos(), want);
		if (q >= 0 && wantsWater(want) && interactive(want)) q = Math.min(q, Interact.BucketOp.feasible(eye, j.pos(), Interact.BucketOp.Mode.PLACE)); // the water goes in first
		return q;
	}

	/**
	 * Standing cells near the job from which it is really doable, the best ones for the orientation of the block first; falls
	 * back to "anywhere within reach". Null when the spot the player is on ({@code here}, its quality) is as good as any.
	 */
	private Goal standGoal(Job j, double here) {
		var c = Bot.nav.live();
		BlockPos t = j.pos();
		BlockPos me = mc.player.blockPosition();
		List<BlockPos> ok = new ArrayList<>();
		List<Double> quality = new ArrayList<>();
		double bestQ = -1;
		for (int pass = 0; pass < 2 && bestQ < 1 - 1e-9; pass++) { // the second look also counts spots that have to be built first: a pillar to climb, a floor to bridge
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					for (int dy = lookLow(j); dy <= (pass == 0 ? 3 : 1); dy++) { // (up to three above: on top of what stands beside the job, a piston or a wall, when that is a floor already)
						BlockPos sp = t.offset(dx, dy, dz);
						if (badStands.contains(sp) || dx == 0 && dz == 0 && dy >= -1 && dy <= 0) continue;
						if (pass == 0 ? !c.valid(sp.getX(), sp.getY(), sp.getZ()) || walledIn(c, sp) : c.valid(sp.getX(), sp.getY(), sp.getZ()) || !buildable(c, sp)) continue;
						double q = quality(new Vec3(sp.getX() + 0.5, sp.getY() + 1.62, sp.getZ() + 0.5), j);
						if (q < 0) continue;
						ok.add(sp);
						quality.add(q);
						bestQ = Math.max(bestQ, q);
					}
				}
			}
		}
		lastBestQ = bestQ;
		lastBestSpot = null;
		if (here >= 0 && (ok.isEmpty() || here >= bestQ - 1e-9)) return null;
		if (ok.isEmpty()) return new Goal.Reach(t, j.isBreak() ? Math.min(3.8, Interact.reach() - 0.4) : Math.min(3.6, Interact.reach() - 0.6));
		List<BlockPos> best = new ArrayList<>();
		for (int i = 0; i < ok.size(); i++) if (quality.get(i) >= bestQ - 1e-9) best.add(ok.get(i));
		best.sort(Comparator.comparingDouble(sp -> sp.distSqr(me)));
		lastBestSpot = best.get(0);
		List<Goal> goals = new ArrayList<>();
		for (int i = 0; i < Math.min(12, best.size()); i++) goals.add(new Goal.At(best.get(i).getX(), best.get(i).getY(), best.get(i).getZ()));
		return new Goal.Any(goals);
	}

	/**
	 * A cell that is not standable yet but could be made so on the way: the body fits, the floor cell is free and no cell of the schematic,
	 * and a block can be placed there (against something next to it, or on top of a column that leads down to the ground).
	 */
	private static boolean buildable(com.humanisedbaritone.path.Ctx c, BlockPos sp) {
		int x = sp.getX(), y = sp.getY(), z = sp.getZ();
		if (!c.allowPlace || !c.hasBlocks || !c.passable(x, y, z) || !c.passable(x, y + 1, z) || c.reserved(x, y, z) || c.reserved(x, y + 1, z)) return false;
		if (!c.replaceable(x, y - 1, z) || c.reserved(x, y - 1, z)) return false;
		if (c.hasSupport(x, y - 1, z)) return true;
		for (int fy = y - 2; fy >= y - 10; fy--) {
			if (c.canStandOn(x, fy, z)) return true; // the ground: a pillar can be built up from there
			if (!c.replaceable(x, fy, z) || c.reserved(x, fy, z)) return false;
		}
		return false;
	}

	/** A pit: no side to walk out of. Working from there means being stuck in it (the bot cannot always climb out of a hole between chests and hoppers). */
	private static boolean walledIn(com.humanisedbaritone.path.Ctx c, BlockPos sp) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos n = sp.relative(d);
			if (c.passable(n.getX(), n.getY(), n.getZ()) && c.passable(n.getX(), n.getY() + 1, n.getZ())) return false;
		}
		return true;
	}

	/** Gives a job up only after it failed a few times; placements occasionally miss for no lasting reason. */
	private void giveUp(Job j, String why) {
		if (tempJobs.contains(j)) { // a temporary block that could not be put down: the rest of its chain is useless, and the job it was for tries another way
			Log.debug("temporary block at " + j.pos() + " could not be placed - " + why);
			support.clear();
			Bot.nav.cancel();
			ph = Ph.PICK;
			return;
		}
		int n = tries.merge(j.pos(), why.equals("no workable position") ? 3 : 1, Integer::sum); // a spot that gives no view is not a matter of luck
		retryLater.add(j.pos()); // looked at again after the next round if the world has moved on: the floor a repeater or a wire needs may be there by then
		Log.debug("job " + (n >= 3 ? "skipped" : "will be retried") + ": " + j.pos() + " " + (j.isBreak() ? "break" : j.want()) + " - " + why);
		if (n >= 3) skipped.add(j.pos());
		Bot.nav.cancel();
		ph = Ph.PICK;
	}

	private void startWork() {
		ops.clear();
		BlockPos pos = cur.pos();
		BlockState want = cur.want();
		if (cur.isBreak()) {
			ops.add(new Interact.BreakOp(pos));
			ph = Ph.WORK;
			return;
		}
		BlockState have = mc.level.getBlockState(pos);
		if (Orient.shortCount(have, want) && (!wantsWater(want) || Orient.built(have, want) || have.getValue(BlockStateProperties.WATERLOGGED))) { // the block stands, one more of it goes on top: the sea pickles of a pickle farm, four to a cell
			ops.add(Interact.PlaceOp.stacking(pos, want));
			ph = Ph.WORK;
			return;
		}
		if (breaksFirst(have, want)) {
			ops.add(new Interact.BreakOp(pos)); // something that does not belong is there (a coral that died without water, a block turned the wrong way, a temporary support): out it goes, then the right one
			have = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		}
		if (want.getBlock() instanceof LiquidBlock) ops.add(new Interact.BucketOp(pos, Interact.BucketOp.Mode.PLACE));
		else if (Orient.waterMissing(have, want)) ops.add(new Interact.BucketOp(pos, Interact.BucketOp.Mode.WATERLOG)); // it stands, only the water is missing
		else if (wantsWater(want) && interactive(want)) { // a click with the bucket would open a chest: the water goes in first, and the chest into the water
			ops.add(new Interact.BucketOp(pos, Interact.BucketOp.Mode.PLACE));
			ops.add(new Interact.PlaceOp(pos, want));
		} else if (wantsWater(want)) {
			ops.add(new Interact.PlaceOp(pos, want));
			ops.add(new Interact.BucketOp(pos, Interact.BucketOp.Mode.WATERLOG));
		} else ops.add(new Interact.PlaceOp(pos, want));
		ph = Ph.WORK;
	}

	private boolean finish() {
		if (resweep != null && sweeps < 24) { // look at the world again: a scaffold, or a block somebody else put in the way, still needs a job
			if (done > doneAtSweep) { // the world has moved on since the last round: what had to wait (a top slab that needs the layer above, water that needs the pond sealed) may be possible now
				skipped.removeAll(retryLater);
				for (BlockPos p : retryLater) {
					tries.remove(p);
					placedAt.remove(p);
				}
				retryLater.clear();
			}
			List<Job> more = new ArrayList<>();
			for (Job j : resweep.get()) if (!skipped.contains(j.pos())) more.add(j);
			if (!more.isEmpty() && done > doneAtSweep) {
				doneAtSweep = done;
				sweeps++;
				Log.debug("sweep " + sweeps + ": " + more.size() + " more job(s)");
				jobs.clear();
				jobs.addAll(more);
				indexJobs();
				total = done + more.size();
				deferred.clear();
				ph = Ph.PICK;
				return false;
			}
		}
		if (!retryLater.isEmpty()) Log.warn(retryLater.size() + " block(s) left undone: no spot gave them the planned facing or half, or a way to reach them, or (water) the pond was not sealed.");
		if (!missing.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (var e : missing.entrySet()) sb.append(sb.length() > 0 ? ", " : "").append(e.getValue()).append("x ").append(e.getKey().getDescriptionId().replaceAll("^.*\\.", ""));
			Log.warn("Ran out of materials. Still needed: " + sb);
		}
		int left = 0;
		for (Job j : jobs) if (!satisfied(j)) left++;
		Log.info(title + ": " + Math.min(done, total) + " of " + total + " done" + (left > 0 ? ", " + left + " could not be done." : "."));
		return true;
	}
}
