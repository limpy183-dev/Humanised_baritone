package com.humanisedbaritone.human;

import com.humanisedbaritone.Bot;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * The virtual keyboard and mouse buttons. Behaviours set these fields during a tick; {@link BotInput} feeds the
 * movement keys to the player and {@link #flush} presses the mouse buttons through the real key mappings, so every
 * action goes through the same vanilla code path as a human's input.
 */
public final class Controls {
	private static final Minecraft mc = Minecraft.getInstance();

	public boolean forward, back, left, right, jump, sneak, sprint;
	public boolean attack, use;
	public int attackClicks, useClicks;
	private boolean prevAttack, prevUse;
	/** True while the bot owns the movement keys. */
	public boolean owned;
	/** The player keeps the movement keys but the bot presses jump (and sprint): see {@code ElytraJump.assist}. Only counts while {@link #owned} is false. */
	public boolean assist;

	public void reset() {
		forward = back = left = right = jump = sneak = sprint = false;
		assist = false;
		attack = use = false;
		attackClicks = useClicks = 0;
	}

	public boolean anyMovement() {
		return forward || back || left || right;
	}

	/**
	 * Presses the movement keys that walk towards world heading {@code headingYaw} (Minecraft yaw degrees) given the
	 * current view yaw. Only the eight key combinations a real keyboard offers are possible.
	 */
	public void walkTowards(double headingYaw, double viewYaw) {
		walkTowards(headingYaw, viewYaw, 22.5);
	}

	/**
	 * Same, but a sideways key is only added once the heading is more than {@code strafeFrom} degrees off the view. A
	 * person steers with the mouse and keeps W down; strafing is for fine corrections, so a bot that is only slightly
	 * off simply holds forward and lets the camera catch up.
	 */
	public void walkTowards(double headingYaw, double viewYaw, double strafeFrom) {
		double rel = Mth.wrapDegrees(headingYaw - viewYaw);
		double a = Math.abs(rel);
		boolean fwd = a < 67.5, bck = a > 112.5;
		boolean side = a > strafeFrom && a < 157.5;
		forward |= fwd;
		back |= bck;
		if (side) {
			if (rel > 0) right = true;
			else left = true;
		}
	}

	public void clickAttack() {
		attackClicks++;
	}

	/** The game tick (of the player) of the bot's last use-click: a container that opens right after it was not asked for. */
	public int lastUseTick = -1000;

	public void clickUse() {
		useClicks++;
		if (mc.player != null) lastUseTick = mc.player.tickCount;
	}

	/** Pushes the mouse-button state into the game. Runs at the start of a tick, before the game handles key binds. */
	public void flush() {
		// only touch a mouse button while the bot uses it, so the player's own clicking is left alone
		if (attack || prevAttack) setHeld(mc.options.keyAttack, attack);
		if (use || prevUse) setHeld(mc.options.keyUse, use);
		prevAttack = attack;
		prevUse = use;
		for (; attackClicks > 0; attackClicks--) click(mc.options.keyAttack);
		for (; useClicks > 0; useClicks--) click(mc.options.keyUse);
	}

	public void releaseAll() {
		reset();
		flush();
	}

	private static void setHeld(KeyMapping k, boolean want) {
		if (k.isDown() == want) return;
		k.setDown(want);
		if (k.isDown() != want) k.setDown(true); // "toggle attack/use" accessibility options toggle instead
	}

	private static void click(KeyMapping k) {
		if (k.isUnbound()) return;
		KeyMapping.click(KeyMappingHelper.getBoundKeyOf(k));
	}

	/** Replaces the player's keyboard input while the bot is driving, and hands it back afterwards. */
	public static final class BotInput extends ClientInput {
		final ClientInput real;

		BotInput(ClientInput real) {
			this.real = real;
		}

		@Override
		public void tick() {
			real.tick();
			Controls c = Bot.ctl;
			if (!c.owned) {
				keyPresses = real.keyPresses;
				moveVector = real.getMoveVector();
				if (c.assist) {
					Input r = real.keyPresses;
					keyPresses = new Input(r.forward(), r.backward(), r.left(), r.right(), c.jump, r.shift(), r.sprint() || c.sprint);
				}
				return;
			}
			keyPresses = new Input(c.forward, c.back, c.left, c.right, c.jump, c.sneak, c.sprint);
			moveVector = new Vec2(impulse(c.left, c.right), impulse(c.forward, c.back)).normalized();
		}

		private static float impulse(boolean pos, boolean neg) {
			return pos == neg ? 0 : pos ? 1 : -1;
		}
	}

	/** Makes sure our input wrapper is the active one. */
	public static void install(LocalPlayer p) {
		if (!(p.input instanceof BotInput)) p.input = new BotInput(p.input);
	}

	public static void uninstall(LocalPlayer p) {
		if (p != null && p.input instanceof BotInput b) p.input = b.real;
	}
}
