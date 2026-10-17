package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;

/** Walk to a goal. */
public final class GotoTask extends Task {
	private final Goal goal;

	public GotoTask(Goal goal) {
		this.goal = goal;
	}

	@Override public String name() {
		return "Travelling to " + goal.describe();
	}

	@Override public String tag() {
		return "TRAVEL";
	}

	@Override public void start() {
		Bot.nav.setGoal(goal);
	}

	@Override public boolean tick() {
		Nav.State s = Bot.nav.tick();
		if (s == Nav.State.ARRIVED) {
			Log.info("Arrived at " + goal.describe() + ".");
			return true;
		}
		if (s == Nav.State.FAILED) {
			Log.warn("Cannot reach " + goal.describe() + " (" + Bot.nav.failReason + ").");
			return true;
		}
		return false;
	}

	@Override public void stop() {
		Bot.nav.cancel();
	}

	@Override public String status() {
		double eta = Bot.nav.eta();
		return Bot.nav.describe() + (eta >= 0 ? String.format("  -  about %.0f s", eta) : "");
	}

	@Override public double progress() {
		return Bot.nav.progress();
	}
}
