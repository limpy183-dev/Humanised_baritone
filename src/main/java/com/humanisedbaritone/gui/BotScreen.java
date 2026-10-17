package com.humanisedbaritone.gui;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.HumanisedBaritone;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.gui.Ui.Anim;
import com.humanisedbaritone.settings.Setting;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.settings.Settings.UiStyle;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The Humanised Baritone control panel. Its look (font, layout, shapes, motion) comes from the interface style, see {@link Theme}. */
public final class BotScreen extends Screen implements W.Host {
	public static final String DASH = "Dashboard", PROFILES = "Profiles", MAP = "Map";
	private static final String[] TABS = {DASH, Settings.LOGGING, MAP, Settings.GENERAL, Settings.HUMAN, Settings.MOVE, Settings.WORLD, Settings.COMBAT, Settings.SAFETY, Settings.SURVIVAL, Settings.CHAT, Settings.UI, Settings.ADV, PROFILES};
	private static final Item[] ICONS = {Items.COMPASS, Items.CHEST, Items.FILLED_MAP, Items.COMPARATOR, Items.PLAYER_HEAD, Items.DIAMOND_BOOTS, Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD, Items.SHIELD,
		Items.GOLDEN_APPLE, Items.PAPER, Items.SPYGLASS, Items.REDSTONE, Items.WRITABLE_BOOK};
	private static final int N = TABS.length;
	/** The first of the settings tabs: Dashboard, Logging and Map come before it, Profiles after the last. */
	private static final int FIRST_SETTINGS = 3;
	private static String lastTab = DASH;

	private String tab = lastTab, prevTab = lastTab;
	/** UI scale relative to the GUI scale, and the screen size in panel units. */
	private float s = 1;
	private int uw, uh;
	private int px, py, pw, ph, tabH = 18, railSize = 20, tabIw = 28;
	/** The content area (below the header and the tab strip, beside the tab column). */
	private int cx, cy, cw, ch;
	/** Where each tab button is; filled in every frame, the layout decides the shape. */
	private final int[] tx = new int[N], ty = new int[N], tw = new int[N], th = new int[N];
	private final W.ScrollArea content = new W.ScrollArea();
	private final W.TextBox search = new W.TextBox("Search all settings...", 32, t -> rebuild(false));
	private final W.Toggle master = new W.Toggle(() -> Bot.enabled, Bot::setEnabled);
	private final W.Btn closeBtn = new W.Btn("x", W.Style.GHOST, this::onClose);
	private W modal, focused;
	private String tip = "";
	private long lastNanos = System.nanoTime();
	private final Anim open = new Anim(0), indY = new Anim(Float.NaN), titleA = new Anim(1), logoHov = new Anim(0);
	private final Anim[] tabHov = new Anim[N], tabSel = new Anim[N];
	/** The style the layout was computed for; a different one in the setting re-lays out the panel. */
	private UiStyle shownStyle;
	/** Whether the dashboard was built with the schematic card. */
	private boolean hadPlacement = com.humanisedbaritone.build.Placement.current != null;

	public BotScreen() {
		super(Component.literal("Humanised Baritone"));
		master.compact = true;
		for (int i = 0; i < N; i++) {
			tabHov[i] = new Anim(0);
			tabSel[i] = new Anim(TABS[i].equals(tab) ? 1 : 0);
		}
	}

	// ---------------------------------------------------------------- layout

	@Override protected void init() {
		Ui.sync();
		layout();
		shownStyle = Ui.theme().style;
		rebuild(true);
	}

	private void layout() {
		Theme t = Ui.theme();
		var win = minecraft.getWindow();
		double gs = win.getGuiScale();
		int e = switch (Settings.UI_SCALE.get()) {
			case AUTO -> Mth.clamp(win.getWidth() / 500, 1, 6);
			case X1 -> 1;
			case X2 -> 2;
			case X3 -> 3;
			case X4 -> 4;
			case X5 -> 5;
		};
		while (e > 1 && (win.getWidth() / e < 360 || win.getHeight() / e < 250)) e--;
		s = (float) (e / gs);
		uw = Math.round(width / s);
		uh = Math.round(height / s);
		pw = Mth.clamp(uw - 16, 340, 560);
		ph = Mth.clamp(uh - 16, 240, 340);
		px = (uw - pw) / 2;
		py = (uh - ph) / 2;
		boolean groups = t.style == UiStyle.MODERN; // the side bar is split into three groups there
		int below = t.ftrH + 12 + (groups ? 10 : 0); // leave room for the footer and group gaps in every sidebar
		tabH = Mth.clamp((ph - t.hdrH - below) / N, 12, 20);
		railSize = Mth.clamp((ph - t.hdrH - t.ftrH - 24) / N - 3, 10, 24);
		cx = px + t.sbW + t.pad;
		cy = py + t.hdrH + t.tabBar + t.pad;
		cw = pw - t.sbW - t.pad * 2;
		ch = ph - t.hdrH - t.tabBar - t.ftrH - t.pad * 2 + 2;
		content.gap = t.gap;
	}

	/** The style setting changed while the panel is open: new layout, new rows, and the panel pops in again. */
	private void restyle() {
		layout();
		shownStyle = Ui.theme().style;
		open.v = Math.min(open.v, 0.55f);
		open.vel = 0;
		indY.v = Float.NaN;
		rebuild(true);
	}

	private int indexOf(String t) {
		for (int i = 0; i < N; i++) if (TABS[i].equals(t)) return i;
		return 0;
	}

	private void rebuild(boolean slide) {
		Theme t = Ui.theme();
		content.clear(slide);
		String q = search.text().toLowerCase(Locale.ROOT).trim();
		if (!q.isEmpty() && isSettingsTab(tab)) {
			String lastCat = null;
			int n = 0;
			for (Setting<?> st : Setting.ALL) {
				if (!(st.name.toLowerCase(Locale.ROOT).contains(q) || st.desc.toLowerCase(Locale.ROOT).contains(q) || st.id.toLowerCase(Locale.ROOT).contains(q))) continue;
				if (!st.category.equals(lastCat)) {
					lastCat = st.category;
					content.add(new SettingRow.Header(st.category), t.headH);
				}
				content.add(new SettingRow(st, q), t.rowH);
				n++;
			}
			if (n == 0) content.add(new W.Label(() -> "No setting matches \"" + q + "\"", Ui.MUTED, false), 14);
		} else if (tab.equals(DASH)) {
			Pages.dashboard(content, this);
		} else if (tab.equals(PROFILES)) {
			Pages.profiles(content, this, () -> rebuild(false));
		} else if (tab.equals(MAP)) {
			content.add(new MapPage(), ch); // one widget that fills the whole page
		} else {
			String lastGroup = null;
			if (tab.equals(Settings.CHAT)) {
				content.add(new W.Label(() -> Bot.enabled ? "Listening while each challenge toggle is ON." : "Turn the bot ON to enable chat replies.", Ui.DIM, false), 15);
				content.add(new W.Label(com.humanisedbaritone.chat.ChatChallenges::status, Ui.MUTED, false), 15);
			}
			if (tab.equals(Settings.HUMAN)) {
				content.add(new SettingRow.Header("Presets"), t.headH);
				content.add(new Pages.PresetRow(), Pages.presetH());
			}
			if (tab.equals(Settings.LOGGING)) LogPage.build(content, ch - 21); // the log first, then what it looks for
			for (Setting<?> st : Settings.in(tab)) {
				if (!st.group.equals(lastGroup)) {
					lastGroup = st.group;
					content.add(new SettingRow.Header(st.group), t.headH);
				}
				content.add(new SettingRow(st, null), t.rowH);
			}
		}
	}

	private static boolean isSettingsTab(String t) {
		return !t.equals(DASH) && !t.equals(PROFILES) && !t.equals(MAP);
	}

	/** Opens the list editor of a setting (used by tests). */
	public void openPicker(Setting.IdSet s) {
		Picker.open(this, s);
	}

	/** Jumps to a tab by name (used by tests and commands). */
	public void showTab(String t) {
		selectTab(t);
	}

	@Override public void openTab(String t) {
		selectTab(t);
	}

	private void selectTab(String t) {
		if (t.equals(tab)) return;
		if (focused != null) focus(null);
		prevTab = tab;
		titleA.v = 0;
		titleA.vel = 0;
		tab = lastTab = t;
		search.setText("");
		rebuild(true);
	}

	// ------------------------------------------------------------------ host

	@Override public void focus(W w) {
		if (focused == w) return;
		W old = focused;
		focused = w;
		if (old != null) old.unfocus();
	}

	@Override public boolean isFocused(W w) {
		return focused == w;
	}

	@Override public void tip(String t) {
		tip = t;
	}

	@Override public void modal(W w) {
		modal = w;
		if (w instanceof Picker p) focus(p.searchBox());
	}

	@Override public void closeModal() {
		modal = null;
		focus(null);
	}

	@Override public boolean isPauseScreen() {
		return false;
	}

	// ----------------------------------------------------------------- render

	@Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		Ui.sync();
		float o = open.v;
		var placing = com.humanisedbaritone.build.Placement.current;
		float lift = placing != null && !placing.confirmed ? 0.4f : 1f; // a schematic waits for its confirmation: the world behind it must stay readable
		g.fill(0, 0, width, height, Ui.alpha(0xFF04060C, (float) Settings.UI_DIM.d() * o * lift));
		g.fillGradient(0, 0, width, height, 0x00000000, Ui.alpha(0xFF000000, 0.35f * o));
		Fx.backdrop(g, width, height, Math.min(1, o));
	}

	@Override public void extractRenderState(GuiGraphicsExtractor g, int rawMx, int rawMy, float partial) {
		if (broken) return;
		try {
			draw(g, rawMx, rawMy, partial);
		} catch (RuntimeException e) {
			crashed(e);
		}
	}

	private void draw(GuiGraphicsExtractor g, int rawMx, int rawMy, float partial) {
		long now = System.nanoTime();
		float dt = Mth.clamp((now - lastNanos) / 1e9f, 0.0005f, 0.1f);
		lastNanos = now;
		Ui.sync();
		Theme t = Ui.theme();
		if (t.style != shownStyle) restyle();
		boolean placing = com.humanisedbaritone.build.Placement.current != null;
		if (placing != hadPlacement) { // a schematic was loaded or dropped: the dashboard gains or loses its schematic card
			hadPlacement = placing;
			if (tab.equals(DASH)) rebuild(false);
		}
		float o = open.to(1, dt, 16);
		extractBackground(g, rawMx, rawMy, partial);
		tip = "";
		int mx = Math.round(rawMx / s), my = Math.round(rawMy / s);
		Fx.follow(mx, my, dt);
		W.Frame f = new W.Frame(g, modal != null ? -9999 : mx, modal != null ? -9999 : my, dt, this);
		int acc = Ui.accent();

		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(s, s);
		float ccx = px + pw / 2f, ccy = py + ph / 2f, k = 1 - (1 - o) * t.openShrink;
		pose.translate(ccx, ccy + (1 - o) * t.openDrop); // the panel settles in from below, or pops up
		pose.scale(k, k);
		pose.translate(-ccx, -ccy);

		Skin.panel(g, px, py, pw, ph, o, mx, my);
		placeTabs(dt);
		drawHeader(g, f, t, acc);
		drawTabs(g, f, t, acc);

		int off = isSettingsTab(tab) ? 21 : 0;
		content.at(cx, cy + off, cw, ch - off);
		if (isSettingsTab(tab)) {
			search.at(cx, cy, cw - 7, 17);
			search.render(f);
		}
		content.render(f);

		int fy = py + ph - t.ftrH, fx = px + t.pad + 4;
		if (t.style == UiStyle.MINECRAFT || t.style == UiStyle.MODERN) Ui.rect(g, px + 10, fy, pw - 20, 1, Ui.alpha(0xFFFFFFFF, 0.06f));
		String closeText = "Press " + HumanisedBaritone.OPEN_KEY.getTranslatedKeyMessage().getString() + " to close";
		int closeW = Ui.width(closeText);
		String hint = tip.isEmpty() ? "Right-click: reset    Double-click: type a value" : tip;
		var lines = Ui.wrap(hint, pw - 2 * (t.pad + 4) - 16 - closeW, 2);
		for (int i = 0; i < lines.size(); i++) Ui.text(g, lines.get(i), fx, fy + 5 + i * 10, tip.isEmpty() ? Ui.MUTED : Ui.DIM);
		Ui.textR(g, closeText, px + pw - t.pad - 4, fy + 5, Ui.MUTED);

		if (t.layout == Theme.Layout.RAIL) drawRailLabels(g, t, acc);
		if (modal != null) {
			modal.at(px, py, pw, ph);
			modal.render(new W.Frame(g, mx, my, dt, this));
		}
		g.enableScissor(px, py, px + pw, py + ph);
		Fx.overlay(g, dt);
		g.disableScissor();
		pose.popMatrix();
	}

	// ---------------------------------------------------------------- header

	private void drawHeader(GuiGraphicsExtractor g, W.Frame f, Theme t, int acc) {
		int hh = t.hdrH, ctlY = py + (hh - 14) / 2, mid = py + hh / 2;
		boolean overLogo = modal == null && f.mx >= px + 6 && f.mx < px + 46 && f.my >= py + 3 && f.my < py + hh - 3;
		float lh = logoHov.to(overLogo ? 1 : 0, f.dt, 10);
		switch (t.style) {
			case MINECRAFT -> {
				Skin.logo(g, px + 14, py + 11, lh);
				Ui.text(g, "Humanised Baritone", px + 24, py + 6, Ui.TEXT);
				Ui.text(g, "pathfinding that moves like a person", px + 24, py + 16, Ui.MUTED);
			}
			case MODERN -> {
				Skin.logo(g, px + 19, mid, lh);
				Ui.text(g, "Humanised Baritone", px + 35, py + 8, Ui.TEXT);
				Ui.text(g, "pathfinding that moves like a person", px + 35, py + 20, Ui.MUTED);
			}
			case FUN -> {
				Skin.logo(g, px + 26, mid, lh);
				Ui.text(g, "Humanised Baritone", px + 45, py + 9, Ui.TEXT);
				Ui.text(g, "pathfinding that moves like a person", px + 45, py + 21, Ui.alpha(Ui.DIM, 0.9f));
			}
			case RELAXING -> {
				Skin.logo(g, px + 28, mid, lh);
				Ui.text(g, "HUMANISED BARITONE", px + 48, py + 8, Ui.MUTED);
				float ta = titleA.to(1, f.dt, 7);
				var pose = g.pose();
				if (ta < 0.4f) { // the page title of the tab you left sinks and fades quickly, then the new one rises in
					pose.pushMatrix();
					pose.translate(px + 48, py + 19 + ta * 8);
					pose.scale(1.2f, 1.2f);
					Ui.text(g, prevTab, 0, 0, Ui.alpha(Ui.TEXT, 1 - ta * 2.5f));
					pose.popMatrix();
				}
				float in = Mth.clamp((ta - 0.3f) / 0.7f, 0, 1);
				pose.pushMatrix();
				pose.translate(px + 48, py + 19 + (1 - in) * 8);
				pose.scale(1.2f, 1.2f);
				Ui.text(g, tab, 0, 0, Ui.alpha(Ui.TEXT, in));
				pose.popMatrix();
			}
		}

		closeBtn.at(px + pw - 24, ctlY, 16, 14);
		int mw = t.style == UiStyle.FUN ? 38 : 30; // the switch is wider in some styles
		master.at(px + pw - 24 - 6 - mw, ctlY, mw, 14);
		String bot = "Bot";
		int botX = master.x - 6 - Ui.width(bot);
		Ui.text(g, bot, botX, ctlY + 4, Ui.DIM);

		String st = Bot.status();
		int chipW = Ui.width(st) + 18;
		int chipX = botX - 12 - chipW;
		int sc = !Bot.enabled ? Ui.MUTED : Bot.alert() ? Ui.WARN : Bot.busy() ? Ui.GOOD : Ui.DIM;
		Ui.rrect(g, chipX, ctlY, chipW, 14, 7, Ui.alpha(sc, t.style == UiStyle.FUN ? 0.3f : 0.16f));
		if (t.style == UiStyle.FUN) Ui.rring(g, chipX, ctlY, chipW, 14, 7, Ui.alpha(sc, 0.9f));
		float pulse = Bot.enabled && Bot.busy() ? 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 260.0) : 1f;
		Ui.rrect(g, chipX + 6, ctlY + 5, 4, 4, 2, Ui.alpha(sc, pulse));
		Ui.text(g, st, chipX + 13, ctlY + 3, sc);

		master.render(f);
		closeBtn.render(f);
		switch (t.style) {
			case MINECRAFT, MODERN -> Ui.rect(g, px + 1, py + hh, pw - 2, 1, Ui.alpha(0xFFFFFFFF, 0.06f));
			case FUN -> Skin.stripe(g, px + 14, py + hh - 3, pw - 28);
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ tabs

	private void placeTabs(float dt) {
		Theme t = Ui.theme();
		int sel = indexOf(tab);
		for (int i = 0; i < N; i++) tabSel[i].to(i == sel ? 1 : 0, dt, 14);
		switch (t.layout) {
			case SIDEBAR -> {
				boolean groups = t.style == UiStyle.MODERN; // Dashboard, Logging and Map | the settings | Profiles
				int sy = py + t.hdrH + 7;
				for (int i = 0; i < N; i++) {
					tx[i] = px + 6;
					ty[i] = sy + i * tabH + (groups ? (i >= FIRST_SETTINGS ? 5 : 0) + (i == N - 1 ? 5 : 0) : 0);
					tw[i] = t.sbW - 6;
					th[i] = tabH;
				}
			}
			case RAIL -> {
				for (int i = 0; i < N; i++) {
					tx[i] = px + (t.sbW - railSize) / 2;
					ty[i] = py + t.hdrH + 10 + i * (railSize + 3);
					tw[i] = th[i] = railSize;
				}
			}
			case TOPBAR -> {
				int gap = 4, avail = pw - 20, maxLabel = 0;
				for (String name : TABS) maxLabel = Math.max(maxLabel, Ui.width(name) + 6);
				int iw = Mth.clamp((avail - gap * (N - 1) - maxLabel) / N, 14, 28), at = px + 10;
				tabIw = iw;
				for (int i = 0; i < N; i++) {
					tx[i] = at;
					ty[i] = py + t.hdrH + 3;
					tw[i] = iw + Math.max(0, Math.round(tabSel[i].v * (Ui.width(TABS[i]) + 6)));
					th[i] = t.tabBar - 6;
					at += tw[i] + gap;
				}
			}
		}
	}

	private void icon(GuiGraphicsExtractor g, int i, float x, float y, float scale) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		g.fakeItem(new ItemStack(ICONS[i]), 0, 0);
		pose.popMatrix();
	}

	private void drawTabs(GuiGraphicsExtractor g, W.Frame f, Theme t, int acc) {
		int sel = indexOf(tab);
		float[] hv = new float[N];
		for (int i = 0; i < N; i++) {
			boolean hot = modal == null && f.mx >= tx[i] && f.mx < tx[i] + tw[i] && f.my >= ty[i] && f.my < ty[i] + th[i];
			hv[i] = tabHov[i].to(hot ? 1 : 0, f.dt, 16);
			if (hot) tip = tabInfo(TABS[i]);
		}
		if (Float.isNaN(indY.v)) indY.v = ty[sel];
		float iy = indY.to(ty[sel], f.dt, 18);
		switch (t.layout) {
			case SIDEBAR -> {
				boolean modern = t.style == UiStyle.MODERN;
				int sx = tx[0], sw = tw[0], r = modern ? t.ctlR : 4;
				Ui.rect(g, px + t.sbW, py + t.hdrH + 1, 1, ph - t.hdrH - t.ftrH - 1, Ui.alpha(0xFFFFFFFF, 0.05f));
				if (modern) {
					Ui.rect(g, sx + 6, ty[FIRST_SETTINGS] - 4, sw - 12, 1, Ui.alpha(0xFFFFFFFF, 0.07f));
					Ui.rect(g, sx + 6, ty[N - 1] - 4, sw - 12, 1, Ui.alpha(0xFFFFFFFF, 0.07f));
				}
				int barY = Math.round(iy);
				if (modern) Ui.glow(g, sx + 2, barY + (tabH - 1) / 2f, 26, tabH * 0.9f, acc, 0.3f);
				Ui.rrect(g, sx, barY, sw, tabH - 1, r, Ui.alpha(acc, modern ? 0.14f : 0.17f));
				Ui.rrect(g, sx, barY + 3, 2, tabH - 7, 1, acc);
				for (int i = 0; i < N; i++) {
					boolean isSel = i == sel;
					if (hv[i] > 0.01f && !isSel) Ui.rrect(g, sx, ty[i], sw, tabH - 1, r, Ui.alpha(0xFFFFFFFF, 0.06f * hv[i]));
					int nudge = modern ? Math.round(hv[i] * 2) : 0;
					icon(g, i, sx + 6 + nudge, ty[i] + (tabH - 12) / 2f - 0.5f, 0.72f);
					Ui.text(g, Ui.fit(TABS[i], sw - 26), sx + 22 + nudge, ty[i] + (tabH - 8) / 2, isSel ? Ui.TEXT : Ui.mix(Ui.DIM, Ui.TEXT, hv[i]));
				}
			}
			case TOPBAR -> {
				for (int i = 0; i < N; i++) {
					float ts = Mth.clamp(tabSel[i].v, 0, 1), h = hv[i];
					int lift = Math.round(-h * 2 - ts), x = tx[i], y = ty[i] + lift, w = tw[i], hh = th[i], r = hh / 2;
					Ui.rrect(g, x + 2, ty[i] + 2, w, hh, r, Ui.STROKE);
					Ui.rrect(g, x, y, w, hh, r, Ui.STROKE);
					Ui.rrect(g, x + 2, y + 2, w - 4, hh - 4, Math.max(1, r - 2), Ui.mix(Ui.mix(Ui.CARD_HI, Ui.TEXT, 0.12f * h), acc, ts));
					icon(g, i, x + (tabIw - 12) / 2f, y + (hh - 12) / 2f - 0.5f, 0.72f);
					if (ts > 0.25f) {
						g.enableScissor(x + 2, y, x + w - 2, y + hh);
						Ui.text(g, TABS[i], x + tabIw - 3, y + (hh - 8) / 2, Ui.alpha(Ui.ON_ACCENT, Mth.clamp((ts - 0.25f) * 1.6f, 0, 1)));
						g.disableScissor();
					}
				}
			}
			case RAIL -> {
				int first = ty[0] - 5, last = ty[N - 1] + railSize + 5, mid = px + t.sbW / 2;
				Ui.rrect(g, px + 6, first, t.sbW - 12, last - first, (t.sbW - 12) / 2, Ui.alpha(0xFFFFFFFF, 0.05f));
				float breath = 0.75f + 0.25f * (float) Math.sin(Fx.time() * 1.6f), oy = iy + railSize / 2f;
				Ui.glow(g, mid, oy, 22, 22, acc, 0.35f * breath);
				Ui.circle(g, mid, oy, railSize / 2f + 2, Ui.alpha(acc, 0.22f));
				Ui.rring(g, Math.round(mid - railSize / 2f - 2), Math.round(oy - railSize / 2f - 2), railSize + 4, railSize + 4, (railSize + 4) / 2, Ui.alpha(acc, 0.65f));
				for (int i = 0; i < N; i++) {
					if (hv[i] > 0.01f && i != sel) Ui.circle(g, mid, ty[i] + railSize / 2f, railSize / 2f + 1, Ui.alpha(0xFFFFFFFF, 0.09f * hv[i]));
					icon(g, i, mid - 5.8f, ty[i] + (railSize - 12) / 2f - 0.5f - hv[i] * 1.5f, 0.72f);
				}
			}
		}
	}

	/** Names that float out beside the rail icons when one is hovered. */
	private void drawRailLabels(GuiGraphicsExtractor g, Theme t, int acc) {
		for (int i = 0; i < N; i++) {
			float h = tabHov[i].v;
			if (h < 0.03f) continue;
			int lw = Ui.width(TABS[i]) + 16, lx = tx[i] + tw[i] + 8 + Math.round((h - 1) * 6), ly = ty[i] + (railSize - 16) / 2;
			Ui.shadow(g, lx, ly, lw, 16, 8, 0xFF000000, 4, 0.4f * h);
			Ui.rrect(g, lx, ly, lw, 16, 8, Ui.alpha(Ui.mix(Ui.BG2, 0xFFFFFFFF, 0.16f), Math.min(1, h * 1.3f)));
			Ui.rring(g, lx, ly, lw, 16, 8, Ui.alpha(acc, 0.5f * h));
			Ui.text(g, TABS[i], lx + 8, ly + 4, Ui.alpha(Ui.TEXT, Math.min(1, h * 1.3f)));
		}
	}

	private static String tabInfo(String t) {
		return switch (t) {
			case DASH -> "Status, travel, tasks and the command console.";
			case Settings.LOGGING -> "What the scanner found: stashes, spawners, players, portals, valuable items... and what it looks for.";
			case MAP -> "The terrain you have seen, you on it, and every logged find. Saved per world and dimension.";
			case Settings.GENERAL -> "Chat commands, feedback and basic behaviour of the bot.";
			case Settings.HUMAN -> "Everything that makes the bot look like a person: camera, timing, variation.";
			case Settings.MOVE -> "What kinds of movement the pathfinder may use.";
			case Settings.WORLD -> "Breaking, placing, mining, farming and building.";
			case Settings.COMBAT -> "Fighting style, targets and retreat rules.";
			case Settings.SAFETY -> "Hazards, mobs, breathing and emergencies.";
			case Settings.SURVIVAL -> "Eating, armor, totems and item pick-up.";
			case Settings.CHAT -> "Automatic challenge replies with a random response delay for each game.";
			case Settings.UI -> "Interface style, colours, overlay and in-world rendering.";
			case Settings.ADV -> "Planner limits and diagnostics.";
			default -> "Humanising presets, saved profiles and waypoints.";
		};
	}

	// ------------------------------------------------------------------ input

	private boolean inPanel(double mx, double my) {
		return mx >= px && mx < px + pw && my >= py && my < py + ph;
	}

	@Override public boolean mouseClicked(MouseButtonEvent ev, boolean dbl) {
		return safe(() -> click(ev, dbl));
	}

	private boolean click(MouseButtonEvent ev, boolean dbl) {
		Theme t = Ui.theme();
		double mx = ev.x() / s, my = ev.y() / s;
		int btn = ev.button();
		if (open.v < 0.9f) return true;
		if (btn == 0 && inPanel(mx, my)) Fx.click((float) mx, (float) my);
		if (modal != null) {
			modal.mouseClicked(mx, my, btn, dbl);
			return true;
		}
		if (!inPanel(mx, my)) {
			onClose();
			return true;
		}
		if (focused != null && !focused.over(mx, my)) focus(null);
		if (closeBtn.mouseClicked(mx, my, btn, dbl) || master.mouseClicked(mx, my, btn, dbl)) return true;
		for (int i = 0; i < N; i++) {
			if (btn == 0 && mx >= tx[i] && mx < tx[i] + tw[i] && my >= ty[i] && my < ty[i] + th[i]) {
				Ui.click();
				selectTab(TABS[i]);
				return true;
			}
		}
		if (my >= py + t.hdrH && (my < py + t.hdrH + t.tabBar || mx < px + t.sbW)) return true; // the tab column and strip swallow other clicks
		if (isSettingsTab(tab) && search.mouseClicked(mx, my, btn, dbl)) return true;
		content.mouseClicked(mx, my, btn, dbl);
		return true;
	}

	@Override public boolean mouseReleased(MouseButtonEvent ev) {
		return safe(() -> release(ev));
	}

	private boolean release(MouseButtonEvent ev) {
		double mx = ev.x() / s, my = ev.y() / s;
		int btn = ev.button();
		if (modal != null) {
			modal.mouseReleased(mx, my, btn);
			return true;
		}
		closeBtn.mouseReleased(mx, my, btn);
		content.mouseReleased(mx, my, btn);
		search.mouseReleased(mx, my, btn);
		return true;
	}

	@Override public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
		return safe(() -> drag(ev));
	}

	private boolean drag(MouseButtonEvent ev) {
		double mx = ev.x() / s, my = ev.y() / s;
		if (modal != null) return modal.mouseDragged(mx, my, ev.button());
		if (focused == search && search.mouseDragged(mx, my, ev.button())) return true;
		return content.mouseDragged(mx, my, ev.button());
	}

	@Override public boolean mouseScrolled(double rawMx, double rawMy, double sx, double sy) {
		return safe(() -> scroll(rawMx, rawMy, sy));
	}

	private boolean scroll(double rawMx, double rawMy, double sy) {
		double mx = rawMx / s, my = rawMy / s;
		if (modal != null) return modal.mouseScrolled(mx, my, sy);
		if (mx < px + Ui.theme().sbW) return true;
		return content.mouseScrolled(mx, my, sy);
	}

	@Override public boolean keyPressed(KeyEvent e) {
		return safe(() -> key(e));
	}

	private boolean key(KeyEvent e) {
		if (modal != null) {
			modal.keyPressed(e);
			return true;
		}
		if (focused != null) {
			if (focused.keyPressed(e)) return true;
			if (e.isEscape()) focus(null);
			return true;
		}
		if (e.isEscape() || HumanisedBaritone.OPEN_KEY.matches(e)) {
			onClose();
			return true;
		}
		if (isSettingsTab(tab) && e.hasControlDown() && e.key() == 70) { // ctrl+F
			search.focusNow = true;
			return true;
		}
		return super.keyPressed(e);
	}

	@Override public boolean charTyped(CharacterEvent e) {
		return safe(() -> typed(e));
	}

	private boolean typed(CharacterEvent e) {
		if (modal != null) return modal.charTyped(e.codepoint());
		if (focused != null) return focused.charTyped(e.codepoint());
		return false;
	}

	@Override public void removed() {
		Settings.save();
		super.removed();
	}

	// ------------------------------------------------------------ error safety

	/** A bug in the panel must never take the game down with it: the panel closes itself instead. */
	private boolean broken;

	private boolean safe(java.util.function.BooleanSupplier r) {
		if (broken) return true;
		try {
			return r.getAsBoolean();
		} catch (RuntimeException e) {
			crashed(e);
			return true;
		}
	}

	private void crashed(RuntimeException e) {
		if (broken) return;
		broken = true;
		Log.warn("The panel hit an internal error and closed: " + e);
		e.printStackTrace();
	}

	@Override public void tick() {
		super.tick();
		if (broken) onClose();
	}

	@Override public boolean shouldCloseOnEsc() {
		return false;
	}
}
