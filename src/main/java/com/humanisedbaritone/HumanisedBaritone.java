package com.humanisedbaritone;

import com.humanisedbaritone.gui.BotScreen;
import com.humanisedbaritone.chat.ChatChallenges;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.scan.Finds;
import com.humanisedbaritone.scan.Radar;
import com.humanisedbaritone.settings.Settings;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class HumanisedBaritone implements ClientModInitializer {
	public static final String ID = "humanised_baritone";
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
	/** Opens the control panel. Default: the apostrophe key. */
	public static final KeyMapping OPEN_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.humanised_baritone.open", InputConstants.Type.KEYSYM, InputConstants.KEY_APOSTROPHE, CATEGORY));
	public static final KeyMapping STOP_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.humanised_baritone.stop", InputConstants.Type.KEYSYM, InputConstants.KEY_SEMICOLON, CATEGORY));
	public static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.humanised_baritone.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));

	@Override
	public void onInitializeClient() {
		Settings.load();
		Rng.reseed();

		ClientTickEvents.START_CLIENT_TICK.register(mc -> Guard.run("bot", Bot::tick));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Guard.run("keys", () -> handleKeys(mc));
			Guard.run("overlay", Render::tick);
			Guard.run("chat challenges", ChatChallenges::tick);
			Radar.tick(); // has its own guard: a bug in the scanner must not switch the bot off
		});
		ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register(Radar::onBlockEntity);
		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(Radar::onBlockEntityGone);
		ClientChunkEvents.CHUNK_LOAD.register(Radar::onChunk);
		ClientChunkEvents.CHUNK_UNLOAD.register(Radar::onChunkUnload);
		LevelRenderEvents.COLLECT_SUBMITS.register(com.humanisedbaritone.build.Ghosts::submit);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> Bot.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			Radar.onDisconnect();
			Bot.onDisconnect();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			Settings.save();
			Finds.flush();
		});
		ClientSendMessageEvents.ALLOW_CHAT.register(msg -> !Commands.interceptChat(msg));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> Guard.run("chat challenges", () -> ChatChallenges.receive(message, overlay)));
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(ID, "hud"), (g, d) -> Guard.run("hud", () -> Hud.extract(g, d)));
	}

	private static void handleKeys(Minecraft mc) {
		while (OPEN_KEY.consumeClick()) {
			if (mc.gui.screen() == null && mc.player != null) mc.gui.setScreen(new BotScreen());
		}
		while (STOP_KEY.consumeClick()) Commands.run("stop");
		while (TOGGLE_KEY.consumeClick()) Bot.setEnabled(!Bot.enabled);
	}
}
