package com.humanisedbaritone.world;

import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Entity queries used by combat, safety and the pick-up logic. */
public final class Ent {
	private static final Minecraft mc = Minecraft.getInstance();

	private Ent() {
	}

	public static String key(Entity e) {
		var k = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
		return k == null ? "" : k.toString();
	}

	public static boolean isTarget(Entity e) {
		String k = key(e);
		return Settings.TARGETS.has(k) && !Settings.IGNORE.has(k);
	}

	public static boolean isIgnored(Entity e) {
		return Settings.IGNORE.has(key(e));
	}

	public static List<LivingEntity> living(double radius, Predicate<LivingEntity> filter) {
		List<LivingEntity> out = new ArrayList<>();
		if (mc.level == null || mc.player == null) return out;
		double r2 = radius * radius;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player || !(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator()) continue;
			if (le.distanceToSqr(mc.player) > r2) continue;
			if (filter.test(le)) out.add(le);
		}
		out.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
		return out;
	}

	/** Dropped items worth walking to: not junk and not something the player just threw away. */
	public static List<ItemEntity> items(double radius) {
		return items(radius, false);
	}

	public static List<ItemEntity> items(double radius, boolean all) {
		List<ItemEntity> out = new ArrayList<>();
		if (mc.level == null || mc.player == null) return out;
		double r2 = radius * radius;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity ie) || !ie.isAlive() || ie.distanceToSqr(mc.player) > r2 || ie.getItem().isEmpty()) continue;
			if (!all) {
				if (ie.getOwner() == mc.player && ie.tickCount < 6000) continue; // the player threw it away on purpose
				var k = BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
				if (Settings.JUNK.on() && k != null && Settings.JUNK_ITEMS.has(k.toString())) continue;
			}
			out.add(ie);
		}
		out.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
		return out;
	}

	/** Distance from the player's eyes to the closest point of the entity's hitbox. */
	public static double reachTo(Entity e) {
		AABB b = e.getBoundingBox();
		Vec3 eye = mc.player.getEyePosition();
		double dx = Math.max(Math.max(b.minX - eye.x, 0), eye.x - b.maxX);
		double dy = Math.max(Math.max(b.minY - eye.y, 0), eye.y - b.maxY);
		double dz = Math.max(Math.max(b.minZ - eye.z, 0), eye.z - b.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** A point on the upper body where a person would aim, with a little random offset. */
	public static Vec3 aimPoint(Entity e, double spread) {
		AABB b = e.getBoundingBox();
		double h = b.getYsize(), w = b.getXsize();
		double fy = Math.min(0.92, Math.max(0.35, 0.68 + Rng.gauss() * 0.1 * spread));
		double ox = Rng.gauss() * w * 0.14 * spread, oz = Rng.gauss() * b.getZsize() * 0.14 * spread;
		return new Vec3(b.getCenter().x + ox, b.minY + h * fy, b.getCenter().z + oz);
	}

	/** The cell a player would stand in to pick an item up (an item resting on a slab or farmland sits low in its block). */
	public static net.minecraft.core.BlockPos cellOf(Entity e) {
		return net.minecraft.core.BlockPos.containing(e.getX(), e.getY() + 0.3, e.getZ());
	}

	public static boolean isPlayer(Entity e) {
		return e instanceof Player;
	}
}
