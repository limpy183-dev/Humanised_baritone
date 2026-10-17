package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.gui.W;
import com.humanisedbaritone.scan.Atlas;
import com.humanisedbaritone.scan.Find;
import com.humanisedbaritone.scan.Find.Kind;
import com.humanisedbaritone.scan.Finds;
import com.humanisedbaritone.scan.Radar;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.settings.Settings.UiStyle;
import com.humanisedbaritone.task.GotoTask;
import com.humanisedbaritone.task.Tasks;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.MapColor;

/**
 * The scanner, the Logging page and the Map page: what a chest ESP or a block search would see is found, grouped, alerted in the chat,
 * remembered per world and dimension, and drawn on the map. HB_TEST=scan; HB_ONLY=stash,search,... picks scenarios (later ones need earlier ones' finds).
 */
public class ScanGameTest extends BotTest {
	@Override
	protected void shot(ClientGameTestContext ctx, String name) {
		ctx.takeScreenshot(net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions.of(name).withSize(1600, 900));
	}

	private static void put(TestSingleplayerContext sp, String block, int x, int y, int z) {
		cmd(sp, "setblock " + x + " " + y + " " + z + " " + block);
	}

	private static Object field(Object o, String name) {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(o);
			} catch (NoSuchFieldException ignored) {
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
		throw new RuntimeException("no field " + name + " in " + o.getClass());
	}

	private static Field staticField(String cls, String name) {
		try {
			Field f = Class.forName("com.humanisedbaritone.gui." + cls).getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static List<String> chat(Minecraft mc) {
		try {
			Field f = ChatComponent.class.getDeclaredField("allMessages");
			f.setAccessible(true);
			List<String> out = new ArrayList<>();
			for (Object o : (List<?>) f.get(mc.gui.hud.getChat())) out.add(((GuiMessage) o).content().getString());
			return out;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static List<Find> of(Kind k) {
		List<Find> out = new ArrayList<>();
		if (Finds.live != null) for (Find f : Finds.live.list) if (f.kind == k) out.add(f);
		return out;
	}

	private static Find nearTo(Kind k, int x, int z, int range) {
		for (Find f : of(k)) if (Math.abs(f.x - x) <= range && Math.abs(f.z - z) <= range) return f;
		return null;
	}

	private static boolean has(List<String> lines, String... parts) {
		for (String l : lines) {
			boolean all = true;
			for (String p : parts) all &= l.contains(p);
			if (all) return true;
		}
		return false;
	}

	private static String show(Find f) {
		return f == null ? "none" : f.kind + " '" + f.label + "' [" + f.detail + "] at " + f.where() + " count=" + f.count;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1600, 900);
			ctx.waitTicks(40);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful", "gamerule spawn_mobs false");
			revive(ctx, sp);
			ctx.runOnClient(mc -> {
				Settings.FEEDBACK.set(false);
				Log.clear();
			});
			load(ctx, sp, -96, -96, 96, 96);
			cmds(sp, "tp @a 0.5 -60 0.5");
			ctx.waitTicks(60);

			if (only("group")) groups();
			if (only("stash")) stash(ctx, sp);
			if (only("highlight")) highlight(ctx, sp);
			if (only("threshold")) threshold(ctx, sp);
			if (only("area")) area(ctx, sp);
			if (only("search")) search(ctx, sp);
			if (only("misc")) misc(ctx, sp);
			if (only("player")) player(ctx);
			if (only("death")) death(ctx, sp);
			if (only("map")) map(ctx, sp);
			if (only("persist")) persist(ctx);
			if (only("gui")) gui(ctx);
			if (only("go")) go(ctx);
			if (only("dimension")) dimension(ctx, sp);
			if (only("view")) view(ctx);
			System.out.println("[HBTEST] radar: worst tick " + Radar.worstNanos / 1000 + " us, longest step \"" + Radar.worstStep + "\" " + Radar.worstStepNanos / 1000 + " us");
			finish();
		}
	}

	// ------------------------------------------------------------------ containers

	private void stash(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// the scanner is off while the containers are placed, then switched on: one look at everything, so one alert with the full count
		ctx.runOnClient(mc -> Settings.SCAN.set(false));
		for (int x = 30; x <= 36; x += 2) for (int z = 30; z <= 34; z += 2) put(sp, "chest", x, -60, z);
		for (int x = 30; x <= 34; x += 2) put(sp, "barrel", x, -60, 36);
		put(sp, "red_shulker_box", 36, -60, 36);
		put(sp, "shulker_box", 38, -60, 36);
		ctx.waitTicks(30);
		ctx.runOnClient(mc -> {
			Log.clear();
			Settings.SCAN.set(true);
		});
		ctx.waitTicks(100);
		Find a = client(ctx, mc -> nearTo(Kind.STASH, 34, 33, 8));
		report("stash-found", a != null && a.count == 17, show(a));
		report("stash-detail", a != null && a.detail.equals("12 Chest, 3 Barrel, 2 Shulker Box"), a == null ? "" : a.detail);
		report("stash-one-find", client(ctx, mc -> of(Kind.STASH).size()) == 1, "stashes=" + client(ctx, mc -> of(Kind.STASH).size()));
		report("stash-console-alert", client(ctx, mc -> has(Log.lines(), "Stash: 17 containers (12 Chest, 3 Barrel, 2 Shulker Box) within 16 blocks at")), "");
		List<String> c = client(ctx, mc -> chat(mc));
		report("stash-chat-alert", has(c, "[HB] Stash: 17 containers", "blocks away"), c.size() + " chat lines: " + (c.isEmpty() ? "" : c.get(c.size() - 1)));
		// a second look at the same stash must not alert again
		ctx.runOnClient(mc -> Radar.scanNow());
		ctx.waitTicks(100);
		long alerts = client(ctx, mc -> chat(mc).stream().filter(l -> l.contains("Stash:")).count());
		report("stash-no-repeat", alerts == 1, "alerts=" + alerts);
		// breaking one container is a smaller stash, not a new one
		put(sp, "air", 30, -60, 30);
		ctx.waitTicks(120);
		Find b = client(ctx, mc -> nearTo(Kind.STASH, 34, 33, 8));
		report("stash-shrinks", b != null && b.count == 16 && client(ctx, mc -> of(Kind.STASH).size()) == 1, show(b));
		put(sp, "chest", 30, -60, 30);
		ctx.waitTicks(120);
		report("stash-regrows-silently", client(ctx, mc -> nearTo(Kind.STASH, 34, 33, 8).count) == 17 && client(ctx, mc -> chat(mc).stream().filter(l -> l.contains("Stash:")).count()) == 1, "");
		// a double chest is two block entities and counts once
		put(sp, "chest[type=left,facing=north]", 40, -60, 32);
		put(sp, "chest[type=right,facing=north]", 41, -60, 32);
		ctx.waitTicks(100);
		report("double-chest-counts-once", client(ctx, mc -> Radar.containersLoaded()) == 17 + 2 && client(ctx, mc -> nearTo(Kind.STASH, 34, 33, 8).count) == 18,
			"block entities in view=" + client(ctx, mc -> Radar.containersLoaded()) + ", the stash counts " + client(ctx, mc -> nearTo(Kind.STASH, 34, 33, 8).count));
		cmds(sp, "fill 40 -60 32 41 -60 32 air");
		ctx.waitTicks(100);
		report("stash-back-to-17", client(ctx, mc -> nearTo(Kind.STASH, 34, 33, 8).count) == 17, "");
	}

	/** The grouping itself, with made-up points: right groups, nothing twice, and a stash of tens of thousands of containers costs little. */
	private void groups() {
		BlockPos[] p = new BlockPos[12 + 12 + 5];
		int k = 0;
		for (int i = 0; i < 12; i++) p[k++] = new BlockPos(i % 4 * 3, 0, i / 4 * 3);
		for (int i = 0; i < 12; i++) p[k++] = new BlockPos(100 + i % 4 * 3, 5, i / 4 * 3);
		for (int i = 0; i < 5; i++) p[k++] = new BlockPos(300 + i * 2, 0, 300);
		int[] w = new int[p.length];
		java.util.Arrays.fill(w, 1);
		List<int[]> g = Radar.group(p, w, 16, 10);
		report("group-blobs", g.size() == 2 && g.get(0).length == 12 && g.get(1).length == 12, g.size() + " groups of " + g.stream().map(a -> a.length).toList() + " (the third blob has 5 points, under the minimum of 10)");
		g = Radar.group(p, w, 16, 1);
		boolean[] seen = new boolean[p.length];
		int twice = 0, total = 0;
		for (int[] a : g) {
			for (int i : a) {
				if (seen[i]) twice++;
				seen[i] = true;
				total++;
			}
		}
		report("group-every-point-once", twice == 0 && total == p.length && g.size() == 3, g.size() + " groups, " + total + " of " + p.length + " points, " + twice + " twice");
		java.util.Random rnd = new java.util.Random(1);
		BlockPos[] big = new BlockPos[30000];
		for (int i = 0; i < big.length; i++) big[i] = new BlockPos(rnd.nextInt(300), rnd.nextInt(40), rnd.nextInt(300));
		int[] bw = new int[big.length];
		java.util.Arrays.fill(bw, 1);
		long t0 = System.nanoTime();
		List<int[]> bg = Radar.group(big, bw, 16, 10);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		report("group-30000-points", ms < 400 && !bg.isEmpty(), "30000 containers in 300 x 40 x 300 blocks: " + bg.size() + " groups in " + ms + " ms");
	}

	/** The boxes in the world: every container of the stash, and none once the find is marked as dealt with or the setting is off. */
	private void highlight(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		cmds(sp, "tp @a 22.5 -60 24.5");
		ctx.waitTicks(30);
		ctx.getInput().lookAt(new BlockPos(34, -60, 33));
		ctx.waitTicks(10);
		int on = client(ctx, mc -> Radar.highlighted);
		shot(ctx, "scan-world-highlight");
		report("highlight-draws-the-stash", on >= 17, "boxes drawn: " + on);
		// broken containers lose their box at once (the find stays in the log), and so does a find with nothing left
		put(sp, "air", 30, -60, 30);
		put(sp, "air", 32, -60, 30);
		ctx.waitTicks(10);
		int less = client(ctx, mc -> Radar.highlighted);
		report("highlight-drops-broken", less == on - 2, "before " + on + ", after breaking 2 chests " + less);
		put(sp, "chest", 30, -60, 30);
		put(sp, "chest", 32, -60, 30);
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> nearTo(Kind.STASH, 34, 33, 8).checked = true);
		ctx.waitTicks(5);
		report("highlight-skips-done", client(ctx, mc -> Radar.highlighted) == 0, "");
		ctx.runOnClient(mc -> {
			nearTo(Kind.STASH, 34, 33, 8).checked = false;
			Settings.SCAN_HIGHLIGHT.set(false);
		});
		ctx.waitTicks(5);
		report("highlight-setting-off", client(ctx, mc -> Radar.highlighted) == 0, "");
		shot(ctx, "scan-world-nohighlight");
		ctx.runOnClient(mc -> Settings.SCAN_HIGHLIGHT.set(true));
		cmds(sp, "tp @a 0.5 -60 0.5");
		ctx.waitTicks(40);
	}

	private void threshold(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		for (int z = 30; z <= 38; z += 2) put(sp, "chest", -40, -60, z);
		ctx.waitTicks(100);
		report("threshold-below", client(ctx, mc -> nearTo(Kind.STASH, -40, 34, 12)) == null, "5 chests with a minimum of 8 are not a stash");
		ctx.runOnClient(mc -> {
			Log.clear();
			Settings.STASH_MIN.set(5.0);
		});
		ctx.waitTicks(100);
		Find f = client(ctx, mc -> nearTo(Kind.STASH, -40, 34, 12));
		report("threshold-lowered", f != null && f.count == 5, show(f));
		report("threshold-alert", client(ctx, mc -> has(Log.lines(), "Stash: 5 containers")), "");
		ctx.runOnClient(mc -> Settings.STASH_MIN.reset());
		ctx.waitTicks(60);
		report("threshold-raised-keeps-history", client(ctx, mc -> nearTo(Kind.STASH, -40, 34, 12)) != null, "a find is never dropped because the setting changed");
	}

	private void area(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ctx.runOnClient(mc -> Settings.STASH_MIN.set(5.0));
		for (int x = 0; x <= 8; x += 2) put(sp, "chest", x, -60, -40);
		for (int x = 30; x <= 38; x += 2) put(sp, "chest", x, -60, -40);
		ctx.waitTicks(120);
		int separate = client(ctx, mc -> {
			int n = 0;
			for (Find f : of(Kind.STASH)) if (f.z < -30) n++;
			return n;
		});
		report("area-separate", separate == 2, "two groups 22 blocks apart with an area of 16: " + separate);
		ctx.runOnClient(mc -> Settings.STASH_AREA.set(40.0));
		ctx.waitTicks(120);
		int merged = client(ctx, mc -> {
			int n = 0;
			for (Find f : of(Kind.STASH)) if (f.z < -30) n++;
			return n;
		});
		Find m = client(ctx, mc -> nearTo(Kind.STASH, 19, -40, 25));
		report("area-merged", merged == 1 && m != null && m.count == 10, "an area of 40 joins them: " + merged + " " + show(m));
		ctx.runOnClient(mc -> {
			Settings.STASH_AREA.reset();
			Settings.STASH_MIN.reset();
		});
		ctx.waitTicks(60);
	}

	// ----------------------------------------------------------------- block search

	private void search(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		put(sp, "diamond_ore", 50, -60, 0);
		put(sp, "diamond_ore", 51, -60, 0);
		put(sp, "diamond_ore", 50, -59, 0);
		put(sp, "ancient_debris", -50, -60, 0);
		cmds(sp, "fill 60 -61 10 62 -59 12 stone");
		put(sp, "diamond_ore", 61, -60, 11);
		ctx.waitTicks(30);
		report("search-off-by-default", client(ctx, mc -> of(Kind.SEARCH).isEmpty()), "");
		ctx.runOnClient(mc -> {
			Log.clear();
			Settings.SEARCH.set(true);
		});
		ctx.waitTicks(100);
		Find vein = client(ctx, mc -> nearTo(Kind.SEARCH, 50, 0, 3));
		Find debris = client(ctx, mc -> nearTo(Kind.SEARCH, -50, 0, 3));
		Find hidden = client(ctx, mc -> nearTo(Kind.SEARCH, 61, 11, 2));
		report("search-vein", vein != null && vein.count == 3 && vein.label.equals("3 Diamond Ore"), show(vein));
		report("search-debris", debris != null && debris.count == 1 && debris.label.equals("1 Ancient Debris"), show(debris));
		report("search-enclosed-found", hidden != null, show(hidden));
		report("search-alert", client(ctx, mc -> has(Log.lines(), "Search: 3 Diamond Ore at")), "");
		int boxes = client(ctx, mc -> Radar.highlighted);
		put(sp, "air", 50, -60, 0);
		put(sp, "air", 51, -60, 0);
		ctx.waitTicks(5);
		int one = client(ctx, mc -> Radar.highlighted);
		put(sp, "air", 50, -59, 0);
		ctx.waitTicks(5);
		int none = client(ctx, mc -> Radar.highlighted);
		report("search-highlight-follows-blocks", one == boxes && none == boxes - 1, "boxes " + boxes + ", with one ore left " + one + ", with none " + none);
		put(sp, "diamond_ore", 50, -60, 0);
		put(sp, "diamond_ore", 51, -60, 0);
		put(sp, "diamond_ore", 50, -59, 0);
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			Finds.live.clear(Kind.SEARCH.bit());
			Settings.SEARCH_EXPOSED.set(true);
			Radar.scanNow();
		});
		ctx.waitTicks(100);
		report("search-exposed-only", client(ctx, mc -> nearTo(Kind.SEARCH, 61, 11, 2)) == null && client(ctx, mc -> nearTo(Kind.SEARCH, 50, 0, 3)) != null,
			"the ore inside the stone is skipped, the open ones are not: " + client(ctx, mc -> of(Kind.SEARCH).size()) + " finds");
		ctx.runOnClient(mc -> {
			Settings.SEARCH_EXPOSED.set(false);
			Settings.SEARCH.set(false);
		});
	}

	// ----------------------------------------------------------------------- misc

	private void misc(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ctx.runOnClient(mc -> {
			Log.clear();
			Settings.SIGNS.set(true);
		});
		put(sp, "spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}", 20, -60, -20);
		put(sp, "trial_spawner", 22, -60, -20);
		put(sp, "vault", 24, -60, -20);
		for (int x = -60; x <= -52; x += 4) put(sp, "spawner{SpawnData:{entity:{id:\"minecraft:cave_spider\"}}}", x, -60, -60);
		put(sp, "trial_spawner", -70, -60, 60);
		put(sp, "vault", -66, -60, 60);
		put(sp, "beacon", 0, -60, -20);
		cmds(sp, "fill -10 -60 -20 -7 -56 -20 obsidian", "fill -9 -59 -20 -8 -57 -20 nether_portal[axis=x]");
		for (int x = -20; x <= -18; x++) put(sp, "end_portal_frame[eye=true,facing=north]", x, -60, -20);
		put(sp, "end_portal", -30, -60, -20);
		put(sp, "stone", 5, -59, -29);
		cmd(sp, "summon item_frame 5 -59 -30 {Facing:2b,Item:{id:\"minecraft:elytra\",count:1}}");
		cmd(sp, "summon item 8 -60 -30 {Item:{id:\"minecraft:diamond_block\",count:2},PickupDelay:32767s,Age:-32768s}");
		put(sp, "oak_sign[rotation=0]{front_text:{messages:[\"Hello\",\"base 100 64\",\"\",\"\"]}}", 40, -60, -20);
		ctx.waitTicks(220); // blocks that appear later are found on the next pass over their chunk, which comes every few seconds
		Find z = client(ctx, mc -> nearTo(Kind.SPAWNER, 20, -20, 0));
		report("spawner", z != null && z.label.equals("Zombie spawner") && z.count == 1, show(z));
		Find tc = client(ctx, mc -> nearTo(Kind.SPAWNER, 23, -20, 2));
		report("trial-chamber-is-one-find", tc != null && tc.label.equals("Trial chamber") && tc.count == 2 && tc.detail.equals("1 trial spawner, 1 vault"), show(tc));
		Find cs = client(ctx, mc -> nearTo(Kind.SPAWNER, -56, -60, 6));
		report("same-spawners-are-one-find", cs != null && cs.label.equals("3 Cave Spider spawners"), show(cs));
		Find tc2 = client(ctx, mc -> nearTo(Kind.SPAWNER, -68, 60, 4));
		report("far-chamber-is-another", tc2 != null && tc2.label.equals("Trial chamber") && tc2.count == 2 && client(ctx, mc -> of(Kind.SPAWNER).size()) == 4, client(ctx, mc -> of(Kind.SPAWNER).size()) + " spawner finds in all");
		report("beacon", client(ctx, mc -> nearTo(Kind.BASE, 0, -20, 0)) != null, show(client(ctx, mc -> nearTo(Kind.BASE, 0, -20, 0))));
		Find np = client(ctx, mc -> nearTo(Kind.PORTAL, -8, -20, 3));
		report("nether-portal", np != null && np.label.equals("Nether portal") && np.count == 6, show(np));
		Find ep = client(ctx, mc -> nearTo(Kind.PORTAL, -19, -20, 3));
		report("end-portal-frames", ep != null && ep.count == 3 && ep.detail.startsWith("3 of 3"), show(ep));
		report("end-portal", client(ctx, mc -> nearTo(Kind.PORTAL, -30, -20, 0)) != null, show(client(ctx, mc -> nearTo(Kind.PORTAL, -30, -20, 0))));
		Find el = client(ctx, mc -> nearTo(Kind.ITEM, 5, -30, 1));
		report("item-frame-elytra", el != null && el.label.equals("Elytra") && el.detail.contains("item frame"), show(el));
		Find dr = client(ctx, mc -> nearTo(Kind.ITEM, 8, -30, 1));
		report("dropped-diamond-block", dr != null && dr.label.contains("Block of Diamond") && dr.count == 2, show(dr));
		Find sg = client(ctx, mc -> nearTo(Kind.SIGN, 40, -20, 0));
		report("sign-text", sg != null && sg.label.equals("Hello / base 100 64"), show(sg));
		List<String> c = client(ctx, mc -> chat(mc));
		report("misc-chat-alerts", has(c, "Spawner: Zombie spawner") && has(c, "Spawner: Trial chamber") && has(c, "Spawner: 3 Cave Spider spawners") && has(c, "Portal:") && has(c, "Valuable item:", "Elytra") && has(c, "Base marker:", "Beacon") && !has(c, "Sign:"), "signs are logged quietly: " + c.size() + " chat lines");
		ctx.runOnClient(mc -> Settings.SIGNS.set(false));
	}

	private void player(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			Log.clear();
			RemotePlayer p = new RemotePlayer(mc.level, new GameProfile(UUID.nameUUIDFromBytes("hb-scan-test".getBytes()), "TestSteve"));
			p.setId(1_000_007); // the server hands out ids; this one is ours
			p.setPos(12, -60, 12);
			mc.level.addEntity(p);
		});
		ctx.waitTicks(60);
		Find f = client(ctx, mc -> of(Kind.PLAYER).stream().filter(x -> x.label.equals("TestSteve")).findFirst().orElse(null));
		report("player-seen", f != null && f.detail.equals("empty-handed, armor 0/4") && f.seen == 1, show(f));
		report("player-chat", client(ctx, mc -> has(chat(mc), "Player: TestSteve came into view")), "");
		report("player-listed-once", client(ctx, mc -> of(Kind.PLAYER).size()) == 1, "");
		// still in view: no second alert; out of view for a while and back: one more
		ctx.waitTicks(60);
		report("player-no-repeat", client(ctx, mc -> chat(mc).stream().filter(l -> l.contains("TestSteve")).count()) == 1, "");
		ctx.runOnClient(mc -> {
			for (Find x : of(Kind.PLAYER)) x.last -= 600_000;
		});
		ctx.waitTicks(40);
		Find g = client(ctx, mc -> of(Kind.PLAYER).get(0));
		report("player-back", g.seen == 2 && client(ctx, mc -> has(chat(mc), "TestSteve is back in view")), "seen=" + g.seen);
		ctx.runOnClient(mc -> {
			for (var e : new ArrayList<>(mc.level.players())) if (e instanceof RemotePlayer) e.discard();
		});
	}

	private void death(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		cmds(sp, "tp @a 3.5 -60 3.5");
		ctx.waitTicks(20);
		cmd(sp, "kill @p");
		until(ctx, "death find", mc -> !of(Kind.DEATH).isEmpty(), 100);
		Find d = client(ctx, mc -> of(Kind.DEATH).isEmpty() ? null : of(Kind.DEATH).get(0));
		report("death-logged", d != null && d.x == 3 && d.z == 3 && d.label.equals("You died"), show(d));
		revive(ctx, sp);
		cmds(sp, "tp @a 0.5 -60 0.5");
		ctx.waitTicks(40);
		report("death-once", client(ctx, mc -> of(Kind.DEATH).size()) == 1, "");
	}

	// -------------------------------------------------------------------------- map

	private void map(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ctx.waitTicks(40);
		int[] r = client(ctx, mc -> {
			Atlas.Tile t = Finds.live.atlas.get(0, 0, false);
			if (t == null) return new int[]{0, 0, 0};
			BlockPos p = new BlockPos(1, -61, 1);
			int want = mc.level.getBlockState(p).getMapColor(mc.level, p).calculateARGBColor(MapColor.Brightness.NORMAL);
			return new int[]{1, t.pixel(1, 1), want};
		});
		report("map-painted", r[0] == 1 && r[1] == r[2] && r[1] != 0, String.format("pixel %08x, the ground's map colour %08x", r[1], r[2]));
		report("map-tiles", client(ctx, mc -> Finds.live.atlas.size()) >= 4, "tiles=" + client(ctx, mc -> Finds.live.atlas.size()));
		// a new block shows up within a few seconds
		put(sp, "obsidian", 3, -60, 3);
		ctx.waitTicks(200);
		int[] o = client(ctx, mc -> {
			Atlas.Tile t = Finds.live.atlas.get(0, 0, false);
			return new int[]{t.pixel(3, 3), MapColor.COLOR_BLACK.calculateARGBColor(MapColor.Brightness.NORMAL), MapColor.COLOR_BLACK.calculateARGBColor(MapColor.Brightness.HIGH), MapColor.COLOR_BLACK.calculateARGBColor(MapColor.Brightness.LOW)};
		});
		report("map-follows-the-world", o[0] == o[1] || o[0] == o[2] || o[0] == o[3], String.format("pixel %08x", o[0]));
		// a column of water gets the water colour
		cmds(sp, "fill 6 -61 6 8 -61 8 water");
		ctx.waitTicks(200);
		int w = client(ctx, mc -> Finds.live.atlas.get(0, 0, false).pixel(7, 7));
		report("map-water", ((w >> 16 & 255) < 100) && ((w & 255) > 150), String.format("pixel %08x", w));
		cmds(sp, "fill 6 -61 6 8 -61 8 grass_block", "setblock 3 -60 3 air");
		// saved to disk
		ctx.runOnClient(mc -> Finds.flush());
		Path tile = client(ctx, mc -> Finds.live.dir.resolve("tiles").resolve("0_0.png"));
		boolean ok = Files.exists(tile);
		int back = 0;
		if (ok) {
			try (var in = Files.newInputStream(tile)) {
				NativeImage img = NativeImage.read(in);
				back = img.getPixel(1, 1);
				img.close();
			} catch (java.io.IOException e) {
				ok = false;
			}
		}
		report("map-saved", ok && back == r[1], tile + String.format(" pixel %08x", back));
	}

	// ------------------------------------------------------------------ persistence

	private void persist(ClientGameTestContext ctx) {
		int before = client(ctx, mc -> Finds.live.list.size());
		List<String> keep = client(ctx, mc -> {
			List<String> l = new ArrayList<>();
			for (Find f : Finds.live.list) l.add(f.id + "|" + f.kind + "|" + f.label + "|" + f.x + "," + f.y + "," + f.z + "|" + f.count);
			return l;
		});
		Path dir = client(ctx, mc -> Finds.live.dir);
		ctx.runOnClient(mc -> Finds.flush());
		report("persist-file", Files.exists(dir.resolve("finds.json")), dir.toString());
		int stashAlertsBefore = client(ctx, mc -> (int) chat(mc).stream().filter(l -> l.contains("Stash:")).count());
		// leave and come back: what is on disk is what the next visit starts with
		ctx.runOnClient(mc -> Radar.onDisconnect());
		ctx.waitTicks(10);
		until(ctx, "store reloaded", mc -> Finds.live != null, 40);
		ctx.waitTicks(120);
		List<String> now = client(ctx, mc -> {
			List<String> l = new ArrayList<>();
			if (Finds.live != null) for (Find f : Finds.live.list) l.add(f.id + "|" + f.kind + "|" + f.label + "|" + f.x + "," + f.y + "," + f.z + "|" + f.count);
			return l;
		});
		boolean same = now.containsAll(keep);
		report("persist-reloaded", same && now.size() <= before + 2, before + " finds before, " + now.size() + " after; missing: " + keep.stream().filter(s -> !now.contains(s)).toList());
		int stashAlertsAfter = client(ctx, mc -> (int) chat(mc).stream().filter(l -> l.contains("Stash:")).count());
		report("persist-no-new-alerts", stashAlertsAfter == stashAlertsBefore, "stash alerts " + stashAlertsBefore + " -> " + stashAlertsAfter);
		report("persist-world-listed", client(ctx, mc -> Finds.worlds().size()) == 1 && client(ctx, mc -> Finds.dims(Finds.worlds().get(0)).contains("minecraft:overworld")), client(ctx, mc -> Finds.worlds() + " " + Finds.dims(Finds.worlds().get(0))));
	}

	// -------------------------------------------------------------------------- gui

	private int[] px(Minecraft mc, BotScreen scr, double ux, double uy) {
		float s = (float) field(scr, "s");
		double gs = mc.getWindow().getGuiScale();
		return new int[]{(int) Math.round(ux * s * gs), (int) Math.round(uy * s * gs)};
	}

	private W row(Minecraft mc, String cls) {
		for (W r : ((W.ScrollArea) field(mc.gui.screen(), "content")).rows) if (r.getClass().getSimpleName().equals(cls) || r.getClass().getName().endsWith("$" + cls)) return r;
		return null;
	}

	private void gui(ClientGameTestContext ctx) {
		UiStyle was = Settings.UI_STYLE.get();
		for (UiStyle st : UiStyle.values()) {
			String n = st.name().toLowerCase();
			ctx.runOnClient(mc -> {
				Settings.UI_STYLE.set(st);
				mc.gui.setScreen(new BotScreen());
			});
			ctx.waitTicks(40);
			for (String tab : new String[]{"Logging", "Map"}) {
				ctx.runOnClient(mc -> ((BotScreen) mc.gui.screen()).showTab(tab));
				ctx.waitTicks(40);
				shot(ctx, "scan-" + n + "-" + tab.toLowerCase());
				report("gui-" + n + "-" + tab.toLowerCase(), client(ctx, mc -> mc.gui.screen() instanceof BotScreen), "the panel stays open (a draw error closes it)");
			}
		}
		ctx.runOnClient(mc -> Settings.UI_STYLE.set(was));
		// the scanner's settings sit below the list: where the thresholds are
		ctx.runOnClient(mc -> {
			Settings.UI_STYLE.set(UiStyle.MINECRAFT);
			mc.gui.setScreen(new BotScreen());
			((BotScreen) mc.gui.screen()).showTab("Logging");
		});
		ctx.waitTicks(25);
		for (int[] at : new int[][]{{250}, {520}, {9999}}) {
			ctx.runOnClient(mc -> {
				try {
					Field tg = W.ScrollArea.class.getDeclaredField("target");
					tg.setAccessible(true);
					tg.setFloat(field(mc.gui.screen(), "content"), at[0]);
				} catch (ReflectiveOperationException e) {
					throw new RuntimeException(e);
				}
			});
			ctx.waitTicks(25);
			shot(ctx, "scan-logging-settings-" + at[0]);
		}
		// real mouse on the map
		ctx.runOnClient(mc -> {
			Settings.UI_STYLE.set(UiStyle.MINECRAFT);
			mc.gui.setScreen(new BotScreen());
			((BotScreen) mc.gui.screen()).showTab("Map");
		});
		ctx.waitTicks(30);
		Field zoom = staticField("MapPage", "zoom"), follow = staticField("MapPage", "follow"), cx = staticField("MapPage", "cx");
		try {
			int z0 = zoom.getInt(null);
			double cx0 = cx.getDouble(null);
			int[] mid = client(ctx, mc -> {
				W m = row(mc, "MapPage");
				return px(mc, (BotScreen) mc.gui.screen(), m.x + m.w / 2.0, m.y + m.h / 2.0);
			});
			report("gui-map-follows-you", follow.getBoolean(null), "");
			ctx.getInput().setCursorPos(mid[0], mid[1]);
			ctx.waitTicks(3);
			ctx.getInput().scroll(1);
			ctx.waitTicks(5);
			report("gui-map-zoom", zoom.getInt(null) == z0 + 1, "zoom index " + z0 + " -> " + zoom.getInt(null));
			ctx.getInput().holdMouse(0);
			for (int i = 0; i < 6; i++) {
				ctx.getInput().moveCursor(10, 4);
				ctx.waitTicks(2);
			}
			ctx.getInput().releaseMouse(0);
			ctx.waitTicks(5);
			report("gui-map-drag", !follow.getBoolean(null) && cx.getDouble(null) < cx0, "centre x " + cx0 + " -> " + cx.getDouble(null) + " (dragging right moves the map right, so the centre goes west)");
			shot(ctx, "scan-map-dragged");
			// right-click drops a pin, the info bar offers Go/Mark
			ctx.getInput().setCursorPos(mid[0] + 40, mid[1] + 20);
			ctx.waitTicks(3);
			ctx.getInput().pressMouse(1);
			ctx.waitTicks(5);
			Field pin = MapPageClass().getDeclaredField("hasPin");
			pin.setAccessible(true);
			report("gui-map-pin", client(ctx, mc -> {
				try {
					return pin.getBoolean(row(mc, "MapPage"));
				} catch (IllegalAccessException e) {
					throw new RuntimeException(e);
				}
			}), "");
			shot(ctx, "scan-map-pin");
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		// a row of the list: select, then the Map button switches to the map centred on it
		ctx.runOnClient(mc -> {
			((BotScreen) mc.gui.screen()).showTab("Logging");
			try {
				Field m = Class.forName("com.humanisedbaritone.gui.LogPage").getDeclaredField("mask");
				m.setAccessible(true);
				m.setInt(null, Kind.STASH.bit());
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		ctx.waitTicks(30);
		int[] mapBtn = client(ctx, mc -> {
			try {
				W l = row(mc, "Listing");
				Method bx = l.getClass().getDeclaredMethod("btnX", int.class);
				bx.setAccessible(true);
				return px(mc, (BotScreen) mc.gui.screen(), (int) bx.invoke(l, 1) + 14, l.y + 4 + 13);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		ctx.getInput().setCursorPos(mapBtn[0], mapBtn[1]);
		ctx.waitTicks(4);
		shot(ctx, "scan-log-row-hot");
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(30);
		String tabNow = client(ctx, mc -> String.valueOf(field(mc.gui.screen(), "tab")));
		report("gui-row-map-button", tabNow.equals("Map"), "tab=" + tabNow);
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
	}

	private static Class<?> MapPageClass() throws ClassNotFoundException {
		return Class.forName("com.humanisedbaritone.gui.MapPage");
	}

	// ------------------------------------------------------------------------ travel

	private void go(ClientGameTestContext ctx) {
		Find f = client(ctx, mc -> nearTo(Kind.STASH, 34, 33, 8));
		if (f == null) {
			report("go", false, "no stash to travel to");
			return;
		}
		ctx.runOnClient(mc -> {
			Settings.FEEDBACK.set(false);
			Radar.goTo(f);
		});
		report("go-starts-travel", client(ctx, mc -> Tasks.current() instanceof GotoTask && Bot.enabled), "");
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> { // the map shows where the bot is going
			mc.gui.setScreen(new BotScreen());
			((BotScreen) mc.gui.screen()).showTab("Map");
		});
		ctx.waitTicks(20);
		shot(ctx, "scan-map-route");
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
		until(ctx, "next to the stash", mc -> Math.sqrt(f.dist2(mc.player.getX(), mc.player.getZ())) <= 6, 1500);
		double d = client(ctx, mc -> Math.sqrt(f.dist2(mc.player.getX(), mc.player.getZ())));
		report("go-arrives", d <= 6, String.format("%.1f blocks from the container at %s", d, f.where()));
		ctx.runOnClient(mc -> com.humanisedbaritone.Commands.run("stop"));
	}

	// ------------------------------------------------------------------- viewing

	/** The buttons at the top of the pages show another dimension of the world without leaving yours, and bring you back. */
	private void view(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.gui.setScreen(new BotScreen());
			((BotScreen) mc.gui.screen()).showTab("Logging");
		});
		ctx.waitTicks(30);
		int[] dim = client(ctx, mc -> viewButton(mc, "dim"));
		ctx.getInput().setCursorPos(dim[0], dim[1]);
		ctx.waitTicks(3);
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(10);
		report("view-other-dimension", client(ctx, mc -> Finds.view != Finds.live && Finds.view.dim.contains("nether") && Finds.live.dim.contains("overworld")),
			client(ctx, mc -> "viewing " + Finds.view.dim + ", in " + Finds.live.dim));
		report("view-has-its-own-finds", client(ctx, mc -> Finds.view.list.size()) == 1 && client(ctx, mc -> Finds.view.list.get(0).kind) == Kind.STASH, "the Nether holds one stash");
		shot(ctx, "scan-view-nether");
		int[] live = client(ctx, mc -> viewButton(mc, "live"));
		ctx.getInput().setCursorPos(live[0], live[1]);
		ctx.waitTicks(3);
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(10);
		report("view-back-to-live", client(ctx, mc -> Finds.view == Finds.live), "");
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
	}

	private int[] viewButton(Minecraft mc, String which) {
		W bar = (W) field(row(mc, "Head"), "view");
		W.Btn b = (W.Btn) field(bar, which);
		return px(mc, (BotScreen) mc.gui.screen(), b.x + b.w / 2.0, b.y + b.h / 2.0);
	}

	// -------------------------------------------------------------------- dimensions

	private void dimension(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int overworld = client(ctx, mc -> Finds.live.list.size());
		String world = client(ctx, mc -> Finds.live.world);
		cmds(sp, "execute in minecraft:the_nether run forceload add 0 0 48 48");
		cmds(sp, "gamemode spectator @a", "execute in minecraft:the_nether run tp @a 20 100 20");
		until(ctx, "in the nether", mc -> mc.level.dimension().identifier().toString().equals("minecraft:the_nether"), 400);
		ctx.waitTicks(100);
		report("dim-store-switched", client(ctx, mc -> Finds.live.dim.equals("minecraft:the_nether") && Finds.live.list.isEmpty()), client(ctx, mc -> Finds.live.dim + " finds=" + Finds.live.list.size()));
		for (int x = 20; x <= 28; x += 2) {
			cmd(sp, "execute in minecraft:the_nether run setblock " + x + " 100 24 minecraft:chest");
			cmd(sp, "execute in minecraft:the_nether run setblock " + x + " 100 28 minecraft:chest");
		}
		ctx.waitTicks(120);
		Find n = client(ctx, mc -> nearTo(Kind.STASH, 24, 26, 8));
		report("dim-nether-stash", n != null && n.count == 10, show(n));
		report("dim-overworld-untouched", client(ctx, mc -> Finds.open(world, Finds.worldName(), "minecraft:overworld").list.size()) == overworld, "overworld still has " + overworld + " finds");
		report("dim-two-dimensions-listed", client(ctx, mc -> Finds.dims(world).size()) == 2, client(ctx, mc -> String.valueOf(Finds.dims(world))));
		int[] floor = client(ctx, mc -> {
			Atlas.Tile t = Finds.live.atlas.get(0, 0, false);
			return new int[]{t == null ? 0 : 1, t == null ? 0 : t.pixel(20, 20)};
		});
		report("dim-nether-map", floor[0] == 1 && floor[1] != 0, String.format("nether map pixel %08x", floor[1]));
		cmds(sp, "execute in minecraft:overworld run tp @a 0.5 -60 0.5");
		until(ctx, "back in the overworld", mc -> mc.level.dimension().identifier().toString().equals("minecraft:overworld"), 400);
		ctx.waitTicks(100);
		report("dim-back", client(ctx, mc -> Finds.live.dim.equals("minecraft:overworld") && Finds.live.list.size() == overworld), client(ctx, mc -> Finds.live.dim + " finds=" + Finds.live.list.size() + " (was " + overworld + ")"));
		cmds(sp, "gamemode survival @a");
	}
}
