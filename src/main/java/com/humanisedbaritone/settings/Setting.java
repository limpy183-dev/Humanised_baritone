package com.humanisedbaritone.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One user-tweakable option. The GUI, the config file and the command line are all generated from {@link #ALL}. */
public abstract class Setting<T> {
	public static final List<Setting<?>> ALL = new ArrayList<>();

	public final String id, name, desc, category, group;
	public final T def;
	protected T value;
	/** When set, the GUI greys this setting out while the parent toggle is off. */
	public Setting.Bool parent;

	Setting(String category, String group, String id, String name, String desc, T def) {
		this.category = category;
		this.group = group;
		this.id = id;
		this.name = name;
		this.desc = desc;
		this.def = def;
		this.value = def;
		ALL.add(this);
	}

	public T get() {
		return value;
	}

	public void set(T v) {
		T n = normalise(v);
		if (!n.equals(value)) {
			value = n;
			Settings.markDirty();
		}
	}

	public void reset() {
		set(def);
	}

	public boolean isDefault() {
		return value.equals(def);
	}

	public boolean enabled() {
		return parent == null || parent.get();
	}

	protected T normalise(T v) {
		return v;
	}

	/** Short human readable value for the UI. */
	public abstract String display();

	public abstract JsonElement toJson();

	public abstract void fromJson(JsonElement e);

	/** Parses user text (command line). Returns false when the text is not valid for this setting. */
	public abstract boolean parse(String s);

	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S child(Setting.Bool p) {
		this.parent = p;
		return (S) this;
	}

	// ---------------------------------------------------------------- Bool

	public static final class Bool extends Setting<Boolean> {
		Bool(String cat, String grp, String id, String name, String desc, boolean def) {
			super(cat, grp, id, name, desc, def);
		}

		public boolean on() {
			return value;
		}

		public void toggle() {
			set(!value);
		}

		@Override public String display() {
			return value ? "ON" : "OFF";
		}

		@Override public JsonElement toJson() {
			return new JsonPrimitive(value);
		}

		@Override public void fromJson(JsonElement e) {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) value = e.getAsBoolean();
		}

		@Override public boolean parse(String s) {
			switch (s.toLowerCase(Locale.ROOT)) {
				case "true", "on", "yes", "1", "enable", "enabled" -> set(true);
				case "false", "off", "no", "0", "disable", "disabled" -> set(false);
				case "toggle" -> toggle();
				default -> {
					return false;
				}
			}
			return true;
		}
	}

	// ----------------------------------------------------------------- Num

	/** Integer or decimal number with a slider range. */
	public static final class Num extends Setting<Double> {
		public final double min, max, step;
		public final boolean integer;
		public final String unit;

		Num(String cat, String grp, String id, String name, String desc, double def, double min, double max, double step, boolean integer, String unit) {
			super(cat, grp, id, name, desc, clamp(def, min, max));
			this.min = min;
			this.max = max;
			this.step = step;
			this.integer = integer;
			this.unit = unit;
		}

		private static double clamp(double v, double lo, double hi) {
			return Math.max(lo, Math.min(hi, v));
		}

		public double d() {
			return value;
		}

		public float f() {
			return value.floatValue();
		}

		public int i() {
			return (int) Math.round(value);
		}

		/** Milliseconds to ticks. */
		public int ticks() {
			return (int) Math.round(value / 50.0);
		}

		@Override protected Double normalise(Double v) {
			double c = clamp(v, min, max);
			if (step > 0) c = Math.round(c / step) * step;
			c = clamp(c, min, max);
			return integer ? (double) Math.round(c) : Math.round(c * 10000.0) / 10000.0;
		}

		public String format(double v) {
			String s = integer ? Long.toString(Math.round(v)) : trim(v);
			return unit.isEmpty() ? s : (unit.equals("x") || unit.equals("%")) ? s + unit : s + " " + unit;
		}

		private static String trim(double v) {
			String s = String.format(Locale.ROOT, "%.2f", v);
			if (s.contains(".")) {
				s = s.replaceAll("0+$", "");
				if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
			}
			return s;
		}

		@Override public String display() {
			return format(value);
		}

		@Override public JsonElement toJson() {
			return new JsonPrimitive(value);
		}

		@Override public void fromJson(JsonElement e) {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) value = normalise(e.getAsDouble());
		}

		@Override public boolean parse(String s) {
			try {
				set(Double.parseDouble(s.trim()));
				return true;
			} catch (NumberFormatException ex) {
				return false;
			}
		}
	}

	// -------------------------------------------------------------- Choice

	public static final class Choice<E extends Enum<E>> extends Setting<E> {
		public final E[] options;

		Choice(String cat, String grp, String id, String name, String desc, E def) {
			super(cat, grp, id, name, desc, def);
			this.options = def.getDeclaringClass().getEnumConstants();
		}

		public void cycle(int dir) {
			set(options[Math.floorMod(value.ordinal() + dir, options.length)]);
		}

		public static String nice(Enum<?> e) {
			String s = e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
			return Character.toUpperCase(s.charAt(0)) + s.substring(1);
		}

		@Override public String display() {
			return nice(value);
		}

		@Override public JsonElement toJson() {
			return new JsonPrimitive(value.name());
		}

		@Override public void fromJson(JsonElement e) {
			if (!e.isJsonPrimitive()) return;
			for (E o : options) if (o.name().equalsIgnoreCase(e.getAsString())) value = o;
		}

		@Override public boolean parse(String s) {
			String n = s.trim().replace(' ', '_');
			for (E o : options) {
				if (o.name().equalsIgnoreCase(n)) {
					set(o);
					return true;
				}
			}
			return false;
		}
	}

	// ---------------------------------------------------------------- Text

	public static final class Text extends Setting<String> {
		Text(String cat, String grp, String id, String name, String desc, String def) {
			super(cat, grp, id, name, desc, def);
		}

		@Override public String display() {
			return value;
		}

		@Override public JsonElement toJson() {
			return new JsonPrimitive(value);
		}

		@Override public void fromJson(JsonElement e) {
			if (e.isJsonPrimitive()) value = e.getAsString();
		}

		@Override public boolean parse(String s) {
			set(s);
			return true;
		}
	}

	// --------------------------------------------------------------- IdSet

	public enum Registry { BLOCK, ITEM, ENTITY }

	/** A set of registry ids such as {@code minecraft:stone}. */
	public static final class IdSet extends Setting<Set<String>> {
		public final Registry registry;

		IdSet(String cat, String grp, String id, String name, String desc, Registry registry, String... def) {
			super(cat, grp, id, name, desc, normalised(def));
			this.registry = registry;
			this.value = new LinkedHashSet<>(this.def);
		}

		private static Set<String> normalised(String[] ids) {
			Set<String> out = new LinkedHashSet<>();
			for (String s : ids) out.add(norm(s));
			return out;
		}

		public boolean has(String key) {
			return value.contains(key);
		}

		public void toggle(String key) {
			Set<String> n = new LinkedHashSet<>(value);
			if (!n.remove(key)) n.add(key);
			set(n);
		}

		@Override public boolean isDefault() {
			return value.equals(def);
		}

		@Override public String display() {
			return value.size() + (value.size() == 1 ? " entry" : " entries");
		}

		@Override public JsonElement toJson() {
			JsonArray a = new JsonArray();
			for (String s : value) a.add(s);
			return a;
		}

		@Override public void fromJson(JsonElement e) {
			if (!e.isJsonArray()) return;
			Set<String> n = new LinkedHashSet<>();
			for (JsonElement x : e.getAsJsonArray()) n.add(norm(x.getAsString()));
			value = n;
		}

		static String norm(String s) {
			s = s.trim().toLowerCase(Locale.ROOT);
			return s.contains(":") ? s : "minecraft:" + s;
		}

		@Override public boolean parse(String s) {
			Set<String> n = new LinkedHashSet<>();
			for (String p : s.split("[,\\s]+")) if (!p.isBlank()) n.add(norm(p));
			set(n);
			return true;
		}
	}
}
