package com.humanisedbaritone.test;

import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.settings.Settings.UiStyle;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * The interface styles: every tab of every style is drawn for a while (a bug in a draw routine closes the panel,
 * which the test notices) and photographed, and the style is switched while the panel is open.
 * HB_ONLY=fun,modern picks styles, HB_TABS=Dashboard,Interface picks tabs, HB_SIZE=854x480 changes the window.
 */
public class StyleGameTest extends BotTest {
	private static final String[] TABS = {"Dashboard", "Logging", "Map", "Humanise", "Combat", "Interface", "Profiles"};

	private static Object field(Object o, String name) {
		try {
			java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(o);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static String currentTab(ClientGameTestContext ctx) {
		return client(ctx, mc -> String.valueOf(field(mc.gui.screen(), "tab")));
	}

	/** The middle of a tab button in window pixels, read from the open panel. */
	private static int[] tabPixel(ClientGameTestContext ctx, String tab) {
		return client(ctx, mc -> {
			try {
				java.lang.reflect.Field names = BotScreen.class.getDeclaredField("TABS");
				names.setAccessible(true);
				String[] tabs = (String[]) names.get(null);
				int i = java.util.Arrays.asList(tabs).indexOf(tab);
				BotScreen scr = (BotScreen) mc.gui.screen();
				int[] tx = (int[]) field(scr, "tx"), ty = (int[]) field(scr, "ty"), tw = (int[]) field(scr, "tw"), th = (int[]) field(scr, "th");
				float s = (float) field(scr, "s");
				double gs = mc.getWindow().getGuiScale();
				return new int[]{(int) Math.round((tx[i] + tw[i] / 2.0) * s * gs), (int) Math.round((ty[i] + th[i] / 2.0) * s * gs)};
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static boolean open(ClientGameTestContext ctx) {
		return client(ctx, mc -> mc.gui.screen() instanceof BotScreen);
	}

	/** Waits, moving the mouse now and then: after a minute without input the game drops to 30 fps and fps readings mean nothing. */
	private static void jiggle(ClientGameTestContext ctx, int ticks) {
		for (int i = 0; i < ticks; i += 10) {
			ctx.getInput().setCursorPos(800 + (i / 10 % 2), 450);
			ctx.waitTicks(10);
		}
	}

	private static void shot(ClientGameTestContext ctx, String name, int w, int h) {
		ctx.takeScreenshot(TestScreenshotOptions.of(name).withSize(w, h));
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			int w = 1600, h = 900;
			String size = System.getenv("HB_SIZE");
			if (size != null && size.contains("x")) {
				w = Integer.parseInt(size.split("x")[0]);
				h = Integer.parseInt(size.split("x")[1]);
			}
			ctx.getInput().resizeWindow(w, h);
			ctx.waitTicks(40);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			revive(ctx, sp);
			botOn(ctx);
			if (System.getenv("HB_PERF") != null) { // frame rate with the panel open, no screenshots in between
				for (UiStyle st : UiStyle.values()) {
					ctx.runOnClient(mc -> {
						Settings.UI_STYLE.set(st);
						mc.gui.setScreen(new BotScreen());
					});
					jiggle(ctx, 60);
					StringBuilder sb = new StringBuilder();
					for (String tab : new String[]{"Dashboard", "Humanise", "Interface"}) {
						ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).showTab(tab));
						jiggle(ctx, 50);
						int fps = client(ctx, mc -> mc.getFps());
						sb.append(tab).append("=").append(fps).append("  ");
					}
					report("perf-" + st.name().toLowerCase(), true, "fps " + sb);
					ctx.runOnClient(mc -> mc.gui.setScreen(null));
					jiggle(ctx, 60);
					report("perf-none-after-" + st.name().toLowerCase(), true, "fps " + client(ctx, mc -> mc.getFps()));
				}
				finish();
			}
			String only = System.getenv("HB_ONLY"), tabs = System.getenv("HB_TABS");
			for (UiStyle st : UiStyle.values()) {
				String n = st.name().toLowerCase();
				if (only != null && !only.toLowerCase().contains(n)) continue;
				ctx.runOnClient(mc -> {
					Settings.UI_STYLE.set(st);
					mc.gui.setScreen(new BotScreen());
				});
				ctx.waitTicks(45);
				for (String tab : TABS) {
					if (tabs != null && !tabs.contains(tab)) continue;
					ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).showTab(tab));
					ctx.waitTicks(45);
					shot(ctx, n + "-" + tab.toLowerCase(), w, h);
					report(n + "-" + tab, open(ctx), "panel must stay open (a draw error closes it) fps=" + client(ctx, mc -> mc.getFps()));
				}
				ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).showTab("Combat"));
				ctx.waitTicks(10);
				ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).openPicker(Settings.TARGETS));
				ctx.waitTicks(30);
				shot(ctx, n + "-picker", w, h);
				report(n + "-picker", open(ctx), "");
				ctx.runOnClient(mc -> mc.gui.setScreen(null));
				ctx.waitTicks(15);
				shot(ctx, n + "-hud", w, h); // the in-game status overlay, top left
			}
			if (only == null) {
				// a real mouse: hover and click a tab (confetti, ripple), then a click on the style selector itself
				UiStyle[] order = {UiStyle.MODERN, UiStyle.FUN, UiStyle.RELAXING};
				for (int i = 0; i < order.length && w == 1600; i++) {
					UiStyle st = order[i];
					ctx.runOnClient(mc -> {
						Settings.UI_STYLE.set(st);
						mc.gui.setScreen(new BotScreen());
					});
					ctx.waitTicks(40);
					int[] at = tabPixel(ctx, "Combat"); // read from the open panel: the tabs move when there are more of them
					ctx.getInput().setCursorPos(at[0], at[1]);
					ctx.waitTicks(12);
					shot(ctx, "hover-" + st.name().toLowerCase(), w, h);
					ctx.getInput().pressMouse(0);
					ctx.waitTicks(6);
					shot(ctx, "click-" + st.name().toLowerCase(), w, h);
					report("click-tab-" + st.name().toLowerCase(), client(ctx, mc -> mc.gui.screen() instanceof BotScreen) && "Combat".equals(currentTab(ctx)), "tab=" + currentTab(ctx));
					ctx.runOnClient(mc -> mc.gui.setScreen(null));
				}
				// the selector itself: a click on its "Fun" segment (Modern layout, 1600x900) switches the style
				if (w == 1600) {
					ctx.runOnClient(mc -> {
						Settings.UI_STYLE.set(UiStyle.MODERN);
						mc.gui.setScreen(new BotScreen());
						((BotScreen) mc.gui.screen()).showTab("Interface");
					});
					ctx.waitTicks(40);
					ctx.getInput().setCursorPos(1251, 322);
					ctx.waitTicks(5);
					ctx.getInput().pressMouse(0);
					ctx.waitTicks(30);
					shot(ctx, "selector-click", w, h);
					report("selector-click", Settings.UI_STYLE.get() == UiStyle.FUN, "style=" + Settings.UI_STYLE.get());
					ctx.runOnClient(mc -> mc.gui.setScreen(null));
				}
				// no animations: still scenery, nothing breaks
				ctx.runOnClient(mc -> Settings.UI_ANIM.set(false));
				for (UiStyle st : UiStyle.values()) {
					ctx.runOnClient(mc -> {
						Settings.UI_STYLE.set(st);
						mc.gui.setScreen(new BotScreen());
					});
					ctx.waitTicks(20);
					shot(ctx, "noanim-" + st.name().toLowerCase(), w, h);
					report("noanim-" + st.name().toLowerCase(), open(ctx), "");
					ctx.runOnClient(mc -> mc.gui.setScreen(null));
				}
				ctx.runOnClient(mc -> Settings.UI_ANIM.set(true));
				// switching while the panel is open: new layout, nothing breaks, the setting is what the selector shows
				ctx.runOnClient(mc -> {
					Settings.UI_STYLE.set(UiStyle.MINECRAFT);
					mc.gui.setScreen(new BotScreen());
					((BotScreen) mc.gui.screen()).showTab("Interface");
				});
				ctx.waitTicks(30);
				for (UiStyle st : new UiStyle[]{UiStyle.RELAXING, UiStyle.FUN, UiStyle.MODERN, UiStyle.MINECRAFT}) {
					ctx.runOnClient(mc -> Settings.UI_STYLE.set(st));
					ctx.waitTicks(8);
					shot(ctx, "switch-" + st.name().toLowerCase(), w, h);
					ctx.waitTicks(30);
					report("switch-" + st.name().toLowerCase(), open(ctx), "");
				}
				report("parse", Settings.UI_STYLE.parse("relaxing") && Settings.UI_STYLE.get() == UiStyle.RELAXING, "");
				Settings.UI_STYLE.set(UiStyle.MINECRAFT);
				ctx.runOnClient(mc -> mc.gui.setScreen(null));
			}
			finish();
		}
	}
}
