package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.behave.Combat;
import com.humanisedbaritone.human.Gaze;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Stay around one spot, fight anything that comes close and go back to the spot afterwards. */
public final class GuardTask extends Task {
	private static final Minecraft mc = Minecraft.getInstance();
	private BlockPos home;
	private int idle;

	@Override public String name() {
		return "Guarding this spot";
	}

	@Override public String tag() {
		return "GUARD";
	}

	@Override public void start() {
		home = mc.player.blockPosition();
		Combat.forceDefend = true;
	}

	@Override public void stop() {
		Combat.forceDefend = false;
		Bot.nav.cancel();
	}

	@Override public String status() {
		return "at " + home.getX() + " " + home.getY() + " " + home.getZ();
	}

	@Override public boolean tick() {
		double d = Math.sqrt(mc.player.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5));
		if (d > 2.5) {
			if (!(Bot.nav.goal() instanceof Goal.Near)) Bot.nav.setGoal(new Goal.Near(home.getX(), home.getY(), home.getZ(), 1.2));
			Nav.State s = Bot.nav.tick();
			if (s == Nav.State.FAILED) Bot.nav.cancel();
			return false;
		}
		Bot.nav.cancel();
		idle++;
		Gaze.tick(false, false);
		if (Gaze.glancing()) Bot.look.aim(mc.player.getYRot() + Gaze.yaw(false), Gaze.pitch(false), 0.1, 30);
		if (idle % 200 == 0 && Rng.chance(0.3)) Bot.ctl.jump = mc.player.onGround();
		return false;
	}
}
