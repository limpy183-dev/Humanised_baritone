package com.humanisedbaritone.gui;

import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/** Theme colours and low level drawing helpers shared by every widget. */
public final class Ui {
	/** The palette of the current interface style; {@link #sync()} refreshes it, every draw path starts with that. */
	public static int BG, BG2, CARD, CARD_HI, FIELD, STROKE, TEXT, DIM, MUTED, GOOD, WARN, BAD, ON_ACCENT, BTN, BTN_TEXT;
	private static Theme theme = Theme.MINECRAFT;

	static {
		use(Theme.MINECRAFT);
	}

	private Ui() {
	}

	private static void use(Theme t) {
		theme = t;
		BG = t.bg;
		BG2 = t.bg2;
		CARD = t.card;
		CARD_HI = t.cardHi;
		FIELD = t.field;
		STROKE = t.stroke;
		TEXT = t.text;
		DIM = t.dim;
		MUTED = t.muted;
		GOOD = t.good;
		WARN = t.warn;
		BAD = t.bad;
		ON_ACCENT = t.onAccent;
		BTN = t.btn;
		BTN_TEXT = t.btnText;
	}

	/** Picks up a change of the interface style setting; every draw path starts with this. */
	public static void sync() {
		Theme t = Theme.of(Settings.UI_STYLE.get());
		if (t != theme) use(t);
	}

	/** Background of the in-game status overlay in the current style. */
	public static void hud(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean alert) {
		Skin.hud(g, x, y, w, h, alert);
	}

	/** A progress bar (0..1) in the current style. */
	public static void progress(GuiGraphicsExtractor g, int x, int y, int w, int h, float v) {
		Skin.progress(g, x, y, w, h, v, false);
	}

	static Theme theme() {
		return theme;
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	public static int accent() {
		return theme.accent(Settings.ACCENT.get());
	}

	public static int alpha(int c, float a) {
		return ((int) (Mth.clamp(a, 0, 1) * 255) << 24) | (c & 0xFFFFFF);
	}

	public static int mulAlpha(int c, float a) {
		return ((int) (((c >>> 24) & 0xFF) * Mth.clamp(a, 0, 1)) << 24) | (c & 0xFFFFFF);
	}

	public static int mix(int a, int b, float t) {
		t = Mth.clamp(t, 0, 1);
		int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, aa = a >>> 24;
		int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255, ba = b >>> 24;
		return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
	}

	// ------------------------------------------------------------------ shapes

	private static int inset(int r, int row) {
		double d = r - row - 0.5;
		return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - d * d)));
	}

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
		g.fill(x, y, x + w, y + h, c);
	}

	/** Filled rectangle with rounded corners. */
	public static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int c) {
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, c);
			return;
		}
		g.fill(x, y + r, x + w, y + h - r, c);
		for (int i = 0, j; i < r; i = j) { // rows of a corner that cut in by the same amount go in one fill
			int in = inset(r, i);
			for (j = i + 1; j < r && inset(r, j) == in; j++) ;
			g.fill(x + in, y + i, x + w - in, y + j, c);
			g.fill(x + in, y + h - j, x + w - in, y + h - i, c);
		}
	}

	/** Rounded rectangle filled with a vertical gradient, drawn row by row (several rows at a time away from the corners). */
	public static void rrectV(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w, h) / 2);
		int step = h > 200 ? 3 : h > 90 ? 2 : 1;
		for (int i = 0; i < h; ) {
			boolean edge = i < r || i >= h - r - step;
			int n = edge ? 1 : Math.min(step, h - r - i);
			int in = i < r ? inset(r, i) : h - 1 - i < r ? inset(r, h - 1 - i) : 0;
			g.fill(x + in, y + i, x + w - in, y + i + n, mix(top, bottom, (i + n / 2f) / h));
			i += n;
		}
	}

	public static void circle(GuiGraphicsExtractor g, float cx, float cy, float r, int c) {
		int d = Math.max(1, Math.round(r * 2));
		rrect(g, Math.round(cx - r), Math.round(cy - r), d, d, d / 2, c);
	}

	/** One pixel wide rounded outline. */
	public static void rring(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int c) {
		if (w <= 2 || h <= 2) return;
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 1) {
			g.fill(x, y, x + w, y + 1, c);
			g.fill(x, y + h - 1, x + w, y + h, c);
			g.fill(x, y + 1, x + 1, y + h - 1, c);
			g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
			return;
		}
		g.fill(x + r, y, x + w - r, y + 1, c);
		g.fill(x + r, y + h - 1, x + w - r, y + h, c);
		g.fill(x, y + r, x + 1, y + h - r, c);
		g.fill(x + w - 1, y + r, x + w, y + h - r, c);
		for (int i = 0; i < r; i++) {
			int o = inset(r, i);
			int n = i == 0 ? r : inset(r - 1, i - 1) + 1;
			if (n <= o) n = o + 1;
			g.fill(x + o, y + i, x + n, y + i + 1, c);
			g.fill(x + w - n, y + i, x + w - o, y + i + 1, c);
			g.fill(x + o, y + h - 1 - i, x + n, y + h - i, c);
			g.fill(x + w - n, y + h - 1 - i, x + w - o, y + h - i, c);
		}
	}

	/** Soft glow behind a rounded rectangle. */
	public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color, int spread, float strength) {
		int step = spread >= 4 ? 2 : 1; // a wide glow is drawn in rings two pixels thick: half the fills, the same look
		for (int k = spread; k >= 1; k -= step) {
			float al = 0;
			for (int n = k; n > k - step && n >= 1; n--) {
				float a = strength * (1f - (float) n / (spread + 1)) * 0.5f;
				al += a * a * 2.2f;
			}
			rrect(g, x - k, y - k, w + 2 * k, h + 2 * k, r + k, alpha(color, al));
		}
	}

	/**
	 * Round soft light: vertical strips whose height and brightness fall off towards the rim. {@code a} is the peak alpha at the centre.
	 * Costs about 2 * rx / step * 4 draw calls (a strip is four gradients).
	 */
	public static void glow(GuiGraphicsExtractor g, float cx, float cy, float rx, float ry, int color, float a) {
		if (rx < 2 || ry < 2 || a <= 0.002f) return;
		int step = Mth.clamp(Math.round(rx / 14f), 3, 10), x0 = Math.round(cx - rx);
		for (int sx = 0; sx < 2 * rx; sx += step) {
			float dx = (sx + step / 2f - rx) / rx, k = 1 - dx * dx;
			if (k <= 0) continue;
			float h = ry * (float) Math.sqrt(k), peak = a * k;
			int x = x0 + sx, y0 = Math.round(cy - h), y1 = Math.round(cy), y2 = Math.round(cy + h);
			int ym = Math.round(cy - h * 0.45f), yn = Math.round(cy + h * 0.45f);
			int edge = alpha(color, 0), mid = alpha(color, peak * 0.3f), core = alpha(color, peak);
			g.fillGradient(x, y0, x + step, ym, edge, mid);
			g.fillGradient(x, ym, x + step, y1, mid, core);
			g.fillGradient(x, y1, x + step, yn, core, mid);
			g.fillGradient(x, yn, x + step, y2, mid, edge);
		}
	}

	// -------------------------------------------------------------------- text

	/** Width of a text in the current style's font. */
	public static int width(String s) {
		return theme.font == null ? font().width(s) : font().width(FormattedCharSequence.forward(s, theme.font));
	}

	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
		if (theme.font == null) g.text(font(), s, x, y, c, false);
		else g.text(font(), FormattedCharSequence.forward(s, theme.font), x, y, c, false);
	}

	public static void textC(GuiGraphicsExtractor g, String s, int cx, int y, int c) {
		text(g, s, cx - width(s) / 2, y, c);
	}

	public static void textR(GuiGraphicsExtractor g, String s, int rx, int y, int c) {
		text(g, s, rx - width(s), y, c);
	}

	/** The longest start of {@code s} that is at most {@code maxW} pixels wide. */
	private static String head(String s, int maxW) {
		return theme.font == null ? font().plainSubstrByWidth(s, maxW) : font().substrByWidth(FormattedText.of(s, theme.font), maxW).getString();
	}

	/** Truncates with an ellipsis so the text fits in {@code maxW} pixels. */
	public static String fit(String s, int maxW) {
		if (width(s) <= maxW) return s;
		return head(s, Math.max(0, maxW - width("..."))) + "...";
	}

	/** Greedy word wrap into at most {@code maxLines} lines; the last line is truncated with an ellipsis if needed. */
	public static java.util.List<String> wrap(String s, int maxW, int maxLines) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String word : s.split(" ")) {
			String trial = cur.length() == 0 ? word : cur + " " + word;
			if (width(trial) <= maxW || cur.length() == 0) {
				cur = new StringBuilder(trial);
			} else {
				lines.add(cur.toString());
				cur = new StringBuilder(word);
				if (lines.size() == maxLines - 1) break;
			}
		}
		if (lines.size() < maxLines) lines.add(cur.toString());
		if (lines.size() == maxLines) {
			// whatever did not fit in the last line is cut off
			String all = String.join(" ", lines.subList(0, maxLines - 1));
			String rest = s.length() > all.length() ? s.substring(all.isEmpty() ? 0 : all.length() + 1) : "";
			lines.set(maxLines - 1, fit(rest, maxW));
		}
		return lines;
	}

	public static void click() {
		if (!Settings.UI_SOUND.on()) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
	}

	/**
	 * A value that eases towards a target, used for hover fades and sliding indicators. In a style with a bounce
	 * it is a spring instead and overshoots a little before it settles.
	 */
	public static final class Anim {
		public float v, vel;

		public Anim(float start) {
			v = start;
		}

		public float to(float target, float dt, float speed) {
			if (!Settings.UI_ANIM.on()) {
				vel = 0;
				return v = target;
			}
			Theme t = theme;
			speed *= t.ease;
			if (t.bounce <= 0 || speed > 40) { // a quick follow (dragging) stays a plain ease
				vel = 0;
				v += (target - v) * (1f - (float) Math.exp(-dt * speed));
			} else {
				float w = speed * 0.9f, zeta = 1f - t.bounce;
				int n = Math.max(1, (int) Math.ceil(dt / 0.008f));
				float h = dt / n;
				for (int i = 0; i < n; i++) {
					vel += (w * w * (target - v) - 2 * zeta * w * vel) * h;
					v += vel * h;
				}
				if (Math.abs(vel) < 0.01f && Math.abs(target - v) < 0.002f) vel = 0;
			}
			if (vel == 0 && Math.abs(target - v) < 0.002f) v = target;
			return v;
		}
	}
}
