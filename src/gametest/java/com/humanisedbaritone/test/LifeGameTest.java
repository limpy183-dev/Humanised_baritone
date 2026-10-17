package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Inv;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/** Everyday life: sleeping, chests, throwing junk away, putting on armor, exploring and standing around. */
public class LifeGameTest extends BotTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			if (only("sleep")) sleeping(ctx, sp);
			if (only("chest")) chest(ctx, sp);
			if (only("junk")) junk(ctx, sp);
			if (only("gear")) gear(ctx, sp);
			if (only("explore")) explore(ctx, sp);
			if (only("idle")) idle(ctx, sp);
			if (only("respawn")) respawning(ctx, sp);
			if (only("resume")) resuming(ctx, sp);
			finish();
		}
	}

	private void sleeping(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, -10, -20, 40, 20);
		cmds(sp, "time set midnight", "setblock 12 -60 0 red_bed[facing=east,part=foot]", "setblock 13 -60 0 red_bed[facing=east,part=head]", "tp @a 4.5 -60 0.5");
		ctx.waitTicks(30);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("sleep"));
		boolean slept = until(ctx, "in bed", mc -> mc.player.isSleeping(), 900);
		if (!slept) {
			String why = sp.getServer().computeOnServer(srv -> srv.getPlayerList().getPlayers().get(0).startSleepInBed(new net.minecraft.core.BlockPos(13, -60, 0)).left().map(pr -> pr.message().getString()).orElse("the server would put the player to bed"));
			System.out.println("[HBTEST]    asked the server directly: " + why + "  (player at " + client(ctx, mc -> mc.player.position()) + ")");
		}
		report("sleep", slept, "");
		shot(ctx, "life-sleep");
		ctx.runOnClient(mc -> Commands.run("stop"));
		cmd(sp, "time set day"); // wakes the player
		until(ctx, "awake", mc -> !mc.player.isSleeping(), 200);
	}

	private void chest(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, 40, -20, 100, 20);
		cmds(sp, "setblock 60 -60 0 chest[facing=west]", "tp @a 54.5 -60 0.5", "give @a iron_ingot 5", "give @a bone 12", "give @a gunpowder 7", "give @a iron_pickaxe");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("store"));
		until(ctx, "store task", mc -> !Bot.busy(), 1500);
		int left = client(ctx, mc -> Inv.count(s -> s.is(Items.IRON_INGOT) || s.is(Items.BONE) || s.is(Items.GUNPOWDER)));
		int pick = client(ctx, mc -> Inv.count(s -> s.is(Items.IRON_PICKAXE)));
		report("store", left == 0 && pick == 1, "items left=" + left + " pickaxes=" + pick);
		ctx.runOnClient(mc -> Commands.run("fetch iron_ingot"));
		until(ctx, "fetch task", mc -> !Bot.busy(), 1500);
		int ingots = client(ctx, mc -> Inv.count(s -> s.is(Items.IRON_INGOT)));
		int bones = client(ctx, mc -> Inv.count(s -> s.is(Items.BONE)));
		report("fetch", ingots == 5 && bones == 0, "ingots=" + ingots + " bones=" + bones);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	private void junk(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmd(sp, "tp @a 0.5 -60 40.5");
		for (int i = 0; i < 34; i++) cmd(sp, "give @a gravel 64"); // nearly full: one free slot is left after the diamonds
		cmd(sp, "give @a diamond 3");
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> Settings.JUNK.set(true));
		botOn(ctx);
		boolean thrown = until(ctx, "junk thrown", mc -> Inv.count(s -> s.is(Items.GRAVEL)) < 34 * 64, 1200);
		int dia = client(ctx, mc -> Inv.count(s -> s.is(Items.DIAMOND)));
		report("junk", thrown && dia == 3, "gravel=" + client(ctx, mc -> Inv.count(s -> s.is(Items.GRAVEL))) + " diamonds=" + dia);
		ctx.runOnClient(mc -> {
			Settings.JUNK.reset();
			Commands.run("stop");
		});
	}

	private void gear(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmds(sp, "tp @a 0.5 -60 -40.5", "give @a iron_chestplate", "give @a iron_helmet", "give @a totem_of_undying");
		ctx.waitTicks(10);
		botOn(ctx);
		boolean worn = until(ctx, "gear on", mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
			&& mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING), 1500);
		report("gear", worn, "chest=" + client(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem()) + " off=" + client(ctx, mc -> mc.player.getOffhandItem().getItem()));
	}

	private void explore(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmd(sp, "tp @a 0.5 -60 0.5");
		ctx.waitTicks(20);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("explore"));
		boolean far = until(ctx, "far from the start", mc -> mc.player.distanceToSqr(0.5, -60, 0.5) > 40 * 40, 2400);
		boolean alive = client(ctx, mc -> mc.player.isAlive());
		report("explore", far && alive, "");
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/** Standing around: the camera and the body must not be frozen, as if nobody were there. */
	private void idle(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmd(sp, "tp @a 0.5 -60 100.5");
		ctx.waitTicks(20);
		ctx.runOnClient(mc -> Settings.VAR_FIDGET.set(1.0));
		botOn(ctx);
		double turned = 0;
		float last = client(ctx, mc -> mc.player.getYRot());
		for (int i = 0; i < 300; i++) {
			ctx.waitTicks(10);
			float yaw = client(ctx, mc -> mc.player.getYRot());
			turned += Math.abs(Mth.wrapDegrees(yaw - last));
			last = yaw;
		}
		report("idle", turned > 20, String.format("total yaw movement %.0f degrees in 3000 ticks", turned));
		ctx.runOnClient(mc -> Settings.VAR_FIDGET.reset());
	}

	/** With the setting on, the bot clicks "Respawn" by itself after a pause. */
	private void respawning(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		cmd(sp, "tp @a 0.5 -60 120.5");
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> Settings.AUTO_RESPAWN.set(true));
		botOn(ctx);
		cmd(sp, "kill @a");
		boolean died = until(ctx, "death", mc -> !mc.player.isAlive(), 200); // first the death has to reach the client
		boolean back = until(ctx, "respawned", mc -> mc.player.isAlive() && mc.gui.screen() == null, 800);
		report("respawn", died && back, "died=" + died);
		ctx.runOnClient(mc -> Settings.AUTO_RESPAWN.reset());
		// the bot has to keep working after the new life has started
		cmds(sp, "tp @a 0.5 -60 120.5", "give @a iron_sword", "summon zombie 6 -60 120 {Tags:[\"hb\"]}");
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> Commands.run("hunt"));
		ctx.waitTicks(60);
		String state = client(ctx, mc -> "enabled=" + Bot.enabled + " screen=" + mc.gui.screen() + " alive=" + mc.player.isAlive() + " hp=" + mc.player.getHealth() + " status=" + Bot.status() + " / " + Bot.detail()
			+ " pos=" + mc.player.blockPosition().toShortString());
		boolean killed = until(ctx, "zombie dead", mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, mc.player.getBoundingBox().inflate(40)).isEmpty(), 1200);
		report("after-respawn", killed, state);
		ctx.runOnClient(mc -> Commands.run("stop"));
	}

	/** Dying in the middle of a job: after the respawn the bot picks the job up again. */
	private void resuming(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		revive(ctx, sp);
		load(ctx, sp, -10, -10, 60, 30);
		cmds(sp, "kill @e[type=item]", "setblock 8 -60 4 diamond_ore", "setblock 30 -60 6 diamond_ore", "tp @a 0.5 -60 0.5", "give @a iron_pickaxe");
		ctx.runOnClient(mc -> {
			Settings.AUTO_RESPAWN.set(true);
			Settings.AUTO_RESUME.set(true);
		});
		ctx.waitTicks(10);
		botOn(ctx);
		ctx.runOnClient(mc -> Commands.run("mine diamond"));
		boolean first = until(ctx, "first diamond", mc -> Inv.count(s -> s.is(Items.DIAMOND)) >= 1, 1200);
		cmd(sp, "kill @a");
		boolean died = until(ctx, "death", mc -> !mc.player.isAlive(), 200);
		until(ctx, "new life", mc -> mc.player.isAlive() && mc.gui.screen() == null, 800);
		cmds(sp, "kill @e[type=item]", "give @a iron_pickaxe"); // what was carried is gone
		boolean second = until(ctx, "diamond after the respawn", mc -> Inv.count(s -> s.is(Items.DIAMOND)) >= 1, 2400);
		boolean oreGone = client(ctx, mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(30, -60, 6)).isAir());
		report("resume", first && died && second && oreGone, "first=" + first + " died=" + died + " second=" + second + " oreGone=" + oreGone);
		ctx.runOnClient(mc -> {
			Settings.AUTO_RESPAWN.reset();
			Settings.AUTO_RESUME.reset();
			Commands.run("stop");
		});
	}
}
