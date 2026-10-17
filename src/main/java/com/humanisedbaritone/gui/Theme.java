package com.humanisedbaritone.gui;

import com.humanisedbaritone.HumanisedBaritone;
import com.humanisedbaritone.settings.Settings.Accent;
import com.humanisedbaritone.settings.Settings.UiStyle;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** Everything that makes one interface style look different: palette, shapes, spacing, font, motion and layout. */
final class Theme {
	enum Layout { SIDEBAR, TOPBAR, RAIL }

	UiStyle style;
	Layout layout = Layout.SIDEBAR;
	/** Panel fill (top and bottom), cards, text fields, outline, three text levels, status colours, text on an accent fill, plain button fill and its text. */
	int bg, bg2, card, cardHi, field, stroke, text, dim, muted, good, warn, bad, onAccent, btn, btnText;
	/** Corner radius of panel, cards and controls; height of a settings row and of a section header; gap between rows; padding inside the panel. */
	int panelR = 8, cardR = 5, ctlR = 3, rowH = 27, headH = 15, gap = 3, pad = 8;
	/** Width of the tab column, height of the header, of the footer and of the tab strip. */
	int sbW = 92, hdrH = 30, ftrH = 25, tabBar = 0;
	/** Animation speed factor, spring overshoot (0 = a plain ease), how far the panel drops in and how much smaller it starts. */
	float ease = 1, bounce = 0, openDrop = 14, openShrink = 0;
	/** Rows slide in one after the other. */
	boolean stagger;
	/** The style's own font; null = the game's. */
	Style font;

	static final Theme MINECRAFT = new Theme(), MODERN = new Theme(), FUN = new Theme(), RELAXING = new Theme();

	static {
		MINECRAFT.style = UiStyle.MINECRAFT;
		MINECRAFT.bg = 0xF2101421;
		MINECRAFT.bg2 = 0xF2101421;
		MINECRAFT.card = 0xFF161B2C;
		MINECRAFT.cardHi = 0xFF1D2438;
		MINECRAFT.field = 0xFF0E1220;
		MINECRAFT.stroke = 0xFF283152;
		MINECRAFT.text = 0xFFE9EDF9;
		MINECRAFT.dim = 0xFF96A3C8;
		MINECRAFT.muted = 0xFF5D6992;
		MINECRAFT.good = 0xFF4ADE9E;
		MINECRAFT.warn = 0xFFFFB454;
		MINECRAFT.bad = 0xFFFF6B80;
		MINECRAFT.onAccent = 0xFF06121A;
		MINECRAFT.btn = MINECRAFT.cardHi;
		MINECRAFT.btnText = MINECRAFT.text;

		MODERN.style = UiStyle.MODERN;
		MODERN.bg = 0xF50C0E15;
		MODERN.bg2 = 0xF5090B10;
		MODERN.card = 0xFF13161F;
		MODERN.cardHi = 0xFF1A1E2A;
		MODERN.field = 0xFF0A0C12;
		MODERN.stroke = 0xFF262B3B;
		MODERN.text = 0xFFF1F3F9;
		MODERN.dim = 0xFF9EA6BD;
		MODERN.muted = 0xFF5F677E;
		MODERN.good = 0xFF34D399;
		MODERN.warn = 0xFFFBBF24;
		MODERN.bad = 0xFFF87171;
		MODERN.onAccent = 0xFF05090F;
		MODERN.btn = MODERN.cardHi;
		MODERN.btnText = MODERN.text;
		MODERN.sbW = 106;
		MODERN.hdrH = 34;
		MODERN.pad = 10;
		MODERN.panelR = 10;
		MODERN.cardR = 7;
		MODERN.ctlR = 5;
		MODERN.rowH = 29;
		MODERN.headH = 18;
		MODERN.gap = 3;
		MODERN.ease = 1.15f;
		MODERN.openDrop = 10;
		MODERN.openShrink = 0.04f;
		MODERN.stagger = true;
		MODERN.font = font("modern");

		FUN.style = UiStyle.FUN;
		FUN.layout = Layout.TOPBAR;
		FUN.bg = 0xFF2C1B60;
		FUN.bg2 = 0xFF1F1146;
		FUN.card = 0xFF3B2584;
		FUN.cardHi = 0xFF4C30A0;
		FUN.field = 0xFF1A0E3E;
		FUN.stroke = 0xFF12082B;
		FUN.text = 0xFFFFFFFF;
		FUN.dim = 0xFFDACDFF;
		FUN.muted = 0xFFA796E2;
		FUN.good = 0xFF5CF2A8;
		FUN.warn = 0xFFFFD23F;
		FUN.bad = 0xFFFF6B9D;
		FUN.onAccent = 0xFF1A0E3E;
		FUN.btn = 0xFFF3ECFF;
		FUN.btnText = 0xFF2C1B60;
		FUN.sbW = 0;
		FUN.hdrH = 38;
		FUN.tabBar = 26;
		FUN.pad = 10;
		FUN.panelR = 14;
		FUN.cardR = 10;
		FUN.ctlR = 7;
		FUN.rowH = 31;
		FUN.headH = 21;
		FUN.gap = 5;
		FUN.bounce = 0.5f;
		FUN.openDrop = 0;
		FUN.openShrink = 0.3f;
		FUN.stagger = true;
		FUN.font = font("fun");

		RELAXING.style = UiStyle.RELAXING;
		RELAXING.layout = Layout.RAIL;
		RELAXING.bg = 0xEF1B2540;
		RELAXING.bg2 = 0xEF2D2B57;
		RELAXING.card = 0x1CFFFFFF;
		RELAXING.cardHi = 0x2DFFFFFF;
		RELAXING.field = 0x45101828;
		RELAXING.stroke = 0x26FFFFFF;
		RELAXING.text = 0xFFEFECF9;
		RELAXING.dim = 0xFFBBBADC;
		RELAXING.muted = 0xFF8180AC;
		RELAXING.good = 0xFF93E6C4;
		RELAXING.warn = 0xFFF5CD92;
		RELAXING.bad = 0xFFF4A0B4;
		RELAXING.onAccent = 0xFF141C34;
		RELAXING.btn = 0x1CFFFFFF;
		RELAXING.btnText = RELAXING.text;
		RELAXING.sbW = 48;
		RELAXING.hdrH = 40;
		RELAXING.ftrH = 26;
		RELAXING.pad = 12;
		RELAXING.panelR = 18;
		RELAXING.cardR = 13;
		RELAXING.ctlR = 9;
		RELAXING.rowH = 33;
		RELAXING.headH = 23;
		RELAXING.gap = 6;
		RELAXING.ease = 0.55f;
		RELAXING.openDrop = 20;
		RELAXING.openShrink = 0.02f;
		RELAXING.stagger = true;
		RELAXING.font = font("relaxing");
	}

	private static Style font(String name) {
		return Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(HumanisedBaritone.ID, name)));
	}

	static Theme of(UiStyle s) {
		return switch (s) {
			case MINECRAFT -> MINECRAFT;
			case MODERN -> MODERN;
			case FUN -> FUN;
			case RELAXING -> RELAXING;
		};
	}

	/** The accent colour as this style shows it: punchier for Fun, softer for Relaxing. */
	int accent(Accent a) {
		int base = switch (a) {
			case CYAN -> 0xFF2DD4E8;
			case VIOLET -> 0xFF9B7BFF;
			case EMERALD -> 0xFF34D399;
			case AMBER -> 0xFFFBBF24;
			case ROSE -> 0xFFFB6F92;
			case AZURE -> 0xFF5B9DFF;
		};
		return switch (style) {
			case FUN -> Ui.mix(base, 0xFFFFFFFF, 0.12f);
			case RELAXING -> Ui.mix(base, 0xFFC8CCE6, 0.30f);
			default -> base;
		};
	}
}
