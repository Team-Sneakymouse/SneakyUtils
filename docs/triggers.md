# Triggers: areas, occupancy, watches, timers, clicks and signals

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

- **Don't implement `Triggers`, `Subscription`, `PlayerTimer` or `Occupancy`.** Only SneakyUtils implements them; later
  additions come as default methods, but other implementations aren't supported. In tests, load SneakyUtils in
  MockBukkit, or put the few calls you make behind your own interface and fake that. Handler interfaces (`AreaHandler`,
  `OccupancyHandler`, `StepHandler`, `BlockClickHandler`, ...) are yours to implement: anything added to them later is
  a default method.
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
- **Spheres and cylinders** are built-in custom shapes. `Area.sphere(world, x, y, z, radius)` (or a `Vector` or
  `Location` centre): positions at most `radius` from the centre. `Area.cylinder(world, x, y, z, radius, height)`: an
  upright cylinder **standing on** the centre, from `y` up to `y + height`, horizontal distance at most `radius`. The
  surface, base and top count as inside. Players are tested at their feet position, and like every area only when
  their block changes, so a boundary that runs through a block is checked as they step into or out of that block.
  The box is every block the shape reaches (a sphere of radius up to about 500 fits the chunk limit).
- **Moving a shape**: `area.translate(dx, dy, dz)` returns a copy moved by whole blocks (box and shape together; a
  custom predicate keeps its own coordinates). A move that would leave the int block range throws
  `IllegalArgumentException`, as does a sphere or cylinder reaching past it. For fractional moves build the shape again
  at its new centre.
- **Limits**: an area may span at most 64 x 64 chunks. Areas are keyed by world UUID; unloading a world closes its
  areas.

## Occupancy

Who is inside an area, kept by the service: "is anyone at the board / in the mansion / near the toaster?" without an
enter and exit set of your own or a loop over every player. The handler hears when the first player comes in and the
last one leaves; the handle reads the count and the players, and can move the area (moving stations).

```kotlin
val toaster = triggers.occupancy(this, Area.sphere(block.location.toCenterLocation(), 4.0), object : OccupancyHandler {
    override fun onFirstIn(occupancy: Occupancy, player: Player, cause: OccupancyCause) { hum.start() }
    override fun onLastOut(occupancy: Occupancy, player: Player, cause: OccupancyCause) { hum.stop() }
})
if (toaster.isOccupied) toaster.players.forEach { warm(it) }   // players: a snapshot
```

```java
Occupancy board = triggers.occupancy(plugin, Area.cylinder(boardFoot, 6.0, 4.0),
    OccupancyHandler.of((occ, p, cause) -> showBoard(), (occ, p, cause) -> hideBoard()),
    OccupancyOptions.builder().label("jobboard:board").build());
int nearby = board.getCount();
boolean here = board.contains(player);
```

- **Calls** (`OccupancyHandler`, all default empty; `OccupancyHandler.of(firstIn, lastOut)` or
  `of(firstIn, lastOut, enter, exit)` for lambdas): `onFirstIn` when the count goes 0 -> 1, just before that player's
  `onEnter`; `onLastOut` when it goes 1 -> 0, just after that player's `onExit`; `onEnter` / `onExit` for every
  player. Each gets the occupancy, the player and an `OccupancyCause`: the area causes (`MOVE`, `VEHICLE`, `TELEPORT`,
  `JOIN`, `WORLD_CHANGE`, `REGISTER`, `QUIT`, `UNREGISTER`, `DEATH`) plus `GAME_MODE` and `AREA_MOVED`. For one player
  the calls alternate enter, exit, as for areas.
- **The handle** (`Occupancy`, a `Subscription`): `count`, `isOccupied`, `contains(player)` (no allocation), `players`
  (a new unmodifiable snapshot each call, in the order they entered: safe to iterate while moving players; don't keep
  it past the tick), `area`, `moveTo`, `translate`, `close`. It follows the calls delivered: a player counts from their
  `onEnter` to their `onExit`, so the count goes 0 -> 1 exactly when `onFirstIn` runs. Inside a handler, what another
  call set off (a teleport, a move, a close) shows from its own calls, which come after the current one.
- **Who counts** (`OccupancyOptions.builder()`, defaults in brackets):
  - `riders` [`ALLOW`: riders count; `SKIP`, `ONLY` as for areas, deciding entering only].
  - `includeSpectators` [false]. Unlike areas this holds all the time: a member who switches to spectator leaves
    (`GAME_MODE`), and switching back while still inside enters again (`GAME_MODE`). A spectator walking in isn't
    counted. A dead member who is switched to spectator leaves at once too (`GAME_MODE`); switching back while dead
    enters nothing until the respawn. The change is applied when its event fires and checked against the player's real
    mode on the next tick, so a handler that changes the mode again during it (the outer change wins), or a listener
    that cancels it late, is corrected then (for areas' spectator gate as well).
  - `countDead` [true]: a member who dies stays counted until they respawn outside the area (exit `TELEPORT`; respawning
    inside keeps them in without calls), quit (`QUIT`), change world (`WORLD_CHANGE`), or the area moves off them
    (`AREA_MOVED`). With false they leave on death (`DEATH`), as in areas. A player already dead when the occupancy is
    made, or who joins dead, enters at their respawn.
  - `label` [none], as for every subscription.
- **No silent stays.** Players already inside enter at once (`REGISTER`, before `occupancy(...)` returns unless it is
  called from a handler), and players joining inside enter (`JOIN`), so the count is always right.
- **When a shaped occupancy is re-tested** (sphere, cylinder, custom), and the one accepted limit:
  - on **block steps** (every reported change of block, as for areas);
  - on **teleport events**, also when the player stays in the same block (cause `TELEPORT`; `VEHICLE` for a rider
    carried by the teleport and `MOVE` for a dismount, as areas classify them), and on the next-tick correction after a
    handler teleported the player during their own teleport (same causes: `VEHICLE` for a rider). After a correction
    the members and counts are exact; the cause on its enter or exit is that of the teleport event that scheduled it,
    on a best-effort basis;
  - on the **explicit evaluations**: registering it, `moveTo` / `translate` (also `moveTo(occupancy.area)`), any game
    mode change (also one that has nothing to do with spectator, such as survival to creative), a mount or dismount,
    and the respawn; a death takes members out only with `countDead` off.
  - **Not** on plain walking or riding within one block: a player who crosses a shape's boundary without changing
    block is counted, or not, as at their last test, until the next of the above. Box occupancies depend on the block
    only, so this never shows for them. If a small shape must follow players within a block, re-test it yourself with
    `moveTo(occupancy.area)` when it matters.
  - **Riders and the poll.** The explicit evaluations use each player's last reported block. A rider's position is
    reported by the riding poll, once a tick (riders make no move events), so a rider who crossed into another block
    since the last poll is evaluated at the block they were in; the next poll, at most one tick later, reports the new
    block as a step and enters or exits them then (cause `VEHICLE`). A `moveTo` onto a rider can therefore return
    before their `onFirstIn`, which follows within a tick. Areas have the same one-tick lag for riders.
- **Where players are tested.** At the exact position at that moment: the reported one for steps and teleports, the
  player's current one for the explicit evaluations (a player whose own move, teleport or respawn is being handled:
  that event's destination). This is for occupancies only: areas, pass-through paths and watches are tested at the
  last reported step, with or without occupancies around. A member found outside by an explicit evaluation (they
  walked out of a small shape without changing block) leaves with the cause of what found them: `GAME_MODE` when they
  turned spectator (only then: the mode change itself is why they stop counting), `MOVE` or `VEHICLE` (on foot or
  riding) for any other game mode change, as they left by moving, and the same for a player such a change finds
  inside (also a spectator switch, for an occupancy that includes spectators; and a mount or dismount, for one whose
  rider policy takes both: on foot before a mount, riding before a dismount); `MOVE` when they mount (they left on foot), `VEHICLE` when they dismount, `AREA_MOVED` for a `moveTo`; a member who dies with `countDead` off leaves with `DEATH` wherever they stand, and with `countDead` on
  stays until the respawn is evaluated.
- **Lifecycle** as for areas: owned by your plugin and closed silently when it disables (nothing reaches it, the handle
  empties); `close()` is idempotent and takes everyone out (`onExit`, then `onLastOut`, cause `UNREGISTER`); unloading
  the area's world closes it. `/triggers` lists it as kind `occupancy` (and in the area chunk entries). A handler that
  disables its own plugin (or the occupancy's owner) gets no further call, even the second half of a pair (`onEnter`
  after `onFirstIn`, `onLastOut` after `onExit`); one that only closes the occupancy still gets it.
- **Handler loops.** When one event sets off more than the 10,000 calls a drain allows (see the rules above), the rest
  are dropped, occupancy calls included: `onEnter`, `onExit`, `onFirstIn` and `onLastOut` that were still queued are
  skipped. The occupancy's count and players are then set to the engine's actual membership, silently, so they stay
  right; a first-in or last-out that fell in the dropped part is not called later.

### Re-anchoring (moving stations)

`occupancy.moveTo(newArea)` (or `translate(dx, dy, dz)`) replaces the area at once:

- Players inside both the old and the new area stay inside, with no calls. Players only in the new area enter, players
  only in the old one exit, both with cause `AREA_MOVED`.
- Enters are delivered before exits, so `onFirstIn` and `onLastOut` fire only on real 0 <-> 1 changes: a move that
  swaps one player for another calls neither.
- The usual rules decide who enters (riders, spectators; dead players don't enter, but a dead member stays while the
  new area still holds their position).
- Players are tested where they are now. For a sphere, cylinder or custom shape that is their current exact position
  (the engine otherwise stores it only on block steps); a player whose own move, teleport or respawn is being handled
  (`moveTo` from that handler) is tested at the destination of that event (also a teleport within the same block).
  Boxes depend on the block only. Passing the current area again (`moveTo(occupancy.area)`) re-evaluates who is inside
  without touching the index.
- The new area may be in another world: everyone in the old world exits. Any shape works.
- From a handler it takes effect at once and its calls come after the current one; inside an area predicate it throws
  `IllegalStateException`, as on a closed occupancy.

```kotlin
// A station flying along x, one block a tick, from a timer that runs while it flies.
val station = triggers.occupancy(this, Area.cylinder(deck, 12.0, 6.0), crew)
val flight = triggers.every(this, 1) { station.translate(1, 0, 0) }
```

**Carried players.** Players riding along are re-evaluated when their own position is reported (the move event or the
1-tick riding poll), which may come before or after your `moveTo` in a tick. Make a moving area larger than its deck by
at least what it moves in a tick, so a carried player never sits outside it between the two updates (that would be an
exit and an enter).

**Cost.** Block steps cost what an area's do: an occupancy is one entry in the chunk index, and a member's step adds a
field read for the spectator rule. Nothing runs per tick unless you move it. A `moveTo` is one box test per online
player (and the shape test for those in the box), a second pass over the online players when the area had members,
and index updates only for the chunks the area starts or stops touching: none for a move inside the same chunks, a
column or row of chunks when it crosses a chunk border. Calling it every tick for a few moving stations is fine;
hundreds of stations moving every tick with many players online is not what it is for.

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

### Block removal

Furniture and devices anchored to a block hear when that block goes, whatever removes it, without sweeps or timers:

```kotlin
val anchor = triggers.onBlockRemoved(this, BlockKey.of(supportBlock)) { block, cause, event -> tv.unmount(block) }
```

```java
Subscription anchor = triggers.onBlockRemoved(plugin, BlockKey.of(supportBlock),
    (block, cause, event) -> tv.unmount(block),
    BlockRemovedOptions.builder().label("myplugin:tv-anchor").build());
```

Each removal is reported once, by the event that decides it (read against Paper 26.2's server code):

| Cause | Event (Paper 26.2) | When |
|---|---|---|
| `BREAK` | `BlockBreakEvent` | a player breaks it (not a spectator, and a game-master block such as a command or structure block only when Paper lets that player break it) |
| `EXPLODE` | `BlockExplodeEvent`, `EntityExplodeEvent`; `TNTPrimeEvent` (`EXPLOSION`) | it is in the explosion's `blockList()`, for explosions that destroy blocks (`DESTROY`, `DESTROY_WITH_DECAY`; not a wind charge's `TRIGGER_BLOCK`), and is a block an explosion can destroy (not bedrock, barrier, end portal frame, end portal, end gateway, command, structure or jigsaw blocks); or it is the bed half that was used or the respawn anchor that exploded itself; or it is TNT the explosion primes; or an ender dragon flies through it |
| `BURN` | `BlockBurnEvent`; Paper's old `TNTPrimeEvent` (`FIRE`) | fire burns it, or primes it (TNT; by then fire may already stand in its place) |
| `FADE` | `BlockFadeEvent` | it fades, melts or turns into another block (snow, ice, fire burning out, coral dying, grass or farmland to dirt) |
| `DECAY` | `LeavesDecayEvent` | leaves decay |
| `PISTON` | `BlockPistonExtendEvent`, `BlockPistonRetractEvent` | a piston moves it away from this position, or breaks it (Paper's `getBlocks()` lists both, at their original positions); or it is the head of a retracting piston (reported even when a sticky pull is cancelled: Paper clears the head before that event and doesn't put it back) |
| `DESTROY` | Paper's `BlockDestroyEvent` | the server destroys it: losing its support (a torch, sign or carpet), `/setblock ... destroy`, and entity breaks that go through the server's destroy (wither, ravager, villager harvesting, sheep eating short grass, rabbit, silverfish, boat on a lily pad, projectile on a chorus flower, pot or dripstone, trampled crops or turtle eggs); and, on the next tick, a watched rail, redstone wire, repeater or comparator whose support another reported event took away (see below) |
| `ENTITY` | `EntityChangeBlockEvent`; `TNTPrimeEvent` (`PROJECTILE`) | an entity changes it directly: turns it into another type (sheep eating a grass block, trampled farmland, silverfish infesting stone, a falling block landing on grass), or removes it itself (an enderman, sand or gravel starting to fall, eating the last slice of cake); or a burning projectile primes it (TNT) |
| `FLUID` | `BlockFromToEvent`, `FluidLevelChangeEvent` | flowing water or lava washes it away (the target isn't air, a fluid or waterloggable); or it is a fluid that recedes or turns into another block (a level change within the same fluid doesn't count) |
| `PRIME` | `TNTPrimeEvent` (`REDSTONE`, `PLAYER`, `DISPENSER`) | TNT is lit and turns into primed TNT |
| `TELEPORT` | `BlockFromToEvent` (face `SELF`) | a dragon egg teleports away |
| `SPREAD` | `BlockSpreadEvent` | something spreads onto it and turns it into another block (sculk over stone, grass or mycelium over dirt) |
| `REPLACE` | `BlockPlaceEvent`, `BlockMultiPlaceEvent` | a player places another block in its position (a block into short grass, a door over flowers), accepted and with `canBuild()`; the world already holds the new block when the handler runs, the old one is the event's replaced state |
| `FORM` | `BlockFormEvent` (and `EntityBlockFormEvent`) | a block forms in its place: concrete powder hardening, lava turning to obsidian or cobblestone, water turning to stone or freezing, frost walker ice (not copper weathering; concrete powder placed straight into water is `REPLACE`) |
| `ABSORB` | `SpongeAbsorbEvent` | a sponge soaks it up (water, water plants); a block that only loses its water stays and isn't reported |
| `BUCKET` | `PlayerBucketFillEvent` | a player scoops it up with a bucket: a water or lava source, powder snow or a bubble column (a waterlogged block only loses its water and isn't reported) |

- **Timing.** Every event is observed at `MONITOR` with `ignoreCancelled`, so a cancelled event is never delivered and
  the removal is certain. The handler runs before the event is applied: `block` still holds the old block (except for
  `REPLACE`, which Paper applies before its event, and for a bed or respawn anchor exploding by itself, which Paper
  removes before its `BlockExplodeEvent`, and fire-primed TNT that fire has already replaced; those last are delivered
  even when their event is cancelled, since they are gone either way). Read the event; never cancel or change it.
- **The deciding event.** Where Paper fires two events for one removal, the later, deciding one reports it: an entity
  break that goes on to the server's destroy comes as `DESTROY` (a protection plugin cancelling the `BlockDestroyEvent`
  keeps the block, and nothing is reported), not from its `EntityChangeBlockEvent`; TNT comes from its `TNTPrimeEvent`,
  not from the explosion list, `BlockBurnEvent` or projectile that set it off, so TNT whose priming is cancelled and
  stays is never reported; for fire that is Paper's old `TNTPrimeEvent`, which fires after the Bukkit one and can still
  keep it. TNT broken by a player is `BREAK` (its `BLOCK_BREAK` priming is not reported again). An ender dragon at its
  default yield of 0 removes everything its `EntityExplodeEvent` lists, TNT included; when a plugin changes that yield,
  Paper's old `TNTPrimeEvent` (`EXPLOSION`) decides each listed block and reports it. A break Paper refuses after the
  `BlockBreakEvent` (a spectator, or a game-master block without creative and op, or a command block without
  creative and `minecraft.commandblock`) is not reported; op stands in for Minecraft's permission level 2.
- **Once per watched block per event.** An explosion or piston that removes several watched blocks calls each of their
  subscriptions once, in the event's order; a position the event lists twice is delivered once, at its first place.
  A subscription also gets at most one delivery of a cause per server tick: Paper can report one removal twice in a
  tick (a sponge placed with `Block.setType(SPONGE, true)` into still water fires two `SpongeAbsorbEvent`s with the
  same positions), and the second is dropped. A different cause in the same tick still comes.
  Explosion list entries count by their coordinates in the explosion's world, as Paper applies them (a plugin may add
  a block object of another world); air in a list is skipped. Paper's `isDestroyable` keeps bedrock, barriers, end
  portal frames, end portals, end gateways, command, structure and jigsaw blocks even when a plugin lists them, so
  those aren't reported (an ender dragon removes what it lists without that check; assumes Paper's unsupported setting
  `allow-permanent-block-break-exploits` is off).
- **Blocks that drop without an event.** Paper 26.2's rails (all four kinds) remove themselves in `neighborChanged`
  (`dropResources` + `removeBlock`) when their support goes, with no event at all. Redstone wire, repeaters and
  comparators do the same when the neighbour update reaches them first (their support broken or blown up); when the
  shape update comes first (a piston moving the support) they go through the server's destroy and fire
  `BlockDestroyEvent` instead (checked on a live Paper 26.2 server). Every other support-dependent block (torches,
  signs, carpets, buttons, levers, pressure plates, doors, beds, plants) goes through the server's destroy and comes as
  `DESTROY` at once. So when a reported event (any cause above) takes a block away (for an explosion: only the
  targets it really destroys, not TNT left to its prime event or the blocks Paper protects), the watched blocks that
  rest on it are queued for a check on the next tick: a rail, wire, repeater or comparator just above it, and a rail beside it
  that ascends towards it (an ascending rail also rests on its uphill side; its shape says which). If one has changed
  by then it is reported as `DESTROY` with the event that took its support, to the subscriptions that were watching
  when that happened (closed ones are skipped, newer ones wait for the next removal). The block is already gone when
  that handler runs, and the event is the support's (a `BlockBreakEvent`, an explosion...). A block its own event
  reported meanwhile (a `BlockDestroyEvent`, an explosion listing both) isn't reported again, and when two events in a
  tick take supports of the same rail, the first one is reported. Not covered: a support removed without an event
  (`setType`, WorldEdit), so the rail on it isn't reported either. There are no chains to follow: none of those four
  can rest on another of them.
- **Two reports for one spot.** A block burnt away (`BURN`) usually becomes fire, and that fire burning out later is a
  `FADE` at the same position; likewise anything that later replaces what took a block's place. Close the subscription
  in the first call (the usual pattern for an anchor) and the rest never reaches you.
- **Only the position you watch.** The other half of a door, bed or tall plant is another block; watch both if both
  matter. An exploding bed reports only the half that was used: Paper removes the other half before the event only
  when it is the matching half, and nothing left at the event shows whether it was, so that half isn't reported. A
  sticky piston whose pull can't move its blocks loses its head with no event at all; that head isn't reported either.
  Changes plugins make without an event (`setType`, `breakNaturally`, WorldEdit) are not reported, nor is a bed or
  anchor exploding with `mobGriefing` off (Paper fires no event then).
  Also not reported: a dispenser scooping a source up with a bucket (its event is the dispense, before the pickup is
  decided) and a hoe tilling grass or dirt (no event of its own). Other item uses that change a block (stripping a log,
  making a path, brushing suspicious sand) fire `EntityChangeBlockEvent` and come as `ENTITY`. A rail on TNT that fire
  primes may drop before Paper's old `TNTPrimeEvent(FIRE)` queues its check, so that rail isn't reported.
- **Not used:** `BlockPhysicsEvent` (far too frequent; a block losing its support comes as `DESTROY`), and Paper's
  `BlockBreakBlockEvent` (not cancellable; the piston and fluid breaks it reports come from the events above). Paper's
  old `com.destroystokyo` `TNTPrimeEvent` (deprecated for removal) is used for the two paths it still decides:
  fire-primed TNT and a dragon at a changed yield. Listening to it makes Paper log a deprecation warning when the first
  removal watch is made ("... has registered a listener for com.destroystokyo.paper.event.block.TNTPrimeEvent ...");
  that is expected, and needed until Paper stops deciding those paths with it. If Paper drops it, those are reported
  from the events before it (the Bukkit `TNTPrimeEvent(FIRE)` and the dragon's `EntityExplodeEvent`) and a cancelled
  old event can't be seen.
- **Cost.** SneakyUtils listens to these events only once something has subscribed. Then an event costs one lookup per
  block it touches (an explosion looks up each block in its list; a retracting piston also its head, found from the
  base's own facing), plus five for the positions that may rest on each block it takes away (the next-tick check
  above); a neighbour's type and shape are read only when it is watched. One world lookup comes first, and nothing at all happens when the
  world has no watched blocks. `BlockFromToEvent` fires for every fluid flow, so this is one lookup per flow while
  any removal is watched in that world. `/triggers stats` counts `events.block-removed`, `block-removed.lookups`,
  `block-removed.neighbour-checks` and `block-removed.neighbour-removals`. Same rules as the other indexes: server
  thread only, errors contained, subscribing or closing inside a handler takes effect from the next event (an event
  that removes several positions looks them all up first), owner disable and world unload close the subscriptions.

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
- Don't put player names, UUIDs, locations or other varying text in a diagnostics label (see Diagnostics): a label
  names a job, not an instance.

## Diagnostics

### `/triggers`

- `/triggers [stats|top|profile on|off]` (permission `sneakyutils.command.triggers`, op): subscriptions per kind and
  owner, areas and chunk entries, players tracked and riding, flagged all-online timers, the always-on counters, the
  top subscriptions by calls and time (with their labels and calls of 1 ms or more), and handler profiling without a
  capture (`profile on` resets the times). `stats` also prints warnings for layouts that make events expensive: a chunk
  listing over 256 areas, a player with over 16 watches, over 64 subscriptions on one block, block type or entity type
  (clicks, breaks and removal watches),
  and dropped events or failing handlers and predicates. The first time a chunk passes 256 areas, the console gets a
  warning naming the plugin.
- `Triggers.stats()` returns the same numbers for tests and other plugins.

### Labelling your subscriptions

Every handler runs inside SneakyUtils' listeners and tasks, so a profiler, or SneakyDiagnostics' scheduler and listener
probes, put its cost on SneakyUtils. SneakyUtils' own capture splits it again by owner plugin, callback and **label**.
Give each job its own label so a capture names it:

```kotlin
val triggers = SneakyUtils.getTriggers().labelled("myplugin:forge")      // every subscription made through it
triggers.area(this, forge, handler)                                       // -> label myplugin:forge
triggers.area(this, pad, padHandler, AreaOptions.builder().label("myplugin:forge-pad").build()) // options win
SneakyUtils.getTriggers().every(this, 20, task, TimerOptions.builder().label("myplugin:cleanup").build())
```

- Every options type (`AreaOptions`, `OccupancyOptions`, `WatchOptions`, `PlayerTimerOptions`, `ClickOptions`,
  `BreakOptions`, `BlockRemovedOptions`, `TimerOptions` for `every`) has `.label(...)`. `Triggers.labelled(label)` returns a view that labels everything
  made through it whose options have no label; keep one per feature. `loadTable` through a view is not labelled.
- A label is 1 to 64 characters of `a-z 0-9 . _ : -`, starting with a letter or digit (`TriggerLabels`); anything else
  throws `IllegalArgumentException`. Prefix it with your plugin or feature (`holdover:forge-bellow`).
- A label names a **job, not an instance**: the same on every start, shared by all subscriptions that do the same
  thing (a rule's 200 areas share one label). Never a player, UUID, location or counter: a capture holds at most 256
  names, and per-instance labels would only fold together (below).
- Unlabelled subscriptions are still attributed to their owner and callback; the label only splits them further.
  `Subscription.label` and `SubscriptionStats.label` read it back.

### What a capture records

With [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) installed (optional),
`/diagnostics plugins start 30 SneakyUtils` (or `/diagnostics all start`) writes `plugins-sneakyutils-1.json`,
instrumentation `sneakyutils-ticks-2`, in the shared `sneakydiagnostics.probe/1` shape (see SneakyDiagnostics'
`docs/report-schema-v2.md`: calls, total and self nanoseconds, percentiles, the 10 slowest calls, per-second series).

**Sections** (server-thread wall time; strictly nested, so `selfNanos` adds up):

| Section | What |
|---|---|
| `handler.<owner>.<callback>[.<label>]` | one subscription's handler calls; `<owner>` is the plugin name in lower case, `<callback>` is `area-enter`, `area-exit`, `area-step`, `occupancy-first-in`, `occupancy-last-out`, `occupancy-enter`, `occupancy-exit`, `step-watch`, `block-under`, `block-watch`, `timer`, `player-timer`, `player-timer-all-online`, `block-click`, `block-type`, `block-break`, `block-break-type`, `block-removed` or `entity-click` |
| `move` | a PlayerMoveEvent that changed block: area lookup, watch checks and (nested) the delivery |
| `teleport` | a teleport (including riders moved by teleport and dismounts) |
| `player-state` | join, quit, death, respawn, world change, game mode change |
| `riding`, `resync` | the next-tick re-reads after (dis)mounts and nested teleports |
| `drain` | delivering one event's queued enters, exits and steps; its self time is the queue's own cost |
| `tick`, `tick.mounted-poll`, `tick.timers` | the 1-tick ticker: riders polled, timers run |
| `click.block`, `click.break`, `click.entity` | a click, break or entity click that matched subscriptions |
| `block.removed` | a removal event that touched watched blocks (all of them, for an explosion or piston) |
| `area-index.add`, `area-index.remove` | registering and closing areas and occupancies (index updates, `fireOnRegister` enters nested) |
| `area-index.move` | an occupancy's `moveTo` (index updates, re-evaluation; its calls nested) |
| `handler.other` | handler calls past the name budget (below) |

A handler's section is nested in the stage that delivered it, so a stage's `selfNanos` is SneakyUtils' own cost and
the `handler.*` rows are the subscribers'. Handler sections from one plugin add up to what that plugin's subscriptions
cost, wherever they ran.

**Counts:** the always-on counters, once a second as the change since the last second (`events.move`, `steps`,
`steps.noop`, `area.enters`, `area.exits`, `handler.calls`, `handler.errors`, `handler.slow`, `queue.dropped`,
`queue.self-triggered`, `reentrant.dropped`, `predicate.errors`, `events.block-removed`, `block-removed.lookups`, ...); and per subscriber, under the handler's name:
`handler.errors.<owner>.<callback>[.<label>]` (a throw; `area-predicate` or `occupancy-predicate` for a custom
shape's predicate),
`handler.slow.<...>` (a call of 1 ms or more), `handler.self-skipped.<...>` (calls skipped because that subscription's
own handler caused them), `queue.dropped.after.<owner>.<kind>[.<label>]` (events dropped by the loop limit, after
that subscription's handler ran last). `diagnostics.names-folded` counts names folded by the budget.

**Levels** (once a second): `players.tracked`, `players.riding`, `players.watched`, `areas` and `areas.chunks` (with
occupancies),
`timers`, `timers.per-player`, `timers.per-player.players`, `timers.all-online`, `subscriptions`,
`subscriptions.by-kind.<kind>`, `subscriptions.by-owner.<owner>.<kind>` (the first 200 pairs of the capture; the rest
in `subscriptions.by-owner.other`).

**Name budget.** The probe keeps at most 256 names per kind. SneakyUtils admits the first 192 handler section names of
a capture as they are; later ones fold into `handler.<owner>.<callback>` (the label dropped), and past 224 into
`handler.other`. Counts do the same (160, then 200, then `handler.errors.other` and so on). Each fold adds to
`diagnostics.names-folded`, so a report with folded names says so; it stays `complete`. If it is non-zero, use fewer,
broader labels.

**Cost.** Nothing is timed, counted or allocated outside a capture or `/triggers profile on`: each move, drain, tick
and click reads one field, and a drain, tick or matched click asks the probe once whether a capture runs (an
interface call; a null check without SneakyDiagnostics), never once per handler. `DiagnosticsCostTest` measures it:
1,000,000 block steps with 250,000 labelled handler calls allocate nothing and cost the same with SneakyDiagnostics
installed and idle as without it. During a capture each handler call costs two clock reads and a probe section
(about 40 ns in SneakyDiagnostics' recorder). A capture that begins shows handlers from the first event that delivers
something, and stages from then on (at the latest a second later).

### Reading a capture

1. Open `plugins-1.json` in the capture folder and follow SneakyUtils' `file` entry. Check `complete`,
   `measurements.omittedKeys` and `measurements.nestingErrors` (all should be clean), and
   `counts["diagnostics.names-folded"]`.
2. **Which plugin or job is slow:** sort `sections` by `selfNanos`. `handler.<owner>.*` rows are subscribers' time;
   sum them per owner for a plugin's total. `meanNanos` and `p99Nanos` show whether it is every call or a few.
3. **Single slow calls:** `slowest` lists the 10 longest calls with their section (owner, callback, label) and when
   (`atMillis`, `atTick`); `handler.slow.*` counts every call of 1 ms or more.
4. **Firing far more than expected:** a handler row's `calls` against what the feature should do (an area entered
   once per visit, a timer `capture seconds x 20 / interval` times); the per-second `series` (for the 16 most
   expensive sections) shows bursts. `subscriptions.by-owner.*` levels show subscriptions piling up (a leak looks like
   a level that only grows).
5. **Failing handlers:** `handler.errors.<owner>.<callback>[.<label>]`; the server log has the stack trace (once a
   minute per subscription, with its id; `/triggers top` maps ids to labels).
6. **SneakyUtils itself:** the stage rows' `selfNanos` (`move`, `drain`, `tick.mounted-poll`, ...). SneakyDiagnostics'
   own scheduler and listener probes (Ignite) still list SneakyUtils as the owner of this time; this report is what
   splits it.

SneakyDiagnostics may be missing, installed later, reloaded or removed: SneakyUtils keeps working, unmeasured while it
is absent, and measures (and samples) again when its service comes back (`integration/Probe.java`, the shared shim).
