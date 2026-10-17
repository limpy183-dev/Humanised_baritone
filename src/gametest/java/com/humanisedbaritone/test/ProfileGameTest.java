package com.humanisedbaritone.test;

import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.settings.Profiles;
import com.humanisedbaritone.settings.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;

/** Selected profile: settings changes are saved into it, switching loads another one, a row click selects it. */
public class ProfileGameTest extends BotTest {
	private static Path file(String name) {
		return FabricLoader.getInstance().getConfigDir().resolve("humanised_baritone").resolve("profiles").resolve(name + ".json");
	}

	/** The value a profile file holds for max clicks per second, or -1 when the profile does not have it (= default). */
	private static int cps(String name) {
		try {
			var o = com.google.gson.JsonParser.parseString(Files.readString(file(name))).getAsJsonObject();
			return o.has(Settings.MAX_CPS.id) ? o.get(Settings.MAX_CPS.id).getAsInt() : -1;
		} catch (Exception e) {
			return -2;
		}
	}

	private static int cps() {
		return Settings.MAX_CPS.i();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1600, 900);
			ctx.waitTicks(40);

			ctx.runOnClient(mc -> {
				Settings.MAX_CPS.set(5.0);
				Profiles.save("Alpha");
				Settings.MAX_CPS.set(9.0);
				Profiles.save("Beta");
			});
			report("select-on-create", client(ctx, mc -> "Beta".equals(Settings.profile)), "profile=" + Settings.profile);

			// a change is written into the selected profile only, by itself
			ctx.runOnClient(mc -> Settings.MAX_CPS.set(13.0));
			ctx.waitTicks(60);
			report("autosave", cps("Beta") == 13 && cps("Alpha") == 5, "Beta=" + cps("Beta") + " Alpha=" + cps("Alpha"));

			// a change made a moment before switching still lands in the profile that is left
			ctx.runOnClient(mc -> {
				Settings.MAX_CPS.set(14.0);
				Profiles.load("Alpha");
			});
			report("switch-loads", client(ctx, mc -> cps() == 5 && "Alpha".equals(Settings.profile)), "cps=" + cps() + " profile=" + Settings.profile);
			report("switch-flushes", cps("Beta") == 14 && cps("Alpha") == 5, "Beta=" + cps("Beta") + " Alpha=" + cps("Alpha"));

			// and the GUI shows it: open the Profiles tab
			ctx.runOnClient(mc -> {
				mc.gui.setScreen(new BotScreen());
				((BotScreen) mc.gui.screen()).showTab(BotScreen.PROFILES);
			});
			ctx.waitTicks(25);
			ctx.takeScreenshot(TestScreenshotOptions.of("profiles-alpha-selected").withSize(1600, 900));

			// a real click on the row of Beta (not on a button): selects it
			int[] at = rowAt(System.getenv("HB_CLICK"));
			if (at != null) {
				ctx.getInput().setCursorPos(at[0], at[1]);
				ctx.waitTicks(10);
				ctx.takeScreenshot(TestScreenshotOptions.of("profiles-hover").withSize(1600, 900));
				ctx.getInput().pressMouse(0);
				ctx.waitTicks(15);
				ctx.takeScreenshot(TestScreenshotOptions.of("profiles-beta-selected").withSize(1600, 900));
				report("click-selects", client(ctx, mc -> cps() == 14 && "Beta".equals(Settings.profile) && mc.gui.screen() instanceof BotScreen), "cps=" + cps() + " profile=" + Settings.profile + " (panel must stay open)");
			}
			ctx.runOnClient(mc -> mc.gui.setScreen(null));

			// the selected profile is deleted: nothing selected, and it stays gone
			String cur = Settings.profile;
			ctx.runOnClient(mc -> Profiles.delete(cur));
			ctx.waitTicks(60);
			report("delete-selected", Settings.profile.isEmpty() && !Files.exists(file(cur)), "deleted=" + cur + " profile='" + Settings.profile + "'");
			finish();
		}
	}

	private static int[] rowAt(String s) {
		if (s == null || s.isBlank()) return null;
		String[] p = s.split(",");
		return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
	}
}
