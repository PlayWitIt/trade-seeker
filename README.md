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

**Trading halls:** with Auto-buy on and several books picked, each book is crossed off once bought and it moves on to
the next untraded villager in reach for the rest. Stand in the aisle, up against the lectern; when it moves on, it
sneaks along the aisle to the next lectern (sneaking can't walk off an edge).

A pod that never loses a villager or a lectern (this is what the self-test builds):

- The villager stands in a 1x1 cell at floor level, with its lectern in front of it, between it and you.
- Put a block above the lectern. With the lectern broken, the gap is then 1 block high, and the villager can't get out.
- Put an **open iron trapdoor** across the front of the villager's cell, on the lectern side. Without it, about half
  of the broken lecterns fly into the pod, out of reach. With it, they bounce back to you. The villager still takes
  the job.
- Don't raise the villager a block above its lectern: it won't take a lectern that is diagonally below it.

Carry a spare lectern or two anyway. If one is lost, it says so and carries on with a spare.

It never trades while re-rolling, because one trade locks a villager's offers for good. It refuses a villager that
has already been traded with.

Settings are saved in `config/tradeseeker.json`.

## Build

`./gradlew build` (JDK 21). `./gradlew runSelfTest` re-rolls a real villager in a test world and checks the results.
