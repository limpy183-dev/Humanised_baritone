package com.humanisedbaritone.gui;

import com.humanisedbaritone.gui.Ui.Anim;
import com.humanisedbaritone.settings.Setting;
import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.input.KeyEvent;

/** One line of the settings list: name and description on the left, the matching control on the right. */
public final class SettingRow extends W {
	private final Setting<?> s;
	private final W control;
	private final int controlW;
	private final Anim hov = new Anim(0);
	private final String search;

	public SettingRow(Setting<?> s, String search) {
		this.s = s;
		this.search = search;
		if (s instanceof Setting.Bool b) {
			control = Toggle.of(b);
			controlW = 62;
		} else if (s instanceof Setting.Num n) {
			control = new Slider(n);
			controlW = 176;
		} else if (s == Settings.UI_STYLE) {
			control = new Seg((Setting.Choice<?>) s);
			controlW = 226;
		} else if (s instanceof Setting.Choice<?> c) {
			control = new Cycle(c);
			controlW = 116;
		} else if (s instanceof Setting.Text t) {
			TextBox tb = new TextBox("", 48, null);
			tb.setText(t.get());
			tb.onChange = t::set;
			control = tb;
			controlW = 120;
		} else {
			Setting.IdSet ids = (Setting.IdSet) s;
			control = new Btn("", Style.NORMAL, () -> Picker.open(host, ids));
			controlW = 116;
		}
	}

	private Host host;

	@Override public void render(Frame f) {
		host = f.host;
		Theme t = Ui.theme();
		boolean on = over(f.mx, f.my), fun = t.style == Settings.UiStyle.FUN, soft = t.style == Settings.UiStyle.RELAXING;
		float hv = hov.to(on ? 1 : 0, f.dt, 14);
		boolean enabled = s.enabled();
		int acc = Ui.accent();
		if (hv > 0.01f) Ui.rrect(f.g, x, y, w, h, fun ? 8 : t.ctlR + 1, Ui.alpha(0xFFFFFFFF, (fun ? 0.08f : soft ? 0.05f : 0.045f) * hv));
		int cwid = s == Settings.UI_STYLE ? Math.min(controlW, Math.max(150, w - 130)) : controlW; // the style selector shrinks in a narrow panel
		control.enabled = enabled;
		control.at(x + w - cwid - 6, y, cwid, h);
		int nameColor = enabled ? Ui.TEXT : Ui.MUTED;
		int indent = s.parent != null ? 9 : 0;
		int lead = fun || soft || t.style == Settings.UiStyle.MODERN ? 12 : 11; // the distance from the name to its description line
		int ty = y + (h - 8 - lead) / 2, tx = x + 8 + indent + (fun ? Math.round(hv * 2) : 0);
		if (indent > 0) Ui.rect(f.g, x + 9, y + 2, 1, h - 4, Ui.alpha(acc, enabled ? 0.35f : 0.15f));
		if (!s.isDefault()) { // changed from the default
			if (fun) {
				Ui.circle(f.g, x + 4, y + h / 2f, 2.5f, acc);
			} else if (soft) {
				Ui.circle(f.g, x + 4, y + h / 2f, 5, Ui.alpha(acc, 0.18f));
				Ui.circle(f.g, x + 4, y + h / 2f, 2, acc);
			} else {
				Ui.rrect(f.g, x + 2, ty + 2, 2, 8, 1, acc);
			}
		}
		boolean match = search != null && !search.isEmpty() && s.name.toLowerCase().contains(search);
		if (match) nameColor = acc;
		int leftW = w - cwid - 20 - indent;
		Ui.text(f.g, Ui.fit(s.name, leftW), tx, ty, nameColor);
		Ui.text(f.g, Ui.fit(s.desc, leftW), tx, ty + lead, enabled ? Ui.MUTED : Ui.alpha(Ui.MUTED, 0.6f));
		if (s instanceof Setting.IdSet ids) ((Btn) control).label = "Edit list (" + ids.get().size() + ")";
		control.render(f);
		if (on) f.host.tip(s.desc + (s.isDefault() ? "" : "   [right-click a slider to reset]"));
	}

	@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
		return control.mouseClicked(mx, my, btn, dbl);
	}

	@Override public boolean mouseReleased(double mx, double my, int btn) {
		return control.mouseReleased(mx, my, btn);
	}

	@Override public boolean mouseDragged(double mx, double my, int btn) {
		return control.mouseDragged(mx, my, btn);
	}

	@Override public boolean mouseScrolled(double mx, double my, double dy) {
		return control.mouseScrolled(mx, my, dy);
	}

	@Override public boolean keyPressed(KeyEvent e) {
		return control.keyPressed(e);
	}

	@Override public boolean charTyped(int cp) {
		return control.charTyped(cp);
	}

	@Override public void unfocus() {
		control.unfocus();
	}

	/** Section title inside the settings list. */
	public static final class Header extends W {
		private final String title;

		public Header(String title) {
			this.title = title;
		}

		@Override public void render(Frame f) {
			Theme t = Ui.theme();
			int acc = Ui.accent(), ty = y + (h - 8) / 2 + 1;
			String up = title.toUpperCase();
			switch (t.style) {
				case MINECRAFT -> {
					Ui.text(f.g, up, x + 4, y + 5, acc);
					int tw = Ui.width(up);
					Ui.rect(f.g, x + 10 + tw, y + 9, Math.max(0, w - tw - 14), 1, Ui.alpha(acc, 0.25f));
				}
				case MODERN -> {
					Ui.rrect(f.g, x + 4, ty, 2, 8, 1, acc);
					Ui.text(f.g, up, x + 11, ty, Ui.mix(Ui.DIM, acc, 0.4f));
					int tw = Ui.width(up);
					Ui.rect(f.g, x + 18 + tw, ty + 4, Math.max(0, w - tw - 22), 1, Ui.alpha(acc, 0.2f));
				}
				case FUN -> {
					int tw = Ui.width(up) + 14, ph = 14, py = y + (h - ph) / 2;
					Ui.rrect(f.g, x + 4, py + 2, tw, ph, 7, Ui.STROKE);
					Ui.rrect(f.g, x + 2, py, tw, ph, 7, Ui.STROKE);
					Ui.rrect(f.g, x + 4, py + 2, tw - 4, ph - 4, 5, acc);
					Ui.text(f.g, up, x + 11, py + (ph - 8) / 2 + 1, Ui.ON_ACCENT);
				}
				case RELAXING -> {
					int tw = Ui.width(title), cx = x + w / 2, c = Ui.alpha(0xFFFFFFFF, 0.12f);
					Ui.text(f.g, title, cx - tw / 2, ty, Ui.mix(acc, 0xFFFFFFFF, 0.2f));
					int len = Math.max(0, w / 2 - tw / 2 - 18);
					Ui.rect(f.g, x + 4, ty + 4, len, 1, c);
					Ui.rect(f.g, x + w - 4 - len, ty + 4, len, 1, c);
				}
			}
		}
	}
}
