package com.humanisedbaritone.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/** Built-in humanising presets and user saved settings profiles. */
public final class Profiles {
	public static final Map<String, String> PRESET_INFO = new LinkedHashMap<>();
	private static final Map<String, Map<String, String>> PRESETS = new LinkedHashMap<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private Profiles() {
	}

	private static void preset(String name, String info, String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
		PRESETS.put(name, m);
		PRESET_INFO.put(name, info);
	}

	static {
		preset("Casual", "Relaxed and a bit sloppy: slower camera, more pauses, more variation.",
			"look.speed", "0.8", "look.max", "450", "look.reaction", "190", "look.overshoot", "0.4", "look.noise", "1.4", "look.glance", "0.6", "look.pitch", "8",
			"motion.wander", "0.5", "motion.corner", "0.5", "motion.hesitate", "0.45", "motion.strafe", "0.35", "motion.sprintDelay", "260", "motion.jumpSlack", "2",
			"act.delay", "260", "act.jitter", "0.7", "act.swap", "220", "act.settle", "90", "aim.spread", "0.75", "var.path", "0.07", "var.attentionAmt", "0.6", "var.fidget", "0.5");
		preset("Natural", "The default balance between believable and efficient.",
			"look.speed", "1", "look.max", "600", "look.reaction", "130", "look.overshoot", "0.3", "look.noise", "1", "look.glance", "0.4", "look.pitch", "6",
			"motion.wander", "0.35", "motion.corner", "0.6", "motion.hesitate", "0.25", "motion.strafe", "0.25", "motion.sprintDelay", "180", "motion.jumpSlack", "1",
			"act.delay", "140", "act.jitter", "0.5", "act.swap", "160", "act.settle", "60", "aim.spread", "0.6", "var.path", "0.04", "var.attentionAmt", "0.4", "var.fidget", "0.3");
		preset("Focused", "Efficient and steady, still clearly a person.",
			"look.speed", "1.25", "look.max", "720", "look.reaction", "100", "look.overshoot", "0.2", "look.noise", "0.8", "look.glance", "0.25", "look.pitch", "5",
			"motion.wander", "0.2", "motion.corner", "0.75", "motion.hesitate", "0.12", "motion.strafe", "0.15", "motion.sprintDelay", "120", "motion.jumpSlack", "1",
			"act.delay", "90", "act.jitter", "0.35", "act.swap", "110", "act.settle", "40", "aim.spread", "0.45", "var.path", "0.03", "var.attentionAmt", "0.25", "var.fidget", "0.15");
		preset("Speedrun", "As fast as it gets while the input still looks like a hand on a mouse.",
			"look.speed", "1.8", "look.max", "900", "look.reaction", "60", "look.overshoot", "0.15", "look.noise", "0.6", "look.glance", "0.1", "look.pitch", "4",
			"motion.wander", "0.1", "motion.corner", "0.9", "motion.hesitate", "0.05", "motion.strafe", "0.08", "motion.sprintDelay", "60", "motion.jumpSlack", "0",
			"act.delay", "40", "act.jitter", "0.25", "act.swap", "70", "act.settle", "20", "aim.spread", "0.3", "var.path", "0.02", "var.attentionAmt", "0.15", "var.fidget", "0.05");
	}

	public static List<String> presets() {
		return new ArrayList<>(PRESETS.keySet());
	}

	public static void applyPreset(String name) {
		Map<String, String> m = PRESETS.get(name);
		if (m != null) Settings.apply(m);
	}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanised_baritone").resolve("profiles");
	}

	private static String safe(String name) {
		return name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
	}

	public static List<String> saved() {
		List<String> out = new ArrayList<>();
		try {
			if (!Files.isDirectory(dir())) return out;
			try (Stream<Path> s = Files.list(dir())) {
				s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.substring(0, n.length() - 5)).sorted().forEach(out::add);
			}
		} catch (IOException ignored) {
		}
		return out;
	}

	/** Writes the current settings into the profile file, without selecting it. */
	static boolean write(String name) {
		try {
			Files.createDirectories(dir());
			JsonObject o = new JsonObject();
			for (Setting<?> s : Setting.ALL) if (!s.isDefault()) o.add(s.id, s.toJson());
			Files.writeString(dir().resolve(safe(name) + ".json"), GSON.toJson(o));
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** Stores the current settings under this name and makes it the selected profile. */
	public static boolean save(String name) {
		if (safe(name).isEmpty() || !write(name)) return false;
		Settings.profile = safe(name);
		Settings.markDirty();
		return true;
	}

	/** Loads the profile's settings and makes it the selected profile. */
	public static boolean load(String name) {
		try {
			Path p = dir().resolve(safe(name) + ".json");
			if (!Files.exists(p)) return false;
			JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
			Settings.save(); // edits made less than a moment ago still belong to the profile being left
			Settings.profile = ""; // nothing half loaded may be written back into the old profile
			Settings.resetAll();
			for (Setting<?> s : Setting.ALL) {
				JsonElement e = o.get(s.id);
				if (e != null) s.fromJson(e);
			}
			Settings.profile = safe(name);
			Settings.markDirty();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public static boolean delete(String name) {
		try {
			if (safe(name).equals(Settings.profile)) { // before the file goes, or the next save would write it again
				Settings.profile = "";
				Settings.markDirty();
			}
			return Files.deleteIfExists(dir().resolve(safe(name) + ".json"));
		} catch (IOException e) {
			return false;
		}
	}
}
