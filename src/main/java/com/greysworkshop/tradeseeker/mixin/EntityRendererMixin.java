package com.greysworkshop.tradeseeker.mixin;

import com.greysworkshop.tradeseeker.ModConfig;
import com.greysworkshop.tradeseeker.TradeLabelHolder;
import com.greysworkshop.tradeseeker.TradeMemory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAttachmentType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** The trades it remembers for a villager, as labels over its head (above its name, if it has one showing). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "updateRenderState", at = @At("TAIL"))
	private void tradeseeker$labels(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
		TradeLabelHolder holder = (TradeLabelHolder) state;
		if (!(entity instanceof VillagerEntity v)) {
			holder.tradeseeker$setLabels(List.of(), null);
			return;
		}
		List<Text> lines = TradeMemory.lines(MinecraftClient.getInstance(), v);
		holder.tradeseeker$setLabels(lines, lines.isEmpty() ? null : v.getAttachments().getPointNullable(EntityAttachmentType.NAME_TAG, 0, v.getLerpedYaw(tickProgress)));
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void tradeseeker$drawLabels(EntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		TradeLabelHolder holder = (TradeLabelHolder) state;
		List<Text> lines = holder.tradeseeker$lines();
		if (lines.isEmpty() || holder.tradeseeker$labelPos() == null) return;
		// At the label size (Options → Text), grown or shrunk around the point over its head.
		float scale = ModConfig.INSTANCE.labelSize / 100f;
		Vec3d pos = holder.tradeseeker$labelPos();
		matrices.push();
		// (Labels are drawn half a block above the point.)
		matrices.translate(pos.x, pos.y + 0.5, pos.z);
		matrices.scale(scale, scale, scale);
		matrices.translate(-pos.x, -pos.y - 0.5, -pos.z);
		// Bottom line just above the name (or where the name would be, which stays its own size); the others stacked up from there.
		int bottom = state.displayName != null ? -Math.round(10 / scale) : 0;
		for (int i = 0; i < lines.size(); i++) {
			int y = bottom - (lines.size() - 1 - i) * 10;
			queue.submitLabel(matrices, pos, y, lines.get(i), true, state.light, state.squaredDistanceToCamera, camera);
		}
		matrices.pop();
	}
}
