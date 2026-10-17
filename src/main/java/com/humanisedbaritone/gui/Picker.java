package com.humanisedbaritone.gui;

import com.humanisedbaritone.gui.Ui.Anim;
import com.humanisedbaritone.settings.Setting;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Modal dialog with a search box and a scrolling list; either multi-select (registry sets) or pick-one. */
public final class Picker extends W {
	public record Entry(String key, String label, ItemStack icon) {
	}

	private final String title, doneLabel;
	private final List<Entry> all;
	private final boolean multi;
	private final Set<String> selected;
	private final Consumer<Set<String>> done;
	private final Consumer<String> pick;
	private final TextBox search = new TextBox("Search...", 40, null);
	private final ScrollArea list = new ScrollArea();
	private final Btn ok, clear, onlySel;
	private boolean onlySelected;
	private String lastQuery = "\0";
	private final Anim open = new Anim(0);
	private Host host;
	private int dx, dy, dw, dh;

	private Picker(String title, List<Entry> all, boolean multi, Set<String> initial, Consumer<Set<String>> done, Consumer<String> pick, String doneLabel) {
		this.title = title;
		this.all = all;
		this.multi = multi;
		this.selected = new LinkedHashSet<>(initial);
		this.done = done;
		this.pick = pick;
		this.doneLabel = doneLabel;
		list.gap = 1;
		ok = new Btn(multi ? doneLabel : "Cancel", multi ? Style.PRIMARY : Style.NORMAL, this::finish);
		clear = new Btn("Clear", Style.NORMAL, () -> {
			selected.clear();
			lastQuery = "\0";
		});
		onlySel = new Btn("Selected only", Style.GHOST, () -> {
			onlySelected = !onlySelected;
			lastQuery = "\0";
		});
		onlySel.active = () -> onlySelected;
		clear.enabled = multi;
		onlySel.enabled = multi;
	}

	private void finish() {
		if (multi && done != null) done.accept(selected);
		host.closeModal();
	}

	// ------------------------------------------------------------- factories

	public static void open(Host host, Setting.IdSet s) {
		host.modal(new Picker(s.name, collect(s.registry, s.get()), true, s.get(), s::set, null, "Done"));
	}

	/** Pick one entry from a prepared list. */
	public static void single(Host host, String title, List<Entry> entries, Consumer<String> onPick) {
		host.modal(new Picker(title, entries, false, Set.of(), null, onPick, "Cancel"));
	}

	/** Multi-select over a registry with a custom confirm button, e.g. "Start mining". */
	public static void multi(Host host, String title, Setting.Registry reg, Set<String> initial, Consumer<Set<String>> done, String doneLabel) {
		host.modal(new Picker(title, collect(reg, initial), true, initial, done, null, doneLabel));
	}

	private static final java.util.Set<String> MISC_MOBS = java.util.Set.of("iron_golem", "snow_golem", "copper_golem", "armor_stand");

	private static List<Entry> collect(Setting.Registry reg, Set<String> selected) {
		List<Entry> entries = new ArrayList<>();
		Set<String> keys = new TreeSet<>();
		switch (reg) {
			case BLOCK -> BuiltInRegistries.BLOCK.keySet().forEach(k -> keys.add(k.toString()));
			case ITEM -> BuiltInRegistries.ITEM.keySet().forEach(k -> keys.add(k.toString()));
			case ENTITY -> BuiltInRegistries.ENTITY_TYPE.keySet().forEach(k -> {
				var type = BuiltInRegistries.ENTITY_TYPE.getValue(k);
				// boats, arrows and other non-creatures are of no use in a target list
				if (type.getCategory() != net.minecraft.world.entity.MobCategory.MISC || MISC_MOBS.contains(k.getPath())) keys.add(k.toString());
			});
		}
		for (String k : keys) {
			if (k.equals("minecraft:air") || k.endsWith(":cave_air") || k.endsWith(":void_air")) continue;
			ItemStack icon = ItemStack.EMPTY;
			Identifier id = Identifier.parse(k);
			if (reg == Setting.Registry.BLOCK) {
				Block b = BuiltInRegistries.BLOCK.getValue(id);
				Item it = b == null ? null : b.asItem();
				if (it != null && it != net.minecraft.world.item.Items.AIR) icon = new ItemStack(it);
			} else if (reg == Setting.Registry.ITEM) {
				Item it = BuiltInRegistries.ITEM.getValue(id);
				if (it != null) icon = new ItemStack(it);
			} else {
				var egg = net.minecraft.world.item.SpawnEggItem.byId(BuiltInRegistries.ENTITY_TYPE.getValue(id));
				if (egg.isPresent()) icon = new ItemStack(egg.get());
			}
			entries.add(new Entry(k, id.getPath().replace('_', ' '), icon));
		}
		// what is already chosen comes first, so the current list is the first thing you see
		entries.sort((x, y) -> Boolean.compare(!selected.contains(x.key()), !selected.contains(y.key())));
		return entries;
	}

	// ---------------------------------------------------------------- render

	private void refill() {
		String q = search.text().toLowerCase(Locale.ROOT).trim();
		lastQuery = q + (onlySelected ? "\1" : "");
		list.clear();
		int shown = 0;
		for (Entry e : all) {
			if (onlySelected && !selected.contains(e.key())) continue;
			if (!q.isEmpty() && !e.key().contains(q) && !e.label().contains(q)) continue;
			list.add(new Row(e), 18);
			if (++shown > 600) break;
		}
	}

	@Override public void render(Frame f) {
		host = f.host;
		float o = open.to(1, f.dt, 14);
		Ui.rect(f.g, x, y, w, h, Ui.alpha(0xFF05070D, 0.72f * o));
		dw = Math.min(w - 24, 340);
		dh = Math.min(h - 16, 250);
		dx = x + (w - dw) / 2;
		dy = y + (h - dh) / 2 + Math.round((1 - o) * 8);
		Skin.dialog(f.g, dx, dy, dw, dh, o);
		Ui.text(f.g, Ui.fit(title, dw - 90), dx + 10, dy + 9, Ui.TEXT);
		Ui.textR(f.g, selected.size() + " selected", dx + dw - 10, dy + 9, Ui.DIM);
		search.at(dx + 10, dy + 24, dw - 20, 16);
		search.render(f);
		if (!lastQuery.equals(search.text().toLowerCase(Locale.ROOT).trim() + (onlySelected ? "\1" : ""))) refill();
		list.at(dx + 10, dy + 46, dw - 20, dh - 46 - 30);
		list.render(f);
		int by = dy + dh - 24;
		ok.at(dx + dw - 10 - 70, by, 70, 16);
		clear.at(dx + 10, by, 50, 16);
		onlySel.at(dx + 64, by, 80, 16);
		clear.render(f);
		onlySel.render(f);
		ok.render(f);
	}

	private final class Row extends W {
		final Entry e;
		final Anim hov = new Anim(0);

		Row(Entry e) {
			this.e = e;
		}

		@Override public void render(Frame fr) {
			boolean hot = over(fr.mx, fr.my);
			float hv = hov.to(hot ? 1 : 0, fr.dt, 16);
			boolean sel = selected.contains(e.key());
			if (sel) Ui.rrect(fr.g, x, y, w, h, 3, Ui.alpha(Ui.accent(), 0.16f));
			else if (hv > 0.01f) Ui.rrect(fr.g, x, y, w, h, 3, Ui.alpha(0xFFFFFFFF, 0.06f * hv));
			if (!e.icon().isEmpty()) {
				var pose = fr.g.pose();
				pose.pushMatrix();
				pose.translate(x + 2, y + 1);
				pose.scale(0.9f, 0.9f);
				fr.g.fakeItem(e.icon(), 0, 0);
				pose.popMatrix();
			}
			int tx = x + 22;
			Ui.text(fr.g, Ui.fit(e.label(), w - 62), tx, y + 5, sel ? Ui.TEXT : Ui.DIM);
			if (multi) {
				int bx = x + w - 14, by = y + 4;
				Ui.rrect(fr.g, bx, by, 10, 10, 3, sel ? Ui.accent() : Ui.FIELD);
				if (!sel) Ui.rring(fr.g, bx, by, 10, 10, 3, Ui.STROKE);
				else Ui.textC(fr.g, "v", bx + 5, by + 1, Ui.ON_ACCENT);
			}
		}

		@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
			if (!over(mx, my) || btn != 0) return false;
			Ui.click();
			if (multi) {
				if (!selected.remove(e.key())) selected.add(e.key());
			} else {
				host.closeModal();
				pick.accept(e.key());
			}
			return true;
		}
	}

	@Override public boolean mouseClicked(double mx, double my, int btn, boolean dbl) {
		if (!(mx >= dx && mx < dx + dw && my >= dy && my < dy + dh)) {
			if (multi) finish();
			else host.closeModal();
			return true;
		}
		if (search.mouseClicked(mx, my, btn, dbl)) return true;
		if (ok.mouseClicked(mx, my, btn, dbl) || clear.mouseClicked(mx, my, btn, dbl) || onlySel.mouseClicked(mx, my, btn, dbl)) return true;
		list.mouseClicked(mx, my, btn, dbl);
		return true;
	}

	@Override public boolean mouseReleased(double mx, double my, int btn) {
		ok.mouseReleased(mx, my, btn);
		clear.mouseReleased(mx, my, btn);
		onlySel.mouseReleased(mx, my, btn);
		search.mouseReleased(mx, my, btn);
		list.mouseReleased(mx, my, btn);
		return true;
	}

	@Override public boolean mouseDragged(double mx, double my, int btn) {
		return search.mouseDragged(mx, my, btn) || list.mouseDragged(mx, my, btn);
	}

	@Override public boolean mouseScrolled(double mx, double my, double dy) {
		list.mouseScrolled(mx, my, dy);
		return true;
	}

	@Override public boolean keyPressed(KeyEvent e) {
		if (e.isEscape()) {
			host.closeModal();
			return true;
		}
		return search.keyPressed(e);
	}

	@Override public boolean charTyped(int cp) {
		return search.charTyped(cp);
	}

	/** The search box is always focused while the dialog is open. */
	public TextBox searchBox() {
		return search;
	}
}
