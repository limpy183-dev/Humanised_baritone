package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.behave.Combat;
import com.humanisedbaritone.behave.Eating;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;

public class CombatGameTest extends BotTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set midnight", "difficulty easy", "gamerule spawn_mobs false");
			if (only("bow")) bow(ctx, sp);
			if (only("creeper")) creeper(ctx, sp);
			if (only("guard")) guard(ctx, sp);
			if (only("emergency")) emergency(ctx, sp);
			if (only("moat")) moat(ctx, sp);
			if (only("flee")) flee(ctx, sp);
			if (only("enderman")) enderman(ctx, sp);
			finish();
		}
	}

	private void bow(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, -40, -40, 40, 40);
		cmds(sp, "tp @a 0.5 -60 0.5", "give @a bow", "give @a arrow 32", "summon skeleton 12 -60 0 {NoAI:1b,Tags:[\"hbtarget\"]}");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("hunt"));
		boolean dead = until(ctx, "skeleton dead", mc -> mc.level.getEntitiesOfClass(Skeleton.class, mc.player.getBoundingBox().inflate(60)).isEmpty(), 1500);
		float hp = client(ctx, mc -> mc.player.getHealth());
		int arrowsLeft = client(ctx, mc -> com.humanisedbaritone.world.Inv.count(s -> s.is(net.minecraft.world.item.Items.ARROW)));
		report("bow", dead, "hp=" + hp + " arrows left=" + arrowsLeft);
		shot(ctx, "combat-bow");
		ctx.runOnClient(mc -> Commands.run("stop"));
		cmd(sp, "kill @e[type=skeleton]");
	}

	private void creeper(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmds(sp, "tp @a 0.5 -60 30.5", "give @a iron_sword", "summon creeper 8 -60 30");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("hunt"));
		until(ctx, "creeper gone", mc -> mc.level.getEntitiesOfClass(Creeper.class, mc.player.getBoundingBox().inflate(60)).isEmpty(), 1200);
		float hp = client(ctx, mc -> mc.player.getHealth());
		boolean alive = client(ctx, mc -> mc.player.isAlive());
		report("creeper", alive && hp > 6, "hp=" + hp);
		ctx.runOnClient(mc -> Commands.run("stop"));
		cmd(sp, "kill @e[type=creeper]");
	}

	private void guard(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmds(sp, "tp @a 0.5 -60 60.5", "give @a iron_sword");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("guard"));
		ctx.waitTicks(60);
		cmds(sp, "summon zombie 6 -60 60", "summon zombie -5 -60 63");
		// the client only learns of them a tick or two after the summon: without this the next line sees an empty list and the test ends before any fight
		until(ctx, "zombies spawned", mc -> zombies(mc).size() >= 2, 100);
		until(ctx, "zombies dead", mc -> zombies(mc).isEmpty(), 1200);
		float hp = client(ctx, mc -> mc.player.getHealth());
		boolean back = until(ctx, "back home", mc -> mc.player.distanceToSqr(0.5, -60, 60.5) < 9, 400);
		report("guard", hp > 0 && Bot.busy(), "hp=" + hp + " backHome=" + back);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	private void emergency(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int reps = Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "1")); // more than one round gives a death rate
		for (int r = 0; r < reps; r++) emergencyRound(ctx, sp);
	}

	/** Badly hurt in a fight with a golden apple in the bag: the bot should eat it and still win. */
	private void emergencyRound(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmds(sp, "kill @e[type=item]", "tp @a 0.5 -60 90.5", "give @a iron_sword", "give @a golden_apple 2"); // the items a previous round dropped must not be picked up again
		// badly hurt and too hungry to heal on its own, so that the golden apple is the only way back; read it back, because anything still pending would undo it
		float[] start = {20, 20};
		for (int tries = 0; tries < 3 && !(start[0] <= 5 && start[1] <= 10); tries++) {
			sp.getServer().runOnServer(srv -> {
				var pl = srv.getPlayerList().getPlayers().get(0);
				pl.getFoodData().setFoodLevel(10);
				pl.getFoodData().setSaturation(0f);
				pl.setHealth(3f);
			});
			ctx.waitTicks(3);
			start = client(ctx, mc -> new float[]{mc.player.getHealth(), mc.player.getFoodData().getFoodLevel()});
		}
		cmd(sp, "summon zombie 8 -60 90 {Tags:[\"hb\"]}");
		until(ctx, "zombie spawned", mc -> !zombies(mc).isEmpty(), 100);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("hunt"));
		trace(ctx);
		until(ctx, "zombie dead", mc -> zombies(mc).isEmpty() || !mc.player.isAlive(), 1500);
		float hp = client(ctx, mc -> mc.player.getHealth());
		boolean alive = client(ctx, mc -> mc.player.isAlive());
		long zombies = client(ctx, mc -> (long) zombies(mc).size());
		int apples = client(ctx, mc -> com.humanisedbaritone.world.Inv.count(s -> s.is(net.minecraft.world.item.Items.GOLDEN_APPLE)));
		report("emergency", alive && zombies == 0 && apples < 2, "hp=" + hp + " zombies=" + zombies + " apples left=" + apples + " (started at hp0=" + start[0] + " food0=" + start[1] + ")");
		ctx.runOnClient(mc -> Commands.run("stop"));
		cmd(sp, "kill @e[type=zombie]");
	}

	/** Zombies within 60 blocks that are alive: a corpse stays in the client's list for a moment after it died or was killed. */
	private static java.util.List<Zombie> zombies(net.minecraft.client.Minecraft mc) {
		return mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(60), Zombie::isAlive);
	}

	/** With HB_TRACE set: a line every 5 ticks until the fight is over, so that a lost fight can be replayed from the log. */
	private void trace(ClientGameTestContext ctx) {
		if (System.getenv("HB_TRACE") == null) return;
		for (int t = 0; t < 300; t++) {
			String line = client(ctx, mc -> {
				var z = zombies(mc);
				if (z.isEmpty() || !mc.player.isAlive()) return "";
				return String.format("tick %d: hp %.1f abs %.1f food %d, zombie %.1f away (hp %.0f); eating=%b fighting=%b using=%b", mc.player.tickCount, mc.player.getHealth(), mc.player.getAbsorptionAmount(),
					mc.player.getFoodData().getFoodLevel(), Math.sqrt(z.get(0).distanceToSqr(mc.player)), z.get(0).getHealth(), Eating.active(), Combat.fighting(), mc.player.isUsingItem());
			});
			if (line.isEmpty()) return;
			System.out.println("[HBTEST]    " + line);
			ctx.waitTicks(5);
		}
	}

	/** A mob across a lava moat must not lure the bot into the lava. */
	private void moat(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, -20, 100, 20, 140);
		cmds(sp, "fill -5 -61 115 5 -61 125 lava", "fill -1 -61 119 1 -61 121 grass_block", "tp @a 0.5 -60 120.5", "give @a iron_sword", "summon zombie 8 -60 120 {NoAI:1b,Tags:[\"hb\"]}");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("hunt"));
		int inLava = 0;
		for (int i = 0; i < 80; i++) { // 400 ticks
			ctx.waitTicks(5);
			if (client(ctx, mc -> mc.player.isInLava() || !mc.player.isAlive())) inLava++;
		}
		double[] pos = client(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		report("moat", inLava == 0, String.format("ticks in lava or dead=%d at %.1f %.1f %.1f", inLava * 5, pos[0], pos[1], pos[2]));
		ctx.runOnClient(mc -> Commands.run("stop"));
		cmd(sp, "kill @e[type=zombie]");
	}

	/**
	 * Fleeing three times in a row from a mob that stays 12 blocks away. The 10-tick grace before a flee is given up must be there every time:
	 * once Combat.fleeTicks was left above 10 by the first flee, the later ones ended before the bot had moved at all.
	 */
	private void flee(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		load(ctx, sp, -40, 180, 40, 220);
		for (int round = 1; round <= 3; round++) {
			revive(ctx, sp);
			cmds(sp, "tp @a 0.5 -60 200.5", "summon zombie 12.5 -60 200.5 {NoAI:1b,Tags:[\"hb\"]}");
			float[] start = {20, 20};
			for (int tries = 0; tries < 3 && !(start[0] <= 5 && start[1] <= 10); tries++) { // hurt and without natural regeneration; read back, because pending effects would undo it
				sp.getServer().runOnServer(srv -> {
					var pl = srv.getPlayerList().getPlayers().get(0);
					pl.getFoodData().setFoodLevel(10);
					pl.getFoodData().setSaturation(0f);
					pl.setHealth(4f);
				});
				ctx.waitTicks(3);
				start = client(ctx, mc -> new float[]{mc.player.getHealth(), mc.player.getFoodData().getFoodLevel()});
			}
			until(ctx, "zombie spawned", mc -> !zombies(mc).isEmpty(), 100);
			botOn(ctx);
			ctx.runOnClient(mc -> { // no task, so that nothing but combat moves the bot
				com.humanisedbaritone.settings.Settings.HUNT.set(true);
				com.humanisedbaritone.settings.Settings.HUNT_RANGE.set(30.0);
			});
			double d0 = client(ctx, mc -> Math.sqrt(zombies(mc).get(0).distanceToSqr(mc.player)));
			ctx.waitTicks(60);
			double d1 = client(ctx, mc -> Math.sqrt(zombies(mc).get(0).distanceToSqr(mc.player)));
			report("flee" + round, d1 - d0 >= 1.0 && start[0] <= 5, String.format("moved away %.1f blocks (%.1f -> %.1f), started at hp0=%.1f food0=%.1f", d1 - d0, d0, d1, start[0], start[1]));
			ctx.runOnClient(mc -> Commands.run("stop"));
			cmd(sp, "kill @e[type=zombie]");
		}
	}

	/** Safety.endermanNear caches for 10 ticks by game time; a world with a lower game time than the last one (joining another world) must not freeze it. */
	private void enderman(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, -40, 240, 40, 280);
		cmds(sp, "tp @a 0.5 -60 260.5", "summon enderman 8.5 -60 260.5 {NoAI:1b,Silent:1b,Tags:[\"hb\"]}");
		boolean seen = until(ctx, "enderman seen", mc -> !endermen().isEmpty(), 100);
		ctx.runOnClient(mc -> { // the state a previous world leaves behind: a game time far above this one's, and no enderman there
			try {
				var at = com.humanisedbaritone.behave.Safety.class.getDeclaredField("endermanCheckedAt");
				var cached = com.humanisedbaritone.behave.Safety.class.getDeclaredField("endermanCached");
				at.setAccessible(true);
				cached.setAccessible(true);
				at.setLong(null, 1_000_000_000L);
				cached.setBoolean(null, false);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		int appeared = ticksUntilEndermanNear(ctx, true);
		cmd(sp, "kill @e[type=enderman]");
		boolean gone = until(ctx, "enderman gone", mc -> endermen().isEmpty(), 100);
		int vanished = ticksUntilEndermanNear(ctx, false);
		report("enderman", seen && gone && appeared >= 0 && appeared <= 12 && vanished >= 0 && vanished <= 12, "noticed after " + appeared + " ticks, forgotten after " + vanished + " ticks (-1 = never, limit 12)");
	}

	private static java.util.List<net.minecraft.world.entity.LivingEntity> endermen() {
		return com.humanisedbaritone.world.Ent.living(40, e -> com.humanisedbaritone.world.Ent.key(e).equals("minecraft:enderman"));
	}

	/** Calls endermanNear once per tick until it returns {@code want}: the number of ticks that took, or -1 after 40. */
	private int ticksUntilEndermanNear(ClientGameTestContext ctx, boolean want) {
		for (int t = 0; t < 40; t++) {
			if (client(ctx, mc -> com.humanisedbaritone.behave.Safety.endermanNear()) == want) return t;
			ctx.waitTicks(1);
		}
		return -1;
	}
}
