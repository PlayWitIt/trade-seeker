# Trade Seeker 1.5.x: testing notes

Read this when the user comes back with results from testing 1.5.0 in their own world.

## Where things stand (2026-10-29)

- 1.5.0 is pushed (`main`) and published as a pre-release:
  https://github.com/PlayWitIt/trade-seeker/releases/tag/1.5.0
- `./gradlew runSelfTest` passed 63 of 63 in a dev world, including a real cure (via the game's own
  `ZOMBIE_VILLAGER_CURED` interaction) and a 1 emerald purchase.
- `./gradlew runCureTest` runs only the cure and labels part (~2 min). Use it when iterating on cure or label code.
- The self-test opens a game window on the user's screen; tell them before starting it. `run/options.txt` has
  `onboardAccessibility:false`, and the test skips the accessibility welcome screen anyway.

## What the user asked for, and where it lives

| Request | Code |
|---|---|
| 1 emerald books from a cured villager that isn't locked yet; menu shouldn't say "can't" | `Reroller.Cure`, `cureOf`, `learnCured`, `cureChecks`; `SeekerScreen.drawOdds` / `onlyIfCured` |
| Keep rolling while tabbed out, in the pause menu or chat; pause only for the J menu | `Reroller.tick` gate, `closeTrades`, `hidesTrades`, `keepsWorldRunning`; `mixin/MinecraftClientMixin` |
| Pick the nearest villager in reach when not looking at one | `Reroller.findVillager` |
| Option, off by default: can't hit villagers | `ModConfig.protectVillagers`; `mixin/InteractionMixin.attackEntity` |
| Trades over villagers' heads instead of signs (top books / all books / all trades) | `TradeMemory`; `mixin/EntityRendererMixin`, `EntityRenderStateMixin`; Options → Labels tab |

## Game facts verified from decompiled 1.21.11 code

- Cure: `MAJOR_POSITIVE` +20 (weight 5, max 20, never decays) and `MINOR_POSITIVE` +25 (weight 1, decays 1/day).
  Reputation 125 at first, then 100 for good. Books have multiplier 0.2: 25 off at first, then **20 off for good**.
- Price shown = `clamp(base + demand + specialPrice, 1, 64)`, so it never goes below 1 emerald.
- Book base price: `2 + random(5 + 10·level) + 3·level`, doubled for treasure (Mending), capped at 64.
- The cure only counts if the curing player is in the same world (dimension) when conversion finishes:
  `ZombieVillagerEntity.finishConversion` uses `world.getPlayerByUuid`.
- A cured villager is a new entity (new UUID), so "seen uncured" can't flip to cured for the same UUID.
- The client learns the cure only from `TradeOffer.getSpecialPrice()` when the trades are open. Uncured
  reputation is at most 50 (minor positive 25 + trading 25), so a discount above `floor(50 × multiplier)` means cured.

## Not proven by the self-test: check these first if something is wrong

1. **Multiplayer servers.** The user confirmed 2026-10-29 that it works in multiplayer (server and anticheat not
   known). A strict anticheat might still not like a trade screen staying open behind the pause menu; Speed →
   Careful may help.
2. **Walking with a menu open.** Self-tested since 1.5.2: the hall moves on to villager B with the pause menu up and
   keeps re-rolling. Fetching a lost lectern (FETCH/RETURN, 1.5.2) uses the same sneak-walk and is self-tested in
   the open, not with a menu up.
3. **Labels.** They're drawn through walls (`notSneaking = true` in `submitLabel`). 1.5.1 added a distance option
   (8/16/24/48, `ModConfig.labelRange`). No line-of-sight check.
4. **Protect villagers.** 1.5.1: `VillagerGuard` blocks direct hits, sword sweeps (mirrors `canUseSweepAttack`) and
   spear stabs (`attackWithPiercingWeapon`). Arrows, tridents and spear charges are not blocked (server-side).
5. **"Not cured" is kept for the session only** (`Reroller.notCured`); after a restart that villager is
   "not checked yet" again. That's by design (a cure makes a new UUID), but confirm the user is fine with it.
6. **Hand-opened trades** are matched to a villager through `InteractionMixin.interactEntity`. Right-clicking a
   villager through another mod's UI might bypass it.

## 1.5.3: purchases confirmed by the server

A purchase counts (and unticks the book) only once the server's own copy of the trade screen says the book is
yours: after the buy clicks, a no-op click with a stale revision (`Reroller.askForContents`) makes the server send the
whole screen, in order after the purchase. A refused buy is retried twice, then it stops with `msg.buy_refused`. The
cure test covers it (server clears the emeralds and lags 1 s at the moment of buying). Full self-test: 80/80.

## 1.5.1: demand (the user's goal is 1 emerald books)

Price = base + max(0, floor(base × demand × 0.2)) + specialPrice, clamped ≥ 1. At each restock, demand += uses −
(maxUses − uses); books have maxUses 12. Buying a trade out (12) makes a cured base-10 Mending 14 emeralds.
`Reroller.usesKeepingPrice` gives the most uses that keep the lasting price; buy-max stops there, and buying by hand
shows `status.bought_enough`. Repeat cures add nothing: MAJOR_POSITIVE caps at 20, MINOR_POSITIVE at 25.

## If the user reports...

- **"It still says can't get that price."** Ask what the checklist says: Cured by you / Cured? Not checked yet /
  Not cured by you. If "Not cured", they likely weren't in the world when it converted, or someone else cured it.
  Check the log for `Trades opened by hand at …: cure …`.
- **"It stopped when I pressed Esc."** Pressing Esc while the trade screen itself is up closes the trade. That's
  vanilla, and the run retries. If the whole run stopped, look at the `tick` gate in `Reroller` and at `hidesTrades`.
- **"Labels don't show."** The villager's trades have to be opened once; Labels mode must not be Nothing; it must
  be within 24 blocks; it must not have lost its job since. Data lives in `config/tradeseeker-trades.json`.
- **"It picked the wrong villager."** `findVillager`: the looked-at villager in reach wins; looking at a lectern
  picks the villager nearest that lectern; otherwise the one nearest the player, librarians and jobless first.

## Release workflow

Bump `mod_version` in `gradle.properties` and update the README. Run `./gradlew runSelfTest` (give it up to 90
minutes; a slow Mending can stretch the hall) and wait for `RESULT n passed, 0 failed`. Commit as PlayWit Creations
(the repo's git config), with no attribution trailer. Then:
`git push origin main`,
`gh release create <ver> build/libs/trade-seeker-<ver>+1.21.11.jar --title trade-seeker --target main --notes-file <notes> --latest`,
and upload the jar to Modrinth (project `c0NhgDoJ`, token in `~/.modrinth_token`): POST `/v2/version` with Fabric API
(`P7dR8mSH`) required, game version 1.21.11, loader fabric; then PATCH `/v3/version/<id>` with
`{"environment":"client_only"}`.
