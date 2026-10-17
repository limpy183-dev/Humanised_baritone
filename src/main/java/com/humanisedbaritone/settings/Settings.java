package com.humanisedbaritone.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.humanisedbaritone.settings.Setting.Registry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/** Every setting of the mod, in one place. Tabs, sliders and the config file are generated from these. */
public final class Settings {
	public static final String GENERAL = "General", HUMAN = "Humanise", MOVE = "Movement", WORLD = "World", COMBAT = "Combat",
		SAFETY = "Safety", SURVIVAL = "Survival", CHAT = "Chat Games", UI = "Interface", ADV = "Advanced", LOGGING = "Logging";
	public static final String[] CATEGORIES = {GENERAL, LOGGING, HUMAN, MOVE, WORLD, COMBAT, SAFETY, SURVIVAL, CHAT, UI, ADV};

	public enum Accent { CYAN, VIOLET, EMERALD, AMBER, ROSE, AZURE }
	public enum UiStyle { MINECRAFT, MODERN, FUN, RELAXING }
	public enum PanelScale { AUTO, X1, X2, X3, X4, X5 }
	public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
	public enum CreeperMode { FIGHT, RETREAT, AVOID }
	public enum BuildOrder { BOTTOM_UP, TOP_DOWN, NEAREST }

	private static Setting.Bool b(String c, String g, String id, String n, String d, boolean def) {
		return new Setting.Bool(c, g, id, n, d, def);
	}

	private static Setting.Num f(String c, String g, String id, String n, String d, double def, double min, double max, double step, String unit) {
		return new Setting.Num(c, g, id, n, d, def, min, max, step, false, unit);
	}

	private static Setting.Num i(String c, String g, String id, String n, String d, int def, int min, int max, int step, String unit) {
		return new Setting.Num(c, g, id, n, d, def, min, max, step, true, unit);
	}

	private static <E extends Enum<E>> Setting.Choice<E> e(String c, String g, String id, String n, String d, E def) {
		return new Setting.Choice<>(c, g, id, n, d, def);
	}

	private static Setting.IdSet s(String c, String g, String id, String n, String d, Registry r, String... def) {
		return new Setting.IdSet(c, g, id, n, d, r, def);
	}

	// ================================================================ General
	public static final Setting.Bool REMEMBER_ENABLED = b(GENERAL, "Bot", "bot.rememberEnabled", "Remember bot enabled state", "Keep the bot ON or OFF when rejoining, joining another world or restarting the game.", false);
	public static final Setting.Text CHAT_PREFIX = new Setting.Text(GENERAL, "Bot", "chat.prefix", "Chat command prefix", "Chat messages that start with this run a bot command, e.g. #goto 10 64 10", "#");
	public static final Setting.Bool CHAT_COMMANDS = b(GENERAL, "Bot", "chat.commands", "Chat commands", "Intercept chat messages that start with the prefix.", true);
	public static final Setting.Bool FEEDBACK = b(GENERAL, "Bot", "chat.feedback", "Chat feedback", "Show what the bot is doing in your chat.", true);
	public static final Setting.Bool USER_CANCEL = b(GENERAL, "Bot", "user.cancel", "Movement keys cancel tasks", "Pressing W/A/S/D yourself cancels the running task.", false);
	public static final Setting.Bool PASSIVE = b(GENERAL, "Bot", "passive", "Passive guard when idle", "While enabled but idle the bot still eats, defends you and escapes hazards.", true);
	public static final Setting.Bool AUTO_RESUME = b(GENERAL, "Bot", "resume", "Resume after respawn", "Pick the previous task back up after dying and respawning.", false);
	public static final Setting.Bool AUTO_RESPAWN = b(GENERAL, "Bot", "respawn", "Respawn automatically", "Click respawn after a human-sounding pause when the bot dies. Gives up after 3 deaths in 10 minutes.", false);

	// ================================================================ Chat games
	public static final Setting.Bool CHAT_REVERSE = b(CHAT, "Unreverse the letters", "chat.reverse", "Auto unreverse", "Reverse the challenge text exactly, keeping capitals and numbers. Requires the bot to be ON.", false);
	public static final Setting.Num CHAT_REVERSE_MIN = f(CHAT, "Unreverse the letters", "chat.reverseMin", "Minimum response delay", "Random delay between the two bounds; reversed bounds are sorted automatically.", 1, 0, 30, 0.1, "s").child(CHAT_REVERSE);
	public static final Setting.Num CHAT_REVERSE_MAX = f(CHAT, "Unreverse the letters", "chat.reverseMax", "Maximum response delay", "Time from receiving the complete challenge to sending the answer. Equal bounds give a fixed delay.", 3, 0, 30, 0.1, "s").child(CHAT_REVERSE);
	public static final Setting.Bool CHAT_UNSCRAMBLE = b(CHAT, "Unscramble the word", "chat.unscramble", "Auto unscramble", "Match scrambled letters to a Minecraft block or item name. Unknown words are skipped; ambiguous matches get one guess. Requires the bot to be ON.", false);
	public static final Setting.Num CHAT_UNSCRAMBLE_MIN = f(CHAT, "Unscramble the word", "chat.unscrambleMin", "Minimum response delay", "Random delay between the two bounds; reversed bounds are sorted automatically.", 1, 0, 30, 0.1, "s").child(CHAT_UNSCRAMBLE);
	public static final Setting.Num CHAT_UNSCRAMBLE_MAX = f(CHAT, "Unscramble the word", "chat.unscrambleMax", "Maximum response delay", "Time from receiving the complete challenge to sending the answer. Equal bounds give a fixed delay.", 3, 0, 30, 0.1, "s").child(CHAT_UNSCRAMBLE);
	public static final Setting.Bool CHAT_NUMBER = b(CHAT, "Guess the number", "chat.number", "Auto guess number", "Send one random integer within the announced range, including both endpoints. Requires the bot to be ON.", false);
	public static final Setting.Num CHAT_NUMBER_MIN = f(CHAT, "Guess the number", "chat.numberMin", "Minimum response delay", "Random delay between the two bounds; reversed bounds are sorted automatically.", 1, 0, 30, 0.1, "s").child(CHAT_NUMBER);
	public static final Setting.Num CHAT_NUMBER_MAX = f(CHAT, "Guess the number", "chat.numberMax", "Maximum response delay", "Time from receiving the complete challenge to sending the answer. Equal bounds give a fixed delay.", 3, 0, 30, 0.1, "s").child(CHAT_NUMBER);

	// ================================================================ Humanise
	public static final Setting.Bool LOOK_SMOOTH = b(HUMAN, "Camera", "look.smooth", "Mouse-like camera", "Turn the camera with a human aiming model instead of snapping it.", true);
	public static final Setting.Num LOOK_SPEED = f(HUMAN, "Camera", "look.speed", "Camera speed", "Overall speed multiplier of camera movements.", 1.0, 0.25, 3.0, 0.05, "x").child(LOOK_SMOOTH);
	public static final Setting.Num LOOK_MAX = f(HUMAN, "Camera", "look.max", "Peak turn rate", "Fastest the camera may ever turn.", 600, 90, 1440, 10, "deg/s").child(LOOK_SMOOTH);
	public static final Setting.Num LOOK_REACTION = f(HUMAN, "Camera", "look.reaction", "Reaction delay", "Average delay before the camera follows a new target.", 130, 0, 800, 10, "ms").child(LOOK_SMOOTH);
	public static final Setting.Num LOOK_OVERSHOOT = f(HUMAN, "Camera", "look.overshoot", "Overshoot chance", "Chance that a big flick slightly overshoots and corrects back.", 0.3, 0, 1, 0.05, "").child(LOOK_SMOOTH);
	public static final Setting.Num LOOK_NOISE = f(HUMAN, "Camera", "look.noise", "Aim wobble", "Amount of hand tremor and drift added to the aim.", 1.0, 0, 3, 0.05, "x").child(LOOK_SMOOTH);
	public static final Setting.Bool LOOK_QUANTISE = b(HUMAN, "Camera", "look.quantise", "Mouse-count steps", "Snap rotation to the increments a real mouse produces at your sensitivity.", true).child(LOOK_SMOOTH);
	public static final Setting.Num LOOK_GLANCE = f(HUMAN, "Camera", "look.glance", "Look-around frequency", "How often the bot glances around while travelling.", 0.4, 0, 1, 0.05, "").child(LOOK_SMOOTH);
	public static final Setting.Num LOOK_PITCH = f(HUMAN, "Camera", "look.pitch", "Travel pitch", "Typical downward tilt of the view while walking.", 6, 0, 30, 1, "deg").child(LOOK_SMOOTH);
	public static final Setting.Bool LOOK_SWAY = b(HUMAN, "Camera", "look.sway", "Head sway", "Tiny periodic sway of the view while moving.", true).child(LOOK_SMOOTH);

	public static final Setting.Bool MOTION_STRAIGHT = b(HUMAN, "Movement feel", "motion.straight", "Straight runs", "Run straight lines across open, flat ground instead of block by block. Ledges, jumps, bridges and hazards stay careful.", true);
	public static final Setting.Num MOTION_WANDER = f(HUMAN, "Movement feel", "motion.wander", "Lane wander", "How far from the ideal line the bot may drift. On straight runs it weaves slowly, within the open ground.", 0.35, 0, 1, 0.01, "blocks");
	public static final Setting.Num MOTION_CORNER = f(HUMAN, "Movement feel", "motion.corner", "Corner cutting", "How early the bot starts turning into the next segment.", 0.6, 0, 1, 0.05, "");
	public static final Setting.Num MOTION_HESITATE = f(HUMAN, "Movement feel", "motion.hesitate", "Micro-hesitations", "Frequency of tiny pauses while walking.", 0.25, 0, 1, 0.05, "");
	public static final Setting.Num MOTION_STRAFE = f(HUMAN, "Movement feel", "motion.strafe", "Strafe taps", "Frequency of small sideways key taps.", 0.25, 0, 1, 0.05, "");
	public static final Setting.Num MOTION_SPRINT_DELAY = f(HUMAN, "Movement feel", "motion.sprintDelay", "Sprint start delay", "Average time before the bot starts sprinting.", 180, 0, 800, 10, "ms");
	public static final Setting.Num MOTION_JUMP_SLACK = i(HUMAN, "Movement feel", "motion.jumpSlack", "Jump timing slack", "Random early/late jump timing inside the safe window.", 1, 0, 3, 1, "ticks");

	public static final Setting.Num ACT_DELAY = f(HUMAN, "Actions", "act.delay", "Action delay", "Pause between finishing one action and starting the next.", 140, 0, 1000, 10, "ms");
	public static final Setting.Num ACT_JITTER = f(HUMAN, "Actions", "act.jitter", "Timing variance", "How much all delays vary from one occurrence to the next.", 0.5, 0, 1, 0.05, "");
	public static final Setting.Num ACT_SWAP = f(HUMAN, "Actions", "act.swap", "Hotbar switch delay", "Time taken to react after switching the held item.", 160, 0, 600, 10, "ms");
	public static final Setting.Num ACT_SETTLE = f(HUMAN, "Actions", "act.settle", "Crosshair settle time", "How long the crosshair rests on a target before clicking.", 60, 0, 400, 10, "ms");
	public static final Setting.Num AIM_SPREAD = f(HUMAN, "Actions", "aim.spread", "Aim point spread", "How randomly the hit point on a block face is chosen.", 0.6, 0, 1, 0.05, "");
	public static final Setting.Num AIM_MARGIN = f(HUMAN, "Actions", "aim.margin", "Block edge margin", "Keep aim points this far from block edges.", 0.14, 0.02, 0.45, 0.01, "");

	public static final Setting.Num VAR_PATH = f(HUMAN, "Variation", "var.path", "Path variety", "Random cost noise so equal routes are not always chosen identically.", 0.04, 0, 0.25, 0.005, "");
	public static final Setting.Bool VAR_ATTENTION = b(HUMAN, "Variation", "var.attention", "Attention drift", "Focus slowly fluctuates, changing reaction time and steadiness.", true);
	public static final Setting.Num VAR_ATTENTION_AMT = f(HUMAN, "Variation", "var.attentionAmt", "Attention strength", "How strongly attention drift affects behaviour.", 0.4, 0, 1, 0.05, "").child(VAR_ATTENTION);
	public static final Setting.Num VAR_FIDGET = f(HUMAN, "Variation", "var.fidget", "Idle fidgets", "Small looks and movements while waiting.", 0.3, 0, 1, 0.05, "");
	public static final Setting.Num VAR_SEED = i(HUMAN, "Variation", "var.seed", "Random seed", "0 = different every session; any other value repeats the behaviour.", 0, 0, 999999, 1, "");

	// ================================================================ Movement
	public static final Setting.Bool SPRINT = b(MOVE, "Gait", "move.sprint", "Sprint", "Allow sprinting along straight stretches.", true);
	public static final Setting.Bool SPRINT_JUMP = b(MOVE, "Gait", "move.sprintJump", "Sprint jump", "Bunny-hop while sprinting along long open runs: faster than a plain sprint. Never near ledges, hazards or a low ceiling.", true).child(SPRINT);
	public static final Setting.Bool PARKOUR = b(MOVE, "Gait", "move.parkour", "Gap jumps", "Allow jumping over gaps in the path.", true);
	public static final Setting.Bool PARKOUR_UP = b(MOVE, "Gait", "move.parkourUp", "Jump gaps upwards", "Allow gap jumps that land one block higher.", true).child(PARKOUR);
	public static final Setting.Num MAX_GAP = i(MOVE, "Gait", "move.maxGap", "Longest gap jump", "Blocks between take-off and landing block (3 needs a sprint).", 3, 1, 4, 1, "blocks").child(PARKOUR);
	public static final Setting.Bool DIAGONAL = b(MOVE, "Gait", "move.diagonal", "Diagonal steps", "Allow cutting across corners.", true);
	public static final Setting.Bool SWIM = b(MOVE, "Gait", "move.swim", "Swim", "Allow paths through water.", true);
	public static final Setting.Bool CLIMB = b(MOVE, "Gait", "move.climb", "Climb", "Use ladders and vines.", true);
	public static final Setting.Bool DOORS = b(MOVE, "Gait", "move.doors", "Open doors", "Open wooden doors, gates and trapdoors on the way.", true);
	public static final Setting.Num JUMP_PENALTY = f(MOVE, "Gait", "move.jumpPenalty", "Jump penalty", "Extra path cost for every jump (makes the bot prefer flat routes).", 2, 0, 12, 0.5, "ticks");
	public static final Setting.Num MAX_FALL = i(MOVE, "Falling", "move.maxFall", "Max fall height", "Longest drop taken without a water bucket.", 3, 1, 40, 1, "blocks");
	public static final Setting.Bool CLUTCH = b(MOVE, "Falling", "move.clutch", "Water bucket clutch", "Allow long drops if a water bucket can cancel the fall.", true);
	public static final Setting.Num MAX_FALL_BUCKET = i(MOVE, "Falling", "move.maxFallBucket", "Max clutch height", "Longest drop taken with a water bucket.", 20, 4, 60, 1, "blocks").child(CLUTCH);
	public static final Setting.Bool CLUTCH_STEER = b(MOVE, "Falling", "move.clutchSteer", "Clutch landing correction", "While falling towards a clutch, nudge sideways in the air so the landing is well inside a block, not on its edge, where the water cannot be placed.", true).child(CLUTCH);
	public static final Setting.Bool PILLAR = b(MOVE, "Vertical", "move.pillar", "Pillar up", "Place blocks under yourself to climb.", true);
	public static final Setting.Bool DIG_DOWN = b(MOVE, "Vertical", "move.digDown", "Dig down", "Break the floor and drop to descend.", true);
	public static final Setting.Bool BRIDGE = b(MOVE, "Vertical", "move.bridge", "Bridge gaps", "Place blocks to cross holes.", true);
	public static final Setting.Bool ELYTRA = b(MOVE, "Elytra", "elytra.jump", "Elytra jump", "With an elytra worn, jump on the exact tick of every landing and re-open the elytra in the air: 30-38 blocks a second on open flat ground, no fireworks. Wander and long goto trips use it.", true);
	public static final Setting.Bool ELYTRA_HELPER = b(MOVE, "Elytra", "elytra.helper", "Elytra jump helper", "With the bot ON, an elytra worn, forward held and the camera 55+ degrees down, the bot presses jump and sprint at the right tick. You steer.", false).child(ELYTRA);
	public static final Setting.Bool ELYTRA_SAFE = b(MOVE, "Elytra", "elytra.safe", "Brake before obstacles", "Stop jumping in time to halt before a wall, a step up, water, lava or a hole. A wall at full speed costs ten hit points or more.", true).child(ELYTRA_HELPER);
	public static final Setting.Num ELYTRA_KEEP = i(MOVE, "Elytra", "elytra.keep", "Spare the elytra", "Stop bouncing below this elytra durability. A bounce itself hardly wears the elytra.", 8, 0, 50, 1, "%").child(ELYTRA);
	public static final Setting.Num WANDER_RADIUS = i(MOVE, "Wander", "wander.radius", "Wander radius", "How far from the place where it started the wander task roams. 0 = no limit.", 250, 0, 5000, 10, "blocks");

	// ================================================================ Mining & building
	public static final Setting.Bool BREAK = b(WORLD, "Blocks", "world.break", "Break blocks", "Allow the path to go through blocks by mining them.", true);
	public static final Setting.Bool PLACE = b(WORLD, "Blocks", "world.place", "Place blocks", "Allow the path to place blocks (bridging, pillars, steps).", true);
	public static final Setting.Num BREAK_PENALTY = f(WORLD, "Blocks", "world.breakPenalty", "Break penalty", "Extra path cost for every block that has to be broken.", 2, 0, 30, 0.5, "ticks").child(BREAK);
	public static final Setting.Num PLACE_PENALTY = f(WORLD, "Blocks", "world.placePenalty", "Place penalty", "Extra path cost for every block that has to be placed.", 1, 0, 30, 0.5, "ticks").child(PLACE);
	public static final Setting.Num REACH = f(WORLD, "Blocks", "world.reach", "Interaction reach", "Never interact with blocks further away than this.", 4.2, 2, 5, 0.1, "blocks");
	public static final Setting.Bool AUTO_TOOL = b(WORLD, "Blocks", "world.autoTool", "Auto tool", "Switch to the best tool before mining.", true);
	public static final Setting.Num TOOL_KEEP = i(WORLD, "Blocks", "world.toolKeep", "Protect worn tools", "Do not use tools with less durability than this (percent).", 4, 0, 50, 1, "%");
	public static final Setting.Bool NO_LIQUID = b(WORLD, "Blocks", "world.noLiquid", "Keep liquids back", "Never break a block that is holding back lava or water.", true);
	public static final Setting.Bool FALL_GUARD = b(WORLD, "Blocks", "world.fallGuard", "Falling-block guard", "Avoid standing under sand and gravel while mining.", true);
	public static final Setting.IdSet AVOID_BREAK = s(WORLD, "Blocks", "world.avoidBreak", "Never break", "Blocks the bot will never mine, even if they are in the way.", Registry.BLOCK,
		"farmland", "chest", "trapped_chest", "barrel", "ender_chest", "furnace", "blast_furnace", "smoker", "crafting_table", "enchanting_table", "anvil", "beacon", "spawner", "trial_spawner", "vault",
		"white_bed", "red_bed", "respawn_anchor", "lectern", "jukebox", "bookshelf", "chiseled_bookshelf", "command_block", "bedrock", "barrier", "end_portal_frame", "end_portal", "nether_portal");
	public static final Setting.IdSet PLACEABLE = s(WORLD, "Blocks", "world.placeable", "Building blocks", "Cheap blocks the bot may place for bridges and pillars.", Registry.BLOCK,
		"cobblestone", "dirt", "stone", "netherrack", "cobbled_deepslate", "deepslate", "andesite", "diorite", "granite", "tuff", "blackstone", "end_stone", "sandstone", "stone_bricks", "oak_planks", "spruce_planks", "coarse_dirt");
	public static final Setting.Num MINE_RADIUS = i(WORLD, "Mining", "mine.radius", "Scan radius", "How far around you to look for ore.", 48, 8, 256, 4, "blocks");
	public static final Setting.Bool MINE_VISIBLE = b(WORLD, "Mining", "mine.visible", "Only exposed ore", "Legit mode: only mine ore that has a face open to air or liquid.", false);
	public static final Setting.Bool MINE_PICKUP = b(WORLD, "Mining", "mine.pickup", "Collect drops", "Walk to dropped items after mining.", true);
	public static final Setting.Bool MINE_VEIN = b(WORLD, "Mining", "mine.vein", "Follow veins", "Finish a whole vein before moving on.", true);
	public static final Setting.Bool MINE_EXPLORE = b(WORLD, "Mining", "mine.explore", "Explore for ore", "Wander to new areas when no ore is known nearby.", true);
	public static final Setting.Num FARM_RADIUS = i(WORLD, "Farming", "farm.radius", "Farm radius", "How far around the start point crops are harvested.", 16, 4, 64, 1, "blocks");
	public static final Setting.Bool FARM_REPLANT = b(WORLD, "Farming", "farm.replant", "Replant", "Replant seeds after harvesting.", true);
	public static final Setting.Choice<BuildOrder> BUILD_ORDER = e(WORLD, "Building", "build.order", "Build order", "In which order schematic layers are built.", BuildOrder.BOTTOM_UP);
	public static final Setting.Bool BUILD_CLEAR = b(WORLD, "Building", "build.clear", "Clear extra blocks", "Remove blocks that are in the way of the schematic.", true);
	public static final Setting.Bool BUILD_TIDY = b(WORLD, "Building", "build.tidy", "Remove scaffolding", "When a build is done, take away the blocks the bot put down only to reach places (bridges, pillars).", true);

	// ================================================================ Combat
	public static final Setting.Bool COMBAT_ON = b(COMBAT, "Fighting", "combat.enabled", "Combat module", "Master switch for automatic fighting.", true);
	public static final Setting.Bool DEFEND = b(COMBAT, "Fighting", "combat.defend", "Fight back", "Attack anything that attacks you.", true).child(COMBAT_ON);
	public static final Setting.Bool HUNT = b(COMBAT, "Fighting", "combat.hunt", "Hunt nearby mobs", "Seek out and kill hostile mobs close to you.", false).child(COMBAT_ON);
	public static final Setting.Num HUNT_RANGE = f(COMBAT, "Fighting", "combat.range", "Hunt radius", "How far away hostile mobs are noticed.", 14, 4, 32, 1, "blocks").child(COMBAT_ON);
	public static final Setting.Bool HIT_PLAYERS = b(COMBAT, "Fighting", "combat.players", "Attack players", "Also fight other players that attack you.", false).child(COMBAT_ON);
	public static final Setting.Num REACH_ENTITY = f(COMBAT, "Technique", "combat.reach", "Attack reach", "Preferred fighting distance (varies a little each hit).", 2.85, 1.5, 3.0, 0.05, "blocks").child(COMBAT_ON);
	public static final Setting.Num CHARGE = f(COMBAT, "Technique", "combat.charge", "Cooldown wait", "Attack cooldown fraction to wait for before swinging.", 0.92, 0.4, 1.0, 0.01, "").child(COMBAT_ON);
	public static final Setting.Num MAX_CPS = f(COMBAT, "Technique", "combat.cps", "Max clicks per second", "Hard cap on attack speed.", 11, 2, 20, 1, "cps").child(COMBAT_ON);
	public static final Setting.Bool CRIT = b(COMBAT, "Technique", "combat.crit", "Jump crits", "Time hits while falling for critical damage.", true).child(COMBAT_ON);
	public static final Setting.Bool WTAP = b(COMBAT, "Technique", "combat.wtap", "Sprint resets", "Briefly release forward after a hit to keep knockback.", true).child(COMBAT_ON);
	public static final Setting.Bool STRAFE = b(COMBAT, "Technique", "combat.strafe", "Strafe", "Move sideways around the target while fighting.", true).child(COMBAT_ON);
	public static final Setting.Bool RANGED = b(COMBAT, "Technique", "combat.ranged", "Use a bow", "Shoot distant targets with a bow when arrows are available.", true).child(COMBAT_ON);
	public static final Setting.Num RANGED_MIN = f(COMBAT, "Technique", "combat.rangedMin", "Bow distance", "Only shoot when the target is at least this far away.", 7, 3, 20, 1, "blocks").child(RANGED);
	public static final Setting.Bool SHIELD = b(COMBAT, "Technique", "combat.shield", "Use shield", "Raise an off-hand shield against incoming attacks.", true).child(COMBAT_ON);
	public static final Setting.Num FLEE_HP = f(COMBAT, "Survival", "combat.flee", "Retreat below health", "Break off the fight and escape below this many hit points.", 6, 0, 20, 1, "HP").child(COMBAT_ON);
	public static final Setting.Choice<CreeperMode> CREEPER = e(COMBAT, "Survival", "combat.creeper", "Creeper tactic", "How creepers are handled.", CreeperMode.RETREAT);
	public static final Setting.IdSet TARGETS = s(COMBAT, "Targets", "combat.targets", "Hostile targets", "Mobs that are hunted and fought.", Registry.ENTITY,
		"zombie", "zombie_villager", "husk", "drowned", "skeleton", "stray", "bogged", "spider", "cave_spider", "creeper", "witch", "slime", "silverfish", "pillager", "vindicator",
		"evoker", "ravager", "phantom", "blaze", "wither_skeleton", "hoglin", "piglin_brute", "guardian", "magma_cube", "ghast", "breeze", "endermite");
	public static final Setting.IdSet IGNORE = s(COMBAT, "Targets", "combat.ignore", "Never attack", "Mobs that are left alone unless you are in real danger.", Registry.ENTITY,
		"enderman", "zombified_piglin", "iron_golem", "warden", "wolf", "bee", "llama", "panda", "polar_bear", "piglin");

	// ================================================================ Safety
	public static final Setting.Bool LAVA = b(SAFETY, "Hazards", "safe.lava", "Avoid lava", "Keep paths away from lava and escape it when touched.", true);
	public static final Setting.Num LAVA_MARGIN = i(SAFETY, "Hazards", "safe.lavaMargin", "Lava clearance", "Stay at least this far from lava where possible.", 1, 0, 3, 1, "blocks").child(LAVA);
	public static final Setting.Bool FIRE = b(SAFETY, "Hazards", "safe.fire", "Avoid fire", "Avoid fire, magma and campfires; put out flames in water.", true);
	public static final Setting.IdSet HAZARDS = s(SAFETY, "Hazards", "safe.hazards", "Hazard blocks", "Blocks paths never go through or over.", Registry.BLOCK,
		"cactus", "sweet_berry_bush", "wither_rose", "fire", "soul_fire", "campfire", "soul_campfire", "magma_block", "cobweb", "powder_snow", "pointed_dripstone", "lava", "end_portal",
		"nether_portal", "bubble_column", "sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "tnt", "trial_spawner");
	public static final Setting.Num MOB_AVOID = f(SAFETY, "Creatures", "safe.mobAvoid", "Mob avoidance", "Paths keep this distance from hostile mobs where possible.", 5, 0, 16, 1, "blocks");
	public static final Setting.Bool ENDERMAN = b(SAFETY, "Creatures", "safe.enderman", "Avoid endermen gaze", "Keep the crosshair away from endermen so they are not provoked.", true);
	public static final Setting.Bool DROWN = b(SAFETY, "Vitals", "safe.drown", "Breathe", "Swim to the surface before running out of air.", true);
	public static final Setting.Num AIR_MIN = i(SAFETY, "Vitals", "safe.air", "Surface at air", "Seconds of air left when the bot heads for the surface.", 6, 2, 14, 1, "s").child(DROWN);
	public static final Setting.Num PANIC_HP = f(SAFETY, "Vitals", "safe.panic", "Emergency below health", "Drop the current task and recover below this many hit points.", 5, 0, 20, 1, "HP");
	public static final Setting.Bool STOP_LOW = b(SAFETY, "Vitals", "safe.stopLow", "Stop if unrecoverable", "Disable the bot when in danger and nothing can heal it.", false);

	// ================================================================ Survival
	public static final Setting.Bool EAT = b(SURVIVAL, "Food", "eat.enabled", "Auto-eat", "Eat food automatically when hungry.", true);
	public static final Setting.Num EAT_FOOD = i(SURVIVAL, "Food", "eat.hunger", "Eat below food level", "Start eating when the hunger bar drops to this level.", 14, 1, 19, 1, "").child(EAT);
	public static final Setting.Bool EAT_HEAL = b(SURVIVAL, "Food", "eat.heal", "Eat to heal", "Also eat when hurt and the hunger bar is not full.", true).child(EAT);
	public static final Setting.Bool EAT_EMERGENCY = b(SURVIVAL, "Food", "eat.emergency", "Golden apples in emergencies", "Use golden apples when health is critical.", true).child(EAT);
	public static final Setting.Bool EAT_SAFE = b(SURVIVAL, "Food", "eat.safeOnly", "Eat only when safe", "Wait for threats to go away before eating (unless in an emergency).", true).child(EAT);
	public static final Setting.IdSet EAT_AVOID = s(SURVIVAL, "Food", "eat.avoid", "Never eat", "Food that is only eaten as a last resort.", Registry.ITEM,
		"rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken", "suspicious_stew", "golden_apple", "enchanted_golden_apple", "chorus_fruit", "tropical_fish");
	public static final Setting.IdSet EAT_PREFER = s(SURVIVAL, "Food", "eat.prefer", "Preferred food", "Eaten first when available (empty = best saturation).", Registry.ITEM);
	public static final Setting.Bool ARMOR = b(SURVIVAL, "Gear", "armor.auto", "Auto-equip armor", "Put on the best armor found in the inventory.", true);
	public static final Setting.Bool TOTEM = b(SURVIVAL, "Gear", "totem.auto", "Totem in off-hand", "Keep a totem of undying in the off-hand.", true);
	public static final Setting.Bool PICKUP = b(SURVIVAL, "Items", "items.pickup", "Pick up drops", "Walk to dropped items nearby when idle (tasks such as mining collect their own drops).", false);
	public static final Setting.Num PICKUP_RADIUS = i(SURVIVAL, "Items", "items.radius", "Pick-up radius", "How far away dropped items are noticed.", 7, 2, 24, 1, "blocks").child(PICKUP);
	public static final Setting.Bool JUNK = b(SURVIVAL, "Items", "items.dropJunk", "Drop junk", "Throw away junk items when the inventory is nearly full.", false);
	public static final Setting.IdSet JUNK_ITEMS = s(SURVIVAL, "Items", "items.junk", "Junk items", "Items that may be thrown away.", Registry.ITEM,
		"rotten_flesh", "spider_eye", "poisonous_potato", "dirt", "gravel", "andesite", "diorite", "granite", "cobbled_deepslate", "tuff", "netherrack", "bone").child(JUNK);

	// ================================================================ Interface
	public static final Setting.Choice<UiStyle> UI_STYLE = e(UI, "Look", "ui.style", "Interface style", "Restyles the whole interface: font, layout, shapes and animations. Minecraft is the classic look.", UiStyle.MINECRAFT);
	public static final Setting.Choice<Accent> ACCENT = e(UI, "Look", "ui.accent", "Accent colour", "Main colour of the interface and the path overlay.", Accent.CYAN);
	public static final Setting.Choice<PanelScale> UI_SCALE = e(UI, "Look", "ui.scale", "Panel scale", "Pixel size of the control panel. Auto picks a crisp size for your window.", PanelScale.AUTO);
	public static final Setting.Bool UI_ANIM = b(UI, "Look", "ui.anim", "Animations", "Animate the interface.", true);
	public static final Setting.Bool UI_SOUND = b(UI, "Look", "ui.sound", "Click sounds", "Play sounds when using the interface.", true);
	public static final Setting.Num UI_DIM = f(UI, "Look", "ui.dim", "Background dim", "How much the game is darkened behind the interface.", 0.45, 0, 0.9, 0.05, "");
	public static final Setting.Bool HUD = b(UI, "Overlay", "hud.enabled", "Status overlay", "Show a small status panel in-game.", true);
	public static final Setting.Choice<Corner> HUD_CORNER = e(UI, "Overlay", "hud.corner", "Overlay corner", "Where the status panel sits.", Corner.TOP_LEFT).child(HUD);
	public static final Setting.Bool RENDER_PATH = b(UI, "World overlay", "render.path", "Show path", "Draw the planned path in the world.", true);
	public static final Setting.Bool RENDER_GOAL = b(UI, "World overlay", "render.goal", "Show goal", "Highlight the destination.", true);
	public static final Setting.Bool RENDER_BLOCKS = b(UI, "World overlay", "render.blocks", "Show block targets", "Highlight blocks to break or place.", true);
	public static final Setting.Bool RENDER_TOP = b(UI, "World overlay", "render.top", "Through walls", "Draw the overlay on top of terrain.", true);
	public static final Setting.Bool PREVIEW = b(UI, "Schematic preview", "preview.enabled", "Schematic preview", "Show a loaded schematic in the world: what is still missing as ghost blocks, wrong blocks in red, blocks that do not belong in purple. A block disappears from it the moment it is placed right.", true);
	public static final Setting.Bool PREVIEW_GHOST = b(UI, "Schematic preview", "preview.ghost", "Ghost blocks", "Draw the blocks that are still missing the way they will look.", true).child(PREVIEW);
	public static final Setting.Bool PREVIEW_EXTRA = b(UI, "Schematic preview", "preview.extra", "Mark extra blocks", "Mark blocks inside the schematic's box that do not belong there, and the scaffolding the bot put down on the way, in purple.", true).child(PREVIEW);
	public static final Setting.Num PREVIEW_RANGE = i(UI, "Schematic preview", "preview.range", "Preview distance", "How far from you the preview is drawn.", 64, 16, 160, 4, "blocks").child(PREVIEW);
	public static final Setting.Num PREVIEW_MAX = i(UI, "Schematic preview", "preview.max", "Ghost block limit", "Most ghost blocks drawn at once, the nearest first. Lower it if the game gets slow with a very big schematic.", 1500, 100, 6000, 100, "").child(PREVIEW);

	// ================================================================ Logging
	public static final Setting.Bool SCAN = b(LOGGING, "Scanner", "scan.enabled", "Scanner", "Watch what the game has loaded around you (containers, spawners, players, portals, valuable items...) and log it on this page and on the map. It only reads what the server already sent you; nothing is sent back.", true);
	public static final Setting.Bool SCAN_CHAT = b(LOGGING, "Scanner", "scan.alertChat", "Chat alerts", "Print a message in your chat when something new is logged. Click the coordinates in it to get a travel command in the chat box.", true).child(SCAN);
	public static final Setting.Bool SCAN_SOUND = b(LOGGING, "Scanner", "scan.alertSound", "Alert sound", "Play a short chime with every chat alert.", true).child(SCAN_CHAT);
	public static final Setting.Bool SCAN_HIGHLIGHT = b(LOGGING, "Scanner", "scan.highlight", "Highlight finds", "Draw boxes around logged finds in the world, and around every container of a stash. Follows 'Through walls' in the Interface tab.", true).child(SCAN);
	public static final Setting.Num SCAN_HIGHLIGHT_RANGE = i(LOGGING, "Scanner", "scan.highlightRange", "Highlight distance", "Only finds this close to you are drawn.", 96, 16, 256, 8, "blocks").child(SCAN_HIGHLIGHT);

	public static final Setting.Bool STASH = b(LOGGING, "Containers", "scan.stash", "Stash finder", "Log a place where many containers (chests, barrels, shulker boxes...) stand close together, and send an alert.", true).child(SCAN);
	public static final Setting.Num STASH_MIN = i(LOGGING, "Containers", "scan.stashMin", "Containers for an alert", "How many containers have to be within the area below to count as a stash. A double chest counts once. Villages and other buildings of the world have a few, a base has many.", 10, 2, 200, 1, "").child(STASH);
	public static final Setting.Num STASH_AREA = i(LOGGING, "Containers", "scan.stashArea", "Stash area", "Containers up to this many blocks apart (in every direction) are counted together.", 16, 4, 64, 1, "blocks").child(STASH);
	public static final Setting.IdSet STASH_TYPES = s(LOGGING, "Containers", "scan.stashTypes", "Container types", "The blocks that count as a container.", Registry.BLOCK,
		"chest", "trapped_chest", "barrel", "ender_chest", "shulker_box", "white_shulker_box", "orange_shulker_box", "magenta_shulker_box", "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box",
		"pink_shulker_box", "gray_shulker_box", "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box", "brown_shulker_box", "green_shulker_box", "red_shulker_box", "black_shulker_box").child(STASH);

	public static final Setting.Bool SEARCH = b(LOGGING, "Block search", "scan.search", "Block search", "Log veins of the blocks listed below, wherever the server sent them (like the search module of a hacked client). A server with anti-xray hides or fakes ores.", false).child(SCAN);
	public static final Setting.IdSet SEARCH_BLOCKS = s(LOGGING, "Block search", "scan.searchBlocks", "Search blocks", "The blocks to look for.", Registry.BLOCK,
		"ancient_debris", "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore").child(SEARCH);
	public static final Setting.Num SEARCH_MIN = i(LOGGING, "Block search", "scan.searchMin", "Blocks per find", "A vein must have at least this many blocks to be logged.", 1, 1, 64, 1, "").child(SEARCH);
	public static final Setting.Bool SEARCH_EXPOSED = b(LOGGING, "Block search", "scan.searchExposed", "Only exposed blocks", "Legit mode: only blocks that touch air or liquid (that could be seen from a cave or the surface).", false).child(SEARCH);

	public static final Setting.Bool SPAWNERS = b(LOGGING, "Other finds", "scan.spawners", "Spawners and vaults", "Log mob spawners, trial spawners and vaults.", true).child(SCAN);
	public static final Setting.Bool PLAYERS = b(LOGGING, "Other finds", "scan.players", "Players", "Log other players that come into view: who, where, what they hold.", true).child(SCAN);
	public static final Setting.Num PLAYERS_AGAIN = i(LOGGING, "Other finds", "scan.playersAgain", "Same player again after", "A player that was out of view for this long is reported again when they return.", 120, 10, 3600, 10, "s").child(PLAYERS);
	public static final Setting.Bool PORTALS = b(LOGGING, "Other finds", "scan.portals", "Portals", "Log nether portals, end portal frames, end portals and end gateways.", true).child(SCAN);
	public static final Setting.Bool BASES = b(LOGGING, "Other finds", "scan.bases", "Beacons and conduits", "Log beacons and conduits: a base is usually near.", true).child(SCAN);
	public static final Setting.Bool ITEMS = b(LOGGING, "Other finds", "scan.items", "Valuable items", "Log item frames and dropped items that hold something from the list below.", true).child(SCAN);
	public static final Setting.IdSet ITEM_LIST = s(LOGGING, "Other finds", "scan.itemList", "Valuable item list", "Items worth noting.", Registry.ITEM,
		"elytra", "enchanted_golden_apple", "totem_of_undying", "netherite_ingot", "netherite_block", "nether_star", "dragon_egg", "trident", "heart_of_the_sea", "diamond_block", "beacon", "mace").child(ITEMS);
	public static final Setting.Bool SIGNS = b(LOGGING, "Other finds", "scan.signs", "Signs with text", "Log every sign that has writing on it. There can be a lot of them.", false).child(SCAN);
	public static final Setting.Bool DEATHS = b(LOGGING, "Other finds", "scan.deaths", "Your deaths", "Remember where you died.", true).child(SCAN);

	public static final Setting.Bool MAP_RECORD = b(LOGGING, "Map", "map.record", "Record the map", "Paint the terrain you see into the Map page. It is saved per world and dimension.", true);

	// ================================================================ Advanced
	public static final Setting.Num PLAN_TIME = i(ADV, "Pathfinding", "adv.planTime", "Planning time", "Time the planner gets before settling for the best partial path.", 600, 100, 5000, 50, "ms");
	public static final Setting.Num PLAN_FAIL = i(ADV, "Pathfinding", "adv.planFail", "Give-up time", "Maximum planning time when no acceptable path is found.", 4000, 500, 20000, 100, "ms");
	public static final Setting.Num HEURISTIC = f(ADV, "Pathfinding", "adv.heuristic", "Heuristic weight", "Above 1 plans faster but finds slightly longer paths.", 1.0, 0.8, 3.0, 0.05, "x");
	public static final Setting.Num MAX_NODES = i(ADV, "Pathfinding", "adv.maxNodes", "Max search nodes", "Safety limit of the planner.", 400000, 20000, 2000000, 20000, "");
	public static final Setting.Num SPLICE = i(ADV, "Pathfinding", "adv.splice", "Plan ahead distance", "Start planning the next path segment this many blocks before the end.", 30, 5, 100, 1, "blocks");
	public static final Setting.Num STUCK_TICKS = i(ADV, "Pathfinding", "adv.stuck", "Stuck timeout", "Ticks without progress before the bot re-plans.", 80, 20, 400, 10, "ticks");
	public static final Setting.Bool DEBUG = b(ADV, "Diagnostics", "adv.debug", "Debug logging", "Print detailed planner and executor messages.", false);

	private Settings() {
	}

	// ============================================================ lookup / IO
	private static boolean dirty;
	private static long dirtySince;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Last bot state, separate from profiles and from the temporary disconnect reset. */
	private static boolean rememberedBotEnabled;
	/** Waypoints and other small pieces of state saved next to the settings. */
	public static final Map<String, int[]> WAYPOINTS = new LinkedHashMap<>();
	/** Text of the dashboard Travel boxes (X, Y, Z), kept so it survives closing the GUI and restarting. */
	public static final String[] TRAVEL = {"", "", ""};
	/** Name of the selected profile ("" = none). Every settings change is saved into it as well. */
	public static String profile = "";

	public static void setTravel(int i, String v) {
		if (TRAVEL[i].equals(v)) return;
		TRAVEL[i] = v;
		markDirty();
	}

	public static void markDirty() {
		if (!dirty) dirtySince = System.currentTimeMillis();
		dirty = true;
	}

	public static void rememberBotEnabled(boolean enabled) {
		if (rememberedBotEnabled == enabled) return;
		rememberedBotEnabled = enabled;
		markDirty();
	}

	public static boolean restoreBotEnabled() {
		return REMEMBER_ENABLED.on() && rememberedBotEnabled;
	}

	public static Setting<?> byId(String id) {
		for (Setting<?> s : Setting.ALL) if (s.id.equalsIgnoreCase(id)) return s;
		return null;
	}

	public static List<Setting<?>> in(String category) {
		List<Setting<?>> out = new ArrayList<>();
		for (Setting<?> s : Setting.ALL) if (s.category.equals(category)) out.add(s);
		return out;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanised_baritone.json");
	}

	public static void load() {
		rememberedBotEnabled = false;
		try {
			if (!Files.exists(file())) return;
			JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			JsonElement enabled = root.get("botEnabled");
			if (enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) rememberedBotEnabled = enabled.getAsBoolean();
			JsonObject st = root.has("settings") ? root.getAsJsonObject("settings") : new JsonObject();
			for (Setting<?> s : Setting.ALL) {
				JsonElement e = st.get(s.id);
				if (e != null) s.fromJson(e);
			}
			if (root.has("profile")) {
				String p = root.get("profile").getAsString();
				if (Profiles.saved().contains(p)) profile = p;
			}
			if (root.has("travel")) {
				var t = root.getAsJsonArray("travel");
				for (int k = 0; k < TRAVEL.length && k < t.size(); k++) TRAVEL[k] = t.get(k).getAsString();
			}
			if (root.has("waypoints")) {
				for (var en : root.getAsJsonObject("waypoints").entrySet()) {
					var a = en.getValue().getAsJsonArray();
					WAYPOINTS.put(en.getKey(), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
				}
			}
		} catch (Exception ex) {
			System.err.println("[humanised_baritone] could not read config: " + ex);
		}
	}

	public static void save() {
		dirty = false;
		try {
			JsonObject root = new JsonObject();
			JsonObject st = new JsonObject();
			for (Setting<?> s : Setting.ALL) if (!s.isDefault()) st.add(s.id, s.toJson());
			root.add("settings", st);
			root.addProperty("botEnabled", rememberedBotEnabled);
			JsonObject wp = new JsonObject();
			for (var en : WAYPOINTS.entrySet()) {
				var a = new com.google.gson.JsonArray();
				for (int v : en.getValue()) a.add(v);
				wp.add(en.getKey(), a);
			}
			root.add("waypoints", wp);
			var tr = new com.google.gson.JsonArray();
			for (String v : TRAVEL) tr.add(v);
			root.add("travel", tr);
			root.addProperty("profile", profile);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(root));
			if (!profile.isEmpty()) Profiles.write(profile);
		} catch (IOException ex) {
			System.err.println("[humanised_baritone] could not write config: " + ex);
		}
	}

	/** Called every tick; writes the config a moment after the last change. */
	public static void tick() {
		if (dirty && System.currentTimeMillis() - dirtySince > 1500) save();
	}

	/** Applies a map of {setting id -> text value} such as a preset. */
	public static void apply(Map<String, String> values) {
		for (var en : values.entrySet()) {
			Setting<?> s = byId(en.getKey());
			if (s != null) s.parse(en.getValue());
		}
	}

	public static void resetAll() {
		for (Setting<?> s : Setting.ALL) s.reset();
	}
}
