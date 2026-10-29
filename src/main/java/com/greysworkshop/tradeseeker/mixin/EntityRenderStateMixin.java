package com.greysworkshop.tradeseeker.mixin;

import com.greysworkshop.tradeseeker.TradeLabelHolder;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements TradeLabelHolder {
	@Unique
	private List<Text> tradeseeker$lines = List.of();
	@Unique
	private Vec3d tradeseeker$labelPos;

	@Override
	public List<Text> tradeseeker$lines() {
		return tradeseeker$lines;
	}

	@Override
	public Vec3d tradeseeker$labelPos() {
		return tradeseeker$labelPos;
	}

	@Override
	public void tradeseeker$setLabels(List<Text> lines, Vec3d pos) {
		tradeseeker$lines = lines;
		tradeseeker$labelPos = pos;
	}
}
