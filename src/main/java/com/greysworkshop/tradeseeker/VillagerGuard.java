package com.greysworkshop.tradeseeker;

import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.RaycastContext;

import java.util.List;

/**
 * "Don't let me hit villagers": which attacks would land on a villager. The server works out who a hit reaches, so
 * the only way to spare a villager from the client is not to send the attack at all.
 */
public final class VillagerGuard {
	private VillagerGuard() {}

	/** A hit on this target would hurt a villager: the target itself, or one caught in the sword's sweep. */
	public static boolean attackHurtsVillager(PlayerEntity player, Entity target) {
		if (target instanceof VillagerEntity) return true;
		if (!sweeps(player)) return false;
		// As PlayerEntity.doSweepingAttack picks who the sweep reaches.
		return !player.getEntityWorld().getEntitiesByClass(VillagerEntity.class, target.getBoundingBox().expand(1, 0.25, 1),
				v -> v != target && player.squaredDistanceTo(v) < 9).isEmpty();
	}

	/** A spear's stab would reach a villager (it goes through everything in its range). */
	public static boolean stabHurtsVillager(PlayerEntity player) {
		return ProjectileUtil.collectPiercingCollisions(player, player.getAttackRange(), e -> PiercingWeaponComponent.canHit(player, e),
				RaycastContext.ShapeType.COLLIDER).map(block -> List.<EntityHitResult>of(), hits -> hits).stream()
				.anyMatch(hit -> hit.getEntity() instanceof VillagerEntity);
	}

	/** As PlayerEntity.canUseSweepAttack: a sword, fully charged, on the ground, not sprinting or falling, barely moving. */
	private static boolean sweeps(PlayerEntity player) {
		if (!player.getStackInHand(Hand.MAIN_HAND).isIn(ItemTags.SWORDS)) return false;
		if (player.getAttackCooldownProgress(0.5f) <= 0.9f || !player.isOnGround() || player.isSprinting()) return false;
		return player.getMovement().horizontalLengthSquared() < MathHelper.square(player.getMovementSpeed() * 2.5);
	}
}
