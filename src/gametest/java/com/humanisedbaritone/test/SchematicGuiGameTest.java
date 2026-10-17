package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.build.Placement;
import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.gui.W;
import com.humanisedbaritone.task.Tasks;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The Build flow in the panel, with real mouse clicks: Build... opens the list, a click on the schematic loads it and shows the preview, the card moves and turns it,
 * and only the confirm button starts the building. Positions of the widgets are read from the open panel, so the test does not depend on pixel coordinates.
 */
public class SchematicGuiGameTest extends BotTest {
	private static Object field(Object o, String name) {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(o);
			} catch (NoSuchFieldException ignored) {
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
		throw new RuntimeException("no field " + name + " in " + o.getClass());
	}

	/** The button with this label somewhere in a widget tree: cards keep their buttons in fields, lists or arrays. */
	private static W.Btn findBtn(Object o, String label, int depth) throws IllegalAccessException {
		if (o == null || depth > 3) return null;
		if (o instanceof W.Btn b) return label.equals(b.label) ? b : null;
		if (o instanceof Iterable<?> it) {
			for (Object e : it) {
				W.Btn r = findBtn(e, label, depth + 1);
				if (r != null) return r;
			}
			return null;
		}
		if (o instanceof Object[] arr) {
			for (Object e : arr) {
				W.Btn r = findBtn(e, label, depth + 1);
				if (r != null) return r;
			}
			return null;
		}
		if (o instanceof W) {
			for (Field f : o.getClass().getDeclaredFields()) {
				f.setAccessible(true);
				W.Btn r = findBtn(f.get(o), label, depth + 1);
				if (r != null) return r;
			}
		}
		return null;
	}

	private static int[] pixel(Minecraft mc, BotScreen scr, W w) {
		float s = (float) field(scr, "s");
		double gs = mc.getWindow().getGuiScale();
		return new int[]{(int) Math.round((w.x + w.w / 2.0) * s * gs), (int) Math.round((w.y + w.h / 2.0) * s * gs)};
	}

	private void clickAt(ClientGameTestContext ctx, int[] at) {
		ctx.getInput().setCursorPos(at[0], at[1]);
		ctx.waitTicks(3);
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(4);
	}

	/** The dashboard is taller than the panel: scroll until the button is in view (a click outside the panel would close it). */
	private void reveal(ClientGameTestContext ctx, String label) {
		client(ctx, mc -> {
			try {
				BotScreen scr = (BotScreen) mc.gui.screen();
				W.ScrollArea content = (W.ScrollArea) field(scr, "content");
				W card = null; // buttons are only placed while their card is drawn, so the card's row says where to scroll to
					for (W r : content.rows) if (findBtn(r, label, 1) != null) card = r;
					if (card == null) return false;
					Field sc = W.ScrollArea.class.getDeclaredField("scroll"), tg = W.ScrollArea.class.getDeclaredField("target");
					sc.setAccessible(true);
					tg.setAccessible(true);
					float want = sc.getFloat(content) + (card.y + card.h / 2f) - (content.y + content.h / 2f); // the card in the middle of the area
					tg.setFloat(content, Math.max(0, want));
					return true;
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		ctx.waitTicks(20);
	}

	private void scrollTop(ClientGameTestContext ctx) {
		client(ctx, mc -> {
			try {
				Field tg = W.ScrollArea.class.getDeclaredField("target");
				tg.setAccessible(true);
				tg.setFloat(field(mc.gui.screen(), "content"), 0);
				return true;
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		ctx.waitTicks(20);
	}

	private void clickButton(ClientGameTestContext ctx, String label) {
		reveal(ctx, label);
		int[] at = client(ctx, mc -> {
			try {
				BotScreen scr = (BotScreen) mc.gui.screen();
				W.ScrollArea content = (W.ScrollArea) field(scr, "content");
				W.Btn b = findBtn(content.rows, label, 0);
				return b == null ? null : pixel(mc, scr, b);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		});
		if (at == null) throw new RuntimeException("no button '" + label + "' on the dashboard");
		System.out.println("[HBTEST]    click '" + label + "' at " + at[0] + "," + at[1] + " " + client(ctx, mc -> {
			BotScreen scr = (BotScreen) mc.gui.screen();
			W.ScrollArea content = (W.ScrollArea) field(scr, "content");
			return "s=" + field(scr, "s") + " gui=" + mc.getWindow().getGuiScale() + " win=" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
				+ " content=" + content.x + "," + content.y + " " + content.w + "x" + content.h + " scroll=" + field(content, "scroll");
		}));
		clickAt(ctx, at);
	}

	private static BlockPos origin() {
		return Placement.current == null ? null : Placement.current.origin;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1600, 900);
			ctx.waitTicks(40);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful", "gamemode creative @a");
			PickleGameTest.install();
			load(ctx, sp, 180, 180, 240, 240);
			cmds(sp, "tp @a 200.5 -60 200.5 -90 15");
			ctx.waitTicks(20);
			botOn(ctx);
			ctx.runOnClient(mc -> mc.gui.setScreen(new BotScreen()));
			ctx.waitTicks(25);
			ctx.takeScreenshot(TestScreenshotOptions.of("schem-1-dashboard").withSize(1600, 900));
			report("no-card-before", client(ctx, mc -> Placement.current == null), "");

			// Build... opens the list of schematics; a click on one loads it
			clickButton(ctx, "Build...");
			ctx.waitTicks(12);
			ctx.takeScreenshot(TestScreenshotOptions.of("schem-2-list").withSize(1600, 900));
			int[] row = client(ctx, mc -> {
				BotScreen scr = (BotScreen) mc.gui.screen();
				W modal = (W) field(scr, "modal");
				W.ScrollArea list = (W.ScrollArea) field(modal, "list");
				return list.rows.isEmpty() ? null : pixel(mc, scr, list.rows.get(0));
			});
			if (row == null) throw new RuntimeException("the list of schematics is empty");
			clickAt(ctx, row);
			ctx.waitTicks(25);
			ctx.takeScreenshot(TestScreenshotOptions.of("schem-3-loaded").withSize(1600, 900));
			report("loaded", client(ctx, mc -> Placement.current != null && !Placement.current.confirmed && Bot.busy() == false), "origin=" + client(ctx, mc -> origin()));
			BlockPos o0 = client(ctx, mc -> origin());
			report("in-front", o0 != null && o0.getX() >= 200 && Math.abs(o0.getZ() - 197) <= 1 && o0.getY() == -60, "origin=" + o0 + " (the player stands at 200.5 -60 200.5 facing east)");
			report("not-started", client(ctx, mc -> Tasks.current() == null), "nothing may be built before the confirm");

			// the arrows move it the way the player faces (east): one step forward is one block east
			clickButton(ctx, "Forward");
			ctx.waitTicks(4);
			BlockPos o1 = client(ctx, mc -> origin());
			report("forward", o0 != null && o1 != null && o1.equals(o0.east()), "origin " + o0 + " -> " + o1);
			clickButton(ctx, "Up");
			ctx.waitTicks(4);
			report("up", client(ctx, mc -> origin().getY() == -59), "origin=" + client(ctx, mc -> origin()));
			clickButton(ctx, "Down");
			ctx.waitTicks(4);
			clickButton(ctx, "Step 1"); // the step goes from 1 to 5
			ctx.waitTicks(3);
			clickButton(ctx, "Left");
			ctx.waitTicks(4);
			BlockPos o2 = client(ctx, mc -> origin());
			report("left-by-5", o1 != null && o2 != null && o2.equals(o1.north(5)), "origin " + o1 + " -> " + o2 + " (facing east, left is north)");

			// typed coordinates
				scrollTop(ctx);
				int[] xbox = client(ctx, mc -> {
				BotScreen scr = (BotScreen) mc.gui.screen();
				W.ScrollArea content = (W.ScrollArea) field(scr, "content");
				W card = content.rows.get(0);
				return pixel(mc, scr, (W) field(card, "bx"));
			});
			clickAt(ctx, xbox);
				for (int i = 0; i < 6; i++) ctx.getInput().pressKey(InputConstants.KEY_BACKSPACE); // the box is cleared by hand (a simulated Ctrl+A carries no modifier)
				ctx.getInput().typeChars("215");
			ctx.waitTicks(3);
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);
			ctx.waitTicks(5);
			report("typed-x", client(ctx, mc -> origin().getX() == 215), "origin=" + client(ctx, mc -> origin()));

			clickButton(ctx, "Rotate");
			ctx.waitTicks(4);
			report("rotate", client(ctx, mc -> Placement.current.turns() == 1), "turns=" + client(ctx, mc -> Placement.current.turns()));
			ctx.takeScreenshot(TestScreenshotOptions.of("schem-4-moved").withSize(1600, 900));
			report("still-not-started", client(ctx, mc -> Tasks.current() == null && !Placement.current.confirmed), "");

			// only the confirm button starts the build
			clickButton(ctx, "Confirm and build");
			ctx.waitTicks(30);
			report("confirmed", client(ctx, mc -> Placement.current != null && Placement.current.confirmed && Tasks.current() != null && "BUILD".equals(Tasks.current().tag())),
				"task=" + client(ctx, mc -> Tasks.current() == null ? "none" : Tasks.current().name()));
			ctx.takeScreenshot(TestScreenshotOptions.of("schem-5-building").withSize(1600, 900));
			ctx.runOnClient(mc -> Commands.run("stop"));
			ctx.waitTicks(10);

			// cancel drops the schematic and the card goes with it
			clickButton(ctx, "Cancel");
			ctx.waitTicks(15);
			report("cancel", client(ctx, mc -> Placement.current == null && mc.gui.screen() instanceof BotScreen), "");
			ctx.takeScreenshot(TestScreenshotOptions.of("schem-6-cancelled").withSize(1600, 900));
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			finish();
		}
	}
}
