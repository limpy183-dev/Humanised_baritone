package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.world.Ent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** After something was broken: wait a moment for the drops to appear, then walk over them. */
final class Looter {
	private static final Minecraft mc = Minecraft.getInstance();
	private final BlockPos center;
	private int wait, ticks;

	Looter(BlockPos center, int waitTicks) {
		this.center = center;
		this.wait = waitTicks;
	}

	/** Returns true when there is nothing more to pick up. */
	boolean tick() {
		if (wait-- > 0) return false;
		ticks++;
		ItemEntity item = null;
		for (ItemEntity e : Ent.items(7)) {
			if (e.position().distanceTo(Vec3.atCenterOf(center)) < 4.5) {
				item = e;
				break;
			}
		}
		if (item == null || ticks > 160) {
			Bot.nav.cancel();
			return true;
		}
		BlockPos b = Ent.cellOf(item);
		if (!(Bot.nav.goal() instanceof Goal.Near n && n.x() == b.getX() && n.z() == b.getZ())) Bot.nav.setGoal(new Goal.Near(b.getX(), b.getY(), b.getZ(), 0.9));
		Nav.State s = Bot.nav.tick();
		if (s == Nav.State.FAILED || s == Nav.State.ARRIVED && ticks > 30) ticks = 999;
		return false;
	}
}
