# Trade Seeker

A client-side Fabric mod for Minecraft 1.21.11 that re-rolls a librarian for you. It breaks and replaces the lectern
until the villager offers the enchanted book you picked, at a price you accept.

## Use

1. Put a lectern next to a villager that nobody has traded with (jobless, or a librarian at Novice level).
2. Stand right next to the lectern, so the broken lectern lands in your inventory (or carry spares). Keep an axe in
   your hotbar: it uses the fastest tool you have and skips tools that are about to break.
3. Press **J**. Click the enchantments you want. Click the level on the right to set the lowest level that counts
   (right-click lowers it). Set the price range in emeralds.
4. Look at the villager and press **Start re-rolling**. Press **J** again at any time to stop.

When it finds a match it plays a sound, says so in chat and stops with the trade screen open and the trade selected.
**Auto-buy is off by default**: you decide whether to take the book. With Auto-buy on, it buys it straight away (you
need the emeralds and a book on you), which locks the librarian's trades in.

It never trades while re-rolling, because one trade locks a villager's offers for good. It refuses a villager that
has already been traded with.

Settings are saved in `config/tradeseeker.json`.

## Build

`./gradlew build` (JDK 21). `./gradlew runSelfTest` re-rolls a real villager in a test world and checks the results.
