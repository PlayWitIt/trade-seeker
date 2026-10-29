package com.greysworkshop.tradeseeker.mixin;

import com.greysworkshop.tradeseeker.ModConfig;
import com.greysworkshop.tradeseeker.Reroller;
import com.greysworkshop.tradeseeker.VillagerGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class InteractionMixin {
	/** "Don't let me hit villagers": an attack that would reach a villager is never sent, so it can't lower its opinion of you. */
	@Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
	private void tradeseeker$spareVillagers(PlayerEntity player, Entity target, CallbackInfo ci) {
		if (ModConfig.INSTANCE.protectVillagers && VillagerGuard.attackHurtsVillager(player, target)) ci.cancel();
	}

	/** The same for a spear's stab, which reaches everything in front of you. */
	@Inject(method = "attackWithPiercingWeapon", at = @At("HEAD"), cancellable = true)
	private void tradeseeker$spareVillagersStab(PiercingWeaponComponent weapon, CallbackInfo ci) {
		PlayerEntity player = MinecraftClient.getInstance().player;
		if (ModConfig.INSTANCE.protectVillagers && player != null && VillagerGuard.stabHurtsVillager(player)) ci.cancel();
	}

	/** Which villager trades opened by hand belong to (the crosshair isn't kept up to date once a screen is open). */
	@Inject(method = "interactEntity", at = @At("HEAD"))
	private void tradeseeker$noteVillager(PlayerEntity player, Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
		Reroller.usedEntity(entity);
	}
}
