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
	/**
	 * The pods' z positions: A and B are the hall; C (buy as many), D (offhand lecterns, buying by hand) and E (a villager
	 * that can never take the job) are out of reach of each other.
	 */
	private static final int A = 0, B = 2, C = 6, D = 10, E = 14, G = 16, F = 18;
	private static final int[] PODS = {A, B, C, D};
	/** Common books, so a match turns up within a few re-rolls. */
	private static final String[] COMMON = {"minecraft:protection", "minecraft:sharpness", "minecraft:efficiency",
			"minecraft:unbreaking", "minecraft:power", "minecraft:fire_protection", "minecraft:smite", "minecraft:bane_of_arthropods"};

	private static boolean started, escaped, night;
	private static int rollsAsleep, hurtChecked;
	private static int tick = -1, phaseStart, passed, failed, booksBefore;
	private static String phase = "setup";
	private static long hallStart;
	private static int wantedBefore;
	private static String untickId;
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
		// Don't stop for the pause menu when the window loses focus (the mod rightly waits while a menu is open).
		client.options.pauseOnLostFocus = false;
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
		if (tick % 2000 == 0 && !night) command(client, "time set 1000");
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
				if (tick % 5 == 0 && tick < 245) client.getToastManager().clear();
				if (tick == 160) screenshot(client, "1_menu_all");
				if (tick == 162 && client.currentScreen instanceof SeekerScreen s) {
					s.testTab("PICKED");
					s.testFocus("minecraft:mending");
				}
				if (tick == 175) screenshot(client, "1b_menu_picked_mending");
				if (tick == 177 && client.currentScreen instanceof SeekerScreen s) s.testOptions(true);
				if (tick == 190) screenshot(client, "1c_menu_options");
				if (tick == 192 && client.currentScreen instanceof SeekerScreen s) s.testOptionsTab(1);
				if (tick == 225) screenshot(client, "1d_menu_options_sounds");
				if (tick == 228 && client.currentScreen instanceof SeekerScreen s) {
					s.testOptionsTab(0);
					s.testOptions(false);
				}
				// -Dtradeseeker.selftest.menu=true: just the menu screenshots, for quick looks at the layout.
				if (tick == 245) {
					client.setScreen(null);
					// Stop when hurt: start on a book no librarian sells, then take a little damage.
					want("minecraft:protection", 9, 1, 64);
					aimAt(client, villager(client, A));
					Reroller.start(client);
					check("re-rolling starts on the villager you look at", Reroller.running() && Reroller.target() == villager(client, A));
				}
				if (tick == 290) command(client, "damage @p 1 minecraft:generic");
				// The server may run the command a little late: give it up to 3 s.
				if (tick > 290 && tick <= 350 && hurtChecked == 0 && (!Reroller.running() || tick == 350)) {
					hurtChecked = tick;
					check("it stops when you get hurt", !Reroller.running());
					command(client, "effect give @p minecraft:instant_health 1 1");
				}
				if (tick == 370) {
					want("minecraft:protection", 9, 1, 64);
					config.maxRolls = 3;
					// The shortest wait for the job you can set.
					config.jobWait = 0.5;
					aimAt(client, villager(client, A));
					Reroller.start(client);
					next("limit");
				}
			}
			case "limit" -> {
				if (Reroller.running() && since < 6000) return;
				VillagerEntity v = villager(client, A);
				check("it stops at the re-roll limit, with a 0.5 s wait for the job (" + Reroller.rolls() + " of 3)", !Reroller.running() && Reroller.rolls() == 3);
				config.jobWait = 0;
				check("the lectern is back and the villager is a librarian again", client.world.getBlockState(lectern(A)).isOf(Blocks.LECTERN)
						&& v != null && isLibrarian(v));
				check("nothing was bought while re-rolling", xp(client, A) == 0 && books(client, null, 1) == 0);
				// (About 1 in 100 lands out of reach even with the trapdoor.)
				check("the broken lectern is picked back up (" + lecterns(client) + " of 16 spares left)", lecterns(client) >= 15);
				config.maxRolls = 0;
				// -Dtradeseeker.selftest.menu=true: the quick run ends here.
				if (Boolean.getBoolean("tradeseeker.selftest.menu")) {
					finish(client);
					return;
				}
				commonBooks();
				aimAt(client, villager(client, A));
				Reroller.start(client);
				next("rolling");
			}
			case "rolling" -> {
				// With auto-buy off it keeps running after a find: it waits for you to decide.
				if (Reroller.running() && !"DECIDE".equals(Reroller.stateName()) && since < 9000) return;
				found = Reroller.lastFound();
				check("a common book is found (" + (found == null ? "none" : found.describe().getString()) + ")", found != null);
				check("…and it waits for you to decide", "DECIDE".equals(Reroller.stateName()));
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
					// Closing without buying stops and keeps the trade.
					client.player.closeHandledScreen();
				}
				if (since == 26) check("closing the trade without buying stops and keeps it", !Reroller.running() && xp(client, A) == 0);
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
				next("buymax_cleanup");
			}
			case "buymax_cleanup" -> {
				// After the trade has closed on the server too, or these would race it.
				if (since != 20) return;
				// Hand back what C cost, for the hall.
				command(client, "clear @p minecraft:enchanted_book");
				command(client, "clear @p minecraft:emerald");
				command(client, "clear @p minecraft:book");
				command(client, "give @p minecraft:emerald 192");
				command(client, "give @p minecraft:book 8");
				config.buyMax = false;
				// Pod D: only offhand lecterns.
				command(client, "clear @p minecraft:lectern");
				command(client, "item replace entity @p weapon.offhand with minecraft:lectern 4");
				command(client, "tp @p 0.69 -60 10.5 -90 20");
				next("offhand_go");
			}
			case "offhand_go" -> {
				if (since != 30) return;
				var inv = client.player.getInventory();
				for (int i = 0; i < inv.size(); i++) {
					if (inv.getStack(i).isOf(Items.LECTERN)) log("lectern slot " + i + " x" + inv.getStack(i).getCount());
				}
				var serverInv = client.getServer().getPlayerManager().getPlayerList().get(0).getInventory();
				for (int i = 0; i < serverInv.size(); i++) {
					if (serverInv.getStack(i).isOf(Items.LECTERN)) log("server lectern slot " + i + " x" + serverInv.getStack(i).getCount());
				}
				var spares = Reroller.preflight(client).stream().filter(c -> c.label().getString().contains("spare")).findFirst();
				check("lecterns in the offhand count as spares (" + spares.map(c -> c.label().getString()).orElse("none") + ")",
						spares.isPresent() && spares.get().status() == Reroller.Status.OK && spares.get().label().getString().startsWith("4"));
				want("minecraft:protection", 9, 1, 64);
				config.autoBuy = false;
				config.maxRolls = 2;
				aimAt(client, villager(client, D));
				Reroller.start(client);
				next("offhand");
			}
			case "offhand" -> {
				if (Reroller.running() && since < 4000) return;
				check("re-rolls placing the lectern from the offhand (" + Reroller.rolls() + " of 2)", Reroller.rolls() == 2
						&& client.world.getBlockState(lectern(D)).isOf(Blocks.LECTERN) && client.player.getOffHandStack().isOf(Items.LECTERN));
				config.maxRolls = 0;
				commonBooks();
				aimAt(client, villager(client, D));
				Reroller.start(client);
				next("manual");
			}
			case "manual" -> {
				// Auto-buy off: when the book turns up, buy it the way you would, then close the trade.
				if (Reroller.running() && !"DECIDE".equals(Reroller.stateName()) && since < 9000) return;
				if (!(client.currentScreen instanceof MerchantScreen ms)) {
					check("a common book turns up for the hand-buying test", false);
					next("job_go");
					return;
				}
				var handler = ms.getScreenHandler();
				if (since % 10 != 0) return;
				if (handler.getSlot(2).hasStack()) {
					client.interactionManager.clickSlot(handler.syncId, 2, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, client.player);
					for (int i = 3; i < handler.slots.size(); i++) {
						if (!handler.getSlot(i).hasStack()) {
							client.interactionManager.clickSlot(handler.syncId, i, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, client.player);
							break;
						}
					}
					next("manual_close");
				}
			}
			case "manual_close" -> {
				if (since == 20) {
					check("buying by hand is noticed (" + Reroller.bought().size() + ")", Reroller.running() && Reroller.bought().size() == 1);
					String got = Reroller.idOf(Reroller.lastFound().enchantment());
					wantedBefore = config.wanted.size();
					untickId = got;
					client.player.closeHandledScreen();
				}
				if (since == 40) {
					// No other untraded villager is in reach of D, so it stops and says what's still wanted.
					check("after buying by hand and closing, it moves on (here: no villager left in reach, so it stops)",
							!Reroller.running() && xp(client, D) == 1);
					check("a book bought by hand is unticked too", !config.wanted.containsKey(untickId) && config.wanted.size() == wantedBefore - 1);
					command(client, "clear @p minecraft:enchanted_book");
					// Pod E: the villager stands a block above its lectern, so it can never take it.
					command(client, "tp @p 0.69 -60 14.5 -90 20");
					command(client, "fill 1 -60 13 3 -57 15 minecraft:glass");
					command(client, "fill 2 -59 14 2 -58 14 minecraft:air");
					command(client, "setblock 1 -60 14 minecraft:lectern");
					command(client, "summon minecraft:villager 2.5 -59 14.5 {VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}}");
					next("job_go");
				}
			}
			case "job_go" -> {
				if (since != 40) return;
				VillagerEntity e = villager(client, E);
				boolean warned = Reroller.preflight(client).stream().anyMatch(c -> c.status() == Reroller.Status.WARN
						&& c.detail().getString().contains("beside"));
				check("the checklist warns that a villager not right beside its lectern may never take it", warned);
				want("minecraft:protection", 9, 1, 64);
				config.jobWait = 5.5;
				aimAt(client, e);
				Reroller.start(client);
				next("job");
			}
			case "job" -> {
				if (since % 400 == 0) log("job: state " + Reroller.stateName() + ", tries " + Reroller.jobTries());
				if (Reroller.running() && since < 20 * 240) return;
				boolean named = Reroller.lastProblems.stream().anyMatch(t -> t.getString().contains("beside"));
				check("a villager that won't take the lectern gets it placed again, 8 times (" + Reroller.jobTries() + ")",
						!Reroller.running() && Reroller.jobTries() == 8);
				check("…then it says why (" + Reroller.lastProblems.size() + " reasons)", named);
				command(client, "kill @e[type=minecraft:villager,x=2,y=-61,z=14,dx=0,dy=4,dz=0]");
				command(client, "setblock 1 -60 14 minecraft:air");
				config.jobWait = 0;
				command(client, "clear @p minecraft:lectern");
				command(client, "give @p minecraft:lectern 16");
				command(client, "tp @p 0.69 -60 18.5 -90 20");
				next("night_go");
			}
			case "night_go" -> {
				if (since != 60) return;
				VillagerEntity f = villager(client, F);
				aimAt(client, f);
				boolean warned = Reroller.preflight(client).stream().anyMatch(c -> c.status() == Reroller.Status.WARN
						&& c.label().getString().contains("Bed"));
				check("the checklist warns about a bed the villager may sleep in at night", warned);
				want("minecraft:protection", 9, 1, 64);
				Reroller.start(client);
				next("night_fall");
			}
			case "night_fall" -> {
				if (since == 80) {
					// Dusk: F goes to its bed. G, next door, has none and stays up.
					night = true;
					command(client, "time set 13000");
				}
				if (since > 80 && villager(client, F) != null && villager(client, F).isSleeping()) {
					next("night_switch");
				} else if (since > 80 + 20 * 90) {
					check("the villager went to sleep at night (it didn't, so the night handling couldn't be tested)", false);
					night = false;
					command(client, "time set 1000");
					Reroller.stop(client, null);
					next("night_done");
				}
			}
			case "night_switch" -> {
				// It shouldn't sit out the night: the awake neighbour is re-rolled instead.
				if (since == 1) rollsAsleep = Reroller.rolls();
				if (since == 300) {
					check("when the villager goes to sleep, it re-rolls an awake one in reach instead (" + (Reroller.rolls() - rollsAsleep)
							+ " re-rolls on it)", Reroller.running() && Reroller.target() == villager(client, G) && Reroller.rolls() > rollsAsleep
							&& !Reroller.napping());
					Reroller.stop(client, null);
				}
				if (since == 320) {
					// Now only the sleeping one is left: it has to wait.
					command(client, "kill @e[type=minecraft:villager,x=2,y=-61,z=16,dx=0,dy=4,dz=0]");
					command(client, "setblock 1 -60 16 minecraft:air");
				}
				if (since == 360) {
					aimAt(client, villager(client, F));
					want("minecraft:protection", 9, 1, 64);
					Reroller.start(client);
					next("night_asleep");
				}
			}
			case "night_asleep" -> {
				if (since == 40) screenshot(client, "5_night_notice");
				if (since == 60) rollsAsleep = Reroller.rolls();
				if (since == 200) {
					check("with only a sleeping villager, it waits (" + (Reroller.rolls() - rollsAsleep) + " re-rolls, lectern "
							+ (client.world.getBlockState(lectern(F)).isOf(Blocks.LECTERN) ? "in place" : "missing") + ")",
							Reroller.running() && Reroller.napping() && Reroller.rolls() == rollsAsleep
									&& client.world.getBlockState(lectern(F)).isOf(Blocks.LECTERN));
					check("a notice is shown on screen", Reroller.notice(client) != null);
					// Morning: it should wake and carry on by itself.
					command(client, "time set 1000");
					night = false;
				}
				if (since > 200 && !Reroller.napping() && Reroller.rolls() > rollsAsleep + 1) {
					check("at sunrise it wakes and carries on by itself (" + (Reroller.rolls() - rollsAsleep) + " more re-rolls)", Reroller.running());
					Reroller.stop(client, null);
					next("night_done");
				} else if (since > 200 + 20 * 60) {
					check("at sunrise it wakes and carries on by itself (napping " + Reroller.napping() + ", running " + Reroller.running() + ")", false);
					Reroller.stop(client, null);
					next("night_done");
				}
			}
			case "night_done" -> {
				if (since == 20) command(client, "tp @p 0.69 -60 1 -90 20");
				if (since == 50) next("hall_go");
			}
			case "hall_go" -> {
				if (since != 30) return;
				command(client, "kill @e[type=minecraft:villager,x=2,y=-61,z=17,dx=0,dy=4,dz=4]");
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
					check("books you got are unticked on the wanted list (" + config.wanted.keySet() + ")",
							!config.wanted.containsKey("minecraft:mending") && !config.wanted.containsKey("minecraft:unbreaking"));
					check("both villagers were traded with (" + xp(client, A) + ", " + xp(client, B) + " xp)", xp(client, A) > 0 && xp(client, B) > 0);
					check("no villager escaped its pod", !escaped && villager(client, A) != null && villager(client, B) != null);
					// With the trapdoor, a lectern is rarely lost (about 1 in 50 re-rolls); without it, about 1 in 2.
					int used = 16 - lecterns(client);
					check("lecterns are hardly ever lost (" + used + " used in " + Reroller.rolls() + " re-rolls)", used * 20 <= Math.max(Reroller.rolls(), 20));
					long withBook = Reroller.seen().stream().filter(s -> !s.books().isEmpty()).count();
					int n = Reroller.seen().size();
					check("about 2 in 3 re-rolls offer a book, as the odds assume (" + withBook + " of " + n + ")",
							n < 30 || Math.abs(withBook / (double) n - 2 / 3.0) < 0.15);
					// Every wanted book that turned up at too low a level (Unbreaking I or II) must be marked a near miss.
					long lowUnbreaking = Reroller.seen().stream().filter(s -> s.books().stream().anyMatch(b ->
							"minecraft:unbreaking".equals(Reroller.idOf(b.enchantment())) && b.level() < 3)).count();
					long flagged = Reroller.seen().stream().filter(s -> s.kind() == Reroller.Kind.NEAR).count();
					check("near misses are noted in the Seen list (" + flagged + " of " + lowUnbreaking + ")", flagged == lowUnbreaking);
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
					check("Auto learned how long villagers take the job (waits " + Reroller.jobWait() + " s, from " + ModConfig.INSTANCE.jobTakeTimes.size()
							+ " takes)", ModConfig.INSTANCE.jobTakeTimes.size() >= 5 && Reroller.jobWait() >= 1.5 && Reroller.jobWait() < 5.5);
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
		command(client, "fill -3 -61 -3 5 -61 23 minecraft:stone");
		command(client, "fill -3 -60 -3 5 -56 23 minecraft:air");
		// Pod G, next to F: an ordinary pod with no bed, so its villager stays up at night.
		command(client, "fill 1 -60 15 3 -58 17 minecraft:glass");
		command(client, "fill 2 -60 16 2 -59 16 minecraft:air");
		command(client, "setblock 2 -60 16 minecraft:iron_trapdoor[open=true,facing=east]");
		command(client, "setblock 1 -60 16 minecraft:lectern");
		command(client, "summon minecraft:villager 2.7 -60 16.5 {VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}}");
		// Pod F: like the others, but with a bed behind the villager inside the pod, so it sleeps there at night.
		command(client, "fill 1 -60 17 3 -58 21 minecraft:glass");
		command(client, "fill 2 -60 18 2 -59 20 minecraft:air");
		command(client, "setblock 2 -60 18 minecraft:iron_trapdoor[open=true,facing=east]");
		command(client, "setblock 2 -60 19 minecraft:red_bed[facing=north,part=head]");
		command(client, "setblock 2 -60 20 minecraft:red_bed[facing=north,part=foot]");
		command(client, "setblock 1 -60 18 minecraft:lectern");
		command(client, "summon minecraft:villager 2.7 -60 18.5 {VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}}");
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
		command(client, "give @a minecraft:lectern 16");
		command(client, "time set 1000");
	}

	private static void watchForEscape(MinecraftClient client) {
		for (Entity e : client.world.getEntities()) {
			if (!(e instanceof VillagerEntity v)) continue;
			int x = MathHelper.floor(v.getX()), z = MathHelper.floor(v.getZ());
			if (!escaped && (x != 2 || (z != A && z != B && z != C && z != D && z != E && z != G && (z < F || z > F + 2)))) {
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
		for (Entity e : client.world.getEntities()) {
			if (!(e instanceof VillagerEntity v)) continue;
			int z = MathHelper.floor(v.getZ());
			// F's villager may be lying on its bed, further back in the pod.
			if (z == pod || (pod == F && z > F && z <= F + 2)) return v;
		}
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
