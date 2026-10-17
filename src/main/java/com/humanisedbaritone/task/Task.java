package com.humanisedbaritone.task;

/** A job the bot works on until it is done: go somewhere, mine something, follow someone. */
public abstract class Task {
	public abstract String name();

	/** Short upper-case label for the status chip. */
	public String tag() {
		return "BUSY";
	}

	public void start() {
	}

	/** Runs every tick while this task owns the player. Returns true when the task is finished. */
	public abstract boolean tick();

	public void stop() {
	}

	/** One line about what is happening right now. */
	public String status() {
		return "";
	}

	/** 0..1, or -1 if unknown. */
	public double progress() {
		return -1;
	}
}
