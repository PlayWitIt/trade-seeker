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
 * Builds a jobless villager beside a lectern in a survival world and re-rolls it for real.
 */
final class SelfTest {
	private static final String WORLD = "ts-selftest";
	/** Common books, so a match turns up within a few rolls. */
	private static final String[] WANTED = {"minecraft:protection", "minecraft:sharpness", "minecraft:efficiency",
			"minecraft:unbreaking", "minecraft:power", "minecraft:fire_protection", "minecraft:smite", "minecraft:bane_of_arthropods"};

	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";
	private static Reroller.Book book;

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
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 60) build(client);
				if (tick == 140) {
					ModConfig config = ModConfig.INSTANCE;
					config.wanted.clear();
					for (String id : WANTED) config.wanted.put(id, 1);
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
					ModConfig.INSTANCE.wanted.clear();
					ModConfig.INSTANCE.wanted.put("minecraft:protection", 9);
					ModConfig.INSTANCE.maxRolls = 3;
					aimAtVillager(client);
					Reroller.start(client);
					check("re-rolling starts with the villager in its pod", Reroller.running() && Reroller.target() == villager(client));
					next("limit");
				}
			}
			case "limit" -> {
				if (since % 200 == 0) log("rolls=" + Reroller.rolls() + " state=" + Reroller.stateName());
				if (Reroller.running() && since < 6000) return;
				VillagerEntity v = villager(client);
				check("it stops at the re-roll limit (" + Reroller.rolls() + " of 3)", !Reroller.running() && Reroller.rolls() == 3);
				check("the lectern is back and the villager is a librarian again", client.world.getBlockState(LECTERN).isOf(Blocks.LECTERN)
						&& v != null && v.getVillagerData().profession().matchesKey(VillagerProfession.LIBRARIAN));
				check("nothing was bought while re-rolling", serverVillagerXp(client) == 0 && !hasBook(client, null));
				ModConfig.INSTANCE.wanted.clear();
				for (String id : WANTED) ModConfig.INSTANCE.wanted.put(id, 1);
				ModConfig.INSTANCE.maxRolls = 0;
				aimAtVillager(client);
				Reroller.start(client);
				next("rolling");
			}
			case "rolling" -> {
				if (since % 200 == 0) log("rolls=" + Reroller.rolls() + " state=" + Reroller.stateName());
				if (Reroller.running()) {
					if (since > 9000) {
						check("a wanted book turns up within 7.5 minutes (" + Reroller.rolls() + " rolls)", false);
						finish(client);
					}
					return;
				}
				book = Reroller.lastFound();
				check("a wanted book is found (" + Reroller.rolls() + " rolls, " + (book == null ? "none" : book.describe().getString()) + ")",
						book != null && ModConfig.INSTANCE.wants(Reroller.idOf(book.enchantment()), book.level()));
				check("with auto-buy off, the trade screen is left open for you", client.currentScreen instanceof MerchantScreen);
				next("decide");
			}
			case "decide" -> {
				if (since == 10) screenshot(client, "2_found_trade_open");
				if (since == 20) {
					check("with auto-buy off, nothing is bought", serverVillagerXp(client) == 0 && !hasBook(client, null));
					if (client.currentScreen instanceof MerchantScreen ms) {
						check("the found trade is selected and its payment filled in",
								!ms.getScreenHandler().getSlot(2).getStack().isEmpty());
					}
					client.player.closeHandledScreen();
				}
				if (since == 40) {
					if (book == null) {
						finish(client);
						return;
					}
					// Ask for exactly what it has now, so the next start finds it at once and buys it.
					ModConfig.INSTANCE.wanted.clear();
					ModConfig.INSTANCE.wanted.put(Reroller.idOf(book.enchantment()), book.level());
					ModConfig.INSTANCE.autoBuy = true;
					aimAtVillager(client);
					Reroller.start(client);
					next("autobuy");
				}
			}
			case "autobuy" -> {
				if (Reroller.running() && since < 400) return;
				if (since == 400) check("auto-buy finishes", false);
				check("auto-buy buys the book", hasBook(client, Reroller.idOf(book.enchantment())));
				next("locked");
			}
			case "locked" -> {
				if (since == 10) screenshot(client, "3_bought");
				if (since == 20) {
					check("the villager has traded (" + serverVillagerXp(client) + " xp)", serverVillagerXp(client) > 0);
					client.player.closeHandledScreen();
				}
				if (since == 40) {
					aimAtVillager(client);
					Reroller.start(client);
				}
				if (since == 60) {
					check("a traded librarian is refused (its trades are locked)", !Reroller.running());
					check("the next-door villager and its lectern are left alone", client.world.getBlockState(LECTERN.south(3)).isOf(Blocks.LECTERN)
							&& neighbour(client) != null && neighbour(client).getVillagerData().profession().matchesKey(VillagerProfession.LIBRARIAN));
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

	private static void build(MinecraftClient client) {
		log("Building the test bench");
		command(client, "kill @e[type=!player]");
		command(client, "tp @a 0.5 -60 0.5 -90 20");
		command(client, "fill -3 -61 -3 5 -61 6 minecraft:stone");
		command(client, "fill -3 -60 -3 5 -57 6 minecraft:air");
		// Two trading-hall pods side by side: each villager in a 1x1 glass cell with its lectern in front, facing the aisle.
		for (int z : new int[] {0, 3}) {
			command(client, "fill 1 -60 " + (z - 1) + " 3 -58 " + (z + 1) + " minecraft:glass");
			command(client, "fill 1 -60 " + z + " 2 -59 " + z + " minecraft:air");
			command(client, "setblock 1 -60 " + z + " minecraft:lectern");
			command(client, "summon minecraft:villager 2.5 -60 " + (z + 0.5) + " {VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}}");
		}
		command(client, "clear @a");
		command(client, "give @a minecraft:diamond_axe");
		command(client, "give @a minecraft:emerald 64");
		command(client, "give @a minecraft:book 4");
		command(client, "time set 1000");
	}

	private static final BlockPos LECTERN = new BlockPos(1, -60, 0);

	/** The villager being re-rolled (the pod at z=0). */
	private static VillagerEntity villager(MinecraftClient client) {
		for (Entity e : client.world.getEntities()) if (e instanceof VillagerEntity v && v.getZ() < 1.5) return v;
		return null;
	}

	private static VillagerEntity neighbour(MinecraftClient client) {
		for (Entity e : client.world.getEntities()) if (e instanceof VillagerEntity v && v.getZ() > 2.5) return v;
		return null;
	}

	private static int serverVillagerXp(MinecraftClient client) {
		VillagerEntity v = villager(client);
		if (v == null) return -1;
		Entity server = client.getServer().getOverworld().getEntity(v.getUuid());
		return server instanceof VillagerEntity sv ? sv.getExperience() : -1;
	}

	/** Whether the player holds an enchanted book (with that enchantment, if given). */
	private static boolean hasBook(MinecraftClient client, String id) {
		var inventory = client.player.getInventory();
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (!stack.isOf(Items.ENCHANTED_BOOK)) continue;
			if (id == null) return true;
			for (var entry : stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT).getEnchantments()) {
				if (id.equals(Reroller.idOf(entry))) return true;
			}
		}
		return false;
	}

	private static void aimAtVillager(MinecraftClient client) {
		VillagerEntity v = villager(client);
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
