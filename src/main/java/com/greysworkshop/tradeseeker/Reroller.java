package com.greysworkshop.tradeseeker;

import com.greysworkshop.tradeseeker.mixin.MerchantScreenAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Re-rolls a librarian: opens its trades, and while no wanted book is offered, breaks its lectern, waits for it to
 * lose the job, puts the lectern back and waits for it to take the job again (which gives it new trades).
 * A villager's trades only lock once someone trades with it, so it never trades unless told to.
 */
public final class Reroller {
	private enum State { OPEN, AWAIT_OFFERS, STEP, BREAK, WAIT_JOBLESS, PLACE, CONFIRM_PLACE, WAIT_JOB, BUY, DECIDE, WAIT_DAY }

	/**
	 * One enchanted book a librarian offers. price is what it settles at for good (what counts for a find); now is what
	 * the trade screen shows, which can be lower for a while after a cure or with Hero of the Village.
	 */
	public record Book(int index, RegistryEntry<Enchantment> enchantment, int level, int price, int now) {
		public Text describe() {
			Text text = Text.empty().append(Enchantment.getName(enchantment, level)).append(Text.literal(" · " + price + " emeralds"));
			return now < price ? text.copy().append(Text.literal(" (" + now + " for now)")) : text;
		}
	}

	/**
	 * What curing a villager takes off a book for good. The cure gives the curer a lasting +100 reputation and a fading
	 * +25 (gone in about 25 days), and a book costs 0.2 × reputation less: 25 at first, then 20 for good.
	 */
	public static final int CURED_DISCOUNT = 20;
	/** How many cured villagers the config remembers; the oldest go first. */
	private static final int CURED_KEPT = 500;

	public enum Kind { MATCH, NEAR, OTHER, NO_BOOK }

	/** What one re-roll offered, for the menu's Seen list. */
	public record Seen(int roll, List<Book> books, Kind kind, Text note) {}

	public enum Status { OK, WARN, BAD }

	/** One line of the menu's ready-to-start checklist. */
	public record Check(Status status, Text label, Text detail) {}

	private static State state;
	private static VillagerEntity villager;
	private static BlockPos lectern;
	private static int ticksInState, delay, attempts, rolls;
	private static long startedAt;
	private static Book found;
	private static Set<UUID> neighbours = Set.of();
	private static boolean stepping;
	/** At least this many placements, and at least this long in all, before giving up on a villager taking the lectern. */
	private static final int MIN_JOB_TRIES = 8;
	private static final double JOB_GIVE_UP_SECONDS = 40;
	/** Lectern placements while waiting for the job; the next one is a retry, not a new re-roll. */
	private static int jobTries;
	private static boolean retrying;
	/** Waiting for a sleeping villager to wake; and whether this run has said it's night already. */
	private static boolean napping, nightSaid;
	private static int nightAwake, napTicks;
	/** The server wouldn't let an awake villager trade at night: wait for day, then carry on. */
	private static boolean nightBlocked;
	/** Why the last run's villager wouldn't take the lectern (for the self-test). */
	static List<Text> lastProblems = List.of();
	/** Dropped lecterns (entity ids) that landed out of pickup reach this run. */
	private static Set<Integer> lostDrops = new HashSet<>();
	/** This run's wanted books; one is crossed off when it's bought, and the rest are sought from the next villager. */
	private static Map<String, ModConfig.Want> remaining = new LinkedHashMap<>();
	/** Newest first, this run only. */
	private static final List<Seen> seen = new ArrayList<>();
	private static final List<Book> bought = new ArrayList<>();
	private static int boughtHere, booksAtFind;
	private static float lastHealth;
	private static int lastHurtTime;
	private static long endedAt;
	/** Villagers this run has finished with (bought from, or found already traded). */
	private static Set<UUID> done = new HashSet<>();
	/**
	 * Each villager's lectern spot. If a run stops with the lectern broken, the villager is left jobless and the
	 * nearest lectern may be a neighbour's; this puts it back where it was instead.
	 */
	private static final Map<UUID, BlockPos> homes = new java.util.HashMap<>();
	/** What the last roll offered, for the status line. */
	private static Text lastSeen = Text.empty();

	private Reroller() {}

	public static boolean running() {
		return state != null;
	}

	public static int rolls() {
		return rolls;
	}

	// ---- Starting and stopping ----

	public static void start(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) return;
		ModConfig config = ModConfig.INSTANCE;
		if (config.wanted.isEmpty()) {
			chat(player, Text.translatable("tradeseeker.msg.nothing_wanted").formatted(Formatting.RED));
			return;
		}
		VillagerEntity target = findVillager(client);
		if (target == null) {
			chat(player, Text.translatable("tradeseeker.msg.no_villager").formatted(Formatting.RED));
			return;
		}
		if (target.getVillagerData().profession().matchesKey(VillagerProfession.NITWIT)) {
			chat(player, Text.translatable("tradeseeker.msg.nitwit").formatted(Formatting.RED));
			return;
		}
		if (target.getVillagerData().level() > 1) {
			chat(player, Text.translatable("tradeseeker.msg.locked").formatted(Formatting.RED));
			return;
		}
		BlockPos pos = lecternFor(client, target, true);
		if (pos == null) {
			chat(player, Text.translatable("tradeseeker.msg.no_lectern").formatted(Formatting.RED));
			return;
		}
		remaining = new LinkedHashMap<>();
		config.wanted.forEach((id, w) -> remaining.put(id, w.copy()));
		boolean asleep = target.isSleeping();
		done = new HashSet<>();
		lostDrops = new HashSet<>();
		napping = false;
		nightSaid = false;
		nightAwake = 0;
		napTicks = 0;
		seen.clear();
		bought.clear();
		rolls = 0;
		startedAt = System.currentTimeMillis();
		endedAt = 0;
		lastHealth = player.getHealth();
		lastHurtTime = player.hurtTime;
		begin(client, target, pos);
		chat(player, Text.translatable("tradeseeker.msg.started", pos.toShortString()).formatted(Formatting.GREEN));
		// The one you picked is asleep: an awake one in reach saves waiting for sunrise.
		if (asleep && nextVillager(client, true)) {
			chat(player, Text.translatable("tradeseeker.msg.switch_awake", villager.getBlockPos().toShortString()).formatted(Formatting.AQUA));
		}
	}

	/** Whether all of the lectern's spot is within item pickup reach, so the broken lectern comes back to you. */
	private static boolean canCatchDrop(ClientPlayerEntity player, BlockPos pos) {
		Box reach = player.getBoundingBox().expand(1, 0.5, 1);
		// A broken lectern's drop can fly to any part of its spot (walls keep it in), and is caught once its box
		// (1/8 block either side of its middle) touches the reach: so all of the spot, less that 1/8, must be in reach.
		Box spot = new Box(pos).contract(0.125);
		return reach.minX <= spot.minX && reach.maxX >= spot.maxX && reach.minZ <= spot.minZ && reach.maxZ >= spot.maxZ
				&& reach.minY <= spot.minY + 0.5;
	}

	private static void begin(MinecraftClient client, VillagerEntity target, BlockPos pos) {
		villager = target;
		lectern = pos;
		homes.put(target.getUuid(), pos);
		found = null;
		lastSeen = Text.empty();
		neighbours = joblessNeighbours(client);
		jobTries = 0;
		retrying = false;
		enter(isLibrarian(target) ? State.OPEN : State.WAIT_JOB);
	}

	/**
	 * Moves on to the nearest villager in reach that this run hasn't finished with, as in a trading hall.
	 * Returns false when there's none.
	 */
	private static boolean nextVillager(MinecraftClient client) {
		return nextVillager(client, false);
	}

	/** Awake villagers come first (a sleeping one can't trade); with awakeOnly, sleeping ones are skipped. */
	private static boolean nextVillager(MinecraftClient client, boolean awakeOnly) {
		ClientPlayerEntity player = client.player;
		VillagerEntity best = null;
		BlockPos bestLectern = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof VillagerEntity v) || v.isBaby() || done.contains(v.getUuid()) || v == villager) continue;
			if ((!isLibrarian(v) && !isJobless(v)) || v.getVillagerData().level() > 1 || !player.canInteractWithEntity(v, 0.5)) continue;
			if (awakeOnly && v.isSleeping()) continue;
			BlockPos pos = lecternFor(client, v, false);
			// A jobless one that isn't right beside its lectern would never take it.
			if (pos != null && isJobless(v) && v.getBlockPos().getManhattanDistance(pos) != 1) continue;
			// A sleeping villager counts as much further away, so an awake one is picked when there is one.
			double d = v.squaredDistanceTo(player) + (v.isSleeping() ? 10_000 : 0);
			if (pos != null && d < bestDistance) {
				best = v;
				bestLectern = pos;
				bestDistance = d;
			}
		}
		if (best == null) return false;
		begin(client, best, bestLectern);
		return true;
	}

	private static Text remainingText() {
		var text = Text.empty();
		var registry = MinecraftClient.getInstance().world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
		boolean first = true;
		for (var e : remaining.entrySet()) {
			if (!first) text.append(", ");
			first = false;
			var entry = registry.getEntry(net.minecraft.util.Identifier.of(e.getKey()));
			text.append(entry.isPresent() ? Enchantment.getName(entry.get(), e.getValue().minLevel) : Text.literal(e.getKey()));
		}
		return text;
	}

	public static void stop(MinecraftClient client, Text why) {
		if (state == null) return;
		releaseKeys(client);
		if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		state = null;
		villager = null;
		endedAt = System.currentTimeMillis();
		learnPace();
		if (client.player != null && why != null) chat(client.player, why);
	}

	/** Keeps the time estimates honest: blends this run's seconds per re-roll into the saved figure. */
	private static void learnPace() {
		if (rolls < 5) return;
		double measured = (endedAt - startedAt) / 1000.0 / rolls;
		if (measured < 0.5 || measured > 60) return;
		ModConfig config = ModConfig.INSTANCE;
		config.secondsPerRoll = Math.round((config.secondsPerRoll * 0.5 + measured * 0.5) * 100) / 100.0;
		ModConfig.save();
	}

	private static void fail(MinecraftClient client, String key, Object... args) {
		if (client.player != null && client.currentScreen instanceof MerchantScreen) client.player.closeHandledScreen();
		stop(client, Text.translatable(key, args).formatted(Formatting.RED));
		ding(client, false);
	}

	private static void enter(State next) {
		state = next;
		ticksInState = 0;
		delay = ModConfig.INSTANCE.speed.delay;
	}

	// ---- The loop ----

	public static void tick(MinecraftClient client) {
		if (state == null) return;
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.interactionManager == null) {
			state = null;
			villager = null;
			return;
		}
		// Chat, the pause menu, the inventory: wait until it's closed. The trade screen is ours.
		if (client.currentScreen != null && !(client.currentScreen instanceof MerchantScreen)) return;
		// Hurt: a drop in health, or the red flinch (which shows even when health regenerates before it's sent).
		boolean flinched = player.hurtTime > 0 && lastHurtTime == 0;
		lastHurtTime = player.hurtTime;
		if (ModConfig.INSTANCE.stopWhenHurt && (player.getHealth() < lastHealth - 0.01f || flinched)) {
			fail(client, "tradeseeker.msg.hurt");
			return;
		}
		lastHealth = player.getHealth();
		if (villager.isRemoved() || !villager.isAlive()) {
			fail(client, "tradeseeker.msg.villager_gone");
			return;
		}
		if (!player.canInteractWithBlockAt(lectern, 0.5)) {
			fail(client, "tradeseeker.msg.too_far");
			return;
		}
		if (sleepCheck(client, player)) return;
		if (delay > 0) {
			delay--;
			return;
		}
		ticksInState++;
		switch (state) {
			case OPEN -> open(client, player);
			case AWAIT_OFFERS -> awaitOffers(client, player);
			case STEP -> step(client, player);
			case BREAK -> breakLectern(client, player);
			case WAIT_JOBLESS -> waitJobless(client, player);
			case PLACE -> place(client, player);
			case CONFIRM_PLACE -> confirmPlace(client, player);
			case WAIT_JOB -> waitJob(client, player);
			case BUY -> buy(client, player);
			case DECIDE -> decide(client, player);
			case WAIT_DAY -> waitDay(client, player);
		}
	}

	/**
	 * Villagers trade, and take jobs, at any hour; the one thing night changes is that a villager with a bed it can
	 * reach goes to sleep in it, from dusk to sunrise, and a sleeping villager won't trade. So while it sleeps,
	 * everything waits (it never breaks the lectern on a sleeping villager), with a notice on screen, and carries on
	 * by itself when it wakes. Returns true while it's waiting.
	 */
	private static boolean sleepCheck(MinecraftClient client, ClientPlayerEntity player) {
		// Putting a broken lectern back doesn't involve the villager, so that still happens: it never sits out the night
		// with an empty spot. Trading, breaking and waiting for the job all wait.
		if (state == State.DECIDE || state == State.BUY || state == State.WAIT_JOBLESS || state == State.PLACE || state == State.CONFIRM_PLACE) {
			return false;
		}
		// The lectern is gone (it broke just as the villager fell asleep): put it back first, then wait.
		if (!client.world.getBlockState(lectern).isOf(Blocks.LECTERN)) {
			if (state == State.BREAK || state == State.STEP) enter(State.WAIT_JOBLESS);
			return false;
		}
		if (villager.isSleeping()) {
			// Don't sit out the night: re-roll an awake villager in reach instead, when there is one (checked now and
			// every few seconds while waiting).
			if ((!napping || ++napTicks % 100 == 0) && nextVillager(client, true)) {
				napping = false;
				chat(player, Text.translatable("tradeseeker.msg.switch_awake", villager.getBlockPos().toShortString()).formatted(Formatting.AQUA));
				TradeSeekerClient.LOGGER.info("Villager asleep; switched to the awake one at {}", villager.getBlockPos().toShortString());
				return true;
			}
			if (!napping) {
				napping = true;
				napTicks = 0;
				releaseKeys(client);
				client.interactionManager.cancelBlockBreaking();
				if (client.currentScreen instanceof MerchantScreen) player.closeHandledScreen();
				chat(player, Text.translatable("tradeseeker.msg.asleep", untilMorning(client)).formatted(Formatting.GOLD));
				TradeSeekerClient.LOGGER.info("Villager asleep at time {}; waiting for it to wake", client.world.getTimeOfDay() % 24000);
			}
			status(player, Text.translatable("tradeseeker.status.asleep_trade", untilMorning(client)));
			return true;
		}
		if (napping) {
			napping = false;
			ticksInState = 0;
			delay = 20;
			Sounds.morning();
			chat(player, Text.translatable("tradeseeker.msg.awake").formatted(Formatting.GREEN));
			TradeSeekerClient.LOGGER.info("Villager awake; carrying on");
		}
		// Still awake well into the night: say so once, so a quiet night doesn't look like it stalled.
		nightAwake = isNight(client) ? nightAwake + 1 : 0;
		if (!nightSaid && nightAwake > 600) {
			nightSaid = true;
			BlockPos bed = nearbyBed(client, villager);
			chat(player, (bed == null ? Text.translatable("tradeseeker.msg.night_awake")
					: Text.translatable("tradeseeker.msg.night_awake_bed", bed.toShortString())).formatted(Formatting.GRAY));
		}
		return false;
	}

	/** What villagers do at a time of day (vanilla schedule), as a translation key suffix. */
	static String activityAt(long time) {
		time %= 24000;
		if (time < 2000) return "idle";
		if (time < 9000) return "work";
		if (time < 11000) return "meet";
		if (time < 12000) return "idle";
		return "rest";
	}

	/** A day time as a clock (time 0 is 6:00 in the morning). */
	static String clock(long time) {
		time %= 24000;
		long hours = (time / 1000 + 6) % 24, minutes = time % 1000 * 60 / 1000;
		return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, minutes);
	}

	static boolean nightBlocked() {
		return nightBlocked;
	}

	/** Villagers' rest time: dusk (12000) to sunrise (24000, the start of the next day). */
	static boolean isNight(MinecraftClient client) {
		return client.world.getTimeOfDay() % 24000 >= 12000;
	}

	/** The notice drawn on screen while it waits for a sleeping villager, or null. */
	public static List<Text> notice(MinecraftClient client) {
		if (state == State.WAIT_DAY && client.world != null) {
			return List.of(Text.translatable("tradeseeker.notice.blocked_title"), Text.translatable("tradeseeker.notice.blocked_line", untilMorning(client)),
					Text.translatable("tradeseeker.notice.blocked_tip"));
		}
		if (state == null || !napping || client.world == null) return null;
		return List.of(Text.translatable("tradeseeker.notice.title"), Text.translatable("tradeseeker.notice.line", untilMorning(client)),
				Text.translatable("tradeseeker.notice.tip"));
	}

	/** The nearest bed within 16 blocks of the villager (it may claim one and sleep in it at night), or null. */
	static BlockPos nearbyBed(MinecraftClient client, VillagerEntity v) {
		BlockPos at = v.getBlockPos(), best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(at.add(-16, -4, -16), at.add(16, 4, 16))) {
			if (!client.world.getBlockState(pos).isIn(net.minecraft.registry.tag.BlockTags.BEDS)) continue;
			double d = pos.getSquaredDistance(at);
			if (d < bestDistance) {
				best = pos.toImmutable();
				bestDistance = d;
			}
		}
		return best;
	}

	private static void open(MinecraftClient client, ClientPlayerEntity player) {
		if (!isLibrarian(villager)) {
			enter(isJobless(villager) ? State.WAIT_JOB : State.BREAK);
			return;
		}
		if (!player.canInteractWithEntity(villager, 0.5)) {
			fail(client, "tradeseeker.msg.too_far_villager");
			return;
		}
		lookAt(player, villager.getEyePos().subtract(0, 0.2, 0));
		Vec3d at = villager.getBoundingBox().getCenter();
		client.interactionManager.interactEntityAtLocation(player, villager, new EntityHitResult(villager, at), Hand.MAIN_HAND);
		client.interactionManager.interactEntity(player, villager, Hand.MAIN_HAND);
		player.swingHand(Hand.MAIN_HAND);
		enter(State.AWAIT_OFFERS);
		delay = 0;
	}

	private static void waitDay(MinecraftClient client, ClientPlayerEntity player) {
		if (isNight(client)) {
			status(player, Text.translatable("tradeseeker.status.night_blocked", untilMorning(client)));
			return;
		}
		Sounds.morning();
		chat(player, Text.translatable("tradeseeker.msg.day_again").formatted(Formatting.GREEN));
		enter(State.OPEN);
	}

	private static void awaitOffers(MinecraftClient client, ClientPlayerEntity player) {
		if (client.currentScreen instanceof MerchantScreen screen && !screen.getScreenHandler().getRecipes().isEmpty()) {
			attempts = 0;
			check(client, player, screen);
			return;
		}
		if (ticksInState > 60) {
			if (client.currentScreen instanceof MerchantScreen) player.closeHandledScreen();
			if (++attempts >= 3) {
				if (isNight(client) && !villager.isSleeping()) {
					// Awake, yet no trades at night: this server must not allow night trading. Another villager won't
					// do better, so wait for sunrise and carry on then.
					attempts = 0;
					nightBlocked = true;
					chat(player, Text.translatable("tradeseeker.msg.night_blocked", untilMorning(client)).formatted(Formatting.GOLD));
					enter(State.WAIT_DAY);
					return;
				}
				fail(client, "tradeseeker.msg.no_trades");
				return;
			}
			enter(State.OPEN);
		}
	}

	private static void check(MinecraftClient client, ClientPlayerEntity player, MerchantScreen screen) {
		MerchantScreenHandler handler = screen.getScreenHandler();
		if (handler.getExperience() > 0 || villager.getVillagerData().level() > 1) {
			// Partway through a hall, a traded villager is just skipped.
			if (!done.isEmpty()) {
				done.add(villager.getUuid());
				player.closeHandledScreen();
				if (nextVillager(client)) return;
			}
			fail(client, "tradeseeker.msg.locked");
			return;
		}
		if (remaining.isEmpty()) {
			player.closeHandledScreen();
			stop(client, Text.translatable("tradeseeker.msg.nothing_left").formatted(Formatting.YELLOW));
			return;
		}
		learnCured(villager, handler.getRecipes(), player);
		List<Book> books = books(handler.getRecipes(), discountFor(villager));
		Book match = firstWanted(books);
		lastSeen = describeAll(books);
		TradeSeekerClient.LOGGER.info("Re-roll {}: {}", rolls, lastSeen.getString());
		record(player, books, match);
		if (match != null) {
			found(client, player, screen, match);
			return;
		}
		player.closeHandledScreen();
		int max = ModConfig.INSTANCE.maxRolls;
		if (max > 0 && rolls >= max) {
			stop(client, Text.translatable("tradeseeker.msg.max_rolls", rolls).formatted(Formatting.YELLOW));
			ding(client, false);
			return;
		}
		status(player, Text.translatable("tradeseeker.status.offer").append(lastSeen.copy().formatted(Formatting.GRAY)));
		enter(State.STEP);
	}

	private static void record(ClientPlayerEntity player, List<Book> books, Book match) {
		Kind kind = books.isEmpty() ? Kind.NO_BOOK : Kind.OTHER;
		Text note = Text.empty();
		if (match != null) {
			kind = Kind.MATCH;
		} else {
			for (Book book : books) {
				ModConfig.Want w = remaining.get(idOf(book.enchantment()));
				if (w == null) continue;
				kind = Kind.NEAR;
				note = book.level() < w.minLevel
						? Text.translatable("tradeseeker.near.level", SeekerScreen.roman(book.level()), SeekerScreen.roman(w.minLevel))
						: book.price() > w.maxPrice
								? Text.translatable("tradeseeker.near.dear", book.price(), w.maxPrice)
								: Text.translatable("tradeseeker.near.cheap", book.price(), w.minPrice);
				if (ModConfig.INSTANCE.nearMisses) {
					chat(player, Text.translatable("tradeseeker.msg.near_miss", Enchantment.getName(book.enchantment(), book.level()), note)
							.formatted(Formatting.GOLD));
				}
				break;
			}
		}
		seen.add(0, new Seen(rolls, books, kind, note));
		if (seen.size() > 200) seen.remove(seen.size() - 1);
	}

	private static void found(MinecraftClient client, ClientPlayerEntity player, MerchantScreen screen, Book book) {
		found = book;
		select(client, screen, book.index());
		long seconds = (System.currentTimeMillis() - startedAt) / 1000;
		chat(player, Text.translatable("tradeseeker.msg.found", book.describe(), rolls, seconds / 60, seconds % 60)
				.formatted(Formatting.GREEN, Formatting.BOLD));
		ding(client, true);
		SystemToast.show(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
				Text.translatable("tradeseeker.toast.found"), book.describe());
		boughtHere = 0;
		if (ModConfig.INSTANCE.autoBuy) {
			enter(State.BUY);
		} else {
			booksAtFind = count(player.getInventory(), Items.ENCHANTED_BOOK);
			chat(player, Text.translatable("tradeseeker.msg.your_call").formatted(Formatting.YELLOW));
			enter(State.DECIDE);
			delay = 0;
		}
	}

	/**
	 * Auto-buy off: the trade is open and it's your call. Buying is noticed (the trade's use count, or the book turning
	 * up in your inventory); once you close the trade it carries on with the next villager. Closing without buying
	 * stops and leaves the trade as it is, so a stray Esc can't throw a good book away.
	 */
	private static void decide(MinecraftClient client, ClientPlayerEntity player) {
		delay = 0;
		if (client.currentScreen instanceof MerchantScreen screen) {
			TradeOfferList offers = screen.getScreenHandler().getRecipes();
			int uses = found.index() < offers.size() ? offers.get(found.index()).getUses() : 0;
			int gained = count(player.getInventory(), Items.ENCHANTED_BOOK) - booksAtFind;
			int now = Math.max(uses, gained);
			if (now > boughtHere) {
				for (int i = boughtHere; i < now; i++) bought.add(found);
				boughtHere = now;
				Sounds.bought();
				status(player, Text.translatable("tradeseeker.status.bought_close", boughtHere));
			} else if (boughtHere == 0 && ModConfig.INSTANCE.remind && ticksInState % 200 == 0) {
				// Away from the keyboard? Say it again every 10 seconds until you act.
				Sounds.found(true);
			}
			return;
		}
		if (boughtHere > 0) {
			finishBuying(client, player);
		} else {
			stop(client, Text.translatable("tradeseeker.msg.left_it").formatted(Formatting.YELLOW));
		}
	}

	/**
	 * Buys the found book: exactly one, or with "buy as many as I can afford", one at a time (a shift-click only spends
	 * the stack in the payment slot), refilling the payment from the inventory, until something runs out.
	 */
	private static void buy(MinecraftClient client, ClientPlayerEntity player) {
		if (!(client.currentScreen instanceof MerchantScreen screen)) {
			if (boughtHere > 0) finishBuying(client, player);
			else fail(client, "tradeseeker.msg.buy_closed");
			return;
		}
		MerchantScreenHandler handler = screen.getScreenHandler();
		ItemStack result = handler.getSlot(2).getStack();
		int free = freeSlot(handler);
		if (result.isEmpty() || free < 0) {
			if (boughtHere > 0) {
				finishBuying(client, player);
			} else {
				// Nothing in the result slot: the inventory hasn't got the emeralds and book for it.
				stop(client, Text.translatable(free < 0 ? "tradeseeker.msg.inventory_full" : "tradeseeker.msg.cant_afford").formatted(Formatting.YELLOW));
			}
			return;
		}
		// A plain click takes exactly one; then it goes in a free slot (books don't stack).
		client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.PICKUP, player);
		client.interactionManager.clickSlot(handler.syncId, free, 0, SlotActionType.PICKUP, player);
		boughtHere++;
		bought.add(found);
		logBought(found);
		if (!ModConfig.INSTANCE.buyMax) {
			finishBuying(client, player);
			return;
		}
		status(player, Text.translatable("tradeseeker.status.buying", boughtHere, found.describe()));
		// Refill the payment slots from the inventory, as clicking the trade again would.
		select(client, screen, found.index());
		delay = Math.max(2, ModConfig.INSTANCE.speed.delay);
	}

	private static int freeSlot(MerchantScreenHandler handler) {
		for (int i = 3; i < handler.slots.size(); i++) if (!handler.getSlot(i).hasStack()) return i;
		return -1;
	}

	private static void finishBuying(MinecraftClient client, ClientPlayerEntity player) {
		Text what = boughtHere > 1 ? Text.translatable("tradeseeker.msg.bought_many", boughtHere, found.describe()) : found.describe();
		chat(player, Text.translatable("tradeseeker.msg.bought", what).formatted(Formatting.GREEN));
		String id = idOf(found.enchantment());
		remaining.remove(id);
		// Got it: off the wanted list for good, so it's never rolled for again.
		if (ModConfig.INSTANCE.untickBought && ModConfig.INSTANCE.wanted.remove(id) != null) {
			ModConfig.save();
			chat(player, Text.translatable("tradeseeker.msg.unticked", Enchantment.getName(found.enchantment(), found.level())).formatted(Formatting.GRAY));
		}
		done.add(villager.getUuid());
		if (remaining.isEmpty()) {
			Sounds.allDone();
			stop(client, Text.translatable("tradeseeker.msg.all_done").formatted(Formatting.GREEN, Formatting.BOLD));
			return;
		}
		// (Bought by hand, the purchase sound has played already.)
		if (ModConfig.INSTANCE.autoBuy) Sounds.bought();
		if (client.currentScreen instanceof MerchantScreen) player.closeHandledScreen();
		if (nextVillager(client)) {
			chat(player, Text.translatable("tradeseeker.msg.next_villager", remainingText()).formatted(Formatting.AQUA));
		} else {
			stop(client, Text.translatable("tradeseeker.msg.no_next_villager", remainingText()).formatted(Formatting.YELLOW));
		}
	}

	private static void logBought(Book book) {
		TradeSeekerClient.LOGGER.info("Bought {} for {} emeralds after {} re-rolls", book.describe().getString(), book.price(), rolls);
	}

	/** Shows the trade as picked and fills the payment slots from the inventory, as clicking it would. Buys nothing. */
	private static void select(MinecraftClient client, MerchantScreen screen, int index) {
		MerchantScreenAccessor accessor = (MerchantScreenAccessor) screen;
		accessor.tradeseeker$setSelectedIndex(index);
		accessor.tradeseeker$setIndexStartOffset(Math.max(0, Math.min(index, screen.getScreenHandler().getRecipes().size() - 7)));
		MerchantScreenHandler handler = screen.getScreenHandler();
		handler.setRecipeIndex(index);
		handler.switchTo(index);
		client.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(index));
	}

	/**
	 * Before breaking, sneaks up against the lectern if its drop could land out of pickup reach (in a hall, after
	 * moving on to the next pod). Sneaking can't walk off an edge; walls just stop it, and then it breaks from there.
	 */
	private static void step(MinecraftClient client, ClientPlayerEntity player) {
		if (canCatchDrop(player, lectern) || ticksInState > 60) {
			releaseKeys(client);
			enter(State.BREAK);
			delay = 2;
			return;
		}
		Vec3d d = Vec3d.ofCenter(lectern).subtract(player.getEntityPos());
		player.setYaw((float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90);
		client.options.sneakKey.setPressed(true);
		client.options.forwardKey.setPressed(true);
		stepping = true;
		delay = 0;
	}

	private static void releaseKeys(MinecraftClient client) {
		if (!stepping) return;
		stepping = false;
		client.options.forwardKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
	}

	private static void breakLectern(MinecraftClient client, ClientPlayerEntity player) {
		BlockState block = client.world.getBlockState(lectern);
		if (!block.isOf(Blocks.LECTERN)) {
			client.interactionManager.cancelBlockBreaking();
			enter(State.WAIT_JOBLESS);
			return;
		}
		if (ticksInState > 400) {
			fail(client, "tradeseeker.msg.cant_break");
			return;
		}
		if (ticksInState == 1) pickTool(player, block);
		Vec3d center = Vec3d.ofCenter(lectern);
		Direction side = Direction.getFacing(player.getEyePos().subtract(center));
		lookAt(player, center.add(Vec3d.of(side.getVector()).multiply(0.5)));
		client.interactionManager.updateBlockBreakingProgress(lectern, side);
		player.swingHand(Hand.MAIN_HAND);
		delay = 0;
	}

	private static void waitJobless(MinecraftClient client, ClientPlayerEntity player) {
		if (isJobless(villager)) {
			enter(State.PLACE);
			return;
		}
		status(player, Text.translatable("tradeseeker.status.wait_jobless", rolls));
		if (ticksInState > 300) fail(client, "tradeseeker.msg.kept_job");
	}

	private static void place(MinecraftClient client, ClientPlayerEntity player) {
		BlockState block = client.world.getBlockState(lectern);
		if (block.isOf(Blocks.LECTERN)) {
			enter(State.WAIT_JOB);
			return;
		}
		if (!block.isReplaceable()) {
			fail(client, "tradeseeker.msg.spot_taken");
			return;
		}
		// A dropped item can't be picked up for its first half second, and placing over it pushes it aside. So wait
		// while the broken lectern is still flying or about to be picked up; one that landed out of reach is lost.
		if (ticksInState <= 40 && awaitDrop(client, player)) {
			status(player, Text.translatable("tradeseeker.status.wait_lectern"));
			return;
		}
		PlayerInventory inventory = player.getInventory();
		// A lectern in the offhand is placed from there, with no hotbar shuffling.
		boolean offhand = player.getOffHandStack().isOf(Items.LECTERN);
		int slot = offhand ? -1 : slotOf(inventory, Items.LECTERN);
		if (!offhand && slot < 0) {
			// The broken lectern may still be flying to us.
			status(player, Text.translatable("tradeseeker.status.wait_lectern"));
			if (ticksInState > 80) fail(client, "tradeseeker.msg.no_lectern_item");
			return;
		}
		if (!offhand && slot >= PlayerInventory.getHotbarSize()) {
			int hotbar = inventory.getSwappableHotbarSlot();
			client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, hotbar, SlotActionType.SWAP, player);
			return;
		}
		BlockHitResult hit = placement(client);
		if (hit == null) {
			fail(client, "tradeseeker.msg.spot_taken");
			return;
		}
		Hand hand = offhand ? Hand.OFF_HAND : Hand.MAIN_HAND;
		if (!offhand) inventory.setSelectedSlot(slot);
		lookAt(player, hit.getPos());
		client.interactionManager.interactBlock(player, hand, hit);
		player.swingHand(hand);
		enter(State.CONFIRM_PLACE);
		delay = 0;
	}

	/** Whether a dropped lectern is still on its way to us. Counts (once) the ones that landed out of reach. */
	private static boolean awaitDrop(MinecraftClient client, ClientPlayerEntity player) {
		Box around = new Box(lectern).expand(1.5);
		Box reach = player.getBoundingBox().expand(1, 0.5, 1);
		boolean coming = false;
		for (ItemEntity item : client.world.getEntitiesByClass(ItemEntity.class, around, i -> i.getStack().isOf(Items.LECTERN))) {
			if (item.getItemAge() < 12 || item.getBoundingBox().intersects(reach)) {
				coming = true;
			} else if (lostDrops.add(item.getId())) {
				int spares = count(player.getInventory(), Items.LECTERN);
				if (lostDrops.size() == 1) chat(player, Text.translatable("tradeseeker.msg.drop_lost", spares).formatted(Formatting.YELLOW));
				if (spares <= 2) chat(player, Text.translatable("tradeseeker.msg.spares_low", spares).formatted(Formatting.YELLOW));
			}
		}
		return coming;
	}

	/** How many of the item you carry, offhand included. */
	private static int count(PlayerInventory inventory, net.minecraft.item.Item item) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inventory.getStack(i).isOf(item)) n += inventory.getStack(i).getCount();
		ItemStack off = inventory.getStack(PlayerInventory.OFF_HAND_SLOT);
		if (off.isOf(item)) n += off.getCount();
		return n;
	}

	public static int lostLecterns() {
		return lostDrops.size();
	}

	/** A solid face to put the lectern against: the floor, or else a wall beside or the block above it. */
	private static BlockHitResult placement(MinecraftClient client) {
		for (Direction toward : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
			BlockPos against = lectern.offset(toward);
			if (!client.world.getBlockState(against).isSideSolidFullSquare(client.world, against, toward.getOpposite())) continue;
			Vec3d at = Vec3d.ofCenter(lectern).add(Vec3d.of(toward.getVector()).multiply(0.5));
			return new BlockHitResult(at, toward.getOpposite(), against, false);
		}
		return null;
	}

	private static void confirmPlace(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world.getBlockState(lectern).isOf(Blocks.LECTERN)) {
			attempts = 0;
			if (!retrying) {
				rolls++;
				jobTries = 0;
			}
			retrying = false;
			neighbours = joblessNeighbours(client);
			enter(State.WAIT_JOB);
			return;
		}
		if (ticksInState > 20) {
			if (++attempts >= 3) {
				fail(client, "tradeseeker.msg.cant_place");
				return;
			}
			enter(State.PLACE);
		}
	}

	private static void waitJob(MinecraftClient client, ClientPlayerEntity player) {
		if (isLibrarian(villager)) {
			TradeSeekerClient.LOGGER.info("Job taken after {} s (try {})", String.format(java.util.Locale.ROOT, "%.1f", ticksInState / 20.0), jobTries + 1);
			if (jobTries == 0) learnJobTake(ticksInState / 20.0);
			jobTries = 0;
			enter(State.OPEN);
			delay = Math.max(delay, 6);
			return;
		}
		if (!isJobless(villager)) {
			fail(client, "tradeseeker.msg.other_job");
			return;
		}
		if (!client.world.getBlockState(lectern).isOf(Blocks.LECTERN)) {
			enter(State.PLACE);
			return;
		}
		int seconds = ticksInState / 20;
		status(player, jobTries == 0 ? Text.translatable("tradeseeker.status.wait_job", rolls, seconds)
				: Text.translatable("tradeseeker.status.wait_job_retry", rolls, seconds, jobTries + 1, jobTriesAllowed()));
		// A villager looks for a job every 1-2 s, so a healthy one takes the lectern within a few seconds. Longer means
		// another jobless villager (within 48 blocks) may have reserved it, or a failed path made it back off (up to
		// 20 s). Breaking and placing it again cancels such a reservation and starts a fresh search.
		if (ticksInState > Math.round(jobWait() * 20)) {
			if (++jobTries >= jobTriesAllowed()) {
				failJob(client, player);
				return;
			}
			TradeSeekerClient.LOGGER.info("No job after {} s; placing the lectern again (try {} of {})", jobWait(), jobTries + 1, jobTriesAllowed());
			retrying = true;
			enter(State.BREAK);
		}
	}

	/** More tries with a short wait, so a slow villager always gets about 40 seconds. */
	private static int jobTriesAllowed() {
		return Math.max(MIN_JOB_TRIES, (int) Math.ceil(JOB_GIVE_UP_SECONDS / jobWait()));
	}

	/**
	 * Seconds to wait for the job before placing the lectern again. Auto learns from how fast villagers have taken it
	 * here: the slow end (90%) of recent first-try takes, plus half again, kept between 1.5 and 8 s. Until it has
	 * seen a few, 5.5 s.
	 */
	public static double jobWait() {
		ModConfig config = ModConfig.INSTANCE;
		if (config.jobWait > 0) return config.jobWait;
		List<Double> times = config.jobTakeTimes;
		if (times.size() < 5) return 5.5;
		List<Double> sorted = new ArrayList<>(times);
		java.util.Collections.sort(sorted);
		double p90 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.9) - 1));
		return Math.round(MathHelper.clamp(p90 * 1.5 + 0.5, 1.5, 8) * 10) / 10.0;
	}

	/** Remembers how long a villager took to take the lectern (first try only, so retries don't skew it). */
	private static void learnJobTake(double seconds) {
		List<Double> times = ModConfig.INSTANCE.jobTakeTimes;
		times.add(Math.round(seconds * 10) / 10.0);
		while (times.size() > 40) times.remove(0);
	}

	private static void failJob(MinecraftClient client, ClientPlayerEntity player) {
		List<Text> reasons = jobProblems(client, villager, lectern);
		lastProblems = reasons;
		if (reasons.isEmpty()) reasons.add(Text.translatable("tradeseeker.job.unknown"));
		fail(client, "tradeseeker.msg.no_job", jobTriesAllowed());
		for (Text reason : reasons) chat(player, Text.literal("• ").append(reason).formatted(Formatting.GRAY));
	}

	/** Roughly how long until villagers get up (they rise around sunrise, time 0 of the day). */
	static String untilMorning(MinecraftClient client) {
		long time = client.world.getTimeOfDay() % 24000;
		long ticks = (24000 - time) % 24000;
		return Odds.duration(ticks / 20.0);
	}

	/** Why a villager might not take this lectern, as far as the client can see. */
	public static List<Text> jobProblems(MinecraftClient client, VillagerEntity v, BlockPos pos) {
		List<Text> reasons = new ArrayList<>();
		// It must reach a spot right beside the lectern (not diagonal, not a block up or down).
		if (v.getBlockPos().getManhattanDistance(pos) != 1) reasons.add(Text.translatable("tradeseeker.job.not_beside"));
		VillagerEntity nearest = null;
		int rivals = 0;
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof VillagerEntity other) || other == v || other.isBaby() || !isJobless(other)) continue;
			if (other.squaredDistanceTo(Vec3d.ofCenter(pos)) > 48 * 48) continue;
			rivals++;
			if (nearest == null || other.squaredDistanceTo(Vec3d.ofCenter(pos)) < nearest.squaredDistanceTo(Vec3d.ofCenter(pos))) nearest = other;
		}
		if (nearest != null) reasons.add(Text.translatable("tradeseeker.job.rivals", rivals, nearest.getBlockPos().toShortString()));
		return reasons;
	}

	// ---- Reading trades ----

	/**
	 * Every enchanted book on offer, with the price it settles at for good: its base price less the lasting discount
	 * (CURED_DISCOUNT for a villager you cured), never under 1. Fading discounts don't count, so a find stays a find.
	 */
	public static List<Book> books(TradeOfferList offers, int discount) {
		List<Book> books = new ArrayList<>();
		for (int i = 0; i < offers.size(); i++) {
			TradeOffer offer = offers.get(i);
			ItemStack sell = offer.getSellItem();
			if (!sell.isOf(Items.ENCHANTED_BOOK) || offer.isDisabled()) continue;
			boolean emeralds = offer.getOriginalFirstBuyItem().isOf(Items.EMERALD);
			int price = emeralds ? Math.max(1, offer.getOriginalFirstBuyItem().getCount() - discount) : Integer.MAX_VALUE;
			int now = emeralds ? offer.getDisplayedFirstBuyItem().getCount() : Integer.MAX_VALUE;
			ItemEnchantmentsComponent stored = sell.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
			for (var entry : stored.getEnchantmentEntries()) books.add(new Book(i, entry.getKey(), entry.getIntValue(), price, now));
		}
		return books;
	}

	/** The lasting discount a villager gives you on books: CURED_DISCOUNT once its trades showed you cured it. */
	public static int discountFor(VillagerEntity v) {
		return v != null && ModConfig.INSTANCE.curedVillagers.contains(v.getUuidAsString()) ? CURED_DISCOUNT : 0;
	}

	/** The lasting discount of the villager the menu would start on. */
	public static int targetDiscount(MinecraftClient client) {
		if (client.player == null || client.world == null) return 0;
		return discountFor(running() ? villager : findVillager(client));
	}

	/** Learns from trades you open by hand whether that villager was cured, so the menu's odds count it. */
	public static void watchTrades(MinecraftClient client) {
		if (running() || !(client.currentScreen instanceof MerchantScreen screen) || screen.getScreenHandler().getRecipes().isEmpty()) return;
		if (client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof VillagerEntity v) {
			learnCured(v, screen.getScreenHandler().getRecipes(), client.player);
		}
	}

	/**
	 * The server sends each trade's reputation discount (its special price, which also holds Hero of the Village's
	 * share). Without a cure, reputation is at most 50 (fading good gossip and past trades); a cure gives at least 100.
	 * So a discount above what 50 reputation gives means the villager was cured by you.
	 */
	private static void learnCured(VillagerEntity v, TradeOfferList offers, ClientPlayerEntity player) {
		if (v == null || player == null) return;
		var hero = player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.HERO_OF_THE_VILLAGE);
		for (TradeOffer offer : offers) {
			float multiplier = offer.getPriceMultiplier();
			if (multiplier <= 0) continue;
			int heroShare = hero == null ? 0
					: Math.max(1, (int) Math.floor((0.3 + 0.0625 * hero.getAmplifier()) * offer.getOriginalFirstBuyItem().getCount()));
			int fromReputation = -offer.getSpecialPrice() - heroShare;
			if (fromReputation > MathHelper.floor(50 * multiplier)) {
				List<String> cured = ModConfig.INSTANCE.curedVillagers;
				if (!cured.contains(v.getUuidAsString())) {
					cured.add(v.getUuidAsString());
					while (cured.size() > CURED_KEPT) cured.remove(0);
					ModConfig.save();
				}
				return;
			}
		}
		if (ModConfig.INSTANCE.curedVillagers.remove(v.getUuidAsString())) ModConfig.save();
	}

	public static Book firstWanted(List<Book> books) {
		for (Book book : books) {
			String id = idOf(book.enchantment());
			ModConfig.Want w = remaining.get(id);
			if (w != null && w.accepts(book.level(), book.price())) return book;
		}
		return null;
	}

	private static Text describeAll(List<Book> books) {
		if (books.isEmpty()) return Text.translatable("tradeseeker.status.no_book");
		var text = Text.empty();
		for (int i = 0; i < books.size(); i++) {
			if (i > 0) text.append(", ");
			text.append(books.get(i).describe());
		}
		return text;
	}

	public static String idOf(RegistryEntry<Enchantment> enchantment) {
		return enchantment.getKey().map(key -> key.getValue().toString()).orElse(null);
	}

	// ---- Finding the villager and lectern ----

	/**
	 * The villager you're looking at; or else (in a trading hall you often look through glass, or at the lectern)
	 * the one nearest the spot you're looking at, within reach.
	 */
	private static VillagerEntity findVillager(MinecraftClient client) {
		if (client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof VillagerEntity v && !v.isBaby()) return v;
		ClientPlayerEntity player = client.player;
		Vec3d aim = client.crosshairTarget != null && client.crosshairTarget.getType() != HitResult.Type.MISS
				? client.crosshairTarget.getPos() : player.getEyePos();
		VillagerEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof VillagerEntity v) || v.isBaby() || v.squaredDistanceTo(player) > 6 * 6) continue;
			if (!isLibrarian(v) && !isJobless(v)) continue;
			double d = v.getBoundingBox().getCenter().squaredDistanceTo(aim);
			if (d < bestDistance) {
				best = v;
				bestDistance = d;
			}
		}
		return best;
	}

	/** Jobless villagers near the lectern other than ours: in a hall, one of them could take it instead. */
	private static Set<UUID> joblessNeighbours(MinecraftClient client) {
		Set<UUID> ids = new HashSet<>();
		for (Entity entity : client.world.getEntities()) {
			if (entity instanceof VillagerEntity v && v != villager && !v.isBaby() && isJobless(v)
					&& v.squaredDistanceTo(Vec3d.ofCenter(lectern)) < 6 * 6) ids.add(v.getUuid());
		}
		return ids;
	}

	/**
	 * The lectern you're looking at; or else the spot this villager's lectern was in before (still a lectern, or empty
	 * because a run stopped with it broken); or else the one nearest the villager that you can reach.
	 */
	private static BlockPos lecternFor(MinecraftClient client, VillagerEntity target, boolean lookedAt) {
		if (lookedAt && client.crosshairTarget instanceof BlockHitResult hit && client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.LECTERN)) {
			return hit.getBlockPos();
		}
		BlockPos home = homes.get(target.getUuid());
		if (home != null && client.player.canInteractWithBlockAt(home, 0)) {
			BlockState state = client.world.getBlockState(home);
			if (state.isOf(Blocks.LECTERN) || state.isReplaceable()) return home;
		}
		return findLectern(client, target, false);
	}

	/** The lectern you're looking at, or else the one nearest the villager that you can reach. */
	private static BlockPos findLectern(MinecraftClient client, VillagerEntity target, boolean lookedAt) {
		if (lookedAt && client.crosshairTarget instanceof BlockHitResult hit && client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.LECTERN)) {
			return hit.getBlockPos();
		}
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos at = client.player.getBlockPos();
		for (BlockPos pos : BlockPos.iterate(at.add(-5, -3, -5), at.add(5, 3, 5))) {
			if (!client.world.getBlockState(pos).isOf(Blocks.LECTERN) || !client.player.canInteractWithBlockAt(pos, 0)) continue;
			double d = target.squaredDistanceTo(Vec3d.ofCenter(pos));
			if (d < bestDistance) {
				best = pos.toImmutable();
				bestDistance = d;
			}
		}
		return best;
	}

	// ---- Helpers ----

	private static boolean isLibrarian(VillagerEntity v) {
		return v.getVillagerData().profession().matchesKey(VillagerProfession.LIBRARIAN);
	}

	private static boolean isJobless(VillagerEntity v) {
		return v.getVillagerData().profession().matchesKey(VillagerProfession.NONE);
	}

	/** Holds the hotbar tool that breaks the lectern fastest, skipping ones about to break. */
	private static void pickTool(ClientPlayerEntity player, BlockState block) {
		PlayerInventory inventory = player.getInventory();
		int best = -1;
		float bestSpeed = 1;
		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= 10) continue;
			float speed = stack.getMiningSpeedMultiplier(block);
			if (speed > bestSpeed) {
				best = i;
				bestSpeed = speed;
			}
		}
		if (best >= 0) inventory.setSelectedSlot(best);
	}

	private static int slotOf(PlayerInventory inventory, net.minecraft.item.Item item) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			if (inventory.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	private static void lookAt(ClientPlayerEntity player, Vec3d target) {
		if (!ModConfig.INSTANCE.faceTargets) return;
		Vec3d d = target.subtract(player.getEyePos());
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		player.setYaw(MathHelper.wrapDegrees((float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90));
		player.setPitch((float) -(MathHelper.atan2(d.y, horizontal) * MathHelper.DEGREES_PER_RADIAN));
	}

	private static void status(ClientPlayerEntity player, Text text) {
		player.sendMessage(Text.literal("⟳ ").formatted(Formatting.AQUA).append(text), true);
	}

	private static void chat(ClientPlayerEntity player, Text text) {
		player.sendMessage(Text.literal("[Trade Seeker] ").formatted(Formatting.AQUA).append(text), false);
	}

	private static void ding(MinecraftClient client, boolean good) {
		if (good) Sounds.found(false);
		else Sounds.problem();
	}

	// ---- For the menu ----

	public static List<Seen> seen() {
		return seen;
	}

	public static List<Book> bought() {
		return bought;
	}

	/** Seconds since this run started (or how long the last one ran). */
	public static long elapsedSeconds() {
		if (startedAt == 0) return 0;
		return ((state != null || endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt) / 1000;
	}

	/** The books this run is still after (all picked ones when it isn't running). */
	public static Map<String, ModConfig.Want> remaining() {
		return remaining;
	}

	/** The menu changed what's wanted: a run in progress follows along (books it already bought stay crossed off). */
	public static void wantsChanged() {
		if (state == null) return;
		Set<String> got = new HashSet<>();
		for (Book b : bought) got.add(idOf(b.enchantment()));
		Map<String, ModConfig.Want> next = new LinkedHashMap<>();
		ModConfig.INSTANCE.wanted.forEach((id, w) -> {
			if (!got.contains(id)) next.put(id, w.copy());
		});
		remaining = next;
	}

	/** What's in place for a start, as the menu's checklist shows it. */
	public static List<Check> preflight(MinecraftClient client) {
		List<Check> checks = new ArrayList<>();
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) return checks;
		ModConfig config = ModConfig.INSTANCE;
		if (config.wanted.isEmpty()) checks.add(new Check(Status.BAD, Text.translatable("tradeseeker.check.pick"), Text.translatable("tradeseeker.check.pick.tip")));

		VillagerEntity v = running() ? villager : findVillager(client);
		if (v == null) {
			checks.add(new Check(Status.BAD, Text.translatable("tradeseeker.check.no_villager"), Text.translatable("tradeseeker.msg.no_villager")));
		} else if (v.getVillagerData().profession().matchesKey(VillagerProfession.NITWIT)) {
			checks.add(new Check(Status.BAD, Text.translatable("tradeseeker.check.nitwit"), Text.translatable("tradeseeker.msg.nitwit")));
		} else if (v.getVillagerData().level() > 1) {
			checks.add(new Check(Status.BAD, Text.translatable("tradeseeker.check.locked"), Text.translatable("tradeseeker.msg.locked")));
		} else {
			checks.add(new Check(Status.OK, Text.translatable(isLibrarian(v) ? "tradeseeker.check.librarian" : "tradeseeker.check.jobless"),
					Text.translatable("tradeseeker.check.villager.tip")));
			if (discountFor(v) > 0) {
				checks.add(new Check(Status.OK, Text.translatable("tradeseeker.check.cured", CURED_DISCOUNT), Text.translatable("tradeseeker.check.cured.tip", CURED_DISCOUNT)));
			}
			if (v.isSleeping()) {
				checks.add(new Check(Status.WARN, Text.translatable("tradeseeker.check.asleep"), Text.translatable("tradeseeker.check.asleep.tip", untilMorning(client))));
			} else {
				BlockPos bed = nearbyBed(client, v);
				long time = client.world.getTimeOfDay() % 24000;
				if (bed != null) {
					// It may sleep in that bed from dusk to sunrise, and won't trade then.
					Text when = isNight(client) ? Text.translatable("tradeseeker.check.bed.now")
							: Text.translatable("tradeseeker.check.bed.later", Odds.duration((12000 - time) / 20.0));
					checks.add(new Check(Status.WARN, Text.translatable("tradeseeker.check.bed"),
							Text.translatable("tradeseeker.check.bed.tip", bed.toShortString(), when)));
				} else if (isNight(client)) {
					checks.add(new Check(Status.OK, Text.translatable("tradeseeker.check.night_ok"), Text.translatable("tradeseeker.check.night_ok.tip")));
				}
			}
			BlockPos pos = running() ? lectern : lecternFor(client, v, true);
			if (pos == null) {
				checks.add(new Check(Status.BAD, Text.translatable("tradeseeker.check.no_lectern"), Text.translatable("tradeseeker.msg.no_lectern")));
			} else if (!canCatchDrop(player, pos)) {
				// It sneaks up to the lectern by itself before breaking it, so this is fine.
				checks.add(new Check(Status.OK, Text.translatable("tradeseeker.check.far_lectern"), Text.translatable("tradeseeker.check.far_lectern.tip")));
			} else {
				checks.add(new Check(Status.OK, Text.translatable("tradeseeker.check.lectern"), Text.translatable("tradeseeker.check.lectern.tip")));
			}
			if (pos != null && isJobless(v)) {
				// Only a jobless villager still has to take the lectern; then these matter.
				for (Text problem : jobProblems(client, v, pos)) {
					checks.add(new Check(Status.WARN, Text.translatable("tradeseeker.check.job_risk"), problem));
				}
			}
		}

		PlayerInventory inventory = player.getInventory();
		BlockState lecternState = Blocks.LECTERN.getDefaultState();
		boolean axe = false;
		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) axe |= inventory.getStack(i).getMiningSpeedMultiplier(lecternState) > 1;
		checks.add(axe ? new Check(Status.OK, Text.translatable("tradeseeker.check.axe"), Text.translatable("tradeseeker.check.axe.tip"))
				: new Check(Status.WARN, Text.translatable("tradeseeker.check.no_axe"), Text.translatable("tradeseeker.check.no_axe.tip")));
		int spares = count(inventory, Items.LECTERN);
		checks.add(spares > 0 ? new Check(Status.OK, Text.translatable("tradeseeker.check.spares", spares), Text.translatable("tradeseeker.check.spares.tip"))
				: new Check(Status.WARN, Text.translatable("tradeseeker.check.no_spares"), Text.translatable("tradeseeker.check.spares.tip")));

		if (config.autoBuy && !config.wanted.isEmpty()) {
			int emeralds = count(inventory, Items.EMERALD), need = 0, discount = discountFor(v);
			var registry = client.world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
			for (var e : config.wanted.entrySet()) {
				var entry = registry.getEntry(net.minecraft.util.Identifier.of(e.getKey()));
				if (entry.isEmpty()) continue;
				boolean treasure = entry.get().isIn(net.minecraft.registry.tag.EnchantmentTags.DOUBLE_TRADE_PRICE);
				need = Math.max(need, Math.min(e.getValue().maxPrice, Odds.highestPrice(entry.get().value().getMaxLevel(), treasure, discount)));
			}
			checks.add(emeralds >= need ? new Check(Status.OK, Text.translatable("tradeseeker.check.emeralds", emeralds), Text.translatable("tradeseeker.check.emeralds.tip", need))
					: new Check(emeralds == 0 ? Status.BAD : Status.WARN, Text.translatable("tradeseeker.check.emeralds_short", emeralds, need),
							Text.translatable("tradeseeker.check.emeralds.tip", need)));
			int books = count(inventory, Items.BOOK);
			checks.add(books > 0 ? new Check(Status.OK, Text.translatable("tradeseeker.check.books", books), Text.translatable("tradeseeker.check.books.tip"))
					: new Check(Status.BAD, Text.translatable("tradeseeker.check.no_books"), Text.translatable("tradeseeker.check.books.tip")));
			if (inventory.getEmptySlot() < 0) {
				checks.add(new Check(Status.BAD, Text.translatable("tradeseeker.check.full"), Text.translatable("tradeseeker.msg.inventory_full")));
			}
		}
		return checks;
	}

	// ---- Self-test hooks ----

	static String stateName() {
		return state == null ? "IDLE" : state.name();
	}

	static VillagerEntity target() {
		return villager;
	}

	static boolean napping() {
		return napping;
	}

	static int jobTries() {
		return jobTries;
	}

	static Book lastFound() {
		return found;
	}
}
