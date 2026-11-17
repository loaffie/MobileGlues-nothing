package com.loaffie.spikecatcher.mixin;

import com.loaffie.spikecatcher.SpikeCatcher;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	// By descriptor, so a Minecraft version that changes this method skips the hook instead of crashing.
	@Inject(method = "render(Z)V", at = @At("HEAD"), require = 0)
	private void spikecatcher$frameStart(boolean tick, CallbackInfo ci) {
		SpikeCatcher.frameFromMixin();
	}
}
