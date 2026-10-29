package com.greysworkshop.tradeseeker.mixin;

import com.greysworkshop.tradeseeker.Reroller;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server sent a screen's whole contents, as it stands on the server. (At the end: on the game thread.) */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class NetworkMixin {
	@Inject(method = "onInventory", at = @At("TAIL"))
	private void tradeseeker$contents(InventoryS2CPacket packet, CallbackInfo ci) {
		Reroller.serverSentContents(packet.syncId());
	}
}
