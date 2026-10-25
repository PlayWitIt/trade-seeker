package com.greysworkshop.tradeseeker;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Development-only scripted test, run with {@code ./gradlew runSelfTest}. Not included in the release jar.
 * Builds a two-villager trading hall in a survival world and re-rolls it for real, ending with Mending and Unbreaking III.
 */
final class SelfTest {
	private static final String WORLD = "ts-selftest";
	/** The two pods' z positions. */
	private static final int[] PODS = {0, 2};
	/** Common books, so a match turns up within a few rolls. */
	private static final String[] COMMON = {"minecraft:protection", "minecraft:sharpness", "minecraft:efficiency",
			"minecraft:unbreaking", "minecraft:power", "minecraft:fire_protection", "minecraft:smite", "minecraft:bane_of_arthropods"};

	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";
	private static boolean escaped;
	private static long hallStart;

	private SelfTest() {}

	static void start() {
		log("Self-test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		Path saves = client.runDirectory.toPath().resolve("saves").resolve(WORLD);
		try (Stream<Path> files = Files.exists(saves) ? Files.walk(saves) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear the old world: " + e);
		}
		try {
			ModConfig.useFile(Files.createTempDirectory("tradeseeker").resolve("tradeseeker.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		if (tick % 2000 == 0) command(client, "time set 1000");
		if (tick > 100) watchForEscape(client);
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 60) build(client);
				if (tick == 140) {
					ModConfig config = ModConfig.INSTANCE;
					config.wanted.clear();
					for (String id : COMMON) config.wanted.put(id, 1);
					config.autoBuy = false;
					config.minPrice = 1;
					config.maxPrice = 64;
					ModConfig.save();
					client.setScreen(new SeekerScreen());
				}
				if (tick == 160) screenshot(client, "1_menu");
				if (tick == 170) {
					client.setScreen(null);
					// First a book no librarian sells (level 9), with a limit of 3: the loop must re-roll exactly 3 times.
					want("minecraft:protection", 9);
					ModConfig.INSTANCE.maxRolls = 3;
					aimAt(client, villager(client, 0));
					Reroller.start(client);
					check("re-rolling starts on the villager you look at", Reroller.running() && Reroller.target() == villager(client, 0));
					next("limit");
				}
			}
			case "limit" -> {
				if (Reroller.running() && since < 6000) return;
				VillagerEntity v = villager(client, 0);
				check("it stops at the re-roll limit (" + Reroller.rolls() + " of 3)", !Reroller.running() && Reroller.rolls() == 3);
				check("the lectern is back and the villager is a librarian again", client.world.getBlockState(lectern(0)).isOf(Blocks.LECTERN)
						&& v != null && isLibrarian(v));
				check("nothing was bought while re-rolling", xp(client, 0) == 0 && !hasBook(client, null, 1));
				check("the broken lectern is picked back up each time (" + lecterns(client) + " of 4 spares left)", lecterns(client) == 4);
				ModConfig.INSTANCE.wanted.clear();
				for (String id : COMMON) ModConfig.INSTANCE.wanted.put(id, 1);
				ModConfig.INSTANCE.maxRolls = 0;
				aimAt(client, villager(client, 0));
				Reroller.start(client);
				next("rolling");
			}
			case "rolling" -> {
				if (Reroller.running() && since < 9000) return;
				Reroller.Book book = Reroller.lastFound();
				check("a common book is found (" + (book == null ? "none" : book.describe().getString()) + ")", !Reroller.running() && book != null);
				check("with auto-buy off, the trade screen is left open for you", client.currentScreen instanceof MerchantScreen);
				next("decide");
			}
			case "decide" -> {
				if (since == 10) screenshot(client, "2_found_trade_open");
				if (since == 20) {
					check("with auto-buy off, nothing is bought", xp(client, 0) == 0 && !hasBook(client, null, 1));
					if (client.currentScreen instanceof MerchantScreen ms) {
						check("the found trade is selected and its payment filled in", !ms.getScreenHandler().getSlot(2).getStack().isEmpty());
					}
					client.player.closeHandledScreen();
				}
				if (since == 40) {
					// The real job: Mending and Unbreaking III at any price, from a two-villager hall, in one go.
					ModConfig.INSTANCE.wanted.clear();
					ModConfig.INSTANCE.wanted.put("minecraft:mending", 1);
					ModConfig.INSTANCE.wanted.put("minecraft:unbreaking", 3);
					ModConfig.INSTANCE.minPrice = 1;
					ModConfig.INSTANCE.maxPrice = 64;
					ModConfig.INSTANCE.autoBuy = true;
					aimAt(client, villager(client, 0));
					Reroller.start(client);
					hallStart = System.currentTimeMillis();
					next("hall");
				}
			}
			case "hall" -> {
				if (since % 1200 == 0) log("hall: " + Reroller.rolls() + " re-rolls, " + (System.currentTimeMillis() - hallStart) / 1000 + " s, state "
						+ Reroller.stateName() + ", villager " + (Reroller.target() == villager(client, 0) ? "A" : "B"));
				if (Reroller.running() && since < 20 * 60 * 90) return;
				long seconds = (System.currentTimeMillis() - hallStart) / 1000;
				log("hall finished after " + Reroller.rolls() + " re-rolls in " + seconds / 60 + "m " + seconds % 60 + "s");
				next("hall_check");
			}
			case "hall_check" -> {
				if (since == 10) screenshot(client, "3_hall_done");
				if (since == 20) {
					if (client.currentScreen != null) client.player.closeHandledScreen();
					check("bought exactly one Mending book (" + books(client, "minecraft:mending", 1) + ")", books(client, "minecraft:mending", 1) == 1);
					check("bought exactly one Unbreaking III book (" + books(client, "minecraft:unbreaking", 3) + ")",
							books(client, "minecraft:unbreaking", 3) == 1);
					check("nothing else was bought (" + books(client, null, 1) + " books)", books(client, null, 1) == 2);
					check("both villagers were traded with (" + xp(client, 0) + ", " + xp(client, 2) + " xp)", xp(client, 0) > 0 && xp(client, 2) > 0);
					check("neither villager escaped its pod", !escaped && villager(client, 0) != null && villager(client, 2) != null);
					// With the trapdoor, a lectern is rarely lost (about 1 in 50 re-rolls); without it, about 1 in 2.
					int used = 4 - lecterns(client);
					check("lecterns are hardly ever lost (" + used + " used in " + Reroller.rolls() + " re-rolls)", used * 20 <= Math.max(Reroller.rolls(), 20));
				}
				if (since == 30) {
					aimAt(client, villager(client, 0));
					ModConfig.INSTANCE.wanted.put("minecraft:protection", 1);
					Reroller.start(client);
				}
				if (since == 50) {
					check("a traded librarian is refused (its trades are locked)", !Reroller.running());
					ModConfig.save();
					ModConfig before = ModConfig.INSTANCE;
					ModConfig.INSTANCE = new ModConfig();
					ModConfig.load();
					check("settings survive a restart", ModConfig.INSTANCE.autoBuy == before.autoBuy
							&& ModConfig.INSTANCE.wanted.equals(before.wanted) && ModConfig.INSTANCE.maxPrice == before.maxPrice);
					finish(client);
				}
			}
			default -> {}
		}
	}

	private static void want(String id, int level) {
		ModConfig.INSTANCE.wanted.clear();
		ModConfig.INSTANCE.wanted.put(id, level);
	}

	/**
	 * A trading hall: two pods side by side, sharing a wall, facing an aisle at x=0. Each villager stands in a 1x1 glass
	 * cell with its lectern in front and glass above the lectern: with the lectern broken the gap is 1 high, so the
	 * villager can't get out. An open trapdoor across the pod's front keeps the drop from flying in.
	 * The player stands in the aisle between the two pods.
	 */
	private static void build(MinecraftClient client) {
		log("Building the trading hall");
		command(client, "kill @e[type=!player]");
		command(client, "fill -3 -61 -3 5 -61 6 minecraft:stone");
		command(client, "fill -3 -60 -3 5 -56 6 minecraft:air");
		for (int z : PODS) {
			command(client, "fill 1 -60 " + (z - 1) + " 3 -58 " + (z + 1) + " minecraft:glass");
			command(client, "fill 2 -60 " + z + " 2 -59 " + z + " minecraft:air");
			// An open iron trapdoor across the front of the pod: drops from the lectern bounce off it instead of landing
			// at the villager's feet.
			command(client, "setblock 2 -60 " + z + " minecraft:iron_trapdoor[open=true,facing=east]");
			command(client, "setblock 1 -60 " + z + " minecraft:lectern");
			command(client, "summon minecraft:villager 2.7 -60 " + (z + 0.5) + " {VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}}");
		}
		// Right up against the lecterns (x=1), as you would stand to catch the drop.
		command(client, "tp @a 0.69 -60 1 -90 20");
		command(client, "clear @a");
		command(client, "give @a minecraft:diamond_axe");
		command(client, "give @a minecraft:emerald 192");
		command(client, "give @a minecraft:book 8");
		command(client, "give @a minecraft:lectern 4");
		command(client, "time set 1000");
	}

	private static void watchForEscape(MinecraftClient client) {
		for (Entity e : client.world.getEntities()) {
			if (!(e instanceof VillagerEntity v)) continue;
			int x = MathHelper.floor(v.getX()), z = MathHelper.floor(v.getZ());
			if (!escaped && (x != 2 || (z != PODS[0] && z != PODS[1]))) {
				escaped = true;
				log("A villager got out of its pod, at " + v.getBlockPos().toShortString());
			}
		}
	}

	private static BlockPos lectern(int pod) {
		return new BlockPos(1, -60, pod);
	}

	private static boolean isLibrarian(VillagerEntity v) {
		return v.getVillagerData().profession().matchesKey(VillagerProfession.LIBRARIAN);
	}

	/** The villager in the pod at that z. */
	private static VillagerEntity villager(MinecraftClient client, int pod) {
		for (Entity e : client.world.getEntities()) if (e instanceof VillagerEntity v && MathHelper.floor(v.getZ()) == pod) return v;
		return null;
	}

	private static int xp(MinecraftClient client, int pod) {
		VillagerEntity v = villager(client, pod);
		if (v == null) return -1;
		Entity server = client.getServer().getOverworld().getEntity(v.getUuid());
		return server instanceof VillagerEntity sv ? sv.getExperience() : -1;
	}

	/** Whether the player holds an enchanted book (with that enchantment at that level or higher, if given). */
	private static boolean hasBook(MinecraftClient client, String id, int level) {
		return books(client, id, level) > 0;
	}

	/** How many such enchanted books the player holds (any book, if no id is given). */
	private static int books(MinecraftClient client, String id, int level) {
		var inventory = client.player.getInventory();
		int n = 0;
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (!stack.isOf(Items.ENCHANTED_BOOK)) continue;
			if (id == null) {
				n += stack.getCount();
				continue;
			}
			for (var entry : stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT).getEnchantmentEntries()) {
				if (id.equals(Reroller.idOf(entry.getKey())) && entry.getIntValue() >= level) n += stack.getCount();
			}
		}
		return n;
	}

	private static int lecterns(MinecraftClient client) {
		var inventory = client.player.getInventory();
		int n = 0;
		for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(Items.LECTERN)) n += inventory.getStack(i).getCount();
		return n;
	}

	private static void aimAt(MinecraftClient client, VillagerEntity v) {
		if (v == null) return;
		Vec3d d = v.getEyePos().subtract(client.player.getEyePos());
		client.player.setYaw((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
		client.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
	}

	private static void command(MinecraftClient client, String command) {
		MinecraftServer server = client.getServer();
		server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), command));
	}

	private static void next(String name) {
		phase = name;
		phaseStart = tick;
	}

	private static void finish(MinecraftClient client) {
		log("RESULT " + passed + " passed, " + failed + " failed");
		next("done");
		client.scheduleStop();
	}

	private static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "ts_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		TradeSeekerClient.LOGGER.info("[SELFTEST] {}", message);
	}
}
