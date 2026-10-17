package com.humanisedbaritone.gui;

import com.humanisedbaritone.Log;
import com.humanisedbaritone.gui.Ui.Anim;
import com.humanisedbaritone.scan.Find;
import com.humanisedbaritone.scan.Find.Kind;
import com.humanisedbaritone.scan.Finds;
import com.humanisedbaritone.scan.Radar;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/** The Logging tab: what the scanner found in the world (and dimension) you are viewing, as a filterable list. The Map page shows the same finds. */
final class LogPage {
	/** Kinds shown (a bit per {@link Kind}); the list and the map share it. */
	static int mask = (1 << Kind.values().length) - 1;
	/** Id of the selected find in the viewed store, or -1. */
	static int selected = -1;
	/** Set by a row's Map button: the map centres on it when it opens. */
	static Find focus;

	enum Sort { NEWEST, NEAREST, KIND }

	static Sort sort = Sort.NEWEST;

	private LogPage() {
	}

	static boolean shows(Find f) {
		return (mask & f.kind.bit()) != 0;
	}

	/** The summary card and the list; the scanner's settings follow below them. */
	static void build(W.ScrollArea a, int availH) {
		a.add(new Head(), 72);
		a.add(new Listing(), Math.max(110, availH - 72 - a.gap));
	}

	private static final Map<Kind, ItemStack> ICONS = new EnumMap<>(Kind.class);

	static ItemStack icon(Kind k) {
		return ICONS.computeIfAbsent(k, kk -> new ItemStack(kk.icon));
	}

	// ==================================================================== view

	/** Which world and dimension the pages show: buttons that cycle through the stored ones, and a way back to the live one. */
	static final class ViewBar extends W {
		private final Btn world, dim, live;

		ViewBar() {
			world = new Btn("", Style.NORMAL, () -> world(1));
			dim = new Btn("", Style.NORMAL, () -> dim(1));
			live = new Btn("Live", Style.GHOST, () -> {
				Finds.show(Finds.live);
				selected = -1;
			});
			world.tip = "The world whose finds and map are shown. Click for the next stored world, right-click for the previous one.";
			dim.tip = "The dimension shown. Click for the next one, right-click for the previous one.";
			live.tip = "Go back to the world and dimension you are in.";
		}

		private static void world(int dir) {
			List<String> ws = Finds.worlds();
			if (Finds.view == null || ws.isEmpty()) return;
			int i = Math.max(0, ws.indexOf(Finds.view.world));
			String next = ws.get(Math.floorMod(i + dir, ws.size()));
			if (next.equals(Finds.view.world)) return;
			List<String> dims = Finds.dims(next);
			if (dims.isEmpty()) return;
			String keep = dims.get(0);
			for (String d : dims) if (d.equals(Finds.view.dim)) keep = d;
			Finds.show(Finds.open(next, Finds.nameOf(next), keep));
			selected = -1;
		}

		private static void dim(int dir) {
			if (Finds.view == null) return;
			List<String> ds = Finds.dims(Finds.view.world);
			if (ds.size() < 2) return;
			int i = Math.max(0, ds.indexOf(Finds.view.dim));
			Finds.show(Finds.open(Finds.view.world, Finds.view.worldName, ds.get(Math.floorMod(i + dir, ds.size()))));
			selected = -1;
		}

		@Override public void render(Frame f) {
			Finds.Store v = Finds.view;
			if (v == null) return;
			boolean other = v != Finds.live;
			int gap = 4, lw = other ? 34 : 0, room = w - lw - (other ? gap : 0) - gap;
			int ww = Math.max(40, room * 3 / 5), dw = Math.max(40, room - ww);
			world.label = v.worldName;
			dim.label = Finds.prettyDim(v.dim);
			world.at(x, y, ww, h);
			dim.at(x + ww + gap, y, dw, h);
			live.at(x + ww + dw + gap * 2, y, lw, h);
			world.render(f);
			dim.render(f);
			if (other) live.render(f);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			if (b == 1 && world.over(mx, my)) {
				world(-1);
				return true;
			}
			if (b == 1 && dim.over(mx, my)) {
				dim(-1);
				return true;
			}
			return world.mouseClicked(mx, my, b, d) || dim.mouseClicked(mx, my, b, d) || Finds.view != Finds.live && live.mouseClicked(mx, my, b, d);
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = world.mouseReleased(mx, my, b);
			r |= dim.mouseReleased(mx, my, b);
			return live.mouseReleased(mx, my, b) || r;
		}
	}

	// ==================================================================== head

	private static final class Head extends W {
		private final Btn scan, clear, sorter;
		private final ViewBar view = new ViewBar();
		private long armedAt;
		private final int[] chipX = new int[Kind.values().length], chipY = new int[Kind.values().length], chipW = new int[Kind.values().length];
		private final Anim[] hov = new Anim[Kind.values().length];

		Head() {
			for (int i = 0; i < hov.length; i++) hov[i] = new Anim(0);
			scan = new Btn("Scan", Style.NORMAL, Radar::scanNow);
			scan.tip = "Look at everything that is loaded right now, again. Use it after changing what the scanner looks for.";
			clear = new Btn("Clear", Style.DANGER, () -> {
				Finds.Store s = Finds.view;
				if (s == null) return;
				if (System.currentTimeMillis() - armedAt < 3000) {
					Log.say("Removed " + s.clear(mask) + " finds.");
					selected = -1;
					armedAt = 0;
				} else armedAt = System.currentTimeMillis();
			});
			clear.tip = "Remove the finds that are shown (the kinds switched on below) from this world and dimension. Click twice.";
			sorter = new Btn("", Style.GHOST, () -> sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length]);
			sorter.tip = "Order of the list: newest finds first, nearest to you first, or grouped by kind. Click to change.";
		}

		@Override public void render(Frame f) {
			Finds.Store s = Finds.view;
			Kind[] ks = Kind.values();
			int[] n = new int[ks.length];
			if (s != null) for (Find fi : s.list) n[fi.kind.ordinal()]++;
			int chipH = 13, gap = 4, left = x + 10, avail = w - 20, px = 0, py = 0, rows = 1;
			for (int i = 0; i < ks.length; i++) {
				chipW[i] = 20 + Ui.width(ks[i].label + " " + n[i]);
				if (px > 0 && px + chipW[i] > avail) {
					px = 0;
					py += chipH + gap;
					rows++;
				}
				chipX[i] = left + px;
				chipY[i] = y + 44 + py;
				px += chipW[i] + gap;
			}
			h = 44 + rows * (chipH + gap) + 3;
			Skin.card(f.g, x, y, w, h, "Logging");
			int bx = x + w - 8;
			boolean armed = System.currentTimeMillis() - armedAt < 3000;
			clear.label = armed ? "Sure?" : "Clear";
			clear.at(bx - 40, y + 4, 40, 14);
			scan.at(bx - 40 - 4 - 44, y + 4, 44, 14);
			sorter.label = sortName(sort);
			sorter.at(bx - 40 - 4 - 44 - 4 - 56, y + 4, 56, 14);
			int titleEnd = x + 84;
			if (sorter.x - 8 > titleEnd + 60) Ui.text(f.g, Ui.fit(Radar.status(), sorter.x - 8 - titleEnd), titleEnd, y + 8, Ui.MUTED); // only when there is room beside the buttons
			view.at(x + 10, y + 22, w - 20, 16);
			view.render(f);
			scan.render(f);
			clear.render(f);
			sorter.render(f);
			for (int i = 0; i < ks.length; i++) {
				Kind k = ks[i];
				boolean on = (mask & k.bit()) != 0, hot = f.mx >= chipX[i] && f.mx < chipX[i] + chipW[i] && f.my >= chipY[i] && f.my < chipY[i] + chipH;
				float hv = hov[i].to(hot ? 1 : 0, f.dt, 16);
				int c = k.color;
				Ui.rrect(f.g, chipX[i], chipY[i], chipW[i], chipH, chipH / 2, on ? Ui.alpha(c, 0.2f + 0.1f * hv) : Ui.alpha(0xFFFFFFFF, 0.04f + 0.05f * hv));
				Ui.rring(f.g, chipX[i], chipY[i], chipW[i], chipH, chipH / 2, on ? Ui.alpha(c, 0.7f) : Ui.alpha(0xFFFFFFFF, 0.1f));
				Ui.circle(f.g, chipX[i] + 8, chipY[i] + chipH / 2f, 3, on ? c : Ui.alpha(c, 0.35f));
				Ui.text(f.g, ks[i].label + " " + n[i], chipX[i] + 15, chipY[i] + 3, on ? (n[i] > 0 ? Ui.TEXT : Ui.DIM) : Ui.MUTED);
				if (hot) f.host.tip("Click to show or hide " + k.label.toLowerCase() + " finds. Right-click shows only these.");
			}
		}

		private static String sortName(Sort s) {
			return switch (s) {
				case NEWEST -> "newest";
				case NEAREST -> "nearest";
				case KIND -> "kind";
			};
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean d) {
			if (view.mouseClicked(mx, my, b, d) || scan.mouseClicked(mx, my, b, d) || clear.mouseClicked(mx, my, b, d) || sorter.mouseClicked(mx, my, b, d)) return true;
			Kind[] ks = Kind.values();
			for (int i = 0; i < ks.length; i++) {
				if (mx >= chipX[i] && mx < chipX[i] + chipW[i] && my >= chipY[i] && my < chipY[i] + 13) {
					if (b == 1) mask = ks[i].bit();
					else if (b == 0) mask ^= ks[i].bit();
					else return false;
					Ui.click();
					return true;
				}
			}
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			boolean r = view.mouseReleased(mx, my, b);
			r |= scan.mouseReleased(mx, my, b);
			r |= clear.mouseReleased(mx, my, b);
			return sorter.mouseReleased(mx, my, b) || r;
		}
	}

	// ==================================================================== list

	private static final class Listing extends W {
		private static final int ROW = 26, GO = 0, MAP = 1, DONE = 2, DEL = 3;
		private static final int[] BW = {26, 28, 38, 16};
		private List<Find> shown = List.of();
		private Finds.Store shownStore;
		private int shownRev = -1, shownMask = -1, frames;
		private Sort shownSort;
		private float scroll, target;
		private int pressRow = -1, pressBtn = -1;
		private final Anim bar = new Anim(0);

		private void refresh() {
			Finds.Store s = Finds.view;
			if (s == null) {
				shown = List.of();
				return;
			}
			boolean tick = sort == Sort.NEAREST && frames++ % 20 == 0;
			if (s == shownStore && s.rev == shownRev && mask == shownMask && sort == shownSort && !tick) return;
			shownStore = s;
			shownRev = s.rev;
			shownMask = mask;
			shownSort = sort;
			List<Find> l = new ArrayList<>();
			for (Find f : s.list) if (shows(f)) l.add(f);
			LocalPlayer p = Minecraft.getInstance().player;
			Comparator<Find> byNew = (a, b) -> Long.compare(b.first, a.first);
			switch (sort) {
				case NEWEST -> l.sort(byNew);
				case NEAREST -> {
					if (p != null && s == Finds.live) l.sort(Comparator.comparingDouble((Find f) -> f.dist2(p.getX(), p.getZ())));
					else l.sort(byNew);
				}
				case KIND -> l.sort(Comparator.comparingInt((Find f) -> f.kind.ordinal()).thenComparing(byNew));
			}
			shown = l;
		}

		private int viewH() {
			return h - 8;
		}

		private int maxScroll() {
			return Math.max(0, shown.size() * ROW - viewH());
		}

		/** x and width of one of a row's buttons (Go, Map, Done, delete), counted from the right edge. */
		private int btnX(int i) {
			int xr = x + w - 8 - (maxScroll() > 0 ? 6 : 0);
			for (int k = BW.length - 1; k > i; k--) xr -= BW[k] + 3;
			return xr - BW[i];
		}

		private int rowAt(double my) {
			int i = (int) Math.floor((my - (y + 4) + scroll) / ROW);
			return i >= 0 && i < shown.size() ? i : -1;
		}

		private int btnAt(double mx, double my, int row) {
			if (row < 0) return -1;
			int ry = y + 4 + row * ROW - Math.round(scroll);
			if (my < ry + 5 || my >= ry + 21) return -1;
			for (int i = 0; i < BW.length; i++) if (mx >= btnX(i) && mx < btnX(i) + BW[i]) return i;
			return -1;
		}

		private Host host;

		@Override public void render(Frame f) {
			host = f.host;
			refresh();
			Skin.card(f.g, x, y, w, h, null);
			int max = maxScroll();
			target = Mth.clamp(target, 0, max);
			scroll += (target - scroll) * (1f - (float) Math.exp(-f.dt * 22f));
			if (Math.abs(target - scroll) < 0.3f) scroll = target;
			scroll = Mth.clamp(scroll, 0, max);
			Finds.Store s = Finds.view;
			LocalPlayer p = Minecraft.getInstance().player;
			boolean live = s == Finds.live && p != null;
			if (shown.isEmpty()) {
				boolean any = s != null && !s.list.isEmpty();
				Ui.textC(f.g, any ? "Nothing of the kinds switched on." : "Nothing logged here yet.", x + w / 2, y + h / 2 - 10, Ui.DIM);
				Ui.textC(f.g, any ? "Switch more kinds on above." : "Chests, spawners, portals and players are logged as they load.", x + w / 2, y + h / 2 + 2, Ui.MUTED);
				return;
			}
			int acc = Ui.accent();
			f.g.enableScissor(x + 3, y + 4, x + w - 3, y + h - 4);
			int first = Math.max(0, (int) (scroll / ROW)), last = Math.min(shown.size() - 1, (int) ((scroll + viewH()) / ROW));
			int hot = over(f.mx, f.my) ? rowAt(f.my) : -1;
			for (int i = first; i <= last; i++) {
				Find fi = shown.get(i);
				int ry = y + 4 + i * ROW - Math.round(scroll), rx = x + 5, rw = w - 10 - (max > 0 ? 6 : 0);
				boolean sel = fi.id == selected, hit = i == hot;
				int r = Ui.theme().ctlR + 1;
				if (sel) {
					Ui.rrect(f.g, rx, ry, rw, ROW - 2, r, Ui.alpha(acc, 0.15f));
					Ui.rring(f.g, rx, ry, rw, ROW - 2, r, Ui.alpha(acc, 0.7f));
				} else if (hit) {
					Ui.rrect(f.g, rx, ry, rw, ROW - 2, r, Ui.alpha(0xFFFFFFFF, 0.05f));
				}
				Ui.rect(f.g, rx + 2, ry + 3, 2, ROW - 8, Ui.alpha(fi.kind.color, fi.checked ? 0.35f : 1f));
				var pose = f.g.pose();
				pose.pushMatrix();
				pose.translate(rx + 8, ry + 5);
				pose.scale(0.8f, 0.8f);
				f.g.fakeItem(icon(fi.kind), 0, 0);
				pose.popMatrix();
				int tx = rx + 26, tw = btnX(GO) - tx - 6;
				String head = fi.kind.label + "  ";
				int hw = Ui.width(head);
				Ui.text(f.g, head, tx, ry + 4, fi.checked ? Ui.alpha(fi.kind.color, 0.5f) : fi.kind.color);
				Ui.text(f.g, Ui.fit(fi.label, Math.max(20, (hit || sel ? tw : tw + 40) - hw)), tx + hw, ry + 4, fi.checked ? Ui.MUTED : Ui.TEXT);
				StringBuilder sub = new StringBuilder(fi.where());
				if (live) sub.append("  -  ").append(Math.round(Math.sqrt(fi.dist2(p.getX(), p.getZ())))).append(" m");
				if (!fi.detail.isEmpty()) sub.append("  -  ").append(fi.detail);
				Ui.text(f.g, Ui.fit(sub.toString(), Math.max(20, (hit || sel ? tw : tw + 40))), tx, ry + 14, Ui.DIM);
				if (hit || sel) {
					boolean go = live;
					for (int b = 0; b < BW.length; b++) {
						String label = switch (b) {
							case GO -> "Go";
							case MAP -> "Map";
							case DONE -> fi.checked ? "Undo" : "Done";
							default -> "x";
						};
						Skin.button(f.g, btnX(b), ry + 5, BW[b], 16, b == DEL ? Style.DANGER : b == GO ? Style.PRIMARY : Style.NORMAL, label, false, b != GO || go,
							f.mx >= btnX(b) && f.mx < btnX(b) + BW[b] && f.my >= ry + 5 && f.my < ry + 21 && hit ? 1 : 0, pressRow == i && pressBtn == b ? 1 : 0);
					}
					if (hit) {
						int bb = btnAt(f.mx, f.my, i);
						if (bb == GO) f.host.tip(go ? "Walk next to it with the pathfinder." : "You can only travel in the world you are in.");
						else if (bb == MAP) f.host.tip("Show it on the map.");
						else if (bb == DONE) f.host.tip(fi.checked ? "Mark it as not dealt with." : "Mark it as dealt with (a looted stash, for instance). It is no longer highlighted in the world.");
						else if (bb == DEL) f.host.tip("Remove it from the log.");
						else f.host.tip(fi.kind.label + ": " + fi.label + "  at " + fi.where() + "   first seen " + Find.ago(fi.first)
							+ (fi.kind == Kind.PLAYER ? ", last seen " + Find.ago(fi.last) + ", " + fi.seen + "x" : "") + (fi.detail.isEmpty() ? "" : "   " + fi.detail));
					}
				} else {
					Ui.textR(f.g, Find.ago(fi.kind == Kind.PLAYER ? fi.last : fi.first), rx + rw - 4, ry + 9, Ui.MUTED);
				}
			}
			f.g.disableScissor();
			if (max > 0) {
				float bv = bar.to(over(f.mx, f.my) ? 1 : 0.35f, f.dt, 12);
				int bh = Math.max(14, viewH() * viewH() / (shown.size() * ROW));
				int by = y + 4 + (int) ((viewH() - bh) * (scroll / max));
				Skin.scrollbar(f.g, x, y + 4, w, viewH(), by, bh, bv);
			}
		}

		private void showOnMap(Find f) {
			focus = f;
			selected = f.id;
			if (host != null) host.openTab(BotScreen.MAP);
		}

		@Override public boolean mouseClicked(double mx, double my, int b, boolean dbl) {
			if (!over(mx, my) || b != 0) return false;
			int row = rowAt(my);
			if (row < 0) return false;
			int btn = btnAt(mx, my, row);
			if (btn >= 0) { // the buttons of a row are only there while it is hovered or selected, and a mouse over them hovers it
				pressRow = row;
				pressBtn = btn;
				return true;
			}
			Find f = shown.get(row);
			if (dbl) showOnMap(f);
			else selected = selected == f.id ? -1 : f.id;
			return true;
		}

		@Override public boolean mouseReleased(double mx, double my, int b) {
			if (pressRow < 0) return false;
			int row = pressRow, btn = pressBtn;
			pressRow = pressBtn = -1;
			if (b != 0 || row >= shown.size() || btnAt(mx, my, row) != btn) return true;
			Find f = shown.get(row);
			Finds.Store s = Finds.view;
			Ui.click();
			switch (btn) {
				case GO -> {
					if (s == Finds.live) Radar.goTo(f);
				}
				case MAP -> showOnMap(f);
				case DONE -> {
					f.checked = !f.checked;
					s.touch();
				}
				default -> {
					if (selected == f.id) selected = -1;
					s.remove(f);
				}
			}
			return true;
		}

		@Override public boolean mouseScrolled(double mx, double my, double dy) {
			// at the top or the bottom of the list the wheel goes on to the page, so the settings below it can be reached
			if (maxScroll() <= 0 || dy > 0 && target <= 0.5f || dy < 0 && target >= maxScroll() - 0.5f) return false;
			target -= (float) dy * ROW;
			return true;
		}
	}
}
