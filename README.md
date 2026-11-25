# SneakyUtils

Shared **Paper** utilities and managers for Team-Sneakymouse / Lords of Minecraft plugins.

This is a live Paper plugin (not a compile-only library). Install the JAR on the server so managers keep working memory; depend on the published artifact at compile time for the API.

## What’s included (v1)

- `TextUtility` — Unicode escape decoding (`\uXXXX` / `\UXXXXXXXX`), legacy `&`/`§` → MiniMessage → Adventure `Component`, plus balanced line wrapping
- `AlteredBlockManager` — reversible temporary block changes with persistent Marker fallback (scoreboard tag `sneakyutils_altered_block`, PDC keys under `sneakyutils:`); changes can carry causes from the fixed `AlteredBlockCauses` set
- MagicsSpells-ported static helpers under `com.sneakyutils.util`: location/XP/color/params, `BlockUtils`, `EntityPdc` (injectable namespace), attribute/particle/sound/potion name resolution, geometry (`BoundingBox` / `bounds`), `AtomicFiles`, and small Bukkit/stdlib helpers
- `PlayerUtil` — SCM noformat character names and active character id via optional PAPI, CMI-aware target eligibility, dispatch on-duty checks, and Bukkit account-name lookups; the PAPI reads are cached per player for a few ticks (`player-util.*-cache-ticks`, see [Player helpers](#player-helpers)), with `clearCachedChecks(player)` to drop them
- `PlayerUtil.conceal(owner, player)` / `reveal(owner, player)` / `isConcealed(player)` (`PlayerConcealment`) — hide a player from everyone, themselves included, without spectator mode or `hidePlayer`. Concealing a player:
  - makes them invisible;
  - sends their held items and armour to other players as empty (a packet filter, so the server re-sending them doesn't undo it);
  - shows them their own armour, hotbar and offhand as empty;
  - hides everything riding them (worn cosmetics, nametag displays) from every viewer.

  They stay a real entity and stay in the tab list. Each owning plugin counts separately, so one plugin's `reveal` doesn't undo another's. Concealment comes off on quit and when SneakyUtils disables. Used by `WatchEngine` watchers and SneakyPoses sleepers
- `Toasts.show(player, title, icon, itemModel)` — a toast in the corner of one player's screen (MagicSpells' `toast` effect), through a hidden advancement made once per distinct title and icon and saved by Bukkit, so keep titles to a small fixed set
- `Waypoints.guide(player, location, path)` — point a player at a place with the server's `findlocation` waypoint; the commands (run as the player) are `waypoints.commands` in SneakyUtils' config
- `PlaceholderUtil` — optional PlaceholderAPI `setPlaceholders` for consumers that already depend on SneakyUtils (no local PAPI compile dependency required for expansion alone)
- `DisplayOnlyInventoryHolder` — marker for display-only GUI inventory holders; whole-inventory scans on open (SneakyItems updater, expiration) skip them; the owning plugin must keep real items out of the slots. `DisplayOnlyInventoryHolder.isDisplayOnly(inventory)` checks it
- `ToastUtil.show(player, icon, title[, frame])` — an advancement toast for one player without a real advancement (nothing shows in their advancement screen or is saved). Server thread; uses the server's classes by reflection and returns false (warning logged once) when they don't match
- `ServerUtil` — server display name and kebab-case server id from the `P_SERVER_NAME` environment variable. Platform-free (in `sneakyutils-common`), so Velocity plugins can use it too
- `com.sneakyutils.watch.WatchEngine` — looking through a camera (from SneakyCCTV): eye held at the lens within a look cone, a mannequin body left behind, adventure/spectator watching with `WatchRestrictions`, left/right switching, crash-safe sessions file; one subclass per kind of camera (SneakyCCTV, SneakyHaunted's ghost cams), a player in one at a time. `Directions` holds the yaw/pitch/cone maths
- Cross-plugin ports: `InventoryUtility` (Paper YAML + legacy Base64), `PermissionUtility`, `PoseFacing` / `Direction`, `ItemMetaText`
- `com.sneakyutils.triggers` (`SneakyUtils.getTriggers()`) — shared trigger service replacing MagicSpells passive triggers: areas (enter/exit/pass-through, rider policy; boxes, custom shapes, spheres and cylinders), area occupancy (who is inside, first-in/last-out callbacks, re-anchoring for moving areas), per-player step and block watches, staggered global and per-player timers, block click/type/break and entity click indexes, block removal watches (breaks, explosions, fire, fading, decay, pistons, physics, entities, fluids, TNT priming, dragon eggs, spreading, placement over it, forming, sponges, buckets, rails and wire losing their support), YAML data tables with named actions; `/triggers` stats (permission `sneakyutils.command.triggers`), optional SneakyDiagnostics timing per owner, callback and subscription label. Usage and rules: [docs/triggers.md](docs/triggers.md)
- `com.sneakyutils.targets` (`VirtualTargets`) — the one call a weapon makes to hit things that are not entities (SneakyHorde's packet-only figures): `damageLine`, `damageFirst`, `damageSphere`, `damageExplosion`, `firstAlong`; plugins that own such targets register a `VirtualTargetProvider`, and `VirtualTargetDamageEvent` lets a weapon change or cancel any hit on one. Usage: [docs/virtual-targets.md](docs/virtual-targets.md)
- `com.sneakyutils.signals` — shared gameplay events (`ArrestEvent`, `ChainedEvent`, `DutyChangedEvent`, `ActivityEvent`) with `fire(...)` helpers that skip building the event when nobody listens

## Consumer usage

Maven coordinates:

```text
io.github.team-sneakymouse:sneakyutils:<version>
```

```kotlin
repositories {
    maven("https://maven.sneakyrp.com/sneakymouse")
}

dependencies {
    compileOnly("io.github.team-sneakymouse:sneakyutils:VERSION")
}
```

Ensure `SneakyUtils` is installed and loads before plugins that call `SneakyUtils.getAlteredBlockManager()` or `SneakyUtils.getTriggers()`.

SneakyUtils optionally depends on SneakyDiagnostics (`load: BEFORE`, not required) to time triggers during captures; the API is compile-only and never bundled.

### Platform-free helpers (`sneakyutils-common`)

Helpers with no Paper, Velocity or Kotlin dependency (currently `ServerUtil`) live in the
`common/` module, published as `io.github.team-sneakymouse:sneakyutils-common:<version>` with the
same version as `sneakyutils`. It is plain Java (Java 25, like the rest of the build) and has no runtime dependencies.

- **Paper plugins:** change nothing. `sneakyutils` re-exports `sneakyutils-common` and the
  SneakyUtils plugin jar bundles it, so keep `compileOnly(sneakyutils)` and the `SneakyUtils`
  `depend`. Do not also bundle `sneakyutils-common` in a Paper plugin.
- **Velocity (and other non-Paper) plugins:** SneakyUtils is not installed there, so bundle the
  module into your plugin jar, relocating it if you shade:

```kotlin
dependencies {
    implementation("io.github.team-sneakymouse:sneakyutils-common:VERSION")
}
```

Add a helper here only when it needs nothing beyond the JDK. Anything that touches Bukkit/Paper
stays in the root module.

Soulbound/expiration item behaviors moved to **SneakyItems** (`com.sneakyitems.util.items.ItemBehaviors`,
`ItemExpireEvent`). PDC keys remain `magicspells:soulbound_owner` / `magicspells:expires_at`.

## Temporary block changes

Use the shared manager on the **server thread**:

```java
var manager = SneakyUtils.getAlteredBlockManager();
// 100 server ticks; automatic restoration uses false for physics as well.
var change = manager.apply("my-plugin:effect", block, Material.AIR, false, 100L);
// Optional early restoration cancels the scheduled undo:
change.undo(false);
```

Existing untimed overloads remain unchanged. Optional timed overloads (Java and Kotlin):

```text
apply(String, Block, Material, boolean applyPhysics, long durationTicks): Change
apply(String, Block, BlockData, boolean applyPhysics, long durationTicks): Change
register(String, Block, BlockData fromData, BlockState fromState,
         boolean applyPhysics, long durationTicks): Change
add(Change, boolean applyPhysics, long durationTicks): void
```

- Durations must be **positive**; zero/negative values throw `IllegalArgumentException`
  before world mutation or registration. Omit the duration to keep a change untimed.
- Durations use synchronous Bukkit scheduler ticks (nominally 20 ticks/second),
  not wall-clock time; server lag delays expiration.
- `applyPhysics` controls both the initial `apply` write and automatic restoration.
- `onUndone(Change, Runnable)` runs a callback once when an active change is undone for any reason
  (timed expiry, explicit undo, undo by name/cause, `restoreAll`, or spliced out under a newer change),
  so a feature can remove what it attached to the change (such as a display entity) without its own
  expiry. Callbacks are not persisted across restarts; throws `IllegalStateException` for an inactive change.
  For `register`/`add`, it controls restoration only: these APIs never perform the edit.
  Capture original data/state **before** an external edit, then register afterwards.
- `Change.undo(...)`, `undoAll(...)`, and plugin-disable `restoreAll()` cancel timers.
  Explicit undo uses its own physics argument. Use the exact registered/returned
  `Change` instance, not a structurally equal copy; register each instance only once
  while active. Record components and existing method signatures are preserved.
- Items of a block with an inventory (chests and other containers, chiseled bookshelves,
  decorated pots, shelves, lecterns with their page) are saved from the captured
  state's snapshot inventory and written back through a fresh state's snapshot inventory, so
  undo, timed restore and crash recovery put them back. Other block-entity data is kept only
  where `serializeTileData` lists it (signs, spawners, banners); furnace cook progress, campfire
  items and brushable-block items, for example, are not. A jukebox's record is not saved or
  emptied either (Paper drops it when the jukebox is replaced): on Paper, writing a record back
  updates neighbours even with physics off.
- Only `apply` takes over a block's inventory, only for the block's first change (the one whose
  state the marker persists), only when it turns the block into another block type, and only
  once Paper has accepted the block's recovery marker (spawned before anything else changes; if
  an `EntitySpawnEvent` listener refuses it, the items are left to Paper, which drops them as
  the block entity goes, and the change holds none). The change holds items exactly when apply
  emptied them, so a physics callback that breaks the block during apply can't make it hold
  items that already dropped. It empties the block's live inventory after capturing its state,
  so Paper drops nothing on the ground (the
  change holds the items and undo, timed restore or crash recovery puts them back, so they are
  never duplicated). For one half of a double chest only that half's own inventory is emptied;
  the other half keeps its items. Block-entity contents that are not saved (campfire items, a
  brushable block's item, ...) are not emptied and drop or vanish as they would without the
  manager.
- `register` and `add` record an external edit, which already decided what became of the
  inventory (dropped, kept, moved), so their changes never hold items: undo, timed restore and
  crash recovery write back the BlockData and non-inventory tile data only, and a restored
  container keeps whatever its block entity holds then (a fresh one is empty).
- A change to the same block type (a chest turned to face east, via `apply` or an external edit
  registered afterwards) keeps the block entity and its items and does not take the inventory:
  its undo, timed restore and crash recovery write back the BlockData and saved non-inventory
  data (a lectern's page, for example) and leave the live items as they are, so items taken out
  in between don't come back.
- A later change on a block that is already changed never takes or empties the inventory: if it
  replaces a block with an inventory (a rotated chest broken afterwards), Paper drops the items
  as it would without the manager, and no undo or crash recovery writes them back.
- Saved items never overwrite a live inventory that holds items (a chest placed again by a later
  change and filled by players): the live items stay and the saved ones drop at the block.
  If another plugin cancels the `ItemSpawnEvent` of such a drop, that item is gone, as it would
  be for Paper's own drop of a broken container's items.
- Timers are in-memory, not persisted countdowns. Persistent marker recovery remains
  unchanged: after a crash, orphan markers restore blocks when their entities load.
- Several changes on one block are kept separately, oldest first, with one marker
  holding the block's original state. Undoing the **newest** change writes back the
  state it replaced. Undoing an **older** change leaves the world alone and splices it
  out: the change above it takes over its restore state. So an older deadline no longer
  overwrites a newer edit, and the last undo always lands on the original.
- After a crash, the marker restores the original state; changes and causes are not persisted.
  Markers carry an `altered_block_format` key; ones written by older builds (without it) are
  recovered by the exact code those builds used (`applyLegacyTileData`: items written to the
  state's live inventory, then the state updated), whatever its net effect, and nothing is
  dropped, since their payload may be items that never left the block.
- Undoing a block whose chunk unloaded and loaded again removes its marker by UUID. If the
  marker's entities are not loaded at that moment, it is looked for every tick for 100 ticks and
  removed once it loads, and if it loads later in this session it is removed without restoring.
  When the retry gives up, the UUID is forgotten only if the marker's chunk has its entities
  loaded without it (the marker was deleted, so it can never load); otherwise it is kept. At most
  4096 are kept: past that the oldest is forgotten with a warning, and that marker restores its
  block once more if it ever loads.
- When an undo or crash recovery puts a marker's captured items back, the block entity gets the
  marker's UUID in its PDC (`altered_block_restored`), written in the same update as the items.
  A marker that then loads again (a restart after the undo, or a recovery run twice) is removed
  without writing anything, and an undo whose restore threw stays registered and can be called
  again without writing the items twice.
- While `apply` changes a block (until the change is registered) or a block is being restored (an
  undo, or recovery from its marker), code called back from inside (an `EntitySpawnEvent`,
  `BlockPhysicsEvent` or displaced item's `ItemSpawnEvent` listener, for example) can't change
  it: an undo of one of its changes is ignored with a warning, and `apply`, `register` or `add`
  on it throws `IllegalStateException`. An apply or restore that threw leaves the block free again.
- Remaining window: an undo finds its marker not loaded, and the server stops or crashes before
  the marker's entities load and the 100-tick retry or a load event removes it. On Paper a
  chunk's entities load with the chunk, which the undo's own block write loads, so this needs
  the entities to stay unloaded that long (seen most plausibly in `restoreAll` on shutdown,
  where no retry can run). The note that the marker was removed is only in memory, so after the
  restart the marker restores once more when it loads:
  - it writes the original BlockData and tile data again, which is what the undo wrote, so it
    only overwrites an edit made to that block in between while its entities were not loaded
    (WorldEdit, another plugin);
  - for a block whose undo put captured items back, the `altered_block_restored` stamp makes it
    remove itself without writing anything; but if such an edit replaced the restored
    container in between, the stamp went with the old block entity and the marker puts the
    captured items in once more.

### Causes

Every `apply` / `register` overload (timed and untimed) also takes a trailing cause,
either one `String` or a `Set<String>`, recorded on `Change.causes`. A cause says what the
change is, separate from the feature-owned internal name. Causes come from the fixed set in
`AlteredBlockCauses`; any other string throws `IllegalArgumentException` before the world is touched:

| Cause | Meaning | Also matches lookups for |
|-------|---------|--------------------------|
| `damage` | blocks broken, removed or spoiled (smashed windows, holes, cobwebs, graffiti) | — |
| `explosion` | damage from an explosion | `damage` |
| `fire` | damage from fire, including placed fire | `damage` |
| `decoration` | temporary decoration that is not damage | — |

Give a change its most specific cause only: `explosion`, not `explosion` and `damage`.

```java
manager.apply("my-plugin:bomb", block, Material.AIR, false, AlteredBlockCauses.EXPLOSION);
manager.apply("my-plugin:lights", block, Material.GLOWSTONE, false, 200L, AlteredBlockCauses.DECORATION);

manager.getByCause("damage");                            // damage, explosion and fire changes
manager.getBlocksInBoxByCause(world, x1, y1, z1, x2, y2, z2, Set.of("explosion", "fire"));
manager.undoAllByCause("damage", true);
```

For one block, `undoByCause(block, causes, physics)` undoes its matching changes and
`previewUndoByCause(block, causes)` returns the BlockData it would show afterwards (null when
nothing matches), for repair tools that animate the result before writing it.

Each lookup and undo accepts one cause or a collection, and matches changes carrying any
of them (following the implications above). Undoing by cause leaves other changes on the
same block in place (see splicing above).

### Finding tracked blocks near a place

`getBlocksInBox(world, minX, minY, minZ, maxX, maxY, maxZ, internalNames)` returns the
tracked blocks inside an inclusive block-coordinate box that have a change under one of
the names, each once, in no particular order. It only looks at the chunks the box
overlaps, so its cost does not grow with every tracked block on the server. For a
sphere or cylinder, query a box that encloses it and apply the exact shape check to
the result.

`getByInternalName(name)` and `undoAll(name, ...)` only visit the blocks that have a
change under that name, so a feature with nothing tracked costs almost nothing to
clean up (MagicSpells calls `undoAll` for every replace/destroy spell on reload).
Within each block, `getByInternalName` lists changes oldest first and `undoAll` undoes
them newest first; blocks come in no particular order.

Tests cover scheduler deadlines, early/bulk cancellation, stale callbacks, equal
records, physics flag forwarding, splicing, cause lookup/undo, and untimed compatibility with MockBukkit.
Live Paper checks still needed: actual neighbor physics, tile-state restoration,
and crash/restart marker recovery; unit tests are not in-game verification.

## Placeholder expansion

`com.sneakyutils.util.PlaceholderUtil` wraps PlaceholderAPI for consumers that already
depend on SneakyUtils and only need `setPlaceholders`:

```java
boolean present = PlaceholderUtil.isAvailable();
String out = PlaceholderUtil.setPlaceholders(player, "Hello %player_name%");
```

Call on the **server thread**. When PlaceholderAPI is missing/disabled, `text` is
null/empty, or it contains no `%`, the helper returns `text` unchanged. Linkage and
runtime failures also return `text` unchanged. Prefer `PlayerUtil.getCharacterName`,
`canTarget`, and `onDuty` when those specialized contracts already cover the need.
Registering a `PlaceholderExpansion` still requires a direct PAPI compile dependency
in the registering plugin.

## Player helpers

`com.sneakyutils.util.PlayerUtil` exposes Java-static and Kotlin-callable helpers:

```java
String name = PlayerUtil.getCharacterName(player);
boolean target = PlayerUtil.canTarget(player);       // creative excluded
boolean creativeTarget = PlayerUtil.canTarget(player, true);
boolean onDuty = PlayerUtil.onDuty(player);          // dispatch duty (paladin character required by Dispatch)
@Nullable String characterId = PlayerUtil.characterId(player); // active SCM character UUID, or null
PlayerUtil.clearCachedChecks(player);                // drop this player's cached checks
Player exact = PlayerUtil.getPlayerExact("MinecraftName");
Player partial = PlayerUtil.getPlayer("Minecraft");
```

Call on the **server thread**. Character names are read through PlaceholderAPI's
`%sneakycharacters_character_name_noformat%`, not the display name (no added title,
prefix or gender suffix). The result is preserved exactly: no local stripping,
trimming, formatting repairs or recursive placeholder expansion. Insert it as
literal text rather than parsing it as MiniMessage. If SCM or PAPI is absent or
disabled, the expansion is missing, or the result is blank/unresolved, the helper
returns `player.getName()`. Plugin availability (SCM, PAPI) is checked on every call; the
name itself is cached (see [Cached checks](#cached-checks)).

`characterId(player)` returns the UUID string of the player's active SCM character from
`%sneakycharacters_character_uuid%`, unchanged. It returns null when SCM or PAPI is absent or
disabled, the expansion is missing, the player has no character, or the result is
blank/unresolved. Call on the **server thread**.

PAPI 2.11.5 is compile-only (matching SCM's declared API version), not bundled.
The Paper descriptor declares an **optional joined classpath** dependency on
PlaceholderAPI. SCM is not a dependency: it already depends on SneakyUtils.
Calls before SCM is enabled deliberately fall back.

`canTarget(Player)` defaults `allowCreative` to false; both overloads are Java
static. Survival and Adventure are eligible, Creative only with explicit opt-in,
and Spectator never. CMI vanish detection follows SCM's nametag implementation:
`%cmi_user_vanished_symbol%` excludes the player only when **resolved and nonempty**.
Whitespace and `false` count as nonempty symbols; this is not a boolean placeholder.
Absent/disabled PAPI, missing expansion and unresolved/null expansion responses
are treated as not known to be vanished (fail-open, matching SCM nametags).
The helper adds no dead/offline, permissions or viewer-visibility checks.

Source contract verified in [SCM at `b80befa89e397d60ee0a3c05cf1af5bdd91301bc`](https://github.com/Team-Sneakymouse/SneakyCharacterManager-Paper/tree/b80befa89e397d60ee0a3c05cf1af5bdd91301bc):
`SneakyCharacterManager.IDENTIFIER` is `sneakycharacters`; `handlers/Placeholders.java`
registers `character_name_noformat` via `getNameUnformatted()`;
`handlers/nametags/NametagManager.java` checks the resolved nonempty CMI symbol.
`commands/CommandSkin.java` lacks the nametag unresolved-token guard; this helper
uses the nametag contract, so unavailable CMI does not hide every player.

`onDuty(Player)` returns true only when `%sneakydispatch_on_duty%` resolves to
`true` (case-insensitive, without trimming). SneakyDispatch itself returns `false`
while the player is on a non-paladin character (SCM `character_hastag_paladin`), so
this helper no longer checks the tag. It reads the value through PAPI (cached, see
[Cached checks](#cached-checks)); missing/disabled PAPI, a missing expansion, null/unresolved responses and any
other output return false. There is no hard dependency or reverse classpath link to
Dispatch. Call on the **server thread**. Tests cover real PAPI parsing/dispatch with
test expansions, not the running provider; live Paper verification still needs duty
toggles, character switches and optional PAPI absence/classloading checks.

Source contract verified in SneakyDispatch's `Placeholders.kt` (`on_duty`, with
identifier `sneakydispatch`; requires SneakyDispatch with the paladin-character
check, otherwise non-paladin characters in a unit read as on duty).

### Cached checks

`canTarget`'s CMI vanish check, `onDuty`, `characterId` and `getCharacterName` are hot-path
calls, so while SneakyUtils is enabled each player's PAPI result is kept for a few server
ticks, set in `plugins/SneakyUtils/config.yml` (read on enable; restart to change):

| Key | Default | Caches |
|-----|---------|--------|
| `player-util.vanish-cache-ticks` | 20 | `canTarget`'s vanish result (the game mode is read on every call, never cached) |
| `player-util.duty-cache-ticks` | 20 | `onDuty` |
| `player-util.character-cache-ticks` | 20 | `characterId` and `getCharacterName` |

`0` turns a cache off (every call reads the placeholder, as before); negative values count as
`0`. A result is read at most once per player per window and is the same as an uncached read
when nothing changed. Entries are dropped:

- when the player quits;
- when PlaceholderAPI enables or disables (all checks), or CMI, SCM or SneakyDispatch does
  (the checks that read from it; SCM also drops duty, which SneakyDispatch derives from the worn
  character). A disable is handled like a change event below (Paper fires it before the plugin's
  own shutdown), heard at LOWEST;
- on CMI's `com.Zrips.CMI.events.CMIPlayerVanishEvent` / `CMIPlayerUnVanishEvent` (vanish, every
  player: the events aren't Bukkit `PlayerEvent`s);
- on `com.sneakyutils.signals.DutyChangedEvent` (duty);
- on SCM's `LoadCharacterEvent` and, when SCM has it, `CharacterLoadedEvent` (id, name and duty,
  for **every** player), and `CharacterNickChangeEvent` (name, that player), all in
  `net.sneakycharactermanager.paper.handlers.character`. A swap changes two bodies but fires one
  `CharacterLoadedEvent` per body, one after the other, and no `LoadCharacterEvent`, so both load
  events are heard at LOWEST and cover every player: from the first event on, listeners see both
  bodies' new values. Listeners at LOWEST registered before SneakyUtils hooked SCM run first and
  can still see a cached value. Loads are rare, so the extra reads cost little;
- by `PlayerUtil.clearCachedChecks(player)`, for plugins that change one of these without such
  an event (any thread).

Most of these events fire before the change, so at the event (MONITOR, cancelled or not) the
entry is dropped and, for the rest of that tick, the affected checks bypass the cache entirely
(no lookup, no store): a read during the event and a read after the change in the same tick both
see the provider's current value. Entries are dropped again on the next tick. An event fired off
the server thread only drops the entries on the next tick. A change with no event shows up within the configured ticks.

Known limits (SCM sends no event SneakyUtils could use for these; staleness is bounded by
`player-util.character-cache-ticks` for the id and name and `player-util.duty-cache-ticks` for duty,
20 ticks by default):

- **Puppet swaps:** SCM exchanges the two bodies' characters, then applies their player state
  (potion effects, air) before it fires the first `CharacterLoadedEvent`. Listeners called during
  that state change (potion-effect or air events, in that tick) can still read either body's old
  `characterId`, `getCharacterName` or `onDuty`.
- **`chardisable` of a player's own character:** removing the worn character fires no SCM event
  (only a borrowed character's removal does). `characterId`, `getCharacterName` and `onDuty` can
  return the removed character's values until the entry expires.

A caller that needs exact values in these cases can set the relevant key to `0` in
`plugins/SneakyUtils/config.yml` and restart, which turns that cache off for every caller
(each call reads the placeholder again, as before caching), or call
`PlayerUtil.clearCachedChecks(player)` right after making such a change itself.
CMI and SCM events are registered by class name through that plugin's class loader when it
enables (before or after SneakyUtils), and removed when it disables; there is no compile
dependency on either. Off the server thread, or while SneakyUtils isn't enabled, nothing is
cached and every call reads the placeholder.

Tests cover cache hits and expiry, `0`, each invalidating event (with test stand-ins shaped like
CMI 9.8.9.8's and SCM's classes) including reads during the event in the same tick, swaps (both
bodies fresh at LOWEST in the first event), a read during a provider's disable and after its
teardown in the same tick, change marks across tick-counter wrap, plugin enable/disable, quit, the game mode staying live and
off-thread calls. Live checks still needed: the real CMI and SCM events on a Paper server.

### Migration and verification

`PlayerNameUtils` has been removed as requested, not retained as a compatibility
class. Change imports and all three existing method calls to `PlayerUtil` and
recompile consumers; this is a **source and binary breaking change**. Account-name
exact/partial lookup semantics remain Bukkit's and do not resolve character names.
Local source searches of SneakyKnives, SneakyHaunted, SneakyLeveling, SCM and
MagicSpells-pdc found no shared `PlayerNameUtils` callers; MagicSpells has its own
same-named utility, which is not this API. Other checkouts/deployed JARs and shared
team policy may still reference the removed class and require separate migration.
No other repositories were changed for migration.

Tests exercise real PAPI expansion registration, lookup and parsing with test
expansions; only the PAPI plugin lifecycle singleton/config is mocked. They do
not execute the real SCM/CMI plugins. Live Paper checks still needed: optional
PAPI absence and joined plugin classloading, SCM selection/rename and disable,
CMI vanish/unvanish in each game mode, and actual provider output. Provider
formatting quirks are intentionally preserved, not repaired in SneakyUtils.

## Server identity

`com.sneakyutils.util.ServerUtil` (in [`sneakyutils-common`](#platform-free-helpers-sneakyutils-common),
usable on Paper and Velocity) exposes static getters that read the `P_SERVER_NAME` environment
variable on each call:

```java
@Nullable String name = ServerUtil.getServerName(); // "Dev Main"
@Nullable String id = ServerUtil.getServerId();     // "dev-main"
```

- `getServerName()` returns the supplied value **exactly** (no trimming or case
  changes), for display. It returns `null` when the variable is missing, empty or
  whitespace-only.
- `getServerId()` derives a lowercase kebab-case id from that name: lowercased with
  `Locale.ROOT` (independent of the JVM default locale), every run of characters
  other than Unicode letters/digits (whitespace, punctuation, `_`, symbols) becomes
  one `-`, and leading/trailing `-` are removed. Examples: `Dev Main` → `dev-main`,
  `Dev_Main` → `dev-main`, `LoM (EU) 1` → `lom-eu-1`. Non-ASCII letters/digits are
  kept, not transliterated.
- `getServerId()` returns `null` whenever `getServerName()` does, and also when the
  name has no letters or digits (e.g. `---`).

Both values are nullable (JSpecify `@Nullable`): handle `null` explicitly rather than
substituting a default identity. There is no config file or command; the environment is set by
the server host. On a proxy, set `P_SERVER_NAME` for the proxy process itself; it does not see
the backends' values. Tests cover the derivation and read a real test-JVM
environment; live servers' `P_SERVER_NAME` values are not verified here.

## Versioning

Builds **require a git checkout** with at least one commit. The version is:

```text
yyyy.MM.dd-<12-char-git-hash>
```

Plugin JAR name: `SneakyUtils-<version>.jar`.

## Publish flow

On every push to `main`, `.github/workflows/publish.yml` builds with Java 25, fetches Maven credentials from Infisical via OIDC, and publishes to `https://maven.sneakyrp.com/sneakymouse`.

Public Team-Sneakymouse repositories inherit organization Infisical secrets. Private repositories must configure `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN`, and allowlist this repo in Infisical.

## Local build

```bash
./gradlew check
```

## Agent / LLM instructions

Point your tool’s project instructions at **[AGENTS.md](./AGENTS.md)**. Team-wide use/contribute rules live in [team-development-policy/policy/sneakyutils.md](../team-development-policy/policy/sneakyutils.md).
