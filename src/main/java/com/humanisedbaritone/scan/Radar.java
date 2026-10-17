package com.humanisedbaritone.scan;

import com.humanisedbaritone.Commands;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.scan.Find.Kind;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.GotoTask;
import com.humanisedbaritone.world.BlockScan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Reads what the client has loaded - the same block entities, blocks and entities a chest ESP or a block search draws - and
 * turns it into {@link Find}s: container clusters, spawners, players, valuable items... It only reads; nothing is sent to the
 * server. Everything here runs on the client thread, in small slices.
 */
public final class Radar {
	private static final Minecraft mc = Minecraft.getInstance();

	private record Cont(BlockPos pos, Block block, int weight) {
	}

	private record Hit(BlockPos pos, BlockState state) {
	}

	private record Alert(Find find, String text) {
	}

	/** The level all the state below belongs to. */
	private static ClientLevel level;
	/** Every loaded container, by position. */
	private static final Map<BlockPos, Cont> containers = new HashMap<>();
	/** What the block search found, per chunk. */
	private static final Map<Long, List<Hit>> hits = new HashMap<>();
	private static final ArrayDeque<Long> queue = new ArrayDeque<>();
	private static final Set<Long> queued = new HashSet<>();
	private static final Map<Long, Long> scannedAt = new HashMap<>();
	/** The find of every special block entity seen so far, by position: a second look at it only has to refine it. */
	private static final Map<Long, Find> specials = new HashMap<>();
	private static final List<Alert> alerts = new ArrayList<>();
	private static boolean stashDirty, searchDirty, wantAll, dead, failed;
	private static long tickNo;
	/** What the settings said last time: a change in what is collected means a new pass over the loaded world. */
	private static int scanSig = -1, paramSig = -1;
	private static Set<Block> stashSet = Set.of(), targetSet = Set.of();
	private static Set<Item> itemSet = Set.of();

	private Radar() {
	}

	// ================================================================= hooks

	private static void guard(String where, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) { // the scanner must never take the bot (or the game) down with it
			if (!failed) {
				failed = true;
				Log.warn("The scanner hit an internal error in " + where + ": " + t);
				t.printStackTrace();
			}
		}
	}

	private static void ensure(ClientLevel lv) {
		if (lv == level) return;
		level = lv;
		containers.clear();
		hits.clear();
		queue.clear();
		queued.clear();
		scannedAt.clear();
		specials.clear();
		alerts.clear();
		later.clear();
		spawns.clear();
		stashDirty = searchDirty = spawnDirty = dead = false;
		scanSig = paramSig = -1;
		Finds.use(lv);
	}

	/** A block entity came into the client's world. */
	public static void onBlockEntity(BlockEntity be, ClientLevel lv) {
		guard("block entity", () -> {
			ensure(lv);
			if (!Settings.SCAN.on()) return;
			Block b = be.getBlockState().getBlock();
			// containers are counted at once; the others wait for a pass over their chunk a few ticks later, when the data that follows the
			// block entity (the mob of a spawner, the writing on a sign) has arrived
			if (Settings.STASH.on() && stashSet.contains(b)) classify(be);
			else if (special(be, b)) later.putIfAbsent(ChunkPos.pack(be.getBlockPos().getX() >> 4, be.getBlockPos().getZ() >> 4), tickNo + 5);
		});
	}

	/** Chunks to look at again soon, with the tick when. */
	private static final Map<Long, Long> later = new HashMap<>();

	private static boolean special(BlockEntity be, Block b) {
		return switch (BuiltInRegistries.BLOCK.getKey(b).getPath()) {
			case "spawner", "trial_spawner", "vault", "beacon", "conduit", "end_portal", "end_gateway" -> true;
			default -> be instanceof SignBlockEntity;
		};
	}

	public static void onBlockEntityGone(BlockEntity be, ClientLevel lv) {
		guard("block entity", () -> {
			if (lv != level) return;
			if (containers.remove(be.getBlockPos()) != null) stashDirty = true;
			spawns.remove(be.getBlockPos());
		});
	}

	/** A chunk left the loaded area: what was found in it stays in the log, the work it needed is forgotten. */
	public static void onChunkUnload(ClientLevel lv, LevelChunk chunk) {
		guard("chunk", () -> {
			if (lv != level) return;
			long k = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
			hits.remove(k);
			scannedAt.remove(k);
		});
	}

	public static void onChunk(ClientLevel lv, LevelChunk chunk) {
		guard("chunk", () -> {
			ensure(lv);
			enqueue(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
		});
	}

	/** Leaving the world: everything is written, and the memory given back. */
	public static void onDisconnect() {
		guard("disconnect", () -> {
			level = null;
			containers.clear();
			hits.clear();
			queue.clear();
			later.clear();
			queued.clear();
			spawns.clear();
			scannedAt.clear();
			specials.clear();
			alerts.clear();
			Finds.leave();
		});
	}

	private static void enqueue(long chunk) {
		if (queued.add(chunk)) queue.add(chunk);
	}

	/** Look at everything that is loaded once more, soon. */
	public static void scanNow() {
		wantAll = true;
	}

	public static int containersLoaded() {
		return containers.size();
	}

	public static String status() {
		if (!Settings.SCAN.on()) return "Scanner is off";
		return containers.size() + " containers in view" + (queue.isEmpty() ? "" : ", looking at " + queue.size() + " more chunks");
	}

	// ================================================================== tick

	/** End of every client tick. */
	public static void tick() {
		long t0 = System.nanoTime();
		guard("tick", Radar::run);
		long d = System.nanoTime() - t0;
		worstNanos = Math.max(worstNanos, d);
		totalNanos += d;
		ticks++;
		if (d > 4_000_000L) slow4++;
		if (d > 8_000_000L) slow8++;
	}

	/** How long the scanner took per tick: the longest, the sum and the number of ticks (a diagnostic: the tests print it). */
	public static long worstNanos, totalNanos, ticks, slow4, slow8;

	private static void run() {
		tickNo++;
		ClientLevel lv = mc.level;
		LocalPlayer me = mc.player;
		if (lv == null || me == null) return;
		long t = System.nanoTime();
		ensure(lv);
		t = lap("level change", t);
		Finds.tick();
		t = lap("saving", t);
		boolean on = Settings.SCAN.on();
		configure();
		if (wantAll) {
			wantAll = false;
			scannedAt.clear();
			specials.clear();
			rescanAll();
		}
		if (!later.isEmpty()) later.entrySet().removeIf(en -> {
			if (en.getValue() > tickNo) return false;
			enqueue(en.getKey());
			return true;
		});
		t = lap("settings", t);
		if (on || Settings.MAP_RECORD.on()) pump();
		t = lap("chunks", t);
		if (tickNo % 40 == 0) refill();
		t = lap("refill", t);
		if (!on) return;
		death(me);
		if (tickNo % 20 == 0) entities();
		t = lap("entities", t);
		if (stashDirty && tickNo % (containers.size() > 3000 ? 100 : 20) == 5) { // a very big base is grouped less often
			stashDirty = false;
			stash();
		}
		t = lap("stash", t);
		if (searchDirty && tickNo % 20 == 10) {
			searchDirty = false;
			search();
		}
		if (spawnDirty && tickNo % 20 == 15) {
			spawnDirty = false;
			spawners();
		}
		t = lap("search", t);
		flushAlerts();
		t = lap("alerts", t);
		if (tickNo % 20 == 0 && Finds.live != null) Finds.live.atlas.saveSome(4);
		lap("saving", t);
	}

	/** Which step of a tick took longest, and how long (a diagnostic: the tests print it). */
	public static String worstStep = "";
	public static long worstStepNanos;

	private static long lap(String step, long since) {
		long now = System.nanoTime();
		if (now - since > worstStepNanos) {
			worstStepNanos = now - since;
			worstStep = step;
		}
		return now;
	}

	/** Notices changed settings: what to collect changes -> look again; how to group changes -> group again. */
	private static void configure() {
		Set<String> a = Settings.STASH_TYPES.get(), b = Settings.SEARCH_BLOCKS.get(), c = Settings.ITEM_LIST.get();
		int sig = (Settings.SCAN.on() ? 1 : 0) | (Settings.STASH.on() ? 2 : 0) | (Settings.SEARCH.on() ? 4 : 0) | (Settings.SEARCH_EXPOSED.on() ? 8 : 0)
			| (Settings.SPAWNERS.on() ? 16 : 0) | (Settings.PORTALS.on() ? 32 : 0) | (Settings.BASES.on() ? 64 : 0) | (Settings.ITEMS.on() ? 128 : 0)
			| (Settings.SIGNS.on() ? 256 : 0) | (Settings.MAP_RECORD.on() ? 512 : 0);
		sig = 31 * (31 * (31 * sig + a.hashCode()) + b.hashCode()) + c.hashCode();
		if (sig != scanSig) {
			scanSig = sig;
			stashSet = blocks(a);
			itemSet = new HashSet<>();
			for (String s : c) {
				Identifier id = Identifier.tryParse(s);
				if (id != null && BuiltInRegistries.ITEM.containsKey(id)) itemSet.add(BuiltInRegistries.ITEM.getValue(id));
			}
			Set<Block> t = new HashSet<>();
			if (Settings.SEARCH.on()) t.addAll(blocks(b));
			if (Settings.PORTALS.on()) {
				t.add(Blocks.NETHER_PORTAL);
				t.add(Blocks.END_PORTAL_FRAME);
			}
			targetSet = t;
			later.clear();
			spawns.clear();
			containers.clear();
			hits.clear();
			wantAll = true;
		}
		int par = Settings.STASH_MIN.i() * 1009 + Settings.STASH_AREA.i() * 31 + Settings.SEARCH_MIN.i();
		if (par != paramSig) {
			paramSig = par;
			stashDirty = searchDirty = true;
		}
	}

	private static Set<Block> blocks(Set<String> ids) {
		Set<Block> out = new HashSet<>();
		for (String s : ids) {
			Identifier id = Identifier.tryParse(s);
			if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) out.add(BuiltInRegistries.BLOCK.getValue(id));
		}
		return out;
	}

	/** Queues every loaded chunk around the player, the nearest first. */
	private static void rescanAll() {
		LocalPlayer me = mc.player;
		int r = mc.options.getEffectiveRenderDistance() + 2, cx = me.getBlockX() >> 4, cz = me.getBlockZ() >> 4;
		List<long[]> found = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) != null) found.add(new long[]{ChunkPos.pack(cx + dx, cz + dz), (long) dx * dx + (long) dz * dz});
			}
		}
		found.sort((p, q) -> Long.compare(p[1], q[1]));
		for (long[] f : found) enqueue(f[0]);
	}

	/** Chunks that were not looked at for a while go back into the queue: near ones often (the map and what you build), far ones rarely. */
	private static void refill() {
		LocalPlayer me = mc.player;
		int r = mc.options.getEffectiveRenderDistance() + 1, cx = me.getBlockX() >> 4, cz = me.getBlockZ() >> 4;
		long now = System.nanoTime();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				long k = ChunkPos.pack(cx + dx, cz + dz);
				Long at = scannedAt.get(k);
				long every = Math.max(Math.abs(dx), Math.abs(dz)) <= 3 ? 5_000_000_000L : 45_000_000_000L;
				if ((at == null || now - at > every) && level.getChunkSource().getChunkNow(cx + dx, cz + dz) != null) enqueue(k);
			}
		}
	}

	/** Works through the queue for at most a few milliseconds. */
	private static void pump() {
		long t0 = System.nanoTime(), budget = queue.size() > 300 ? 4_000_000L : 2_000_000L;
		while (!queue.isEmpty() && System.nanoTime() - t0 < budget) {
			long k = queue.poll();
			queued.remove(k);
			LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k));
			if (c == null) continue;
			scannedAt.put(k, System.nanoTime());
			scanChunk(c, k);
		}
	}

	private static void scanChunk(LevelChunk chunk, long key) {
		Finds.Store s = Finds.live;
		if (s == null) return;
		if (Settings.MAP_RECORD.on()) s.atlas.paint(level, chunk, mc.player != null ? mc.player.getBlockY() : level.getSeaLevel());
		if (!Settings.SCAN.on()) return;
		for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) classify(be);
		if (!targetSet.isEmpty()) blockScan(chunk, key);
	}

	// ============================================================ block entities

	private static void classify(BlockEntity be) {
		BlockState st = be.getBlockState();
		Block b = st.getBlock();
		BlockPos pos = be.getBlockPos();
		if (Settings.STASH.on() && stashSet.contains(b)) {
			BlockPos ip = pos.immutable();
			int w = b instanceof ChestBlock && st.getValue(ChestBlock.TYPE) == ChestType.LEFT ? 0 : 1; // a double chest is two block entities and counts once
			Cont old = containers.put(ip, new Cont(ip, b, w));
			if (old == null || old.block() != b || old.weight() != w) stashDirty = true;
			return;
		}
		if (Finds.live == null) return;
		String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
		switch (id) {
			case "spawner", "trial_spawner", "vault" -> {
				if (Settings.SPAWNERS.on()) spawner(be, id);
			}
			case "beacon", "conduit" -> {
				if (Settings.BASES.on() && !specials.containsKey(pos.asLong())) point(Kind.BASE, pos, b.getName().getString(), "A base is usually close", id);
			}
			case "end_portal", "end_gateway" -> {
				if (Settings.PORTALS.on() && !specials.containsKey(pos.asLong())) point(Kind.PORTAL, pos, b.getName().getString(), "", id);
			}
			default -> {
				if (be instanceof SignBlockEntity sign && Settings.SIGNS.on()) sign(sign);
			}
		}
	}

	/** One spawner, trial spawner or vault as its block entity says; {@code key} names the find it joins. */
	private record Spawn(String key, String name) {
	}

	private static final Map<BlockPos, Spawn> spawns = new HashMap<>();
	private static boolean spawnDirty;

	private static void spawner(BlockEntity be, String id) {
		Spawn s;
		if (id.equals("spawner")) {
			String key = "spawner", name = "Spawner"; // until the data of the block entity (which comes after the block itself) says which mob
			if (be instanceof SpawnerBlockEntity sp) {
				Entity e = sp.getSpawner().getOrCreateDisplayEntity(level, be.getBlockPos());
				if (e != null) {
					key = "mob:" + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
					name = e.getType().getDescription().getString();
				}
			}
			s = new Spawn(key, name);
		} else {
			s = new Spawn("trial_chamber", id.equals("vault") ? "vault" : "trial spawner");
		}
		if (!s.equals(spawns.put(be.getBlockPos().immutable(), s))) spawnDirty = true;
	}

	/** Spawners of one mob close together are one find ("3 Cave Spider spawners"); the trial spawners and vaults of a trial chamber are one find. */
	private static void spawners() {
		Finds.Store s = Finds.live;
		if (s == null) return;
		Map<String, List<Map.Entry<BlockPos, Spawn>>> byKey = new HashMap<>();
		for (var en : spawns.entrySet()) byKey.computeIfAbsent(en.getValue().key(), k -> new ArrayList<>()).add(en);
		Set<Find> taken = new HashSet<>();
		long now = System.currentTimeMillis();
		for (var kv : byKey.entrySet()) {
			String key = kv.getKey();
			boolean chamber = key.equals("trial_chamber"), unknown = key.equals("spawner");
			int r = chamber ? 40 : 24;
			var list = kv.getValue();
			BlockPos[] p = new BlockPos[list.size()];
			int[] w = new int[p.length];
			for (int i = 0; i < p.length; i++) {
				p[i] = list.get(i).getKey();
				w[i] = 1;
			}
			for (int[] g : group(p, w, r, 1)) {
				int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
				Map<String, Integer> names = new TreeMap<>();
				for (int i : g) {
					x0 = Math.min(x0, p[i].getX());
					y0 = Math.min(y0, p[i].getY());
					z0 = Math.min(z0, p[i].getZ());
					x1 = Math.max(x1, p[i].getX());
					y1 = Math.max(y1, p[i].getY());
					z1 = Math.max(z1, p[i].getZ());
					names.merge(list.get(i).getValue().name(), 1, Integer::sum);
				}
				int count = g.length;
				String name = list.get(g[0]).getValue().name(), label, detail;
				if (chamber) {
					label = "Trial chamber";
					StringBuilder sb = new StringBuilder();
					for (var n : names.entrySet()) sb.append(sb.length() > 0 ? ", " : "").append(n.getValue()).append(' ').append(n.getKey()).append(n.getValue() == 1 ? "" : "s");
					detail = sb.toString();
				} else {
					label = unknown ? (count == 1 ? "Spawner" : count + " spawners") : count == 1 ? name + " spawner" : count + " " + name + " spawners";
					detail = count == 1 ? "Mob spawner" : count + " spawners within " + r + " blocks";
				}
				BlockPos rep = p[g[0]];
				Find f = nearest(s, Kind.SPAWNER, key, rep, r, taken);
				if (f == null) {
					f = new Find();
					f.kind = Kind.SPAWNER;
					f.key = key;
					fill(f, rep, label, detail, count, r, x0, y0, z0, x1, y1, z1);
					s.add(f);
					announce(f, chamber ? label + " (" + detail + ")" : label);
				} else if (count >= f.count) { // like the block search, a count only grows: what is out of view is not gone
					boolean changed = count != f.count || !label.equals(f.label);
					fill(f, rep, label, detail, count, r, Math.min(x0, f.minX), Math.min(y0, f.minY), Math.min(z0, f.minZ), Math.max(x1, f.maxX), Math.max(y1, f.maxY), Math.max(z1, f.maxZ));
					if (changed) s.touch();
				}
				taken.add(f);
				f.last = now;
				if (!unknown) { // the same spawners from before their mob was known
					int a = x0 - 1, b = y0 - 1, c = z0 - 1, d = x1 + 1, e = y1 + 1, h = z1 + 1;
					if (s.list.removeIf(o -> o.kind == Kind.SPAWNER && o.key.equals("spawner") && o.x >= a && o.x <= d && o.y >= b && o.y <= e && o.z >= c && o.z <= h)) s.touch();
				}
			}
		}
	}

	private static void sign(SignBlockEntity sign) {
		StringBuilder sb = new StringBuilder();
		for (var text : new net.minecraft.world.level.block.entity.SignText[]{sign.getFrontText(), sign.getBackText()}) {
			for (Component c : text.getMessages(false)) {
				String t = c.getString().trim();
				if (!t.isEmpty()) sb.append(sb.length() > 0 ? " / " : "").append(t);
			}
		}
		if (sb.length() == 0) return; // the writing comes in a packet of its own after the block: the next pass sees it
		String label = cut(sb.toString(), 60);
		BlockPos pos = sign.getBlockPos();
		Find known = specials.get(pos.asLong());
		if (known == null) point(Kind.SIGN, pos, label, "", "sign");
		else if (!label.equals(known.label)) {
			known.label = label;
			Finds.live.touch();
		}
	}

	private static String cut(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 3) + "...";
	}

	/** A find at one block: made once, found again by its position. */
	private static Find point(Kind kind, BlockPos pos, String label, String detail, String key) {
		Finds.Store s = Finds.live;
		Find f = s.at(kind, pos.getX(), pos.getY(), pos.getZ());
		long now = System.currentTimeMillis();
		if (f != null) {
			f.last = now;
			specials.put(pos.asLong(), f);
			return f;
		}
		f = new Find();
		f.kind = kind;
		f.x = f.minX = f.maxX = pos.getX();
		f.y = f.minY = f.maxY = pos.getY();
		f.z = f.minZ = f.maxZ = pos.getZ();
		f.label = label;
		f.detail = detail;
		f.key = key;
		s.add(f);
		specials.put(pos.asLong(), f);
		announce(f, label);
		return f;
	}

	// ================================================================ clustering

	private static long cell(int x, int y, int z, int r) {
		return ((long) (Math.floorDiv(x, r) & 0x1FFFFF) << 42) | ((long) (Math.floorDiv(y, r) & 0x1FFFFF) << 21) | (Math.floorDiv(z, r) & 0x1FFFFF);
	}

	/** Indices of the points within {@code r} of point {@code i} in every axis (itself included). */
	private static List<Integer> around(Map<Long, List<Integer>> grid, BlockPos[] p, int i, int r) {
		List<Integer> out = new ArrayList<>();
		int cx = Math.floorDiv(p[i].getX(), r), cy = Math.floorDiv(p[i].getY(), r), cz = Math.floorDiv(p[i].getZ(), r);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					List<Integer> cellList = grid.get(cell((cx + dx) * r, (cy + dy) * r, (cz + dz) * r, r));
					if (cellList == null) continue;
					for (int j : cellList) {
						if (Math.abs(p[j].getX() - p[i].getX()) <= r && Math.abs(p[j].getY() - p[i].getY()) <= r && Math.abs(p[j].getZ() - p[i].getZ()) <= r) out.add(j);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Groups of points: a point in a dense spot starts a group made of all points within {@code r} of it (every axis) that are
	 * still free, and so on, as long as a group weighs at least {@code min}. Dense is judged from a grid with cells of size {@code r}, so
	 * the work stays small however many points there are.
	 */
	public static List<int[]> group(BlockPos[] p, int[] w, int r, int min) {
		int n = p.length;
		Map<Long, List<Integer>> grid = new HashMap<>();
		for (int i = 0; i < n; i++) grid.computeIfAbsent(cell(p[i].getX(), p[i].getY(), p[i].getZ(), r), k -> new ArrayList<>()).add(i);
		Map<Long, Integer> cellWeight = new HashMap<>();
		for (var en : grid.entrySet()) {
			int sum = 0;
			for (int j : en.getValue()) sum += w[j];
			cellWeight.put(en.getKey(), sum);
		}
		int[] score = new int[n]; // what the 27 cells around a point hold: more than its real neighbourhood, cheap, and enough to start with the densest
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
			int cx = Math.floorDiv(p[i].getX(), r), cy = Math.floorDiv(p[i].getY(), r), cz = Math.floorDiv(p[i].getZ(), r);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) score[i] += cellWeight.getOrDefault(cell((cx + dx) * r, (cy + dy) * r, (cz + dz) * r, r), 0);
				}
			}
		}
		Arrays.sort(order, (a, b) -> Integer.compare(score[b], score[a]));
		boolean[] used = new boolean[n];
		List<int[]> out = new ArrayList<>();
		for (int i : order) {
			if (used[i] || score[i] < min) continue;
			List<Integer> free = new ArrayList<>();
			int weight = 0;
			for (int j : around(grid, p, i, r)) {
				if (used[j]) continue;
				free.add(j);
				weight += w[j];
			}
			if (weight < min) continue;
			for (int j : free) used[j] = true;
			out.add(free.stream().mapToInt(Integer::intValue).toArray());
		}
		return out;
	}

	/** The nearest find of a kind and key within {@code r} blocks that no other group of this pass took. */
	private static Find nearest(Finds.Store s, Kind kind, String key, BlockPos at, int r, Set<Find> taken) {
		Find best = null;
		long bestD = Long.MAX_VALUE;
		for (Find f : s.list) {
			if (f.kind != kind || !f.key.equals(key) || taken.contains(f)) continue;
			int dx = Math.abs(f.x - at.getX()), dy = f.hasY() ? Math.abs(f.y - at.getY()) : 0, dz = Math.abs(f.z - at.getZ());
			if (dx > r || dy > r || dz > r) continue;
			long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
			if (d < bestD) {
				bestD = d;
				best = f;
			}
		}
		return best;
	}

	/** True when every chunk under the box (plus a margin) is loaded, so a count taken from it is complete. */
	private static boolean loaded(int x0, int z0, int x1, int z1) {
		for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
			for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
		}
		return true;
	}

	// ==================================================================== stash

	private static void stash() {
		Finds.Store s = Finds.live;
		if (s == null) return;
		int r = Settings.STASH_AREA.i(), min = Settings.STASH_MIN.i();
		Cont[] all = containers.values().toArray(new Cont[0]);
		BlockPos[] p = new BlockPos[all.length];
		int[] w = new int[all.length];
		for (int i = 0; i < all.length; i++) {
			p[i] = all[i].pos();
			w[i] = all[i].weight();
		}
		Set<Find> taken = new HashSet<>();
		long now = System.currentTimeMillis();
		boolean settled = queue.isEmpty();
		for (int[] g : group(p, w, r, min)) {
			int count = 0, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			double sx = 0, sy = 0, sz = 0;
			Map<String, Integer> types = new TreeMap<>();
			for (int i : g) {
				count += w[i];
				BlockPos q = p[i];
				sx += q.getX();
				sy += q.getY();
				sz += q.getZ();
				minX = Math.min(minX, q.getX());
				minY = Math.min(minY, q.getY());
				minZ = Math.min(minZ, q.getZ());
				maxX = Math.max(maxX, q.getX());
				maxY = Math.max(maxY, q.getY());
				maxZ = Math.max(maxZ, q.getZ());
				String name = all[i].block().getName().getString();
				types.merge(name.endsWith("Shulker Box") ? "Shulker Box" : name, w[i], Integer::sum);
			}
			if (count < min) continue;
			BlockPos rep = p[g[0]];
			double best = Double.MAX_VALUE;
			for (int i : g) { // the container nearest the middle stands for the group: a real block to travel to
				double d = Math.pow(p[i].getX() - sx / g.length, 2) + Math.pow(p[i].getY() - sy / g.length, 2) + Math.pow(p[i].getZ() - sz / g.length, 2);
				if (d < best) {
					best = d;
					rep = p[i];
				}
			}
			StringBuilder detail = new StringBuilder();
			types.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> detail.append(detail.length() > 0 ? ", " : "").append(e.getValue()).append(' ').append(e.getKey()));
			String label = count + (count == 1 ? " container" : " containers");
			Find f = nearest(s, Kind.STASH, "", rep, r, taken);
			if (f == null) {
				f = new Find();
				f.kind = Kind.STASH;
				fill(f, rep, label, detail.toString(), count, r, minX, minY, minZ, maxX, maxY, maxZ);
				s.add(f);
				taken.add(f);
				announce(f, label + " (" + detail + ") within " + r + " blocks");
				continue;
			}
			taken.add(f);
			f.last = now;
			// a count only goes down when everything around it has been looked at and is loaded: a stash seen from afar, or half scanned, is not a smaller stash
			if (count >= f.count || settled && count != f.count && loaded(minX - r, minZ - r, maxX + r, maxZ + r)) {
				boolean changed = count != f.count || f.x != rep.getX() || f.z != rep.getZ();
				fill(f, rep, label, detail.toString(), count, r, minX, minY, minZ, maxX, maxY, maxZ);
				if (changed) s.touch();
			}
			if (f.count >= f.alerted + Math.max(3, f.alerted / 2)) announce(f, "grew to " + label + " (" + detail + ")");
		}
		if (s.list.removeIf(f -> f.kind == Kind.STASH && !taken.contains(f) && absorbed(f, taken))) s.touch(); // a bigger area swallowed the smaller stashes
	}

	/** True when a stash that no group matched this time lies inside a group that is at least as big (the area was widened or the minimum lowered). */
	private static boolean absorbed(Find f, Set<Find> taken) {
		for (Find g : taken) {
			if (g.count >= f.count && Math.abs(g.x - f.x) <= g.radius && Math.abs(g.y - f.y) <= g.radius && Math.abs(g.z - f.z) <= g.radius) return true;
		}
		return false;
	}

	private static void fill(Find f, BlockPos at, String label, String detail, int count, int radius, int x0, int y0, int z0, int x1, int y1, int z1) {
		f.x = at.getX();
		f.y = at.getY();
		f.z = at.getZ();
		f.label = label;
		f.detail = detail;
		f.count = count;
		f.radius = radius;
		f.minX = x0;
		f.minY = y0;
		f.minZ = z0;
		f.maxX = x1;
		f.maxY = y1;
		f.maxZ = z1;
	}

	// ============================================================== block search

	private static void blockScan(LevelChunk chunk, long key) {
		List<Hit> list = new ArrayList<>();
		boolean exposed = Settings.SEARCH_EXPOSED.on();
		BlockScan.inChunk(chunk, st -> targetSet.contains(st.getBlock()), (pos, st) -> {
			if (list.size() >= 512) return; // a block list that matches half the world is not a search
			boolean portal = st.is(Blocks.NETHER_PORTAL) || st.is(Blocks.END_PORTAL_FRAME);
			if (exposed && !portal && !BlockScan.exposed(pos)) return;
			list.add(new Hit(pos, st));
		});
		List<Hit> old = list.isEmpty() ? hits.remove(key) : hits.put(key, list);
		if (!list.isEmpty() || old != null && !old.isEmpty()) searchDirty = true;
	}

	private static void search() {
		Finds.Store s = Finds.live;
		if (s == null) return;
		Map<Block, List<Hit>> byBlock = new HashMap<>();
		for (List<Hit> l : hits.values()) for (Hit h : l) byBlock.computeIfAbsent(h.state().getBlock(), k -> new ArrayList<>()).add(h);
		Set<Find> taken = new HashSet<>();
		long now = System.currentTimeMillis();
		for (var en : byBlock.entrySet()) {
			Block b = en.getKey();
			boolean portal = b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL_FRAME;
			Kind kind = portal ? Kind.PORTAL : Kind.SEARCH;
			int r = b == Blocks.END_PORTAL_FRAME ? 6 : 4, min = portal ? 1 : Settings.SEARCH_MIN.i();
			List<Hit> list = en.getValue();
			BlockPos[] p = new BlockPos[list.size()];
			int[] w = new int[p.length];
			for (int i = 0; i < p.length; i++) {
				p[i] = list.get(i).pos();
				w[i] = 1;
			}
			String name = b.getName().getString(), key = BuiltInRegistries.BLOCK.getKey(b).toString();
			for (int[] g : group(p, w, r, min)) {
				int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, eyes = 0;
				for (int i : g) {
					BlockPos q = p[i];
					minX = Math.min(minX, q.getX());
					minY = Math.min(minY, q.getY());
					minZ = Math.min(minZ, q.getZ());
					maxX = Math.max(maxX, q.getX());
					maxY = Math.max(maxY, q.getY());
					maxZ = Math.max(maxZ, q.getZ());
					if (b == Blocks.END_PORTAL_FRAME && list.get(i).state().getValue(EndPortalFrameBlock.HAS_EYE)) eyes++;
				}
				BlockPos rep = p[g[0]];
				int count = g.length;
				String label = b == Blocks.NETHER_PORTAL ? "Nether portal" : b == Blocks.END_PORTAL_FRAME ? "End portal frames (" + count + ")" : count + " " + name;
				String detail = b == Blocks.END_PORTAL_FRAME ? eyes + " of " + count + " hold an eye of ender" : b == Blocks.NETHER_PORTAL ? count + " portal blocks" : count == 1 ? "Single block" : "Vein of " + count;
				Find f = nearest(s, kind, key, rep, r, taken);
				if (f == null) {
					f = new Find();
					f.kind = kind;
					f.key = key;
					fill(f, rep, label, detail, count, r, minX, minY, minZ, maxX, maxY, maxZ);
					s.add(f);
					taken.add(f);
					announce(f, label);
					continue;
				}
				taken.add(f);
				f.last = now;
				if (count != f.count || !label.equals(f.label)) {
					fill(f, rep, label, detail, Math.max(count, f.count), r, Math.min(minX, f.minX), Math.min(minY, f.minY), Math.min(minZ, f.minZ), Math.max(maxX, f.maxX), Math.max(maxY, f.maxY), Math.max(maxZ, f.maxZ));
					f.label = label;
					s.touch();
				}
			}
		}
	}

	// ================================================================= entities

	private static void entities() {
		Finds.Store s = Finds.live;
		LocalPlayer me = mc.player;
		if (s == null || me == null) return;
		boolean pl = Settings.PLAYERS.on(), it = Settings.ITEMS.on();
		if (!pl && !it) return;
		long now = System.currentTimeMillis();
		for (Entity e : level.entitiesForRendering()) {
			if (pl && e instanceof AbstractClientPlayer p) {
				if (p != me) player(s, p, now);
			} else if (it && e instanceof ItemFrame fr) {
				item(s, fr.getItem(), fr, "in an item frame", now);
			} else if (it && e instanceof ItemEntity ie) {
				item(s, ie.getItem(), ie, "on the ground", now);
			}
		}
	}

	private static void player(Finds.Store s, AbstractClientPlayer p, long now) {
		String key = p.getUUID().toString(), name = p.getName().getString();
		ItemStack hand = p.getMainHandItem();
		int armor = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) if (!p.getItemBySlot(slot).isEmpty()) armor++;
		String detail = (hand.isEmpty() ? "empty-handed" : "holding " + hand.getHoverName().getString()) + ", armor " + armor + "/4";
		Find f = s.byKey(Kind.PLAYER, key);
		boolean fresh = f == null, again = !fresh && now - f.last > Settings.PLAYERS_AGAIN.i() * 1000L;
		if (fresh) {
			f = new Find();
			f.kind = Kind.PLAYER;
			f.key = key;
		}
		boolean moved = Math.abs(f.x - p.getBlockX()) + Math.abs(f.z - p.getBlockZ()) > 16;
		f.x = f.minX = f.maxX = p.getBlockX();
		f.y = f.minY = f.maxY = p.getBlockY();
		f.z = f.minZ = f.maxZ = p.getBlockZ();
		f.label = name;
		f.detail = detail;
		f.last = now;
		if (fresh) {
			s.add(f);
			announce(f, name + " came into view, " + detail);
		} else if (again) {
			f.seen++;
			s.touch();
			announce(f, name + " is back in view, " + detail);
		} else if (moved) {
			s.touch();
		}
	}

	private static void item(Finds.Store s, ItemStack stack, Entity e, String where, long now) {
		if (stack.isEmpty() || !itemSet.contains(stack.getItem())) return;
		String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		BlockPos at = e.blockPosition();
		Find f = nearest(s, Kind.ITEM, key, at, 6, Set.of());
		if (f != null) {
			f.last = now;
			return;
		}
		f = new Find();
		f.kind = Kind.ITEM;
		f.key = key;
		String name = stack.getHoverName().getString();
		fill(f, at, stack.getCount() > 1 ? stack.getCount() + " x " + name : name, where.equals("in an item frame") ? "In an item frame" : "Lying on the ground", stack.getCount(), 0, at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ());
		s.add(f);
		announce(f, name + " " + where);
	}

	private static void death(LocalPlayer me) {
		boolean d = me.isDeadOrDying();
		if (d && !dead && Settings.DEATHS.on() && Finds.live != null) {
			Find f = new Find();
			f.kind = Kind.DEATH;
			BlockPos at = me.blockPosition();
			fill(f, at, "You died", Finds.prettyDim(level.dimension().identifier().toString()), 1, 0, at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ());
			f.key = "death";
			Finds.live.add(f);
			Log.console("You died at " + f.where() + ".");
		}
		dead = d;
	}

	// ================================================================== alerts

	private static void announce(Find f, String text) {
		f.alerted = f.count;
		if (f.kind == Kind.SIGN || f.kind == Kind.DEATH || f.kind == Kind.NOTE) return; // logged quietly
		alerts.add(new Alert(f, text));
	}

	/** Three alerts every half second; a flood (a whole base came into view at once) is cut to three lines and a count. */
	private static void flushAlerts() {
		if (alerts.isEmpty() || tickNo % 10 != 0) return;
		int n = alerts.size();
		if (n > 12) {
			for (int i = 0; i < 3; i++) send(alerts.get(i));
			String more = "+" + (n - 3) + " more finds logged - see the Logging page.";
			Log.console(more);
			if (Settings.SCAN_CHAT.on() && mc.player != null) mc.player.sendSystemMessage(Component.literal("[HB] ").withStyle(Style.EMPTY.withColor(0x7C8CFF)).append(Component.literal(more).withStyle(Style.EMPTY.withColor(0xB0B8D8))));
			alerts.clear();
		} else {
			for (int i = 0; i < Math.min(n, 3); i++) send(alerts.remove(0));
		}
		if (Settings.SCAN_CHAT.on() && Settings.SCAN_SOUND.on()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f));
	}

	private static void send(Alert a) {
		Find f = a.find();
		LocalPlayer me = mc.player;
		long away = me == null ? 0 : Math.round(Math.sqrt(f.dist2(me.getX(), me.getZ())));
		Log.console(f.kind.label + ": " + a.text() + " at " + f.where() + (me != null ? " (" + away + " blocks away)" : ""));
		if (!Settings.SCAN_CHAT.on() || me == null) return;
		MutableComponent coords = Component.literal("[" + f.where() + "]").withStyle(Style.EMPTY.withColor(0x55E0D0).withUnderlined(true)
			.withClickEvent(new ClickEvent.SuggestCommand(Settings.CHAT_PREFIX.get() + "log goto " + f.id))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to put the travel command into the chat box"))));
		MutableComponent msg = Component.literal("[HB] ").withStyle(Style.EMPTY.withColor(0x7C8CFF))
			.append(Component.literal(f.kind.label + ": ").withStyle(Style.EMPTY.withColor(f.kind.color & 0xFFFFFF).withBold(true)))
			.append(Component.literal(a.text() + " at ").withStyle(Style.EMPTY.withColor(0xE9EDF9)))
			.append(coords)
			.append(Component.literal(" " + away + " blocks away").withStyle(Style.EMPTY.withColor(0x8A93B5)));
		me.sendSystemMessage(msg);
	}

	// =============================================================== highlighting

	private static GizmoProperties top(GizmoProperties g) {
		return Settings.RENDER_TOP.on() ? g.setAlwaysOnTop() : g;
	}

	/** How many boxes the last call of {@link #highlight} drew (a diagnostic: the tests read it). */
	public static int highlighted;

	/** Boxes around the finds near you, and around every container of a stash. Called every tick. */
	public static void highlight() {
		highlighted = boxes();
	}

	private static void grow(int[] b, BlockPos p) {
		b[0] = Math.min(b[0], p.getX());
		b[1] = Math.min(b[1], p.getY());
		b[2] = Math.min(b[2], p.getZ());
		b[3] = Math.max(b[3], p.getX());
		b[4] = Math.max(b[4], p.getY());
		b[5] = Math.max(b[5], p.getZ());
	}

	private static boolean in(Find f, BlockPos p) {
		return p.getX() >= f.minX && p.getX() <= f.maxX && p.getY() >= f.minY && p.getY() <= f.maxY && p.getZ() >= f.minZ && p.getZ() <= f.maxZ;
	}

	/**
	 * The box {minX, minY, minZ, maxX, maxY, maxZ} of what of a find is still in the world, or null when all of it is gone. Parts that are
	 * not loaded cannot be checked, so the stored box stands. Containers of a stash are added to {@code members}.
	 */
	private static int[] live(Find f, List<BlockPos> members) {
		int[] stored = {f.minX, f.minY, f.minZ, f.maxX, f.maxY, f.maxZ};
		if (!loaded(f.minX, f.minZ, f.maxX, f.maxZ)) return stored;
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		switch (f.kind) {
			case STASH -> {
				for (Cont c : containers.values()) {
					if (!in(f, c.pos())) continue;
					members.add(c.pos());
					grow(b, c.pos());
				}
			}
			case SPAWNER -> {
				for (var en : spawns.entrySet()) if (in(f, en.getKey()) && en.getValue().key().equals(f.key)) grow(b, en.getKey());
			}
			case SEARCH, PORTAL, BASE, SIGN -> {
				if (f.key.contains(":")) { // a block search find: the hits still standing
					for (int cx = f.minX >> 4; cx <= f.maxX >> 4; cx++) {
						for (int cz = f.minZ >> 4; cz <= f.maxZ >> 4; cz++) {
							for (Hit h : hits.getOrDefault(ChunkPos.pack(cx, cz), List.of())) if (in(f, h.pos()) && level.getBlockState(h.pos()).getBlock() == h.state().getBlock()) grow(b, h.pos());
						}
					}
				} else { // a single block entity
					BlockPos p = new BlockPos(f.x, f.y, f.z);
					boolean there = f.kind == Kind.SIGN ? level.getBlockEntity(p) instanceof SignBlockEntity : BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath().equals(f.key);
					if (there) grow(b, p);
				}
			}
			default -> {
				return stored;
			}
		}
		return b[0] == Integer.MAX_VALUE ? null : b;
	}

	private static int boxes() {
		Finds.Store s = Finds.live;
		LocalPlayer me = mc.player;
		if (s == null || me == null || level == null || !Settings.SCAN.on() || !Settings.SCAN_HIGHLIGHT.on()) return 0;
		double range = Settings.SCAN_HIGHLIGHT_RANGE.d(), r2 = range * range;
		int drawn = 0;
		List<BlockPos> members = new ArrayList<>();
		for (Find f : s.list) {
			if (f.checked || f.kind == Kind.PLAYER || f.kind == Kind.NOTE && !f.hasY() || f.dist2(me.getX(), me.getZ()) > r2) continue;
			int rgb = f.kind.color & 0xFFFFFF;
			int[] b = live(f, members); // a find stays in the log after you broke its blocks, but it is only drawn where something is still there
			if (b == null) continue;
			if (f.kind == Kind.STASH) {
				top(Gizmos.cuboid(new AABB(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1).inflate(0.15), GizmoStyle.stroke(0xFF000000 | rgb, 1.5f)));
				drawn++;
			} else if (f.hasY()) {
				top(Gizmos.cuboid(new AABB(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1).inflate(0.02), GizmoStyle.strokeAndFill(0xFF000000 | rgb, 2.5f, 0x30000000 | rgb)));
				drawn++;
			}
			if (drawn > 200) return drawn;
		}
		if (members.size() > 300) { // a huge stash: the 300 containers nearest to you
			double px = me.getX(), py = me.getY(), pz = me.getZ();
			members.sort((a, b) -> Double.compare(a.distToCenterSqr(px, py, pz), b.distToCenterSqr(px, py, pz)));
			members = members.subList(0, 300);
		}
		int rgb = Kind.STASH.color & 0xFFFFFF;
		for (BlockPos q : members) top(Gizmos.cuboid(q, 0.02f, GizmoStyle.strokeAndFill(0xFF000000 | rgb, 2.5f, 0x30000000 | rgb)));
		return drawn + members.size();
	}

	// ============================================================= for the pages

	/** Travels next to a find. */
	public static void goTo(Find f) {
		Goal g = f.hasY() ? new Goal.Near(f.x, f.y, f.z, f.kind == Kind.STASH ? 4 : 2.5) : new Goal.Column(f.x, f.z);
		Commands.start(new GotoTask(g));
	}

	/** A marker of your own. {@code y} may be {@link Find#NO_Y}. */
	public static Find mark(String name, int x, int y, int z) {
		Finds.Store s = Finds.live;
		if (s == null) return null;
		Find f = new Find();
		f.kind = Kind.NOTE;
		fill(f, new BlockPos(x, y == Find.NO_Y ? 0 : y, z), name, "", 1, 0, x, y, z, x, y, z);
		f.y = y;
		s.add(f);
		return f;
	}

	/** The text commands: {@code log ...}. */
	public static void command(String[] a) {
		Finds.Store s = Finds.live;
		if (s == null) {
			Log.warn("Not in a world.");
			return;
		}
		String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "list", "ls" -> {
				int n = 0, max = a.length > 1 && a[1].matches("\\d+") ? Integer.parseInt(a[1]) : 12;
				List<Find> sorted = new ArrayList<>(s.list);
				sorted.sort((p, q) -> Long.compare(q.last, p.last));
				Log.say(Finds.worldName() + " / " + Finds.prettyDim(s.dim) + ": " + s.list.size() + " finds.");
				for (Find f : sorted) {
					if (n++ >= max) break;
					Log.say("#" + f.id + "  " + f.kind.label + "  " + f.label + "  " + f.where() + "  " + Find.ago(f.last));
				}
			}
			case "goto", "go" -> {
				Find f = a.length > 1 && a[1].matches("\\d+") ? s.byId(Integer.parseInt(a[1])) : null;
				if (f == null) Log.warn("No such find. Usage: log goto <number>   (see log list)");
				else {
					Log.say("Going to #" + f.id + " " + f.label + " at " + f.where() + ".");
					goTo(f);
				}
			}
			case "check" -> {
				Find f = a.length > 1 && a[1].matches("\\d+") ? s.byId(Integer.parseInt(a[1])) : null;
				if (f == null) Log.warn("Usage: log check <number>");
				else {
					f.checked = !f.checked;
					s.touch();
				}
			}
			case "clear" -> {
				int mask = ~0;
				if (a.length > 1) {
					mask = 0;
					for (Kind k : Kind.values()) if (k.name().equalsIgnoreCase(a[1])) mask = k.bit();
					if (mask == 0) {
						Log.warn("Unknown kind. Kinds: " + Arrays.stream(Kind.values()).map(k -> k.name().toLowerCase(Locale.ROOT)).toList());
						return;
					}
				}
				Log.say("Removed " + s.clear(mask) + " finds.");
			}
			case "scan" -> {
				scanNow();
				Log.say("Looking at everything that is loaded...");
			}
			case "mark" -> {
				LocalPlayer me = mc.player;
				String name = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "Marker";
				if (me != null) {
					Find f = mark(name, me.getBlockX(), me.getBlockY(), me.getBlockZ());
					Log.say("Marked #" + f.id + " " + name + " at " + f.where() + ".");
				}
			}
			default -> Log.warn("Usage: log [list [n]] | goto <n> | check <n> | clear [kind] | scan | mark <name>");
		}
	}
}
