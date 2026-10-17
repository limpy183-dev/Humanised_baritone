package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.human.Gaze;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Keep close to a player or any living entity, varying the distance the way a person wandering behind a friend would. */
public final class FollowTask extends Task {
	private static final Minecraft mc = Minecraft.getInstance();
	private final String name;
	private int targetId = -1;
	private double want = 3;
	private int retune, lostTicks;
	private BlockPos goalPos;

	public FollowTask(String name) {
		this.name = name;
	}

	@Override public String name() {
		return "Following " + name;
	}

	@Override public String tag() {
		return "FOLLOW";
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	private LivingEntity find() {
		Entity e = targetId >= 0 ? mc.level.getEntity(targetId) : null;
		if (e instanceof LivingEntity le && le.isAlive()) return le;
		for (AbstractClientPlayer p : mc.level.players()) {
			if (p != mc.player && p.getName().getString().equalsIgnoreCase(name)) {
				targetId = p.getId();
				return p;
			}
		}
		for (Entity en : mc.level.entitiesForRendering()) {
			if (en instanceof LivingEntity le && en != mc.player && le.isAlive() && com.humanisedbaritone.world.Ent.key(en).endsWith(":" + name.toLowerCase())) {
				targetId = en.getId();
				return le;
			}
		}
		return null;
	}

	@Override public String status() {
		LivingEntity t = find();
		return t == null ? "cannot see " + name : String.format("%.0f blocks away", Math.sqrt(t.distanceToSqr(mc.player)));
	}

	@Override public boolean tick() {
		LivingEntity t = find();
		if (t == null) {
			if (++lostTicks > 20 * 60 * 3) return true;
			Bot.nav.cancel();
			Gaze.tick(false, false);
			return false;
		}
		lostTicks = 0;
		if (--retune <= 0) {
			retune = Rng.range(200, 600);
			want = Rng.range(2.0, 4.5);
		}
		double d = Math.sqrt(t.distanceToSqr(mc.player));
		if (d > want + 1.2) {
			BlockPos b = t.blockPosition();
			if (goalPos == null || goalPos.distSqr(b) > 4 || !Bot.nav.active()) {
				goalPos = b;
				Bot.nav.setGoal(new Goal.Near(b.getX(), b.getY(), b.getZ(), want));
			}
			Bot.nav.tick();
		} else {
			if (Bot.nav.active()) Bot.nav.cancel();
			goalPos = null;
			Gaze.tick(false, false);
			if (d < want + 0.3) Bot.look.aimAt(t.getEyePosition(), 0.15, 25);
		}
		return false;
	}
}
