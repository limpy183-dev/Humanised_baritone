package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.behave.Combat;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.world.Ent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/** Looks for hostile mobs and fights them; wanders when there are none. */
public final class HuntTask extends Task {
	private static final Minecraft mc = Minecraft.getInstance();
	private int ticks, kills;
	private boolean wandering;

	@Override public String name() {
		return "Hunting mobs";
	}

	@Override public String tag() {
		return "HUNT";
	}

	@Override public void start() {
		Combat.forceHunt = true;
		Explorer.reset();
	}

	@Override public void stop() {
		Combat.forceHunt = false;
		Bot.nav.cancel();
	}

	@Override public String status() {
		return wandering ? "searching for mobs" : "moving in on a target";
	}

	@Override public boolean tick() {
		Explorer.mark();
		ticks++;
		LivingEntity near = Ent.living(48, Ent::isTarget).stream().findFirst().orElse(null);
		if (near != null) {
			wandering = false;
			BlockPos b = near.blockPosition();
			if (!(Bot.nav.goal() instanceof Goal.Near n) || Math.abs(n.x() - b.getX()) + Math.abs(n.z() - b.getZ()) > 3) Bot.nav.setGoal(new Goal.Near(b.getX(), b.getY(), b.getZ(), 3));
			Bot.nav.tick();
			return false;
		}
		wandering = true;
		Nav.State s = Bot.nav.tick();
		if (Bot.nav.goal() == null || s == Nav.State.ARRIVED || s == Nav.State.FAILED || ticks % 1200 == 0) {
			Goal g = Explorer.pick(25, 60);
			if (g != null) Bot.nav.setGoal(g);
		}
		return false;
	}
}
