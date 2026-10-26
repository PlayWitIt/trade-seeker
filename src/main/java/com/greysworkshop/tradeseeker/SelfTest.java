package com.greysworkshop.tradeseeker;

import com.greysworkshop.tradeseeker.ModConfig.Want;
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
 * Builds a trading hall in a survival world and re-rolls it for real, ending with Mending and Unbreaking III.
 */
final class SelfTest {
	private static final String WORLD = "ts-selftest";
	/** The pods' z positions: A and B are the hall, C is for the buy-as-many test. */
	private static final int A = 0, B = 2, C = 6;
	private static final int[] PODS = {A, B, C};
	/** Common books, so a match turns up within a few re-rolls. */
	private static final String[] COMMON = {"minecraft:protection", "minecraft:sharpness", "minecraft:efficiency",
			"minecraft:unbreaking", "minecraft:power", "minecraft:fire_protection", "minecraft:smite", "minecraft:bane_of_arthropods"};

	private static boolean started, escaped;
	private static int tick = -1, phaseStart, passed, failed, booksBefore;
	private static String phase = "setup";
	private static long hallStart;
	private static Reroller.Book found;

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
		ModConfig config = ModConfig.INSTANCE;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) {
					checkOdds();
					checkUpgrade();
				}
				if (tick == 60) build(client);
				if (tick == 140) {
					config.wanted.clear();
					config.wanted.put("minecraft:mending", new Want(1, 1, 64));
					config.wanted.put("minecraft:unbreaking", new Want(3, 1, 30));
					config.wanted.put("minecraft:efficiency", new Want(4, 1, 40));
					config.autoBuy = false;
					config.buyMax = false;
					ModConfig.save();
					client.setScreen(new SeekerScreen());
				}
				if (tick == 145 && client.currentScreen instanceof SeekerScreen s) s.testFocus("minecraft:unbreaking");
				if (tick % 5 == 0 && tick < 215) client.getToastManager().clear();
				if (tick == 160) screenshot(client, "1_menu_all");
				if (tick == 162 && client.currentScreen instanceof SeekerScreen s) {
					s.testTab("PICKED");
					s.testFocus("minecraft:mending");
				}
				if (tick == 175) screenshot(client, "1b_menu_picked_mending");
				if (tick == 177 && client.currentScreen instanceof SeekerScreen s) s.testOptions(true);
				if (tick == 190) screenshot(client, "1c_menu_options");
				if (tick == 192 && client.currentScreen instanceof SeekerScreen s) s.testOptions(false);
				// -Dtradeseeker.selftest.menu=true: just the menu screenshots, for quick looks at the layout.
				if (tick == 210 && Boolean.getBoolean("tradeseeker.selftest.menu")) finish(client);
				if (tick == 215) {
					client.setScreen(null);
					// Stop when hurt: start on a book no librarian sells, then take a little damage.
					want("minecraft:protection", 9, 1, 64);
					aimAt(client, villager(client, A));
					Reroller.start(client);
					check("re-rolling starts on the villager you look at", Reroller.running() && Reroller.target() == villager(client, A));
				}
				if (tick == 260) command(client, "damage @p 1 minecraft:generic");
				if (tick == 270) {
					check("it stops when you get hurt", !Reroller.running());
					command(client, "effect give @p minecraft:instant_health 1 1");
				}
				if (tick == 290) {
					want("minecraft:protection", 9, 1, 64);
					config.maxRolls = 3;
					aimAt(client, villager(client, A));
					Reroller.start(client);
					next("limit");
				}
			}
			case "limit" -> {
				if (Reroller.running() && since < 6000) return;
				VillagerEntity v = villager(client, A);
				check("it stops at the re-roll limit (" + Reroller.rolls() + " of 3)", !Reroller.running() && Reroller.rolls() == 3);
				check("the lectern is back and the villager is a librarian again", client.world.getBlockState(lectern(A)).isOf(Blocks.LECTERN)
						&& v != null && isLibrarian(v));
				check("nothing was bought while re-rolling", xp(client, A) == 0 && books(client, null, 1) == 0);
				check("the broken lectern is picked back up each time (" + lecterns(client) + " of 4 spares left)", lecterns(client) == 4);
				config.maxRolls = 0;
				commonBooks();
				aimAt(client, villager(client, A));
				Reroller.start(client);
				next("rolling");
			}
			case "rolling" -> {
				if (Reroller.running() && since < 9000) return;
				found = Reroller.lastFound();
				check("a common book is found (" + (found == null ? "none" : found.describe().getString()) + ")", !Reroller.running() && found != null);
				check("with auto-buy off, the trade screen is left open for you", client.currentScreen instanceof MerchantScreen);
				next("decide");
			}
			case "decide" -> {
				if (since == 10) screenshot(client, "2_found_trade_open");
				if (since == 20) {
					check("with auto-buy off, nothing is bought", xp(client, A) == 0 && books(client, null, 1) == 0);
					if (client.currentScreen instanceof MerchantScreen ms) {
						check("the found trade is selected and its payment filled in", !ms.getScreenHandler().getSlot(2).getStack().isEmpty());
					}
					client.player.closeHandledScreen();
				}
				// Buy as many as I can afford, on pod C.
				// (C is out of reach of the hall, so its run can't move on to B.)
				if (since == 30) command(client, "tp @p 0.69 -60 6.5 -90 20");
				if (since == 50) {
					commonBooks();
					config.autoBuy = true;
					config.buyMax = true;
					aimAt(client, villager(client, C));
					booksBefore = books(client, Items.BOOK);
					Reroller.start(client);
					next("buymax");
				}
			}
			case "buymax" -> {
				if (Reroller.running() && since < 9000) return;
				Reroller.Book b = Reroller.lastFound();
				int got = b == null ? 0 : books(client, Reroller.idOf(b.enchantment()), 1);
				int expected = b == null ? -1 : Math.min(12, Math.min(booksBefore, 320 / b.price()));
				check("buy as many as I can afford buys until something runs out (" + got + " of " + expected + ")", b != null && got == expected);
				check("…and every one was a real trade (" + xp(client, C) + " xp)", xp(client, C) == got);
				// Hand back what C cost, for the hall.
				command(client, "clear @p minecraft:enchanted_book");
				command(client, "clear @p minecraft:emerald");
				command(client, "clear @p minecraft:book");
				command(client, "give @p minecraft:emerald 192");
				command(client, "give @p minecraft:book 8");
				command(client, "tp @p 0.69 -60 1 -90 20");
				config.buyMax = false;
				next("hall_go");
			}
			case "hall_go" -> {
				if (since != 30) return;
				// The real job: Mending and Unbreaking III at any price, from a two-villager hall, in one go.
				config.wanted.clear();
				config.wanted.put("minecraft:mending", new Want(1, 1, 64));
				config.wanted.put("minecraft:unbreaking", new Want(3, 1, 64));
				config.autoBuy = true;
				aimAt(client, villager(client, A));
				Reroller.start(client);
				hallStart = System.currentTimeMillis();
				next("hall");
			}
			case "hall" -> {
				if (since % 1200 == 0) log("hall: " + Reroller.rolls() + " re-rolls, " + (System.currentTimeMillis() - hallStart) / 1000 + " s, state "
						+ Reroller.stateName() + ", villager " + (Reroller.target() == villager(client, A) ? "A" : "B"));
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
					check("both villagers were traded with (" + xp(client, A) + ", " + xp(client, B) + " xp)", xp(client, A) > 0 && xp(client, B) > 0);
					check("no villager escaped its pod", !escaped && villager(client, A) != null && villager(client, B) != null);
					// With the trapdoor, a lectern is rarely lost (about 1 in 50 re-rolls); without it, about 1 in 2.
					int used = 4 - lecterns(client);
					check("lecterns are hardly ever lost (" + used + " used in " + Reroller.rolls() + " re-rolls)", used * 20 <= Math.max(Reroller.rolls(), 20));
					long withBook = Reroller.seen().stream().filter(s -> !s.books().isEmpty()).count();
					int n = Reroller.seen().size();
					check("about 2 in 3 re-rolls offer a book, as the odds assume (" + withBook + " of " + n + ")",
							n < 30 || Math.abs(withBook / (double) n - 2 / 3.0) < 0.15);
					long near = Reroller.seen().stream().filter(s -> s.kind() == Reroller.Kind.NEAR).count();
					check("near misses are noted in the Seen list (" + near + ")", near > 0 || n < 30);
					client.setScreen(new SeekerScreen());
				}
				if (since == 25 && client.currentScreen instanceof SeekerScreen s) s.testTab("SEEN");
				if (since == 40) screenshot(client, "4_menu_seen");
				if (since == 45) {
					client.setScreen(null);
					aimAt(client, villager(client, A));
					config.wanted.put("minecraft:protection", new Want(1, 1, 64));
					Reroller.start(client);
				}
				if (since == 65) {
					check("a traded librarian is refused (its trades are locked)", !Reroller.running());
					ModConfig.save();
					ModConfig before = ModConfig.INSTANCE;
					ModConfig.INSTANCE = new ModConfig();
					ModConfig.load();
					check("settings survive a restart", ModConfig.INSTANCE.autoBuy == before.autoBuy
							&& ModConfig.INSTANCE.wanted.equals(before.wanted) && ModConfig.INSTANCE.secondsPerRoll == before.secondsPerRoll);
					check("it learned its pace (" + ModConfig.INSTANCE.secondsPerRoll + " s per re-roll)", ModConfig.INSTANCE.secondsPerRoll != 3.0);
					finish(client);
				}
			}
			default -> {}
		}
	}

	/** The odds and prices against numbers worked out by hand from the game's trade rules. */
	private static void checkOdds() {
		check("Mending costs 10–38 emeralds", Odds.lowestPrice(1, true) == 10 && Odds.highestPrice(1, true) == 38);
		check("Unbreaking III costs 11–45 emeralds", Odds.lowestPrice(3, false) == 11 && Odds.highestPrice(3, false) == 45);
		check("treasure prices stop at 64", Odds.highestPrice(5, true) == 64);
		// Unbreaking III at any price, with 38 tradeable enchantments: 2/3 × 1/38 × 1/3.
		double p = Odds.perRoll(38, 1, 3, false, 3, 1, 64);
		check("Unbreaking III at any price is 1 in 171 (" + Odds.oneIn(p) + ")", Odds.oneIn(p) == 171);
		check("a price range nobody asks can't happen", Odds.perRoll(38, 1, 1, true, 1, 1, 9) == 0);
		// Unbreaking III's 35 equally likely prices run 11..45; 18 of them are 28 or less.
		check("a price limit cuts the odds in proportion", Math.abs(Odds.priceChance(3, false, 1, 28) - 18 / 35.0) < 1e-9);
	}

	/** 1.0.0's settings (one level per book, one price range for all) carry over. */
	private static void checkUpgrade() {
		try {
			Path old = Files.createTempDirectory("tradeseeker-old").resolve("tradeseeker.json");
			Files.writeString(old, "{\"wanted\":{\"minecraft:mending\":1,\"minecraft:unbreaking\":3},\"minPrice\":5,\"maxPrice\":30,\"autoBuy\":true}");
			ModConfig keep = ModConfig.INSTANCE;
			ModConfig.useFile(old);
			ModConfig.load();
			Want w = ModConfig.INSTANCE.wanted.get("minecraft:unbreaking");
			check("1.0.0 settings carry over", w != null && w.minLevel == 3 && w.minPrice == 5 && w.maxPrice == 30 && ModConfig.INSTANCE.autoBuy);
			ModConfig.INSTANCE = keep;
			ModConfig.useFile(Files.createTempDirectory("tradeseeker").resolve("tradeseeker.json"));
		} catch (IOException e) {
			check("1.0.0 settings carry over (" + e + ")", false);
		}
	}

	private static void want(String id, int level, int min, int max) {
		ModConfig.INSTANCE.wanted.clear();
		ModConfig.INSTANCE.wanted.put(id, new Want(level, min, max));
	}

	private static void commonBooks() {
		ModConfig.INSTANCE.wanted.clear();
		for (String id : COMMON) ModConfig.INSTANCE.wanted.put(id, new Want(1, 1, 64));
	}

	/**
	 * A trading hall: pods side by side, sharing walls, facing an aisle at x=0. Each villager stands in a 1x1 glass cell
	 * with its lectern in front and glass above the lectern: with the lectern broken the gap is 1 high, so the villager
	 * can't get out. An open iron trapdoor across the pod's front keeps the drop from flying in.
	 */
	private static void build(MinecraftClient client) {
		log("Building the trading hall");
		command(client, "kill @e[type=!player]");
		command(client, "fill -3 -61 -3 5 -61 8 minecraft:stone");
		command(client, "fill -3 -60 -3 5 -56 8 minecraft:air");
		for (int z : PODS) {
			command(client, "fill 1 -60 " + (z - 1) + " 3 -58 " + (z + 1) + " minecraft:glass");
			command(client, "fill 2 -60 " + z + " 2 -59 " + z + " minecraft:air");
			command(client, "setblock 2 -60 " + z + " minecraft:iron_trapdoor[open=true,facing=east]");
			command(client, "setblock 1 -60 " + z + " minecraft:lectern");
			command(client, "summon minecraft:villager 2.7 -60 " + (z + 0.5) + " {VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}}");
		}
		// Right up against the lecterns (x=1), as you would stand to catch the drop.
		command(client, "tp @a 0.69 -60 1 -90 20");
		command(client, "clear @a");
		command(client, "give @a minecraft:diamond_axe");
		command(client, "give @a minecraft:emerald 320");
		command(client, "give @a minecraft:book 16");
		command(client, "give @a minecraft:lectern 4");
		command(client, "time set 1000");
	}

	private static void watchForEscape(MinecraftClient client) {
		for (Entity e : client.world.getEntities()) {
			if (!(e instanceof VillagerEntity v)) continue;
			int x = MathHelper.floor(v.getX()), z = MathHelper.floor(v.getZ());
			if (!escaped && (x != 2 || (z != A && z != B && z != C))) {
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

	/** How many such enchanted books the player holds (any, if no id is given). */
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

	private static int books(MinecraftClient client, net.minecraft.item.Item item) {
		var inventory = client.player.getInventory();
		int n = 0;
		for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(item)) n += inventory.getStack(i).getCount();
		return n;
	}

	private static int lecterns(MinecraftClient client) {
		return books(client, Items.LECTERN);
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
		client.getToastManager().clear();
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
