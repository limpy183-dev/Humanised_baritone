package com.humanisedbaritone.mixin;

import com.humanisedbaritone.Bot;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the bot a per-frame hook at the exact point where a physical mouse would turn the camera. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void humanisedBaritone$frame(CallbackInfo ci) {
		Bot.frame();
	}
}
