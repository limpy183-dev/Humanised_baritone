package com.humanisedbaritone.behave;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Ctx;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.Tasks;
import com.humanisedbaritone.world.Blk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/** Reflexes that override everything else: get out of lava, put out flames, come up for air, cushion falls. */
public final class Safety {
	private static final Minecraft mc = Minecraft.getInstance();
	private static int waterSearch;

	private Safety() {
	}

	public static boolean tick() {
		LocalPlayer p = mc.player;
		if (!p.isAlive()) return false;

		if (Clutch.tick()) {
			Bot.markAlert();
			return true;
		}
		if (Settings.LAVA.on() && p.isInLava() && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) {
			Bot.markAlert();
			return escape(true);
		}
		if (Settings.FIRE.on() && p.isOnFire() && !p.isInWater() && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) {
			Bot.markAlert();
			if (seekWater()) return true;
		} else {
			waterSearch = 0;
		}
		if (Settings.DROWN.on() && p.isEyeInFluid(FluidTags.WATER) && p.getAirSupply() < Settings.AIR_MIN.i() * 20) {
			Bot.markAlert();
			Bot.ctl.jump = true;
			Bot.look.aim(p.getYRot(), -50, 0.9, 10);
			if (p.getAirSupply() < 40) {
				var nav = Bot.navAux;
				if (nav.state() == com.humanisedbaritone.path.Nav.State.FAILED || nav.goal() == null) {
					Vec3 a = airPocket();
					if (a != null) nav.setGoal(new Goal.At((int) Math.floor(a.x), (int) Math.floor(a.y), (int) Math.floor(a.z)));
				}
				if (nav.active()) nav.tick();
			}
			return true;
		}
		if (p.getHealth() <= Settings.PANIC_HP.d() && !Combat.fighting()) {
			Bot.markAlert();
			if (Settings.STOP_LOW.on() && !Eating.canHeal()) {
				Log.warn("Health is critical and nothing can heal it - stopping.");
				Tasks.stopAll();
				Bot.setEnabled(false);
				return false;
			}
		}
		return false;
	}

	private static long endermanCheckedAt;
	private static boolean endermanCached;

	/** An enderman within sight range: the camera should stay off its head. */
	public static boolean endermanNear() {
		long t = mc.level.getGameTime();
		if (Math.abs(t - endermanCheckedAt) > 10) { // abs: a world with a lower game time than the last one must not freeze the cache
			endermanCheckedAt = t;
			endermanCached = !com.humanisedbaritone.world.Ent.living(40, e -> com.humanisedbaritone.world.Ent.key(e).equals("minecraft:enderman")).isEmpty();
		}
		return endermanCached;
	}

	/** Moves towards the nearest cell that is not lava. */
	private static boolean escape(boolean jump) {
		LocalPlayer p = mc.player;
		Ctx c = Bot.nav.live();
		int px = p.getBlockX(), py = p.getBlockY(), pz = p.getBlockZ();
		double best = 1e9;
		double bestHeading = p.getYRot();
		for (int r = 1; r <= 6; r++) {
			for (int k = 0; k < 16; k++) {
				double a = k * Math.PI / 8;
				int x = px + (int) Math.round(-Math.sin(a) * r), z = pz + (int) Math.round(Math.cos(a) * r);
				for (int dy = 1; dy >= -1; dy--) {
					int y = py + dy;
					if (!Blk.isLava(c.get(x, y, z)) && !Blk.isLava(c.get(x, y - 1, z)) && c.passable(x, y, z) && c.passable(x, y + 1, z) && c.canStandOn(x, y - 1, z)) {
						double d = r + Math.abs(dy) * 0.5;
						if (d < best) {
							best = d;
							bestHeading = Math.toDegrees(Math.atan2(-(x + 0.5 - p.getX()), z + 0.5 - p.getZ()));
						}
					}
				}
			}
			if (best < 1e8) break;
		}
		Bot.look.aim(bestHeading, 12, 1.0, 12);
		Bot.ctl.walkTowards(bestHeading, p.getYRot());
		Bot.ctl.jump = jump;
		Bot.ctl.sprint = true;
		return true;
	}

	private static boolean seekWater() {
		var nav = Bot.navAux;
		if (waterSearch++ % 40 == 0 || nav.goal() == null) {
			LocalPlayer p = mc.player;
			Ctx c = Bot.nav.live();
			int px = p.getBlockX(), py = p.getBlockY(), pz = p.getBlockZ();
			double best = 1e9;
			Goal g = null;
			for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) for (int dy = -4; dy <= 3; dy++) {
				int x = px + dx, y = py + dy, z = pz + dz;
				if (!c.water(x, y, z) || !c.valid(x, y, z)) continue;
				double d = dx * dx + dz * dz + dy * dy * 2;
				if (d < best) {
					best = d;
					g = new Goal.At(x, y, z);
				}
			}
			if (g == null) return false;
			nav.setGoal(g);
		}
		nav.tick();
		return nav.active();
	}

	private static Vec3 airPocket() {
		LocalPlayer p = mc.player;
		Ctx c = Bot.nav.live();
		int px = p.getBlockX(), py = p.getBlockY(), pz = p.getBlockZ();
		double best = 1e9;
		Vec3 out = null;
		for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int dy = -2; dy <= 12; dy++) {
			int x = px + dx, y = py + dy, z = pz + dz;
			if (c.get(x, y + 1, z).isAir() && c.passable(x, y, z) && c.passable(x, y + 1, z) && (c.water(x, y, z) || c.canStandOn(x, y - 1, z))) {
				double d = dx * dx + dz * dz + dy * dy;
				if (d < best) {
					best = d;
					out = new Vec3(x, y, z);
				}
			}
		}
		return out;
	}
}
