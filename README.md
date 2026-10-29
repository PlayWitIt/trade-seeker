# Trade Seeker

A client-side Fabric mod for Minecraft 1.21.11 that re-rolls a librarian for you. It breaks and replaces the lectern
until the villager offers a book you picked, at a level and price you accept.

## Use

1. Put a lectern next to a villager that nobody has traded with (jobless, or a Novice librarian).
2. Stand right up against the lectern, with an axe in your hotbar.
3. Press **J**. Tick every book you'd take: that's your **Wanted** list. It stops at whichever turns up first, so no
   good trade is skipped. Click a book to set its options on the right:
   - **Lowest level**: that level or better counts.
   - **Price**: drag the two ends of the slider (or scroll over them). The gold band shows what villagers really ask
     for that book. The presets are Any, the cheaper half, and a bargain (the cheapest quarter).
   - The odds: "1 in N re-rolls · about T", worked out from the game's own trade rules and your measured pace.
4. Press **Start re-rolling** (or Shift+Enter). It re-rolls the villager you're looking at, or else the one nearest
   you (nearest the lectern you look at, in a trading hall). It never picks one out of reach.

The checklist above Start shows what's in place: the villager, the lectern, an axe, spare lecterns and, with
auto-buy, the emeralds, books and inventory space. Start stays greyed out until nothing is missing.

When it finds a book, it plays a jingle and shows a toast, then waits with the trade open and selected. You decide:

- **Buy it** (take it from the result slot), then close the trade. It notices the purchase and carries on with the
  next untraded villager in reach for the rest of your list.
- **Close without buying**: it stops and leaves the trade as it is, so a stray Esc can't throw a good book away.
  Press Start to carry on.

It repeats the jingle every 10 seconds until you act (Options: Keep reminding me).

While it runs you can do other things: tab out, open the pause menu, chat, change settings. It carries on (in single
player the world keeps running too), and the villager's trades open out of sight, so your screen stays up. A find you
have to decide on brings the trade screen up. It waits only while the J menu, or an inventory or chest, is open.

There are four find sounds: Chime, Bell, Fanfare and Level up. Click one in Options to hear it, and set the volume
there. A purchase plays a coin and a happy villager, and a finished list plays the challenge fanfare.

- **Auto-buy** (off by default) buys it for you. Then it moves to the next untraded villager in reach for the books
  still wanted.
- **Buy as many as I can afford** (off by default, needs auto-buy) keeps buying copies until you run out of
  emeralds, books or space, or the villager runs out.

It never trades while re-rolling, because one trade locks a villager's offers for good. It refuses a villager that
has already been traded with.

## More

- **Seen** tab: every offer this run, newest first. Near misses (a wanted book at too low a level or too high a
  price) are in gold, and are also said once in chat.
- **★ Starter kit**: picks the ten books most people want, at their best level.
- **⚙ Options**:
  - Speed: Fast, Normal, or Careful (for strict anticheats).
  - Turn to face the targets.
  - Stop if I get hurt (on by default).
  - Don't let me hit villagers (off by default): your attacks on villagers do nothing, so a stray click can't cost a
    cured villager's discount. Arrows, and sword sweeps at something else, can still reach them.
  - Near-miss messages, sound, and a re-roll limit.
- Keys: **J** opens the menu, even while it's running. "Start / stop re-rolling" can be bound in Controls.
- Settings and the menu's tab and selection are kept in `config/tradeseeker.json`. 1.0.0 settings carry over.

## Trades over villagers' heads

No more signs. Once a villager's trades have been opened (by you, or by a re-roll), they show over its head, up to
24 blocks away and through glass, and they're remembered in `config/tradeseeker-trades.json` across restarts. Options,
**Labels** tab:

- **Top books** (the default): books at their best level, in gold, and books on your wanted list, in green.
- **All books**, **All trades**, or **Nothing**.

Book prices are what the villager keeps asking you: less a cure's 20, never under 1. A traded villager is marked
"locked in". A villager that loses its job is forgotten (its next job brings new trades). **Forget all** clears them.

## Cured villagers: 1 emerald books

Cure a zombie villager yourself, then re-roll it like any other (curing doesn't lock its trades; only a trade does).
Every book it offers you costs less, and it keeps that discount through every re-roll:

- The cure gives you a lasting +100 reputation with it and a fading +25 (gone in about 25 in-game days). A book costs
  0.2 × reputation less, never under 1 emerald: 25 off at first, then **20 off for good**.
- So a book whose base price is 21 or less is 1 emerald for good. One at 22 to 26 shows 1 emerald at first, then
  creeps up to 2 to 6 as the fading part wears off, and a bought trade keeps its base price.
- The mod judges a find on the price it keeps: base price less 20. Set the price to 1 and it only stops on books that
  stay 1 emerald. The Seen list shows the lower price for now in brackets when there is one.
- It learns a villager was cured from its trades (the server sends each trade's discount), when it opens them or when
  you do. It remembers cured villagers in `config/tradeseeker.json` (the last 500), so that's once per villager.
- What the menu says, for a price only a cured villager asks:
  - **Cured by you: −20 for good** (checklist): known cured. The price band and odds count the 20 off.
  - **Cured? Not checked yet**: it hasn't seen this villager's trades. The odds say "only a villager you cured goes
    this cheap: 1 in N if this one is". Right-click it once to open its trades, or just start: the first re-roll checks.
  - **Not cured by you**: its trades show no cure discount. If no pick can come from it, Start is blocked, and a run
    that finds this out stops right away (or, in a hall, moves on to the next villager) instead of re-rolling forever.
- It's only your cure that counts: the discount is per player. And you must be in the world when the zombie villager
  turns back (it takes 3 to 5 minutes), or the game gives the discount to nobody.

How often a cured villager's book is 1 emerald for good: any level I book (not Mending) always; Mending 2 in 5;
Unbreaking III 11 in 35; Protection IV 8 in 45; Efficiency V or Sharpness V 1 in 11. Hitting the villager lowers
its opinion of you for a while, so don't.

## When the villager won't take the lectern

A jobless villager only takes a lectern if three things hold:

- its job search finds a walking path to a spot right beside the lectern (not diagonal, not a block higher or lower);
- no other jobless villager within 48 blocks has reserved the lectern first;
- it ends up within 2 blocks of the lectern.

After a failed path, the game makes it wait longer each time before trying again, up to 20 seconds. So if no job
comes within about 15 seconds, the mod breaks the lectern and places it again. That cancels any reservation and
starts a fresh search. After 5 tries it stops and says what's likely wrong. The checklist warns about both problems
before you start. The clock pauses while the villager is asleep.

Lecterns in your **offhand** work: they're placed from there and count as spares.

## Night

Villagers trade, and take jobs, at any hour. This comes from the game's code: the only thing that stops a trade is
a villager asleep in a bed (or a baby, or one another player is already trading with). Villagers with no bed in
reach never sleep. So:

- **Night with no beds:** it carries on as normal.
- **The villager goes to bed:** it doesn't wait. If another untraded villager in reach is awake, it re-rolls that
  one instead.
- **Only sleeping villagers left:** it pauses without breaking anything, shows a notice with the time to sunrise,
  and carries on by itself when the villager wakes.
- **Your server blocks night trading:** some servers do this with plugins. It notices when an awake villager won't
  trade at night, and waits for sunrise in the same way.
- **The menu:** the bar under the title is the villager day (work, gathering, rest), with a mark for now. Hover over
  it for the details. The checklist warns about a bed near the villager.

## Trading halls

Stand in the aisle, right up against the lectern. When it moves on to another villager, it sneaks along the aisle to
that lectern (sneaking can't walk off an edge).

A pod that never loses a villager, and hardly ever a lectern (this is what the self-test builds):

- The villager stands in a 1x1 cell at floor level, with its lectern in front of it, between it and you.
- Put a block above the lectern. With the lectern broken, the gap is then 1 block high, and the villager can't get out.
- Put an **open iron trapdoor** across the front of the villager's cell, on the lectern side. Without it, about half
  of the broken lecterns fly into the pod, out of reach. With it, about 1 in 50 does.
- Don't raise the villager a block above its lectern: it won't take a lectern that is diagonally below it.

Carry a spare lectern or two. If one is lost, it says so and carries on with a spare.

## Build

`./gradlew build` (JDK 21).

- `./gradlew runSelfTest` builds a trading hall in a survival world and re-rolls it for real. It ends with Mending
  and Unbreaking III at any price and takes about 10 minutes.
- `./gradlew runCureTest` only runs the cure and labels part (a couple of minutes).
- `./gradlew runMenuTest` only takes the menu screenshots.
