package com.humanisedbaritone.gui;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Path;
import com.humanisedbaritone.scan.Atlas;
import com.humanisedbaritone.scan.Find;
import com.humanisedbaritone.scan.Find.Kind;
import com.humanisedbaritone.scan.Finds;
import com.humanisedbaritone.scan.Radar;
import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;

/**
 * The Map tab: the terrain you have seen in the world and dimension you are viewing, with you on it, the other players in
 * view and everything the scanner logged. Drag to move, wheel to zoom, click a marker to select it, right-click to drop a pin.
 */
final class MapPage extends W {
	/** How many blocks the farthest zoom shows across the map. */
	private static final float FAR = 200_000f;
	/** Pixels per block; entry 0 is a placeholder, see {@link #scale()}. */
	private static final float[] SCALES = {0.005f, 0.01f, 0.02f, 0.04f, 0.08f, 0.125f, 0.25f, 0.5f, 1f, 2f, 4f, 8f};
	private static final int ONE = 8; // index of scale 1
	/** Below this scale a map tile is a few pixels across: not worth decoding, only the dots are drawn. */
	private static final float MIN_TERRAIN = 0.03f;
	private static final Minecraft mc = Minecraft.getInstance();
	/** Kept between visits to the tab: zoom level (index into {@link #SCALES}), the world point in the middle, whether the view follows you. */
	private static int zoom = ONE;
	private static double cx, cz;
	private static boolean follow = true, legend = true, terrain = true;

	private final LogPage.ViewBar view = new LogPage.ViewBar();
	private final Btn zin, zout, followBtn, legendBtn, terrainBtn, go, copy, mark, log, clearMap;
	private Finds.Store lastStore;
	private boolean dragging, moved;
	private double lastX, lastY, pinX, pinZ;
	private boolean hasPin;
	private long clearArmed;
	private int frames;
	/** Geometry of the map area, set every frame. */
	private int vx, vy, vw, vh;
	private Host host;

	MapPage() {
		zin = new Btn("+", Style.NORMAL, () -> zoomBy(1, vx + vw / 2.0, vy + vh / 2.0));
		zout = new Btn("-", Style.NORMAL, () -> zoomBy(-1, vx + vw / 2.0, vy + vh / 2.0));
		followBtn = new Btn("Follow", Style.NORMAL, () -> follow = !follow);
		followBtn.active = () -> follow;
		followBtn.tip = "Keep the map centred on you.";
		legendBtn = new Btn("Legend", Style.NORMAL, () -> legend = !legend);
		legendBtn.active = () -> legend;
		legendBtn.tip = "Show what each colour on the map means. Click a kind in it to show or hide it.";
		terrainBtn = new Btn("Terrain", Style.NORMAL, () -> terrain = !terrain);
		terrainBtn.active = () -> terrain;
		terrainBtn.tip = "Draw the land with its colours. Off: only the logged dots on an empty map.";
		zin.tip = "Zoom in (mouse wheel).";
		zout.tip = "Zoom out (mouse wheel).";
		go = new Btn("Go", Style.PRIMARY, this::goThere);
		go.tip = "Walk there with the pathfinder.";
		copy = new Btn("Copy", Style.NORMAL, this::copyCoords);
		copy.tip = "Copy the coordinates to the clipboard.";
		mark = new Btn("Mark", Style.NORMAL, this::markPin);
		mark.tip = "Keep this spot as a marker of your own.";
		log = new Btn("Log", Style.NORMAL, () -> {
			if (host != null) host.openTab(Settings.LOGGING);
		});
		log.tip = "Show it in the list.";
		clearMap = new Btn("Clear map", Style.DANGER, () -> {
			Finds.Store s = Finds.view;
			if (s == null) return;
			if (System.currentTimeMillis() - clearArmed < 3000) {
				s.atlas.clear();
				clearArmed = 0;
				Log.say("The map of " + s.worldName + " (" + Finds.prettyDim(s.dim) + ") was cleared.");
			} else clearArmed = System.currentTimeMillis();
		});
		clearMap.tip = "Forget the terrain of this world and dimension (the finds stay). Click twice.";
	}

	/** Pixels per block; the farthest level is worked out from the map width so that it always shows {@link #FAR} blocks across. */
	private float scale() {
		return zoom == 0 ? Math.max(1e-4f, Math.min(vw / FAR, SCALES[1] / 1.5f)) : SCALES[zoom];
	}

	private Find selectedFind() {
		Finds.Store s = Finds.view;
		return s == null || LogPage.selected < 0 ? null : s.byId(LogPage.selected);
	}

	// ------------------------------------------------------------------ actions

	private void zoomBy(int dir, double mx, double my) {
		int nz = Mth.clamp(zoom + dir, 0, SCALES.length - 1);
		if (nz == zoom) return;
		double vcx = vx + vw / 2.0, vcy = vy + vh / 2.0;
		double wx = cx + (mx - vcx) / scale(), wz = cz + (my - vcy) / scale();
		zoom = nz;
		if (!follow) { // the point under the cursor stays under it
			cx = wx - (mx - vcx) / scale();
			cz = wz - (my - vcy) / scale();
		}
	}

	private void goThere() {
		Finds.Store s = Finds.view;
		if (s != Finds.live) return;
		Find f = selectedFind();
		if (f != null) Radar.goTo(f);
		else if (hasPin) Commands.run("goto " + (int) Math.floor(pinX) + " " + (int) Math.floor(pinZ));
	}

	private void copyCoords() {
		Find f = selectedFind();
		String t = f != null ? f.where().replace(",", "") : (int) Math.floor(pinX) + " " + (int) Math.floor(pinZ);
		if (f != null || hasPin) mc.keyboardHandler.setClipboard(t);
	}

	private void markPin() {
		if (!hasPin || Finds.view != Finds.live) return;
		Find f = Radar.mark("Marker " + (Finds.live.count(Kind.NOTE) + 1), (int) Math.floor(pinX), Find.NO_Y, (int) Math.floor(pinZ));
		if (f != null) LogPage.selected = f.id;
		hasPin = false;
	}

	/** Puts the middle of the view where it should be for a store: on you when it is the live one. */
	private void recentre(Finds.Store s) {
		LocalPlayer p = mc.player;
		if (s == Finds.live && p != null) {
			cx = p.getX();
			cz = p.getZ();
			follow = true;
			return;
		}
		follow = false;
		double sx = 0, sz = 0;
		int n = 0;
		for (Find f : s.list) {
			sx += f.x;
			sz += f.z;
			n++;
		}
		if (n > 0) {
			cx = sx / n;
			cz = sz / n;
			return;
		}
		var tiles = s.atlas.known();
		for (int[] t : tiles) {
			sx += (t[0] + 0.5) * Atlas.SIZE;
			sz += (t[1] + 0.5) * Atlas.SIZE;
		}
		cx = tiles.isEmpty() ? 0 : sx / tiles.size();
		cz = tiles.isEmpty() ? 0 : sz / tiles.size();
	}

	// ------------------------------------------------------------------ geometry

	private void geom() {
		vx = x;
		vy = y + 19;
		vw = w;
		vh = h - 19 - 25;
	}

	private float sx(double wx) {
		return (float) (vx + vw / 2.0 + (wx - cx) * scale());
	}

	private float sz(double wz) {
		return (float) (vy + vh / 2.0 + (wz - cz) * scale());
	}

	private double worldX(double mx) {
		return cx + (mx - (vx + vw / 2.0)) / scale();
	}

	private double worldZ(double my) {
		return cz + (my - (vy + vh / 2.0)) / scale();
	}

	private boolean inMap(double mx, double my) {
		return mx >= vx && mx < vx + vw && my >= vy && my < vy + vh;
	}

	// -------------------------------------------------------------------- render

	@Override public void render(Frame f) {
		host = f.host;
		Finds.Store s = Finds.view;
		if (s == null) return;
		geom();
		LocalPlayer p = mc.player;
		boolean live = s == Finds.live && p != null;
		if (s != lastStore) {
			lastStore = s;
			hasPin = false;
			recentre(s);
		}
		if (LogPage.focus != null) {
			Find t = LogPage.focus;
			LogPage.focus = null;
			cx = t.x + 0.5;
			cz = t.z + 0.5;
			follow = false;
			zoom = Math.max(zoom, ONE);
			LogPage.selected = t.id;
		}
		if (follow && live) {
			cx = p.getX();
			cz = p.getZ();
		}
		var g = f.g;
		Atlas atlas = s.atlas;
		atlas.loadsLeft = 3;
		if (++frames % 120 == 0) atlas.releaseTextures(false);

		// top row: which world and dimension, and whether the map is being drawn
		view.at(x, y + 1, w - 78, 15);
		view.render(f);
		boolean rec = live && Settings.MAP_RECORD.on();
		Ui.textR(g, rec ? "Recording" : live ? "Not recording" : "Saved map", x + w - 4, y + 5, rec ? Ui.GOOD : Ui.MUTED);

		// the map itself
		g.enableScissor(vx, vy, vx + vw, vy + vh);
		Ui.rect(g, vx, vy, vw, vh, Ui.FIELD);
		float sc = scale();
		if (terrain && sc >= MIN_TERRAIN) drawTiles(g, atlas, sc);
		drawGrid(g, sc);
		if (live) drawBorder(g);
		drawFinds(f, s, sc);
		if (live) drawRoute(g, sc);
		drawPlayers(f, p, live, sc);
		drawScale(g, sc);
		if (hasPin) drawPin(g);
		drawOverlay(f, s);
		g.disableScissor();
		Ui.rring(g, vx, vy, vw, vh, 2, Ui.STROKE);

		drawInfo(f, s, live);
	}

	private void drawTiles(net.minecraft.client.gui.GuiGraphicsExtractor g, Atlas atlas, float sc) {
		int t0x = Math.floorDiv((int) Math.floor(worldX(vx)), Atlas.SIZE), t1x = Math.floorDiv((int) Math.floor(worldX(vx + vw)), Atlas.SIZE);
		int t0z = Math.floorDiv((int) Math.floor(worldZ(vy)), Atlas.SIZE), t1z = Math.floorDiv((int) Math.floor(worldZ(vy + vh)), Atlas.SIZE);
		for (int tx = t0x; tx <= t1x; tx++) {
			for (int tz = t0z; tz <= t1z; tz++) {
				Atlas.Tile t = atlas.get(tx, tz, false);
				if (t == null) continue;
				int x0 = Math.round(sx((double) tx * Atlas.SIZE)), x1 = Math.round(sx((double) (tx + 1) * Atlas.SIZE));
				int z0 = Math.round(sz((double) tz * Atlas.SIZE)), z1 = Math.round(sz((double) (tz + 1) * Atlas.SIZE));
				if (x1 <= x0 || z1 <= z0) continue;
				g.blit(RenderPipelines.GUI_TEXTURED, atlas.texture(t), x0, z0, 0f, 0f, x1 - x0, z1 - z0, Atlas.SIZE, Atlas.SIZE, Atlas.SIZE, Atlas.SIZE);
			}
		}
	}

	/** Chunk borders at every zoom (zoomed out the lines are every 2nd, 4th, 8th ... chunk so they stay at least 12 px apart), and the two axes through 0. */
	private void drawGrid(net.minecraft.client.gui.GuiGraphicsExtractor g, float sc) {
		int chunks = 1;
		while (chunks * 16 * sc < 12) chunks <<= 1;
		double step = chunks * 16.0;
		long c0 = (long) Math.floor(worldX(vx) / step), c1 = (long) Math.floor(worldX(vx + vw) / step);
		for (long c = c0; c <= c1; c++) {
			int px = Math.round(sx(c * step));
			g.fill(px, vy, px + 1, vy + vh, Ui.alpha(0xFFFFFFFF, c % 8 == 0 ? 0.09f : 0.04f));
		}
		c0 = (long) Math.floor(worldZ(vy) / step);
		c1 = (long) Math.floor(worldZ(vy + vh) / step);
		for (long c = c0; c <= c1; c++) {
			int pz = Math.round(sz(c * step));
			g.fill(vx, pz, vx + vw, pz + 1, Ui.alpha(0xFFFFFFFF, c % 8 == 0 ? 0.09f : 0.04f));
		}
		int ax = Math.round(sx(0)), az = Math.round(sz(0));
		if (ax >= vx && ax < vx + vw) g.fill(ax, vy, ax + 1, vy + vh, Ui.alpha(0xFFFFFFFF, 0.14f));
		if (az >= vy && az < vy + vh) g.fill(vx, az, vx + vw, az + 1, Ui.alpha(0xFFFFFFFF, 0.14f));
	}

	/** The world border of the dimension you are in, as a yellow-orange square. */
	private void drawBorder(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (mc.level == null) return;
		var b = mc.level.getWorldBorder();
		int col = 0xFFFFA81E;
		double x0 = sx(b.getMinX()), x1 = sx(b.getMaxX()), z0 = sz(b.getMinZ()), z1 = sz(b.getMaxZ());
		int ix0 = (int) Mth.clamp(x0, vx - 5, vx + vw + 5), ix1 = (int) Mth.clamp(x1, vx - 5, vx + vw + 5);
		int iz0 = (int) Mth.clamp(z0, vy - 5, vy + vh + 5), iz1 = (int) Mth.clamp(z1, vy - 5, vy + vh + 5);
		if (z0 >= vy - 2 && z0 < vy + vh + 2) g.fill(ix0, (int) z0 - 1, ix1 + 1, (int) z0 + 1, col);
		if (z1 >= vy - 2 && z1 < vy + vh + 2) g.fill(ix0, (int) z1 - 1, ix1 + 1, (int) z1 + 1, col);
		if (x0 >= vx - 2 && x0 < vx + vw + 2) g.fill((int) x0 - 1, iz0, (int) x0 + 1, iz1 + 1, col);
		if (x1 >= vx - 2 && x1 < vx + vw + 2) g.fill((int) x1 - 1, iz0, (int) x1 + 1, iz1 + 1, col);
	}

	/** Markers of the finds that are switched on; returns the one under the mouse. */
	private Find drawFinds(Frame f, Finds.Store s, float sc) {
		var g = f.g;
		Find hover = null;
		double best = 1e9;
		int sel = LogPage.selected;
		Find selected = null;
		for (int pass = 0; pass < 2; pass++) {
			for (Find fi : s.list) {
				if (!LogPage.shows(fi) || (fi.id == sel) != (pass == 1)) continue;
				float px = sx(fi.x + 0.5), pz = sz(fi.z + 0.5);
				if (px < vx - 12 || px > vx + vw + 12 || pz < vy - 12 || pz > vy + vh + 12) continue;
				int col = fi.kind.color;
				float a = fi.checked ? 0.45f : 1f;
				if (fi.kind == Kind.STASH || fi.kind == Kind.SEARCH || fi.kind == Kind.PORTAL) { // the area a cluster covers
					int bx0 = Math.round(sx(fi.minX)), bx1 = Math.round(sx(fi.maxX + 1.0)), bz0 = Math.round(sz(fi.minZ)), bz1 = Math.round(sz(fi.maxZ + 1.0));
					if (bx1 - bx0 >= 6 || bz1 - bz0 >= 6) Ui.rring(g, bx0, bz0, Math.max(3, bx1 - bx0), Math.max(3, bz1 - bz0), 0, Ui.alpha(col, 0.55f * a));
				}
				int r = (fi.id == sel ? 1 : 0) + (sc < 0.5f ? 3 : 4); // smaller markers when zoomed far out, where they would hide the land
				Ui.circle(g, px, pz, r + 1.5f, Ui.alpha(0xFF000000, 0.75f * a));
				Ui.circle(g, px, pz, r, Ui.alpha(col, a));
				if (fi.id == sel) {
					Ui.rring(g, Math.round(px) - 8, Math.round(pz) - 8, 16, 16, 8, 0xFFFFFFFF);
					selected = fi;
				}
				double d = Math.hypot(f.mx - px, f.my - pz);
				if (d < r + 4 && d < best && f.mx >= vx && f.mx < vx + vw && f.my >= vy && f.my < vy + vh) {
					best = d;
					hover = fi;
				}
			}
		}
		for (Find fi : new Find[]{selected, hover}) { // a name tag for the selected and the hovered one
			if (fi == null) continue;
			float px = sx(fi.x + 0.5), pz = sz(fi.z + 0.5);
			String t = Ui.fit(fi.kind.label + ": " + fi.label, 170);
			int tw = Ui.width(t) + 8, tx = Mth.clamp(Math.round(px) - tw / 2, vx + 2, vx + vw - tw - 2), ty = Math.max(vy + 2, Math.round(pz) - 22);
			Ui.rrect(g, tx, ty, tw, 12, 4, Ui.alpha(0xFF000000, 0.8f));
			Ui.rring(g, tx, ty, tw, 12, 4, Ui.alpha(fi.kind.color, 0.8f));
			Ui.text(g, t, tx + 4, ty + 2, Ui.TEXT);
		}
		if (hover != null) f.host.tip(hover.kind.label + ": " + hover.label + "  at " + hover.where() + (hover.detail.isEmpty() ? "" : "   " + hover.detail) + "   (click to select)");
		return hover;
	}

	/** Where the bot is going: its planned route as dots (a long straight run is one step of the plan, so the dots are spread along it), and a ring on the goal. */
	private void drawRoute(net.minecraft.client.gui.GuiGraphicsExtractor g, float sc) {
		if (!Bot.enabled || !Bot.busy()) return;
		Path path = Bot.nav.path();
		if (path != null) {
			int col = Ui.alpha(0xFF46E6A0, 0.95f), dots = 0;
			for (int i = Math.max(0, Bot.nav.index()); i < path.steps.size() && dots < 700; i++) {
				Path.Step st = path.steps.get(i);
				double x0 = st.sx() + 0.5, z0 = st.sz() + 0.5, x1 = st.dx() + 0.5, z1 = st.dz() + 0.5;
				int n = Math.max(1, (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0)) * sc / 5)); // a dot every five units on screen
				for (int k = 1; k <= n && dots < 700; k++, dots++) {
					double t = k / (double) n;
					int px = Math.round(sx(x0 + (x1 - x0) * t)), pz = Math.round(sz(z0 + (z1 - z0) * t));
					if (px >= vx && px < vx + vw && pz >= vy && pz < vy + vh) g.fill(px - 1, pz - 1, px + 1, pz + 1, col);
				}
			}
		}
		Goal goal = Bot.nav.goal();
		if (goal == null || goal instanceof Goal.Away || goal instanceof Goal.Level) return;
		var a = goal.anchor();
		int gx = Math.round(sx(a.getX() + 0.5)), gz = Math.round(sz(a.getZ() + 0.5));
		Ui.rring(g, gx - 5, gz - 5, 11, 11, 5, 0xFF46E6A0);
		Ui.rect(g, gx - 1, gz - 1, 3, 3, 0xFF46E6A0);
	}

	private void drawPlayers(Frame f, LocalPlayer me, boolean live, float sc) {
		if (!live || mc.level == null) return;
		var g = f.g;
		for (AbstractClientPlayer o : mc.level.players()) {
			if (o == me) continue;
			float px = sx(o.getX()), pz = sz(o.getZ());
			if (px < vx - 6 || px > vx + vw + 6 || pz < vy - 6 || pz > vy + vh + 6) continue;
			Ui.circle(g, px, pz, 3.5f, Ui.alpha(0xFF000000, 0.8f));
			Ui.circle(g, px, pz, 2.5f, 0xFFFFFFFF);
			if (sc >= 1 || Math.hypot(f.mx - px, f.my - pz) < 6) {
				String n = o.getName().getString();
				Ui.text(g, n, Math.round(px) - Ui.width(n) / 2, Math.round(pz) + 5, Ui.alpha(0xFFFFFFFF, 0.85f));
			}
		}
		// you: an arrow that points where you look (north is up)
		float px = sx(me.getX()), pz = sz(me.getZ());
		if (px < vx - 10 || px > vx + vw + 10 || pz < vy - 10 || pz > vy + vh + 10) return;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(px, pz);
		pose.rotate((float) Math.toRadians(me.getYRot() - 180));
		int[] half = {0, 0, 1, 1, 2, 3, 3, 4, 4};
		for (int i = 0; i < half.length; i++) g.fill(-half[i] - 1, i - 6, half[i] + 2, i - 5, Ui.alpha(0xFF000000, 0.85f));
		g.fill(-2, 3, 3, 4, Ui.alpha(0xFF000000, 0.85f));
		for (int i = 0; i < half.length; i++) g.fill(-half[i], i - 5, half[i] + 1, i - 4, Ui.accent());
		pose.popMatrix();
	}

	private void drawPin(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		int px = Math.round(sx(pinX)), pz = Math.round(sz(pinZ));
		g.fill(px - 6, pz, px + 7, pz + 1, 0xFFFFFFFF);
		g.fill(px, pz - 6, px + 1, pz + 7, 0xFFFFFFFF);
		Ui.rring(g, px - 4, pz - 4, 9, 9, 4, Ui.accent());
	}

	/** A bar that shows how long some round number of blocks is at this zoom. */
	private void drawScale(net.minecraft.client.gui.GuiGraphicsExtractor g, float sc) {
		int[] steps = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};
		int pick = steps[0];
		for (int st : steps) {
			pick = st;
			if (st * sc >= 40) break;
		}
		String t = pick + " blocks";
		int len = Math.round(pick * sc), bx = vx + vw - 8 - Math.max(len, Ui.width(t)), by = vy + vh - 12; // bottom right: the legend is at the left
		Ui.rect(g, bx, by, len, 1, 0xFFFFFFFF);
		Ui.rect(g, bx, by - 3, 1, 4, 0xFFFFFFFF);
		Ui.rect(g, bx + len, by - 3, 1, 4, 0xFFFFFFFF);
		Ui.rect(g, bx - 2, by - 14, Ui.width(t) + 4, 10, Ui.alpha(0xFF000000, 0.55f));
		Ui.text(g, t, bx, by - 13, Ui.TEXT);
	}

	/** The zoom and follow buttons, and the legend. */
	private void drawOverlay(Frame f, Finds.Store s) {
		int by = vy + 5, right = vx + vw - 6;
		zin.at(right - 16, by, 16, 14);
		zout.at(zin.x - 3 - 16, by, 16, 14);
		followBtn.at(zout.x - 3 - 44, by, 44, 14);
		legendBtn.at(followBtn.x - 3 - 46, by, 46, 14);
		terrainBtn.at(legendBtn.x - 3 - 50, by, 50, 14);
		for (W b : new W[]{zin, zout, followBtn, legendBtn, terrainBtn}) b.render(f);
		if (!legend) return;
		Kind[] ks = Kind.values();
		int[] n = new int[ks.length];
		for (Find fi : s.list) n[fi.kind.ordinal()]++;
		int lw = 0;
		for (int i = 0; i < ks.length; i++) lw = Math.max(lw, Ui.width(ks[i].label + " " + n[i]));
		int lx = vx + 6, ly = vy + 5, rowH = 12;
		Ui.rrect(f.g, lx - 2, ly - 2, lw + 22, ks.length * rowH + 4, 4, Ui.alpha(0xFF000000, 0.62f));
		for (int i = 0; i < ks.length; i++) {
			boolean on = (LogPage.mask & ks[i].bit()) != 0, hot = f.mx >= lx - 2 && f.mx < lx + lw + 20 && f.my >= ly + i * rowH && f.my < ly + (i + 1) * rowH;
			if (hot) {
				Ui.rect(f.g, lx - 2, ly + i * rowH, lw + 22, rowH, Ui.alpha(0xFFFFFFFF, 0.08f));
				f.host.tip("Click to show or hide " + ks[i].label.toLowerCase() + " markers.");
			}
			Ui.circle(f.g, lx + 4, ly + i * rowH + 6, 3, on ? ks[i].color : Ui.alpha(ks[i].color, 0.3f));
			Ui.text(f.g, ks[i].label + " " + n[i], lx + 12, ly + i * rowH + 2, on ? Ui.TEXT : Ui.MUTED);
		}
	}

	/** The line below the map: the block under the mouse, or what is selected, and the buttons that go with it. */
	private void drawInfo(Frame f, Finds.Store s, boolean live) {
		var g = f.g;
		int iy = vy + vh + 3, bx = x + w;
		clearMap.label = System.currentTimeMillis() - clearArmed < 3000 ? "Sure?" : "Clear map";
		clearMap.at(bx - 62, iy + 3, 62, 15);
		clearMap.render(f);
		bx = clearMap.x - 4;
		Find sel = selectedFind();
		String text;
		if (sel != null) {
			text = sel.kind.label + ": " + sel.label + "  -  " + sel.where() + (live ? "  -  " + Math.round(Math.sqrt(sel.dist2(mc.player.getX(), mc.player.getZ()))) + " m" : "");
			place(f, log, bx -= 34, iy + 3, 34, true);
			place(f, copy, bx -= 38, iy + 3, 34, true);
			place(f, go, bx -= 30, iy + 3, 26, live);
		} else if (hasPin) {
			text = "Pin  -  " + (int) Math.floor(pinX) + ", " + (int) Math.floor(pinZ);
			place(f, mark, bx -= 40, iy + 3, 36, live);
			place(f, copy, bx -= 38, iy + 3, 34, true);
			place(f, go, bx -= 30, iy + 3, 26, live);
		} else {
			text = f.mx >= vx && f.mx < vx + vw && f.my >= vy && f.my < vy + vh
				? "X " + (int) Math.floor(worldX(f.mx)) + "   Z " + (int) Math.floor(worldZ(f.my)) + "   chunk " + Math.floorDiv((int) Math.floor(worldX(f.mx)), 16) + ", " + Math.floorDiv((int) Math.floor(worldZ(f.my)), 16)
				: s.atlas.size() == 0 ? "Nothing mapped yet - walk around." : "Drag to move, wheel to zoom, right-click for a pin.";
		}
		Ui.text(g, Ui.fit(text, Math.max(40, bx - x - 8)), x + 4, iy + 7, sel != null ? Ui.TEXT : Ui.DIM);
	}

	private static void place(Frame f, Btn b, int bx, int by, int bw, boolean enabled) {
		b.at(bx, by, bw, 15);
		b.enabled = enabled;
		b.render(f);
	}

	// --------------------------------------------------------------------- input

	@Override public boolean mouseClicked(double mx, double my, int b, boolean dbl) {
		geom();
		if (view.mouseClicked(mx, my, b, dbl) || clearMap.mouseClicked(mx, my, b, dbl)) return true;
		Find sel = selectedFind();
		for (Btn bt : new Btn[]{zin, zout, followBtn, legendBtn, terrainBtn, go, copy, mark, log}) {
			boolean shown = bt == go || bt == copy ? sel != null || hasPin : bt == mark ? hasPin && sel == null : bt == log ? sel != null : true;
			if (shown && bt.mouseClicked(mx, my, b, dbl)) return true;
		}
		if (!inMap(mx, my)) return false;
		if (legend && b == 0) { // a row of the legend
			Kind[] ks = Kind.values();
			int lw = 0;
			Finds.Store s = Finds.view;
			int[] n = new int[ks.length];
			if (s != null) for (Find fi : s.list) n[fi.kind.ordinal()]++;
			for (int i = 0; i < ks.length; i++) lw = Math.max(lw, Ui.width(ks[i].label + " " + n[i]));
			int lx = vx + 6, ly = vy + 5;
			if (mx >= lx - 2 && mx < lx + lw + 20 && my >= ly && my < ly + ks.length * 12) {
				LogPage.mask ^= ks[(int) ((my - ly) / 12)].bit();
				Ui.click();
				return true;
			}
		}
		if (b == 1) {
			pinX = worldX(mx);
			pinZ = worldZ(my);
			hasPin = true;
			LogPage.selected = -1;
			return true;
		}
		if (b != 0) return false;
		dragging = true;
		moved = false;
		lastX = mx;
		lastY = my;
		if (dbl) {
			dragging = false;
			cx = worldX(mx);
			cz = worldZ(my);
			follow = false;
			zoomBy(1, mx, my);
		}
		return true;
	}

	@Override public boolean mouseDragged(double mx, double my, int b) {
		if (!dragging || b != 0) return false;
		double dx = mx - lastX, dy = my - lastY;
		if (!moved && Math.abs(dx) + Math.abs(dy) < 3) return true;
		moved = true;
		follow = false;
		cx -= dx / scale();
		cz -= dy / scale();
		lastX = mx;
		lastY = my;
		return true;
	}

	@Override public boolean mouseReleased(double mx, double my, int b) {
		boolean r = view.mouseReleased(mx, my, b);
		for (Btn bt : new Btn[]{zin, zout, followBtn, legendBtn, terrainBtn, go, copy, mark, log, clearMap}) r |= bt.mouseReleased(mx, my, b);
		if (!dragging) return r;
		dragging = false;
		if (!moved && b == 0 && inMap(mx, my)) { // a click, not a drag: select the marker under it
			Finds.Store s = Finds.view;
			Find pick = null;
			double best = 1e9;
			if (s != null) {
				for (Find fi : s.list) {
					if (!LogPage.shows(fi)) continue;
					double d = Math.hypot(mx - sx(fi.x + 0.5), my - sz(fi.z + 0.5));
					if (d < 9 && d < best) {
						best = d;
						pick = fi;
					}
				}
			}
			LogPage.selected = pick == null ? -1 : pick.id;
			if (pick != null) hasPin = false;
		}
		return true;
	}

	@Override public boolean mouseScrolled(double mx, double my, double dy) {
		geom();
		if (!inMap(mx, my)) return false;
		zoomBy(dy > 0 ? 1 : -1, mx, my);
		return true;
	}
}
