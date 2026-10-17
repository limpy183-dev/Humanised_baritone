package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** The bot in a normally generated world: trees, hills, water, caves. */
public class NatureGameTest extends BotTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("hbnature")).create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(120);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful", "gamerule spawn_mobs false");
			botOn(ctx);
			double[] start = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
			System.out.printf("[HBTEST] nature: start at %.1f %.1f %.1f%n", start[0], start[1], start[2]);
			int[][] legs = {{120, 0}, {0, 120}, {-120, 0}, {0, -120}};
			int n = 0;
			for (int[] leg : legs) {
				double[] from = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()});
				int gx = (int) Math.floor(from[0]) + leg[0], gz = (int) Math.floor(from[1]) + leg[1];
				long t0 = System.currentTimeMillis();
				ctx.runOnClient(mc -> {
					com.humanisedbaritone.Log.clear();
					Commands.run("goto " + gx + " " + gz);
				});
				until(ctx, "leg " + n, mc -> !Bot.busy(), 4000);
				double[] pos = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
				boolean alive = client(ctx, mc -> mc.player.isAlive());
				double d = Math.hypot(pos[0] - gx - 0.5, pos[2] - gz - 0.5);
				report("nature-leg" + n, alive && d < 3.0, String.format("goal %d %d -> at %.1f %.1f %.1f dist=%.1f  %.0fs real time", gx, gz, pos[0], pos[1], pos[2], d, (System.currentTimeMillis() - t0) / 1000.0));
				shot(ctx, "nature-leg" + n);
				ctx.runOnClient(mc -> Commands.run("stop"));
				if (!alive) {
					revive(ctx, sp);
					botOn(ctx);
				}
				n++;
			}
			if (only("logs")) mine(ctx, "logs", "mine 4 logs", 4, s -> s.is(net.minecraft.tags.ItemTags.LOGS));
			if (only("coal")) {
				cmd(sp, "give @a iron_pickaxe");
				mine(ctx, "coal", "mine 3 coal", 3, s -> s.is(net.minecraft.world.item.Items.COAL));
			}
			finish();
		}
	}

	/** Runs a mine command in the natural world and counts what ended up in the inventory. */
	private void mine(ClientGameTestContext ctx, String name, String command, int want, java.util.function.Predicate<net.minecraft.world.item.ItemStack> item) {
		long t0 = System.currentTimeMillis();
		ctx.runOnClient(mc -> {
			com.humanisedbaritone.Log.clear();
			Commands.run(command);
		});
		until(ctx, name, mc -> !Bot.busy(), 9000);
		int got = client(ctx, mc -> {
			int n = 0;
			for (int i = 0; i < 36; i++) {
				var st = com.humanisedbaritone.world.Inv.get(i);
				if (item.test(st)) n += st.getCount();
			}
			return n;
		});
		report("nature-" + name, got >= want, String.format("got %d of %d  %.0fs real time", got, want, (System.currentTimeMillis() - t0) / 1000.0));
		shot(ctx, "nature-" + name);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}
}
