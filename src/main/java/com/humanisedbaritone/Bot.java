package com.humanisedbaritone;

import com.humanisedbaritone.behave.Combat;
import com.humanisedbaritone.chat.ChatChallenges;
import com.humanisedbaritone.behave.Clutch;
import com.humanisedbaritone.behave.Eating;
import com.humanisedbaritone.behave.ElytraJump;
import com.humanisedbaritone.behave.Passive;
import com.humanisedbaritone.behave.Safety;
import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.human.Attention;
import com.humanisedbaritone.human.Controls;
import com.humanisedbaritone.human.Fidget;
import com.humanisedbaritone.human.Gaze;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.human.LookController;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.Task;
import com.humanisedbaritone.task.Tasks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** Central hub: owns the virtual controls and decides, every tick, which behaviour drives the player. */
public final class Bot {
	public static final Minecraft mc = Minecraft.getInstance();
	public static final Controls ctl = new Controls();
	public static final LookController look = new LookController();
	/** Navigator of the running task. */
	public static final Nav nav = new Nav();
	/** Navigator for short detours of the reflex layers (fleeing, chasing, fetching items) so the task's route stays intact. */
	public static final Nav navAux = new Nav();

	public static boolean enabled;
	/** A task is working inside a chest: its container screen must not pause the bot. */
	public static boolean containerOk;
	private static LocalPlayer lastPlayer;
	private static boolean alertNow;

	private Bot() {
	}

	public static void setEnabled(boolean on) {
		Settings.rememberBotEnabled(on);
		if (on == enabled) return;
		enabled = on;
		if (!on) {
			ChatChallenges.reset();
			Tasks.stopAll();
			stopAll();
		}
		Log.info(on ? "Bot enabled." : "Bot disabled.");
	}

	/** Emergency switch-off after an internal error. */
	public static void fault() {
		ChatChallenges.reset();
		enabled = false;
		Settings.rememberBotEnabled(false);
		try {
			Tasks.stopAll();
			nav.cancel();
			navAux.cancel();
			Combat.reset();
			Clutch.reset();
			ElytraJump.reset();
			stopAll();
		} catch (Throwable ignored) {
		}
	}

	/** Gives all controls back to the player. */
	public static void stopAll() {
		ctl.owned = false;
		ctl.releaseAll();
		look.release();
		Gaze.reset();
		Controls.uninstall(mc.player);
	}

	public static void stopTask() {
		ChatChallenges.reset();
		Tasks.stopAll();
		nav.cancel();
		navAux.cancel();
		Combat.reset();
		Clutch.reset();
		ElytraJump.reset();
		ctl.owned = false;
		ctl.releaseAll();
		look.release();
	}

	/** Restore only on connection, so respawns and dimension changes keep the current state. */
	public static void onJoin() {
		ChatChallenges.reset();
		setEnabled(Settings.restoreBotEnabled());
	}

	public static void onDisconnect() {
		ChatChallenges.reset();
		Settings.save(); // flush recent state and toggle changes before leaving
		com.humanisedbaritone.build.Placement.clear(); // a schematic on show belongs to the world it was loaded in
		enabled = false; // temporary reset must not overwrite the remembered state
		Tasks.stopAll();
		nav.cancel();
		navAux.cancel();
		Combat.reset();
		Clutch.reset();
		ElytraJump.reset();
		stopAll();
		lastPlayer = null;
	}

	/** Start of every client tick, before the game picks a target and handles key binds. */
	public static void tick() {
		Settings.tick();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			lastPlayer = null;
			return;
		}
		if (p != lastPlayer) { // joined a world or respawned
			lastPlayer = p;
			ctl.owned = false;
			nav.cancel();
			navAux.cancel();
			Combat.reset();
			Clutch.reset();
			ElytraJump.reset();
			Eating.reset();
			if (!Settings.AUTO_RESUME.on()) Tasks.stopAll();
			else Tasks.restartCurrent();
		}
		Attention.tick();
		look.tick();
		if (!enabled) return;

		var screen = mc.gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.DeathScreen)) deadFor = -1;
		else {
			ctl.owned = false;
			ctl.releaseAll();
			respawnSoon(p);
			return;
		}
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> && !containerOk && Tasks.current() != null && p.tickCount - ctl.lastUseTick < 60
			&& !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) && !(screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)) {
			Log.warn("A click of the bot opened " + screen.getClass().getSimpleName() + " - closed it again.");
			p.closeContainer();
			return;
		}
		// another menu (inventory, chat, pause) is open: hands off
		if (screen != null && !(screen instanceof BotScreen) && !(containerOk && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) {
			if (Settings.DEBUG.on() && Tasks.current() != null && p.tickCount % 400 == 0) Log.debug("bot: hands off, " + screen.getClass().getSimpleName() + " is open");
			ctl.owned = false;
			ctl.releaseAll();
			return;
		}
		if (Settings.USER_CANCEL.on() && Tasks.current() != null && userIsMoving()) {
			Log.info("You took over - task cancelled.");
			stopTask();
			return;
		}

		if (p.getAbilities().flying && Tasks.current() != null) { // creative: two quick jumps start a flight, and every plan here assumes gravity (a flying bot hovered above its own build)
			p.getAbilities().flying = false;
			p.onUpdateAbilities();
		}
		ctl.reset();
		Controls.install(p);
		alertNow = false;
		// priority order: reflexes, fighting, eating, the user's task, background chores
		boolean flying = ElytraJump.inFlight(); // a bounce at 30 blocks a second is no time to fight or to eat: both wait for the landing
		String by = Safety.tick() ? "safety" : !flying && Combat.tick() ? "combat" : !flying && Eating.tick() ? "eating" : Tasks.tick() ? "task" : Settings.PASSIVE.on() && Passive.tick() ? "passive" : null;
		boolean handled = by != null;
		if (Settings.DEBUG.on() && Tasks.current() != null && p.tickCount % 400 == 0) Log.debug(String.format("bot: %s has control, player %.1f %.1f %.1f%s", by == null ? "nobody" : by, p.getX(), p.getY(), p.getZ(), p.getAbilities().flying ? ", flying" : ""));
		ctl.owned = handled;
		if (!handled) {
			if (ElytraJump.assist()) look.release(); // the camera and the movement keys stay the player's, only jump and sprint are the bot's
			else if (Fidget.tick()) ctl.owned = true;
			else look.release();
			Gaze.tick(false, false);
		}
		ctl.flush();
	}

	private static int deadFor = -1, respawnAfter;
	private static boolean respawnGaveUp;
	private static final java.util.ArrayDeque<Long> deaths = new java.util.ArrayDeque<>();

	/** Behind the death screen: with the setting on, click "Respawn" after a pause a person would need, unless this keeps happening. */
	private static void respawnSoon(LocalPlayer p) {
		if (deadFor < 0) { // the first tick behind the screen
			deadFor = 0;
			respawnGaveUp = false;
			long now = System.currentTimeMillis();
			deaths.removeIf(t -> now - t > 600_000);
			deaths.add(now);
			respawnAfter = Rng.delayTicks(2600);
			if (Settings.AUTO_RESPAWN.on() && deaths.size() >= 3) {
				Log.warn("Died " + deaths.size() + " times in 10 minutes - not respawning automatically.");
				respawnGaveUp = true;
			}
		}
		if (!Settings.AUTO_RESPAWN.on() || respawnGaveUp || mc.level.getLevelData().isHardcore()) return; // a hardcore death is final
		// exactly what the button does: the game closes the screen when the new life begins; ask again every 5 seconds should nothing happen
		if (++deadFor >= respawnAfter && (deadFor - respawnAfter) % 100 == 0) p.respawn();
	}

	private static boolean userIsMoving() {
		var o = mc.options;
		return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown();
	}

	/** Every rendered frame. */
	public static void frame() {
		if (enabled) Guard.run("camera", look::frame);
	}

	// ------------------------------------------------------------ status API

	public static void markAlert() {
		alertNow = true;
	}

	public static boolean paused() {
		return Tasks.paused();
	}

	public static boolean busy() {
		return Tasks.current() != null;
	}

	public static boolean alert() {
		return alertNow;
	}

	public static String status() {
		if (!enabled) return "OFF";
		Task t = Tasks.current();
		if (t == null) return "IDLE";
		return Tasks.paused() ? "PAUSED" : t.tag();
	}

	public static String headline() {
		if (!enabled) return "Bot is off";
		Task t = Tasks.current();
		return t == null ? (ElytraJump.busy() ? "Elytra jump helper" : "Idle") : t.name();
	}

	public static String detail() {
		if (!enabled) return "Enable the bot to start";
		Task t = Tasks.current();
		if (t == null) return ElytraJump.busy() ? ElytraJump.info() : "Waiting for a task";
		String s = t.status();
		int q = Tasks.queued();
		return (s.isEmpty() ? "working" : s) + (q > 0 ? "  (" + q + " queued)" : "");
	}

	public static float progress() {
		Task t = Tasks.current();
		return t == null ? -1 : (float) t.progress();
	}

	public static String extraInfo() {
		var t = Combat.target();
		if (t != null) return String.format("Fighting %s  (%.0f HP)", t.getName().getString(), t.getHealth());
		if (Combat.fighting()) return "Retreating to recover";
		if (ElytraJump.busy()) return ElytraJump.info();
		if (nav.active() && nav.path() != null) return "Route: " + nav.path().blocksFrom(nav.index()) + " blocks to go";
		return "";
	}

	public static String selectionInfo() {
		return com.humanisedbaritone.build.Selection.info();
	}
}
