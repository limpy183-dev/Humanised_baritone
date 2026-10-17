package com.humanisedbaritone;

import com.humanisedbaritone.gui.Ui;
import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small always-visible status panel. */
public final class Hud {
	private Hud() {
	}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!Settings.HUD.on() || mc.player == null) return;
		var pl = com.humanisedbaritone.build.Placement.current;
		boolean pending = pl != null && !pl.confirmed && !Bot.busy(); // a schematic is waiting for its confirmation
		if (!Bot.enabled && !Bot.busy() && !pending) return;
		Ui.sync();
		String l1 = pending ? "Schematic: " + pl.name : Bot.headline();
		String l2 = pending ? "Waiting for confirmation: open the menu" : Bot.detail();
		int w = Math.max(96, Math.max(Ui.width(l1), Ui.width(l2)) + 20);
		float prog = pending ? -1 : Bot.progress();
		int h = prog >= 0 ? 31 : 27;
		int sw = g.guiWidth(), sh = g.guiHeight();
		int x = switch (Settings.HUD_CORNER.get()) {
			case TOP_LEFT, BOTTOM_LEFT -> 6;
			default -> sw - w - 6;
		};
		int y = switch (Settings.HUD_CORNER.get()) {
			case TOP_LEFT, TOP_RIGHT -> 6;
			default -> sh - h - 6;
		};
		Ui.hud(g, x, y, w, h, Bot.alert());
		Ui.text(g, Ui.fit(l1, w - 20), x + 12, y + 5, Ui.TEXT);
		Ui.text(g, Ui.fit(l2, w - 20), x + 12, y + 16, Ui.DIM);
		if (prog >= 0) Ui.progress(g, x + 12, y + h - 7, w - 24, 3, prog);
	}
}
