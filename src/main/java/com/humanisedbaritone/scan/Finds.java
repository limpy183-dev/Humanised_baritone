package com.humanisedbaritone.scan;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.humanisedbaritone.scan.Find.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Everything the scanner logged and the map it painted, kept separately for every world (a server, or a singleplayer
 * save) and every dimension in it: {@code config/humanised_baritone/finds/<world>/<dimension>/}.
 */
public final class Finds {
	/** Most entries a dimension keeps; past that the oldest ones that were already dealt with (or the oldest at all) go. */
	public static final int MAX = 4000;
	private static final Gson GSON = new Gson();
	private static final Map<String, Store> stores = new HashMap<>();
	/** The store of the world and dimension you are in, and the one the pages show (the same unless you browse another). */
	public static Store live, view;

	private Finds() {
	}

	public static final class Store {
		public final String world, dim, worldName;
		public final Path dir;
		public final List<Find> list = new ArrayList<>();
		public final Atlas atlas;
		/** Counts every change, so the pages know when to rebuild what they show. */
		public int rev;
		private int nextId = 1;
		private boolean dirty;
		private long dirtyAt;

		Store(String world, String worldName, String dim) {
			this.world = world;
			this.worldName = worldName;
			this.dim = dim;
			this.dir = root().resolve(world).resolve(dimDir(dim));
			this.atlas = new Atlas(dir);
			load();
		}

		public void touch() {
			if (!dirty) dirtyAt = System.currentTimeMillis();
			dirty = true;
			rev++;
		}

		public Find byId(int id) {
			for (Find f : list) if (f.id == id) return f;
			return null;
		}

		/** The find of this kind at exactly this block, or null. */
		public Find at(Kind kind, int x, int y, int z) {
			for (Find f : list) if (f.kind == kind && f.x == x && f.y == y && f.z == z) return f;
			return null;
		}

		/** The find with this key of this kind, or null. */
		public Find byKey(Kind kind, String key) {
			for (Find f : list) if (f.kind == kind && f.key.equals(key)) return f;
			return null;
		}

		public Find add(Find f) {
			f.id = nextId++;
			if (f.first == 0) f.first = System.currentTimeMillis();
			if (f.last == 0) f.last = f.first;
			list.add(f);
			while (list.size() > MAX) {
				Find drop = null;
				for (Find c : list) {
					if (c == f || c.kind == Kind.NOTE) continue;
					if (drop == null || c.checked && !drop.checked || c.checked == drop.checked && c.last < drop.last) drop = c;
				}
				if (drop == null) break;
				list.remove(drop);
			}
			touch();
			return f;
		}

		public void remove(Find f) {
			if (list.remove(f)) touch();
		}

		/** Removes the finds of the given kinds ({@code mask} of {@link Kind#bit()}); returns how many went. */
		public int clear(int mask) {
			int n = list.size();
			list.removeIf(f -> (mask & f.kind.bit()) != 0);
			if (list.size() != n) touch();
			return n - list.size();
		}

		public int count(Kind k) {
			int n = 0;
			for (Find f : list) if (f.kind == k) n++;
			return n;
		}

		private Path file() {
			return dir.resolve("finds.json");
		}

		private void load() {
			try {
				if (!Files.exists(file())) return;
				JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
				nextId = root.has("next") ? root.get("next").getAsInt() : 1;
				for (JsonElement e : root.getAsJsonArray("finds")) {
					try {
						Find f = GSON.fromJson(e, Find.class);
						if (f != null && f.kind != null) {
							list.add(f);
							nextId = Math.max(nextId, f.id + 1);
						}
					} catch (RuntimeException skip) { // one damaged entry must not cost the rest
					}
				}
			} catch (IOException | RuntimeException ex) {
				System.err.println("[humanised_baritone] could not read " + file() + ": " + ex);
			}
		}

		/** Writes the entries when something changed (and the map tiles that changed). */
		public void save() {
			saveFinds();
			atlas.saveAll();
		}

		void saveFinds() {
			if (!dirty) return;
			dirty = false;
			try {
				Files.createDirectories(dir);
				JsonObject root = new JsonObject();
				root.addProperty("version", 1);
				root.addProperty("next", nextId);
				root.addProperty("world", worldName);
				root.addProperty("dimension", dim);
				JsonArray a = new JsonArray();
				for (Find f : list) a.add(GSON.toJsonTree(f));
				root.add("finds", a);
				Path tmp = dir.resolve("finds.json.tmp");
				Files.writeString(tmp, GSON.toJson(root));
				Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
				Files.writeString(root().resolve(world).resolve("world.txt"), worldName);
			} catch (IOException ex) {
				System.err.println("[humanised_baritone] could not write " + file() + ": " + ex);
			}
		}

		/** Called often: writes a moment after the last change. */
		void tick() {
			if (dirty && System.currentTimeMillis() - dirtyAt > 8000) saveFinds();
		}
	}

	// ------------------------------------------------------------------ places

	static Path root() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanised_baritone").resolve("finds");
	}

	private static String clean(String s) {
		String c = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		return c.length() > 64 ? c.substring(0, 64) : c;
	}

	static String dimDir(String dim) {
		return clean(dim);
	}

	/** Folder name of the world you are in: the server address, or the save's folder. */
	public static String worldId() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hasSingleplayerServer()) {
			Path p = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize();
			return "sp_" + clean(String.valueOf(p.getFileName()));
		}
		ServerData sd = mc.getCurrentServer();
		return sd == null ? "unknown" : "srv_" + clean(sd.ip);
	}

	/** What the world is called in the pages. */
	public static String worldName() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hasSingleplayerServer()) return "Singleplayer: " + mc.getSingleplayerServer().getWorldData().getLevelName();
		ServerData sd = mc.getCurrentServer();
		if (sd == null) return "Unknown world";
		return sd.name == null || sd.name.isBlank() || sd.name.equalsIgnoreCase(sd.ip) ? sd.ip : sd.name + " (" + sd.ip + ")";
	}

	/** "minecraft:the_nether" -> "The Nether". */
	public static String prettyDim(String dim) {
		String d = dim.replaceFirst("^minecraft[:_]", "");
		return switch (d) {
			case "overworld" -> "Overworld";
			case "the_nether" -> "The Nether";
			case "the_end" -> "The End";
			default -> {
				String s = d.replace(':', ' ').replace('_', ' ');
				yield s.isEmpty() ? dim : Character.toUpperCase(s.charAt(0)) + s.substring(1);
			}
		};
	}

	/** Opens (or reuses) the store of a world and dimension. */
	public static Store open(String world, String worldName, String dim) {
		return stores.computeIfAbsent(world + "|" + dimDir(dim), k -> new Store(world, worldName, dim));
	}

	/** Switches the live store to the level you are in; the pages follow if they were showing the live one. */
	public static void use(ClientLevel level) {
		String w = worldId(), d = level.dimension().identifier().toString();
		if (live != null && live.world.equals(w) && live.dim.equals(d)) return;
		boolean follow = view == null || view == live;
		if (live != null) { // the entries now; the map tiles of the store you leave are written bit by bit in tick()
			live.saveFinds();
			live.atlas.releaseTextures(true);
		}
		live = open(w, worldName(), d);
		if (follow) view = live;
	}

	/** Makes the pages show another store; the map memory of the one that is no longer shown (and not live) is given back. */
	public static void show(Store s) {
		Store old = view;
		view = s;
		if (old != null && old != s && old != live) old.atlas.close();
	}

	/** Leaves the world: everything is written and the memory given back. */
	public static void leave() {
		for (Store s : stores.values()) s.atlas.close();
		for (Store s : stores.values()) s.saveFinds();
		stores.clear();
		live = null;
		view = null;
	}

	/** Writes what changed, without closing anything. */
	public static void flush() {
		for (Store s : stores.values()) s.save();
	}

	private static int ticks;

	/** Every tick: delayed writes, and the textures of map tiles nobody looks at any more are given back. */
	public static void tick() {
		if (live != null) live.tick();
		if (ticks % 20 == 10) for (Store s : stores.values()) if (s != live) s.atlas.saveSome(1); // the maps of the dimensions you left
		if (++ticks % 100 != 0) return;
		if (live != null) live.atlas.releaseTextures(false);
		if (view != null && view != live) view.atlas.releaseTextures(false);
	}

	// -------------------------------------------------------------- browsing

	/** Folder names of the worlds that have stored data. */
	public static List<String> worlds() {
		List<String> out = new ArrayList<>();
		try (Stream<Path> s = Files.exists(root()) ? Files.list(root()) : Stream.empty()) {
			s.filter(Files::isDirectory).forEach(p -> out.add(p.getFileName().toString()));
		} catch (IOException ignored) {
		}
		for (Store s : stores.values()) if (!out.contains(s.world)) out.add(s.world);
		out.sort(String::compareTo);
		return out;
	}

	public static String nameOf(String world) {
		for (Store s : stores.values()) if (s.world.equals(world)) return s.worldName;
		try {
			return Files.readString(root().resolve(world).resolve("world.txt")).trim();
		} catch (IOException e) {
			return world;
		}
	}

	/** The dimensions of a world that have stored data, as dimension ids ("minecraft:overworld"). */
	public static List<String> dims(String world) {
		List<String> out = new ArrayList<>();
		for (Store s : stores.values()) if (s.world.equals(world) && !out.contains(s.dim)) out.add(s.dim);
		try (Stream<Path> s = Files.exists(root().resolve(world)) ? Files.list(root().resolve(world)) : Stream.empty()) {
			s.filter(Files::isDirectory).forEach(p -> {
				String n = p.getFileName().toString();
				boolean have = false;
				for (String d : out) have |= dimDir(d).equals(n);
				if (!have) out.add(n.replaceFirst("^minecraft_", "minecraft:"));
			});
		} catch (IOException ignored) {
		}
		out.sort(String::compareTo);
		return out;
	}
}
