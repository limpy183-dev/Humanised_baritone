package com.humanisedbaritone.behave;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Interact;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Ctx;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import com.humanisedbaritone.world.Inv;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The water bucket clutch: look down just before the ground and place water so the landing is soft, then pick the
 * water up again. Used for every long fall, planned or accidental.
 */
public final class Clutch {
	private static final Minecraft mc = Minecraft.getInstance();
	/** How far inside the rim of a surface a landing should be: the whole sole (0.3 either side) and a little for the aim. */
	private static final double RIM = 0.35;
	private static int lastSteer = -9;
	/** The water is out and still has to be picked up, from the cell {@code water}. */
	private static boolean placed;
	private static BlockPos water;
	private static int wait, equipDelay;

	private Clutch() {
	}

	public static boolean busy() {
		return placed;
	}

	public static void reset() {
		placed = false;
		wait = equipDelay = 0;
	}

	private static boolean hasBucket() {
		return Inv.find(s -> s.is(Items.WATER_BUCKET)) >= 0;
	}

	/** Whether a fall in progress is long enough (and we are able) to be worth a clutch. */
	public static boolean wanted() {
		LocalPlayer p = mc.player;
		if (busy()) return true;
		if (!Settings.CLUTCH.on() || p.onGround() || p.isInWater() || p.onClimbable() || p.getAbilities().flying) return false;
		return p.fallDistance > 2.6 && hasBucket();
	}

	/** Runs the clutch for this tick; returns true while it controls the camera and buttons. */
	public static boolean tick() {
		LocalPlayer p = mc.player;
		if (!wanted()) {
			reset(); // a finished clutch must not leave anything behind: the next fall starts afresh
			return false;
		}
		if (!placed) {
			if (!p.getMainHandItem().is(Items.WATER_BUCKET)) {
				if (equipDelay > 0) {
					equipDelay--;
				} else {
					int slot = Inv.find(s -> s.is(Items.WATER_BUCKET));
					if (slot < 0) return false;
					if (slot < 9) Inv.select(slot);
					else Inv.toHotbar(slot);
					equipDelay = Rng.delayTicks(Settings.ACT_SWAP.d() * 0.5) + 1;
				}
			}
			Bot.look.aim(p.getYRot(), 88, 1.0, 4);
			if (Settings.CLUTCH_STEER.on()) steer(p);
			var down = mc.level.clip(new ClipContext(p.position(), p.position().add(0, -10, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
			double dist = down.getType() == HitResult.Type.BLOCK ? p.getY() - down.getLocation().y : 99;
			var h = Interact.crosshair();
			if (p.getMainHandItem().is(Items.WATER_BUCKET) && dist <= 3.6 && h != null && h.getLocation().distanceTo(p.getEyePosition()) <= Interact.reach() && Bot.look.error() < 14) {
				Bot.ctl.clickUse();
				placed = true;
				water = h.getBlockPos().relative(h.getDirection());
				wait = 0;
			}
			return true;
		}
		if (p.onGround() || p.isInWater()) {
			// Not straight down: a drop with a run-up lands beside the cell the water went into, and a bucket takes up a source block only, not the flow
			aimAtWater(p);
			if (p.onGround()) Bot.ctl.sneak = true; // the water runs off the edge of a small platform and its current carries the player along: a sneaking one stays on
			if (++wait > Rng.delayTicks(280) && Bot.look.error() < 6 && onSource(p)) {
				Bot.ctl.clickUse();
				reset(); // done; this tick still owns the buttons for the click
			} else if (wait > 100) { // the water is gone or out of reach: do not hold the controls for ever
				Log.debug("clutch: could not pick the water up again");
				reset();
			}
		}
		return true;
	}

	/**
	 * Landing correction. The water goes where the crosshair, pointing down from the middle of the player, meets a block, so a
	 * player who comes down on the rim of one, middle beyond its edge, has nothing to put it on. Predicts where the fall ends and,
	 * while that is not well inside a flat surface, taps the movement keys towards the middle of the nearest block of that
	 * height. Redone every tick: the keys come off as soon as the free drift would land well, so it is a nudge, not a detour.
	 */
	private static void steer(LocalPlayer p) {
		Vec3 land = touchdown(p);
		if (land == null || flat(p, land.x, land.z, land.y)) return;
		Ctx c = Bot.nav.live();
		if (bad(c, land.x, land.y + 0.01, land.z)) return; // water or lava to come down in: not an edge problem
		AABB sole = p.getBoundingBox();
		double best = 1e9, tx = 0, tz = 0;
		// ponytail: block middles within 2 blocks only, so a top narrower than a block (slab edge, fence) is left alone; search finer if that ever matters
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				double x = Math.floor(land.x) + dx + 0.5, z = Math.floor(land.z) + dz + 0.5, d = Math.hypot(x - land.x, z - land.z);
				if (d >= best || !flat(p, x, z, land.y) || bad(c, x, land.y - 0.01, z) || bad(c, x, land.y + 0.01, z)) continue;
				if (!mc.level.noCollision(p, sole.move(x - p.getX(), land.y + 0.01 - sole.minY, z - p.getZ()))) continue;
				best = d;
				tx = x;
				tz = z;
			}
		}
		if (best > 1e8) return; // no block of this height with room to spare near enough: leave it
		if (Settings.DEBUG.on() && p.tickCount - lastSteer > 1) Log.debug(String.format("clutch: would land at %.2f %.2f, on a rim: steering for %.2f %.2f", land.x, land.z, tx, tz));
		lastSteer = p.tickCount;
		Bot.ctl.walkTowards(Math.toDegrees(Math.atan2(land.x - tx, tz - land.z)), p.getYRot());
	}

	/** Where the player comes down if nothing more is pressed (middle of the sole), played out with the game's own air physics; null if not within 3 seconds. */
	private static Vec3 touchdown(LocalPlayer p) {
		AABB box = p.getBoundingBox();
		Vec3 v = p.getDeltaMovement();
		double gravity = p.hasEffect(MobEffects.SLOW_FALLING) ? Math.min(p.getGravity(), 0.01) : p.getGravity();
		for (int t = 0; t < 60; t++) {
			Vec3 m = Entity.collideBoundingBox(p, v, box, mc.level, List.of());
			box = box.move(m);
			if (v.y < 0 && m.y != v.y) return new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
			v = new Vec3(m.x == v.x ? v.x * 0.91 : 0, (v.y - gravity) * 0.98, m.z == v.z ? v.z * 0.91 : 0); // a wall stops that axis; the air takes the rest
		}
		return null;
	}

	/** Whether a sole centred on (x, z) rests wholly on surface at height y, with {@link #RIM} to spare all round. */
	private static boolean flat(LocalPlayer p, double x, double z, double y) {
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				double px = x + i * RIM, pz = z + j * RIM;
				var h = mc.level.clip(new ClipContext(new Vec3(px, y + 0.5, pz), new Vec3(px, y - 0.5, pz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
				if (h.getType() != HitResult.Type.BLOCK || Math.abs(h.getLocation().y - y) > 0.02) return false;
			}
		}
		return true;
	}

	/** Water, lava, fire or a hazard block at the point: nowhere to steer to. */
	private static boolean bad(Ctx c, double x, double y, double z) {
		BlockState s = c.get(Mth.floor(x), Mth.floor(y), Mth.floor(z));
		int f = Blk.f(s);
		return (f & Blk.LIQUID) != 0 || c.hazard(s, f);
	}

	/**
	 * Looks at the water the way a person does: wherever the crosshair already is while that is on a source, else at the nearest point of the block.
	 * The middle of the cell is no target: standing right above it there is no heading to it, the yaw changes with every hair of drift and the
	 * camera chases it, so the pick-up came late and, in an edge cell, only after the current had carried the player over the edge. Nor is a spot
	 * well inside the cell, which is what a crosshair near the rim was sent to: a few degrees of view, but for a camera that is nearly straight down
	 * a turn of a hundred degrees of yaw, which kept {@code look.error()} above its limit for twenty ticks while the current carried the player away.
	 */
	private static void aimAtWater(LocalPlayer p) {
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		double top = water.getY() + 0.8, x = water.getX() + 0.5, z = water.getZ() + 0.5; // a source is 8/9 of a block high
		if (eye.y <= top || onSource(p)) { // in the water, or the crosshair is on a source already: stay as it is
			Bot.look.aim(p.getYRot(), p.getXRot(), 0.6, 3);
			return;
		}
		if (look.y < -0.05) { // where the line of sight crosses the surface
			double t = (top - eye.y) / look.y;
			x = eye.x + look.x * t;
			z = eye.z + look.z * t;
		}
		double dx = Mth.clamp(x, water.getX() + 0.2, water.getX() + 0.8) - eye.x, dz = Mth.clamp(z, water.getZ() + 0.2, water.getZ() + 0.8) - eye.z, h = Math.hypot(dx, dz);
		Bot.look.aim(h < 0.1 ? p.getYRot() : Math.toDegrees(Math.atan2(-dx, dz)), Math.toDegrees(-Math.atan2(top - eye.y, h)), 0.6, 3);
	}

	/** What the empty bucket would take up from where the camera points: a source block of water. */
	private static boolean onSource(LocalPlayer p) {
		Vec3 eye = p.getEyePosition();
		var hit = mc.level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(Interact.reach())), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
		return hit.getType() == HitResult.Type.BLOCK && mc.level.getFluidState(hit.getBlockPos()).isSource();
	}
}
