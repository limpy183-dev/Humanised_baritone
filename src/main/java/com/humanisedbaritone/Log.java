package com.humanisedbaritone;

import com.humanisedbaritone.settings.Settings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Console history shown in the GUI, optionally mirrored to the in-game chat. */
public final class Log {
	private static final Deque<String> LINES = new ArrayDeque<>();
	public static int revision;

	private Log() {
	}

	public static synchronized List<String> lines() {
		return new ArrayList<>(LINES);
	}

	public static synchronized void clear() {
		LINES.clear();
		revision++;
	}

	public static void info(String s) {
		add(s);
		if (Settings.FEEDBACK.on()) chat(s, 0x55E0D0);
	}

	/** Shown in the console and always in chat. */
	public static void say(String s) {
		add(s);
		chat(s, 0x55E0D0);
	}

	public static void warn(String s) {
		add("! " + s);
		if (Settings.FEEDBACK.on()) chat(s, 0xFFB454);
	}

	/** A line for the console only: the scanner sends its own chat message. */
	public static void console(String s) {
		add(s);
	}

	public static void debug(String s) {
		if (Settings.DEBUG.on()) add("[debug] " + s);
	}

	/** Tests: HB_LIVE=1 prints every line as it happens (the console only keeps the last 300). */
	private static final boolean LIVE = System.getenv("HB_LIVE") != null;

	private static synchronized void add(String s) {
		if (LIVE) System.out.println("[HBLOG] " + s);
		LINES.addLast(s);
		while (LINES.size() > 300) LINES.removeFirst();
		revision++;
	}

	private static void chat(String s, int rgb) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Component prefix = Component.literal("[HB] ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x7C8CFF)));
		Component body = Component.literal(s).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
		mc.player.sendSystemMessage(Component.empty().append(prefix).append(body));
	}
}
