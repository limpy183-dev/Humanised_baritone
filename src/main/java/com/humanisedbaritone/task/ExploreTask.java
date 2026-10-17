package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;

/** Wander into chunks that have not been seen yet, leg by leg. */
public final class ExploreTask extends Task {
	private final Goal first;
	private int legs, ticks, failed;

	/** @param first where to start heading, or null to choose freely */
	public ExploreTask(Goal first) {
		this.first = first;
	}

	@Override public String name() {
		return "Exploring";
	}

	@Override public String tag() {
		return "EXPLORE";
	}

	@Override public void start() {
		Explorer.reset();
		Goal g = first != null ? first : Explorer.pick(40, 90);
		if (g != null) Bot.nav.setGoal(g);
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	@Override public String status() {
		return Explorer.visitedChunks() + " chunks seen  -  leg " + (legs + 1);
	}

	@Override public boolean tick() {
		Explorer.mark();
		ticks++;
		Nav.State s = Bot.nav.tick();
		if (s == Nav.State.ARRIVED || s == Nav.State.FAILED || Bot.nav.goal() == null || ticks > 2400) {
			if (s == Nav.State.FAILED) failed++;
			else failed = 0;
			if (failed > 6) {
				Log.warn("Nowhere left to explore from here.");
				return true;
			}
			legs++;
			ticks = 0;
			Goal g = Explorer.pick(40, 90);
			if (g == null) {
				Log.info("Explored everything that is loaded.");
				return true;
			}
			Bot.nav.setGoal(g);
		}
		return false;
	}
}
