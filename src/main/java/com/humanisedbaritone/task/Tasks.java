package com.humanisedbaritone.task;

import com.humanisedbaritone.Log;
import java.util.ArrayDeque;
import java.util.Deque;

/** The current task and the queue of tasks that follow it. */
public final class Tasks {
	private static Task current;
	private static final Deque<Task> queue = new ArrayDeque<>();
	private static boolean paused;

	private Tasks() {
	}

	public static Task current() {
		return current;
	}

	public static boolean paused() {
		return paused;
	}

	public static void setPaused(boolean p) {
		paused = p;
	}

	public static int queued() {
		return queue.size();
	}

	/** Replaces whatever is running. */
	public static void start(Task t) {
		if (current != null) current.stop();
		queue.clear();
		paused = false;
		current = t;
		t.start();
	}

	/** After a respawn: run the interrupted task again from its start. */
	public static void restartCurrent() {
		if (current != null) {
			current.stop();
			current.start();
		}
	}

	public static void enqueue(Task t) {
		if (current == null) start(t);
		else queue.add(t);
	}

	public static void stopAll() {
		if (current != null) current.stop();
		current = null;
		queue.clear();
		paused = false;
	}

	/** Runs the current task for one tick. Returns true when a task is running and had control. */
	public static boolean tick() {
		if (current == null || paused) return false;
		boolean done;
		try {
			done = current.tick();
		} catch (RuntimeException e) {
			Log.warn("Task '" + current.name() + "' crashed: " + e);
			e.printStackTrace();
			done = true;
		}
		if (done) {
			current.stop();
			current = queue.poll();
			if (current != null) current.start();
		}
		return true;
	}
}
