package com.humanisedbaritone;

import com.humanisedbaritone.build.Placement;
import com.humanisedbaritone.build.Schematic;
import com.humanisedbaritone.build.Schematics;
import com.humanisedbaritone.build.Selection;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.settings.Profiles;
import com.humanisedbaritone.settings.Setting;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.CollectTask;
import com.humanisedbaritone.task.ExploreTask;
import com.humanisedbaritone.task.FarmTask;
import com.humanisedbaritone.task.FollowTask;
import com.humanisedbaritone.task.GotoTask;
import com.humanisedbaritone.task.GuardTask;
import com.humanisedbaritone.task.HuntTask;
import com.humanisedbaritone.task.MineTask;
import com.humanisedbaritone.task.SleepTask;
import com.humanisedbaritone.task.StashTask;
import com.humanisedbaritone.task.WanderTask;
import com.humanisedbaritone.task.Work;
import com.humanisedbaritone.task.Task;
import com.humanisedbaritone.task.Tasks;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Text commands, shared by the chat prefix and the GUI console. */
public final class Commands {
	private Commands() {
	}

	/** Returns true when the chat message was a bot command and must not be sent to the server. */
	public static boolean interceptChat(String msg) {
		String prefix = Settings.CHAT_PREFIX.get();
		if (!Settings.CHAT_COMMANDS.on() || prefix.isEmpty() || !msg.startsWith(prefix)) return false;
		run(msg.substring(prefix.length()));
		return true;
	}

	public static void run(String line) {
		line = line.trim();
		if (line.isEmpty()) return;
		String[] t = line.split("\\s+");
		String cmd = t[0].toLowerCase(Locale.ROOT);
		String[] a = Arrays.copyOfRange(t, 1, t.length);
		try {
			if (!dispatch(cmd, a)) Log.warn("Unknown command '" + cmd + "'. Type help.");
		} catch (RuntimeException e) {
			Log.warn("Command failed: " + e.getMessage());
			Log.debug(Arrays.toString(e.getStackTrace()));
		}
	}

	private static boolean dispatch(String cmd, String[] a) {
		switch (cmd) {
			case "help", "?" -> help();
			case "enable", "on", "start" -> Bot.setEnabled(true);
			case "disable", "off" -> Bot.setEnabled(false);
			case "toggle" -> Bot.setEnabled(!Bot.enabled);
			case "set" -> set(a);
			case "get" -> get(a);
			case "reset" -> {
				if (a.length == 0) {
					Settings.resetAll();
					Log.say("All settings reset.");
				} else {
					Setting<?> s = Settings.byId(a[0]);
					if (s == null) Log.warn("No such setting: " + a[0]);
					else {
						s.reset();
						Log.say(s.id + " = " + s.display());
					}
				}
			}
			case "preset" -> {
				if (a.length == 0) Log.say("Presets: " + String.join(", ", Profiles.presets()));
				else {
					String n = a[0].substring(0, 1).toUpperCase(Locale.ROOT) + a[0].substring(1).toLowerCase(Locale.ROOT);
					if (Profiles.presets().contains(n)) {
						Profiles.applyPreset(n);
						Log.say("Applied preset " + n + ".");
					} else Log.warn("Unknown preset. Presets: " + String.join(", ", Profiles.presets()));
				}
			}
			case "profile" -> profile(a);
			case "goto", "go", "path" -> go(a);
			case "stop", "cancel" -> {
				Bot.stopTask();
				Log.say("Stopped.");
			}
			case "pause" -> {
				Tasks.setPaused(true);
				Bot.ctl.owned = false;
				Bot.ctl.releaseAll();
				Bot.look.release();
				Log.say("Paused.");
			}
			case "resume", "continue" -> {
				Tasks.setPaused(false);
				Log.say("Resumed.");
			}
			case "status" -> Log.say(Bot.headline() + " - " + Bot.detail());
			case "wp", "waypoint" -> waypoint(a);
			case "mine" -> mine(a);
			case "farm" -> start(new FarmTask(a.length > 0 ? Integer.parseInt(a[0]) : Settings.FARM_RADIUS.i()));
			case "follow" -> {
				if (a.length == 0) Log.warn("Usage: follow <player or mob name>");
				else start(new FollowTask(a[0]));
			}
			case "explore" -> {
				if (a.length >= 2) start(new ExploreTask(new Goal.Column((int) Math.floor(Double.parseDouble(a[0])), (int) Math.floor(Double.parseDouble(a[1])))));
				else start(new ExploreTask(null));
			}
			case "wander", "roam" -> start(new WanderTask(a.length > 0 ? Double.parseDouble(a[0]) : -1));
			case "log", "finds" -> com.humanisedbaritone.scan.Radar.command(a);
			case "sel", "selection" -> sel(a);
			case "build" -> build(a);
			case "tunnel", "dig" -> tunnel(a);
			case "then", "queue" -> {
				if (a.length == 0) Log.warn("Usage: then <command>   runs it after the current task, e.g. then mine 10 logs");
				else {
					queueMode = true;
					try {
						run(String.join(" ", a));
					} finally {
						queueMode = false;
					}
				}
			}
			case "sleep" -> start(new SleepTask());
			case "store", "deposit", "stash" -> start(new StashTask(true, a.length > 0 ? a[0] : "", 0));
			case "fetch", "withdraw", "take" -> {
				if (a.length == 0) Log.warn("Usage: fetch <item name> [max stacks]");
				else start(new StashTask(false, a[0], a.length > 1 ? Integer.parseInt(a[1]) : 0));
			}
			case "hunt", "kill" -> start(new HuntTask());
			case "guard", "defend" -> start(new GuardTask());
			case "collect", "pickup", "loot" -> start(new CollectTask(a.length > 0 ? Double.parseDouble(a[0]) : Settings.PICKUP_RADIUS.d() + 5, false, true));
			default -> {
				return false;
			}
		}
		return true;
	}

	/** While set, the task started by a command waits for the running one instead of replacing it. */
	private static boolean queueMode;

	/** Starts a task, switching the bot on if needed. */
	public static void start(Task t) {
		if (!Bot.enabled) Bot.setEnabled(true);
		if (queueMode && Tasks.current() != null) {
			Tasks.enqueue(t);
			Log.info("Queued: " + t.name() + ".");
			return;
		}
		Tasks.start(t);
		Log.info(t.name() + "...");
	}

	private static double coord(String s, double here) {
		if (s.startsWith("~")) return here + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0);
		return Double.parseDouble(s);
	}

	private static void go(String[] a) {
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		try {
			Goal g;
			if (a.length == 3) g = new Goal.At((int) Math.floor(coord(a[0], p.getX())), (int) Math.floor(coord(a[1], p.getY())), (int) Math.floor(coord(a[2], p.getZ())));
			else if (a.length == 2) g = new Goal.Column((int) Math.floor(coord(a[0], p.getX())), (int) Math.floor(coord(a[1], p.getZ())));
			else if (a.length == 1 && Settings.WAYPOINTS.containsKey(a[0])) {
				int[] w = Settings.WAYPOINTS.get(a[0]);
				g = new Goal.At(w[0], w[1], w[2]);
			} else if (a.length == 1) g = new Goal.Level((int) Math.floor(coord(a[0], p.getY())));
			else {
				Log.warn("Usage: goto <x y z> | <x z> | <y> | <waypoint>   (~ means relative to you)");
				return;
			}
			start(new GotoTask(g));
		} catch (NumberFormatException e) {
			Log.warn("Those are not valid coordinates.");
		}
	}

	private static BlockPos here() {
		return Minecraft.getInstance().player.blockPosition();
	}

	private static BlockPos posArgs(String[] a, int from) {
		var p = Minecraft.getInstance().player;
		if (a.length >= from + 3) return new BlockPos((int) Math.floor(coord(a[from], p.getX())), (int) Math.floor(coord(a[from + 1], p.getY())), (int) Math.floor(coord(a[from + 2], p.getZ())));
		return here();
	}

	private static void sel(String[] a) {
		if (a.length == 0) {
			Log.say("Selection: " + Selection.info());
			return;
		}
		switch (a[0]) {
			case "pos1", "1" -> {
				Selection.a = posArgs(a, 1);
				Log.say("Selection: " + Selection.info());
			}
			case "pos2", "2" -> {
				Selection.b = posArgs(a, 1);
				Log.say("Selection: " + Selection.info());
			}
			case "none", "reset", "forget" -> {
				Selection.clear();
				Log.say("Selection removed.");
			}
			case "info" -> Log.say("Selection: " + Selection.info());
			case "clear", "fill", "walls" -> {
				if (!Selection.ready()) {
					Log.warn("Set both corners first: sel pos1 / sel pos2");
					return;
				}
				if (a[0].equals("clear")) {
					start(Work.clear(Selection.a, Selection.b));
					return;
				}
				if (a.length < 2) {
					Log.warn("Usage: sel " + a[0] + " <block>");
					return;
				}
				Identifier id = Identifier.tryParse(a[1].toLowerCase(Locale.ROOT));
				if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
					Log.warn("Unknown block: " + a[1]);
					return;
				}
				start(Work.fill(Selection.a, Selection.b, BuiltInRegistries.BLOCK.getValue(id), a[0].equals("walls")));
			}
			default -> Log.warn("Usage: sel pos1|pos2|clear|fill <block>|walls <block>|none");
		}
	}

	/** build <file> [x y z] shows the schematic as a preview; build confirm starts it. The other words move, turn or drop the preview. */
	private static void build(String[] a) {
		if (a.length == 0) {
			Log.warn("Usage: build <file> [x y z]  (shows a preview), then build confirm | cancel | here | move <x y z> | nudge <dx dy dz> | rotate   files live in " + Schematics.dir());
			return;
		}
		Placement p = Placement.current;
		switch (a[0].toLowerCase(Locale.ROOT)) {
			case "confirm", "yes", "go" -> confirmBuild();
			case "cancel", "no", "unload", "drop" -> {
				if (p == null) Log.warn("No schematic is loaded.");
				else {
					if (Tasks.current() != null && "BUILD".equals(Tasks.current().tag())) Bot.stopTask();
					Placement.clear();
					Log.say("Schematic dropped.");
				}
			}
			case "here", "move", "nudge", "rotate", "turn" -> {
				if (p == null) {
					Log.warn("Load a schematic first: build <file>");
					return;
				}
				switch (a[0].toLowerCase(Locale.ROOT)) {
					case "here" -> p.moveTo(here());
					case "move" -> p.moveTo(posArgs(a, 1));
					case "nudge" -> {
						if (a.length < 4) Log.warn("Usage: build nudge <dx dy dz>");
						else p.nudge(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
					}
					default -> p.rotate();
				}
				Log.say(p.name + " at " + p.origin.toShortString() + ", size " + p.sx + " x " + p.sy + " x " + p.sz + ".");
			}
			default -> {
				try {
					Schematic s = Schematics.load(a[0]);
					if (s.blocks.isEmpty()) {
						Log.warn(s.name + " contains no blocks" + (s.unknown > 0 ? " this version knows (" + s.unknown + " unknown)" : "") + ".");
						return;
					}
					if (s.unknown > 0) Log.warn(s.unknown + " blocks of " + s.name + " do not exist in this version and are skipped.");
					if (Tasks.current() != null && "BUILD".equals(Tasks.current().tag())) Bot.stopTask();
					Placement n = Placement.show(s, a.length >= 4 ? posArgs(a, 1) : Placement.spotInFront(s));
					Log.say("Loaded " + n.info() + " at " + n.origin.toShortString() + ". Look at the preview, move it if you like, then confirm (Confirm in the panel, or: build confirm).");
				} catch (java.io.IOException e) {
					Log.warn("Could not read the schematic: " + e.getMessage());
				}
			}
		}
	}

	/** Starts building the schematic on show (or goes on with it after a stop). */
	public static void confirmBuild() {
		Placement p = Placement.current;
		if (p == null) {
			Log.warn("No schematic is loaded. Use build <file> first.");
			return;
		}
		p.confirmed = true;
		start(Work.build(p));
	}

	/** tunnel [length] [width] [height]: a corridor straight ahead, starting at your feet. */
	private static void tunnel(String[] a) {
		var p = Minecraft.getInstance().player;
		int len = a.length > 0 ? Integer.parseInt(a[0]) : 20, w = a.length > 1 ? Integer.parseInt(a[1]) : 1, h = a.length > 2 ? Integer.parseInt(a[2]) : 2;
		Direction d = p.getDirection(), side = d.getClockWise();
		BlockPos start = here().relative(d);
		int half = (w - 1) / 2;
		BlockPos c1 = start.relative(side, -half), c2 = start.relative(d, len - 1).relative(side, w - 1 - half).above(h - 1);
		start(Work.clear(c1, c2));
	}

	/** Block names may be given loosely: "diamond" finds diamond_ore and deepslate_diamond_ore, "logs" any log. */
	private static void mine(String[] a) {
		int count = 0, from = 0;
		if (a.length > 0 && a[0].matches("\\d+")) {
			count = Integer.parseInt(a[0]);
			from = 1;
		}
		Set<Block> blocks = new LinkedHashSet<>();
		StringBuilder label = new StringBuilder();
		for (int i = from; i < a.length; i++) {
			String n = a[i].toLowerCase(Locale.ROOT).replace("minecraft:", "");
			int before = blocks.size();
			if (n.equals("log") || n.equals("logs") || n.equals("wood")) {
				BuiltInRegistries.BLOCK.stream().filter(b -> b.defaultBlockState().is(BlockTags.LOGS)).forEach(blocks::add);
			} else {
				for (String cand : new String[]{n, n + "_ore", "deepslate_" + n + "_ore", "deepslate_" + n}) {
					Identifier id = Identifier.tryParse(cand);
					if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) blocks.add(BuiltInRegistries.BLOCK.getValue(id));
				}
			}
			if (blocks.size() == before) Log.warn("Unknown block: " + a[i]);
			else label.append(label.length() > 0 ? ", " : "").append(n.replace('_', ' '));
		}
		if (blocks.isEmpty()) {
			Log.warn("Usage: mine [count] <block...>   e.g. mine diamond  |  mine 20 iron_ore  |  mine logs");
			return;
		}
		start(new MineTask(blocks, count, label.toString()));
	}

	private static void waypoint(String[] a) {
		var p = Minecraft.getInstance().player;
		if (a.length == 0 || a[0].equals("list")) {
			if (Settings.WAYPOINTS.isEmpty()) Log.say("No waypoints. Use: wp save <name>");
			Settings.WAYPOINTS.forEach((k, v) -> Log.say(k + "  " + v[0] + " " + v[1] + " " + v[2]));
			return;
		}
		if (a.length < 2) {
			Log.warn("Usage: wp save|goto|remove <name>");
			return;
		}
		switch (a[0]) {
			case "save", "set", "add" -> {
				if (p == null) return;
				Settings.WAYPOINTS.put(a[1], new int[]{p.getBlockX(), p.getBlockY(), p.getBlockZ()});
				Settings.markDirty();
				Log.say("Saved waypoint " + a[1] + " at " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ() + ".");
			}
			case "goto", "go" -> go(new String[]{a[1]});
			case "remove", "delete", "del" -> {
				Settings.WAYPOINTS.remove(a[1]);
				Settings.markDirty();
				Log.say("Removed waypoint " + a[1] + ".");
			}
			default -> Log.warn("Usage: wp save|goto|remove <name>");
		}
	}

	private static void help() {
		Log.say("Bot: enable | disable | stop | pause | resume | status");
		Log.say("Travel: goto <x y z | x z | y | waypoint> | follow <player> | explore [x z] | wander [radius] | wp save|goto|list|remove <name>");
		Log.say("Work: mine <block...> | farm | collect | hunt | guard | sleep | store [item] | fetch <item> | tunnel [len w h]");
		Log.say("Chain: then <command> runs a command after the current task, e.g. then mine 10 logs");
		Log.say("Elytra: with one worn, wander and long goto trips bounce along open ground (timed jumps, no fireworks); set elytra.helper on times the jumps of your own run (hold forward, look down)");
		Log.say("Areas: sel pos1|pos2|clear|fill <block>|walls <block>|none");
		Log.say("Schematics: build <file> [x y z] shows a preview | build confirm | cancel | here | move <x y z> | nudge <dx dy dz> | rotate");
		Log.say("Finds: log [list [n]] | goto <n> | check <n> | clear [kind] | scan | mark <name>   (the Logging and Map pages show them)");
		Log.say("Settings: set <id> <value> | get <id|text> | reset [id] | preset <name> | profile list|save|load|delete <name>");
	}

	private static void set(String[] a) {
		if (a.length < 2) {
			Log.warn("Usage: set <setting id> <value>");
			return;
		}
		Setting<?> s = Settings.byId(a[0]);
		if (s == null) {
			Log.warn("No such setting: " + a[0] + "  (use 'get <text>' to search)");
			return;
		}
		String v = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
		if (s.parse(v)) Log.say(s.id + " = " + s.display());
		else Log.warn("'" + v + "' is not a valid value for " + s.id);
	}

	private static void get(String[] a) {
		if (a.length == 0) {
			Log.warn("Usage: get <setting id | search text>");
			return;
		}
		String q = String.join(" ", a).toLowerCase(Locale.ROOT);
		int n = 0;
		for (Setting<?> s : Setting.ALL) {
			if (s.id.equalsIgnoreCase(q) || s.id.toLowerCase(Locale.ROOT).contains(q) || s.name.toLowerCase(Locale.ROOT).contains(q)) {
				Log.say(s.id + " = " + s.display() + "   (" + s.name + ")");
				if (++n >= 12) {
					Log.say("... refine the search to see more.");
					break;
				}
			}
		}
		if (n == 0) Log.warn("No setting matches '" + q + "'.");
	}

	private static void profile(String[] a) {
		if (a.length == 0 || a[0].equals("list")) {
			Log.say("Profiles: " + (Profiles.saved().isEmpty() ? "(none)" : String.join(", ", Profiles.saved().stream().map(n -> n.equals(Settings.profile) ? n + " (in use)" : n).toList())));
			return;
		}
		if (a.length < 2) {
			Log.warn("Usage: profile save|load|delete <name>");
			return;
		}
		String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
		switch (a[0]) {
			case "save" -> Log.say(Profiles.save(name) ? "Saved profile " + name : "Could not save profile.");
			case "load" -> Log.say(Profiles.load(name) ? "Loaded profile " + name : "No such profile.");
			case "delete" -> Log.say(Profiles.delete(name) ? "Deleted profile " + name : "No such profile.");
			default -> Log.warn("Usage: profile save|load|delete <name>");
		}
	}
}
