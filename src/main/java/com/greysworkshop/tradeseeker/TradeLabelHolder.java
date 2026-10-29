package com.greysworkshop.tradeseeker;

import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** What an entity's render state carries for the trade labels over its head. */
public interface TradeLabelHolder {
	List<Text> tradeseeker$lines();

	Vec3d tradeseeker$labelPos();

	void tradeseeker$setLabels(List<Text> lines, Vec3d pos);
}
