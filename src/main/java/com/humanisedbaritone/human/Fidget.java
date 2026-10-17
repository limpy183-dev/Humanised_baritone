package com.humanisedbaritone.human;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Small things a person does while standing around: hop, crouch for a moment, turn to look somewhere else. */
public final class Fidget {
	private enum Act { HOP, CROUCH, TURN }

	private static final Minecraft mc = Minecraft.getInstance();
	private static int cooldown = 200, left;
	private static Act act;
	private static double yaw, pitch;

	private Fidget() {
	}

	private static int nextGap() {
		double f = Settings.VAR_FIDGET.d();
		return (int) (20 * Rng.range(5, 22) / (0.15 + f));
	}

	/** Returns true while a fidget is using the controls. Call only while the bot has nothing to do. */
	public static boolean tick() {
		LocalPlayer p = mc.player;
		double f = Settings.VAR_FIDGET.d();
		if (f <= 0 || !p.onGround()) {
			left = 0;
			return false;
		}
		if (left > 0) {
			left--;
			switch (act) {
				case HOP -> Bot.ctl.jump = left == 0;
				case CROUCH -> Bot.ctl.sneak = true;
				case TURN -> Bot.look.aim(yaw, pitch, 0.15, 6);
			}
			return true;
		}
		if (--cooldown > 0) return false;
		cooldown = nextGap();
		act = Act.values()[Rng.index(Act.values().length)];
		if (act == Act.HOP && mc.level.getBlockState(p.blockPosition().below()).is(net.minecraft.world.level.block.Blocks.FARMLAND)) act = Act.TURN; // hopping tramples crops
		switch (act) {
			case HOP -> left = 1;
			case CROUCH -> left = Rng.range(3, 9);
			case TURN -> {
				left = Rng.range(25, 70);
				yaw = p.getYRot() + Rng.range(-130, 130);
				pitch = Rng.range(-25, 18);
			}
		}
		return true;
	}
}
