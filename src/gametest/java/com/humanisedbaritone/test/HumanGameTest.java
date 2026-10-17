package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.Interact;
import com.humanisedbaritone.human.LookController;
import com.humanisedbaritone.path.AStar;
import com.humanisedbaritone.path.Ctx;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Path;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Measures how human the bot actually looks and acts. */
public class HumanGameTest extends BotTest {
	private final List<double[]> frames = new ArrayList<>();
	private final List<double[]> aims = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			revive(ctx, sp);
			load(ctx, sp, -20, -20, 80, 60);
			cmds(sp, "give @a iron_pickaxe", "tp @a 0.5 -60 0.5");
			ctx.waitTicks(20);
			if (only("camera")) camera(ctx, sp);
			if (only("variety")) variety(ctx);
			if (only("aim")) aim(ctx, sp);
			finish();
		}
	}

	/** Walks a winding route and analyses every camera movement. */
	private void camera(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// two walls make the route turn several times
		cmds(sp, "fill 6 -60 -5 6 -58 12 stone", "fill 14 -60 -12 14 -58 5 stone", "fill 22 -60 -5 22 -58 12 stone");
		ctx.waitTicks(10);
		botOn(ctx);
		frames.clear();
		ctx.runOnClient(mc -> {
			LookController.probe = f -> frames.add(f);
			Commands.run("goto 30 -60 0");
		});
		until(ctx, "arrival", mc -> !Bot.busy(), 1500);
		ctx.runOnClient(mc -> LookController.probe = null);
		analyse();
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	private void analyse() {
		int n = frames.size();
		double maxSpeed = 0, maxAccel = 0, maxResidual = 0, prevSpeed = 0;
		List<Double> speeds = new ArrayList<>();
		int reversals = 0;
		double lastYawDir = 0;
		for (int i = 1; i < n; i++) {
			double[] a = frames.get(i - 1), b = frames.get(i);
			double dt = (b[0] - a[0]) / 1e9;
			if (dt <= 1e-4 || dt > 0.2) continue;
			double dy = wrap(b[1] - a[1]), dp = b[2] - a[2];
			double speed = Math.hypot(dy, dp) / dt;
			speeds.add(speed);
			maxSpeed = Math.max(maxSpeed, speed);
			maxAccel = Math.max(maxAccel, Math.abs(speed - prevSpeed) / dt);
			prevSpeed = speed;
			// every applied movement must be a whole number of mouse counts
			double q = b[5];
			double res = Math.max(Math.abs(b[3] - Math.rint(b[3])), Math.abs(b[4] - Math.rint(b[4])));
			maxResidual = Math.max(maxResidual, res);
			if (Math.abs(dy) > 0.3) {
				double dir = Math.signum(dy);
				if (lastYawDir != 0 && dir != lastYawDir) reversals++;
				lastYawDir = dir;
			}
		}
		speeds.sort(Double::compare);
		double p95 = speeds.isEmpty() ? 0 : speeds.get((int) (speeds.size() * 0.95));
		double mean = speeds.stream().mapToDouble(d -> d).average().orElse(0);
		System.out.printf("[HBTEST] camera: frames=%d meanSpeed=%.1f deg/s p95=%.1f max=%.1f deg/s maxAccel=%.0f deg/s^2 yawReversals=%d maxCountResidual=%.6f%n", n, mean, p95, maxSpeed, maxAccel, reversals, maxResidual);
		boolean ok = n > 50 && maxSpeed <= Settings.LOOK_MAX.d() * 1.3 && maxResidual < 1e-6;
		report("camera", ok, "");
	}

	private static double wrap(double d) {
		d %= 360;
		if (d >= 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}

	/** Plans the same trip repeatedly: the route should not be identical every time. */
	private void variety(ClientGameTestContext ctx) {
		Set<String> routes = new HashSet<>();
		for (int i = 0; i < 12; i++) {
			String sig = client(ctx, mc -> {
				Ctx c = new Ctx();
				Path p = new AStar(c, new Goal.At(14, -60, 14), 0, -60, 0).run(500, 2000, 100000, 1.0);
				StringBuilder sb = new StringBuilder();
				for (Path.Step s : p.steps) sb.append(s.kind().name().charAt(0)).append(s.dx()).append(',').append(s.dz()).append(';');
				return sb.toString();
			});
			routes.add(sig);
		}
		System.out.println("[HBTEST] variety: " + routes.size() + " different routes out of 12 identical plans");
		report("variety", routes.size() >= 4, "distinct=" + routes.size());
	}

	/** Mines several identical blocks and looks at where on the faces the hits land. */
	private void aim(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		cmds(sp, "fill 40 -60 10 44 -58 10 stone", "tp @a 42.5 -60 7.5");
		ctx.waitTicks(10);
		aims.clear();
		ctx.runOnClient(mc -> {
			Interact.aimProbe = a -> aims.add(a);
			Commands.run("sel pos1 40 -60 10");
			Commands.run("sel pos2 44 -58 10");
			Commands.run("sel clear");
		});
		until(ctx, "clearing", mc -> !Bot.busy(), 3000);
		ctx.runOnClient(mc -> Interact.aimProbe = null);
		double[] mean = new double[3], sd = new double[3];
		int n = aims.size();
		for (double[] a : aims) for (int i = 0; i < 3; i++) mean[i] += a[i] / n;
		for (double[] a : aims) for (int i = 0; i < 3; i++) sd[i] += Math.pow(a[i] - mean[i], 2) / n;
		Set<String> distinct = new HashSet<>();
		for (double[] a : aims) distinct.add(String.format("%.2f,%.2f,%.2f", a[0], a[1], a[2]));
		System.out.printf("[HBTEST] aim: %d choices, %d distinct points, spread sd(x,y,z)=%.2f,%.2f,%.2f%n", n, distinct.size(), Math.sqrt(sd[0]), Math.sqrt(sd[1]), Math.sqrt(sd[2]));
		report("aim", n >= 5 && distinct.size() >= n * 0.7, "");
	}
}
