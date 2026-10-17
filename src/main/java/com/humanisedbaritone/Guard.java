package com.humanisedbaritone;

import java.util.HashSet;
import java.util.Set;

/** Keeps a bug in the bot from crashing the game: the error is reported once and the bot switches itself off. */
public final class Guard {
	private static final Set<String> seen = new HashSet<>();

	private Guard() {
	}

	public static void run(String where, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			if (seen.add(where + t)) {
				Log.warn("Internal error in " + where + ": " + t);
				t.printStackTrace();
			}
			if (Bot.enabled) {
				Log.warn("The bot was switched off because of that error. You can turn it back on.");
				Bot.fault();
			}
		}
	}
}
