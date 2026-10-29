package com.greysworkshop.tradeseeker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.VillagerProfession;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every villager's trades it has seen (the server only sends them when the trades are opened), kept in
 * config/tradeseeker-trades.json, so the labels over villagers' heads stand in for signs. A villager seen jobless is
 * forgotten: its next job brings new trades.
 */
public final class TradeMemory {
	/** What the labels over villagers show. */
	public enum Labels { OFF, TOP, BOOKS, ALL }

	/** One trade as seen: what it sells, and its price before any discount. */
	static final class Offer {
		String item;
		int count;
		/** For an enchanted book: its enchantment and level. */
		String enchantment;
		int level;
		String priceItem;
		int price;
		String price2Item;
		int price2;
		boolean disabled;

		@Override
		public boolean equals(Object o) {
			return o instanceof Offer x && Objects.equals(item, x.item) && count == x.count && Objects.equals(enchantment, x.enchantment) && level == x.level
					&& Objects.equals(priceItem, x.priceItem) && price == x.price && Objects.equals(price2Item, x.price2Item) && price2 == x.price2
					&& disabled == x.disabled;
		}

		@Override
		public int hashCode() {
			return Objects.hash(item, count, enchantment, level, price);
		}
	}

	static final class Seen {
		String profession;
		/** Traded with: its trades are locked for good. */
		boolean locked;
		List<Offer> offers = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().create();
	private static final int KEPT = 2000;
	/** How far away labels can show (blocks), to pick from. */
	public static final int[] RANGES = {8, 16, 24, 48};
	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("tradeseeker-trades.json");
	/** By villager UUID, oldest first. */
	private static Map<String, Seen> seen = new LinkedHashMap<>();
	private static boolean dirty;
	private static long dirtySince;

	private TradeMemory() {}

	public static void load() {
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			Map<String, Seen> loaded = GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, Seen>>() {}.getType());
			if (loaded != null) {
				loaded.values().removeIf(s -> s == null || s.offers == null);
				seen = loaded;
			}
		} catch (IOException | RuntimeException e) {
			TradeSeekerClient.LOGGER.error("Could not read {}", file, e);
		}
	}

	public static void save() {
		dirty = false;
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temp)) {
				GSON.toJson(seen, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			TradeSeekerClient.LOGGER.error("Could not write {}", file, e);
		}
	}

	/** Saves a few seconds after a change, so a run of re-rolls doesn't write the file every roll. */
	public static void tick() {
		if (dirty && System.currentTimeMillis() - dirtySince > 5000) save();
	}

	/** Self-test only: read and write somewhere else. */
	static void useFile(Path other) {
		file = other;
		seen = new LinkedHashMap<>();
	}

	/** Remembers the trades a villager showed (called while they're open; cheap when nothing changed). */
	public static void record(VillagerEntity v, TradeOfferList offers, boolean locked) {
		if (v == null || offers.isEmpty()) return;
		Seen s = new Seen();
		s.profession = v.getVillagerData().profession().getKey().map(k -> k.getValue().toString()).orElse("");
		s.locked = locked;
		for (TradeOffer offer : offers) s.offers.add(offerOf(offer));
		String id = v.getUuidAsString();
		Seen old = seen.get(id);
		if (old != null && old.locked == s.locked && Objects.equals(old.profession, s.profession) && old.offers.equals(s.offers)) return;
		seen.remove(id);
		seen.put(id, s);
		while (seen.size() > KEPT) seen.remove(seen.keySet().iterator().next());
		changed();
	}

	/** Bought from: its trades are locked for good now. */
	public static void locked(VillagerEntity v) {
		Seen s = v == null ? null : seen.get(v.getUuidAsString());
		if (s != null && !s.locked) {
			s.locked = true;
			changed();
		}
	}

	public static void forgetAll() {
		seen.clear();
		save();
	}

	public static int count() {
		return seen.size();
	}

	private static void changed() {
		if (!dirty) dirtySince = System.currentTimeMillis();
		dirty = true;
	}

	private static Offer offerOf(TradeOffer offer) {
		Offer o = new Offer();
		ItemStack sell = offer.getSellItem();
		o.item = Registries.ITEM.getId(sell.getItem()).toString();
		o.count = sell.getCount();
		if (sell.isOf(Items.ENCHANTED_BOOK)) {
			var stored = sell.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
			for (var entry : stored.getEnchantmentEntries()) {
				o.enchantment = Reroller.idOf(entry.getKey());
				o.level = entry.getIntValue();
				break;
			}
		}
		ItemStack first = offer.getOriginalFirstBuyItem();
		o.priceItem = Registries.ITEM.getId(first.getItem()).toString();
		o.price = first.getCount();
		ItemStack second = offer.getDisplayedSecondBuyItem();
		if (!second.isEmpty()) {
			o.price2Item = Registries.ITEM.getId(second.getItem()).toString();
			o.price2 = second.getCount();
		}
		o.disabled = offer.isDisabled();
		return o;
	}

	/**
	 * The lines over a villager's head, top to bottom, or an empty list. Books show the price they keep for you (less
	 * a cure's lasting discount); top-level books are gold and wanted ones green.
	 */
	public static List<Text> lines(MinecraftClient client, VillagerEntity v) {
		Labels mode = ModConfig.INSTANCE.labels;
		if (mode == Labels.OFF || client.world == null || client.player == null || v.isBaby()) return List.of();
		Seen s = seen.get(v.getUuidAsString());
		if (s == null) return List.of();
		var profession = v.getVillagerData().profession();
		if (profession.matchesKey(VillagerProfession.NONE)) {
			// Lost its job: those trades are gone.
			seen.remove(v.getUuidAsString());
			changed();
			return List.of();
		}
		if (!profession.getKey().map(k -> k.getValue().toString()).orElse("").equals(s.profession)) return List.of();
		int range = ModConfig.INSTANCE.labelRange;
		if (v.squaredDistanceTo(client.player) > range * range) return List.of();
		var registry = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
		int discount = Reroller.discountFor(v);
		List<Text> lines = new ArrayList<>();
		for (Offer o : s.offers) {
			if (o.enchantment != null) {
				var entry = registry.getEntry(Identifier.tryParse(o.enchantment));
				if (entry.isEmpty()) continue;
				Enchantment e = entry.get().value();
				boolean top = o.level >= e.getMaxLevel();
				boolean wanted = ModConfig.INSTANCE.wanted.containsKey(o.enchantment);
				if (mode == Labels.TOP && !top && !wanted) continue;
				int price = "minecraft:emerald".equals(o.priceItem) ? Math.max(1, o.price - discount) : o.price;
				MutableText line = Enchantment.getName(entry.get(), o.level).copy().formatted(wanted ? Formatting.GREEN : top ? Formatting.GOLD : Formatting.WHITE);
				line.append(Text.literal(" · ").formatted(Formatting.GRAY)).append(price(o.priceItem, price, o));
				if (o.disabled) line.formatted(Formatting.STRIKETHROUGH);
				lines.add(line);
			} else if (mode == Labels.ALL) {
				MutableText line = Text.empty();
				if (o.count > 1) line.append(o.count + " × ");
				line.append(itemName(o.item)).append(Text.literal(" · ").formatted(Formatting.GRAY)).append(price(o.priceItem, o.price, o));
				if (o.disabled) line.formatted(Formatting.STRIKETHROUGH);
				lines.add(line);
			}
		}
		if (!lines.isEmpty() && s.locked) lines.add(0, Text.translatable("tradeseeker.label.locked").formatted(Formatting.DARK_GRAY));
		return lines;
	}

	private static Text price(String item, int count, Offer o) {
		MutableText t = "minecraft:emerald".equals(item) ? Text.translatable(count == 1 ? "tradeseeker.label.emerald" : "tradeseeker.label.emeralds", count)
				: Text.literal(count + " ").append(itemName(item));
		if (o.price2Item != null && !"minecraft:book".equals(o.price2Item)) {
			t.append(" + " + o.price2 + " ").append(itemName(o.price2Item));
		}
		return t.formatted(Formatting.YELLOW);
	}

	private static Text itemName(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? Text.literal(id) : Registries.ITEM.get(key).getName();
	}
}
