package com.greysworkshop.tradeseeker.mixin;

import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the found trade be shown as selected and scrolled into view, the way a click would. */
@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {
	@Accessor("selectedIndex")
	void tradeseeker$setSelectedIndex(int index);

	@Accessor("indexStartOffset")
	void tradeseeker$setIndexStartOffset(int offset);
}
