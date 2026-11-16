# Triggers: areas, watches, timers, clicks and signals

`com.sneakyutils.triggers` is a shared trigger service. SneakyUtils listens to each frequent event once (movement,
clicks, breaks, ticks) and hands it only to the subscriptions that can apply. It replaces MagicSpells passive
triggers, which checked every trigger for every player on every step.

```kotlin
val triggers = SneakyUtils.getTriggers()   // throws while SneakyUtils is disabled; triggersOrNull() doesn't
```

Depend on SneakyUtils as usual (`compileOnly` the published artifact, `SneakyUtils` server dependency with
`load: BEFORE`, `join-classpath: true`). Every type here is Java-callable: optional arguments are overloads, callbacks
are single-method interfaces, options use builders.

## Rules for every call

- **Don't implement `Triggers`, `Subscription` or `PlayerTimer`.** Only SneakyUtils implements them; later additions
  come as default methods, but other implementations aren't supported. In tests, load SneakyUtils in MockBukkit, or
  put the few calls you make behind your own interface and fake that. Handler interfaces (`AreaHandler`,
  `StepHandler`, `BlockClickHandler`, ...) are yours to implement: anything added to them later is a default method.
- **Server thread only.** Every method throws `IllegalStateException` on another thread. Handlers run on the server
  thread; do database, HTTP and file work in your own async tasks.
- **Owned by your plugin.** Pass your enabled plugin as `owner`. Everything you subscribe closes when your plugin
  disables, silently (no exits are fired into a disabling plugin). Nothing is delivered to a plugin once it is
  disabled, not even the `UNREGISTER` exits of areas it closes in its own `onDisable`: clean up per-player state there
  yourself. Everything closes when SneakyUtils disables.
- **Close what you don't need.** Every call returns a `Subscription`; `close()` is idempotent. Closing an area fires an
  `UNREGISTER` exit for the players inside it.
- **Errors are contained.** A handler that throws is logged with its subscription id, owner and stack trace, at most
  once a minute per subscription, and stays subscribed. Other handlers still run.
- **Subscribing or closing inside a handler is allowed** and takes effect from the next event. Enters and exits already
  queued for the current event are still delivered, so for one player and one area you always get enter, then exit
  (unless the owner disables meanwhile: then nothing more reaches it).
- **A handler doesn't re-trigger itself.** A step, block-watch or area-step call that the same subscription's
  handler caused for the same player (by moving or teleporting them), directly or through other handlers it set off,
  is skipped, as MagicSpells skipped a passive re-triggered during its own run. So a jump pad's step handler can move
  the player without hearing about that move. Enters and exits are never skipped (they pair up); a loop of those
  (enter teleports out, exit teleports back) is cut off after 10,000 calls, with one log line a minute naming the
  subscription. `/triggers stats` counts both (`queue.self-triggered`, `queue.dropped`).
- **Order.** For one block step: step watches, then block watches (each in the order added); exits, in the order the
  player entered those areas; enters and pass-throughs, in the order the areas were registered (pass-throughs of areas
  away from the destination chunk come after the others); steps inside, in the order entered. A registration or a
  close calls players in the order they joined. What a handler causes (a teleport, a new area) runs after all of these.
  A handler that kicks the player or closes a subscription therefore stops exactly the later calls listed here.

## Areas

```kotlin
val forge = Area.box(world, 100, 60, -20, 110, 70, -12)       // inclusive block corners, any order
triggers.area(this, forge, object : AreaHandler {
    override fun onEnter(player: Player, area: Area, cause: AreaCause) { bellows.start(player) }
    override fun onExit(player: Player, area: Area, cause: AreaCause) { bellows.stop(player) }
})
```

```java
Triggers triggers = SneakyUtils.getTriggers();
triggers.area(plugin, Area.box(world, 100, 60, -20, 110, 70, -12),
    AreaHandler.of((p, area, cause) -> bellows.start(p), (p, area, cause) -> bellows.stop(p)),
    AreaOptions.builder().riders(RiderPolicy.ALLOW).build());
```

- **Causes.** Enter: `MOVE`, `VEHICLE`, `TELEPORT`, `JOIN`, `WORLD_CHANGE`, `REGISTER`. Exit: `MOVE`, `VEHICLE`,
  `TELEPORT`, `QUIT`, `WORLD_CHANGE`, `UNREGISTER`, `DEATH`. A respawn enters with `TELEPORT`. A player still on the
  death screen (rejoining before respawning, or online when SneakyUtils enables) enters nothing until the respawn. A single move longer than
  64 blocks counts as `TELEPORT`.
- **Options** (`AreaOptions.builder()`, defaults in brackets): `riders` [`SKIP`; `ALLOW`, `ONLY`],
  `includeSpectators` [false], `passThrough` [false], `fireOnJoin` [true], `fireOnRegister` [false], `stepEvents`
  [false, calls `onStep` on each block step inside].
- **Steps inside** (`stepEvents`): every block change of an announced member counts, teleports within the area
  included. Override `onStep(player, area, cause)` to tell them apart: `cause` is `MOVE` on foot, `VEHICLE` riding,
  `TELEPORT` for a teleport, respawn or move over 64 blocks. The entering step and silent stays get no `onStep`.
  `onStep(player, area)` (no cause) still works: the three-argument default calls it.
- **Riders and spectators decide entering only.** A player inside stays inside until they leave the area, whatever they
  ride or their game mode. Mounting inside a `SKIP` area keeps you inside until you drive out; dismounting inside a
  `SKIP` area enters it on foot.
- **When riding changes.** Bukkit fires the mount and dismount events before the change is complete (and plugins can
  still cancel them), so the service applies a mount or dismount on the next tick, once it has happened: a handler
  called with `VEHICLE` sees `player.isInsideVehicle` true, one called with `MOVE` sees it false. A cancelled mount
  changes nothing. Paper also reports every dismount as a teleport (cause `DISMOUNT`, from the seat to beside the
  vehicle); the service counts that as a step on foot (`MOVE`), not a teleport. Teleports and world changes re-read
  the riding state on the spot, so a mount made without an event is picked up, and players already riding when
  SneakyUtils enables are tracked from the start.
- **Silent stays.** With `fireOnJoin` or `fireOnRegister` off, players already inside are counted silently: no enter
  and no exit for that stay.
- **Pass-through** (race checkpoints, gates): a move that crosses the area but starts and ends outside fires enter then
  exit. It works on foot and riding, not for teleports. A path that only touches an edge or a corner of the area's box
  doesn't cross it.
- **Custom shapes**: `Area.custom(world, boundingBox) { x, y, z -> polygon.contains(x, z) }`. The box is indexed and
  the predicate refines it. The predicate runs during movement: keep it cheap and side-effect free (no teleports, no
  subscribing or closing; those are dropped or rejected, and a rejected `close()` leaves the subscription active).
  SneakyUtils has no WorldGuard dependency; build the predicate yourself.
- **Limits**: an area may span at most 64 x 64 chunks. Areas are keyed by world UUID; unloading a world closes its
  areas.

## Per-player watches

Turn a watch on while a state is active (channelling, carrying sand, muddy) and close it when the state ends. Players
without watches cost nothing.

```kotlin
val watch = triggers.watchSteps(this, player) { p, from, to, mounted -> channel.cancel(p) }
// later
watch.close()
```

```java
Subscription soggy = triggers.watchBlock(plugin, player, BlockOffset.FEET, Set.of(Material.WATER),
    (p, block, match) -> { if (match == BlockMatch.ENTERED) bread.soak(p); });
```

- `watchSteps`: every block step. `watchBlock(at = FEET | BELOW, materials)`: on block steps where that block matches
  (`ENTERED`, `STAYED`) and once when it stops matching (`LEFT`). `watchBlockUnder(materials)`: `BELOW` matches only.
  `BELOW` is the block just under the feet, or the one below it when that is air, so fence and wall tops count.
- `WatchOptions` [defaults]: `onFootOnly` [true: paused while riding], `keepOnQuit` [false], `keepOnWorldChange`
  [true], `includeTeleports` [false].
- A watch for an offline player without `keepOnQuit` comes back already closed. Subscribing from your own
  `PlayerJoinEvent` handler is fine at any priority.

## Timers

```kotlin
triggers.every(this, 72_000) { auction.hourlyReset() }                 // one global task, staggered
val atForge = triggers.everyForPlayers(this, 2) { p -> bellows.animate(p) }
atForge.add(player)   // e.g. from the forge area's onEnter; remove in onExit
```

- Intervals are 1..72,000 ticks. Timers with the same interval are spread over its ticks. A per-player timer puts each
  player in the least-loaded bucket, so N players on interval I cost about N / I calls a tick.
- No catch-up: a lagging server never runs a timer twice in a tick. Players leave per-player timers when they quit.
- Dead players are paused: from death to respawn a player stays in every per-player timer (`contains`, `size`) but
  isn't called, like MagicSpells' `ticks` passives. A player added while dead starts at the respawn. For a timer that
  should keep calling dead players, pass `PlayerTimerOptions.builder().skipDead(false)` (with `.scope(...)`).
- `PlayerScope.ALL_ONLINE` tracks every player. It is allowed but counted and flagged in `/triggers`: prefer adding the
  players who need it.

## Blocks and entities

```kotlin
triggers.onBlockClick(this, BlockKey.of(doorBlock), { p, block, side, event -> openSpeakeasy(p); true },
    ClickOptions.builder().cancelEvent(true).build())
triggers.onBlockType(this, Material.LEVER) { p, block, side, event -> false }
triggers.onBlockBreak(this, BlockKey.of(oreBlock)) { p, block, event -> respawnLater(block) }
triggers.onEntityClick(this, EntityMatch.scoreboardTag("vendor").withType(EntityType.VILLAGER)) { p, e, side, event -> false }
```

- `ClickOptions` [defaults]: `hands` [main hand only; Paper fires the interact event once per hand], `sides` [`RIGHT`;
  `LEFT`, `BOTH`, `PHYSICAL` for pressure plates], `ignoreCancelled` [true], `cancelEvent` [false: when true, a handler
  returning true cancels the click].
- **Which hand.** Paper fires a right click once per hand: the main hand first, then the off hand when the main hand
  did nothing with it (an empty main hand, for example). The default (main hand only) calls the handler once per
  physical click, from the main-hand event, even when the main hand is empty and the item is in the off hand: read
  `player.inventory.itemInOffHand` when that item matters, not `event.item`. MagicSpells ran on both events, which is
  why many passives carry a short cooldown; with the default that de-bounce isn't needed. With both hands in `hands`,
  the handler gets both events and must de-bounce itself. When a `cancelEvent` handler cancels the main-hand half,
  the off-hand half of the same click (same player and target, same tick) is cancelled too, so the off-hand item
  isn't used either (no block placed against a cancelled button, no bucket filled from a cancelled cow). That holds
  whatever the handler did meanwhile (closed its subscription, changed the block), and the off-hand half follows the
  player's latest main-hand half only.
- Clicks are handled at `HIGH` priority: after protection plugins, before `MONITOR` observers.
- Entity matches: `type`, `scoreboardTag`, `pdcKey`, `citizensNpc(id or null)`; add `withType` whenever you can, since
  matches without a type are tested for every clicked entity. Right clicks come from `PlayerInteractEntityEvent` /
  `PlayerInteractAtEntityEvent` (once per click), left clicks from `PrePlayerAttackEntityEvent`.
- Citizens player NPCs are `PLAYER` entities: for something only real players should trigger (a trade), use
  `EntityMatch.type(EntityType.PLAYER).notCitizens()`. `notCitizens()` works on any match except `citizensNpc`.
- Breaks: by default (`BreakOptions.ignoreCancelled` true) only breaks nobody cancelled, and once a handler cancels a
  break, later break handlers with the default don't see it. `BreakOptions.builder().ignoreCancelled(false)` also
  delivers cancelled breaks (a protection plugin's, or an earlier handler's): check `event.isCancelled` in the handler.

## Data tables

Content such as city buttons and forge areas can live in a YAML file, with each entry naming an action your plugin
resolves:

```yaml
- {kind: block-click, world: world, at: [10, 64, -3], side: right, action: speakeasy-door, args: {code: 1234}}
- {kind: block-break, world: world, at: [11, 64, -3], action: ore-respawn}
- {kind: area, world: world, min: [0, 60, 0], max: [5, 70, 5], riders: allow, action: forge-bellows}
```

```kotlin
val result = triggers.loadTable(this, File(dataFolder, "buttons.yml"), object : ActionResolver {
    override fun blockClick(action: String, args: Map<String, Any?>) = when (action) {
        "speakeasy-door" -> BlockClickHandler { p, _, _, _ -> openDoor(p, args["code"] as Int); true }
        else -> null   // null: rejected as an unknown action
    }
})
result.rejected.forEach { logger.warning("buttons.yml line ${it.line}: ${it.reason}") }
```

- Keys: `kind`, `world`, `action`, `args`; block-click `at`, `side`, `hands` (main, off, both), `cancel`,
  `ignore-cancelled`; block-break `at`, `ignore-cancelled`; area `min`, `max`, `riders`, `spectators`, `pass-through`, `fire-on-join`,
  `fire-on-register`, `step-events`. A map with an `entries:` list also works.
- Unknown keys, unknown actions, unloaded worlds, bad coordinates and resolver exceptions reject that entry, with its
  line; the rest load. Numbers must be plain decimal (`010`, `0x10` and `1:20` are rejected, never reinterpreted)
  and at most 64 characters. `args` keys must be plain names.
- Limits: a table file may be at most 2 MiB, with lines of at most 4,096 characters; a bigger file is refused as a
  whole (nothing changes). Aliases may expand an entry to at most 10,000 values. Loading is linear in the file size
  (about 0.4 s for 30,000 entries), except that many subscriptions on the same block or chunk cost quadratically
  (5,000 on one block: 50 ms; 20,000: 1 s).
- Calling `loadTable` again for the same file replaces the previous load. If the file can't be read or parsed at all,
  nothing changes and `replacedPrevious` is false. A reload of the same file from a handler that the load itself fired
  (an exit from closing the old areas, a `REGISTER` enter) is refused the same way.

## Signals (`com.sneakyutils.signals`)

Plain synchronous Bukkit events, so plugins can tell each other things without depending on each other:
`ArrestEvent`, `DutyChangedEvent`, `ActivityEvent`. Listen with `@EventHandler`. Fire with the static helpers, which
build nothing when no plugin listens:

```kotlin
ArrestEvent.fire(paladin, suspect, "theft")
ActivityEvent.fire(player, "catch", 1)
ActivityEvent.fire(player, "harvest", 1, mapOf("mob" to "cow"), cow.location, cow)   // where, and with what
```

- `ActivityEvent` has an optional `location` (copied) and `target` entity for consumers that care where it happened
  (a fishing district, a quest region) or to what (a harvested mob).
- Skill XP is not a signal here: SneakyLeveling owns it (`SkillXpGainEvent`); listen to that.
- Crimes are not a signal here either: SneakyCrime's own `CrimeCommitEvent` is the contract; fire and hear that.

## Performance rules

The service's budget is under 2% of the server thread with 100 players, many of them driving. That holds only if
subscribers keep to these:

- **Subscribe to the state, not to everyone.** Start a watch or add a player to a timer when the state begins, and
  stop it when it ends. A watch per player "just in case" puts work back on every step.
- **Prefer areas to step watches** for anything tied to a place, and keep `riders = SKIP` unless riders matter.
- **Keep handlers short.** They run inline on the server thread, possibly for many players a tick. Anything slow goes
  to your own async path.
- **Avoid `ALL_ONLINE` and interval-1 per-player timers**; most "every tick" checks have an event instead.
- **Don't listen to `PlayerMoveEvent` yourself** for what an area or watch can do.
- **Don't stack areas in one chunk.** A block step checks every area listed in the player's chunk: about 0.1 us each
  on a desktop, so 256 cost about 1 us a step and 10,000 about 35 us. Spread areas and the cost doesn't depend on how
  many there are; one area may span up to 64 x 64 chunks at no extra cost per step.

Measured on a desktop (Ryzen 9 7900X), engine core only (no Bukkit event dispatch, no handler bodies): 100 players,
70 of them in vehicles at 2 blocks a tick, with 1,000 areas over 10k x 10k blocks and 300 watches: about 6 us a tick
(8 us with the same areas in a 1k x 1k town). Timers: 50 timers with 100 players, about 0.3 us a tick plus the handlers.
Clicks with 10,000 registered blocks: 30 ns a miss. See `EngineCostTest` and `ServiceCostTest`.

## What not to do

- Don't teleport the player synchronously from a handler whose cause is `TELEPORT` (or from a respawn): Bukkit finishes
  the outer teleport after its event and overrides yours. Schedule it for the next tick. (SneakyUtils reconciles its
  own state on the next tick either way.)
- Don't call into the service from an area predicate; don't keep `Player` references from handlers past quit.
- Don't call any method off the server thread, including `close()`.
- Don't put player names, UUIDs or free text in anything that ends up in a diagnostics label; SneakyUtils builds its
  own labels from your plugin name and the subscription kind.

## Diagnostics

- `/triggers [stats|top|profile on|off]` (permission `sneakyutils.command.triggers`, op): subscriptions per kind and
  owner, areas and chunk entries, players tracked and riding, flagged all-online timers, the always-on counters, the
  top subscriptions by calls and time, and handler profiling without a capture (`profile on` resets the times).
  `stats` also prints warnings for layouts that make events expensive: a chunk listing over 256 areas, a player with
  over 16 watches, over 64 subscriptions on one block, block type or entity type, and dropped events or failing
  handlers and predicates. The first time a chunk passes 256 areas, the console gets a warning naming the plugin.
- With [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) installed (optional),
  `/diagnostics plugins start 30 SneakyUtils` records, as instrumentation `sneakyutils-ticks-1`: sections `tick`,
  `tick.mounted-poll`, `tick.timers` and `sub.<owner>.<kind>` per handler call; counts (the always-on counters, once a
  second) such as `steps`, `steps.noop`, `events.move`, `area.enters`, `handler.calls`; levels `players.tracked`,
  `players.riding`, `areas`, `areas.chunks`, `timers`, `timers.per-player`, `timers.all-online`, `subscriptions`.
  Nothing is timed outside a capture or profile, and a block step with nothing to do makes no probe call.
- `Triggers.stats()` returns the same numbers for tests and other plugins.
