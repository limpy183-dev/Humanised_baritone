package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The elytra jump in a normally generated world (hills, trees, water, ravines), where flat ground comes in short stretches: the
 * wander task and a far goto with an elytra worn must stay alive and unhurt, keep to the radius, and still get somewhere. No
 * damage protection: a wall hit, a fall or a drowning shows. {@code HB_REPEAT=n} repeats the wander.
 */
public class ElytraNatureGameTest extends BotTest {
	private List<int[]> starts = List.of();

	private int[] start(int round) {
		return starts.isEmpty() ? new int[]{25, -50 + 15 * (round - 1)} : starts.get(round - 1);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("hbnature")).create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(120);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful", "gamerule spawn_mobs false");
			for (int[] q : new int[][]{{-180, -180}, {1, -180}, {-180, 1}, {1, 1}}) {
				cmd(sp, "forceload add " + q[0] + " " + q[1] + " " + (q[0] + 179) + " " + (q[1] + 179));
				ctx.waitTicks(10);
			}
			ctx.waitTicks(60);
			botOn(ctx);
			ctx.runOnClient(mc -> {
				Settings.VAR_FIDGET.set(0.0);
				Settings.VAR_SEED.set(4242.0);
				com.humanisedbaritone.human.Rng.reseed();
			});
			int rounds = Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "1"));
			// HB_AT=x:z,x:z starts the rounds somewhere else (the default spot is the pinned start of the feelnature suite)
			String at = System.getenv("HB_AT");
			if (at != null && !at.isBlank()) {
				starts = java.util.Arrays.stream(at.split(",")).map(s -> s.split(":")).map(a -> new int[]{Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim())}).toList();
				rounds = starts.size();
			}
			for (int r = 1; r <= rounds; r++) {
				if (only("wander")) wander(ctx, sp, r);
				if (only("goto")) travel(ctx, sp, r);
			}
			finish();
		}
	}

	/** Drops the player from the sky at a fixed spot (resistance for the fall only) and puts the elytra on. */
	private void stage(ClientGameTestContext ctx, TestSingleplayerContext sp, int x, int z) {
		revive(ctx, sp);
		cmds(sp, "gamemode survival @a", "effect give @a minecraft:saturation 1000 255 true", "effect give @a minecraft:resistance 600 4 true", "item replace entity @a armor.chest with minecraft:elytra[minecraft:unbreakable={}]", "tp @a " + x + ".5 250 " + z + ".5");
		until(ctx, "teleport", mc -> mc.player.getY() > 200, 100);
		until(ctx, "landing", mc -> mc.player.getY() < 200 && mc.player.onGround(), 800);
		where(ctx, "landed");
		ctx.waitTicks(40); // the fall damage reaches the player a few ticks after the landing: the resistance has to outlast it
		where(ctx, "40 ticks later");
		cmd(sp, "effect clear @a minecraft:resistance");
		ctx.waitTicks(10);
		where(ctx, "ready");
		ctx.runOnClient(mc -> {
			Settings.ELYTRA.set(true);
			Settings.WANDER_RADIUS.set(150.0);
		});
	}

	private void where(ClientGameTestContext ctx, String what) {
		String s = client(ctx, mc -> String.format(Locale.ROOT, "%.1f %.1f %.1f hp %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getHealth()));
		System.out.println("[HBTEST]   stage " + what + ": " + s);
	}

	/** Notes the lowest health, and says where and how when it drops. */
	private static void hurt(net.minecraft.client.player.LocalPlayer p, double[] minHp, int tick) {
		if (p.getHealth() < minHp[0]) {
			minHp[0] = p.getHealth();
			System.out.println(String.format(Locale.ROOT, "[HBTEST]   hp %.1f at %.1f %.1f %.1f (tick %d), speed %.2f, ground %b, gliding %b, %s", p.getHealth(), p.getX(), p.getY(), p.getZ(), tick, p.getDeltaMovement().horizontalDistance(), p.onGround(), p.isFallFlying(), com.humanisedbaritone.task.Tasks.current() == null ? "no task" : com.humanisedbaritone.task.Tasks.current().status()));
		}
	}

	private void wander(ClientGameTestContext ctx, TestSingleplayerContext sp, int round) {
		stage(ctx, sp, start(round)[0], start(round)[1]);
		double[] home = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()});
		List<double[]> trace = new ArrayList<>();
		double[] last = home.clone();
		double[] minHp = {20};
		ctx.runOnClient(mc -> Commands.run("wander 150"));
		int[] n = {0};
		boolean ended = until(ctx, "wander", mc -> {
			var p = mc.player;
			trace.add(new double[]{p.getX(), p.getZ(), Math.hypot(p.getX() - last[0], p.getZ() - last[1]), p.horizontalCollision ? 1 : 0});
			last[0] = p.getX();
			last[1] = p.getZ();
			hurt(p, minHp, n[0]);
			return !p.isAlive() || !Bot.busy() || ++n[0] >= 2400;
		}, 2500);
		double far = 0, path = 0, fastest = 0;
		int crashes = 0;
		for (int i = 0; i < trace.size(); i++) {
			double[] t = trace.get(i);
			far = Math.max(far, Math.hypot(t[0] - home[0], t[1] - home[1]));
			path += t[2];
			fastest = Math.max(fastest, t[2]);
			if (i > 0 && (t[3] > 0 && trace.get(i - 1)[2] > 0.7 || trace.get(i - 1)[2] > 0.8 && t[2] < 0.4)) crashes++; // into something at speed: the integrated server does not turn that into damage, so the collision and the speed show it
		}
		boolean alive = client(ctx, mc -> mc.player.isAlive());
		boolean busy = client(ctx, mc -> Bot.busy());
		report("nature-wander" + round, alive && minHp[0] >= 12 && crashes == 0 && far <= 150 + 40 && path > 60 && busy, String.format(Locale.ROOT, "alive=%b, lowest hp %.1f, crashes %d, farthest %.0f of 150, covered %.0f blocks, fastest %.1f b/s, task %s%s", alive, minHp[0], crashes, far, path, fastest * 20,
			client(ctx, mc -> com.humanisedbaritone.task.Tasks.current() == null ? "ended" : com.humanisedbaritone.task.Tasks.current().status()), ended ? "" : " (timed out)"));
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
	}

	private void travel(ClientGameTestContext ctx, TestSingleplayerContext sp, int round) {
		stage(ctx, sp, start(round)[0], start(round)[1]);
		double[] from = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()});
		int gx = (int) Math.floor(from[0]) + 150, gz = (int) Math.floor(from[1]) + 90;
		double[] minHp = {20};
		double[] fastest = {0};
		double[][] prev = {from.clone()};
		int[] crashes = {0};
		double[] before = {0};
		ctx.runOnClient(mc -> Commands.run("goto " + gx + " " + gz));
		boolean done = until(ctx, "goto", mc -> {
			var p = mc.player;
			double now = Math.hypot(p.getX() - prev[0][0], p.getZ() - prev[0][1]);
			fastest[0] = Math.max(fastest[0], now);
			if (p.horizontalCollision && before[0] > 0.7 || before[0] > 0.8 && now < 0.4) crashes[0]++;
			before[0] = now;
			prev[0] = new double[]{p.getX(), p.getZ()};
			hurt(p, minHp, 0);
			return !p.isAlive() || !Bot.busy();
		}, 3600);
		double[] pos = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()});
		double d = Math.hypot(pos[0] - gx - 0.5, pos[1] - gz - 0.5);
		boolean alive = client(ctx, mc -> mc.player.isAlive());
		report("nature-goto" + round, alive && done && d < 3 && minHp[0] >= 12 && crashes[0] == 0, String.format(Locale.ROOT, "alive=%b, %.1f from the goal, lowest hp %.1f, crashes %d, fastest %.1f b/s%s", alive, d, minHp[0], crashes[0], fastest[0] * 20, done ? "" : " (timed out)"));
		ctx.runOnClient(mc -> Commands.run("stop"));
		ctx.waitTicks(5);
	}
}
