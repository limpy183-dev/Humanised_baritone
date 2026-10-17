package com.humanisedbaritone.chat;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.settings.Setting;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Receives server announcements and sends one normal chat message when the chosen delay has elapsed. */
public final class ChatChallenges {
	private static final ChallengeResponder RESPONDER = new ChallengeResponder(ChatChallenges::options, ChatChallenges::names, new Random());
	private ChatChallenges() {}

	private static ChallengeResponder.Options options(ChallengeResponder.Kind kind) {
		return switch (kind) {
			case REVERSE -> options(Settings.CHAT_REVERSE, Settings.CHAT_REVERSE_MIN, Settings.CHAT_REVERSE_MAX);
			case UNSCRAMBLE -> options(Settings.CHAT_UNSCRAMBLE, Settings.CHAT_UNSCRAMBLE_MIN, Settings.CHAT_UNSCRAMBLE_MAX);
			case NUMBER -> options(Settings.CHAT_NUMBER, Settings.CHAT_NUMBER_MIN, Settings.CHAT_NUMBER_MAX);
		};
	}

	private static ChallengeResponder.Options options(Setting.Bool enabled, Setting.Num min, Setting.Num max) {
		return new ChallengeResponder.Options(enabled.on(), Math.round(min.d() * 1000), Math.round(max.d() * 1000));
	}

	private static List<String> names() {
		List<String> names = new ArrayList<>();
		// Registry paths provide English aliases even when the client uses another language.
		for (var id : BuiltInRegistries.ITEM.keySet()) {
			names.add(id.getPath().replace('_', ' '));
			names.add(new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getHoverName().getString());
		}
		for (var id : BuiltInRegistries.BLOCK.keySet()) {
			names.add(id.getPath().replace('_', ' '));
			names.add(BuiltInRegistries.BLOCK.getValue(id).getName().getString());
		}
		return names;
	}

	private static long now() { return System.nanoTime() / 1_000_000; }
	private static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		return Bot.enabled && !Bot.paused() && mc.player != null && mc.player.isAlive() && mc.level != null && mc.getConnection() != null;
	}

	public static void receive(Component message, boolean overlay) {
		if (!overlay && active()) RESPONDER.receive(message.getString(), now());
	}

	public static void tick() {
		if (!active()) {
			reset();
			return;
		}
		String answer = RESPONDER.poll(now());
		if (answer != null) Minecraft.getInstance().getConnection().sendChat(answer);
	}

	public static void reset() { RESPONDER.reset(); }
	public static String status() { return RESPONDER.status(); }
}
