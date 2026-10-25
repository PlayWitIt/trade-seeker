package com.greysworkshop.tradeseeker;

import com.greysworkshop.tradeseeker.mixin.MerchantScreenAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Re-rolls a librarian: opens its trades, and while no wanted book is offered, breaks its lectern, waits for it to
 * lose the job, puts the lectern back and waits for it to take the job again (which gives it new trades).
 * A villager's trades only lock once someone trades with it, so it never trades unless told to.
 */
public final class Reroller {
	private enum State { OPEN, AWAIT_OFFERS, BREAK, WAIT_JOBLESS, PLACE, CONFIRM_PLACE, WAIT_JOB, BUY }

	/** One enchanted book a librarian offers. */
	public record Book(int index, RegistryEntry<Enchantment> enchantment, int level, int price) {
		public Text describe() {
			return Text.empty().append(Enchantment.getName(enchantment, level)).append(Text.literal(" · " + price + " emeralds"));
		}
	}

	private static State state;
	private static VillagerEntity villager;
	private static BlockPos lectern;
	private static int ticksInState, delay, attempts, rolls;
	private static long startedAt;
	private static Book found;
	private static Set<UUID> neighbours = Set.of();
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
		BlockPos pos = findLectern(client, target);
		if (pos == null) {
			chat(player, Text.translatable("tradeseeker.msg.no_lectern").formatted(Formatting.RED));
			return;
		}
		villager = target;
		lectern = pos;
		rolls = 0;
		found = null;
		lastSeen = Text.empty();
		startedAt = System.currentTimeMillis();
		neighbours = joblessNeighbours(client);
		enter(isLibrarian(target) ? State.OPEN : State.WAIT_JOB);
		chat(player, Text.translatable("tradeseeker.msg.started", pos.toShortString()).formatted(Formatting.GREEN));
	}

	public static void stop(MinecraftClient client, Text why) {
		if (state == null) return;
		if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		state = null;
		villager = null;
		if (client.player != null && why != null) chat(client.player, why);
	}

	private static void fail(MinecraftClient client, String key, Object... args) {
		if (client.player != null && client.currentScreen instanceof MerchantScreen) client.player.closeHandledScreen();
		stop(client, Text.translatable(key, args).formatted(Formatting.RED));
		ding(client, false);
	}

	private static void enter(State next) {
		state = next;
		ticksInState = 0;
		delay = ModConfig.INSTANCE.stepDelay;
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
		if (villager.isRemoved() || !villager.isAlive()) {
			fail(client, "tradeseeker.msg.villager_gone");
			return;
		}
		if (!player.canInteractWithBlockAt(lectern, 0.5)) {
			fail(client, "tradeseeker.msg.too_far");
			return;
		}
		if (delay > 0) {
			delay--;
			return;
		}
		ticksInState++;
		switch (state) {
			case OPEN -> open(client, player);
			case AWAIT_OFFERS -> awaitOffers(client, player);
			case BREAK -> breakLectern(client, player);
			case WAIT_JOBLESS -> waitJobless(client, player);
			case PLACE -> place(client, player);
			case CONFIRM_PLACE -> confirmPlace(client, player);
			case WAIT_JOB -> waitJob(client, player);
			case BUY -> buy(client, player);
		}
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

	private static void awaitOffers(MinecraftClient client, ClientPlayerEntity player) {
		if (client.currentScreen instanceof MerchantScreen screen && !screen.getScreenHandler().getRecipes().isEmpty()) {
			attempts = 0;
			check(client, player, screen);
			return;
		}
		if (ticksInState > 60) {
			if (client.currentScreen instanceof MerchantScreen) player.closeHandledScreen();
			if (++attempts >= 3) {
				fail(client, "tradeseeker.msg.no_trades");
				return;
			}
			enter(State.OPEN);
		}
	}

	private static void check(MinecraftClient client, ClientPlayerEntity player, MerchantScreen screen) {
		MerchantScreenHandler handler = screen.getScreenHandler();
		if (handler.getExperience() > 0 || villager.getVillagerData().level() > 1) {
			fail(client, "tradeseeker.msg.locked");
			return;
		}
		List<Book> books = books(handler.getRecipes());
		Book match = firstWanted(books);
		lastSeen = describeAll(books);
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
		enter(State.BREAK);
	}

	private static void found(MinecraftClient client, ClientPlayerEntity player, MerchantScreen screen, Book book) {
		found = book;
		select(client, screen, book.index());
		long seconds = (System.currentTimeMillis() - startedAt) / 1000;
		chat(player, Text.translatable("tradeseeker.msg.found", book.describe(), rolls, seconds / 60, seconds % 60)
				.formatted(Formatting.GREEN, Formatting.BOLD));
		ding(client, true);
		if (ModConfig.INSTANCE.autoBuy) {
			enter(State.BUY);
		} else {
			stop(client, Text.translatable("tradeseeker.msg.your_call").formatted(Formatting.YELLOW));
		}
	}

	private static void buy(MinecraftClient client, ClientPlayerEntity player) {
		if (!(client.currentScreen instanceof MerchantScreen screen)) {
			fail(client, "tradeseeker.msg.buy_closed");
			return;
		}
		MerchantScreenHandler handler = screen.getScreenHandler();
		ItemStack result = handler.getSlot(2).getStack();
		if (result.isEmpty()) {
			// Nothing in the result slot: the inventory hasn't got the emeralds and book for it.
			stop(client, Text.translatable("tradeseeker.msg.cant_afford").formatted(Formatting.YELLOW));
			return;
		}
		client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
		stop(client, Text.translatable("tradeseeker.msg.bought", found.describe()).formatted(Formatting.GREEN));
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
		PlayerInventory inventory = player.getInventory();
		int slot = slotOf(inventory, Items.LECTERN);
		if (slot < 0) {
			// The broken lectern may still be flying to us.
			status(player, Text.translatable("tradeseeker.status.wait_lectern"));
			if (ticksInState > 80) fail(client, "tradeseeker.msg.no_lectern_item");
			return;
		}
		if (slot >= PlayerInventory.getHotbarSize()) {
			int hotbar = inventory.getSwappableHotbarSlot();
			client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, hotbar, SlotActionType.SWAP, player);
			return;
		}
		BlockHitResult hit = placement(client);
		if (hit == null) {
			fail(client, "tradeseeker.msg.spot_taken");
			return;
		}
		inventory.setSelectedSlot(slot);
		lookAt(player, hit.getPos());
		client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		player.swingHand(Hand.MAIN_HAND);
		enter(State.CONFIRM_PLACE);
		delay = 0;
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
			rolls++;
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
		// A neighbour taking a job may just have found its own lectern; it's only to blame if ours stays jobless.
		for (Entity entity : client.world.getEntities()) {
			if (ticksInState > 300 && entity instanceof VillagerEntity v && neighbours.contains(v.getUuid()) && isLibrarian(v)) {
				fail(client, "tradeseeker.msg.stolen");
				return;
			}
		}
		status(player, Text.translatable("tradeseeker.status.wait_job", rolls));
		if (ticksInState > 1200) fail(client, "tradeseeker.msg.no_job");
	}

	// ---- Reading trades ----

	/** Every enchanted book on offer, with its emerald price after discounts. */
	public static List<Book> books(TradeOfferList offers) {
		List<Book> books = new ArrayList<>();
		for (int i = 0; i < offers.size(); i++) {
			TradeOffer offer = offers.get(i);
			ItemStack sell = offer.getSellItem();
			if (!sell.isOf(Items.ENCHANTED_BOOK) || offer.isDisabled()) continue;
			ItemStack cost = offer.getDisplayedFirstBuyItem();
			int price = cost.isOf(Items.EMERALD) ? cost.getCount() : Integer.MAX_VALUE;
			ItemEnchantmentsComponent stored = sell.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
			for (var entry : stored.getEnchantmentEntries()) books.add(new Book(i, entry.getKey(), entry.getIntValue(), price));
		}
		return books;
	}

	public static Book firstWanted(List<Book> books) {
		ModConfig config = ModConfig.INSTANCE;
		for (Book book : books) {
			String id = idOf(book.enchantment());
			if (id != null && config.wants(id, book.level()) && config.priceOk(book.price())) return book;
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

	/** The lectern you're looking at, or else the one nearest the villager that you can reach. */
	private static BlockPos findLectern(MinecraftClient client, VillagerEntity target) {
		if (client.crosshairTarget instanceof BlockHitResult hit && client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.LECTERN)) {
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
		if (!ModConfig.INSTANCE.sound) return;
		client.getSoundManager().play(PositionedSoundInstance.ui(
				good ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), good ? 1.0f : 0.6f));
	}

	// ---- Self-test hooks ----

	static String stateName() {
		return state == null ? "IDLE" : state.name();
	}

	static VillagerEntity target() {
		return villager;
	}

	static Book lastFound() {
		return found;
	}
}
