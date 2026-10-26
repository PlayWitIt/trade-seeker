package com.greysworkshop.tradeseeker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TradeSeekerClient implements ClientModInitializer {
	public static final String MOD_ID = "tradeseeker";
	public static final Logger LOGGER = LoggerFactory.getLogger("Trade Seeker");

	private static KeyBinding menuKey, startKey;

	@Override
	public void onInitializeClient() {
		ModConfig.load();
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.tradeseeker.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.tradeseeker.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (menuKey.wasPressed()) {
				if (client.currentScreen == null) client.setScreen(new SeekerScreen());
			}
			while (startKey.wasPressed()) {
				if (Reroller.running()) {
					Reroller.stop(client, Text.translatable("tradeseeker.msg.stopped", Reroller.rolls()).formatted(Formatting.YELLOW));
				} else if (client.currentScreen == null) {
					Reroller.start(client);
				}
			}
			Reroller.tick(client);
			Sounds.tick(client);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Reroller.stop(client, null));
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "notice"), Hud::render);

		if (Boolean.getBoolean("tradeseeker.selftest")) SelfTest.start();
	}
}
