package com.humanisedbaritone.behave;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Ctx;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The elytra bounce: timed jumps, no fireworks. A glide that touches the ground ends, but only when the server says so, a tick
 * later; the first tick on the ground the client is still gliding and has no ground friction, so a jump on exactly that tick
 * keeps the speed (and adds the 0.2 of a sprint jump). Once airborne the glide has to be opened again, which takes a fresh press
 * of the jump key: let go of it the tick after the jump, press it the moment the flag is gone. Done that way no tick of a bounce is
 * a plain falling tick, and the speed climbs to about 35 blocks a second (a sprint hop is 6.7, a sprint 5.6). Every bounce is ten
 * ticks, so the speed is a matter of the jumps alone: pitch anywhere from 70 to 89 degrees measured the same in the real game.
 *
 * <p>Two ways in: {@link #drive} flies a heading with the camera and every key (the wander task), {@link #assist} only presses
 * jump and sprint while the player steers. Both look ahead along the line they fly and stop jumping early enough to come to a halt
 * in front of whatever is there: the game takes ten hit points or more for a wall hit at that speed.
 */
public final class ElytraJump {
	private static final Minecraft mc = Minecraft.getInstance();
	/** The camera pitch of a bounce. */
	private static final double PITCH = 78;
	/** Ticks the key stays up after a jump while the glide is still on: the server's word that it is over comes within a few. Later the key goes down for the jump on the next landing. */
	private static final int WAIT = 7;
	/** Ticks of one bounce, jump to jump. */
	private static final double ARC = 10.5;
	/** Blocks to spare in front of the obstacle when stopped. */
	private static final double MARGIN = 4;
	/** Furthest look-ahead. */
	private static final double SCAN = 72;
	private static final double[] LANES = {-0.8, -0.4, 0, 0.4, 0.8};
	private static final int NONE = Integer.MIN_VALUE;

	private enum Phase { OFF, ALIGN, BOUNCE, BRAKE }

	private static Phase phase = Phase.OFF;
	private static boolean assisting, prevKey, want;
	/** Ticks since the jump (99: none lately); the game tick of the last call and of the last time on the ground; the level of the feet there. */
	private static int sinceJump = 99, lastTick = -1000, lastGround = -1000, floorY = NONE, stopped, waited;
	private static double lineX, lineZ, lineYaw;
	private static final Rng.Ou pitchNoise = new Rng.Ou(0.4, 1.2);

	private ElytraJump() {
	}

	public static void reset() {
		phase = Phase.OFF;
		assisting = prevKey = want = false;
		sinceJump = 99;
		lastTick = lastGround = -1000;
		floorY = NONE;
		stopped = waited = 0;
	}

	/** The helper or the engine has had the player this tick or the last one: whoever stops asking (a task that ended, a fight that took over) leaves the flags behind, so they alone go stale. */
	private static boolean active(LocalPlayer p) {
		return (phase != Phase.OFF || assisting) && p.tickCount - lastTick <= 1;
	}

	/** Bouncing or braking from a bounce: not the time for fighting or eating. */
	public static boolean inFlight() {
		LocalPlayer p = mc.player;
		return p != null && active(p) && (!p.onGround() || speed(p) > 0.5);
	}

	public static boolean busy() {
		LocalPlayer p = mc.player;
		return p != null && active(p);
	}

	public static String info() {
		LocalPlayer p = mc.player;
		if (p == null || !busy()) return "";
		return String.format("Elytra %s, %.0f blocks a second", phase == Phase.BRAKE ? "braking" : "bounce", speed(p) * 20);
	}

	/** The glider worn (an elytra, or anything else that glides), or empty. */
	public static ItemStack glider(LocalPlayer p) {
		for (EquipmentSlot s : EquipmentSlot.VALUES) {
			ItemStack st = p.getItemBySlot(s);
			if (LivingEntity.canGlideUsing(st, s)) return st;
		}
		return ItemStack.EMPTY;
	}

	/** A bounce is possible: the setting is on, a glider worn that has more durability than asked, and nothing that takes the legs, the sprint or gravity away. */
	public static boolean ready() {
		LocalPlayer p = mc.player;
		if (p == null || !Settings.ELYTRA.on() || !p.isAlive() || p.isSpectator() || p.getAbilities().mayfly || p.isPassenger()) return false;
		if (p.isInWater() || p.isInLava() || p.onClimbable() || p.getFoodData().getFoodLevel() <= 6) return false; // no sprint, no boost
		if (p.hasEffect(MobEffects.LEVITATION) || p.hasEffect(MobEffects.SLOW_FALLING) || p.hasEffect(MobEffects.JUMP_BOOST)) return false; // the bounce is ten ticks of a plain hop: all three change its length, and with it how far it carries and how far ahead has to be clear
		ItemStack g = glider(p);
		if (g.isEmpty()) return false;
		return !g.isDamageableItem() || (g.getMaxDamage() - g.getDamageValue()) * 100.0 >= Settings.ELYTRA_KEEP.d() * g.getMaxDamage();
	}

	private static double speed(LocalPlayer p) {
		return p.getDeltaMovement().horizontalDistance();
	}

	/** Called once a tick by whoever is in charge: forgets the key history when it was not called the tick before. */
	private static void begin(LocalPlayer p) {
		if (p.tickCount != lastTick + 1) {
			prevKey = false;
			sinceJump = 99;
			if (p.tickCount - lastTick > 20) phase = Phase.OFF; // long ago: a new start, not a continuation
		}
		lastTick = p.tickCount;
		if (p.onGround()) {
			lastGround = p.tickCount;
			floorY = Mth.floor(p.getY() + 0.001);
		}
	}

	// ------------------------------------------------------------------ the timing

	/**
	 * The jump key of this tick. On the ground it is the jump, on the very first tick there. In the air the key is up for the
	 * first ticks after the jump (the glide is still on, and a press then would do nothing); when the glide is gone a press opens it
	 * at once (the key is up, so it is a fresh press); once it is open again the key goes down and stays down until the landing.
	 */
	private static boolean timing(LocalPlayer p, boolean wanted) {
		boolean key;
		if (p.onGround()) {
			key = wanted;
			sinceJump = wanted ? 0 : 99;
		} else {
			if (sinceJump < 99) sinceJump++;
			if (!wanted) key = false;
			else if (!p.isFallFlying()) key = !prevKey;
			else key = sinceJump >= WAIT;
		}
		prevKey = key;
		return key;
	}

	// ------------------------------------------------------------------ how far

	/** Blocks of bounce that need to be clear for the next jump, the flight of it and the stop after it. */
	private static double need(double v) {
		return ARC * v + brake(v) + MARGIN;
	}

	/** Blocks from the landing to a standstill with the keys let go: two ticks of glide on the ground before the server's word, then ground friction. */
	private static double brake(double v) {
		return 4.5 * v + 2;
	}

	/** The ground ahead has to be clear this far for a bounce to be worth the start: it takes about forty blocks to get going, and a stop and a new plan after it. */
	public static final double WORTH = 40;

	/** A bounce can begin here and now along {@code yaw}, with at most {@code cap} blocks to go: one is possible at all and the ground ahead is clear for {@link #WORTH} blocks (or all of the way). */
	public static boolean canStart(double yaw, double cap) {
		return ready() && lane(yaw, Math.min(cap, SCAN)) >= Math.min(cap, WORTH);
	}

	/** How far a bounce from the feet of the player can go along {@code yaw} on level ground, up to {@code max}. */
	public static double lane(double yaw, double max) {
		LocalPlayer p = mc.player;
		return room(p.getX(), p.getZ(), yaw, Mth.floor(p.getY() + 0.001), max);
	}

	/**
	 * Walks along the line in half blocks, five lanes wide (the body, the sway of the steering and a margin) and checks what a
	 * bounce meets. A rise of any height, however low, is a wall for a body in the air, so anything with a collision box in the cells above the
	 * floor is one (grass and flowers have none); the floor has to be there and plain, level or one
	 * block lower (never higher again after a drop): nothing that slows, slides or burns, no water, no lava, no hole. The whole hop has
	 * to fit, the cells from the floor up to the third above the level it started from. Under a lower ceiling the player would hurt no one,
	 * but it would not be let through either: gliding the body is 0.6 high and rises into it, the server still counts 1.8 and takes the hop
	 * for a move through the ceiling ("moved wrongly"), and puts the player back, hop after hop.
	 */
	private static double room(double x, double z, double yaw, int level, double max) {
		if (level == NONE) return 0;
		Ctx c = Bot.nav.live();
		double r = Math.toRadians(yaw), ux = -Math.sin(r), uz = Math.cos(r), lx = -uz, lz = ux;
		int lvl = level;
		for (double t = 0.5; t <= max; t += 0.5) {
			int best = NONE;
			for (double off : LANES) {
				int fy = column(c, Mth.floor(x + ux * t + lx * off), Mth.floor(z + uz * t + lz * off), lvl);
				if (fy == NONE) return Math.max(0, t - 1.0);
				best = Math.max(best, fy);
			}
			lvl = best;
		}
		return max;
	}

	/** The level of the floor in this column the bounce would come down on, or NONE when something in the way. */
	private static int column(Ctx c, int x, int z, int level) {
		if (!c.loaded(x, z) || !mc.level.getWorldBorder().isWithinBounds(x, z)) return NONE;
		int fy = level;
		if (!floor(c, x, fy - 1, z)) {
			fy--;
			if (!floor(c, x, fy - 1, z)) return NONE;
		}
		for (int y = fy; y <= level + 2; y++) {
			int f = c.f(x, y, z);
			// nothing with a collision box at all: an open door, a trapdoor, a slab, a carpet or a snow layer count as passable for a walker, but a body
			// in the air at that speed runs into it
			if (!c.passable(x, y, z) || (f & Blk.CLEAR) == 0 || (f & (Blk.LIQUID | Blk.CLIMB)) != 0) return NONE;
		}
		return fy;
	}

	private static boolean floor(Ctx c, int x, int y, int z) {
		int f = c.f(x, y, z);
		return c.canStandOn(x, y, z) && (f & Blk.FULL) != 0 && (f & (Blk.SLOW | Blk.SLIPPERY | Blk.NO_JUMP | Blk.LIQUID | Blk.FIRE)) == 0;
	}

	// ------------------------------------------------------------------ the helper

	/**
	 * The player steers, the bot times the jumps: hold forward and look steeply down with a glider worn. Returns true while it presses
	 * the keys (jump and sprint); the camera and the movement keys stay the player's.
	 */
	public static boolean assist() {
		LocalPlayer p = mc.player;
		var o = mc.options;
		boolean on = phase == Phase.OFF && Settings.ELYTRA_HELPER.on() && ready() && o.keyUp.isDown() && !o.keyDown.isDown() && !o.keyShift.isDown() && p.getXRot() >= (active(p) ? 40 : 55);
		if (p.onGround()) {
			lastGround = p.tickCount;
			floorY = Mth.floor(p.getY() + 0.001);
		}
		if (on && !p.onGround() && p.tickCount - lastGround > 14) on = false; // up in the air for another reason, a fall: no business of ours
		if (!on) {
			if (assisting) {
				assisting = false;
				prevKey = false;
				sinceJump = 99;
			}
			return false;
		}
		begin(p);
		if (!assisting) {
			assisting = true;
			want = true;
		}
		double v = speed(p);
		if (p.onGround()) { // the decision of this landing: jump, or come to a halt
			want = true;
			if (Settings.ELYTRA_SAFE.on()) {
				double r = room(p.getX(), p.getZ(), p.getYRot(), floorY, SCAN);
				if (v > 0.3) { // it goes where the speed is, and that is not always where the camera looks
					var m = p.getDeltaMovement();
					double going = Math.toDegrees(Math.atan2(-m.x, m.z));
					if (Math.abs(Mth.wrapDegrees(going - p.getYRot())) > 6) r = Math.min(r, room(p.getX(), p.getZ(), going, floorY, SCAN));
				}
				want = r >= need((v + 0.2) * 0.99);
			}
		}
		Bot.ctl.assist = true;
		Bot.ctl.sprint = want;
		Bot.ctl.jump = timing(p, want);
		return true;
	}

	// ------------------------------------------------------------------ driving

	/**
	 * Flies a bounce along {@code heading} (yaw degrees) for at most {@code cap} more blocks: walks up to speed, bounces, and when
	 * the way ahead stops being good or the cap is used up, brakes. Call it every tick while it should go on. Returns true while it
	 * holds the player, false when it is not going (not possible here, or stopped at the end).
	 */
	public static boolean drive(double heading, double cap) {
		LocalPlayer p = mc.player;
		assisting = false;
		begin(p);
		if (phase == Phase.OFF) {
			if (!ready() || !p.onGround()) return false;
			phase = Phase.ALIGN;
			lineX = p.getX();
			lineZ = p.getZ();
			lineYaw = heading;
			stopped = waited = 0;
			want = false;
		}
		if (Math.abs(Mth.wrapDegrees(heading - lineYaw)) > 2.5) { // a new heading: steer for it from here
			lineX = p.getX();
			lineZ = p.getZ();
			lineYaw = heading;
		}
		double v = speed(p);
		boolean ground = p.onGround();
		var ctl = Bot.ctl;
		if (phase == Phase.ALIGN) { // on the ground, sprinting, the camera coming round and down
			double r = room(p.getX(), p.getZ(), heading, floorY, Math.min(cap, SCAN));
			if (!ready() || r < need(0.45) || ++waited > 200) phase = Phase.BRAKE;
			else {
				Bot.look.aim(heading, PITCH, 0.5, 4);
				if (Math.abs(Mth.wrapDegrees(heading - p.getYRot())) < 30) ctl.forward = ctl.sprint = true; // not off in the wrong direction while the camera comes round
				if (!(ground && p.isSprinting() && Bot.look.error() < 10)) return true;
				phase = Phase.BOUNCE;
			}
		}
		if (phase == Phase.BOUNCE) {
			if (ground) { // the decision of this landing: jump, or come to a halt
				want = ready() && room(p.getX(), p.getZ(), lineYaw, floorY, Math.min(cap, SCAN)) >= need((v + 0.2) * 0.99);
			} else if (!ready()) want = false;
			if (p.horizontalCollision && v < 0.3) want = false; // into something anyway
			if (!want) phase = Phase.BRAKE;
		}
		if (phase == Phase.BOUNCE) {
			double h = Math.toRadians(lineYaw);
			double right = (p.getX() - lineX) * -Math.cos(h) + (p.getZ() - lineZ) * -Math.sin(h); // blocks to the right of the line
			ctl.forward = ctl.sprint = true;
			ctl.jump = timing(p, true);
			// a degree of correction per block off the line: the glide follows the camera with a lag of ten ticks and the camera has its own, a stronger pull would swing
			Bot.look.aim(lineYaw - Mth.clamp(right, -12, 12), Mth.clamp(PITCH + pitchNoise.step(0.05), 72, 84), 0.4, 3);
			return true;
		}
		// BRAKE: keys up, and the camera level once it is on the ground (a glide that is still on would soar with a level camera)
		prevKey = false;
		sinceJump = 99;
		Bot.look.aim(p.getYRot(), ground ? 8 : PITCH, 0.3, 6);
		if (ground && v < 0.05) stopped++;
		else stopped = 0;
		if (stopped > 4 || ++waited > 400 || p.isInWater() || p.isInLava()) { // at rest, or something else has the player now (water, a ledge it hangs from): let go
			phase = Phase.OFF;
			return false;
		}
		return true;
	}
}
