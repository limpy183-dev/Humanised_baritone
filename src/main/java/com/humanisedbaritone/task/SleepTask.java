package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Interact;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.world.BlockScan;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;

/** Walk to the nearest bed and sleep until morning. */
public final class SleepTask extends Task {
	private enum Ph { FIND, GOTO, USE, SLEEP }

	private static final Minecraft mc = Minecraft.getInstance();
	private Ph ph = Ph.FIND;
	private BlockPos bed;
	private Interact.UseOp use;
	private int ticks, sleepTicks;
	private boolean everSlept;

	@Override public String name() {
		return "Going to sleep";
	}

	@Override public String tag() {
		return "SLEEP";
	}

	@Override public void start() {
		ph = Ph.FIND;
		bed = null;
		use = null;
		ticks = 0;
		sleepTicks = 0;
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	@Override public String status() {
		return switch (ph) {
			case FIND -> "looking for a bed";
			case GOTO -> "walking to the bed";
			case USE -> "getting into bed";
			case SLEEP -> "sleeping";
		};
	}

	private static boolean night() {
		return mc.level.isDarkOutside() || mc.level.isThundering();
	}

	@Override public boolean tick() {
		LocalPlayer p = mc.player;
		switch (ph) {
			case FIND -> {
				if (!night()) {
					Log.warn("Beds can only be used at night or in a thunderstorm.");
					return true;
				}
				List<BlockPos> beds = BlockScan.find(p.blockPosition(), 48, s -> s.is(BlockTags.BEDS), 12);
				if (beds.isEmpty()) {
					Log.warn("No bed within 48 blocks.");
					return true;
				}
				bed = beds.get(0);
				Bot.nav.setGoal(new Goal.Reach(bed, 2.8));
				ph = Ph.GOTO;
				ticks = 0;
			}
			case GOTO -> {
				ticks++;
				Nav.State s = Bot.nav.tick();
				// beds can be clicked from 4.5 blocks away but only slept in from within 3, so being able to aim is not enough
				boolean close = Math.abs(p.getX() - (bed.getX() + 0.5)) < 2.6 && Math.abs(p.getZ() - (bed.getZ() + 0.5)) < 2.6 && Math.abs(p.getY() - bed.getY()) < 1.5;
				if (s == Nav.State.ARRIVED || close && Interact.pickAim(bed, null) != null && ticks > 3) {
					Bot.nav.cancel();
					use = new Interact.UseOp(bed, p::isSleeping);
					ph = Ph.USE;
					ticks = 0;
				} else if (s == Nav.State.FAILED || ticks > 1200) {
					Log.warn("Cannot reach the bed.");
					return true;
				}
			}
			case USE -> {
				Interact.R r = use.tick();
				if (r == Interact.R.DONE) {
					ph = Ph.SLEEP;
					everSlept = true;
				} else if (r == Interact.R.FAIL || ++ticks > 200) {
					Log.warn("Could not get into the bed (monsters nearby, or it is not night).");
					return true;
				}
			}
			case SLEEP -> {
				sleepTicks++;
				if (!p.isSleeping() && sleepTicks > 10) {
					if (everSlept) Log.info("Good morning.");
					return true;
				}
			}
		}
		return false;
	}
}
