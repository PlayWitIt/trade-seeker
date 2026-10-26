package com.greysworkshop.tradeseeker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** User settings, saved to config/tradeseeker.json. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("tradeseeker.json");

	public static ModConfig INSTANCE = new ModConfig();

	/** What counts as a find for one enchantment: this level or higher, at a price in this range. */
	public static final class Want {
		public int minLevel = 1;
		public int minPrice = 1;
		public int maxPrice = 64;

		public Want() {}

		public Want(int minLevel, int minPrice, int maxPrice) {
			this.minLevel = minLevel;
			this.minPrice = minPrice;
			this.maxPrice = maxPrice;
		}

		public boolean accepts(int level, int price) {
			return level >= minLevel && price >= minPrice && price <= maxPrice;
		}

		public Want copy() {
			return new Want(minLevel, minPrice, maxPrice);
		}

		void clamp() {
			minLevel = Math.max(1, minLevel);
			minPrice = Math.max(1, Math.min(64, minPrice));
			maxPrice = Math.max(minPrice, Math.min(64, maxPrice));
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Want w && w.minLevel == minLevel && w.minPrice == minPrice && w.maxPrice == maxPrice;
		}

		@Override
		public int hashCode() {
			return minLevel * 4099 + minPrice * 67 + maxPrice;
		}
	}

	public enum Speed {
		FAST(1), NORMAL(4), CAREFUL(10);

		/** Ticks to wait between steps. */
		public final int delay;

		Speed(int delay) {
			this.delay = delay;
		}
	}

	/** Wanted enchantment id (like "minecraft:mending") to what counts as a find. */
	public Map<String, Want> wanted = new LinkedHashMap<>();
	/** Buy the book as soon as it's found. Off: the trade screen stays open and you decide. */
	public boolean autoBuy = false;
	/** With auto-buy: keep buying the book until you run out of emeralds or books, or the villager runs out. */
	public boolean buyMax = false;
	/** Turn the camera toward the lectern and villager while working (what a player would do). */
	public boolean faceTargets = true;
	public Speed speed = Speed.NORMAL;
	/** Give up after this many re-rolls (0 = never). */
	public int maxRolls = 0;
	public boolean sound = true;
	/** Say in chat when a wanted book turns up at the wrong level or price. */
	public boolean nearMisses = true;
	/** Stop if you take damage (on an anarchy server, someone may be at you). */
	public boolean stopWhenHurt = true;
	/** Seconds one re-roll takes, learned from past runs; for the time estimates. */
	public double secondsPerRoll = 3.0;

	// Menu state, so it opens as you left it.
	public String focused = "minecraft:mending";
	public String tab = "ALL";

	public Want want(String id) {
		return wanted.get(id);
	}

	public static void load() {
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			upgrade(json);
			ModConfig loaded = GSON.fromJson(json, ModConfig.class);
			if (loaded != null) {
				if (loaded.wanted == null) loaded.wanted = new LinkedHashMap<>();
				loaded.wanted.values().removeIf(w -> w == null);
				loaded.wanted.values().forEach(Want::clamp);
				if (loaded.speed == null) loaded.speed = Speed.NORMAL;
				if (loaded.tab == null) loaded.tab = "ALL";
				if (!(loaded.secondsPerRoll >= 0.5 && loaded.secondsPerRoll <= 60)) loaded.secondsPerRoll = 3.0;
				loaded.maxRolls = Math.max(0, loaded.maxRolls);
				INSTANCE = loaded;
			}
		} catch (IOException | RuntimeException e) {
			TradeSeekerClient.LOGGER.error("Could not read {}", file, e);
		}
	}

	/** 1.0.0 kept a level per enchantment and one price range for all of them. */
	private static void upgrade(JsonObject json) {
		if (!json.has("wanted") || !json.get("wanted").isJsonObject()) return;
		int min = json.has("minPrice") ? json.get("minPrice").getAsInt() : 1;
		int max = json.has("maxPrice") ? json.get("maxPrice").getAsInt() : 64;
		JsonObject wanted = json.getAsJsonObject("wanted");
		for (Map.Entry<String, JsonElement> e : wanted.entrySet()) {
			if (!e.getValue().isJsonPrimitive()) continue;
			JsonObject w = new JsonObject();
			w.addProperty("minLevel", e.getValue().getAsInt());
			w.addProperty("minPrice", min);
			w.addProperty("maxPrice", max);
			e.setValue(w);
		}
	}

	/** Written beside the file and swapped in, so a crash mid-save can't leave half a file (and reset everything). */
	public static void save() {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temp)) {
				GSON.toJson(INSTANCE, writer);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			TradeSeekerClient.LOGGER.error("Could not write {}", file, e);
		}
	}

	/** Self-test only: read and write somewhere else. */
	static void useFile(Path other) {
		file = other;
	}
}
