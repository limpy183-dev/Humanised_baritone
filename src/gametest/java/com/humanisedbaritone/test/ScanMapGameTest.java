package com.humanisedbaritone.test;

import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.scan.Atlas;
import com.humanisedbaritone.scan.Finds;
import com.humanisedbaritone.scan.Radar;
import com.humanisedbaritone.settings.Settings;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * The map over a natural world (seeded, like the nature tests): a flight over the land paints it, the Map page shows it at several zoom
 * levels, and the scanner's cost is measured with a long view distance. HB_TEST=scanmap, HB_DISTANCE=n sets the view distance (default 16).
 */
public class ScanMapGameTest extends BotTest {
	@Override
	protected void shot(ClientGameTestContext ctx, String name) {
		ctx.takeScreenshot(TestScreenshotOptions.of(name).withSize(1600, 900));
	}

	private static void zoomTo(ClientGameTestContext ctx, int index) {
		try {
			Field z = Class.forName("com.humanisedbaritone.gui.MapPage").getDeclaredField("zoom");
			z.setAccessible(true);
			z.setInt(null, index);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		ctx.waitTicks(25);
	}

	private static void setTerrain(ClientGameTestContext ctx, boolean on) {
		try {
			Field t = Class.forName("com.humanisedbaritone.gui.MapPage").getDeclaredField("terrain");
			t.setAccessible(true);
			t.setBoolean(null, on);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		ctx.waitTicks(10);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(ui -> ui.setSeed("hbnature")).create()) {
			ctx.getInput().resizeWindow(1600, 900);
			ctx.waitTicks(40);
			int dist = Integer.parseInt(System.getenv().getOrDefault("HB_DISTANCE", "16"));
			ctx.runOnClient(mc -> {
				mc.options.renderDistance().set(dist);
				Settings.FEEDBACK.set(false);
			});
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful", "gamerule spawn_mobs false", "gamemode spectator @a");
			ctx.waitTicks(20);
			// what the first alert of a session costs: the sound and the chat message are loaded or built for the first time
			long chat = client(ctx, mc -> {
				long a = System.nanoTime();
				mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[HB] cold start"));
				return System.nanoTime() - a;
			});
			long sound = client(ctx, mc -> {
				long a = System.nanoTime();
				mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0f));
				return System.nanoTime() - a;
			});
			System.out.println("[HBTEST] cold costs: first chat message " + chat / 1000 + " us, first sound " + sound / 1000 + " us");
			// fly a square so that a good part of the land is seen, then park in the middle
			long t0 = System.nanoTime();
			int[][] route = {{0, 150, 0}, {160, 150, 0}, {160, 150, 160}, {0, 150, 160}, {-160, 150, 160}, {-160, 150, 0}, {0, 150, 0}};
			for (int[] p : route) {
				cmds(sp, "tp @a " + p[0] + " " + p[1] + " " + p[2]);
				ctx.waitTicks(60);
			}
			ctx.waitTicks(100);
			int tiles = client(ctx, mc -> Finds.live.atlas.size());
			ctx.runOnClient(mc -> com.humanisedbaritone.Log.clear());
			report("flight-painted-tiles", tiles >= 6, "tiles=" + tiles + " after the flight (" + (System.nanoTime() - t0) / 1_000_000 + " ms)");
			int painted = client(ctx, mc -> {
				int n = 0, total = 0;
				for (int[] t : Finds.live.atlas.known()) {
					Atlas.Tile tile = Finds.live.atlas.get(t[0], t[1], false);
					if (tile == null) continue;
					for (int x = 0; x < Atlas.SIZE; x += 8) {
						for (int z = 0; z < Atlas.SIZE; z += 8) {
							total++;
							if (tile.pixel(x, z) != 0) n++;
						}
					}
				}
				return total == 0 ? 0 : n * 100 / total;
			});
			ctx.runOnClient(mc -> com.humanisedbaritone.Log.clear());
			report("painted-share", painted >= 20, painted + "% of the sampled map pixels in the known tiles are painted");

			ctx.runOnClient(mc -> {
				mc.gui.setScreen(new BotScreen());
				((BotScreen) mc.gui.screen()).showTab("Map");
			});
			ctx.waitTicks(40);
			cmds(sp, "worldborder set 600");
			ctx.waitTicks(10);
			for (int z : new int[]{8, 6, 4, 0, 10}) {
				zoomTo(ctx, z);
				shot(ctx, "scanmap-zoom" + z);
			}
			setTerrain(ctx, false);
			zoomTo(ctx, 8);
			shot(ctx, "scanmap-dots");
			setTerrain(ctx, true);
			ctx.runOnClient(mc -> com.humanisedbaritone.Log.clear());
			report("map-open", client(ctx, mc -> mc.gui.screen() instanceof BotScreen), "");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			ctx.waitTicks(100);
			System.out.println("[HBTEST] radar (view distance " + dist + "): worst tick " + Radar.worstNanos / 1000 + " us, longest step \"" + Radar.worstStep + "\" " + Radar.worstStepNanos / 1000
				+ " us, average " + Radar.totalNanos / Math.max(1, Radar.ticks) / 1000 + " us over " + Radar.ticks + " ticks, " + Radar.slow4 + " over 4 ms, " + Radar.slow8 + " over 8 ms; fps " + client(ctx, mc -> mc.getFps()));
			finish();
		}
	}
}
