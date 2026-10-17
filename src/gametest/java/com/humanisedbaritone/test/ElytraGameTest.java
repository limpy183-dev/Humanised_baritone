package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.behave.ElytraJump;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.Task;
import com.humanisedbaritone.task.Tasks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.LocalPlayer;

/**
 * The elytra jump: timed jumps on landing, no fireworks. In the real game, in a flat world with the elytra on:
 * <ul>
 * <li>{@code assist}: the player holds forward and looks down, the bot only presses jump. It has to reach more than five times a sprint (5.6 blocks a second); with the helper off nothing happens.</li>
 * <li>{@code wall}: a wall ahead. The helper brakes and arrives at walking pace, no damage. With the braking off the same wall hurts (the premise).</li>
 * <li>{@code wear}: how much a bounce wears the elytra (the game counts nine gliding ticks in a row, a bounce has fewer).</li>
 * <li>{@code tunnel}: a three-high, three-wide tunnel, the way a bounce highway is built, works; a two-high one is refused (the server would put the player back).</li>
 * <li>{@code gates}: no elytra, an elytra that is nearly worn out, hunger: no bounce.</li>
 * <li>{@code drive}: the engine of the wander task flies a heading, brakes in front of a wall, holds a diagonal.</li>
 * <li>{@code goto}: a far goal over open ground is bounced when an elytra is worn (the navigator's shortcut), walked when not, and still reached exactly.</li>
 * <li>{@code wander}: the task itself, with and without an elytra, in an open field and among walls: stays within its radius, bounces, never gets hurt.</li>
 * <li>{@code chest}: the passive armor swap does not take the elytra off for a chestplate.</li>
 * </ul>
 * {@code HB_ONLY=assist,wall,...} picks scenarios; traces go to build/elytra/NAME.csv.
 */
public class ElytraGameTest extends BotTest {
	/** The strip runs east from x = 0 along z = 0; the wander field lies around (0, 400). */
	private static final int FIELD_Z = 400;

	/** One recorded run: per tick x, z, horizontal speed (blocks per tick, from the movement), health, on the ground, gliding, jump key. */
	private static final class Run {
		final List<double[]> t = new ArrayList<>();
		double minHp = 20;

		double mean(int from, int to) {
			double s = 0;
			int n = 0;
			for (int i = Math.max(0, from); i < Math.min(t.size(), to); i++, n++) s += t.get(i)[2];
			return n == 0 ? 0 : s / n;
		}

		double meanLast(int n) {
			return mean(t.size() - n, t.size());
		}

		double max() {
			double m = 0;
			for (double[] r : t) m = Math.max(m, r[2]);
			return m;
		}

		double[] last() {
			return t.get(t.size() - 1);
		}

		/**
		 * Times it ran into something at speed: a tick with the game's own collision flag set after one at more than 0.7 blocks, or a drop from
		 * more than 0.8 to less than 0.4 in a tick. The integrated server does not turn that into damage the way a real one does (it works from
		 * its own idea of the speed), so this is what a crash looks like here.
		 */
		int crashes() {
			int n = 0;
			for (int i = 1; i < t.size(); i++) if (t.get(i)[11] > 0 && t.get(i - 1)[2] > 0.7 || t.get(i - 1)[2] > 0.8 && t.get(i)[2] < 0.4) n++;
			return n;
		}

		/** Distance walked or flown. */
		double path() {
			double s = 0;
			for (double[] r : t) s += r[2];
			return s;
		}
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			load(ctx, sp, -20, -20, 540, 20);
			// forceload takes at most 256 chunks a command
			for (int[] q : new int[][]{{-170, -170}, {1, -170}, {-170, 1}, {1, 1}}) load(ctx, sp, q[0], FIELD_Z + q[1], q[0] + 169, FIELD_Z + q[1] + 169);
			botOn(ctx);
			ctx.runOnClient(mc -> Settings.VAR_FIDGET.set(0.0)); // an idle hop would be taken for a bounce
			if (only("assist")) assist(ctx, sp);
			if (only("wall")) wall(ctx, sp);
			if (only("tunnel")) tunnel(ctx, sp);
			if (only("wear")) wear(ctx, sp);
			if (only("gates")) gates(ctx, sp);
			if (only("drive")) drive(ctx, sp);
			if (only("goto")) travel(ctx, sp);
			if (only("wander")) wander(ctx, sp);
			if (only("chest")) chest(ctx, sp);
			finish();
		}
	}

	// ------------------------------------------------------------------ set-up and recording

	private void stage(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double z, float yaw, boolean elytra) {
		stage(ctx, sp, x, z, yaw, elytra, true);
	}

	private void stage(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double z, float yaw, boolean elytra, boolean fed) {
		revive(ctx, sp);
		cmds(sp, "gamemode survival @a", fed ? "effect give @a minecraft:saturation 1000 255 true" : "effect clear @a", "kill @e[type=item]",
			elytra ? "item replace entity @a armor.chest with minecraft:elytra[minecraft:unbreakable={}]" : "item replace entity @a armor.chest with minecraft:air",
			"tp @a " + x + " -60 " + z + " " + yaw + " 6");
		ctx.waitTicks(25);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA.set(true);
			Settings.ELYTRA_HELPER.set(false);
			Settings.ELYTRA_SAFE.set(true);
			Settings.ELYTRA_KEEP.set(8.0);
			Settings.WANDER_RADIUS.set(250.0);
		});
	}

	/** Samples every tick until {@code ticks} are over or {@code stop} says so; {@code pre} runs before each tick (the player's own mouse and keys). */
	private Run record(ClientGameTestContext ctx, String name, int ticks, Consumer<LocalPlayer> pre, Predicate<LocalPlayer> stop) {
		Run r = new Run();
		double[][] last = {client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()})};
		int[] n = {0};
		until(ctx, name, mc -> {
			var p = mc.player;
			double sp = Math.hypot(p.getX() - last[0][0], p.getZ() - last[0][1]);
			last[0] = new double[]{p.getX(), p.getZ()};
			r.t.add(new double[]{p.getX(), p.getZ(), sp, p.getHealth(), p.onGround() ? 1 : 0, p.isFallFlying() ? 1 : 0, p.input.keyPresses.jump() ? 1 : 0, p.getY(), p.getDeltaMovement().y, p.isSprinting() ? 1 : 0, p.input.keyPresses.forward() ? 1 : 0, p.horizontalCollision ? 1 : 0});
			r.minHp = Math.min(r.minHp, p.getHealth());
			if (stop != null && stop.test(p)) return true;
			if (pre != null) pre.accept(p);
			return ++n[0] >= ticks;
		}, ticks + 100);
		dump(name, r);
		return r;
	}

	private void dump(String name, Run r) {
		try {
			Path dir = Path.of("..", "..", "elytra");
			Files.createDirectories(dir);
			StringBuilder sb = new StringBuilder("tick,x,z,speed,hp,ground,glide,jump,y,vy,sprinting,forward,hcol\n");
			for (int i = 0; i < r.t.size(); i++) {
				double[] c = r.t.get(i);
				sb.append(String.format(Locale.ROOT, "%d,%.3f,%.3f,%.4f,%.1f,%d,%d,%d,%.3f,%.3f,%d,%d,%d%n", i, c[0], c[1], c[2], c[3], (int) c[4], (int) c[5], (int) c[6], c[7], c[8], (int) c[9], (int) c[10], (int) c[11]));
			}
			Files.writeString(dir.resolve(name + ".csv"), sb.toString());
		} catch (IOException e) {
			System.out.println("[HBTEST] could not write the trace: " + e);
		}
	}

	private static String blocksPerSecond(double perTick) {
		return String.format(Locale.ROOT, "%.1f b/s", perTick * 20);
	}

	// ------------------------------------------------------------------ the helper: the player steers, the bot times the jumps

	private Run helper(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, boolean helper, boolean safe, int ticks, Predicate<LocalPlayer> stop) {
		stage(ctx, sp, 0.5, 0.5, -90, true);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA_HELPER.set(helper);
			Settings.ELYTRA_SAFE.set(safe);
			mc.options.keyUp.setDown(true); // the player's own keys: forward held, the camera looking steeply down
		});
		Run r = record(ctx, name, ticks, p -> {
			p.setYRot(-90);
			p.setXRot(78);
		}, stop);
		ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
		return r;
	}

	private void assist(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Run on = helper(ctx, sp, "assist", true, true, 330, null);
		double mean = on.meanLast(80), max = on.max();
		report("assist-bounces", mean >= 1.5 && max >= 1.7 && on.minHp >= 19.9, String.format(Locale.ROOT, "last 80 ticks %s, fastest %s, at x=%.0f, lowest hp %.1f", blocksPerSecond(mean), blocksPerSecond(max), on.last()[0], on.minHp));
		// the baseline: the same keys without the helper are a walk
		Run off = helper(ctx, sp, "assist-off", false, true, 120, null);
		report("assist-off-walks", off.meanLast(60) < 0.45, String.format(Locale.ROOT, "last 60 ticks %s", blocksPerSecond(off.meanLast(60))));
		// not looking down (the horizon): the helper must not take over a plain walk with an elytra on
		stage(ctx, sp, 0.5, 0.5, -90, true);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA_HELPER.set(true);
			mc.options.keyUp.setDown(true);
		});
		Run level = record(ctx, "assist-level", 120, p -> {
			p.setYRot(-90);
			p.setXRot(5);
		}, null);
		ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
		report("assist-level-camera-no-bounce", level.meanLast(60) < 0.45, String.format(Locale.ROOT, "last 60 ticks %s", blocksPerSecond(level.meanLast(60))));
	}

	private void wall(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int wx = 300;
		cmd(sp, "fill " + wx + " -60 -12 " + wx + " -30 12 stone");
		ctx.waitTicks(5);
		Run safe = helper(ctx, sp, "wall-safe", true, true, 560, p -> p.getX() > wx - 0.5 - 0.31 && Math.abs(p.getDeltaMovement().x) < 0.01);
		double[] l = safe.last();
		// the speed within three blocks of the wall: a walk, or less
		double near = 0;
		for (double[] r : safe.t) if (r[0] > wx - 3) near = Math.max(near, r[2]);
		report("wall-safe-stops", safe.max() >= 1.4 && near < 0.5 && safe.minHp >= 19.9 && l[0] < wx && safe.crashes() == 0, String.format(Locale.ROOT, "fastest %s, within 3 blocks of the wall %s, ended at x=%.1f (wall at %d), lowest hp %.1f, crashes %d", blocksPerSecond(safe.max()), blocksPerSecond(near), l[0], wx, safe.minHp, safe.crashes()));
		// the premise: without the braking it arrives at full speed (the integrated server does not turn that into damage the way a real one does: its own idea of the speed lags, so health is not compared)
		Run unsafe = helper(ctx, sp, "wall-unsafe", true, false, 560, p -> p.getX() > wx - 0.5 - 0.31 && Math.abs(p.getDeltaMovement().x) < 0.01);
		double hit = 0;
		for (double[] r : unsafe.t) if (r[0] > wx - 3) hit = Math.max(hit, r[2]);
		report("wall-unsafe-arrives-fast", hit >= 1.4 && unsafe.crashes() >= 1, String.format(Locale.ROOT, "without the braking it reaches the wall at %s and crashes (%d)", blocksPerSecond(hit), unsafe.crashes()));
		cmd(sp, "fill " + wx + " -60 -12 " + wx + " -30 12 air");
		cmd(sp, "effect clear @a");
	}

	private void wear(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stage(ctx, sp, 0.5, 0.5, -90, true);
		cmd(sp, "item replace entity @a armor.chest with minecraft:elytra"); // one that can wear out
		ctx.waitTicks(3);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA_HELPER.set(true);
			mc.options.keyUp.setDown(true);
		});
		Run r = record(ctx, "wear", 500, p -> {
			p.setYRot(-90);
			p.setXRot(78);
		}, null);
		ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
		int dmg = client(ctx, mc -> mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getDamageValue());
		// plain gliding wears one point in twenty ticks: 500 ticks would be 25
		report("wear-bounce-is-cheap", r.meanLast(80) >= 1.5 && dmg < 10, String.format(Locale.ROOT, "%d points of 432 after 500 ticks of bouncing at %s", dmg, blocksPerSecond(r.meanLast(80))));
	}

	private void tunnel(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// three free blocks above the floor, a corridor of three between two walls: the way a bounce highway is built
		cmds(sp, "fill 0 -57 -1 330 -57 1 stone", "fill 0 -60 -2 330 -57 -2 stone", "fill 0 -60 2 330 -57 2 stone");
		ctx.waitTicks(5);
		Run r = helper(ctx, sp, "tunnel-three", true, true, 330, null);
		report("tunnel-three-high-bounces", r.meanLast(80) >= 1.5 && r.minHp >= 19.9 && r.last()[0] > 150 && Math.abs(r.last()[1]) < 1.5, String.format(Locale.ROOT, "last 80 ticks %s, fastest %s, at x=%.0f z=%.1f, lowest hp %.1f", blocksPerSecond(r.meanLast(80)), blocksPerSecond(r.max()), r.last()[0], r.last()[1], r.minHp));
		cmds(sp, "fill 0 -57 -1 330 -57 1 air", "fill 0 -60 -2 330 -57 -2 air", "fill 0 -60 2 330 -57 2 air");
		// two blocks only: the server counts the standing body (1.8) while the glide is 0.6 high, takes the hop for a move through the ceiling and puts the player back every time.
		// The helper must not try: it walks on at walking pace instead of being thrown back and forth
		cmds(sp, "fill 0 -58 -1 330 -58 1 stone", "fill 0 -60 -2 330 -58 -2 stone", "fill 0 -60 2 330 -58 2 stone");
		ctx.waitTicks(5);
		Run low = helper(ctx, sp, "tunnel-two", true, true, 200, null);
		report("tunnel-two-high-walks-on", low.meanLast(60) < 0.45 && low.last()[0] > 25, String.format(Locale.ROOT, "last 60 ticks %s, at x=%.0f", blocksPerSecond(low.meanLast(60)), low.last()[0]));
		cmds(sp, "fill 0 -58 -1 330 -58 1 air", "fill 0 -60 -2 330 -58 -2 air", "fill 0 -60 2 330 -58 2 air");
		ctx.waitTicks(5);
	}

	private void gates(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// no elytra at all
		stage(ctx, sp, 0.5, 0.5, -90, false);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA_HELPER.set(true);
			mc.options.keyUp.setDown(true);
		});
		Run none = record(ctx, "gate-none", 120, p -> {
			p.setYRot(-90);
			p.setXRot(78);
		}, null);
		report("gate-no-elytra", none.meanLast(60) < 0.45, String.format(Locale.ROOT, "last 60 ticks %s", blocksPerSecond(none.meanLast(60))));
		// 5 points left of 432: under the 8 percent kept back
		stage(ctx, sp, 0.5, 0.5, -90, true);
		cmd(sp, "item replace entity @a armor.chest with minecraft:elytra[minecraft:damage=427]");
		ctx.waitTicks(3);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA_HELPER.set(true);
			mc.options.keyUp.setDown(true);
		});
		Run worn = record(ctx, "gate-worn", 120, p -> {
			p.setYRot(-90);
			p.setXRot(78);
		}, null);
		report("gate-worn-elytra", worn.meanLast(60) < 0.45, String.format(Locale.ROOT, "last 60 ticks %s", blocksPerSecond(worn.meanLast(60))));
		// too hungry to sprint
		cmd(sp, "difficulty easy"); // on peaceful the hunger bar refills by itself
		stage(ctx, sp, 0.5, 0.5, -90, true, false);
		sp.getServer().runOnServer(srv -> {
			var pl = srv.getPlayerList().getPlayers().get(0);
			pl.getFoodData().setFoodLevel(6);
			pl.getFoodData().setSaturation(0f);
		});
		ctx.waitTicks(3);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA_HELPER.set(true);
			mc.options.keyUp.setDown(true);
		});
		Run hungry = record(ctx, "gate-hungry", 120, p -> {
			p.setYRot(-90);
			p.setXRot(78);
		}, null);
		report("gate-hungry", hungry.meanLast(60) < 0.45, String.format(Locale.ROOT, "last 60 ticks %s, food %d", blocksPerSecond(hungry.meanLast(60)), client(ctx, mc -> mc.player.getFoodData().getFoodLevel())));
		ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
		cmd(sp, "difficulty peaceful");
	}

	// ------------------------------------------------------------------ the engine of the wander task

	/** Flies a heading with {@link ElytraJump#drive} until it lets go. */
	private static final class DriveTask extends Task {
		private final double heading, cap;
		private double x0, z0;

		DriveTask(double heading, double cap) {
			this.heading = heading;
			this.cap = cap;
		}

		@Override public String name() {
			return "Bounce test";
		}

		@Override public void start() {
			var p = net.minecraft.client.Minecraft.getInstance().player;
			x0 = p.getX();
			z0 = p.getZ();
		}

		@Override public boolean tick() {
			var p = net.minecraft.client.Minecraft.getInstance().player;
			return !ElytraJump.drive(heading, cap - Math.hypot(p.getX() - x0, p.getZ() - z0));
		}
	}

	private Run drive(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, double x, double z, double heading, double cap, int ticks) {
		stage(ctx, sp, x, z, (float) heading, true);
		ctx.runOnClient(mc -> Tasks.start(new DriveTask(heading, cap)));
		Run r = record(ctx, name, ticks, null, p -> !Bot.busy());
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(3);
		return r;
	}

	private void drive(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Run flat = drive(ctx, sp, "drive-flat", 0.5, 0.5, -90, 330, 700);
		double[] l = flat.last();
		report("drive-flat", flat.max() >= 1.5 && flat.minHp >= 19.9 && l[0] > 200 && l[0] < 332 && Math.abs(l[1]) < 3 && flat.meanLast(5) < 0.05 && flat.crashes() == 0,
			String.format(Locale.ROOT, "fastest %s, stopped at x=%.1f z=%.1f (cap 330), lowest hp %.1f, %d ticks", blocksPerSecond(flat.max()), l[0], l[1], flat.minHp, flat.t.size()));
		int wx = 200;
		cmd(sp, "fill " + wx + " -60 -12 " + wx + " -30 12 stone");
		ctx.waitTicks(5);
		Run wall = drive(ctx, sp, "drive-wall", 0.5, 0.5, -90, 400, 700);
		l = wall.last();
		report("drive-wall", wall.max() >= 1.2 && wall.minHp >= 19.9 && l[0] < wx - 1.0 && l[0] > 100 && wall.crashes() == 0, String.format(Locale.ROOT, "fastest %s, stopped at x=%.1f (wall at %d: %.1f to spare), lowest hp %.1f", blocksPerSecond(wall.max()), l[0], wx, wx - l[0], wall.minHp));
		cmd(sp, "fill " + wx + " -60 -12 " + wx + " -30 12 air");
		// things a walker steps over or through but a body in the air runs into: a row of bottom slabs (half a block), of open trapdoors (thin, upright) and of carpets
		for (String[] s : new String[][]{{"slab", "minecraft:stone_slab[type=bottom]"}, {"trapdoor", "minecraft:oak_trapdoor[open=true,facing=east,half=bottom]"}, {"carpet", "minecraft:white_carpet"}}) {
			int sx = 170;
			cmd(sp, "fill " + sx + " -60 -5 " + sx + " -60 5 " + s[1]);
			ctx.waitTicks(5);
			Run low = drive(ctx, sp, "drive-" + s[0], 0.5, 0.5, -90, 400, 700);
			l = low.last();
			report("drive-" + s[0] + "-row", low.max() >= 1.2 && low.minHp >= 19.9 && l[0] < sx - 1.0 && low.crashes() == 0, String.format(Locale.ROOT, "fastest %s, stopped at x=%.1f (row at %d: %.1f to spare), crashes %d, lowest hp %.1f", blocksPerSecond(low.max()), l[0], sx, sx - l[0], low.crashes(), low.minHp));
			cmd(sp, "fill " + sx + " -60 -5 " + sx + " -60 5 air");
		}
		// diagonal, yaw -45 (towards +x and +z), in the field: the line is x - x0 = z - z0
		double dx0 = -150.5, dz0 = FIELD_Z - 150.5;
		Run diag = drive(ctx, sp, "drive-diagonal", dx0, dz0, -45, 200, 700);
		l = diag.last();
		double off = Math.abs((l[0] - dx0) - (l[1] - dz0)) / Math.sqrt(2);
		report("drive-diagonal", diag.max() >= 1.2 && diag.minHp >= 19.9 && off < 4 && l[0] - dx0 > 80, String.format(Locale.ROOT, "fastest %s, stopped at %.1f %.1f, %.1f off the line, lowest hp %.1f", blocksPerSecond(diag.max()), l[0], l[1], off, diag.minHp));
		// a heading that is nothing round, yaw 150 (towards -x and -z)
		double ox = 60.5, oz = FIELD_Z + 160.5;
		Run odd = drive(ctx, sp, "drive-odd", ox, oz, 150, 200, 700);
		l = odd.last();
		double h = Math.toRadians(150), ux = -Math.sin(h), uz = Math.cos(h);
		double along = (l[0] - ox) * ux + (l[1] - oz) * uz, side = Math.abs(-(l[0] - ox) * uz + (l[1] - oz) * ux);
		report("drive-odd-heading", odd.max() >= 1.2 && odd.minHp >= 19.9 && along > 80 && side < 4, String.format(Locale.ROOT, "fastest %s, stopped at %.1f %.1f: %.0f along the heading, %.1f beside it, lowest hp %.1f", blocksPerSecond(odd.max()), l[0], l[1], along, side, odd.minHp));
	}

	// ------------------------------------------------------------------ the navigator's shortcut

	private Run trip(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, boolean elytra, boolean setting, String command, int ticks) {
		stage(ctx, sp, 0.5, 0.5, -90, elytra);
		ctx.runOnClient(mc -> {
			Settings.ELYTRA.set(setting);
			Commands.run(command);
		});
		Run r = record(ctx, name, ticks, null, p -> !Bot.busy());
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(3);
		return r;
	}

	private void travel(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Run r = trip(ctx, sp, "goto-bounce", true, true, "goto 300 -60 0", 1200);
		double[] l = r.last();
		double mean = r.path() / r.t.size() * 20, d = Math.hypot(l[0] - 300.5, l[1] - 0.5);
		report("goto-bounces", r.max() >= 1.3 && d < 3 && r.minHp >= 19.9 && mean >= 13 && r.crashes() == 0, String.format(Locale.ROOT, "fastest %s, whole trip %s (%d ticks), %.1f from the goal, lowest hp %.1f", blocksPerSecond(r.max()), blocksPerSecond(mean / 20), r.t.size(), d, r.minHp));
		Run w = trip(ctx, sp, "goto-walks", true, false, "goto 300 -60 0", 1800);
		l = w.last();
		d = Math.hypot(l[0] - 300.5, l[1] - 0.5);
		report("goto-setting-off-walks", w.max() < 0.8 && d < 3, String.format(Locale.ROOT, "fastest %s, %.1f from the goal, %d ticks", blocksPerSecond(w.max()), d, w.t.size()));
		Run n = trip(ctx, sp, "goto-no-elytra", false, true, "goto 300 -60 0", 1800);
		l = n.last();
		d = Math.hypot(l[0] - 300.5, l[1] - 0.5);
		report("goto-no-elytra-walks", n.max() < 0.8 && d < 3, String.format(Locale.ROOT, "fastest %s, %.1f from the goal, %d ticks", blocksPerSecond(n.max()), d, n.t.size()));
		// a wall across the way: the route bends round it, the shortcut must not take the player into it
		cmd(sp, "fill 150 -60 -14 150 -30 14 stone");
		ctx.waitTicks(5);
		Run a = trip(ctx, sp, "goto-around-wall", true, true, "goto 300 -60 0", 1800);
		l = a.last();
		d = Math.hypot(l[0] - 300.5, l[1] - 0.5);
		report("goto-around-wall", d < 3 && a.minHp >= 19.9 && a.crashes() == 0, String.format(Locale.ROOT, "fastest %s, %.1f from the goal, %d ticks, lowest hp %.1f", blocksPerSecond(a.max()), d, a.t.size(), a.minHp));
		cmd(sp, "fill 150 -60 -14 150 -30 14 air");
		ctx.waitTicks(5);
	}

	// ------------------------------------------------------------------ the task

	private Run wandering(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, boolean elytra, int radius, int ticks) {
		stage(ctx, sp, 0.5, FIELD_Z + 0.5, 0, elytra);
		ctx.runOnClient(mc -> Commands.run("wander " + radius));
		Run r = record(ctx, name, ticks, null, p -> !Bot.busy());
		return r;
	}

	private void wander(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Run open = wandering(ctx, sp, "wander-open", true, 120, 1500);
		double far = 0;
		for (double[] r : open.t) far = Math.max(far, Math.hypot(r[0] - 0.5, r[1] - (FIELD_Z + 0.5)));
		boolean alive = client(ctx, mc -> Bot.busy());
		report("wander-bounces-inside-radius", open.max() >= 1.2 && far <= 120 + 35 && open.minHp >= 19.9 && alive && open.path() > 300 && open.crashes() == 0,
			String.format(Locale.ROOT, "fastest %s, farthest %.0f from the start (radius 120), covered %.0f blocks, lowest hp %.1f, task %s", blocksPerSecond(open.max()), far, open.path(), open.minHp, client(ctx, mc -> Tasks.current() == null ? "ended" : Tasks.current().status())));
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
		// walls in the way: a line of them across the field, the bot has to find the gaps or turn
		for (String w : new String[]{"fill -60 -60 330 -60 -30 470 stone", "fill 70 -60 330 70 -30 470 stone", "fill -150 -60 380 -20 -30 380 stone", "fill 20 -60 440 140 -30 440 stone", "fill -100 -60 330 100 -30 330 stone"}) cmd(sp, w);
		ctx.waitTicks(5);
		Run walls = wandering(ctx, sp, "wander-walls", true, 150, 2400);
		far = 0;
		for (double[] r : walls.t) far = Math.max(far, Math.hypot(r[0] - 0.5, r[1] - (FIELD_Z + 0.5)));
		alive = client(ctx, mc -> Bot.busy());
		report("wander-among-walls", walls.minHp >= 19.9 && far <= 150 + 35 && alive && walls.path() > 300 && walls.crashes() == 0,
			String.format(Locale.ROOT, "fastest %s, farthest %.0f, covered %.0f blocks, lowest hp %.1f, task %s", blocksPerSecond(walls.max()), far, walls.path(), walls.minHp, client(ctx, mc -> Tasks.current() == null ? "ended" : Tasks.current().status())));
		ctx.runOnClient(mc -> Commands.run("stop"));
		for (String w : new String[]{"fill -60 -60 330 -60 -30 470 air", "fill 70 -60 330 70 -30 470 air", "fill -150 -60 380 -20 -30 380 air", "fill 20 -60 440 140 -30 440 air", "fill -100 -60 330 100 -30 330 air"}) cmd(sp, w);
		ctx.waitTicks(5);
		// no elytra: it walks
		Run walk = wandering(ctx, sp, "wander-walk", false, 60, 900);
		far = 0;
		for (double[] r : walk.t) far = Math.max(far, Math.hypot(r[0] - 0.5, r[1] - (FIELD_Z + 0.5)));
		alive = client(ctx, mc -> Bot.busy());
		report("wander-walks-without-elytra", walk.max() < 0.8 && far <= 60 + 15 && walk.path() > 50 && alive, String.format(Locale.ROOT, "fastest %s, farthest %.0f (radius 60), covered %.0f blocks", blocksPerSecond(walk.max()), far, walk.path()));
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
	}

	private void chest(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// the control: with nothing on the chest the idle bot does put the chestplate on
		stage(ctx, sp, 0.5, 0.5, -90, false);
		cmd(sp, "give @a diamond_chestplate");
		ctx.waitTicks(450);
		String bare = client(ctx, mc -> mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getHoverName().getString());
		report("chest-control-chestplate-goes-on", bare.contains("Chestplate"), "chest slot: " + bare);
		stage(ctx, sp, 0.5, 0.5, -90, true);
		cmd(sp, "give @a diamond_chestplate");
		ctx.waitTicks(450);
		boolean elytra = client(ctx, mc -> mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA));
		report("chest-keeps-elytra", elytra, "chest slot: " + client(ctx, mc -> mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getHoverName().getString()));
	}
}
