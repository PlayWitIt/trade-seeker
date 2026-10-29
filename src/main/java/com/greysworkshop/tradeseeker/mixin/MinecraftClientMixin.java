package com.greysworkshop.tradeseeker.mixin;

import com.greysworkshop.tradeseeker.Reroller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** So a run carries on while you do other things: tabbed out, in the pause menu, in chat. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	/** Trades a run opens while you're in another screen stay out of sight (they're still open, behind it). */
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void tradeseeker$keepYourScreen(Screen screen, CallbackInfo ci) {
		if (Reroller.hidesTrades((MinecraftClient) (Object) this, screen)) ci.cancel();
	}

	/** In single player, a menu (or tabbing out, which opens the pause menu) would freeze the world mid-run. */
	@Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;shouldPause()Z"))
	private boolean tradeseeker$keepWorldRunning(Screen screen) {
		return !Reroller.keepsWorldRunning(screen) && screen.shouldPause();
	}
}
