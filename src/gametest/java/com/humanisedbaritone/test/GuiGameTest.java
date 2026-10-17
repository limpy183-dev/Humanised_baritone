package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.settings.Settings;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/** Screenshots of the interface and the in-world overlay. */
public class GuiGameTest extends BotTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1600, 900);
			ctx.waitTicks(40);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			revive(ctx, sp);
			load(ctx, sp, -20, -40, 100, 40);
			cmds(sp, "fill 12 -60 -20 12 -58 20 stone", "fill 30 -60 -20 30 -58 20 stone", "give @a iron_pickaxe", "tp @a 0.5 -60 0.5");
			ctx.waitTicks(20);

			// the headline feature: a real press of the apostrophe key opens the panel, another one closes it
			ctx.getInput().pressKey(InputConstants.KEY_APOSTROPHE);
			ctx.waitTicks(4);
			report("key-opens-panel", client(ctx, mc -> mc.gui.screen() instanceof BotScreen), "");
			ctx.getInput().pressKey(InputConstants.KEY_APOSTROPHE);
			ctx.waitTicks(4);
			report("key-closes-panel", client(ctx, mc -> mc.gui.screen() == null), "");

			// the in-world overlay while the bot walks a route with a few turns
			botOn(ctx);
			ctx.runOnClient(mc -> Commands.run("goto 60 -60 10"));
			ctx.waitTicks(70);
			ctx.takeScreenshot(TestScreenshotOptions.of("overlay-path").withSize(1280, 720));

			// the dashboard with a live task
			ctx.runOnClient(mc -> mc.gui.setScreen(new BotScreen()));
			ctx.waitTicks(25);
			ctx.takeScreenshot(TestScreenshotOptions.of("dash-live").withSize(1600, 900));
			ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).showTab("Humanise"));
			ctx.waitTicks(10);
			ctx.takeScreenshot(TestScreenshotOptions.of("tab-humanise").withSize(1600, 900));
			ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).showTab("Safety"));
			ctx.waitTicks(10);
			ctx.takeScreenshot(TestScreenshotOptions.of("tab-safety").withSize(1600, 900));
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			ctx.runOnClient(mc -> Commands.run("stop"));

			// other window sizes
			for (int[] size : new int[][]{{854, 480}, {1920, 1080}}) {
				ctx.getInput().resizeWindow(size[0], size[1]);
				ctx.waitTicks(10);
				ctx.runOnClient(mc -> mc.gui.setScreen(new BotScreen()));
				ctx.waitTicks(25);
				ctx.takeScreenshot(TestScreenshotOptions.of("size-" + size[0]).withSize(size[0], size[1]));
				ctx.runOnClient(mc -> mc.gui.setScreen(null));
			}
			ctx.getInput().resizeWindow(1600, 900);
			ctx.waitTicks(10);
			// a dialog
			ctx.runOnClient(mc -> {
				BotScreen s = new BotScreen();
				mc.gui.setScreen(s);
			});
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).showTab("Combat"));
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).openPicker(Settings.TARGETS));
			ctx.waitTicks(20);
			ctx.takeScreenshot(TestScreenshotOptions.of("picker").withSize(1600, 900));
			finish();
		}
	}
}
