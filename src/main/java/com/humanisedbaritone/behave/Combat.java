package com.humanisedbaritone.behave;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import com.humanisedbaritone.world.Ent;
import com.humanisedbaritone.world.Inv;
import com.humanisedbaritone.world.Tools;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fighting like a person: pick a target, walk up to it while looking at it, swing when the crosshair really is on it and
 * the weapon has recharged, sometimes jump for a critical hit, tap the forward key to keep knockback, strafe, hit and
 * back off from creepers, and break away when health gets low.
 */
public final class Combat {
	private static final Minecraft mc = Minecraft.getInstance();

	private static LivingEntity target;
	private static int retarget, lastClick = -100, strafeTicks, strafeDir = 1, wtap, retreat, critWindow, swapDelay, aimAge, fleeTicks;
	private static double nextNeed = 0.92, reachPref = 2.8;
	private static Vec3 aimOff = Vec3.ZERO;
	/** For the debug log: when the current fight began, how many swings were made and how much health the player had. */
	private static int fightStart, swings;
	private static float fightHp;
	private static boolean fleeing;
	/** Entity ids that could not be reached, and until which game tick they are ignored. */
	private static final java.util.Map<Integer, Integer> unreachable = new java.util.HashMap<>();
	/** Set by the hunt and guard tasks so they fight even when the matching setting is off. */
	public static boolean forceHunt, forceDefend;

	private Combat() {
	}

	public static LivingEntity target() {
		return target;
	}

	public static boolean fighting() {
		return target != null || fleeing;
	}

	public static void reset() {
		lastClick = -100; // a new life starts counting ticks from zero again, which must not keep the weapon locked
		unreachable.clear();
		target = null;
		fleeing = false;
		fleeTicks = 0;
		if (Bot.navAux.active()) Bot.navAux.cancel();
	}

	private static boolean fightable(LivingEntity e, boolean attacker) {
		if (e instanceof Player pl) return Settings.HIT_PLAYERS.on() && attacker && !pl.isCreative() && !pl.isSpectator();
		String k = Ent.key(e);
		if (k.equals("minecraft:warden")) return false;
		return attacker || !Ent.isIgnored(e);
	}

	private static LivingEntity choose() {
		LocalPlayer p = mc.player;
		unreachable.values().removeIf(t -> t < p.tickCount);
		LivingEntity attacker = p.getLastHurtByMob();
		int age = p.tickCount - p.getLastHurtByMobTimestamp();
		boolean defend = Settings.DEFEND.on() || forceDefend;
		// a creeper that gets close is more urgent than whatever is being fought
		if (defend) for (LivingEntity e : Ent.living(5.5, x -> x instanceof Creeper && x != target && fightable(x, false) && !unreachable.containsKey(x.getId()))) return e;
		// stay with the enemy at hand: switching between two of them every time one lands a hit wastes every swing
		if (target != null && target.isAlive() && !target.isRemoved() && !unreachable.containsKey(target.getId()) && target.distanceToSqr(p) < 10 * 10) return target;
		if (defend && attacker != null && attacker.isAlive() && age < 120 && attacker.distanceToSqr(p) < 18 * 18 && fightable(attacker, true)) return attacker;
		double near = defend ? (forceDefend ? 7 : 4.5) : 0;
		double hunt = Settings.HUNT.on() || forceHunt ? Settings.HUNT_RANGE.d() : 0;
		double radius = Math.max(near, hunt);
		if (radius <= 0) return null;
		LivingEntity best = null;
		double bestD = 1e9;
		for (LivingEntity e : Ent.living(radius, x -> Ent.isTarget(x) && fightable(x, false) && !unreachable.containsKey(x.getId()))) {
			double d = Math.sqrt(e.distanceToSqr(p));
			if ((d <= near && p.hasLineOfSight(e) || d <= hunt) && d < bestD) { // the nearest one first
				best = e;
				bestD = d;
			}
		}
		return best;
	}

	/** Returns true when combat took over the controls this tick. */
	public static boolean tick() {
		LocalPlayer p = mc.player;
		if (!Settings.COMBAT_ON.on() || !p.isAlive()) {
			if (target != null || fleeing) reset();
			return false;
		}
		if (target != null && (!target.isAlive() || target.isRemoved() || target.distanceToSqr(p) > 40 * 40)) {
			if (Settings.DEBUG.on()) Log.debug(String.format("fight: %s %s after %d ticks, %d swings, health %.1f -> %.1f", Ent.key(target), target.isAlive() ? "lost" : "killed", p.tickCount - fightStart, swings, fightHp, p.getHealth()));
			target = null;
		}
		if (--retarget <= 0) {
			retarget = 5;
			LivingEntity c = choose();
			if (c != null && c != target) {
				target = c;
				fightStart = p.tickCount;
				fightHp = p.getHealth();
				swings = 0;
				nextNeed = need();
				reachPref = Settings.REACH_ENTITY.d() - Rng.range(0, 0.3);
				aimAge = 99;
			} else if (c == null && target != null && !(Settings.HUNT.on() || forceHunt) && target.distanceToSqr(p) > 20 * 20) target = null;
		}
		if (target == null) {
			if (fleeing || Bot.navAux.active()) reset();
			return false;
		}
		// badly hurt with a golden apple at hand: eating it is worth the hits taken, and running from something that is as fast as we are is not
		if (Settings.EAT.on() && Settings.EAT_EMERGENCY.on() && (Eating.active() || Eating.emergency(p) && Inv.find(st -> st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE)) >= 0)) return false;
		// with the apple eaten its regeneration is running: that is the time to fight, and the mob would only follow
		if (p.getHealth() <= Settings.FLEE_HP.d() && !p.hasEffect(MobEffects.REGENERATION) && !(target instanceof Player && !Settings.HIT_PLAYERS.on())) return flee();
		fleeing = false;
		fleeTicks = 0;
		Bot.markAlert();
		engage(target);
		return true;
	}

	private static double need() {
		return Mth.clamp(Settings.CHARGE.d() + Rng.range(-0.05, 0.04), 0.3, 1.0);
	}

	// ---------------------------------------------------------------- fleeing

	private static boolean flee() {
		LocalPlayer p = mc.player;
		List<LivingEntity> threats = Ent.living(24, e -> e == target || Ent.isTarget(e));
		if (threats.isEmpty()) {
			reset();
			return false;
		}
		double nearest = Math.sqrt(threats.get(0).distanceToSqr(p));
		if (nearest > 10 && fleeTicks++ > 10) { // far enough: let eating and healing happen
			if (p.getHealth() > Settings.FLEE_HP.d() + 4 || nearest > 16) {
				fleeing = false;
				fleeTicks = 0;
				target = null;
				Bot.navAux.cancel();
			}
			return false;
		}
		fleeing = true;
		Bot.markAlert();
		List<Vec3> pos = new ArrayList<>();
		for (LivingEntity e : threats) pos.add(e.position());
		Bot.navAux.setGoal(new Goal.Away(pos, 20));
		Bot.navAux.tick();
		return true;
	}

	// ---------------------------------------------------------------- engaging

	private static void equipWeapon() {
		if (swapDelay > 0) {
			swapDelay--;
			return;
		}
		int slot = Tools.bestWeapon();
		if (slot < 0 || slot == Inv.selected()) return;
		if (slot < 9) Inv.select(slot);
		else Inv.toHotbar(slot);
		swapDelay = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
	}

	private static boolean hasShield() {
		ItemStack off = mc.player.getOffhandItem();
		return off.is(Items.SHIELD);
	}

	private static void engage(LivingEntity t) {
		LocalPlayer p = mc.player;
		double dist = Ent.reachTo(t);
		boolean creeper = t instanceof Creeper;
		Creeper cr = creeper ? (Creeper) t : null;
		equipWeapon();

		// creepers: keep away, or hit and back off
		if (creeper) {
			boolean lit = cr.getSwellDir() > 0 || cr.isIgnited();
			switch (Settings.CREEPER.get()) {
				case AVOID -> {
					if (dist < 7) {
						runFrom(t);
						return;
					}
					Bot.navAux.cancel();
					return;
				}
				case RETREAT -> {
					if (lit && dist < 5.5 || retreat > 0) {
						if (retreat > 0) retreat--;
						runFrom(t);
						return;
					}
				}
				default -> {
				}
			}
		}

		// where on the body to look
		if (++aimAge > 14) {
			aimAge = 0;
			Vec3 c = t.getBoundingBox().getCenter();
			Vec3 a = Ent.aimPoint(t, Settings.AIM_SPREAD.d());
			aimOff = a.subtract(c);
		}
		Vec3 aim = t.getBoundingBox().getCenter().add(aimOff);
		// moving targets: lead a little
		Vec3 vel = t.getDeltaMovement();
		aim = aim.add(vel.x * 2.0, 0, vel.z * 2.0);

		if (useBow(t, dist)) return;
		bowState = 0;
		// closing in
		if (dist > reachPref + 0.35) {
			chase(t, dist, aim);
			return;
		}
		if (Bot.navAux.active()) Bot.navAux.cancel();
		Bot.look.aimAt(aim, 0.85, 2.0);
		Bot.ctl.sprint = false;

		double yaw = p.getYRot();
		double headingTo = Math.toDegrees(Math.atan2(-(t.getX() - p.getX()), t.getZ() - p.getZ()));
		boolean faced = Math.abs(Mth.wrapDegrees(headingTo - yaw)) < 50;
		if (dist > reachPref - 0.25 && faced && wtap <= 0 && safeStep(headingTo, 0.9)) Bot.ctl.forward = true;
		// the sword reaches further than a zombie's arm: keep just outside the distance it can hit from (about 1.4 blocks) while still in reach ourselves
		if (dist < reachPref - 1.1 && safeStep(headingTo + 180, 0.9)) Bot.ctl.back = true;
		if (Settings.STRAFE.on() && dist < reachPref + 0.2 && faced) {
			if (--strafeTicks <= 0) {
				strafeTicks = Rng.range(8, 26);
				strafeDir = Rng.chance(0.5) ? 1 : -1;
				if (Rng.chance(0.25)) strafeDir = 0;
			}
			if (strafeDir > 0 && safeStep(headingTo - 90, 0.8)) Bot.ctl.left = true;
			else if (strafeDir < 0 && safeStep(headingTo + 90, 0.8)) Bot.ctl.right = true;
		}
		if (wtap > 0) {
			wtap--;
			Bot.ctl.forward = Bot.ctl.sprint = false;
		}

		double charge = p.getAttackStrengthScale(0.5F);
		boolean cooldownOk = charge >= nextNeed;
		// shield up while waiting for the weapon to recharge
		if (Settings.SHIELD.on() && hasShield() && p.getMainHandItem().getItem() != Items.SHIELD && charge < nextNeed - 0.14 && dist < 4.5 && !creeper) Bot.ctl.use = true;

		// critical hits: jump and swing on the way down
		boolean wantCrit = Settings.CRIT.on() && p.onGround() && cooldownOk && dist <= reachPref + 0.3 && critWindow <= 0 && !p.isInWater();
		if (wantCrit && Rng.chance(0.6)) {
			Bot.ctl.jump = true;
			critWindow = 14;
		}
		boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.03 && p.fallDistance > 0.0;
		boolean critReady = critWindow <= 0 || falling || critWindow < 5 || !Settings.CRIT.on();
		if (critWindow > 0) critWindow--;

		HitResult hr = p.raycastHitResult(1.0F, p);
		boolean on = hr instanceof EntityHitResult e && e.getEntity() == t;
		int gap = (int) Math.ceil(20.0 / Settings.MAX_CPS.d());
		if (on && cooldownOk && critReady && p.tickCount - lastClick >= gap && !p.isUsingItem()) {
			Bot.ctl.clickAttack();
			swings++;
			lastClick = p.tickCount;
			nextNeed = need();
			critWindow = 0;
			if (Settings.WTAP.on() && Rng.chance(0.7)) wtap = Rng.range(1, 3);
			if (creeper && Settings.CREEPER.get() == Settings.CreeperMode.RETREAT) retreat = Rng.range(10, 18);
		}
	}

	private static int bowState, bowCooldown, bowSwap;

	/** Shoots with a bow at a distant target. Returns true while the bow is in use. */
	private static boolean useBow(LivingEntity t, double dist) {
		LocalPlayer p = mc.player;
		if (!Settings.RANGED.on() || dist < Settings.RANGED_MIN.d() || dist > 36 || !p.hasLineOfSight(t)) return false;
		int bow = Inv.find(s -> s.is(Items.BOW));
		if (bow < 0 || Inv.find(s -> s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.TIPPED_ARROW)) < 0) return false;
		if (!p.getMainHandItem().is(Items.BOW)) {
			if (bowSwap-- > 0) return true;
			if (bow < 9) Inv.select(bow);
			else Inv.toHotbar(bow);
			bowSwap = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
			return true;
		}
		if (bowCooldown > 0) {
			bowCooldown--;
			Bot.look.aimAt(t.getBoundingBox().getCenter(), 0.5, 6);
			return true;
		}
		// lead the target and aim high enough to make up for the arrow dropping on the way
		double flight = Math.max(1, dist / 2.85);
		Vec3 v = t.getDeltaMovement();
		Vec3 c = t.getBoundingBox().getCenter().add(aimOff.scale(0.6));
		Vec3 aim = new Vec3(c.x + v.x * flight, c.y + 0.5 * 0.05 * flight * flight * 1.12, c.z + v.z * flight);
		Bot.look.aimAt(aim, 0.75, 1.8);
		Bot.ctl.use = true;
		bowState++;
		boolean full = p.getTicksUsingItem() >= 20 + Rng.range(0, 3);
		if (full && Bot.look.error() < 2.6) {
			Bot.ctl.use = false; // releasing the button shoots
			bowState = 0;
			bowCooldown = Rng.range(6, 16);
		}
		return true;
	}

	private static void chase(LivingEntity t, double dist, Vec3 aim) {
		LocalPlayer p = mc.player;
		Bot.look.aimAt(aim, 0.6, 3.0);
		double straight = Math.toDegrees(Math.atan2(-(t.getX() - p.getX()), t.getZ() - p.getZ()));
		if (dist < 9 && p.hasLineOfSight(t) && safeStep(straight, 1.3)) {
			if (Bot.navAux.active()) Bot.navAux.cancel();
			double heading = Math.toDegrees(Math.atan2(-(t.getX() - p.getX()), t.getZ() - p.getZ()));
			Bot.ctl.walkTowards(heading, p.getYRot());
			if (Settings.SPRINT.on() && dist > 3.4 && p.getFoodData().getFoodLevel() > 6) Bot.ctl.sprint = true;
			if (p.horizontalCollision || t.getY() - p.getY() > 0.7 && dist < 3) Bot.ctl.jump = p.onGround();
		} else {
			BlockPos b = t.blockPosition();
			Bot.navAux.setGoal(new Goal.Near(b.getX(), b.getY(), b.getZ(), Math.max(1.0, reachPref - 0.8)));
			if (Bot.navAux.tick() == com.humanisedbaritone.path.Nav.State.FAILED) {
				unreachable.put(t.getId(), p.tickCount + 240); // probably behind glass, across a chasm or out of reach: leave it for now
				target = null;
				Bot.navAux.cancel();
				return;
			}
			Bot.look.aimAt(aim, 0.3, 6.0);
		}
	}

	private static void runFrom(LivingEntity t) {
		LocalPlayer p = mc.player;
		double away = Math.toDegrees(Math.atan2(t.getX() - p.getX(), -(t.getZ() - p.getZ())));
		double pick = Double.NaN; // the way out that is not into lava or off a cliff
		for (double off : new double[]{0, 45, -45, 90, -90}) {
			if (safeStep(away + off, 1.3)) {
				pick = away + off;
				break;
			}
		}
		if (Double.isNaN(pick)) return;
		Bot.look.aim(pick, 6, 0.7, 10);
		Bot.ctl.walkTowards(pick, p.getYRot());
		Bot.ctl.sprint = true;
		if (p.horizontalCollision) Bot.ctl.jump = p.onGround();
	}

	/** False when a step of {@code len} blocks towards {@code heading} (yaw degrees) would end in lava, fire, a hazard block or a drop of more than three blocks. */
	private static boolean safeStep(double heading, double len) {
		LocalPlayer p = mc.player;
		double r = Math.toRadians(heading);
		int x = Mth.floor(p.getX() - Math.sin(r) * len), z = Mth.floor(p.getZ() + Math.cos(r) * len), y = Mth.floor(p.getY() + 0.1);
		var c = Bot.nav.live();
		for (int dy = 0; dy <= 3; dy++) {
			var s = c.get(x, y - dy, z);
			if (c.hazard(s, Blk.f(s))) return false;
			if (c.canStandOn(x, y - dy - 1, z) || c.water(x, y - dy, z) || !c.passable(x, y - dy, z)) return true;
		}
		return false;
	}
}
