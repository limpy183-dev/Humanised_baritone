package com.humanisedbaritone.gui;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.build.Ghosts;
import com.humanisedbaritone.build.Placement;
import com.humanisedbaritone.gui.Ui.Anim;
import com.humanisedbaritone.settings.Profiles;
import com.humanisedbaritone.settings.Setting;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.Tasks;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Content of the Dashboard and Profiles tabs. */
final class Pages {
	private Pages() {
	}

	private static void card(W.Frame f, W r, String title) {
		Skin.card(f.g, r.x, r.y, r.w, r.h, title);
	}

	/** Height of the humanising presets card; a little taller where the style's font is. */
	static int presetH() {
		return Ui.theme().style == Settings.UiStyle.MINECRAFT ? 58 : 64;
	}

	private static W.Btn btn(String label, W.Style st, String tip, Runnable r) {
		W.Btn b = new W.Btn(label, st, r);
		b.tip = tip;
		return b;
	}

	// ================================================================ dashboard

	static void dashboard(W.ScrollArea a, W.Host host) {
		if (Placement.current != null) a.add(new SchematicCard(), 112);
		a.add(new StatusCard(), 74);
		a.add(new QuickCard(), 104);
		a.add(new TravelCard(host), 50);
		a.add(new TaskCard(host), 97);
		a.add(new AreaCard(), 52);
		a.add(new ConsoleCard(a), 134);
	}

	private static final class StatusCard extends W {
		private final Btn stop = btn("Stop", Style.DANGER, "Cancel the current task and release all controls.", () -> Commands.run("stop"));
		private final Btn pause = btn("Pause", Style.NORMAL, "Pause or resume the current task.", () -> Commands.run(Bot.paused() ? "resume" : "pause"));
		private final Anim prog = new Anim(0);

		@Override public void render(Frame f) {
			card(f, this, null);
			stop.at(x + w - 56, y + 7, 46, 15);
			pause.at(x + w - 110, y + 7, 50, 15);
			pause.label = Bot.paused() ? "Resume" : "Pause";
			stop.enabled = Bot.enabled && Bot.busy();
			pause.enabled = stop.enabled;
			stop.render(f);
			pause.render(f);
			Ui.text(f.g, Ui.fit(Bot.headline(), w - 130), x + 10, y + 10, Ui.TEXT);
			Ui.text(f.g, Ui.fit(Bot.detail(), w - 24), x + 10, y + 24, Ui.DIM);
			float p = Bot.progress();
			Skin.progress(f.g, x + 10, y + 38, w - 20, 5, p >= 0 ? prog.to(p, f.dt, 10) : -1, Bot.busy());
			LocalPlayer pl = Minecraft.getInstance().player;
			String stats = pl == null ? "Not in a world" : String.format("HP %.0f/%.0f   Food %d   XYZ %.1f %.1f %.1f   Facing %.0f/%.0f", pl.getHealth(), pl.getMaxHealth(),
				pl.getFoodData().getFoodLevel(), pl.getX(), pl.getY(), pl.getZ(), pl.getYRot(), pl.getXRot());
			Ui.text(f.g, Ui.fit(stats, w - 20), x + 10, y + 49, Ui.MUTED);
			Ui.text(f.g, Ui.fit(Bot.extraInfo(), w - 20), x + 10, y + 61, Ui.alpha(Ui.DIM, 0.8f));
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			return stop.mouseClicked(mx, my, b, d) || pause.mouseClicked(mx, my, b, d);
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = stop.mouseReleased(mx, my, b);
			return pause.mouseReleased(mx, my, b) || r;
		}
	}

	private static final class QuickCard extends W {
		private final List<Setting.Bool> list = List.of(Settings.SPRINT, Settings.PARKOUR, Settings.BREAK, Settings.PLACE, Settings.COMBAT_ON, Settings.EAT, Settings.PASSIVE, Settings.LOOK_SMOOTH, Settings.ELYTRA_HELPER);
		private final List<Toggle> toggles = new ArrayList<>();

		QuickCard() {
			for (Setting.Bool b : list) {
				Toggle t = Toggle.of(b);
				t.compact = true;
				toggles.add(t);
			}
		}

		@Override public void render(Frame f) {
			card(f, this, "Quick settings");
			int colW = (w - 30) / 2;
			for (int i = 0; i < list.size(); i++) {
				int cx = x + 10 + (i % 2) * (colW + 10), cy = y + 22 + (i / 2) * 16;
				Toggle t = toggles.get(i);
				t.at(cx, cy, colW, 14);
				Ui.text(f.g, Ui.fit(list.get(i).name, colW - 36), cx, cy + 3, Ui.DIM);
				t.render(f);
				if (t.over(f.mx, f.my)) f.host.tip(list.get(i).desc);
			}
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			for (Toggle t : toggles) if (t.mouseClicked(mx, my, b, d)) return true;
			return false;
		}
	}

	private static final class TravelCard extends W {
		private final Host host;
		private final TextBox bx = new TextBox("X", 8, s -> Settings.setTravel(0, s)), by = new TextBox("Y", 8, s -> Settings.setTravel(1, s)),
			bz = new TextBox("Z", 8, s -> Settings.setTravel(2, s));
		private final Btn go, here, wp;

		TravelCard(Host host) {
			this.host = host;
			go = btn("Go", Style.PRIMARY, "Path to these coordinates. Leave Y empty to travel to X/Z only.", this::go);
			here = btn("Here", Style.NORMAL, "Fill in your current position.", this::fillHere);
			wp = btn("Waypoints", Style.NORMAL, "Travel to a saved waypoint.", () -> {
				List<Picker.Entry> es = new ArrayList<>();
				Settings.WAYPOINTS.forEach((k, v) -> es.add(new Picker.Entry(k, k + "  (" + v[0] + ", " + v[1] + ", " + v[2] + ")", net.minecraft.world.item.ItemStack.EMPTY)));
				if (es.isEmpty()) Log.say("No waypoints saved yet - use the Profiles tab or 'wp save <name>'.");
				else Picker.single(host, "Go to waypoint", es, k -> Commands.run("wp goto " + k));
			});
			bx.setText(Settings.TRAVEL[0]);
			by.setText(Settings.TRAVEL[1]);
			bz.setText(Settings.TRAVEL[2]);
			for (TextBox t : List.of(bx, by, bz)) t.onEnter = s -> go();
		}

		private void fillHere() {
			LocalPlayer p = Minecraft.getInstance().player;
			if (p == null) return;
			set(bx, 0, p.getBlockX());
			set(by, 1, p.getBlockY());
			set(bz, 2, p.getBlockZ());
		}

		private static void set(TextBox t, int i, int v) {
			t.setText(Integer.toString(v));
			Settings.setTravel(i, t.text());
		}

		private void go() {
			String x = bx.text().trim(), y = by.text().trim(), z = bz.text().trim();
			if (x.isEmpty() || z.isEmpty()) {
				Log.say("Enter at least X and Z.");
				return;
			}
			Commands.run("goto " + x + (y.isEmpty() ? "" : " " + y) + " " + z);
		}

		@Override public void render(Frame f) {
			card(f, this, "Travel");
			int ty = y + 22, fw = 44;
			bx.at(x + 10, ty, fw, 16);
			by.at(x + 10 + fw + 4, ty, fw, 16);
			bz.at(x + 10 + (fw + 4) * 2, ty, fw, 16);
			int bxr = x + 10 + (fw + 4) * 3 + 2;
			go.at(bxr, ty, 34, 16);
			here.at(bxr + 38, ty, 40, 16);
			wp.at(bxr + 82, ty, Math.max(60, x + w - 10 - (bxr + 82)), 16);
			for (W wdg : List.of(bx, by, bz, go, here, wp)) wdg.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			for (W wdg : List.of(bx, by, bz, go, here, wp)) if (wdg.mouseClicked(mx, my, b, d)) return true;
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = false;
			for (W wdg : List.of(bx, by, bz, go, here, wp)) r |= wdg.mouseReleased(mx, my, b);
			return r;
		}

		@Override public boolean mouseDragged(double mx, double my, int b) {
			return bx.mouseDragged(mx, my, b) || by.mouseDragged(mx, my, b) || bz.mouseDragged(mx, my, b);
		}

		@Override public boolean keyPressed(KeyEvent e) {
			return bx.keyPressed(e) || by.keyPressed(e) || bz.keyPressed(e);
		}

		@Override public boolean charTyped(int cp) {
			return bx.charTyped(cp) || by.charTyped(cp) || bz.charTyped(cp);
		}
	}

	private static final class TaskCard extends W {
		private final List<Btn> buttons = new ArrayList<>();

		TaskCard(Host host) {
			buttons.add(btn("Mine...", Style.NORMAL, "Pick blocks to find and mine.", () -> Picker.multi(host, "Mine which blocks?", Setting.Registry.BLOCK,
				new LinkedHashSet<>(), sel -> {
					if (sel.isEmpty()) return;
					StringBuilder sb = new StringBuilder("mine");
					for (String s : sel) sb.append(' ').append(s.replace("minecraft:", ""));
					Commands.run(sb.toString());
				}, "Start mining")));
			buttons.add(btn("Farm", Style.NORMAL, "Harvest and replant crops around you.", () -> Commands.run("farm")));
			buttons.add(btn("Follow...", Style.NORMAL, "Pick a player to follow.", () -> {
				List<Picker.Entry> es = new ArrayList<>();
				var mc = Minecraft.getInstance();
				if (mc.level != null) {
					for (AbstractClientPlayer p : mc.level.players()) {
						if (p == mc.player) continue;
						es.add(new Picker.Entry(p.getName().getString(), p.getName().getString(), net.minecraft.world.item.ItemStack.EMPTY));
					}
				}
				if (es.isEmpty()) Log.say("No other players around to follow.");
				else Picker.single(host, "Follow which player?", es, name -> Commands.run("follow " + name));
			}));
			buttons.add(btn("Explore", Style.NORMAL, "Head for chunks you have not seen yet.", () -> Commands.run("explore")));
			buttons.add(btn("Wander", Style.NORMAL, "Roam around on its own, within the wander radius of here. With an elytra worn it bounces along open flat ground (timed jumps, no fireworks).", () -> Commands.run("wander")));
			buttons.add(btn("Hunt", Style.NORMAL, "Find and kill hostile mobs nearby.", () -> Commands.run("hunt")));
			buttons.add(btn("Collect", Style.NORMAL, "Pick up dropped items around you.", () -> Commands.run("collect")));
			buttons.add(btn("Build...", Style.NORMAL, "Load a schematic from the schematics folder and see it in the world first. You confirm where it goes before anything is built.", () -> {
				List<Picker.Entry> es = new ArrayList<>();
				for (String s : com.humanisedbaritone.build.Schematics.list()) es.add(new Picker.Entry(s, s, net.minecraft.world.item.ItemStack.EMPTY));
				if (es.isEmpty()) Log.say("No schematics found. Put .schem / .nbt / .litematic files into " + com.humanisedbaritone.build.Schematics.dir());
				else Picker.single(host, "Which schematic?", es, name -> Commands.run("build " + name));
			}));
			buttons.add(btn("Guard", Style.NORMAL, "Stand still and defend yourself and the area.", () -> Commands.run("guard")));
			buttons.add(btn("Sleep", Style.NORMAL, "Find a bed and sleep through the night.", () -> Commands.run("sleep")));
			buttons.add(btn("Store", Style.NORMAL, "Put non-essential items into the nearest chest.", () -> Commands.run("store")));
		}

		@Override public void render(Frame f) {
			card(f, this, "Tasks");
			int cols = 5, gap = 5, bw = (w - 20 - gap * (cols - 1)) / cols;
			for (int i = 0; i < buttons.size(); i++) {
				buttons.get(i).at(x + 10 + (i % cols) * (bw + gap), y + 22 + (i / cols) * 21, bw, 17);
				buttons.get(i).enabled = Bot.enabled || buttons.get(i).label.equals("Build...");
				buttons.get(i).render(f);
			}
			if (!Bot.enabled) Ui.text(f.g, "Enable the bot (top right) to start tasks.", x + 10, y + 85, Ui.WARN);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			for (Btn btn : buttons) if (btn.mouseClicked(mx, my, b, d)) return true;
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = false;
			for (Btn btn : buttons) r |= btn.mouseReleased(mx, my, b);
			return r;
		}
	}

	// ============================================================ schematic

	/** The loaded schematic: where it stands, how it is turned, what is left to do, and the button that starts the build once the spot is right. */
	private static final class SchematicCard extends W {
		private final TextBox bx = new TextBox("X", 9, null), by = new TextBox("Y", 9, null), bz = new TextBox("Z", 9, null);
		private final Btn here, rotate, confirm, cancel, stepBtn;
		private final Btn[] move = new Btn[6];
		private final List<W> all = new ArrayList<>();
		private int step = 1;
		private BlockPos shown;

		SchematicCard() {
			here = btn("Here", Style.NORMAL, "Move the schematic's corner to where you stand.", () -> Commands.run("build here"));
			rotate = btn("Rotate", Style.NORMAL, "Turn the schematic a quarter turn clockwise.", () -> Commands.run("build rotate"));
			confirm = btn("Confirm and build", Style.PRIMARY, "Happy with the spot? Start building it exactly where the preview is.", Commands::confirmBuild);
			cancel = btn("Cancel", Style.DANGER, "Drop the schematic. Nothing is built.", () -> Commands.run("build cancel"));
			stepBtn = btn("Step 1", Style.GHOST, "How far each arrow moves the schematic.", () -> step = step == 1 ? 5 : step == 5 ? 16 : 1);
			String[] names = {"Left", "Right", "Back", "Forward", "Down", "Up"};
			for (int i = 0; i < 6; i++) {
				int k = i;
				move[i] = btn(names[i], Style.NORMAL, "Move the schematic " + names[i].toLowerCase(java.util.Locale.ROOT) + " (the way you are facing) by the step.", () -> nudge(k));
			}
			all.addAll(List.of(bx, by, bz, here, rotate, confirm, cancel, stepBtn));
			all.addAll(List.of(move));
			for (TextBox t : List.of(bx, by, bz)) t.onEnter = s -> apply();
		}

		private void apply() {
			Placement p = Placement.current;
			if (p == null) return;
			try {
				p.moveTo(new BlockPos(Integer.parseInt(bx.text().trim()), Integer.parseInt(by.text().trim()), Integer.parseInt(bz.text().trim())));
			} catch (NumberFormatException e) {
				Log.say("Enter whole numbers for X, Y and Z.");
			}
		}

		/** Left, right, back, forward, down, up: as the player faces. */
		private void nudge(int k) {
			Placement p = Placement.current;
			LocalPlayer pl = Minecraft.getInstance().player;
			if (p == null || pl == null) return;
			net.minecraft.core.Direction d = pl.getDirection();
			int fx = d.getStepX(), fz = d.getStepZ(), rx = -fz, rz = fx;
			int[][] v = {{-rx, 0, -rz}, {rx, 0, rz}, {-fx, 0, -fz}, {fx, 0, fz}, {0, -1, 0}, {0, 1, 0}};
			p.nudge(v[k][0] * step, v[k][1] * step, v[k][2] * step);
		}

		@Override public void render(Frame f) {
			Placement p = Placement.current;
			if (p == null) return;
			boolean building = p.confirmed && Bot.busy() && Tasks.current() != null && "BUILD".equals(Tasks.current().tag());
			card(f, this, "Schematic");
			Ui.text(f.g, Ui.fit(p.info(), w - 100), x + 66, y + 4, Ui.accent());
			int[] n = p.count;
			String status = !p.confirmed ? "Look at the preview in the world. Move it if you like, then confirm." : building ? Bot.detail() : p.done() ? "Done: everything matches." : "Stopped: " + p.open() + " block(s) still to do.";
			Ui.text(f.g, Ui.fit(status, w - 20), x + 10, y + 20, p.confirmed && !building && p.done() ? Ui.GOOD : Ui.DIM);
			int cx = x + 10, cy = y + 32;
			String[] label = {"Missing ", "Wrong ", "Orientation ", "Extra "};
			int[] val = {n[Placement.MISSING], n[Placement.WRONG], n[Placement.STATE], n[Placement.EXTRA]};
			int[] col = {Ghosts.CYAN, Ghosts.RED, Ghosts.ORANGE, Ghosts.PURPLE};
			for (int i = 0; i < 4; i++) {
				String t = label[i] + val[i];
				Ui.rect(f.g, cx, cy + 1, 5, 6, col[i]);
				Ui.text(f.g, t, cx + 8, cy, val[i] > 0 ? Ui.TEXT : Ui.MUTED);
				cx += 8 + Ui.width(t) + 12;
			}
			if (shown == null || !shown.equals(p.origin)) {
				shown = p.origin;
				if (!f.host.isFocused(bx)) bx.setText(Integer.toString(p.origin.getX()));
				if (!f.host.isFocused(by)) by.setText(Integer.toString(p.origin.getY()));
				if (!f.host.isFocused(bz)) bz.setText(Integer.toString(p.origin.getZ()));
			}
			boolean free = !building;
			int ry = y + 46, gap = 4, fw = Math.max(30, (w - 20 - 3 * 11 - 3 * gap - 44 - gap) / 3);
			int at = x + 10;
			for (TextBox t : List.of(bx, by, bz)) {
				Ui.text(f.g, t == bx ? "X" : t == by ? "Y" : "Z", at, ry + 5, Ui.MUTED);
				t.at(at + 8, ry, fw, 16);
				t.enabled = free;
				t.render(f);
				at += 11 + fw + gap;
			}
			here.at(at, ry, x + w - 10 - at, 16);
			int my = y + 66, bw = (w - 20 - gap * 5) / 6;
			for (int i = 0; i < 6; i++) move[i].at(x + 10 + i * (bw + gap), my, bw, 16);
			int by2 = y + 88;
			rotate.at(x + 10, by2, 52, 17);
			stepBtn.label = "Step " + step;
			stepBtn.at(x + 66, by2, 50, 17);
			confirm.label = p.confirmed ? "Continue building" : "Confirm and build";
			int cw2 = Math.max(96, Ui.width(confirm.label) + 24);
			confirm.at(x + w - 10 - cw2, by2, cw2, 17);
			cancel.at(x + w - 10 - cw2 - gap - 50, by2, 50, 17);
			for (Btn m : move) m.enabled = free;
			here.enabled = rotate.enabled = stepBtn.enabled = free;
			confirm.enabled = free && !p.done();
			for (W wdg : all) if (!(wdg instanceof TextBox)) wdg.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			for (W wdg : all) if (wdg.mouseClicked(mx, my, b, d)) return true;
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = false;
			for (W wdg : all) r |= wdg.mouseReleased(mx, my, b);
			return r;
		}

		@Override public boolean mouseDragged(double mx, double my, int b) {
			return bx.mouseDragged(mx, my, b) || by.mouseDragged(mx, my, b) || bz.mouseDragged(mx, my, b);
		}

		@Override public boolean keyPressed(KeyEvent e) {
			return bx.keyPressed(e) || by.keyPressed(e) || bz.keyPressed(e);
		}

		@Override public boolean charTyped(int cp) {
			return bx.charTyped(cp) || by.charTyped(cp) || bz.charTyped(cp);
		}
	}

	private static final class AreaCard extends W {
		private final List<Btn> buttons = new ArrayList<>();

		AreaCard() {
			buttons.add(btn("Pos 1 here", Style.NORMAL, "Set the first corner of the selection to your position.", () -> Commands.run("sel pos1")));
			buttons.add(btn("Pos 2 here", Style.NORMAL, "Set the second corner of the selection to your position.", () -> Commands.run("sel pos2")));
			buttons.add(btn("Clear area", Style.NORMAL, "Mine every block inside the selection.", () -> Commands.run("sel clear")));
			buttons.add(btn("Forget", Style.GHOST, "Remove the selection.", () -> Commands.run("sel none")));
		}

		@Override public void render(Frame f) {
			card(f, this, "Selection  -  " + Bot.selectionInfo());
			int gap = 5, bw = (w - 20 - gap * 3) / 4;
			for (int i = 0; i < buttons.size(); i++) {
				buttons.get(i).at(x + 10 + i * (bw + gap), y + 26, bw, 17);
				buttons.get(i).enabled = i < 2 || Bot.enabled;
				buttons.get(i).render(f);
			}
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			for (Btn btn : buttons) if (btn.mouseClicked(mx, my, b, d)) return true;
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = false;
			for (Btn btn : buttons) r |= btn.mouseReleased(mx, my, b);
			return r;
		}
	}

	private static final class ConsoleCard extends W {
		private final TextBox input = new TextBox("Type a command, e.g. goto 100 64 -20  (help lists all)", 200, null);
		private final Btn clear;
		private final List<String> history = new ArrayList<>();
		private int histIdx = -1, seenRev = -1, scrollLines;
		private static final List<String> HISTORY = new ArrayList<>();

		ConsoleCard(ScrollArea owner) {
			history.addAll(HISTORY);
			clear = btn("Clear", Style.GHOST, "Clear the console.", () -> {
				Log.clear();
				seenRev = -1;
			});
			input.onNav = dir -> {
				if (history.isEmpty()) return false;
				if (dir < 0) histIdx = histIdx < 0 ? history.size() - 1 : Math.max(0, histIdx - 1);
				else if (histIdx >= 0) histIdx++;
				else return false;
				boolean past = histIdx >= history.size();
				input.setText(past ? "" : history.get(histIdx));
				if (past) histIdx = -1;
				return true;
			};
			input.onEnter = s -> {
				String t = s.trim();
				if (t.isEmpty()) return;
				HISTORY.add(t);
				history.add(t);
				histIdx = -1;
				input.setText("");
				Log.say("> " + t);
				Commands.run(t);
				owner.scrollToBottom();
			};
		}

		@Override public void render(Frame f) {
			card(f, this, "Console");
			clear.at(x + w - 50, y + 4, 40, 13);
			clear.render(f);
			int ly = y + 21, lines = (h - 21 - 24) / 10;
			List<String> all = Log.lines();
			int start = Math.max(0, all.size() - lines - scrollLines);
			int end = Math.min(all.size(), start + lines);
			f.g.enableScissor(x + 6, ly, x + w - 6, ly + lines * 10);
			for (int i = start; i < end; i++) {
				String s = all.get(i);
				int c = s.startsWith("! ") ? Ui.WARN : s.startsWith("> ") ? Ui.accent() : s.startsWith("[debug]") ? Ui.MUTED : Ui.DIM;
				Ui.text(f.g, Ui.fit(s, w - 20), x + 10, ly + (i - start) * 10, c);
			}
			f.g.disableScissor();
			if (all.isEmpty()) Ui.text(f.g, "Nothing yet. Commands and bot messages show up here.", x + 10, ly, Ui.MUTED);
			input.at(x + 8, y + h - 21, w - 16, 16);
			input.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			return clear.mouseClicked(mx, my, b, d) || input.mouseClicked(mx, my, b, d);
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = clear.mouseReleased(mx, my, b);
			return input.mouseReleased(mx, my, b) || r;
		}

		@Override public boolean mouseDragged(double mx, double my, int b) {
			return input.mouseDragged(mx, my, b);
		}

		@Override public boolean mouseScrolled(double mx, double my, double dy) {
			int n = Log.lines().size();
			scrollLines = Math.max(0, Math.min(Math.max(0, n - 4), scrollLines + (int) Math.signum(dy) * 2));
			return true;
		}

		@Override public boolean charTyped(int cp) {
			return input.charTyped(cp);
		}
	}

	// ================================================================== profiles

	static void profiles(W.ScrollArea a, W.Host host, Runnable refresh) {
		a.add(new SettingRow.Header("Humanising presets"), 15);
		a.add(new PresetRow(), presetH());
		a.add(new SettingRow.Header("Saved profiles"), 15);
		a.add(new ProfileSaveRow(refresh), 22);
		for (String name : Profiles.saved()) a.add(new ProfileRow(name, () -> {
			if (Profiles.load(name)) Log.say("Loaded profile '" + name + "'.");
			refresh.run();
		}, () -> {
			Profiles.delete(name);
			refresh.run();
		}), 20);
		a.add(new SettingRow.Header("Waypoints"), 15);
		a.add(new WaypointSaveRow(refresh), 22);
		for (var en : new ArrayList<>(Settings.WAYPOINTS.entrySet())) {
			String name = en.getKey();
			int[] p = en.getValue();
			a.add(new ListRow(name + "   " + p[0] + ", " + p[1] + ", " + p[2], "Go", () -> Commands.run("wp goto " + name), () -> {
				Settings.WAYPOINTS.remove(name);
				Settings.markDirty();
				refresh.run();
			}), 20);
		}
		a.add(new SettingRow.Header("Maintenance"), 15);
		a.add(new ResetRow(refresh), 24);
	}

	static final class PresetRow extends W {
		private final List<Btn> btns = new ArrayList<>();
		private String shown = "";

		PresetRow() {
			for (String p : Profiles.presets()) {
				Btn b = btn(p, Style.NORMAL, Profiles.PRESET_INFO.get(p), () -> {
					Profiles.applyPreset(p);
					shown = p;
					Log.say("Applied humanising preset: " + p);
				});
				btns.add(b);
			}
		}

		@Override public void render(Frame f) {
			card(f, this, null);
			int gap = 5, bw = (w - 20 - gap * (btns.size() - 1)) / btns.size();
			for (int i = 0; i < btns.size(); i++) {
				Btn b = btns.get(i);
				b.at(x + 10 + i * (bw + gap), y + 9, bw, 18);
				b.active = () -> b.label.equals(shown);
				b.render(f);
			}
			String info = shown.isEmpty() ? "Presets only change the Humanise tab. Hover a button for details." : Profiles.PRESET_INFO.get(shown);
			Ui.text(f.g, Ui.fit(info, w - 20), x + 10, y + 35, Ui.DIM);
			Ui.text(f.g, "Tweak anything afterwards in the Humanise tab.", x + 10, y + 46, Ui.MUTED);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			for (Btn bt : btns) if (bt.mouseClicked(mx, my, b, d)) return true;
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = false;
			for (Btn bt : btns) r |= bt.mouseReleased(mx, my, b);
			return r;
		}
	}

	private static final class ProfileSaveRow extends W {
		private final TextBox name = new TextBox("Profile name", 24, null);
		private final Btn save;

		ProfileSaveRow(Runnable refresh) {
			save = btn("New profile", Style.PRIMARY, "Create a profile from the current settings and switch to it. Settings changes are saved to the selected profile automatically.", () -> {
				if (Profiles.save(name.text())) {
					Log.say("Created profile '" + name.text().trim() + "'.");
					refresh.run();
				} else Log.warn("Enter a valid profile name first.");
			});
			name.onEnter = s -> save.action.run();
		}

		@Override public void render(Frame f) {
			name.at(x, y + 2, w - 92, 17);
			save.at(x + w - 86, y + 2, 86, 17);
			name.render(f);
			save.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			return name.mouseClicked(mx, my, b, d) || save.mouseClicked(mx, my, b, d);
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = name.mouseReleased(mx, my, b);
			return save.mouseReleased(mx, my, b) || r;
		}

		@Override public boolean mouseDragged(double mx, double my, int b) {
			return name.mouseDragged(mx, my, b);
		}

		@Override public boolean keyPressed(KeyEvent e) {
			return name.keyPressed(e);
		}

		@Override public boolean charTyped(int cp) {
			return name.charTyped(cp);
		}
	}

	private static final class WaypointSaveRow extends W {
		private final TextBox name = new TextBox("Waypoint name", 24, null);
		private final Btn save;

		WaypointSaveRow(Runnable refresh) {
			save = btn("Save here", Style.PRIMARY, "Remember your current position under this name.", () -> {
				if (!name.text().isBlank()) {
					Commands.run("wp save " + name.text().trim().replace(' ', '_'));
					refresh.run();
				}
			});
			name.onEnter = s -> save.action.run();
		}

		@Override public void render(Frame f) {
			name.at(x, y + 2, w - 92, 17);
			save.at(x + w - 86, y + 2, 86, 17);
			name.render(f);
			save.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			return name.mouseClicked(mx, my, b, d) || save.mouseClicked(mx, my, b, d);
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = name.mouseReleased(mx, my, b);
			return save.mouseReleased(mx, my, b) || r;
		}

		@Override public boolean mouseDragged(double mx, double my, int b) {
			return name.mouseDragged(mx, my, b);
		}

		@Override public boolean keyPressed(KeyEvent e) {
			return name.keyPressed(e);
		}

		@Override public boolean charTyped(int cp) {
			return name.charTyped(cp);
		}
	}

	private static final class ListRow extends W {
		private final String label;
		private final Btn main, del;

		ListRow(String label, String action, Runnable run, Runnable delete) {
			this.label = label;
			main = btn(action, Style.NORMAL, null, run);
			del = btn("x", Style.DANGER, "Delete", delete);
		}

		@Override public void render(Frame f) {
			Ui.rrect(f.g, x, y, w, h, Ui.theme().ctlR + 1, Ui.alpha(0xFFFFFFFF, 0.03f));
			Ui.text(f.g, Ui.fit(label, w - 100), x + 8, y + 6, Ui.TEXT);
			main.at(x + w - 70, y + 2, 44, h - 4);
			del.at(x + w - 22, y + 2, 18, h - 4);
			main.render(f);
			del.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			return main.mouseClicked(mx, my, b, d) || del.mouseClicked(mx, my, b, d);
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = main.mouseReleased(mx, my, b);
			return del.mouseReleased(mx, my, b) || r;
		}
	}

	/** Saved profile: click anywhere on the row to switch to it; the selected one is highlighted. */
	private static final class ProfileRow extends W {
		private final String name;
		private final Runnable select;
		private final Btn del;
		private final Anim hov = new Anim(0);
		private boolean down;

		ProfileRow(String name, Runnable select, Runnable delete) {
			this.name = name;
			this.select = select;
			del = btn("x", Style.DANGER, "Delete", delete);
		}

		@Override public void render(Frame f) {
			boolean cur = name.equals(Settings.profile);
			boolean hot = over(f.mx, f.my) && !del.over(f.mx, f.my);
			float hv = hov.to(hot ? 1 : 0, f.dt, 18);
			int acc = Ui.accent();
			Ui.rrect(f.g, x, y, w, h, Ui.theme().ctlR + 1, cur ? Ui.alpha(acc, 0.14f + 0.06f * hv) : Ui.alpha(0xFFFFFFFF, 0.03f + 0.05f * hv));
			if (cur) Ui.rring(f.g, x, y, w, h, Ui.theme().ctlR + 1, Ui.alpha(acc, 0.75f));
			Ui.text(f.g, Ui.fit(name, w - 100), x + 8, y + 6, cur ? acc : Ui.TEXT);
			if (cur) Ui.textR(f.g, "In use", x + w - 28, y + 6, Ui.alpha(acc, 0.8f));
			del.at(x + w - 22, y + 2, 18, h - 4);
			del.render(f);
			if (hot) f.host.tip(cur ? "Selected profile: settings changes are saved to it automatically." : "Click to load this profile.");
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			if (del.mouseClicked(mx, my, b, d)) return true;
			if (b == 0 && over(mx, my)) down = true;
			return down;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = del.mouseReleased(mx, my, b);
			boolean fire = down && b == 0 && over(mx, my) && !del.over(mx, my);
			boolean had = down;
			down = false;
			if (fire) {
				Ui.click();
				select.run();
			}
			return r || had;
		}
	}

	private static final class ResetRow extends W {
		private final Btn reset;
		private long armedAt;

		ResetRow(Runnable refresh) {
			reset = btn("Reset all settings", Style.DANGER, "Restore every setting to its default value.", () -> {
				if (System.currentTimeMillis() - armedAt < 3000) {
					Settings.resetAll();
					armedAt = 0;
					Log.say("All settings were reset to their defaults.");
					refresh.run();
				} else armedAt = System.currentTimeMillis();
			});
		}

		@Override public void render(Frame f) {
			boolean armed = System.currentTimeMillis() - armedAt < 3000;
			reset.label = armed ? "Click again to confirm" : "Reset all settings";
			reset.at(x, y + 2, 150, 18);
			reset.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			return reset.mouseClicked(mx, my, b, d);
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			return reset.mouseReleased(mx, my, b);
		}
	}
}
