package com.greysworkshop.tradeseeker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.List;

/** The on-screen notice while it waits for a sleeping villager: a small panel near the top, clear of the chat and hotbar. */
final class Hud {
	private Hud() {}

	static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden || client.currentScreen != null) return;
		List<Text> lines = Reroller.notice(client);
		if (lines == null) return;
		TextRenderer font = client.textRenderer;
		// Wrapped to fit small screens: the title, then the explanation, then the tip.
		int max = Math.min(300, context.getScaledWindowWidth() - 40);
		List<List<net.minecraft.text.OrderedText>> wrapped = new java.util.ArrayList<>();
		int w = 0, rows = 0;
		for (Text line : lines) {
			List<net.minecraft.text.OrderedText> parts = font.wrapLines(line, max);
			wrapped.add(parts);
			rows += parts.size();
			for (var part : parts) w = Math.max(w, font.getWidth(part));
		}
		w += 16;
		int h = rows * 10 + 10;
		int x = (context.getScaledWindowWidth() - w) / 2, y = 34;
		context.fill(x, y, x + w, y + h, 0xC0101018);
		context.drawStrokedRectangle(x, y, w, h, 0xFF6A6AB4);
		int ty = y + 5;
		for (int i = 0; i < wrapped.size(); i++) {
			int color = i == 0 ? 0xFFFFD27F : i == wrapped.size() - 1 ? 0xFF9A9AB0 : 0xFFE6E6EE;
			for (var part : wrapped.get(i)) {
				context.drawTextWithShadow(font, part, x + 8, ty, color);
				ty += 10;
			}
		}
	}
}
