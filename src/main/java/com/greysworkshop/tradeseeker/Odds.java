package com.greysworkshop.tradeseeker;

/**
 * What a novice librarian offers, from the game's own trade rules: two of its three novice trades are picked (paper,
 * an enchanted book, a bookshelf), so 2 in 3 re-rolls have a book. The book's enchantment is any tradeable one,
 * equally likely, at any of its levels, equally likely. It costs 2 + random(5 + 10 × level) + 3 × level emeralds,
 * doubled for treasure enchantments like Mending, and never more than 64. A discount (from curing the villager) comes
 * off that, down to 1 emerald at the least.
 */
public final class Odds {
	public static final double BOOK_CHANCE = 2.0 / 3.0;

	private Odds() {}

	public static int lowestPrice(int level, boolean treasure) {
		return lowestPrice(level, treasure, 0);
	}

	public static int lowestPrice(int level, boolean treasure, int discount) {
		return price(level, 0, treasure, discount);
	}

	public static int highestPrice(int level, boolean treasure) {
		return highestPrice(level, treasure, 0);
	}

	public static int highestPrice(int level, boolean treasure, int discount) {
		return price(level, 4 + 10 * level, treasure, discount);
	}

	private static int price(int level, int roll, boolean treasure, int discount) {
		int p = 2 + roll + 3 * level;
		if (treasure) p *= 2;
		return Math.max(1, Math.min(64, p) - discount);
	}

	/** The chance a book of this level is priced within [min, max]. */
	public static double priceChance(int level, boolean treasure, int min, int max) {
		return priceChance(level, treasure, min, max, 0);
	}

	public static double priceChance(int level, boolean treasure, int min, int max, int discount) {
		int n = 5 + 10 * level, hits = 0;
		for (int roll = 0; roll < n; roll++) {
			int p = price(level, roll, treasure, discount);
			if (p >= min && p <= max) hits++;
		}
		return (double) hits / n;
	}

	/**
	 * The chance one re-roll offers this enchantment at minLevel or higher, priced within [min, max].
	 * tradeable is how many enchantments a librarian can sell; levels run from minEnchantLevel to maxEnchantLevel.
	 */
	public static double perRoll(int tradeable, int minEnchantLevel, int maxEnchantLevel, boolean treasure, int minLevel, int min, int max) {
		return perRoll(tradeable, minEnchantLevel, maxEnchantLevel, treasure, minLevel, min, max, 0);
	}

	public static double perRoll(int tradeable, int minEnchantLevel, int maxEnchantLevel, boolean treasure, int minLevel, int min, int max, int discount) {
		if (tradeable <= 0) return 0;
		int levels = maxEnchantLevel - minEnchantLevel + 1;
		double sum = 0;
		for (int level = Math.max(minEnchantLevel, minLevel); level <= maxEnchantLevel; level++) {
			sum += priceChance(level, treasure, min, max, discount);
		}
		return BOOK_CHANCE / tradeable * sum / levels;
	}

	/** "1 in N" for a per-re-roll chance; 0 when it can't happen. */
	public static long oneIn(double chance) {
		return chance <= 0 ? 0 : Math.max(1, Math.round(1 / chance));
	}

	/** Re-rolls needed to have found it with 90% certainty. */
	public static long likely(double chance) {
		if (chance <= 0) return 0;
		if (chance >= 1) return 1;
		return (long) Math.ceil(Math.log(0.1) / Math.log(1 - chance));
	}

	/** Like "40 s", "9 min" or "2.5 h". */
	public static String duration(double seconds) {
		if (seconds < 90) return Math.max(1, Math.round(seconds)) + " s";
		if (seconds < 90 * 60) return Math.round(seconds / 60) + " min";
		double hours = seconds / 3600;
		return (hours < 10 ? String.format(java.util.Locale.ROOT, "%.1f", hours) : Long.toString(Math.round(hours))) + " h";
	}
}
