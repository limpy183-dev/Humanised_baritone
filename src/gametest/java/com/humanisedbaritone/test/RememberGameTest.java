package com.humanisedbaritone.test;

import com.google.gson.JsonParser;
import com.humanisedbaritone.Bot;
import com.humanisedbaritone.settings.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

/** Exercises real disconnects/rejoins and config reloads for both remembered states. */
public class RememberGameTest extends BotTest {
	private static Path config() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanised_baritone.json");
	}

	private static boolean savedEnabled() {
		try {
			return JsonParser.parseString(Files.readString(config())).getAsJsonObject().get("botEnabled").getAsBoolean();
		} catch (Exception e) {
			throw new AssertionError("Could not read saved bot state", e);
		}
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			Settings.resetAll();
			Settings.profile = "";
			Settings.FEEDBACK.set(false);
			Settings.PASSIVE.set(false);
			Bot.setEnabled(false);
		});

		var world = ctx.worldBuilder().create();
		var save = world.getWorldSave();
		report("default-off", client(ctx, mc -> !Bot.enabled && !Settings.REMEMBER_ENABLED.on()), "First join starts disabled");
		ctx.runOnClient(mc -> Bot.setEnabled(true));
		world.close();
		report("disconnect-releases", client(ctx, mc -> !Bot.enabled && !Bot.ctl.owned), "Disconnected bot releases control");

		world = save.open();
		report("opt-out", client(ctx, mc -> !Bot.enabled), "Remember toggle off starts disabled on rejoin");
		ctx.runOnClient(mc -> {
			Bot.setEnabled(true);
			Settings.REMEMBER_ENABLED.set(true); // opt in while already enabled
		});
		world.close(); // no autosave delay: disconnect must flush both changes
		report("enabled-flushed", savedEnabled(), "Immediate disconnect saved ON");

		ctx.runOnClient(mc -> {
			Bot.setEnabled(false);
			Settings.REMEMBER_ENABLED.reset();
			Settings.load(); // discard in-memory values to simulate restarting Minecraft
		});
		world = save.open();
		report("enabled-restored", client(ctx, mc -> Bot.enabled && Settings.REMEMBER_ENABLED.on()), "Config reload and real rejoin restore ON");
		ctx.runOnClient(mc -> Bot.setEnabled(false));
		world.close();
		report("disabled-flushed", !savedEnabled(), "Immediate disconnect saved OFF");

		ctx.runOnClient(mc -> {
			Settings.rememberBotEnabled(true);
			Settings.load();
		});
		world = ctx.worldBuilder().create();
		var otherSave = world.getWorldSave();
		report("disabled-other-world", client(ctx, mc -> !Bot.enabled && Settings.REMEMBER_ENABLED.on()), "A different world restores OFF");
		ctx.runOnClient(mc -> {
			Bot.setEnabled(true);
			Settings.REMEMBER_ENABLED.set(false);
		});
		world.close();

		world = otherSave.open();
		report("opt-out-after-on", client(ctx, mc -> !Bot.enabled), "Turning remember off stops restoring ON");
		ctx.runOnClient(mc -> {
			Settings.REMEMBER_ENABLED.set(true);
			Bot.setEnabled(true);
			Bot.fault();
			Settings.save();
			Settings.rememberBotEnabled(true);
			Settings.load();
		});
		report("fault-stays-off", client(ctx, mc -> !Settings.restoreBotEnabled()), "An internal error persists OFF");
		finish(); // avoid the framework's shutdown deadlock, as in the other suites
	}
}
