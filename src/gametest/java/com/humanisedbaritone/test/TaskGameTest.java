package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Inv;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class TaskGameTest extends BotTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty easy", "gamerule spawn_mobs false");

			if (only("mine")) mining(ctx, sp);
			if (only("farm")) farming(ctx, sp);
			if (only("collect")) collecting(ctx, sp);
			if (only("hunt")) hunting(ctx, sp);
			if (only("eat")) eating(ctx, sp);
			if (only("lava")) lava(ctx, sp);
			if (only("follow")) following(ctx, sp);
			finish();
		}
	}

	private void mining(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int reps = Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "1"));
		for (int r = 0; r < reps; r++) {
			revive(ctx, sp);
			load(ctx, sp, 0, -20, 60, 40);
			cmds(sp, "kill @e[type=item]", "fill 20 -60 0 22 -58 2 stone", "setblock 21 -59 1 diamond_ore", "setblock 10 -60 5 diamond_ore", "setblock 12 -60 6 diamond_ore",
				"tp @a 0.5 -60 0.5", "give @a iron_pickaxe");
			ctx.waitTicks(20);
			botOn(ctx);
			ctx.runOnClient(mc -> Commands.run("mine diamond"));
			until(ctx, "three diamonds", mc -> Inv.count(s -> s.is(Items.DIAMOND)) >= 3, 2400);
			int dia = client(ctx, mc -> Inv.count(s -> s.is(Items.DIAMOND)));
			report("mine", dia == 3, "diamonds=" + dia);
			ctx.runOnClient(mc -> Commands.run("stop"));
		}
	}

	private void farming(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int reps = Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "1"));
		for (int r = 0; r < reps; r++) {
			revive(ctx, sp);
			load(ctx, sp, 30, -20, 60, 20);
			cmds(sp, "kill @e[type=item]", "fill 40 -61 0 44 -61 4 farmland", "setblock 42 -61 2 water", "fill 40 -60 0 44 -60 4 wheat[age=7]", "setblock 42 -60 2 air", "tp @a 38.5 -60 2.5",
				"give @a wheat_seeds 8");
			ctx.waitTicks(20);
			botOn(ctx);
			ctx.runOnClient(mc -> Commands.run("farm 8"));
			until(ctx, "farm task", mc -> !Bot.busy(), 3000);
			int wheat = client(ctx, mc -> Inv.count(s -> s.is(Items.WHEAT)));
			long planted = client(ctx, mc -> {
				long n = 0;
				for (int x = 40; x <= 44; x++) {
					for (int z = 0; z <= 4; z++) {
						if (mc.level.getBlockState(new BlockPos(x, -60, z)).is(Blocks.WHEAT)) n++;
					}
				}
				return n;
			});
			// the field row by row (x 40..44): W wheat, . bare farmland, ~ water, # anything else (e.g. trampled to dirt)
			String grid = client(ctx, mc -> {
				StringBuilder sb = new StringBuilder();
				for (int z = 0; z <= 4; z++) {
					for (int x = 40; x <= 44; x++) {
						var below = mc.level.getBlockState(new BlockPos(x, -61, z));
						sb.append(mc.level.getBlockState(new BlockPos(x, -60, z)).is(Blocks.WHEAT) ? 'W' : below.is(Blocks.FARMLAND) ? '.' : below.is(Blocks.WATER) ? '~' : '#');
					}
					sb.append(z < 4 ? "/" : "");
				}
				return sb.toString();
			});
			report("farm", wheat >= 10 && planted >= 20, "wheat=" + wheat + " replanted=" + planted + " field=" + grid);
			ctx.runOnClient(mc -> Commands.run("stop"));
		}
	}

	private void collecting(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, 80, -20, 130, 20);
		cmds(sp, "tp @a 90.5 -60 0.5", "summon item 96 -58 3 {Item:{id:\"minecraft:diamond\",count:3}}", "summon item 88 -58 -4 {Item:{id:\"minecraft:diamond\",count:2}}");
		ctx.waitTicks(40);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("collect 16"));
		until(ctx, "collect task", mc -> !Bot.busy(), 1200);
		int dia = client(ctx, mc -> Inv.count(s -> s.is(Items.DIAMOND)));
		report("collect", dia == 5, "diamonds=" + dia);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	private void hunting(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int reps = Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "1")); // more than one run gives a death rate
		boolean ab = System.getenv("HB_AB") != null; // alternate sprint-hit fighting (odd runs) with the plain style (even runs)
		for (int r = 0; r < reps; r++) {
			revive(ctx, sp);
			load(ctx, sp, 130, -40, 190, 40);
			boolean sprint = ab && r % 2 == 1;
			if (ab) ctx.runOnClient(mc -> Settings.WTAP.set(sprint));
			cmds(sp, "time set midnight", "tp @a 150.5 -60 0.5", "give @a iron_sword", "summon zombie 158 -60 4 {Tags:[\"hb\"]}", "summon zombie 160 -60 -3 {Tags:[\"hb\"]}"); // extra NBT: no random weapons
			ctx.waitTicks(20);
			botOn(ctx);
			ctx.runOnClient(mc -> Commands.run("hunt"));
			ctx.waitTicks(60);
			System.out.println("[HBTEST]    state after 60 ticks: " + client(ctx, mc -> "enabled=" + Bot.enabled + " screen=" + mc.gui.screen() + " alive=" + mc.player.isAlive() + " hp=" + mc.player.getHealth()
				+ " status=" + Bot.status() + " / " + Bot.detail() + " pos=" + mc.player.blockPosition().toShortString()));
			until(ctx, "zombies dead", mc -> mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(40)).isEmpty() || !mc.player.isAlive(), 1500);
			float hp = client(ctx, mc -> mc.player.getHealth());
			long left = client(ctx, mc -> (long) mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(60)).size());
			report(sprint ? "hunt-sprint" : "hunt", left == 0 && hp > 0, "zombies left=" + left + " hp=" + hp);
			if (r == 0) shot(ctx, "task-hunt");
			ctx.runOnClient(mc -> Commands.run("stop"));
			cmds(sp, "kill @e[type=zombie]", "time set day");
			ctx.waitTicks(10);
		}
	}

	private void eating(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmds(sp, "tp @a 0.5 -60 60.5", "give @a bread 6");
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			Settings.EAT_FOOD.set(19.0);
			Commands.run("enable");
		});
		cmd(sp, "effect give @a minecraft:hunger 20 120");
		int before = client(ctx, mc -> mc.player.getFoodData().getFoodLevel());
		until(ctx, "bread eaten", mc -> Inv.count(s -> s.is(Items.BREAD)) < 6, 600);
		int breadLeft = client(ctx, mc -> Inv.count(s -> s.is(Items.BREAD)));
		report("eat", breadLeft < 6, "bread left=" + breadLeft + " foodBefore=" + before);
		ctx.runOnClient(mc -> Settings.EAT_FOOD.reset());
		cmd(sp, "effect clear @a");
	}

	private void lava(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, 190, -20, 230, 20);
		cmds(sp, "fill 200 -62 0 204 -60 4 lava", "tp @a 202.5 -60 2.5");
		ctx.waitTicks(3);
		botOn(ctx);
		boolean out = until(ctx, "out of lava", mc -> !mc.player.isInLava(), 160);
		boolean alive = client(ctx, mc -> mc.player.isAlive());
		float hp = client(ctx, mc -> mc.player.getHealth());
		report("lava", out && alive, "hp=" + hp);
		shot(ctx, "task-lava");
		cmd(sp, "effect give @a minecraft:instant_health 1 10");
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	private void following(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, 230, -40, 330, 40);
		cmds(sp, "tp @a 250.5 -60 0.5", "summon cow 262 -60 2 {NoAI:1b}");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("follow cow"));
		boolean near = until(ctx, "near the cow", mc -> mc.player.distanceToSqr(262, -60, 2) < 25, 800);
		cmd(sp, "tp @e[type=cow] 290 -60 -10");
		boolean near2 = until(ctx, "near the moved cow", mc -> mc.player.distanceToSqr(290, -60, -10) < 36, 1200);
		report("follow", near && near2, "");
		ctx.runOnClient(mc -> Commands.run("stop"));
	}
}
