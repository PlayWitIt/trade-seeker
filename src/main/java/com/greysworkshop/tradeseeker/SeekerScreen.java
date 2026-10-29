package com.greysworkshop.tradeseeker;

import com.greysworkshop.tradeseeker.ModConfig.Want;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The menu: pick books on the left; on the right, set the lowest level and the price range for the one selected, with
 * the prices villagers really ask and the odds per re-roll. Below: buying options, a checklist of what's needed to
 * start, and Start. Drawn by hand (like Hoard Eye's search), laid out again every frame, so it fits any GUI scale.
 */
public final class SeekerScreen extends Screen {
	// Palette.
	private static final int PANEL = 0xC8121218, PANEL_EDGE = 0xFF2E2E3A, HEADER = 0xFF1C1C26;
	private static final int HOVER = 0x1EFFFFFF, FOCUS = 0x34FFFFFF;
	private static final int TEXT = 0xFFEDEDF2, MUTED = 0xFFA0A0AE, DIM = 0xFF686876;
	private static final int ACCENT = 0xFF4FE38A, ACCENT_DARK = 0xFF1F6B42, GOLD = 0xFFFFC94A, PURPLE = 0xFFC792FF;
	private static final int WARN = 0xFFFFD447, BAD = 0xFFFF6464, TRACK = 0xFF34343F, BAND = 0x66FFC94A, CURED_BAND = 0x3357D9A0;

	private static final int PAD = 8, ROW = 18, GAP = 6;
	/** The books most people re-roll for, at their best level. */
	private static final Map<String, Integer> STARTER_KIT = new LinkedHashMap<>();

	static {
		STARTER_KIT.put("minecraft:mending", 1);
		STARTER_KIT.put("minecraft:unbreaking", 3);
		STARTER_KIT.put("minecraft:efficiency", 5);
		STARTER_KIT.put("minecraft:fortune", 3);
		STARTER_KIT.put("minecraft:silk_touch", 1);
		STARTER_KIT.put("minecraft:protection", 4);
		STARTER_KIT.put("minecraft:feather_falling", 4);
		STARTER_KIT.put("minecraft:sharpness", 5);
		STARTER_KIT.put("minecraft:looting", 3);
		STARTER_KIT.put("minecraft:power", 5);
	}

	private enum Tab { ALL, PICKED, SEEN }

	private enum Drag { NONE, LOW, HIGH }

	/** One enchantment a librarian can sell. */
	private record Choice(String id, RegistryEntry<Enchantment> entry, String name, int minLevel, int maxLevel, boolean treasure,
			ItemStack icon) {}

	/** A clickable area found while drawing; the last one drawn is on top. */
	private record Hit(int x0, int y0, int x1, int y1, Runnable click, Supplier<List<Text>> tooltip) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	private final List<Choice> all = new ArrayList<>();
	private final List<Choice> shown = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private int tradeable;
	/** The lasting discount of the villager it would start on (from a cure), as Reroller learned it. */
	private int discount;
	/** Whether you cured that villager, as far as its trades have shown. */
	private Reroller.Cure cure = Reroller.Cure.UNKNOWN;
	private Tab tab = Tab.ALL;
	private Choice focused;
	private TextFieldWidget search;
	private double scroll;
	private Drag drag = Drag.NONE;
	private boolean options;
	private int optionsTab;
	private List<Reroller.Check> checks = List.of();
	private int checksAge;

	// Layout, worked out in init().
	private int listX0, listX1, detX0, detX1, bodyY0, bodyY1, rowsY0, rowsY1;
	// Where the price slider was drawn, for dragging.
	private int sliderX0, sliderX1;

	public SeekerScreen() {
		super(Text.translatable("tradeseeker.title"));
	}

	// ---- Setup ----

	@Override
	protected void init() {
		loadChoices();
		ModConfig config = ModConfig.INSTANCE;
		try {
			tab = Tab.valueOf(config.tab);
		} catch (IllegalArgumentException e) {
			tab = Tab.ALL;
		}
		if (focused == null) focused = find(config.focused);
		if (focused == null && !all.isEmpty()) focused = all.get(0);

		int listWidth = MathHelper.clamp(width * 2 / 5, 150, 240);
		listX0 = PAD;
		listX1 = PAD + listWidth;
		detX0 = listX1 + GAP;
		detX1 = width - PAD;
		bodyY0 = 24;
		bodyY1 = height - 64;
		rowsY0 = bodyY0 + 40;
		rowsY1 = bodyY1 - 14;

		String query = search == null ? "" : search.getText();
		search = addDrawableChild(new TextFieldWidget(textRenderer, listX0 + 4, bodyY0 + 4, listWidth - 8, 16, Text.translatable("tradeseeker.search")));
		search.setPlaceholder(Text.translatable("tradeseeker.search").formatted(Formatting.DARK_GRAY));
		search.setText(query);
		search.setChangedListener(s -> {
			if (!s.isEmpty() && tab == Tab.SEEN) setTab(Tab.ALL);
			filter();
		});
		setInitialFocus(search);
		filter();
		refreshChecks();
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
			Enchantment e = entry.value();
			ItemStack icon = EnchantmentHelper.getEnchantedBookWith(new EnchantmentLevelEntry(entry, e.getMaxLevel()));
			all.add(new Choice(entry.registryKey().getValue().toString(), entry, e.description().getString(), e.getMinLevel(), e.getMaxLevel(),
					entry.isIn(EnchantmentTags.DOUBLE_TRADE_PRICE), icon));
		}
		tradeable = all.size();
		all.sort(Comparator.comparing(Choice::name));
	}

	private Choice find(String id) {
		for (Choice c : all) if (c.id.equals(id)) return c;
		return null;
	}

	private void filter() {
		String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
		shown.clear();
		for (Choice c : all) {
			if (tab == Tab.PICKED && !picked(c)) continue;
			if (q.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(q) || c.id.contains(q)) shown.add(c);
		}
		scroll = clampScroll(scroll);
	}

	private void setTab(Tab next) {
		tab = next;
		scroll = 0;
		ModConfig.INSTANCE.tab = next.name();
		ModConfig.save();
		filter();
	}

	private void focus(Choice c) {
		focused = c;
		ModConfig.INSTANCE.focused = c.id;
	}

	// ---- Wants ----

	private static boolean picked(Choice c) {
		return ModConfig.INSTANCE.wanted.containsKey(c.id);
	}

	/** The choice's settings, picking it first if it wasn't (changing a setting means you want it). */
	private Want wantOf(Choice c) {
		Want w = ModConfig.INSTANCE.wanted.get(c.id);
		if (w == null) {
			w = new Want(c.maxLevel, 1, 64);
			ModConfig.INSTANCE.wanted.put(c.id, w);
		}
		return w;
	}

	/** Settings shown for an unpicked choice: what picking it would start with. */
	private static Want shownWant(Choice c) {
		Want w = ModConfig.INSTANCE.wanted.get(c.id);
		return w != null ? w : new Want(c.maxLevel, 1, 64);
	}

	private void toggle(Choice c) {
		if (picked(c)) ModConfig.INSTANCE.wanted.remove(c.id);
		else wantOf(c);
		changed();
	}

	private void changed() {
		ModConfig.save();
		Reroller.wantsChanged();
		if (tab == Tab.PICKED) filter();
		refreshChecks();
	}

	private double chance(Choice c, Want w) {
		return chance(c, w, discount);
	}

	private double chance(Choice c, Want w, int discount) {
		return Odds.perRoll(tradeable, c.minLevel, c.maxLevel, c.treasure, w.minLevel, w.minPrice, w.maxPrice, discount);
	}

	/** The chance one re-roll turns up any picked book. */
	private double combinedChance() {
		return combinedChance(discount);
	}

	private double combinedChance(int discount) {
		double sum = 0;
		for (var e : ModConfig.INSTANCE.wanted.entrySet()) {
			Choice c = find(e.getKey());
			if (c != null) sum += chance(c, e.getValue(), discount);
		}
		return sum;
	}

	/** Not known to be cured, but a cure would make this possible: the odds as if it's cured are worth showing. */
	private boolean onlyIfCured(double chanceNow, double chanceCured) {
		return discount == 0 && chanceNow <= 0 && chanceCured > 0;
	}

	private void refreshChecks() {
		checks = client == null ? new ArrayList<>() : new ArrayList<>(Reroller.preflight(client));
		var target = client == null ? null : Reroller.targetVillager(client);
		discount = Reroller.discountFor(target);
		cure = Reroller.cureOf(target);
		// Problems first, so one never hides behind "+N".
		checks.sort(Comparator.comparingInt(c -> -c.status().ordinal()));
		checksAge = 0;
	}

	private boolean ready() {
		for (Reroller.Check c : checks) if (c.status() == Reroller.Status.BAD) return false;
		return true;
	}

	// ---- Drawing ----

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);
		hits.clear();
		if (++checksAge > 20) refreshChecks();
		drawHeader(context);
		drawList(context, mouseX, mouseY);
		drawDetails(context, mouseX, mouseY);
		drawFooter(context, mouseX, mouseY);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (options) drawOptions(context, mouseX, mouseY);
		Hit hover = hitAt(mouseX, mouseY);
		if (hover != null && hover.tooltip != null) {
			List<Text> lines = hover.tooltip.get();
			if (lines != null && !lines.isEmpty()) context.drawTooltip(textRenderer, lines, mouseX, mouseY);
		}
	}

	private void drawHeader(DrawContext context) {
		TextRenderer font = textRenderer;
		context.drawItem(new ItemStack(Items.LECTERN), PAD, 4);
		context.drawTextWithShadow(font, title, PAD + 20, 8, GOLD);
		drawDayClock(context);

		// Help, far right.
		String help = "?";
		int hx1 = width - PAD, hx0 = hx1 - 14;
		chip(context, hx0, 5, hx1, 17, false);
		context.drawText(font, help, hx0 + 5, 7, MUTED, false);
		hit(hx0, 5, hx1, 17, null, () -> lines("tradeseeker.help", 10));

		// Status, left of help.
		Text status;
		int color;
		if (Reroller.running()) {
			status = Text.translatable("tradeseeker.status.running", Reroller.rolls(), clock(Reroller.elapsedSeconds()));
			color = ACCENT;
		} else if (Reroller.rolls() > 0 || !Reroller.seen().isEmpty()) {
			status = Text.translatable("tradeseeker.status.last", Reroller.rolls(), clock(Reroller.elapsedSeconds()), Reroller.bought().size());
			color = MUTED;
		} else {
			status = Text.translatable("tradeseeker.status.ready");
			color = DIM;
		}
		int sw = font.getWidth(status);
		int sx = hx0 - 6 - sw;
		if (Reroller.running()) {
			// A slow pulse, so it reads as live.
			int alpha = (int) (160 + 95 * Math.sin(System.currentTimeMillis() / 300.0));
			context.fill(sx - 8, 9, sx - 4, 13, (alpha << 24) | (ACCENT & 0xFFFFFF));
		}
		context.drawText(font, status, sx, 8, color, false);
		Text subtitle = Text.translatable("tradeseeker.subtitle");
		int subX = PAD + 24 + font.getWidth(title);
		if (subX + font.getWidth(subtitle) < sx - 14) context.drawText(font, subtitle, subX, 8, DIM, false);
	}

	/**
	 * A thin bar under the title: the villager day (work, gathering, rest...) with a mark for now. Hover it for the
	 * time, what villagers are doing, and whether this one can trade now.
	 */
	private void drawDayClock(DrawContext context) {
		if (client == null || client.world == null) return;
		int x0 = PAD, x1 = width - PAD, y = 19;
		long time = client.world.getTimeOfDay() % 24000;
		int[][] spans = {{0, 2000, 0xFF707080}, {2000, 9000, 0xFF3FA866}, {9000, 11000, 0xFFD9A53A}, {11000, 12000, 0xFF707080}, {12000, 24000, 0xFF3A3F8C}};
		for (int[] s : spans) {
			context.fill(x0 + (int) ((long) (x1 - x0) * s[0] / 24000), y, x0 + (int) ((long) (x1 - x0) * s[1] / 24000), y + 2, s[2]);
		}
		int now = x0 + (int) ((x1 - x0) * time / 24000);
		context.fill(now - 1, y - 2, now + 1, y + 4, 0xFFFFFFFF);
		hit(x0, y - 3, x1, y + 5, null, this::dayClockTip);
	}

	private List<Text> dayClockTip() {
		long time = client.world.getTimeOfDay() % 24000;
		List<Text> l = new ArrayList<>();
		l.add(Text.translatable("tradeseeker.day.now", Reroller.clock(time), Text.translatable("tradeseeker.day." + Reroller.activityAt(time))).formatted(Formatting.WHITE));
		l.add(Text.translatable("tradeseeker.day.schedule").formatted(Formatting.GRAY));
		l.add(Text.translatable("tradeseeker.day.trading").formatted(Formatting.GRAY));
		if (Reroller.nightBlocked()) l.add(Text.translatable("tradeseeker.day.blocked").formatted(Formatting.GOLD));
		return l;
	}

	private void drawList(DrawContext context, int mouseX, int mouseY) {
		TextRenderer font = textRenderer;
		panel(context, listX0, bodyY0, listX1, bodyY1);

		// Tabs.
		int tx = listX0 + 4, ty = bodyY0 + 24;
		tx = tab(context, tx, ty, Tab.ALL, Text.translatable("tradeseeker.tab.all", all.size()), mouseX, mouseY);
		tx = tab(context, tx, ty, Tab.PICKED, Text.translatable("tradeseeker.tab.picked", ModConfig.INSTANCE.wanted.size()), mouseX, mouseY);
		tab(context, tx, ty, Tab.SEEN, Text.translatable("tradeseeker.tab.seen", Reroller.seen().size()), mouseX, mouseY);

		// Rows.
		context.enableScissor(listX0 + 1, rowsY0, listX1 - 1, rowsY1);
		if (tab == Tab.SEEN) drawSeen(context, mouseX, mouseY);
		else drawChoices(context, mouseX, mouseY);
		context.disableScissor();
		drawScrollbar(context);

		// Footer line.
		context.fill(listX0 + 1, rowsY1, listX1 - 1, rowsY1 + 1, PANEL_EDGE);
		int fy = rowsY1 + 3;
		if (tab == Tab.SEEN) {
			long near = Reroller.seen().stream().filter(s -> s.kind() == Reroller.Kind.NEAR).count();
			context.drawText(font, fit(Text.translatable("tradeseeker.seen.footer", Reroller.rolls(), near), listX1 - listX0 - 8),
					listX0 + 4, fy, DIM, false);
			return;
		}
		int n = ModConfig.INSTANCE.wanted.size();
		if (n == 0) {
			Text kit = Text.translatable("tradeseeker.kit");
			int kw = font.getWidth(kit) + 8;
			boolean hover = mouseX >= listX0 + 3 && mouseX < listX0 + 3 + kw && mouseY >= fy - 2 && mouseY < fy + 10;
			chip(context, listX0 + 3, fy - 2, listX0 + 3 + kw, fy + 10, hover);
			context.drawText(font, kit, listX0 + 7, fy, GOLD, false);
			hit(listX0 + 3, fy - 2, listX0 + 3 + kw, fy + 10, this::addStarterKit, this::starterKitTip);
			return;
		}
		double p = combinedChance();
		boolean ifCured = onlyIfCured(p, combinedChance(Reroller.CURED_DISCOUNT));
		Text line = ifCured ? Text.translatable(cure == Reroller.Cure.NO ? "tradeseeker.combined.not_cured" : "tradeseeker.combined.if_cured", n)
				: p <= 0 ? Text.translatable("tradeseeker.combined.never", n)
				: Text.translatable("tradeseeker.combined", n, Odds.oneIn(p), Odds.duration(Odds.oneIn(p) * ModConfig.INSTANCE.secondsPerRoll));
		Text clear = Text.translatable("tradeseeker.clear");
		int cw = font.getWidth(clear) + 8, cx1 = listX1 - 3, cx0 = cx1 - cw;
		int room = cx0 - 6 - (listX0 + 4);
		// Too long: drop the time rather than cut a word.
		if (font.getWidth(line) > room && p > 0) line = Text.translatable("tradeseeker.combined.short", n, Odds.oneIn(p));
		context.drawText(font, fit(line, room), listX0 + 4, fy, ifCured && cure == Reroller.Cure.UNKNOWN ? WARN : p <= 0 ? BAD : MUTED, false);
		hit(listX0 + 1, fy - 2, cx0 - 2, fy + 10, null, () -> List.of(Text.translatable("tradeseeker.combined.tip")));
		boolean hover = mouseX >= cx0 && mouseX < cx1 && mouseY >= fy - 2 && mouseY < fy + 10;
		chip(context, cx0, fy - 2, cx1, fy + 10, hover);
		context.drawText(font, clear, cx0 + 4, fy, hover ? TEXT : MUTED, false);
		hit(cx0, fy - 2, cx1, fy + 10, () -> {
			ModConfig.INSTANCE.wanted.clear();
			changed();
		}, () -> List.of(Text.translatable("tradeseeker.clear.tip")));
	}

	private int tab(DrawContext context, int x, int y, Tab which, Text label, int mouseX, int mouseY) {
		int w = textRenderer.getWidth(label) + 10;
		boolean active = tab == which;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 13;
		if (hover && !active) context.fill(x, y, x + w, y + 13, HOVER);
		context.drawText(textRenderer, label, x + 5, y + 2, active ? TEXT : hover ? MUTED : DIM, false);
		if (active) context.fill(x + 3, y + 12, x + w - 3, y + 13, ACCENT);
		hit(x, y, x + w, y + 13, () -> setTab(which), null);
		return x + w + 2;
	}

	private void drawChoices(DrawContext context, int mouseX, int mouseY) {
		TextRenderer font = textRenderer;
		if (shown.isEmpty()) {
			String key = all.isEmpty() ? "tradeseeker.need_world" : tab == Tab.PICKED && search.getText().isEmpty() ? "tradeseeker.none_picked" : "tradeseeker.no_match";
			drawWrapped(context, Text.translatable(key), listX0 + 8, rowsY0 + 6, listX1 - listX0 - 16, DIM);
			return;
		}
		int y = rowsY0 - (int) scroll;
		for (Choice c : shown) {
			if (y + ROW > rowsY0 && y < rowsY1) {
				boolean on = picked(c);
				boolean hover = mouseX >= listX0 && mouseX < listX1 && mouseY >= Math.max(y, rowsY0) && mouseY < Math.min(y + ROW, rowsY1);
				if (c == focused) context.fill(listX0 + 1, y, listX1 - 1, y + ROW, FOCUS);
				else if (hover) context.fill(listX0 + 1, y, listX1 - 1, y + ROW, HOVER);
				if (on) context.fill(listX0 + 1, y, listX0 + 3, y + ROW, ACCENT);

				// Checkbox: its own click target, so you can pick without changing the selection.
				int bx = listX0 + 7, by = y + 5;
				boolean boxHover = mouseX >= bx - 3 && mouseX < bx + 11 && hover;
				context.fill(bx, by, bx + 9, by + 9, on ? ACCENT_DARK : 0x30000000);
				context.drawStrokedRectangle(bx, by, 9, 9, on ? ACCENT : boxHover ? TEXT : DIM);
				if (on) context.drawText(font, "✔", bx + 1, by + 1, 0xFFFFFFFF, false);
				context.drawItem(c.icon, listX0 + 20, y + 1);

				String right = on ? summary(c, shownWant(c)) : c.maxLevel > 1 ? roman(c.minLevel) + "–" + roman(c.maxLevel) : "";
				int rw = font.getWidth(right);
				int nameMax = listX1 - listX0 - 46 - rw - (c.treasure ? 8 : 0);
				String name = font.trimToWidth(c.name, nameMax);
				context.drawText(font, name, listX0 + 39, y + 5, on ? TEXT : MUTED, on);
				if (c.treasure) context.drawText(font, "✦", listX0 + 41 + font.getWidth(name), y + 5, PURPLE, false);
				context.drawText(font, right, listX1 - 6 - rw, y + 5, on ? GOLD : DIM, false);

				int top = Math.max(y, rowsY0), bottom = Math.min(y + ROW, rowsY1);
				hit(listX0 + 1, top, listX1 - 1, bottom, () -> focus(c), null);
				hit(bx - 3, top, bx + 12, bottom, () -> {
					focus(c);
					toggle(c);
				}, () -> List.of(Text.translatable(on ? "tradeseeker.unpick.tip" : "tradeseeker.pick.tip")));
			}
			y += ROW;
		}
	}

	private void drawSeen(DrawContext context, int mouseX, int mouseY) {
		TextRenderer font = textRenderer;
		List<Reroller.Seen> seen = Reroller.seen();
		if (seen.isEmpty()) {
			drawWrapped(context, Text.translatable("tradeseeker.seen.empty"), listX0 + 8, rowsY0 + 6, listX1 - listX0 - 16, DIM);
			return;
		}
		int y = rowsY0 - (int) scroll;
		for (Reroller.Seen s : seen) {
			if (y + ROW > rowsY0 && y < rowsY1) {
				boolean hover = mouseX >= listX0 && mouseX < listX1 && mouseY >= Math.max(y, rowsY0) && mouseY < Math.min(y + ROW, rowsY1);
				if (hover) context.fill(listX0 + 1, y, listX1 - 1, y + ROW, HOVER);
				int color = switch (s.kind()) {
					case MATCH -> ACCENT;
					case NEAR -> GOLD;
					case OTHER -> MUTED;
					case NO_BOOK -> DIM;
				};
				if (s.kind() == Reroller.Kind.MATCH || s.kind() == Reroller.Kind.NEAR) context.fill(listX0 + 1, y, listX0 + 3, y + ROW, color);
				String number = "#" + s.roll();
				context.drawText(font, number, listX0 + 7, y + 5, DIM, false);
				int x = listX0 + 9 + Math.max(22, font.getWidth(number));
				Text what = s.books().isEmpty() ? Text.translatable("tradeseeker.status.no_book") : describe(s.books());
				context.drawText(font, fit(what, listX1 - x - 6), x, y + 5, color, false);
				int top = Math.max(y, rowsY0), bottom = Math.min(y + ROW, rowsY1);
				hit(listX0 + 1, top, listX1 - 1, bottom, s.books().isEmpty() ? null : () -> {
					Choice c = find(Reroller.idOf(s.books().get(0).enchantment()));
					if (c != null) focus(c);
				}, () -> seenTip(s));
			}
			y += ROW;
		}
	}

	private List<Text> seenTip(Reroller.Seen s) {
		List<Text> lines = new ArrayList<>();
		lines.add(Text.translatable("tradeseeker.seen.roll", s.roll()).formatted(Formatting.GRAY));
		for (Reroller.Book b : s.books()) lines.add(b.describe());
		switch (s.kind()) {
			case MATCH -> lines.add(Text.translatable("tradeseeker.seen.match").formatted(Formatting.GREEN));
			case NEAR -> lines.add(Text.translatable("tradeseeker.seen.near", s.note()).formatted(Formatting.GOLD));
			default -> {}
		}
		if (!s.books().isEmpty()) lines.add(Text.translatable("tradeseeker.seen.click").formatted(Formatting.DARK_GRAY));
		return lines;
	}

	private static Text describe(List<Reroller.Book> books) {
		var text = Text.empty();
		for (int i = 0; i < books.size(); i++) {
			if (i > 0) text.append(", ");
			Reroller.Book b = books.get(i);
			text.append(Enchantment.getName(b.enchantment(), b.level())).append(" · " + b.price());
		}
		return text;
	}

	private void drawScrollbar(DrawContext context) {
		int content = rowCount() * ROW, view = rowsY1 - rowsY0;
		if (content <= view) return;
		int barH = Math.max(12, view * view / content);
		int barY = rowsY0 + (int) ((view - barH) * (scroll / (content - view)));
		context.fill(listX1 - 4, barY, listX1 - 2, barY + barH, 0x80FFFFFF);
	}

	/**
	 * The selected book, packed to fit the smallest GUI (427×240): title with the wanted switch, level row, price row,
	 * slider, presets beside the price band, then the odds.
	 */
	private void drawDetails(DrawContext context, int mouseX, int mouseY) {
		TextRenderer font = textRenderer;
		panel(context, detX0, bodyY0, detX1, bodyY1);
		int x0 = detX0 + 9, x1 = detX1 - 9;
		if (focused == null) {
			drawWrapped(context, Text.translatable("tradeseeker.need_world"), x0, bodyY0 + 10, x1 - x0, DIM);
			return;
		}
		Choice c = focused;
		boolean on = picked(c);
		Want w = shownWant(c);
		int y = bodyY0 + 7;
		context.enableScissor(detX0 + 1, bodyY0 + 1, detX1 - 1, bodyY1 - 1);

		// Title row: the book, its name a size up, a treasure mark, and the wanted switch on the right.
		Text wantedLabel = Text.translatable("tradeseeker.wanted");
		int switchW = 22 + font.getWidth(wantedLabel);
		toggleRow(context, x1 - switchW, y + 4, wantedLabel, on, true, mouseX, mouseY, () -> toggle(c),
				() -> List.of(Text.translatable(on ? "tradeseeker.unpick.tip" : "tradeseeker.pick.tip"), Text.translatable("tradeseeker.look_for.tip").formatted(Formatting.GRAY)));
		context.drawItem(c.icon, x0, y);
		int nameRoom = (int) ((x1 - switchW - 8 - (x0 + 21) - (c.treasure ? 10 : 0)) / 1.25f);
		String name = font.trimToWidth(c.name, Math.max(20, nameRoom));
		Matrix3x2fStack m = context.getMatrices();
		m.pushMatrix();
		m.translate(x0 + 21, y + 4);
		m.scale(1.25f, 1.25f);
		context.drawText(font, name, 0, 0, on ? TEXT : MUTED, true);
		m.popMatrix();
		if (c.treasure) {
			int tx = x0 + 23 + (int) (font.getWidth(name) * 1.25f);
			context.drawText(font, "✦", tx, y + 5, PURPLE, false);
			hit(tx - 1, y + 3, tx + 8, y + 14, null, () -> List.of(Text.translatable("tradeseeker.treasure").formatted(Formatting.LIGHT_PURPLE),
					Text.translatable("tradeseeker.treasure.tip").formatted(Formatting.GRAY)));
		}
		y += 22;

		// Level row: label, then a segment per level; the chosen one and those above it are lit.
		Text levelLabel = Text.translatable("tradeseeker.lowest_level");
		context.drawText(font, levelLabel, x0, y + 4, on ? TEXT : MUTED, false);
		int sx = x0 + font.getWidth(levelLabel) + 8;
		if (c.maxLevel <= 1) {
			segment(context, sx, y, 22, roman(1), true, true, on, false, mouseX, mouseY, null, null);
			context.drawText(font, Text.translatable("tradeseeker.one_level"), sx + 28, y + 4, DIM, false);
		} else {
			int segW = MathHelper.clamp((x1 - sx) / (c.maxLevel - c.minLevel + 1) - 2, 18, 28);
			for (int level = c.minLevel; level <= c.maxLevel; level++) {
				int lv = level;
				segment(context, sx + (level - c.minLevel) * (segW + 2), y, segW, roman(level), level >= w.minLevel, level == w.minLevel, on, true,
						mouseX, mouseY, () -> {
							wantOf(c).minLevel = lv;
							changed();
						}, () -> List.of(Text.translatable(lv == c.maxLevel ? "tradeseeker.level.only_tip" : "tradeseeker.level.tip", roman(lv))));
			}
		}
		y += 22;

		// Price row and slider.
		int bandLow = Odds.lowestPrice(w.minLevel, c.treasure, discount), bandHigh = Odds.highestPrice(c.maxLevel, c.treasure, discount);
		context.drawText(font, Text.translatable("tradeseeker.price"), x0, y, on ? TEXT : MUTED, false);
		Text value = w.minPrice == 1 && w.maxPrice == 64 ? Text.translatable("tradeseeker.price.any") : Text.translatable("tradeseeker.price.range", w.minPrice, w.maxPrice);
		int vw = font.getWidth(value);
		context.drawText(font, value, x1 - vw, y, on ? GOLD : DIM, false);
		context.drawItem(new ItemStack(Items.EMERALD), x1 - vw - 17, y - 4);
		y += 13;
		// Not known to be cured: a faint band for what a villager you cured asks, as well.
		int curedLow = discount > 0 ? -1 : Odds.lowestPrice(w.minLevel, c.treasure, Reroller.CURED_DISCOUNT);
		drawSlider(context, x0, y, x1, w, bandLow, bandHigh, curedLow, on, mouseX, mouseY);
		y += 14;

		// Presets on the left, what villagers ask on the right.
		int mid = (bandLow + bandHigh) / 2, cheap = bandLow + Math.max(1, (bandHigh - bandLow) / 4);
		int px = x0;
		px = preset(context, px, y, Text.translatable("tradeseeker.preset.any"), w.minPrice == 1 && w.maxPrice == 64, mouseX, mouseY,
				() -> setPrice(c, 1, 64), "tradeseeker.preset.any.tip");
		px = preset(context, px, y, Text.translatable("tradeseeker.preset.half", mid), w.minPrice == 1 && w.maxPrice == mid, mouseX, mouseY,
				() -> setPrice(c, 1, mid), "tradeseeker.preset.half.tip");
		px = preset(context, px, y, Text.translatable("tradeseeker.preset.bargain", cheap), w.minPrice == 1 && w.maxPrice == cheap, mouseX, mouseY,
				() -> setPrice(c, 1, cheap), "tradeseeker.preset.bargain.tip");
		Text band = Text.translatable(discount > 0 ? "tradeseeker.band.cured" : "tradeseeker.band", bandLow, bandHigh);
		int bw = font.getWidth(band);
		if (px + 4 + bw <= x1) {
			context.drawText(font, band, x1 - bw, y + 3, 0xFFB89640, false);
			int curedFrom = Odds.lowestPrice(w.minLevel, c.treasure, Reroller.CURED_DISCOUNT), curedHigh = Odds.highestPrice(c.maxLevel, c.treasure, Reroller.CURED_DISCOUNT);
			hit(x1 - bw, y, x1, y + 13, null, () -> discount > 0
					? List.of(Text.translatable("tradeseeker.band.tip", roman(w.minLevel), roman(c.maxLevel), bandLow, bandHigh), Text.translatable("tradeseeker.band.cured.tip", discount))
					: List.of(Text.translatable("tradeseeker.band.tip", roman(w.minLevel), roman(c.maxLevel), bandLow, bandHigh),
							Text.translatable("tradeseeker.band.if_cured.tip", curedFrom, curedHigh).formatted(Formatting.GRAY)));
		}
		y += 19;

		drawOdds(context, x0, y, x1, c, w, on);
		context.disableScissor();
	}

	private void setPrice(Choice c, int min, int max) {
		Want w = wantOf(c);
		w.minPrice = min;
		w.maxPrice = max;
		changed();
	}

	private void drawSlider(DrawContext context, int x0, int y, int x1, Want w, int bandLow, int bandHigh, int curedLow, boolean on, int mouseX, int mouseY) {
		sliderX0 = x0 + 3;
		sliderX1 = x1 - 3;
		int cy = y + 3;
		// Where villagers' prices fall, under the track; with a cure they start lower (curedLow, or -1).
		if (curedLow > 0 && curedLow < bandLow) context.fill(px(curedLow) - 1, cy - 2, px(bandLow) - 1, cy + 3, CURED_BAND);
		context.fill(px(bandLow) - 1, cy - 4, px(bandHigh) + 1, cy + 5, BAND);
		context.fill(sliderX0, cy, sliderX1, cy + 2, TRACK);
		context.fill(px(w.minPrice), cy, px(w.maxPrice), cy + 2, on ? ACCENT : DIM);
		boolean hoverLow = drag == Drag.LOW || near(mouseX, mouseY, px(w.minPrice), cy, w);
		boolean hoverHigh = drag == Drag.HIGH || !hoverLow && near(mouseX, mouseY, px(w.maxPrice), cy, w);
		handle(context, px(w.minPrice), cy, hoverLow, on);
		handle(context, px(w.maxPrice), cy, hoverHigh, on);
		hit(sliderX0 - 4, cy - 6, sliderX1 + 4, cy + 8, null, () -> curedLow > 0 && curedLow < bandLow
				? List.of(Text.translatable("tradeseeker.slider.tip"), Text.translatable("tradeseeker.slider.cured_tip").formatted(Formatting.GRAY))
				: List.of(Text.translatable("tradeseeker.slider.tip")));
	}

	private boolean near(int mouseX, int mouseY, int hx, int cy, Want w) {
		return Math.abs(mouseX - hx) <= 4 && Math.abs(mouseY - cy) <= 6;
	}

	private void handle(DrawContext context, int x, int cy, boolean hover, boolean on) {
		context.fill(x - 2, cy - 5, x + 3, cy + 7, 0xFF000000);
		context.fill(x - 1, cy - 4, x + 2, cy + 6, hover ? ACCENT : on ? TEXT : MUTED);
	}

	private int px(int price) {
		return sliderX0 + (int) Math.round((price - 1) / 63.0 * (sliderX1 - sliderX0));
	}

	private int priceAt(double x) {
		return MathHelper.clamp((int) Math.round(1 + (x - sliderX0) / (double) (sliderX1 - sliderX0) * 63), 1, 64);
	}

	/** Two lines: "1 in N re-rolls · about T", then how long to be 90% sure. */
	private void drawOdds(DrawContext context, int x0, int y, int x1, Choice c, Want w, boolean on) {
		TextRenderer font = textRenderer;
		int y1 = Math.min(bodyY1 - 5, y + 32);
		context.fill(x0 - 2, y, x1 + 2, y1, 0x40000000);
		context.drawStrokedRectangle(x0 - 2, y, x1 - x0 + 4, y1 - y, PANEL_EDGE);
		hit(x0 - 2, y, x1 + 2, y1, null, () -> lines("tradeseeker.odds.tip", 3, ModConfig.INSTANCE.secondsPerRoll));
		double p = chance(c, w);
		int tx = x0 + 4, ty = y + 5;
		if (p <= 0) {
			// Why not: too cheap for an uncured villager (and whether this one is cured isn't known, or it isn't), or
			// too cheap for anyone.
			double cured = chance(c, w, Reroller.CURED_DISCOUNT);
			String key = !onlyIfCured(p, cured) ? (discount > 0 ? "tradeseeker.odds.never_cured" : "tradeseeker.odds.never")
					: cure == Reroller.Cure.NO ? "tradeseeker.odds.not_cured" : "tradeseeker.odds.if_cured";
			Text why = key.equals("tradeseeker.odds.if_cured") ? Text.translatable(key, Odds.oneIn(cured)) : Text.translatable(key);
			drawWrapped(context, why, tx, ty - 1, x1 - x0 - 8, key.equals("tradeseeker.odds.if_cured") ? WARN : BAD);
			return;
		}
		long n = Odds.oneIn(p);
		double spr = ModConfig.INSTANCE.secondsPerRoll;
		Text big = Text.translatable("tradeseeker.odds.one_in", n);
		Matrix3x2fStack m = context.getMatrices();
		m.pushMatrix();
		m.translate(tx, ty);
		m.scale(1.25f, 1.25f);
		context.drawText(font, big, 0, 0, on ? ACCENT : MUTED, true);
		m.popMatrix();
		int after = tx + (int) (font.getWidth(big) * 1.25f) + 4;
		Text rest = Text.translatable(on ? "tradeseeker.odds.rerolls" : "tradeseeker.odds.rerolls_if", Odds.duration(n * spr));
		context.drawText(font, fit(rest, x1 - after - 2), after, ty + 2, MUTED, false);
		Text likely = Text.translatable("tradeseeker.odds.likely", Odds.likely(p), Odds.duration(Odds.likely(p) * spr));
		context.drawText(font, fit(likely, x1 - x0 - 8), tx, ty + 14, DIM, false);
	}

	private void drawFooter(DrawContext context, int mouseX, int mouseY) {
		ModConfig config = ModConfig.INSTANCE;
		TextRenderer font = textRenderer;
		// Buying.
		int y = height - 58;
		int x = PAD + 2;
		x = toggleRow(context, x, y, Text.translatable("tradeseeker.autobuy"), config.autoBuy, true, mouseX, mouseY, () -> {
			config.autoBuy = !config.autoBuy;
			changed();
		}, () -> lines("tradeseeker.autobuy.tip", 3)) + 14;
		toggleRow(context, x, y, Text.translatable("tradeseeker.buymax"), config.buyMax, config.autoBuy, mouseX, mouseY, () -> {
			config.buyMax = !config.buyMax;
			changed();
		}, () -> config.autoBuy ? lines("tradeseeker.buymax.tip", 2) : List.of(Text.translatable("tradeseeker.buymax.needs_autobuy")));
		Text opts = Text.translatable("tradeseeker.options");
		int ow = font.getWidth(opts) + 12, ox1 = width - PAD, ox0 = ox1 - ow;
		boolean optHover = !options && mouseX >= ox0 && mouseX < ox1 && mouseY >= y - 2 && mouseY < y + 12;
		chip(context, ox0, y - 2, ox1, y + 12, optHover);
		context.drawText(font, opts, ox0 + 6, y + 1, optHover ? TEXT : MUTED, false);
		hit(ox0, y - 2, ox1, y + 12, () -> options = true, () -> List.of(Text.translatable("tradeseeker.options.tip")));

		// Checklist.
		y = height - 40;
		x = PAD + 2;
		int limit = width - PAD;
		for (int i = 0; i < checks.size(); i++) {
			Reroller.Check check = checks.get(i);
			String mark = switch (check.status()) {
				case OK -> "✔ ";
				case WARN -> "⚠ ";
				case BAD -> "✖ ";
			};
			int color = switch (check.status()) {
				case OK -> ACCENT;
				case WARN -> WARN;
				case BAD -> BAD;
			};
			Text label = Text.literal(mark).append(check.label());
			int w = font.getWidth(label);
			if (x + w > limit) {
				// No room: say how many more, with all of them in the tooltip.
				Text more = Text.literal("+" + (checks.size() - i));
				context.drawText(font, more, x, y, MUTED, false);
				List<Reroller.Check> rest = checks.subList(i, checks.size());
				hit(x, y - 2, x + font.getWidth(more), y + 10, null, () -> {
					List<Text> l = new ArrayList<>();
					for (Reroller.Check r : rest) l.add(r.label().copy().formatted(r.status() == Reroller.Status.OK ? Formatting.GREEN
							: r.status() == Reroller.Status.WARN ? Formatting.YELLOW : Formatting.RED));
					return l;
				});
				break;
			}
			context.drawText(font, label, x, y, color, false);
			hit(x, y - 2, x + w, y + 10, null, () -> List.of(check.label().copy().formatted(Formatting.WHITE), check.detail().copy().formatted(Formatting.GRAY)));
			x += w + 10;
		}

		// Buttons.
		y = height - 24;
		int bw = Math.min(150, (width - 2 * PAD - 6) / 2);
		int sx0 = width / 2 - bw - 3, dx0 = width / 2 + 3;
		boolean running = Reroller.running();
		boolean canStart = running || ready();
		Text startLabel = running ? Text.translatable("tradeseeker.stop") : Text.translatable("tradeseeker.start");
		button(context, sx0, y, bw, startLabel, running ? BAD : canStart ? ACCENT : DIM, canStart, true, mouseX, mouseY, this::startOrStop, () -> {
			if (running) return List.of(Text.translatable("tradeseeker.stop.tip"));
			if (canStart) return List.of(Text.translatable("tradeseeker.start.tip"));
			List<Text> l = new ArrayList<>();
			l.add(Text.translatable("tradeseeker.start.blocked").formatted(Formatting.RED));
			for (Reroller.Check ch : checks) if (ch.status() == Reroller.Status.BAD) l.add(ch.detail().copy().formatted(Formatting.GRAY));
			return l;
		});
		button(context, dx0, y, bw, Text.translatable("gui.done"), MUTED, true, false, mouseX, mouseY, this::close, null);
	}

	private void startOrStop() {
		if (Reroller.running()) {
			Reroller.stop(client, Text.translatable("tradeseeker.msg.stopped", Reroller.rolls()).formatted(Formatting.YELLOW));
			return;
		}
		if (!ready()) return;
		close();
		Reroller.start(client);
	}

	private void addStarterKit() {
		for (var e : STARTER_KIT.entrySet()) {
			Choice c = find(e.getKey());
			if (c != null && !picked(c)) ModConfig.INSTANCE.wanted.put(c.id, new Want(Math.min(e.getValue(), c.maxLevel), 1, 64));
		}
		changed();
	}

	private List<Text> starterKitTip() {
		List<Text> l = new ArrayList<>();
		l.add(Text.translatable("tradeseeker.kit.tip").formatted(Formatting.GRAY));
		for (var e : STARTER_KIT.entrySet()) {
			Choice c = find(e.getKey());
			if (c != null) l.add(Enchantment.getName(c.entry, Math.min(e.getValue(), c.maxLevel)));
		}
		return l;
	}

	/** What the labels over villagers' heads show, and the trades it remembers for them. */
	private void drawLabelOptions(DrawContext context, int x, int y, int x1, int mouseX, int mouseY) {
		ModConfig config = ModConfig.INSTANCE;
		TextRenderer font = textRenderer;
		context.drawText(font, Text.translatable("tradeseeker.opt.labels"), x, y + 4, TEXT, false);
		y += 16;
		TradeMemory.Labels[] modes = TradeMemory.Labels.values();
		int segW = (x1 - 10 - x - 2 * (modes.length - 1)) / modes.length, segX = x;
		for (TradeMemory.Labels mode : modes) {
			String key = "tradeseeker.labels." + mode.name().toLowerCase(Locale.ROOT);
			segment(context, segX, y, segW, Text.translatable(key).getString(), config.labels == mode, config.labels == mode, true, true, mouseX, mouseY,
					() -> {
						config.labels = mode;
						ModConfig.save();
					}, () -> List.of(Text.translatable(key + ".tip")));
			segX += segW + 2;
		}
		y += 22;
		context.drawText(font, Text.translatable("tradeseeker.opt.label_range"), x, y + 4, TEXT, false);
		int rx = x1 - 10 - TradeMemory.RANGES.length * 36 + 2;
		for (int range : TradeMemory.RANGES) {
			segment(context, rx, y, 34, range + "", config.labelRange == range, config.labelRange == range, config.labels != TradeMemory.Labels.OFF, true,
					mouseX, mouseY, () -> {
						config.labelRange = range;
						ModConfig.save();
					}, () -> List.of(Text.translatable("tradeseeker.opt.label_range.tip", range)));
			rx += 36;
		}
		y += 22;
		y = drawWrapped(context, Text.translatable("tradeseeker.labels.about"), x, y, x1 - 10 - x, DIM) + 6;
		Text known = Text.translatable("tradeseeker.labels.known", TradeMemory.count());
		context.drawText(font, known, x, y + 2, MUTED, false);
		Text forget = Text.translatable("tradeseeker.labels.forget");
		int fw = font.getWidth(forget) + 10, fx1 = x1 - 10, fx0 = fx1 - fw;
		boolean hover = mouseX >= fx0 && mouseX < fx1 && mouseY >= y - 1 && mouseY < y + 12;
		chip(context, fx0, y - 1, fx1, y + 12, hover);
		context.drawText(font, forget, fx0 + 5, y + 2, hover ? TEXT : MUTED, false);
		hit(fx0, y - 1, fx1, y + 12, TradeMemory::forgetAll, () -> List.of(Text.translatable("tradeseeker.labels.forget.tip")));
	}

	// ---- Options (a small panel over the screen) ----

	/** Options, in two tabs so the panel fits the smallest GUI (240 high). */
	private void drawOptions(DrawContext context, int mouseX, int mouseY) {
		ModConfig config = ModConfig.INSTANCE;
		TextRenderer font = textRenderer;
		hits.clear();
		context.fill(0, 0, width, height, 0x88000000);
		int w = Math.min(260, width - 20), h = 222;
		int x0 = (width - w) / 2, y0 = (height - h) / 2, x1 = x0 + w;
		// Clicks outside close it.
		hit(0, 0, width, height, () -> options = false, null);
		context.fill(x0, y0, x1, y0 + h, 0xFF15151C);
		context.drawStrokedRectangle(x0, y0, w, h, 0xFF4A4A5A);
		context.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 18, HEADER);
		hit(x0, y0, x1, y0 + h, () -> {}, null);
		context.drawTextWithShadow(font, Text.translatable("tradeseeker.options.title"), x0 + 8, y0 + 6, GOLD);

		// Tabs.
		int tx = x0 + 6, ty = y0 + 22;
		String[] optionTabs = {"tradeseeker.options.behaviour", "tradeseeker.options.sounds", "tradeseeker.options.labels"};
		for (int i = 0; i < optionTabs.length; i++) {
			int which = i;
			Text label = Text.translatable(optionTabs[i]);
			int tw = font.getWidth(label) + 10;
			boolean active = optionsTab == i;
			boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= ty && mouseY < ty + 13;
			if (hover && !active) context.fill(tx, ty, tx + tw, ty + 13, HOVER);
			context.drawText(font, label, tx + 5, ty + 2, active ? TEXT : hover ? MUTED : DIM, false);
			if (active) context.fill(tx + 3, ty + 12, tx + tw - 3, ty + 13, ACCENT);
			hit(tx, ty, tx + tw, ty + 13, () -> optionsTab = which, null);
			tx += tw + 2;
		}
		context.fill(x0 + 1, ty + 14, x1 - 1, ty + 15, PANEL_EDGE);
		int x = x0 + 10, y = ty + 21;

		if (optionsTab == 0) {
			context.drawText(font, Text.translatable("tradeseeker.speed"), x, y + 4, TEXT, false);
			int sx = x1 - 10 - 3 * 52;
			for (ModConfig.Speed speed : ModConfig.Speed.values()) {
				String key = "tradeseeker.speed." + speed.name().toLowerCase(Locale.ROOT);
				segment(context, sx, y, 50, Text.translatable(key).getString(), config.speed == speed, config.speed == speed, true, true, mouseX, mouseY,
						() -> {
							config.speed = speed;
							ModConfig.save();
						}, () -> List.of(Text.translatable(key + ".tip")));
				sx += 52;
			}
			y += 21;
			context.drawText(font, Text.translatable("tradeseeker.opt.job_wait"), x, y + 4, TEXT, false);
			Text wait = config.jobWait == 0 ? Text.translatable("tradeseeker.opt.job_wait.auto", seconds(Reroller.jobWait()))
					: Text.literal(seconds(config.jobWait) + " s");
			// Auto, then 0.5 s to 30 s in half seconds.
			stepper(context, x1 - 10 - 96, y, x1 - 10, wait, mouseX, mouseY, step -> {
				config.jobWait = config.jobWait == 0 ? (step > 0 ? 0.5 : 0) : config.jobWait + (step > 0 ? 0.5 : -0.5);
				config.jobWait = config.jobWait < 0.5 ? 0 : Math.min(30, config.jobWait);
				ModConfig.save();
			}, "tradeseeker.opt.job_wait.tip");
			y += 20;
			context.drawText(font, Text.translatable("tradeseeker.opt.limit"), x, y + 4, TEXT, false);
			Text limit = config.maxRolls == 0 ? Text.translatable("tradeseeker.opt.limit.never") : Text.literal(Integer.toString(config.maxRolls));
			stepper(context, x1 - 10 - 96, y, x1 - 10, limit, mouseX, mouseY, step -> {
				config.maxRolls = Math.max(0, config.maxRolls + step);
				ModConfig.save();
			}, "tradeseeker.opt.limit.tip");
			y += 22;
			toggleRow(context, x, y, Text.translatable("tradeseeker.opt.untick"), config.untickBought, true, mouseX, mouseY, () -> {
				config.untickBought = !config.untickBought;
				ModConfig.save();
			}, () -> List.of(Text.translatable("tradeseeker.opt.untick.tip")));
			y += 16;
			toggleRow(context, x, y, Text.translatable("tradeseeker.opt.hurt"), config.stopWhenHurt, true, mouseX, mouseY, () -> {
				config.stopWhenHurt = !config.stopWhenHurt;
				ModConfig.save();
			}, () -> List.of(Text.translatable("tradeseeker.opt.hurt.tip")));
			y += 16;
			toggleRow(context, x, y, Text.translatable("tradeseeker.opt.face"), config.faceTargets, true, mouseX, mouseY, () -> {
				config.faceTargets = !config.faceTargets;
				ModConfig.save();
			}, () -> List.of(Text.translatable("tradeseeker.opt.face.tip")));
			y += 16;
			toggleRow(context, x, y, Text.translatable("tradeseeker.opt.near"), config.nearMisses, true, mouseX, mouseY, () -> {
				config.nearMisses = !config.nearMisses;
				ModConfig.save();
			}, () -> List.of(Text.translatable("tradeseeker.opt.near.tip")));
			y += 16;
			toggleRow(context, x, y, Text.translatable("tradeseeker.opt.protect"), config.protectVillagers, true, mouseX, mouseY, () -> {
				config.protectVillagers = !config.protectVillagers;
				ModConfig.save();
			}, () -> lines("tradeseeker.opt.protect.tip", 2));
		} else if (optionsTab == 2) {
			drawLabelOptions(context, x, y, x1, mouseX, mouseY);
		} else {
			// The find sound: click one to hear it.
			context.drawText(font, Text.translatable("tradeseeker.opt.sound"), x, y + 4, TEXT, false);
			Sounds.Style[] styles = Sounds.Style.values();
			int[] widths = new int[styles.length];
			int total = 2 * (styles.length - 1);
			for (int i = 0; i < styles.length; i++) {
				widths[i] = font.getWidth(Text.translatable("tradeseeker.sound." + styles[i].name().toLowerCase(Locale.ROOT))) + 10;
				total += widths[i];
			}
			int segX = x1 - 10 - total;
			for (int i = 0; i < styles.length; i++) {
				Sounds.Style style = styles[i];
				String key = "tradeseeker.sound." + style.name().toLowerCase(Locale.ROOT);
				segment(context, segX, y, widths[i], Text.translatable(key).getString(), config.foundSound == style, config.foundSound == style, true, true,
						mouseX, mouseY, () -> {
							config.foundSound = style;
							ModConfig.save();
							Sounds.play(style, 1f);
						}, () -> List.of(Text.translatable("tradeseeker.sound.tip")));
				segX += widths[i] + 2;
			}
			y += 21;
			context.drawText(font, Text.translatable("tradeseeker.opt.volume"), x, y + 4, TEXT, false);
			Text vol = config.volume == 0 ? Text.translatable("options.off") : Text.literal(config.volume + "%");
			stepper(context, x1 - 10 - 84, y, x1 - 10, vol, mouseX, mouseY, step -> {
				config.volume = MathHelper.clamp(config.volume + (step > 0 ? 10 : -10), 0, 100);
				ModConfig.save();
				Sounds.play(config.foundSound, 1f);
			}, "tradeseeker.opt.volume.tip");
			y += 22;
			toggleRow(context, x, y, Text.translatable("tradeseeker.opt.remind"), config.remind, true, mouseX, mouseY, () -> {
				config.remind = !config.remind;
				ModConfig.save();
			}, () -> List.of(Text.translatable("tradeseeker.opt.remind.tip")));
			y += 18;
			drawWrapped(context, Text.translatable("tradeseeker.sound.about"), x, y, x1 - x0 - 20, DIM);
		}
		button(context, x1 - 70, y0 + h - 24, 60, Text.translatable("gui.done"), MUTED, true, false, mouseX, mouseY, () -> options = false, null);
		Hit hover = hitAt(mouseX, mouseY);
		if (hover != null && hover.tooltip != null) {
			List<Text> lines = hover.tooltip.get();
			if (lines != null && !lines.isEmpty()) context.drawTooltip(font, lines, mouseX, mouseY);
		}
	}

	private interface Step {
		void by(int amount);
	}

	private void stepper(DrawContext context, int x0, int y, int x1, Text value, int mouseX, int mouseY, Step step, String tipKey) {
		int h = 16;
		boolean minus = mouseX >= x0 && mouseX < x0 + 16 && mouseY >= y && mouseY < y + h;
		boolean plus = mouseX >= x1 - 16 && mouseX < x1 && mouseY >= y && mouseY < y + h;
		chip(context, x0, y, x0 + 16, y + h, minus);
		chip(context, x1 - 16, y, x1, y + h, plus);
		context.drawText(textRenderer, "−", x0 + 5, y + 4, minus ? TEXT : MUTED, false);
		context.drawText(textRenderer, "+", x1 - 11, y + 4, plus ? TEXT : MUTED, false);
		int vw = textRenderer.getWidth(value);
		context.drawText(textRenderer, value, (x0 + x1 - vw) / 2, y + 4, GOLD, false);
		Supplier<List<Text>> tip = () -> List.of(Text.translatable(tipKey));
		hit(x0, y, x0 + 16, y + h, () -> step.by(hasShiftDown() ? -50 : -10), tip);
		hit(x1 - 16, y, x1, y + h, () -> step.by(hasShiftDown() ? 50 : 10), tip);
	}

	private static String seconds(double s) {
		return s % 1 == 0 ? Integer.toString((int) s) : Double.toString(s);
	}

	private static boolean hasShiftDown() {
		long window = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
		return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
	}

	// ---- Small parts ----

	private void panel(DrawContext context, int x0, int y0, int x1, int y1) {
		context.fill(x0, y0, x1, y1, PANEL);
		context.drawStrokedRectangle(x0, y0, x1 - x0, y1 - y0, PANEL_EDGE);
	}

	private void chip(DrawContext context, int x0, int y0, int x1, int y1, boolean hover) {
		context.fill(x0, y0, x1, y1, hover ? 0x40FFFFFF : 0x24FFFFFF);
		context.drawStrokedRectangle(x0, y0, x1 - x0, y1 - y0, hover ? 0x80FFFFFF : PANEL_EDGE);
	}

	/** A switch and its label; returns the x where it ends. */
	private int toggleRow(DrawContext context, int x, int y, Text label, boolean on, boolean enabled, int mouseX, int mouseY, Runnable click,
			Supplier<List<Text>> tip) {
		int w = 22 + textRenderer.getWidth(label);
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y - 2 && mouseY < y + 12;
		int track = !enabled ? 0xFF2A2A32 : on ? ACCENT_DARK : TRACK;
		context.fill(x, y, x + 16, y + 9, track);
		context.drawStrokedRectangle(x, y, 16, 9, hover ? 0xFFFFFFFF : enabled && on ? ACCENT : 0xFF4A4A58);
		int knob = on ? x + 9 : x + 2;
		context.fill(knob, y + 2, knob + 5, y + 7, !enabled ? DIM : on ? ACCENT : MUTED);
		context.drawText(textRenderer, label, x + 21, y + 1, !enabled ? DIM : hover ? 0xFFFFFFFF : TEXT, false);
		hit(x, y - 2, x + w, y + 12, enabled ? click : null, tip);
		return x + w;
	}

	/** One part of a segmented control: lit (in range), selected (the chosen one). */
	private void segment(DrawContext context, int x, int y, int w, String label, boolean lit, boolean selected, boolean on, boolean enabled,
			int mouseX, int mouseY, Runnable click, Supplier<List<Text>> tip) {
		int h = 16;
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int bg = selected ? (on ? ACCENT_DARK : 0xFF3A3A46) : lit ? (on ? 0xFF1A3A2A : 0xFF2C2C36) : 0x24FFFFFF;
		context.fill(x, y, x + w, y + h, bg);
		context.drawStrokedRectangle(x, y, w, h, hover ? 0xFFFFFFFF : selected ? (on ? ACCENT : MUTED) : PANEL_EDGE);
		int tw = textRenderer.getWidth(label);
		context.drawText(textRenderer, label, x + (w - tw) / 2, y + 4, selected ? 0xFFFFFFFF : lit ? (on ? ACCENT : TEXT) : DIM, false);
		if (click != null || tip != null) hit(x, y, x + w, y + h, click, tip);
	}

	private int preset(DrawContext context, int x, int y, Text label, boolean active, int mouseX, int mouseY, Runnable click, String tipKey) {
		int w = textRenderer.getWidth(label) + 10;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 13;
		context.fill(x, y, x + w, y + 13, active ? ACCENT_DARK : hover ? 0x40FFFFFF : 0x20FFFFFF);
		context.drawStrokedRectangle(x, y, w, 13, active ? ACCENT : hover ? 0x80FFFFFF : PANEL_EDGE);
		context.drawText(textRenderer, label, x + 5, y + 3, active ? 0xFFFFFFFF : hover ? TEXT : MUTED, false);
		hit(x, y, x + w, y + 13, click, () -> List.of(Text.translatable(tipKey)));
		return x + w + 4;
	}

	private void button(DrawContext context, int x, int y, int w, Text label, int color, boolean enabled, boolean primary, int mouseX, int mouseY,
			Runnable click, Supplier<List<Text>> tip) {
		int h = 18;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int base = color & 0xFFFFFF;
		int bg = !enabled ? 0x30FFFFFF : primary ? ((hover ? 0xD0 : 0x90) << 24) | darken(base) : hover ? 0x50FFFFFF : 0x30FFFFFF;
		context.fill(x, y, x + w, y + h, bg);
		context.drawStrokedRectangle(x, y, w, h, !enabled ? PANEL_EDGE : hover ? 0xFFFFFFFF : primary ? color : 0xFF5A5A68);
		int tw = textRenderer.getWidth(label);
		context.drawText(textRenderer, label, x + (w - tw) / 2, y + 5, !enabled ? DIM : 0xFFFFFFFF, true);
		hit(x, y, x + w, y + h, enabled ? click : null, tip);
	}

	private static int darken(int rgb) {
		int r = (rgb >> 16 & 0xFF) * 2 / 5, g = (rgb >> 8 & 0xFF) * 2 / 5, b = (rgb & 0xFF) * 2 / 5;
		return r << 16 | g << 8 | b;
	}

	/** Returns the y below the last line. */
	private int drawWrapped(DrawContext context, Text text, int x, int y, int width, int color) {
		for (var line : textRenderer.wrapLines(text, width)) {
			context.drawText(textRenderer, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	/** Text cut to fit a width (formatting comes from the colour it's drawn in). */
	private String fit(Text text, int width) {
		return textRenderer.trimToWidth(text.getString(), width);
	}

	private void hit(int x0, int y0, int x1, int y1, Runnable click, Supplier<List<Text>> tooltip) {
		hits.add(new Hit(x0, y0, x1, y1, click, tooltip));
	}

	private Hit hitAt(double x, double y) {
		for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) return hits.get(i);
		return null;
	}

	/** Lines "key.1", "key.2", ... of a translated tooltip. */
	private static List<Text> lines(String key, int count, Object... args) {
		List<Text> l = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			Text line = Text.translatable(key + "." + i, args);
			if (line.getString().equals(key + "." + i)) break;
			l.add(i == 1 ? line.copy().formatted(Formatting.WHITE) : line.copy().formatted(Formatting.GRAY));
		}
		return l;
	}

	private String summary(Choice c, Want w) {
		String level = c.maxLevel <= 1 ? "" : w.minLevel == c.maxLevel ? roman(w.minLevel) : roman(w.minLevel) + "+";
		String price = w.minPrice == 1 && w.maxPrice == 64 ? "" : w.minPrice == 1 ? "≤" + w.maxPrice : w.minPrice + "–" + w.maxPrice;
		if (level.isEmpty()) return price.isEmpty() ? Text.translatable("tradeseeker.price.any_short").getString() : price;
		return price.isEmpty() ? level : level + " · " + price;
	}

	private static String clock(long seconds) {
		return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
				: String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
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

	private int rowCount() {
		return tab == Tab.SEEN ? Reroller.seen().size() : shown.size();
	}

	private double clampScroll(double value) {
		int max = Math.max(0, rowCount() * ROW - (rowsY1 - rowsY0));
		return Math.max(0, Math.min(max, value));
	}

	private void scrollTo(Choice c) {
		int index = shown.indexOf(c);
		if (index < 0) return;
		int top = index * ROW, view = rowsY1 - rowsY0;
		if (top < scroll) scroll = top;
		else if (top + ROW > scroll + view) scroll = top + ROW - view;
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mx = click.x(), my = click.y();
		if (options) {
			Hit hit = hitAt(mx, my);
			if (hit != null && hit.click != null) hit.click.run();
			return true;
		}
		if (super.mouseClicked(click, doubled)) return true;
		// The price slider: grab the nearer handle, or move it to where you clicked.
		if (focused != null && click.button() == 0 && mx >= sliderX0 - 4 && mx <= sliderX1 + 4 && sliderHit(my)) {
			Want w = wantOf(focused);
			int price = priceAt(mx);
			drag = Math.abs(price - w.minPrice) < Math.abs(price - w.maxPrice) || (price < w.minPrice) ? Drag.LOW : Drag.HIGH;
			if (w.minPrice == w.maxPrice) drag = price <= w.minPrice ? Drag.LOW : Drag.HIGH;
			dragTo(mx);
			return true;
		}
		Hit hit = hitAt(mx, my);
		if (hit != null && hit.click != null) {
			hit.click.run();
			// Double-clicking a row picks or unpicks it.
			if (doubled && tab != Tab.SEEN && focused != null && mx < listX1 && my >= rowsY0 && my < rowsY1 && mx > listX0 + 20) toggle(focused);
			return true;
		}
		return false;
	}

	private boolean sliderHit(double my) {
		for (Hit h : hits) {
			if (h.click == null && h.x0 == sliderX0 - 4 && my >= h.y0 && my < h.y1) return true;
		}
		return false;
	}

	private void dragTo(double mx) {
		Want w = wantOf(focused);
		int price = priceAt(mx);
		if (drag == Drag.LOW) w.minPrice = Math.min(price, w.maxPrice);
		else if (drag == Drag.HIGH) w.maxPrice = Math.max(price, w.minPrice);
		Reroller.wantsChanged();
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		if (drag != Drag.NONE && focused != null) {
			dragTo(click.x());
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (drag != Drag.NONE) {
			drag = Drag.NONE;
			changed();
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (options) return true;
		// Over the slider: nudge the nearer handle by one emerald (five with Shift).
		if (focused != null && mouseX >= sliderX0 - 4 && mouseX <= sliderX1 + 4 && sliderHit(mouseY)) {
			Want w = wantOf(focused);
			int step = (int) Math.signum(verticalAmount) * (hasShiftDown() ? 5 : 1);
			int price = priceAt(mouseX);
			if (Math.abs(price - w.minPrice) < Math.abs(price - w.maxPrice)) w.minPrice = MathHelper.clamp(w.minPrice + step, 1, w.maxPrice);
			else w.maxPrice = MathHelper.clamp(w.maxPrice + step, w.minPrice, 64);
			changed();
			return true;
		}
		if (mouseX < listX1) scroll = clampScroll(scroll - verticalAmount * ROW * 2);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();
		if (options) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				options = false;
				return true;
			}
			return true;
		}
		if (tab != Tab.SEEN && !shown.isEmpty() && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
			int index = focused == null ? -1 : shown.indexOf(focused);
			index = MathHelper.clamp(index + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, shown.size() - 1);
			focus(shown.get(index));
			scrollTo(focused);
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			if ((input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) startOrStop();
			else if (focused != null) toggle(focused);
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void removed() {
		ModConfig.save();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---- Self-test hooks ----

	void testFocus(String id) {
		Choice c = find(id);
		if (c != null) {
			focus(c);
			scrollTo(c);
		}
	}

	void testTab(String name) {
		setTab(Tab.valueOf(name));
	}

	void testOptions(boolean open) {
		options = open;
	}

	void testOptionsTab(int which) {
		optionsTab = which;
	}
}
