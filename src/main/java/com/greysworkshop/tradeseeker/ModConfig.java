package com.greysworkshop.tradeseeker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

	/** Wanted enchantment id (like "minecraft:mending") to the lowest level that counts. */
	public Map<String, Integer> wanted = new LinkedHashMap<>();
	/** Emerald price range a book must be in. */
	public int minPrice = 1;
	public int maxPrice = 64;
	/** Buy the book as soon as it's found. Off: the trade screen stays open and you decide. */
	public boolean autoBuy = false;
	/** Turn the camera toward the lectern and villager while working (what a player would do). */
	public boolean faceTargets = true;
	/** Ticks to wait between steps, so it doesn't act faster than a person could. */
	public int stepDelay = 4;
	/** Give up after this many re-rolls (0 = never). */
	public int maxRolls = 0;
	public boolean sound = true;

	public boolean wants(String id, int level) {
		Integer min = wanted.get(id);
		return min != null && level >= min;
	}

	public boolean priceOk(int emeralds) {
		return emeralds >= minPrice && emeralds <= maxPrice;
	}

	public static void load() {
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
			if (loaded != null) {
				if (loaded.wanted == null) loaded.wanted = new LinkedHashMap<>();
				loaded.minPrice = Math.max(1, Math.min(64, loaded.minPrice));
				loaded.maxPrice = Math.max(loaded.minPrice, Math.min(64, loaded.maxPrice));
				loaded.stepDelay = Math.max(0, Math.min(40, loaded.stepDelay));
				INSTANCE = loaded;
			}
		} catch (IOException | RuntimeException e) {
			TradeSeekerClient.LOGGER.error("Could not read {}", file, e);
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
