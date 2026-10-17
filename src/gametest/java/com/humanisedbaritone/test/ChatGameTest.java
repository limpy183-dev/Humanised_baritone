package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.chat.ChatChallenges;
import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** Real server announcements, outgoing chat echoed by the server, and the new page in all styles. */
public class ChatGameTest extends BotTest {
	private final List<String> received = new ArrayList<>();

	private void announce(TestSingleplayerContext sp, String text) {
		cmd(sp, "tellraw @a " + new com.google.gson.Gson().toJson(java.util.Map.of("text", text)));
	}

	private boolean echoed(ClientGameTestContext ctx, String answer) {
		return until(ctx, "chat reply " + answer, mc -> received.stream().anyMatch(s -> s.endsWith("> " + answer)), 160);
	}

	@Override public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(40);
			cmds(sp, "difficulty peaceful", "gamemode creative @a");
			ctx.runOnClient(mc -> {
				ClientReceiveMessageEvents.CHAT.register((message, signed, sender, type, time) -> received.add(message.getString()));
				Settings.PASSIVE.set(false);
				Settings.VAR_FIDGET.set(0.0);
				Settings.CHAT_REVERSE.set(true);
				Settings.CHAT_UNSCRAMBLE.set(true);
				Settings.CHAT_NUMBER.set(true);
				for (var delay : List.of(Settings.CHAT_REVERSE_MIN, Settings.CHAT_REVERSE_MAX,
					Settings.CHAT_UNSCRAMBLE_MIN, Settings.CHAT_UNSCRAMBLE_MAX, Settings.CHAT_NUMBER_MIN, Settings.CHAT_NUMBER_MAX)) delay.set(0.1);
			});
			botOn(ctx);
			announce(sp, "Unreverse the letters to win!\n➤ AbC123xY");
			report("reverse-network", echoed(ctx, "Yx321CbA"), client(ctx, mc -> received.toString()));
			announce(sp, "Unscramble the word to win!");
			announce(sp, "➤ drows_dnomaid");
			report("unscramble-registry-network", echoed(ctx, "diamond sword"), client(ctx, mc -> received.toString()));
			announce(sp, "Guess the number between 7 and 7!");
			report("number-network", echoed(ctx, "7"), client(ctx, mc -> received.toString()));
			int before = client(ctx, mc -> received.size());
			announce(sp, "Guess the number between 7 and 7!");
			ctx.waitTicks(20);
			report("duplicate-suppression", client(ctx, mc -> received.size() == before), "one answer per puzzle");

			ctx.runOnClient(mc -> {
				Settings.CHAT_REVERSE_MIN.set(3.0);
				Settings.CHAT_REVERSE_MAX.set(3.0);
			});
			announce(sp, "Unreverse the letters: cancelme");
			until(ctx, "queued answer", mc -> ChatChallenges.status().startsWith("Reply queued:"), 40);
			ctx.runOnClient(mc -> Settings.CHAT_REVERSE.set(false));
			ctx.waitTicks(70);
			report("toggle-cancels", client(ctx, mc -> received.size() == before), "no queued message sent after toggle off");
			ctx.runOnClient(mc -> Bot.setEnabled(false));
			announce(sp, "Guess between 8 and 8!");
			ctx.waitTicks(20);
			report("master-off", client(ctx, mc -> received.size() == before), "master switch stops listening");

			ctx.runOnClient(mc -> Settings.CHAT_REVERSE.set(true));
			for (var style : Settings.UiStyle.values()) {
				ctx.runOnClient(mc -> {
					Settings.UI_STYLE.set(style);
					mc.gui.setScreen(new BotScreen());
					((BotScreen) mc.gui.screen()).showTab(Settings.CHAT);
				});
				ctx.waitTicks(25);
				shot(ctx, "chat-" + style.name().toLowerCase());
				report("chat-page-" + style, client(ctx, mc -> mc.gui.screen() instanceof BotScreen), "page renders");
				ctx.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() * 0.4,
					mc.getWindow().getGuiScaledHeight() * 0.6, 0, -30));
				ctx.waitTicks(20);
				shot(ctx, "chat-" + style.name().toLowerCase() + "-number");
			}
			finish();
		}
	}
}
