package com.humanisedbaritone.gui;

import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/** Ambient animation of the interface styles: drifting lights, confetti, fireflies, click effects. */
final class Fx {
	private static final long START = System.nanoTime();
	private static final float[] RND = new float[1024];
	private static final int[] CONFETTI = {0xFFFF6FB5, 0xFFFFD23F, 0xFF4DE1FF, 0xFF6BF2A0, 0xFFFF9A45, 0xFFB69CFF};

	static {
		Random r = new Random(7);
		for (int i = 0; i < RND.length; i++) RND[i] = r.nextFloat();
	}

	private Fx() {
	}

	/** Seconds since start; frozen while animations are off, so the scenery stands still. */
	static float time() {
		return Settings.UI_ANIM.on() ? (System.nanoTime() - START) / 1e9f : 0f;
	}

	/** A fixed pseudo random number in 0..1 for (item, slot). */
	static float r(int i, int k) {
		return RND[Math.floorMod(i * 8 + k, RND.length)];
	}

	private static float wave(float t, float speed, float phase) {
		return (float) Math.sin(t * speed + phase * 6.2832f);
	}

	// ============================================================ whole screen

	/** Scenery behind the panel, in screen pixels: aurora (Modern), confetti (Fun), stars and fireflies (Relaxing). */
	static void backdrop(GuiGraphicsExtractor g, int w, int h, float o) {
		float t = time();
		int acc = Ui.accent();
		switch (Ui.theme().style) {
			case MODERN -> {
				float rx = Math.min(w * 0.32f, 170), ry = Math.min(h * 0.42f, 130);
				Ui.glow(g, w * (0.22f + 0.1f * wave(t, 0.09f, 0)), h * (0.3f + 0.12f * wave(t, 0.11f, 0.3f)), rx, ry, acc, 0.16f * o);
				Ui.glow(g, w * (0.8f + 0.08f * wave(t, 0.08f, 0.6f)), h * (0.72f + 0.1f * wave(t, 0.1f, 0.1f)), rx, ry, 0xFF7C5CFF, 0.13f * o);
			}
			case FUN -> {
				g.fill(0, 0, w, h, Ui.alpha(0xFF24104F, 0.35f * o));
				for (int i = 0; i < 44; i++) {
					float sz = 3 + r(i, 0) * 4, speed = 14 + r(i, 1) * 26;
					float x = r(i, 2) * w + wave(t, 0.5f + r(i, 4), r(i, 5)) * 14;
					float y = (r(i, 3) * (h + 20) + t * speed) % (h + 20) - 10;
					int c = Ui.alpha(CONFETTI[i % CONFETTI.length], 0.5f * o);
					if (i % 3 == 0) {
						Ui.circle(g, x, y, sz / 2, c);
					} else {
						var pose = g.pose();
						pose.pushMatrix();
						pose.translate(x, y);
						pose.rotate(t * (1 + r(i, 6) * 3) * (r(i, 7) < 0.5f ? -1 : 1) + r(i, 0) * 6.28f);
						int hs = Math.round(sz / 2);
						g.fill(-hs, -Math.max(1, hs / 2), hs, Math.max(1, hs / 2), c);
						pose.popMatrix();
					}
				}
			}
			case RELAXING -> {
				g.fillGradient(0, 0, w, h, Ui.alpha(0xFF070B22, 0.30f * o), Ui.alpha(0xFF2A1B52, 0.40f * o));
				for (int i = 0; i < 48; i++) {
					float tw = 0.25f + 0.65f * (0.5f + 0.5f * wave(t, 0.5f + r(i, 3) * 1.3f, r(i, 4)));
					int sx = (int) (r(i, 0) * w), sy = (int) (r(i, 1) * h * 0.85f), sz = r(i, 2) > 0.85f ? 2 : 1;
					g.fill(sx, sy, sx + sz, sy + sz, Ui.alpha(0xFFE9E6FF, 0.55f * tw * o));
				}
				float rx = Math.min(w * 0.3f, 170), ry = Math.min(h * 0.4f, 130);
				Ui.glow(g, w * (0.25f + 0.06f * wave(t, 0.05f, 0.2f)), h * (0.7f + 0.08f * wave(t, 0.06f, 0.7f)), rx, ry, acc, 0.13f * o);
				Ui.glow(g, w * (0.78f + 0.06f * wave(t, 0.045f, 0.5f)), h * (0.3f + 0.08f * wave(t, 0.055f, 0.1f)), rx, ry, 0xFFB79CFF, 0.11f * o);
				fireflies(g, 0, 0, w, h, 12, 40, 0.8f * o);
			}
			default -> {
			}
		}
	}

	// ==================================================================== panel

	private static float orbX, orbY;

	/** Moves the cursor-following light of the Relaxing style. */
	static void follow(float mx, float my, float dt) {
		float k = 1f - (float) Math.exp(-dt * 2.2f);
		orbX += (mx - orbX) * k;
		orbY += (my - orbY) * k;
	}

	/** Light inside the panel (call with the panel scissored): drifting glows, bokeh, fireflies, cursor light. */
	static void inside(GuiGraphicsExtractor g, int px, int py, int pw, int ph, float mx, float my, float o) {
		float t = time();
		int acc = Ui.accent();
		switch (Ui.theme().style) {
			case MODERN -> {
				Ui.glow(g, px + pw * (0.82f + 0.08f * wave(t, 0.13f, 0)), py + ph * 0.05f, 150, 90, acc, 0.12f * o);
				Ui.glow(g, px + pw * (0.12f + 0.06f * wave(t, 0.1f, 0.4f)), py + ph * 0.98f, 130, 80, 0xFF7C5CFF, 0.09f * o);
				Ui.glow(g, mx, my, 62, 62, 0xFFFFFFFF, 0.055f * o); // spotlight that follows the cursor
			}
			case FUN -> {
				for (int i = 0; i < 5; i++) {
					float rr = 20 + r(i, 0) * 24;
					float x = px + (r(i, 1) + 0.1f * wave(t, 0.12f + r(i, 2) * 0.1f, r(i, 3))) * pw;
					float y = py + (r(i, 4) + 0.1f * wave(t, 0.1f + r(i, 5) * 0.1f, r(i, 6))) * ph;
					Ui.circle(g, x, y, rr, Ui.alpha(CONFETTI[i % CONFETTI.length], 0.07f * o));
				}
			}
			case RELAXING -> {
				Ui.glow(g, px + pw * (0.2f + 0.08f * wave(t, 0.07f, 0.1f)), py + ph * (0.25f + 0.08f * wave(t, 0.06f, 0.5f)), 130, 90, acc, 0.10f * o);
				Ui.glow(g, px + pw * (0.8f + 0.07f * wave(t, 0.06f, 0.9f)), py + ph * (0.8f + 0.08f * wave(t, 0.07f, 0.3f)), 140, 95, 0xFFB79CFF, 0.10f * o);
				fireflies(g, px, py, pw, ph, 6, 0, 0.75f * o);
				Ui.glow(g, orbX, orbY, 46, 46, 0xFFFFF3C4, 0.07f * o);
			}
			default -> {
			}
		}
	}

	/** Glowing dots that drift and blink. {@code seed} picks a different set of them. */
	static void fireflies(GuiGraphicsExtractor g, int x, int y, int w, int h, int n, int seed, float a) {
		float t = time();
		for (int i = seed; i < seed + n; i++) {
			float px = x + r(i, 0) * w + wave(t, 0.13f + r(i, 2) * 0.2f, r(i, 3)) * w * 0.07f;
			float py = y + r(i, 1) * h + wave(t, 0.11f + r(i, 4) * 0.2f, r(i, 7)) * h * 0.07f;
			float b = Math.max(0, wave(t, 0.4f + r(i, 5) * 0.5f, r(i, 6)));
			b *= b;
			if (b < 0.02f) continue;
			int c = i % 2 == 0 ? 0xFFE8F5A8 : 0xFFA8F0E0;
			Ui.circle(g, px, py, 4.5f, Ui.alpha(c, 0.11f * b * a));
			Ui.circle(g, px, py, 1.6f, Ui.alpha(0xFFFFFFF0, 0.9f * b * a));
		}
	}

	// ================================================================= clicks

	private static final class P {
		float x, y, vx, vy, life, max, rot, vr;
		int color, size;
		boolean ring;
	}

	private static final List<P> PARTS = new ArrayList<>();

	/** What a click does: confetti (Fun), a soft ripple (Relaxing), a quick ripple (Modern). Panel coordinates. */
	static void click(float x, float y) {
		if (!Settings.UI_ANIM.on() || PARTS.size() > 150) return;
		int acc = Ui.accent();
		switch (Ui.theme().style) {
			case FUN -> {
				for (int i = 0; i < 9; i++) {
					P p = new P();
					double ang = Math.random() * Math.PI * 2, sp = 40 + Math.random() * 70;
					p.x = x;
					p.y = y;
					p.vx = (float) (Math.cos(ang) * sp);
					p.vy = (float) (Math.sin(ang) * sp) - 45;
					p.max = p.life = 0.6f + (float) Math.random() * 0.35f;
					p.rot = (float) (Math.random() * 6);
					p.vr = (float) (Math.random() * 14 - 7);
					p.size = 2 + (int) (Math.random() * 3);
					p.color = i % 3 == 0 ? acc : CONFETTI[(int) (Math.random() * CONFETTI.length)];
					PARTS.add(p);
				}
			}
			case RELAXING, MODERN -> {
				P p = new P();
				p.x = x;
				p.y = y;
				p.ring = true;
				p.max = p.life = Ui.theme().style == Settings.UiStyle.RELAXING ? 1.1f : 0.5f;
				p.size = Ui.theme().style == Settings.UiStyle.RELAXING ? 42 : 24;
				p.color = acc;
				PARTS.add(p);
			}
			default -> {
			}
		}
	}

	/** Moves and draws the click effects (panel coordinates, call with the panel scissored). */
	static void overlay(GuiGraphicsExtractor g, float dt) {
		for (int i = PARTS.size() - 1; i >= 0; i--) {
			P p = PARTS.get(i);
			p.life -= dt;
			if (p.life <= 0) {
				PARTS.remove(i);
				continue;
			}
			float f = p.life / p.max;
			if (p.ring) {
				float e = 1 - f, r = 3 + (p.size - 3) * (1 - (1 - e) * (1 - e));
				int d = Math.round(r * 2);
				Ui.rring(g, Math.round(p.x - r), Math.round(p.y - r), d, d, d / 2, Ui.alpha(p.color, 0.5f * f * f));
			} else {
				p.vy += 230 * dt;
				p.x += p.vx * dt;
				p.y += p.vy * dt;
				p.rot += p.vr * dt;
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(p.x, p.y);
				pose.rotate(p.rot);
				g.fill(-p.size, -Math.max(1, p.size / 2), p.size, Math.max(1, p.size / 2), Ui.alpha(p.color, Mth.clamp(f * 1.6f, 0, 1)));
				pose.popMatrix();
			}
		}
	}
}
