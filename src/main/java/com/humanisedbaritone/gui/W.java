package com.humanisedbaritone.gui;

import com.humanisedbaritone.gui.Ui.Anim;
import com.humanisedbaritone.settings.Setting;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Mth;

/** Minimal retained-mode widget toolkit. Coordinates are absolute GUI pixels. */
public abstract class W {
	/** What widgets need from the screen that hosts them. */
	public interface Host {
		void focus(W w);

		boolean isFocused(W w);

		void tip(String s);

		void modal(W w);

		void closeModal();

		/** Switches the panel to another tab. */
		default void openTab(String tab) {
		}
	}

	public static final class Frame {
		public final GuiGraphicsExtractor g;
		public final int mx, my;
		public final float dt;
		public final Host host;

		public Frame(GuiGraphicsExtractor g, int mx, int my, float dt, Host host) {
			this.g = g;
			this.mx = mx;
			this.my = my;
			this.dt = dt;
			this.host = host;
		}

		public Frame withMouse(int mx, int my) {
			return new Frame(g, mx, my, dt, host);
		}
	}

	public int x, y, w, h;
	public boolean enabled = true;
	public String tip;

	public abstract void render(Frame f);

	public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
		return false;
	}

	public boolean mouseReleased(double mx, double my, int btn) {
		return false;
	}

	public boolean mouseDragged(double mx, double my, int btn) {
		return false;
	}

	public boolean mouseScrolled(double mx, double my, double dy) {
		return false;
	}

	public boolean keyPressed(KeyEvent e) {
		return false;
	}

	public boolean charTyped(int cp) {
		return false;
	}

	/** Called when this widget loses keyboard focus. */
	public void unfocus() {
	}

	public final boolean over(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public W at(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		return this;
	}

	// =================================================================== Label

	public static final class Label extends W {
		final Supplier<String> text;
		final int color;
		final boolean right;

		public Label(Supplier<String> text, int color, boolean right) {
			this.text = text;
			this.color = color;
			this.right = right;
		}

		@Override public void render(Frame f) {
			String s = Ui.fit(text.get(), w);
			if (right) Ui.textR(f.g, s, x + w, y, color);
			else Ui.text(f.g, s, x, y, color);
		}
	}

	// =================================================================== Button

	public enum Style { PRIMARY, NORMAL, DANGER, GHOST }

	public static final class Btn extends W {
		public String label;
		public Style style;
		public Runnable action;
		public BooleanSupplier active = () -> false;
		private final Anim hov = new Anim(0), press = new Anim(0);
		private boolean down;

		public Btn(String label, Style style, Runnable action) {
			this.label = label;
			this.style = style;
			this.action = action;
		}

		@Override public void render(Frame f) {
			boolean hot = enabled && over(f.mx, f.my);
			float hv = hov.to(hot ? 1 : 0, f.dt, 18);
			float pv = press.to(down && hot ? 1 : 0, f.dt, 30);
			Skin.button(f.g, x, y, w, h, style, label, active.getAsBoolean(), enabled, hv, pv);
			if (tip != null && hot) f.host.tip(tip);
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (enabled && btn == 0 && over(mx, my)) {
				down = true;
				return true;
			}
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int btn) {
			boolean had = down;
			boolean fire = down && over(mx, my) && btn == 0;
			down = false;
			if (fire) {
				Ui.click();
				action.run();
			}
			return had;
		}
	}

	// =================================================================== Toggle

	public static final class Toggle extends W {
		final BooleanSupplier get;
		final Consumer<Boolean> set;
		/** Hides the ON/OFF caption so only the switch is drawn. */
		public boolean compact;
		private final Anim knob = new Anim(Float.NaN), hov = new Anim(0);

		public Toggle(BooleanSupplier get, Consumer<Boolean> set) {
			this.get = get;
			this.set = set;
		}

		public static Toggle of(Setting.Bool s) {
			return new Toggle(s::on, s::set);
		}

		@Override public void render(Frame f) {
			boolean on = get.getAsBoolean();
			if (Float.isNaN(knob.v)) knob.v = on ? 1 : 0;
			float k = knob.to(on ? 1 : 0, f.dt, 22);
			float hv = hov.to(enabled && over(f.mx, f.my) ? 1 : 0, f.dt, 18);
			Skin.toggle(f.g, x, y, w, h, compact, on, enabled, k, hv);
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (enabled && btn == 0 && over(mx, my)) {
				Ui.click();
				set.accept(!get.getAsBoolean());
				return true;
			}
			return false;
		}
	}

	// =================================================================== Slider

	public static final class Slider extends W {
		final Setting.Num s;
		private boolean drag, focusPending;
		private TextBox edit;
		private final Anim fill = new Anim(Float.NaN), hov = new Anim(0);

		public Slider(Setting.Num s) {
			this.s = s;
		}

		private int trackW() {
			return w - 58;
		}

		private double frac() {
			return (s.d() - s.min) / (s.max - s.min);
		}

		@Override public void render(Frame f) {
			if (edit != null) {
				if (focusPending) {
					f.host.focus(this);
					focusPending = false;
				} else if (!f.host.isFocused(this)) {
					commitEdit();
				}
			}
			if (edit != null) {
				edit.at(x + w - 80, y + 2, 80, h - 4);
				edit.forceFocus = true;
				edit.render(f);
				return;
			}
			boolean hot = enabled && (over(f.mx, f.my) || drag);
			float hv = hov.to(hot ? 1 : 0, f.dt, 16);
			if (Float.isNaN(fill.v)) fill.v = (float) frac();
			float fr = fill.to((float) frac(), f.dt, drag ? 60 : 22);
			Skin.slider(f.g, x, y, w, h, trackW(), fr, hv, enabled, drag, s.display());
		}

		private void setFromMouse(double mx) {
			double t = Mth.clamp((mx - x) / (double) (trackW() - 1), 0, 1);
			s.set(s.min + t * (s.max - s.min));
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (edit != null) return edit.mouseClicked(mx, my, btn, dbl);
			if (!enabled || !over(mx, my)) return false;
			if (btn == 1) {
				s.reset();
				Ui.click();
				return true;
			}
			if (btn != 0) return false;
			if (mx > x + trackW() + 2 || dbl) {
				edit = new TextBox("", 12, null);
				edit.setText(s.integer ? Long.toString(Math.round(s.d())) : Double.toString(s.d()));
				edit.selectAll();
				focusPending = true;
				return true;
			}
			drag = true;
			setFromMouse(mx);
			return true;
		}

		private void commitEdit() {
			if (edit == null) return;
			String t = edit.text();
			edit = null;
			s.parse(t);
		}

		@Override public boolean mouseDragged(double mx, double my, int btn) {
			if (drag) {
				setFromMouse(mx);
				return true;
			}
			return edit != null && edit.mouseDragged(mx, my, btn);
		}

		@Override public boolean mouseReleased(double mx, double my, int btn) {
			boolean was = drag;
			drag = false;
			if (edit != null) edit.mouseReleased(mx, my, btn);
			return was;
		}

		@Override public boolean mouseScrolled(double mx, double my, double dy) {
			if (!enabled || !over(mx, my) || edit != null) return false;
			double st = s.step > 0 ? s.step : (s.max - s.min) / 50;
			s.set(s.d() + Math.signum(dy) * st * (Minecraft.getInstance().hasShiftDown() ? 5 : 1));
			return true;
		}

		@Override public boolean keyPressed(KeyEvent e) {
			if (edit == null) return false;
			if (e.isEscape()) {
				edit = null;
				return true;
			}
			if (e.isConfirmation()) {
				commitEdit();
				return true;
			}
			return edit.keyPressed(e);
		}

		@Override public boolean charTyped(int cp) {
			return edit != null && edit.charTyped(cp);
		}

		@Override public void unfocus() {
			commitEdit();
		}
	}

	// =================================================================== Cycle

	public static final class Cycle extends W {
		final Setting.Choice<?> s;
		private final Anim hov = new Anim(0);

		public Cycle(Setting.Choice<?> s) {
			this.s = s;
		}

		@Override public void render(Frame f) {
			boolean hot = enabled && over(f.mx, f.my);
			float hv = hov.to(hot ? 1 : 0, f.dt, 18);
			int bw = Math.min(w, 116), bx = x + w - bw;
			String label = s.display();
			int cx = bx + bw / 2;
			boolean dot = s.get() instanceof Settings.Accent;
			if (dot) cx += 4;
			Skin.cycle(f.g, bx, y, bw, h, label, hv, enabled, cx);
			if (dot) Ui.circle(f.g, cx - Ui.width(label) / 2f - 4, y + h / 2f, 3, Ui.accent());
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (!enabled || !over(mx, my)) return false;
			if (btn == 0) s.cycle(1);
			else if (btn == 1) s.cycle(-1);
			else return false;
			Ui.click();
			return true;
		}

		@Override public boolean mouseScrolled(double mx, double my, double dy) {
			if (!enabled || !over(mx, my)) return false;
			s.cycle(dy > 0 ? -1 : 1);
			return true;
		}
	}

	// ================================================================= TextBox

	public static final class TextBox extends W {
		private final StringBuilder sb = new StringBuilder();
		private int caret, anchor;
		public String hint;
		public final int max;
		public Consumer<String> onChange, onEnter;
		/** Called with -1 (up) or +1 (down); return true to consume the key. */
		public java.util.function.IntPredicate onNav;
		public boolean focusNow, forceFocus;
		private boolean dragging;
		private final Anim focus = new Anim(0);
		private long blinkBase = System.currentTimeMillis();
		private int scrollPx;

		public TextBox(String hint, int max, Consumer<String> onChange) {
			this.hint = hint;
			this.max = max;
			this.onChange = onChange;
		}

		public String text() {
			return sb.toString();
		}

		public void setText(String s) {
			sb.setLength(0);
			sb.append(s);
			caret = anchor = sb.length();
		}

		public void selectAll() {
			anchor = 0;
			caret = sb.length();
		}

		@Override public void render(Frame f) {
			if (focusNow) {
				f.host.focus(this);
				focusNow = false;
			}
			boolean foc = forceFocus || f.host.isFocused(this);
			float fv = focus.to(foc ? 1 : 0, f.dt, 20);
			int acc = Ui.accent(), pd = Skin.fieldPad();
			Skin.field(f.g, x, y, w, h, fv);
			int inner = w - 2 * pd, ty = y + (h - 8) / 2;
			String t = sb.toString();
			int cx = Ui.width(t.substring(0, caret));
			if (cx - scrollPx > inner) scrollPx = cx - inner;
			if (cx - scrollPx < 0) scrollPx = cx;
			if (Ui.width(t) <= inner) scrollPx = 0;
			f.g.enableScissor(x + pd - 1, y, x + w - pd + 1, y + h);
			if (t.isEmpty() && !foc) {
				Ui.text(f.g, Ui.fit(hint, inner), x + pd, ty, Ui.MUTED);
			} else {
				if (foc && anchor != caret) {
					int a = Ui.width(t.substring(0, Math.min(anchor, caret))), b = Ui.width(t.substring(0, Math.max(anchor, caret)));
					Ui.rect(f.g, x + pd + a - scrollPx, y + 2, b - a, h - 4, Ui.alpha(acc, 0.35f));
				}
				Ui.text(f.g, t, x + pd - scrollPx, ty, Ui.TEXT);
				if (foc && ((System.currentTimeMillis() - blinkBase) / 500) % 2 == 0) {
					Ui.rect(f.g, x + pd + cx - scrollPx, y + 2, 1, h - 4, acc);
				}
			}
			f.g.disableScissor();
			forceFocus = false;
		}

		private int indexAt(double mx) {
			double rel = mx - (x + Skin.fieldPad()) + scrollPx;
			String t = sb.toString();
			for (int i = 0; i <= t.length(); i++) {
				if (Ui.width(t.substring(0, i)) >= rel) return i;
			}
			return t.length();
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (!over(mx, my)) return false;
			blinkBase = System.currentTimeMillis();
			caret = anchor = indexAt(mx);
			if (dbl) selectAll();
			focusNow = true;
			dragging = true;
			return true;
		}

		@Override public boolean mouseDragged(double mx, double my, int btn) {
			if (dragging && btn == 0) {
				caret = indexAt(mx);
				return true;
			}
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int btn) {
			boolean was = dragging;
			dragging = false;
			return was;
		}

		private void changed() {
			blinkBase = System.currentTimeMillis();
			if (onChange != null) onChange.accept(sb.toString());
		}

		private void deleteSelection() {
			int a = Math.min(anchor, caret), b = Math.max(anchor, caret);
			sb.delete(a, b);
			caret = anchor = a;
		}

		private void insert(String s) {
			if (anchor != caret) deleteSelection();
			for (int i = 0; i < s.length() && sb.length() < max; i++) {
				char c = s.charAt(i);
				if (c < 32 || c == 127) continue;
				sb.insert(caret++, c);
			}
			anchor = caret;
			changed();
		}

		@Override public boolean charTyped(int cp) {
			insert(Character.toString(cp));
			return true;
		}

		@Override public boolean keyPressed(KeyEvent e) {
			var mc = Minecraft.getInstance();
			boolean shift = e.hasShiftDown();
			if (e.isSelectAll()) {
				selectAll();
				return true;
			}
			if (e.isCopy()) {
				if (anchor != caret) mc.keyboardHandler.setClipboard(sb.substring(Math.min(anchor, caret), Math.max(anchor, caret)));
				return true;
			}
			if (e.isCut()) {
				if (anchor != caret) {
					mc.keyboardHandler.setClipboard(sb.substring(Math.min(anchor, caret), Math.max(anchor, caret)));
					deleteSelection();
					changed();
				}
				return true;
			}
			if (e.isPaste()) {
				insert(mc.keyboardHandler.getClipboard());
				return true;
			}
			switch (e.key()) {
				case 259 -> { // backspace
					if (anchor != caret) deleteSelection();
					else if (caret > 0) sb.deleteCharAt(--caret);
					anchor = caret;
					changed();
				}
				case 261 -> { // delete
					if (anchor != caret) deleteSelection();
					else if (caret < sb.length()) sb.deleteCharAt(caret);
					anchor = caret;
					changed();
				}
				case 263 -> {
					caret = Math.max(0, caret - 1);
					if (!shift) anchor = caret;
				}
				case 262 -> {
					caret = Math.min(sb.length(), caret + 1);
					if (!shift) anchor = caret;
				}
				case 268 -> {
					caret = 0;
					if (!shift) anchor = 0;
				}
				case 269 -> {
					caret = sb.length();
					if (!shift) anchor = caret;
				}
				case 257, 335 -> {
					if (onEnter != null) onEnter.accept(sb.toString());
				}
				case 265, 264 -> {
					if (onNav == null || !onNav.test(e.key() == 265 ? -1 : 1)) return false;
				}
				default -> {
					return false;
				}
			}
			blinkBase = System.currentTimeMillis();
			return true;
		}
	}

	// =============================================================== ScrollArea

	public static class ScrollArea extends W {
		public final List<W> rows = new ArrayList<>();
		public int gap = 3;
		private float scroll, target;
		private int contentH;
		private final Anim bar = new Anim(0);
		private boolean dragBar;
		/** When the rows were last replaced with {@code slide}: they glide in one after the other. 0 = no entrance. */
		private long born;

		public void clear() {
			clear(false);
		}

		/** Removes all rows; with {@code slide} the next set of rows glides in (styles that animate that). */
		public void clear(boolean slide) {
			rows.clear();
			scroll = target = 0;
			born = slide ? System.nanoTime() : 0;
		}

		/** 0..1 progress of the entrance of row {@code i}, eased (a spring-like overshoot in bouncy styles). */
		private float entrance(int i) {
			Theme t = Ui.theme();
			if (born == 0 || !t.stagger || !Settings.UI_ANIM.on()) return 1;
			float p = Mth.clamp(((System.nanoTime() - born) / 1e9f - i * 0.035f) / (t.bounce > 0 ? 0.5f : 0.4f), 0, 1), q = 1 - p;
			if (t.bounce > 0) return 1 + 2.2f * (float) Math.pow(p - 1, 3) + 1.2f * (float) Math.pow(p - 1, 2); // easeOutBack
			return 1 - q * q * q;
		}

		public void add(W row, int h) {
			row.h = h;
			rows.add(row);
		}

		public void scrollToBottom() {
			target = Float.MAX_VALUE;
		}

		private void layout() {
			int cy = 0;
			for (W r : rows) {
				r.x = x;
				r.w = w - 7;
				r.y = y + cy - Math.round(scroll);
				cy += r.h + gap;
			}
			contentH = Math.max(0, cy - gap);
		}

		@Override public void render(Frame f) {
			layout();
			int max = Math.max(0, contentH - h);
			target = Mth.clamp(target, 0, max);
			scroll += (target - scroll) * (1f - (float) Math.exp(-f.dt * 22f));
			if (Math.abs(target - scroll) < 0.3f) scroll = target;
			scroll = Mth.clamp(scroll, 0, max);
			layout();
			boolean inside = over(f.mx, f.my);
			Frame inner = inside ? f : f.withMouse(-9999, -9999);
			f.g.enableScissor(x, y, x + w, y + h);
			for (int i = 0; i < rows.size(); i++) {
				W r = rows.get(i);
				if (r.y + r.h < y || r.y > y + h) continue;
				float e = entrance(i);
				if (e >= 1) {
					r.render(inner);
					continue;
				}
				var pose = f.g.pose(); // not there yet: still gliding up from below
				pose.pushMatrix();
				pose.translate(0, (1 - e) * 14);
				r.render(inner);
				pose.popMatrix();
			}
			f.g.disableScissor();
			if (max > 0) {
				float bv = bar.to(inside || dragBar ? 1 : 0.35f, f.dt, 12);
				int bh = Math.max(14, h * h / Math.max(h, contentH));
				int by = y + (int) ((h - bh) * (scroll / max));
				Skin.scrollbar(f.g, x, y, w, h, by, bh, bv);
			}
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (!over(mx, my)) return false;
			if (mx >= x + w - 7 && contentH > h) {
				dragBar = true;
				dragTo(my);
				return true;
			}
			for (int i = rows.size() - 1; i >= 0; i--) {
				if (rows.get(i).mouseClicked(mx, my, btn, dbl)) return true;
			}
			return true;
		}

		private void dragTo(double my) {
			int max = Math.max(0, contentH - h);
			double t = Mth.clamp((my - y) / h, 0, 1);
			target = scroll = (float) (t * max);
		}

		@Override public boolean mouseDragged(double mx, double my, int btn) {
			if (dragBar) {
				dragTo(my);
				return true;
			}
			for (W r : rows) if (r.mouseDragged(mx, my, btn)) return true;
			return false;
		}

		@Override public boolean mouseReleased(double mx, double my, int btn) {
			boolean used = dragBar;
			dragBar = false;
			for (W r : new ArrayList<>(rows)) used |= r.mouseReleased(mx, my, btn); // a release can rebuild the page (rows.clear())
			return used;
		}

		@Override public boolean mouseScrolled(double mx, double my, double dy) {
			if (!over(mx, my)) return false;
			for (W r : rows) if (r.over(mx, my) && r.mouseScrolled(mx, my, dy)) return true;
			target -= (float) dy * 26f;
			return true;
		}

		@Override public boolean keyPressed(KeyEvent e) {
			for (W r : rows) if (r.keyPressed(e)) return true;
			return false;
		}

		@Override public boolean charTyped(int cp) {
			for (W r : rows) if (r.charTyped(cp)) return true;
			return false;
		}

		@Override public void unfocus() {
			for (W r : rows) r.unfocus();
		}
	}
	// ================================================================ Segmented

	/** Every option of a choice side by side, the highlight slides to the picked one. Picks the interface style. */
	public static final class Seg extends W {
		final Setting.Choice<?> s;
		private final Anim sel = new Anim(Float.NaN);
		private final Anim[] hov;

		public Seg(Setting.Choice<?> s) {
			this.s = s;
			hov = new Anim[s.options.length];
			for (int i = 0; i < hov.length; i++) hov[i] = new Anim(0);
		}

		private int at(double mx) {
			return Mth.clamp((int) ((mx - x - 3) / ((w - 6) / (double) s.options.length)), 0, s.options.length - 1);
		}

		@Override public void render(Frame f) {
			int n = s.options.length, cur = s.get().ordinal(), seg = (w - 6) / n, by = y + 2, bh = h - 4;
			boolean fun = Ui.theme().style == Settings.UiStyle.FUN;
			if (Float.isNaN(sel.v)) sel.v = cur;
			float sv = sel.to(cur, f.dt, 14);
			Skin.field(f.g, x, by, w, bh, 0);
			Skin.button(f.g, x + 3 + Math.round(sv * seg), by + 3, seg, bh - 6, Style.NORMAL, "", true, enabled, 0, 0);
			for (int i = 0; i < n; i++) {
				float hv = hov[i].to(enabled && over(f.mx, f.my) && at(f.mx) == i ? 1 : 0, f.dt, 16);
				String label = Setting.Choice.nice(s.options[i]);
				int cx = x + 3 + i * seg + seg / 2, lw = Ui.width(label), ty = by + (bh - 8) / 2;
				int txt = i == cur ? (fun ? Ui.ON_ACCENT : Ui.TEXT) : Ui.mix(Ui.DIM, Ui.TEXT, hv);
				if (s.options[i] instanceof Settings.UiStyle us && lw + 17 <= seg - 2) { // a swatch of the style's colours
					Theme th = Theme.of(us);
					int sx = cx - (lw + 17) / 2;
					Ui.circle(f.g, sx + 1.5f, y + h / 2f, 1.5f, Ui.alpha(th.bg, 1));
					Ui.circle(f.g, sx + 6.5f, y + h / 2f, 1.5f, th.accent(Settings.ACCENT.get()));
					Ui.circle(f.g, sx + 11.5f, y + h / 2f, 1.5f, th.text);
					Ui.text(f.g, label, sx + 17, ty, txt);
				} else {
					Ui.textC(f.g, label, cx, ty, txt);
				}
			}
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (!enabled || !over(mx, my)) return false;
			if (btn == 1) s.reset();
			else if (btn == 0) s.cycle(at(mx) - s.get().ordinal());
			else return false;
			Ui.click();
			return true;
		}

		@Override public boolean mouseScrolled(double mx, double my, double dy) {
			if (!enabled || !over(mx, my)) return false;
			s.cycle(dy > 0 ? -1 : 1);
			return true;
		}
	}
}
