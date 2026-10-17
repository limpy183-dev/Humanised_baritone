package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.world.Ent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

/** Walk over dropped items to pick them up. */
public final class CollectTask extends Task {
	private static final Minecraft mc = Minecraft.getInstance();
	private final double radius;
	private final boolean silent, all;
	private final Set<Integer> skip = new HashSet<>();
	private ItemEntity cur;
	private int ticks, picked;
	private final BlockPos origin;

	/** @param all also take items the player dropped and junk (an explicit "collect" command) */
	public CollectTask(double radius, boolean silent, boolean all) {
		this.radius = radius;
		this.silent = silent;
		this.all = all;
		this.origin = mc.player.blockPosition();
	}

	@Override public String name() {
		return "Collecting items";
	}

	@Override public String tag() {
		return "COLLECT";
	}

	@Override public boolean tick() {
		if (cur == null || !cur.isAlive() || cur.isRemoved() || ticks > 360) {
			if (cur != null && ticks > 360) skip.add(cur.getId());
			if (cur != null && !cur.isAlive()) picked++;
			cur = null;
			ticks = 0;
			List<ItemEntity> items = Ent.items(radius, all);
			for (ItemEntity e : items) {
				if (skip.contains(e.getId()) || Math.abs(e.getY() - mc.player.getY()) > 8 || e.getItem().isEmpty()) continue;
				cur = e;
				break;
			}
			if (cur == null) {
				if (!silent || picked > 0) Log.info(picked > 0 ? "Picked up items." : "No items nearby.");
				return true;
			}
			BlockPos b = Ent.cellOf(cur);
			Bot.nav.setGoal(new Goal.Near(b.getX(), b.getY(), b.getZ(), 0.9));
		}
		ticks++;
		BlockPos b = Ent.cellOf(cur);
		if (Bot.nav.goal() instanceof Goal.Near n && (n.x() != b.getX() || n.z() != b.getZ()) && ticks % 10 == 0) Bot.nav.setGoal(new Goal.Near(b.getX(), b.getY(), b.getZ(), 0.9));
		Nav.State s = Bot.nav.tick();
		if (s == Nav.State.FAILED) {
			skip.add(cur.getId());
			cur = null;
		} else if (s == Nav.State.ARRIVED && ticks > 4 && cur != null && cur.distanceTo(mc.player) > 1.4) {
			// stand still next to it: items are picked up when close enough; if not, try the next one
			if (ticks > 40) skip.add(cur.getId());
		}
		return false;
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	@Override public String status() {
		return cur == null ? "looking for items" : "walking to an item";
	}
}
