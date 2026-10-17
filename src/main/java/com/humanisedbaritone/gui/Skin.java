package com.humanisedbaritone.gui;

import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.settings.Settings.UiStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/** How every control and surface is drawn in each interface style. Widgets keep the behaviour and the animation state, this class only paints. */
final class Skin {
	private static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;

	private Skin() {
	}

	// ================================================================= button

	/** A button; {@code hv} is the hover fade and {@code pv} the press depth (both 0..1), {@code on} a toggled-on look. */
	static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, W.Style st, String label, boolean on, boolean enabled, float hv, float pv) {
		Theme t = Ui.theme();
		int acc = Ui.accent(), yo = Math.round(pv), cy = y + (h - 8) / 2;
		String s = Ui.fit(label, w - 6);
		switch (t.style) {
			case MINECRAFT -> {
				int base, edge, txt;
				switch (st) {
					case PRIMARY -> {
						base = Ui.mix(Ui.mix(acc, BLACK, 0.35f), acc, hv * 0.6f + (on ? 0.4f : 0));
						edge = Ui.mix(acc, WHITE, 0.15f);
						txt = Ui.ON_ACCENT;
					}
					case DANGER -> {
						base = Ui.mix(0xFF3A1E2A, Ui.BAD, hv * 0.55f);
						edge = Ui.mix(0xFF5A2A3A, Ui.BAD, 0.6f);
						txt = Ui.mix(Ui.BAD, WHITE, hv);
					}
					case GHOST -> {
						base = Ui.mix(on ? Ui.alpha(acc, 0.22f) : 0x00000000, Ui.alpha(WHITE, 0.10f), hv);
						edge = on ? Ui.alpha(acc, 0.6f) : 0x00000000;
						txt = on ? acc : Ui.mix(Ui.DIM, Ui.TEXT, hv);
					}
					default -> {
						base = Ui.mix(Ui.CARD_HI, Ui.mix(Ui.CARD_HI, WHITE, 0.12f), hv + (on ? 0.6f : 0));
						edge = Ui.mix(Ui.STROKE, acc, hv * 0.7f + (on ? 0.5f : 0));
						txt = Ui.mix(Ui.TEXT, acc, on ? 1 : 0);
					}
				}
				if (!enabled) {
					base = Ui.mulAlpha(base, 0.5f);
					txt = Ui.mulAlpha(txt, 0.45f);
				}
				Ui.rrect(g, x, y + yo, w, h, 3, base);
				if ((edge >>> 24) != 0) Ui.rring(g, x, y + yo, w, h, 3, edge);
				Ui.textC(g, s, x + w / 2, cy + yo, txt);
			}
			case MODERN -> {
				int r = t.ctlR, top, bot, edge, txt;
				switch (st) {
					case PRIMARY -> {
						top = Ui.mix(acc, WHITE, 0.06f + 0.14f * hv);
						bot = Ui.mix(acc, BLACK, 0.24f - 0.1f * hv);
						edge = Ui.alpha(WHITE, 0.2f);
						txt = Ui.ON_ACCENT;
					}
					case DANGER -> {
						top = bot = Ui.alpha(Ui.BAD, 0.14f + 0.2f * hv);
						edge = Ui.alpha(Ui.BAD, 0.55f);
						txt = Ui.mix(Ui.BAD, WHITE, hv);
					}
					case GHOST -> {
						top = bot = on ? Ui.alpha(acc, 0.16f) : Ui.alpha(WHITE, 0.08f * hv);
						edge = on ? Ui.alpha(acc, 0.5f) : 0;
						txt = on ? acc : Ui.mix(Ui.DIM, Ui.TEXT, hv);
					}
					default -> {
						top = Ui.mix(Ui.CARD_HI, WHITE, 0.05f + 0.06f * hv + (on ? 0.08f : 0));
						bot = Ui.CARD_HI;
						edge = Ui.mix(Ui.STROKE, acc, hv * 0.75f + (on ? 0.6f : 0));
						txt = on ? acc : Ui.TEXT;
					}
				}
				if (!enabled) {
					top = Ui.mulAlpha(top, 0.5f);
					bot = Ui.mulAlpha(bot, 0.5f);
					edge = Ui.mulAlpha(edge, 0.5f);
					txt = Ui.mulAlpha(txt, 0.45f);
				}
				if (st == W.Style.PRIMARY && enabled && hv > 0.02f) Ui.shadow(g, x, y + yo, w, h, r, acc, 4, hv * 0.5f);
				Ui.rrectV(g, x, y + yo, w, h, r, top, bot);
				if ((edge >>> 24) != 0) Ui.rring(g, x, y + yo, w, h, r, edge);
				if (enabled && hv > 0.05f && st != W.Style.GHOST) sheen(g, x + 1, y + yo, w - 2, h, hv);
				Ui.textC(g, s, x + w / 2, cy + yo, txt);
			}
			case FUN -> {
				int r = t.ctlR, ol = Ui.STROKE, fill, txt;
				float ea = enabled ? 1 : 0.5f;
				int bx = Math.round(-hv + pv * 2); // hover lifts the button, a press sinks it into its shadow
				switch (st) {
					case PRIMARY -> {
						fill = Ui.mix(acc, WHITE, 0.2f * hv);
						txt = Ui.ON_ACCENT;
					}
					case DANGER -> {
						fill = Ui.mix(Ui.BAD, WHITE, 0.2f * hv);
						txt = Ui.ON_ACCENT;
					}
					case GHOST -> {
						fill = on ? Ui.alpha(acc, 0.3f) : Ui.alpha(WHITE, 0.14f * hv);
						txt = on ? acc : Ui.mix(Ui.DIM, Ui.TEXT, hv);
					}
					default -> {
						fill = on ? Ui.mix(acc, WHITE, 0.15f) : Ui.mix(Ui.BTN, acc, 0.3f * hv);
						txt = on ? Ui.ON_ACCENT : Ui.BTN_TEXT;
					}
				}
				if (st == W.Style.GHOST) {
					Ui.rrect(g, x, y, w, h, r, Ui.mulAlpha(fill, ea));
					if (on) Ui.rring(g, x, y, w, h, r, acc);
					Ui.textC(g, s, x + w / 2, cy, Ui.mulAlpha(txt, ea));
				} else {
					Ui.rrect(g, x + 2, y + 2, w, h, r, Ui.mulAlpha(ol, ea));
					Ui.rrect(g, x + bx, y + bx, w, h, r, Ui.mulAlpha(ol, ea));
					Ui.rrect(g, x + bx + 2, y + bx + 2, w - 4, h - 4, Math.max(1, r - 2), Ui.mulAlpha(fill, ea));
					Ui.rect(g, x + bx + 5, y + bx + 2, w - 10, 1, Ui.alpha(WHITE, 0.4f * ea));
					Ui.textC(g, s, x + w / 2 + bx, cy + bx, Ui.mulAlpha(txt, ea));
				}
			}
			case RELAXING -> {
				int r = h / 2, fill, top = 0, bot = 0, ring, txt;
				boolean grad = false;
				switch (st) {
					case PRIMARY -> {
						grad = true;
						top = Ui.mix(acc, WHITE, 0.3f + 0.1f * hv);
						bot = Ui.mix(acc, WHITE, 0.06f);
						fill = top;
						ring = 0;
						txt = Ui.ON_ACCENT;
						if (enabled) Ui.shadow(g, x, y + yo, w, h, r, acc, 5, 0.3f + 0.4f * hv);
					}
					case DANGER -> {
						fill = Ui.alpha(Ui.BAD, 0.16f + 0.2f * hv);
						ring = Ui.alpha(Ui.BAD, 0.4f);
						txt = Ui.BAD;
					}
					case GHOST -> {
						fill = on ? Ui.alpha(acc, 0.2f) : Ui.alpha(WHITE, 0.09f * hv);
						ring = on ? Ui.alpha(acc, 0.5f) : 0;
						txt = on ? acc : Ui.mix(Ui.DIM, Ui.TEXT, hv);
					}
					default -> {
						fill = on ? Ui.alpha(acc, 0.28f) : Ui.mix(0x1EFFFFFF, 0x3AFFFFFF, hv);
						ring = Ui.alpha(on ? acc : WHITE, on ? 0.6f : 0.12f + 0.2f * hv);
						txt = on ? acc : Ui.TEXT;
					}
				}
				if (!enabled) {
					fill = Ui.mulAlpha(fill, 0.5f);
					top = Ui.mulAlpha(top, 0.5f);
					bot = Ui.mulAlpha(bot, 0.5f);
					ring = Ui.mulAlpha(ring, 0.5f);
					txt = Ui.mulAlpha(txt, 0.45f);
				}
				if (grad) Ui.rrectV(g, x, y + yo, w, h, r, top, bot);
				else Ui.rrect(g, x, y + yo, w, h, r, fill);
				if ((ring >>> 24) != 0) Ui.rring(g, x, y + yo, w, h, r, ring);
				Ui.textC(g, s, x + w / 2, cy + yo, txt);
			}
		}
	}

	/** A soft light streak that sweeps over a hovered button. */
	private static void sheen(GuiGraphicsExtractor g, int x, int y, int w, int h, float hv) {
		int sx = x + Math.round((Fx.time() * 80f) % (w + 50) - 25);
		g.enableScissor(x, y, x + w, y + h);
		for (int k = 0; k < 3; k++) g.fill(sx + k * 5, y, sx + k * 5 + 5, y + h, Ui.alpha(WHITE, (k == 1 ? 0.13f : 0.06f) * hv));
		g.disableScissor();
	}

	/** A plain {@code < label >} choice button. */
	static void cycle(GuiGraphicsExtractor g, int bx, int y, int bw, int h, String label, float hv, boolean enabled, int labelX) {
		Theme t = Ui.theme();
		int acc = Ui.accent(), ty = y + (h - 8) / 2;
		if (t.style == UiStyle.MINECRAFT) {
			Ui.rrect(g, bx, y + 2, bw, h - 4, 3, Ui.mix(Ui.CARD_HI, 0xFF2A3354, hv));
			Ui.rring(g, bx, y + 2, bw, h - 4, 3, Ui.mix(Ui.STROKE, acc, hv * 0.7f));
			Ui.text(g, "<", bx + 5, ty, Ui.mix(Ui.MUTED, acc, hv));
			Ui.text(g, ">", bx + bw - 10, ty, Ui.mix(Ui.MUTED, acc, hv));
			Ui.textC(g, label, labelX, ty, enabled ? Ui.TEXT : Ui.MUTED);
			return;
		}
		boolean fun = t.style == UiStyle.FUN;
		button(g, bx, y + 2, bw, h - 4, W.Style.NORMAL, "", false, enabled, hv, 0);
		int o = fun ? Math.round(-hv) : 0, slide = Math.round(hv * 1.5f);
		int ac = fun ? Ui.BTN_TEXT : Ui.mix(Ui.MUTED, acc, hv);
		Ui.text(g, "<", bx + 5 + o - slide, ty + o, ac);
		Ui.text(g, ">", bx + bw - 10 + o + slide, ty + o, ac);
		Ui.textC(g, label, labelX + o, ty + o, enabled ? (fun ? Ui.BTN_TEXT : Ui.TEXT) : Ui.MUTED);
	}

	// ================================================================= toggle

	/** The on/off switch at the right edge of (x, y, w, h); {@code k} is the animated position of the knob (0 = off, 1 = on, may overshoot). */
	static void toggle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean compact, boolean on, boolean enabled, float k, float hv) {
		Theme t = Ui.theme();
		int acc = Ui.accent();
		float kc = Mth.clamp(k, 0, 1), ea = enabled ? 1 : 0.45f;
		switch (t.style) {
			case MINECRAFT -> {
				int track = Ui.mix(Ui.mix(0xFF2A3354, 0xFF3A466E, hv), Ui.mix(acc, BLACK, 0.25f), kc);
				if (!enabled) track = Ui.mulAlpha(track, 0.45f);
				int tw = 26, th = 12, tx = x + w - tw, ty = y + (h - th) / 2;
				if (kc > 0.05f && enabled) Ui.shadow(g, tx, ty, tw, th, 6, acc, 3, kc * 0.55f);
				Ui.rrect(g, tx, ty, tw, th, 6, track);
				int kx = tx + 2 + Math.round(kc * (tw - th));
				int kcol = Ui.mix(0xFFB8C2E2, WHITE, kc);
				if (!enabled) kcol = Ui.mulAlpha(kcol, 0.5f);
				Ui.rrect(g, kx, ty + 2, th - 4, th - 4, 4, kcol);
				if (!compact) Ui.textR(g, on ? "ON" : "OFF", tx - 5, y + (h - 8) / 2, Ui.mix(Ui.MUTED, acc, kc));
			}
			case MODERN -> {
				int tw = 28, th = 14, tx = x + w - tw, ty = y + (h - th) / 2;
				int track = Ui.mulAlpha(Ui.mix(Ui.mix(0xFF262C3F, 0xFF353D56, hv), Ui.mix(acc, BLACK, 0.2f), kc), ea);
				if (kc > 0.05f && enabled) Ui.shadow(g, tx, ty, tw, th, 7, acc, 4, kc * 0.5f);
				Ui.rrect(g, tx, ty, tw, th, 7, track);
				float kx = tx + 2 + kc * (tw - th) + (th - 4) / 2f;
				Ui.circle(g, kx, ty + th / 2f, (th - 4) / 2f, Ui.mulAlpha(Ui.mix(0xFFB8C2E2, WHITE, kc), ea));
				if (!compact) Ui.textR(g, on ? "ON" : "OFF", tx - 6, y + (h - 8) / 2, Ui.mix(Ui.MUTED, acc, kc));
			}
			case FUN -> {
				int tw = 34, th = 18, tx = x + w - tw, ty = y + (h - th) / 2, di = th - 6, ol = Ui.mulAlpha(Ui.STROKE, ea);
				Ui.rrect(g, tx + 1, ty + 2, tw, th, th / 2, ol);
				Ui.rrect(g, tx, ty, tw, th, th / 2, ol);
				Ui.rrect(g, tx + 2, ty + 2, tw - 4, th - 4, (th - 4) / 2, Ui.mulAlpha(Ui.mix(0xFF6A58B4, acc, kc), ea));
				int kw = di + Math.round(5 * (float) Math.sin(Math.PI * kc)); // the knob stretches while it travels
				int kx = tx + 3 + Math.round(Mth.clamp(k, -0.1f, 1.12f) * (tw - 6 - di));
				Ui.rrect(g, kx - 2, ty + 1, kw + 4, th - 2, (th - 2) / 2, ol);
				Ui.rrect(g, kx, ty + 3, kw, di, di / 2, Ui.mulAlpha(WHITE, ea));
				int fcx = kx + kw / 2, fcy = ty + th / 2, face = Ui.mulAlpha(Ui.STROKE, ea);
				if (kc > 0.5f) { // a smile when on
					Ui.rect(g, fcx - 3, fcy - 3, 2, 2, face);
					Ui.rect(g, fcx + 1, fcy - 3, 2, 2, face);
					Ui.rect(g, fcx - 2, fcy + 1, 4, 1, face);
					Ui.rect(g, fcx - 3, fcy, 1, 1, face);
					Ui.rect(g, fcx + 2, fcy, 1, 1, face);
				} else { // sleepy when off
					Ui.rect(g, fcx - 3, fcy - 2, 2, 1, face);
					Ui.rect(g, fcx + 1, fcy - 2, 2, 1, face);
					Ui.rect(g, fcx - 1, fcy + 2, 2, 1, face);
				}
				if (!compact) Ui.textR(g, on ? "ON" : "OFF", tx - 6, y + (h - 8) / 2, on ? acc : Ui.MUTED);
			}
			case RELAXING -> {
				int tw = 28, th = 10, tx = x + w - tw, ty = y + (h - th) / 2;
				float br = 0.75f + 0.25f * (float) Math.sin(Fx.time() * 1.7f), cx = tx + 7 + kc * (tw - 14), cy = ty + th / 2f;
				Ui.rrect(g, tx, ty, tw, th, th / 2, Ui.mulAlpha(Ui.mix(0x24FFFFFF, Ui.alpha(acc, 0.55f), kc), ea));
				Ui.rring(g, tx, ty, tw, th, th / 2, Ui.mulAlpha(Ui.mix(0x30FFFFFF, Ui.alpha(acc, 0.8f), kc), ea));
				if (kc > 0.05f && enabled) {
					Ui.circle(g, cx, cy, 12, Ui.alpha(acc, 0.10f * kc * br));
					Ui.circle(g, cx, cy, 9.5f, Ui.alpha(acc, 0.16f * kc * br));
				}
				Ui.circle(g, cx, cy, 6.5f, Ui.mulAlpha(Ui.mix(0xFFCFCDEB, WHITE, kc), ea));
				if (!compact) Ui.textR(g, on ? "ON" : "OFF", tx - 6, y + (h - 8) / 2, Ui.mix(Ui.MUTED, acc, kc));
			}
		}
	}

	// ================================================================= slider

	/** Track, fill, knob and value text of a slider; {@code tw} is the track width and {@code fr} the animated filled fraction. */
	static void slider(GuiGraphicsExtractor g, int x, int y, int w, int h, int tw, float fr, float hv, boolean enabled, boolean drag, String value) {
		Theme t = Ui.theme();
		int acc = Ui.accent(), cy = y + h / 2, ty = y + (h - 8) / 2;
		int vcol = enabled ? Ui.mix(Ui.DIM, Ui.TEXT, hv) : Ui.MUTED;
		float f = Mth.clamp(fr, 0, 1.04f);
		switch (t.style) {
			case MINECRAFT -> {
				int tr = y + h / 2 - 2;
				Ui.rrect(g, x, tr, tw, 4, 2, enabled ? 0xFF262E4C : 0xFF1A2038);
				int fw = Math.max(4, Math.round(tw * f));
				if (enabled) {
					Ui.rrect(g, x, tr, fw, 4, 2, Ui.mix(Ui.mix(acc, BLACK, 0.35f), acc, 0.4f + 0.6f * hv));
					int kx = x + Math.min(tw - 5, Math.max(0, fw - 3));
					if (hv > 0.05f) Ui.shadow(g, kx, tr - 3, 6, 10, 3, acc, 3, hv * 0.6f);
					Ui.rrect(g, kx, tr - 3, 6, 10, 3, Ui.mix(0xFFC9D2F0, WHITE, hv));
				}
			}
			case MODERN -> {
				int tr = cy - 2, fw = Math.max(4, Math.round(tw * f));
				Ui.rrect(g, x, tr, tw, 4, 2, enabled ? 0xFF232A3E : 0xFF161A27);
				if (enabled) {
					Ui.rrect(g, x, tr, fw, 4, 2, Ui.mix(Ui.mix(acc, BLACK, 0.2f), acc, 0.4f + 0.6f * hv));
					float kx = x + Mth.clamp(fw, 5, tw - 5), kr = 5 + hv + (drag ? 0.5f : 0);
					Ui.circle(g, kx, cy, kr + 4, Ui.alpha(acc, 0.12f * hv));
					Ui.circle(g, kx, cy, kr + 2, Ui.alpha(acc, 0.22f * hv));
					Ui.circle(g, kx, cy, kr, WHITE);
					Ui.circle(g, kx, cy, 2, acc);
				}
			}
			case FUN -> {
				int tr = cy - 4, fw = Math.round((tw - 4) * f), ol = Ui.mulAlpha(Ui.STROKE, enabled ? 1 : 0.5f);
				Ui.rrect(g, x, tr, tw, 8, 4, ol);
				Ui.rrect(g, x + 2, tr + 2, tw - 4, 4, 2, enabled ? Ui.FIELD : Ui.mulAlpha(Ui.FIELD, 0.6f));
				if (enabled) {
					if (fw > 0) Ui.rrect(g, x + 2, tr + 2, Math.max(4, fw), 4, 2, acc);
					float kx = x + Mth.clamp(2 + fw, 7, tw - 7), kr = 7 + hv * 1.5f + (drag ? 1 : 0);
					Ui.circle(g, kx + 1, cy + 2, kr, Ui.alpha(Ui.STROKE, 0.6f));
					Ui.circle(g, kx, cy, kr, ol);
					Ui.circle(g, kx, cy, kr - 2, WHITE);
					Ui.circle(g, kx, cy, 2.5f, acc);
				}
			}
			case RELAXING -> {
				int tr = cy - 1, fw = Math.max(3, Math.round(tw * f));
				Ui.rrect(g, x, tr, tw, 3, 1, Ui.alpha(WHITE, enabled ? 0.14f : 0.07f));
				if (enabled) {
					Ui.rrect(g, x, tr, fw, 3, 1, Ui.mix(acc, WHITE, 0.2f));
					float br = 0.75f + 0.25f * (float) Math.sin(Fx.time() * 1.7f), kx = x + Mth.clamp(fw, 5, tw - 5);
					Ui.circle(g, kx, cy, 10 + hv * 2, Ui.alpha(acc, 0.10f * br * (0.6f + 0.4f * hv)));
					Ui.circle(g, kx, cy, 7.5f, Ui.alpha(acc, 0.18f * br));
					Ui.circle(g, kx, cy, 5 + hv * 0.8f, 0xFFF4F1FF);
				}
			}
		}
		Ui.textR(g, value, x + w, ty, vcol);
	}

	// ============================================================ text field

	/** Horizontal text inset inside a field. */
	static int fieldPad() {
		return switch (Ui.theme().style) {
			case RELAXING -> 8;
			case FUN -> 5;
			default -> 4;
		};
	}

	/** Background and outline of a text field; {@code fv} is the focus fade. */
	static void field(GuiGraphicsExtractor g, int x, int y, int w, int h, float fv) {
		Theme t = Ui.theme();
		int acc = Ui.accent();
		switch (t.style) {
			case MINECRAFT -> {
				Ui.rrect(g, x, y, w, h, 3, Ui.FIELD);
				Ui.rring(g, x, y, w, h, 3, Ui.mix(Ui.STROKE, acc, fv));
			}
			case MODERN -> {
				if (fv > 0.02f) Ui.shadow(g, x, y, w, h, t.ctlR, acc, 3, fv * 0.5f);
				Ui.rrect(g, x, y, w, h, t.ctlR, Ui.FIELD);
				Ui.rring(g, x, y, w, h, t.ctlR, Ui.mix(Ui.STROKE, acc, fv));
				int lw = Math.round((w - 8) * fv);
				if (lw > 0) Ui.rect(g, x + (w - lw) / 2, y + h - 2, lw, 1, acc);
			}
			case FUN -> {
				int r = t.ctlR;
				Ui.rrect(g, x + 1, y + 2, w, h, r, Ui.STROKE);
				Ui.rrect(g, x, y, w, h, r, Ui.mix(Ui.STROKE, acc, fv));
				Ui.rrect(g, x + 2, y + 2, w - 4, h - 4, Math.max(1, r - 2), Ui.FIELD);
			}
			case RELAXING -> {
				int r = h / 2;
				if (fv > 0.02f) Ui.shadow(g, x, y, w, h, r, acc, 5, fv * 0.45f);
				Ui.rrect(g, x, y, w, h, r, Ui.FIELD);
				Ui.rring(g, x, y, w, h, r, Ui.mix(0x1FFFFFFF, Ui.alpha(acc, 0.8f), fv));
			}
		}
	}

	// ============================================================== scrollbar

	/** Scroll track and thumb along the right edge of an area at (x, y, w, h). */
	static void scrollbar(GuiGraphicsExtractor g, int x, int y, int w, int h, int by, int bh, float bv) {
		int acc = Ui.accent();
		switch (Ui.theme().style) {
			case MINECRAFT -> {
				Ui.rrect(g, x + w - 4, y, 3, h, 1, Ui.alpha(WHITE, 0.05f * bv));
				Ui.rrect(g, x + w - 4, by, 3, bh, 1, Ui.alpha(acc, 0.35f + 0.5f * bv));
			}
			case MODERN -> Ui.rrect(g, x + w - 3, by, 2, bh, 1, Ui.alpha(acc, 0.3f + 0.5f * bv));
			case FUN -> {
				Ui.rrect(g, x + w - 6, y, 6, h, 3, Ui.alpha(Ui.STROKE, 0.5f));
				Ui.rrect(g, x + w - 6, by, 6, bh, 3, Ui.STROKE);
				Ui.rrect(g, x + w - 5, by + 1, 4, bh - 2, 2, Ui.mix(Ui.mix(acc, BLACK, 0.35f), acc, 0.4f + 0.6f * bv));
			}
			case RELAXING -> Ui.rrect(g, x + w - 4, by, 3, bh, 1, Ui.alpha(WHITE, 0.14f + 0.26f * bv));
		}
	}

	// ============================================================== surfaces

	/** A dashboard card; the title goes into the top left corner. */
	static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, String title) {
		Theme t = Ui.theme();
		int acc = Ui.accent(), r = t.cardR;
		switch (t.style) {
			case MINECRAFT -> {
				Ui.rrect(g, x, y, w, h, r, Ui.CARD);
				Ui.rring(g, x, y, w, h, r, Ui.alpha(Ui.STROKE, 0.8f));
				if (title != null) Ui.text(g, title.toUpperCase(), x + 10, y + 7, Ui.alpha(acc, 0.95f));
			}
			case MODERN -> {
				Ui.rrectV(g, x, y, w, h, r, Ui.mix(Ui.CARD, WHITE, 0.03f), Ui.CARD);
				Ui.rring(g, x, y, w, h, r, Ui.STROKE);
				if (title != null) {
					Ui.rrect(g, x + 10, y + 7, 2, 8, 1, acc);
					Ui.text(g, title.toUpperCase(), x + 16, y + 7, Ui.mix(Ui.DIM, acc, 0.45f));
				}
			}
			case FUN -> {
				Ui.rrect(g, x + 3, y + 3, w, h, r, Ui.STROKE);
				Ui.rrect(g, x, y, w, h, r, Ui.STROKE);
				Ui.rrect(g, x + 2, y + 2, w - 4, h - 4, r - 2, Ui.CARD);
				if (title != null) {
					String s = title.toUpperCase();
					Ui.rrect(g, x + 8, y + 5, Ui.width(s) + 12, 12, 6, acc);
					Ui.text(g, s, x + 14, y + 7, Ui.ON_ACCENT);
				}
			}
			case RELAXING -> {
				Ui.rrect(g, x, y, w, h, r, Ui.CARD);
				Ui.rring(g, x, y, w, h, r, Ui.alpha(WHITE, 0.09f));
				if (title != null) Ui.text(g, title, x + 12, y + 7, Ui.mix(acc, WHITE, 0.2f));
			}
		}
	}

	/** The panel itself: shadow, body, header tint, border and the light inside. {@code mx, my} is the cursor in panel units. */
	static void panel(GuiGraphicsExtractor g, int px, int py, int pw, int ph, float o, float mx, float my) {
		Theme t = Ui.theme();
		int acc = Ui.accent(), r = t.panelR;
		float a = Math.min(1, o * 1.2f), time = Fx.time();
		int in = Math.max(2, Math.round(r * 0.3f));
		switch (t.style) {
			case MINECRAFT -> {
				Ui.shadow(g, px, py, pw, ph, r, acc, 10, 0.55f * o);
				Ui.rrect(g, px, py, pw, ph, r, Ui.mulAlpha(Ui.BG, a));
				g.fillGradient(px + 1, py + 1, px + pw - 1, py + t.hdrH, Ui.alpha(acc, 0.13f), Ui.alpha(acc, 0.0f));
				Ui.rring(g, px, py, pw, ph, r, Ui.mix(Ui.STROKE, acc, 0.45f));
			}
			case MODERN -> {
				Ui.shadow(g, px, py + 5, pw, ph, r, BLACK, 12, 0.6f * o);
				Ui.shadow(g, px, py, pw, ph, r, acc, 10, 0.26f * o);
				Ui.rrectV(g, px, py, pw, ph, r, Ui.mulAlpha(Ui.BG, a), Ui.mulAlpha(Ui.BG2, a));
				g.enableScissor(px + in, py + in, px + pw - in, py + ph - in);
				Fx.inside(g, px, py, pw, ph, mx, my, o);
				g.disableScissor();
				Ui.rrectV(g, px, py, pw, t.hdrH, r, Ui.alpha(acc, 0.10f), Ui.alpha(acc, 0f));
				Ui.rring(g, px, py, pw, ph, r, Ui.mix(Ui.STROKE, acc, 0.22f));
				// a light that runs along the top edge and one that runs back along the bottom edge
				float sw = 90, span = pw + sw * 2;
				for (int k = 0; k < 9; k++) {
					float s = (float) Math.sin(Math.PI * (k + 0.5f) / 9), l = px + (time * 70f) % span - sw + k * sw / 9, m = px + pw - ((time * 55f + 80f) % span - sw + k * sw / 9) - sw / 9;
					edgeBit(g, px, py, pw, r, l, py, Ui.alpha(acc, 0.9f * s * o));
					edgeBit(g, px, py, pw, r, m, py + ph - 1, Ui.alpha(acc, 0.55f * s * o));
				}
			}
			case FUN -> {
				Ui.rrect(g, px + 4, py + 5, pw, ph, r, Ui.alpha(0xFF0A0418, 0.8f * o));
				Ui.rrect(g, px, py, pw, ph, r, Ui.mulAlpha(Ui.STROKE, Math.min(1, o * 1.5f)));
				Ui.rrectV(g, px + 3, py + 3, pw - 6, ph - 6, r - 3, Ui.mulAlpha(Ui.BG, a), Ui.mulAlpha(Ui.BG2, a));
				g.enableScissor(px + in + 3, py + in + 3, px + pw - in - 3, py + ph - in - 3);
				Fx.inside(g, px, py, pw, ph, mx, my, o);
				g.disableScissor();
				Ui.rrectV(g, px + 3, py + 3, pw - 6, t.hdrH, r - 3, Ui.alpha(acc, 0.3f), Ui.alpha(acc, 0f));
			}
			case RELAXING -> {
				float breath = 0.5f + 0.5f * (float) Math.sin(time * 1.1f);
				Ui.shadow(g, px, py + 6, pw, ph, r, BLACK, 10, 0.45f * o);
				Ui.shadow(g, px, py, pw, ph, r, acc, 12, (0.2f + 0.14f * breath) * o);
				Ui.rrectV(g, px, py, pw, ph, r, Ui.mulAlpha(Ui.BG, a), Ui.mulAlpha(Ui.BG2, a));
				g.enableScissor(px + in, py + in, px + pw - in, py + ph - in);
				Fx.inside(g, px, py, pw, ph, mx, my, o);
				g.disableScissor();
				Ui.rrectV(g, px, py, pw, t.hdrH + 6, r, Ui.alpha(acc, 0.10f), Ui.alpha(acc, 0f));
				Ui.rring(g, px, py, pw, ph, r, Ui.alpha(WHITE, 0.13f));
			}
		}
	}

	/** One column of a light running along an edge, kept inside the rounded corners. */
	private static void edgeBit(GuiGraphicsExtractor g, int px, int py, int pw, int r, float x, int y, int c) {
		int x0 = Math.max(Math.round(x), px + r), x1 = Math.min(Math.round(x) + 11, px + pw - r);
		if (x1 > x0) g.fill(x0, y, x1, y + 1, c);
	}

	/** The area under the header: a moving stripe of candy colours for Fun. */
	static void stripe(GuiGraphicsExtractor g, int x, int y, int w) {
		int[] cols = {0xFFFF6FB5, 0xFFFFD23F, 0xFF4DE1FF, 0xFF6BF2A0, 0xFFFF9A45, 0xFFB69CFF};
		int seg = 12, off = Math.round(Fx.time() * 14f) % (seg * cols.length);
		g.enableScissor(x, y, x + w, y + 3);
		for (int i = -1; i <= w / seg + cols.length; i++) {
			int sx = x + i * seg - off;
			g.fill(sx, y, sx + seg, y + 3, cols[Math.floorMod(i, cols.length)]);
		}
		g.disableScissor();
	}

	/** The mark in the top left corner of the header, centred on (cx, cy). {@code hv} is hover. */
	static void logo(GuiGraphicsExtractor g, int cx, int cy, float hv) {
		Theme t = Ui.theme();
		int acc = Ui.accent();
		float time = Fx.time();
		switch (t.style) {
			case MINECRAFT -> {
				for (int i = 0; i < 6; i++) { // a small diamond
					int w = i < 3 ? 2 + i * 2 : 2 + (5 - i) * 2;
					g.fill(cx - w / 2, cy - 3 + i, cx + w / 2 + (w % 2), cy - 2 + i, acc);
				}
			}
			case MODERN -> {
				Ui.glow(g, cx, cy, 15, 15, acc, 0.45f + 0.15f * (float) Math.sin(time * 2f));
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(cx, cy);
				pose.rotate(time * 0.9f);
				g.fill(-4, -4, 4, 4, acc);
				g.fill(-2, -2, 2, 2, Ui.BG);
				pose.popMatrix();
			}
			case FUN -> {
				float cyc = (time * (1 + hv * 1.6f)) % 2.6f, hop = 0, sy = 1;
				if (cyc < 0.45f) {
					hop = (float) Math.sin(Math.PI * cyc / 0.45f) * 5;
					sy = 1 + 0.06f * (float) Math.sin(Math.PI * cyc / 0.45f);
				} else if (cyc < 0.58f) {
					sy = 1 - 0.16f * (1 - (cyc - 0.45f) / 0.13f);
				}
				int bw = 22, bh = Math.round(20 * sy), bx = cx - bw / 2, by = Math.round(cy + 11 - bh - hop);
				Ui.rrect(g, cx - 8 + Math.round(hop / 2), cy + 11, 16 - Math.round(hop), 3, 1, Ui.alpha(BLACK, 0.3f));
				Ui.rrect(g, bx, by, bw, bh, 7, Ui.STROKE);
				Ui.rrect(g, bx + 2, by + 2, bw - 4, bh - 4, 5, acc);
				Ui.rect(g, bx + 5, by + 3, 6, 1, Ui.alpha(WHITE, 0.5f));
				int eh = time % 3.3f < 0.12f ? 1 : 4, ey = by + bh / 2 - 4 + (4 - eh);
				Ui.rect(g, cx - 5, ey, 2, eh, Ui.STROKE);
				Ui.rect(g, cx + 3, ey, 2, eh, Ui.STROKE);
				Ui.rect(g, cx - 3, by + bh - 7, 6, 1, Ui.STROKE);
				Ui.rect(g, cx - 4, by + bh - 8, 1, 1, Ui.STROKE);
				Ui.rect(g, cx + 3, by + bh - 8, 1, 1, Ui.STROKE);
				Ui.rrect(g, cx - 8, by + bh - 8, 3, 2, 1, Ui.alpha(0xFFFF4F8B, 0.6f));
				Ui.rrect(g, cx + 5, by + bh - 8, 3, 2, 1, Ui.alpha(0xFFFF4F8B, 0.6f));
			}
			case RELAXING -> {
				for (int i = 0; i < 3; i++) { // rings that breathe outwards
					float p = (time * 0.3f + i / 3f) % 1f, rr = 3 + 10 * p;
					int d = Math.round(rr * 2);
					Ui.rring(g, Math.round(cx - rr), Math.round(cy - rr), d, d, d / 2, Ui.alpha(acc, 0.55f * (1 - p) * (1 - p)));
				}
				Ui.circle(g, cx, cy, 5.5f, Ui.alpha(acc, 0.2f));
				Ui.circle(g, cx, cy, 3, acc);
			}
		}
	}

	/** A progress bar: {@code v} is 0..1, below 0 it is a track that stays empty, or with {@code busy} a block that sweeps along it. */
	static void progress(GuiGraphicsExtractor g, int x, int y, int w, int h, float v, boolean busy) {
		Theme t = Ui.theme();
		int acc = Ui.accent(), r = h / 2;
		float time = Fx.time();
		switch (t.style) {
			case FUN -> {
				Ui.rrect(g, x - 1, y - 1, w + 2, h + 2, r + 1, Ui.STROKE);
				Ui.rrect(g, x, y, w, h, r, Ui.FIELD);
			}
			case RELAXING -> Ui.rrect(g, x, y, w, h, r, Ui.alpha(WHITE, 0.1f));
			default -> Ui.rrect(g, x, y, w, h, r, Ui.FIELD);
		}
		int x0 = x, fw;
		if (v >= 0) {
			fw = Math.max(h, Math.round(w * Math.min(1f, v)));
		} else if (busy) {
			int seg = w / 4, off = Settings.UI_ANIM.on() ? Math.round(time * 166f) % (w + seg) - seg : (w - seg) / 2;
			x0 = Math.max(x, x + off);
			fw = Math.min(x + w, x + off + seg) - x0;
			if (fw <= 0) return;
		} else {
			return;
		}
		int col = v >= 0 ? acc : Ui.alpha(acc, 0.8f);
		switch (t.style) {
			case MODERN -> {
				Ui.rrectV(g, x0, y, fw, h, r, Ui.mix(col, WHITE, 0.25f), Ui.mix(col, BLACK, 0.15f));
				g.enableScissor(x0, y, x0 + fw, y + h);
				int sx = x0 + Math.round(time * 60f) % (fw + 30) - 15;
				for (int k = 0; k < 3; k++) g.fill(sx + k * 4, y, sx + k * 4 + 4, y + h, Ui.alpha(WHITE, k == 1 ? 0.4f : 0.18f));
				g.disableScissor();
			}
			case FUN -> {
				Ui.rrect(g, x0, y, fw, h, r, col);
				g.enableScissor(x0, y, x0 + fw, y + h);
				for (int sx = x0 - 8 + Math.round(time * 18f) % 8; sx < x0 + fw; sx += 8) g.fill(sx, y, sx + 3, y + h, Ui.alpha(WHITE, 0.35f));
				g.disableScissor();
			}
			case RELAXING -> {
				Ui.shadow(g, x0, y, fw, h, r, acc, 4, 0.45f);
				Ui.rrect(g, x0, y, fw, h, r, Ui.mix(col, WHITE, 0.2f));
			}
			default -> Ui.rrect(g, x0, y, fw, h, r, col);
		}
	}

	/** In-game status overlay background. */
	static void hud(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean alert) {
		Theme t = Ui.theme();
		int acc = Ui.accent();
		switch (t.style) {
			case MINECRAFT -> {
				Ui.rrect(g, x, y, w, h, 4, Ui.alpha(0xFF0B0F1A, 0.72f));
				Ui.rring(g, x, y, w, h, 4, Ui.alpha(acc, 0.5f));
				Ui.rrect(g, x + 5, y + 6, 3, 15, 1, alert ? Ui.WARN : acc);
			}
			case MODERN -> {
				Ui.shadow(g, x, y + 2, w, h, 6, BLACK, 5, 0.5f);
				Ui.rrectV(g, x, y, w, h, 6, Ui.mulAlpha(Ui.BG, 0.9f), Ui.mulAlpha(Ui.BG2, 0.9f));
				Ui.rring(g, x, y, w, h, 6, Ui.mix(Ui.STROKE, acc, 0.4f));
				Ui.shadow(g, x + 5, y + 6, 3, 15, 1, alert ? Ui.WARN : acc, 3, 0.5f);
				Ui.rrect(g, x + 5, y + 6, 3, 15, 1, alert ? Ui.WARN : acc);
			}
			case FUN -> {
				Ui.rrect(g, x + 2, y + 3, w, h, 8, Ui.STROKE);
				Ui.rrect(g, x, y, w, h, 8, Ui.STROKE);
				Ui.rrect(g, x + 2, y + 2, w - 4, h - 4, 6, Ui.mulAlpha(Ui.BG, 0.95f));
				Ui.rrect(g, x + 5, y + 6, 4, 15, 2, alert ? Ui.WARN : acc);
			}
			case RELAXING -> {
				int r = Math.min(12, h / 2);
				Ui.shadow(g, x, y, w, h, r, acc, 6, 0.28f + 0.12f * (float) Math.sin(Fx.time() * 1.1f));
				Ui.rrectV(g, x, y, w, h, r, Ui.mulAlpha(Ui.BG, 0.88f), Ui.mulAlpha(Ui.BG2, 0.88f));
				Ui.rring(g, x, y, w, h, r, Ui.alpha(WHITE, 0.14f));
				Ui.circle(g, x + 6.5f, y + 13, 2.5f, alert ? Ui.WARN : acc);
				Ui.circle(g, x + 6.5f, y + 13, 5, Ui.alpha(alert ? Ui.WARN : acc, 0.2f));
			}
		}
	}

	/** Background of a modal dialog. {@code o} is its open fade. */
	static void dialog(GuiGraphicsExtractor g, int x, int y, int w, int h, float o) {
		Theme t = Ui.theme();
		int acc = Ui.accent(), r = Math.max(6, t.cardR + 1);
		switch (t.style) {
			case MINECRAFT -> {
				Ui.shadow(g, x, y, w, h, 6, acc, 8, 0.6f * o);
				Ui.rrect(g, x, y, w, h, 6, Ui.mulAlpha(0xFF121726, o));
				Ui.rring(g, x, y, w, h, 6, Ui.alpha(acc, 0.55f * o));
			}
			case MODERN -> {
				Ui.shadow(g, x, y + 4, w, h, r, BLACK, 12, 0.7f * o);
				Ui.rrectV(g, x, y, w, h, r, Ui.mulAlpha(Ui.mix(Ui.BG, WHITE, 0.03f), o), Ui.mulAlpha(Ui.BG2, o));
				Ui.rring(g, x, y, w, h, r, Ui.alpha(Ui.mix(Ui.STROKE, acc, 0.4f), o));
			}
			case FUN -> {
				Ui.rrect(g, x + 4, y + 5, w, h, r + 4, Ui.alpha(0xFF0A0418, 0.8f * o));
				Ui.rrect(g, x, y, w, h, r + 4, Ui.mulAlpha(Ui.STROKE, o));
				Ui.rrectV(g, x + 3, y + 3, w - 6, h - 6, r + 1, Ui.mulAlpha(Ui.BG, o), Ui.mulAlpha(Ui.BG2, o));
			}
			case RELAXING -> {
				Ui.shadow(g, x, y + 5, w, h, r + 6, BLACK, 12, 0.5f * o);
				Ui.shadow(g, x, y, w, h, r + 6, acc, 14, (0.22f + 0.1f * (float) Math.sin(Fx.time() * 1.1f)) * o);
				Ui.rrectV(g, x, y, w, h, r + 6, Ui.mulAlpha(Ui.BG, o), Ui.mulAlpha(Ui.BG2, o));
				Ui.rring(g, x, y, w, h, r + 6, Ui.alpha(WHITE, 0.14f * o));
			}
		}
	}
}
