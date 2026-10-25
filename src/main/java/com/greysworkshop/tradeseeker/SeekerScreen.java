package com.greysworkshop.tradeseeker;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pick the books you want and the price you'll pay, then start. */
public final class SeekerScreen extends Screen {
	private static final int ROW = 16;
	private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, DIM = 0xFF777777, GREEN = 0xFF55FF55, GOLD = 0xFFFFAA00;

	private record Choice(String id, String name, int maxLevel) {}

	private final List<Choice> all = new ArrayList<>();
	private final List<Choice> shown = new ArrayList<>();
	private TextFieldWidget search, minPrice, maxPrice;
	private ButtonWidget startButton;
	private double scroll;
	private int listX0, listX1, listY0, listY1;

	public SeekerScreen() {
		super(Text.translatable("tradeseeker.title"));
	}

	@Override
	protected void init() {
		loadChoices();
		ModConfig config = ModConfig.INSTANCE;
		int w = Math.min(300, width - 20);
		listX0 = (width - w) / 2;
		listX1 = listX0 + w;
		listY0 = 46;
		listY1 = height - 78;

		search = addDrawableChild(new TextFieldWidget(textRenderer, listX0, 26, w, 16, Text.translatable("tradeseeker.search")));
		search.setPlaceholder(Text.translatable("tradeseeker.search").formatted(Formatting.DARK_GRAY));
		search.setChangedListener(s -> filter());
		setInitialFocus(search);

		int rowY = height - 70;
		minPrice = addDrawableChild(priceField(listX0 + 42, rowY, config.minPrice));
		maxPrice = addDrawableChild(priceField(listX0 + 96, rowY, config.maxPrice));
		addDrawableChild(ButtonWidget.builder(autoBuyLabel(), b -> {
			config.autoBuy = !config.autoBuy;
			b.setMessage(autoBuyLabel());
			ModConfig.save();
		}).dimensions(listX1 - 120, rowY - 2, 120, 20)
				.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("tradeseeker.autobuy.tip"))).build());

		int buttonsY = height - 28;
		startButton = addDrawableChild(ButtonWidget.builder(startLabel(), b -> {
			saveFields();
			if (Reroller.running()) {
				Reroller.stop(client, Text.translatable("tradeseeker.msg.stopped", Reroller.rolls()));
				b.setMessage(startLabel());
			} else {
				close();
				Reroller.start(client);
			}
		}).dimensions(width / 2 - 154, buttonsY, 150, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
				.dimensions(width / 2 + 4, buttonsY, 150, 20).build());
		filter();
	}

	private TextFieldWidget priceField(int x, int y, int value) {
		TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, 30, 16, Text.empty());
		field.setMaxLength(2);
		field.setTextPredicate(s -> s.chars().allMatch(Character::isDigit));
		field.setText(Integer.toString(value));
		field.setChangedListener(s -> saveFields());
		return field;
	}

	private void loadChoices() {
		all.clear();
		if (client == null || client.world == null) return;
		Registry<Enchantment> registry = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
		List<RegistryEntry.Reference<Enchantment>> entries = registry.streamEntries().toList();
		// Only books a librarian can sell, unless the server didn't send that list.
		boolean anyTradeable = entries.stream().anyMatch(e -> e.isIn(EnchantmentTags.TRADEABLE));
		for (RegistryEntry.Reference<Enchantment> entry : entries) {
			if (anyTradeable && !entry.isIn(EnchantmentTags.TRADEABLE)) continue;
			all.add(new Choice(entry.registryKey().getValue().toString(), entry.value().description().getString(), entry.value().getMaxLevel()));
		}
		// Wanted ones first, then by name.
		all.sort(Comparator.comparing((Choice c) -> !ModConfig.INSTANCE.wanted.containsKey(c.id)).thenComparing(Choice::name));
	}

	private void filter() {
		String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
		shown.clear();
		for (Choice c : all) if (q.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(q) || c.id.contains(q)) shown.add(c);
		scroll = clampScroll(scroll);
	}

	private void saveFields() {
		ModConfig config = ModConfig.INSTANCE;
		config.minPrice = parse(minPrice.getText(), config.minPrice);
		config.maxPrice = parse(maxPrice.getText(), config.maxPrice);
		ModConfig.save();
	}

	private static int parse(String s, int fallback) {
		try {
			return Math.max(1, Math.min(64, Integer.parseInt(s)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private Text autoBuyLabel() {
		return Text.translatable("tradeseeker.autobuy", ModConfig.INSTANCE.autoBuy
				? Text.translatable("options.on").formatted(Formatting.GREEN) : Text.translatable("options.off").formatted(Formatting.GRAY));
	}

	private Text startLabel() {
		return Text.translatable(Reroller.running() ? "tradeseeker.stop" : "tradeseeker.start");
	}

	private double clampScroll(double value) {
		int max = Math.max(0, shown.size() * ROW - (listY1 - listY0));
		return Math.max(0, Math.min(max, value));
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		double mx = click.x(), my = click.y();
		if (mx < listX0 || mx >= listX1 || my < listY0 || my >= listY1) return false;
		int index = (int) ((my - listY0 + scroll) / ROW);
		if (index < 0 || index >= shown.size()) return false;
		Choice c = shown.get(index);
		var wanted = ModConfig.INSTANCE.wanted;
		boolean onLevel = mx >= listX1 - 70 && wanted.containsKey(c.id) && c.maxLevel > 1;
		if (onLevel) {
			// Left click raises the lowest level that counts, right click lowers it; both wrap around.
			int level = wanted.get(c.id) + (click.button() == 1 ? -1 : 1);
			if (level > c.maxLevel) level = 1;
			if (level < 1) level = c.maxLevel;
			wanted.put(c.id, level);
		} else if (wanted.containsKey(c.id)) {
			wanted.remove(c.id);
		} else {
			wanted.put(c.id, c.maxLevel);
		}
		ModConfig.save();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = clampScroll(scroll - verticalAmount * ROW * 2);
		return true;
	}

	@Override
	public void removed() {
		if (minPrice != null) saveFields();
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, GOLD);

		context.fill(listX0, listY0, listX1, listY1, 0x88000000);
		var wanted = ModConfig.INSTANCE.wanted;
		if (shown.isEmpty()) {
			Text none = Text.translatable(all.isEmpty() ? "tradeseeker.need_world" : "tradeseeker.no_match");
			context.drawTextWithShadow(textRenderer, none, listX0 + 6, listY0 + 6, GRAY);
		}
		context.enableScissor(listX0, listY0, listX1, listY1);
		int y = listY0 - (int) scroll;
		for (Choice c : shown) {
			if (y + ROW >= listY0 && y <= listY1) {
				boolean on = wanted.containsKey(c.id);
				boolean hover = mouseX >= listX0 && mouseX < listX1 && mouseY >= y && mouseY < y + ROW && mouseY >= listY0 && mouseY < listY1;
				if (hover) context.fill(listX0, y, listX1, y + ROW, 0x30FFFFFF);
				context.drawTextWithShadow(textRenderer, on ? "☑" : "☐", listX0 + 5, y + 4, on ? GREEN : DIM);
				context.drawTextWithShadow(textRenderer, c.name, listX0 + 18, y + 4, on ? WHITE : GRAY);
				if (on) {
					String level = c.maxLevel > 1
							? Text.translatable("tradeseeker.level_min", roman(wanted.get(c.id)), roman(c.maxLevel)).getString()
							: Text.translatable("tradeseeker.level_only").getString();
					context.drawTextWithShadow(textRenderer, level, listX1 - 6 - textRenderer.getWidth(level), y + 4, GOLD);
				}
			}
			y += ROW;
		}
		context.disableScissor();

		int rowY = height - 70;
		context.drawTextWithShadow(textRenderer, Text.translatable("tradeseeker.price"), listX0, rowY + 4, WHITE);
		context.drawTextWithShadow(textRenderer, "–", listX0 + 80, rowY + 4, GRAY);
		context.drawTextWithShadow(textRenderer, Text.translatable("tradeseeker.emeralds"), listX0 + 132, rowY + 4, GRAY);

		Text status = Reroller.running()
				? Text.translatable("tradeseeker.status.running", Reroller.rolls()).formatted(Formatting.GREEN)
				: Text.translatable("tradeseeker.status.picked", wanted.size());
		context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height - 50, GRAY);
		context.drawCenteredTextWithShadow(textRenderer, Text.translatable("tradeseeker.hint"), width / 2, height - 39, DIM);
	}

	static String roman(int n) {
		return switch (n) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> Integer.toString(n);
		};
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
