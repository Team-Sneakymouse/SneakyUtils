# SneakyUtils

Shared **Paper** utilities and managers for Team-Sneakymouse / Lords of Minecraft plugins.

This is a live Paper plugin (not a compile-only library). Install the JAR on the server so managers keep working memory; depend on the published artifact at compile time for the API.

## What’s included (v1)

- `TextUtility` — Unicode escape decoding (`\uXXXX` / `\UXXXXXXXX`), legacy `&`/`§` → MiniMessage → Adventure `Component`, plus balanced line wrapping
- `AlteredBlockManager` — reversible temporary block changes with persistent Marker fallback (scoreboard tag `sneakyutils_altered_block`, PDC keys under `sneakyutils:`)
- MagicsSpells-ported static helpers under `com.sneakyutils.util`: location/XP/color/params, `BlockUtils`, `EntityPdc` (injectable namespace), attribute/particle/sound/potion name resolution, geometry (`BoundingBox` / `bounds`), `AtomicFiles`, and small Bukkit/stdlib helpers
- `com.sneakyutils.util.items.ItemBehaviors` — soulbinding and expiration, with utility-owned enforcement, scheduling and `ItemExpireEvent`
- `PlayerUtil` — SCM noformat character names via optional PAPI, CMI-aware target eligibility, and Bukkit account-name lookups
- Cross-plugin ports: `InventoryUtility` (Paper YAML + legacy Base64), `PermissionUtility`, `PoseFacing` / `Direction`, `ItemMetaText`

## Consumer usage

Maven coordinates:

```text
io.github.team-sneakymouse:sneakyutils:<version>
```

```kotlin
repositories {
    maven("https://maven.sneakyrp.com/releases")
}

dependencies {
    compileOnly("io.github.team-sneakymouse:sneakyutils:VERSION")
}
```

Ensure `SneakyUtils` is installed and loads before plugins that call `SneakyUtils.getAlteredBlockManager()`.

## Item behaviors

Call the Java-static API on the **server thread**, with SneakyUtils installed and
listed as a required server dependency. No MagicSpells dependency is needed:

```java
ItemBehaviors.applySoulbound(item, player); // or UUID
ItemBehaviors.applyExpiration(item, 60_000.0); // duration in milliseconds
ItemExpirationScheduler.scheduleFromItem(player, item); // after giving the item
```

The default application methods preserve an existing correctly typed value.
`applySoulboundOverlay` and `applyExpirationOverlay` deliberately replace it.
Persistence is **unchanged**, with no migration, namespace rewrite or timer reset:
`magicspells:soulbound_owner` is a STRING UUID and `magicspells:expires_at` a LONG
Unix epoch millisecond deadline. The historical namespace is a storage contract,
not a runtime dependency. MagicSpells remains responsible for its item definitions
and conjure overlays; it delegates the behaviors here.

SneakyUtils registers soulbound/expiration listeners and owns all expiry tasks.
Its initial online-player scan runs next tick, after dependent plugins register
listeners; disabled providers and players who have left are ignored.
Soulbound restrictions retain the legacy behavior: foreign-owner inventory entries
are removed on join/open; pickup, use, clicks, drag, swaps and hotbar selection are
blocked. Creative bypass requires `sneakyutils.nosoulbound` **and** Creative mode;
the old `magicspells.nosoulbound` node no longer grants this bypass. Permissions
should be migrated separately from item data.

Inventory expiration emits synchronous, non-cancellable
`com.sneakyutils.events.ItemExpireEvent` with the player and original stack before
removal. Ground-item cleanup emits no inventory-expiration event. MagicSpells'
`expireitem` passive consumes this event directly; there is no legacy event bridge.
Future deadlines are deduplicated by (player UUID, deadline); inventory and ender
chest entries are scanned at the deadline, and pending state is cleared on disable.
Ground items retain legacy event-driven expiry checks, not independent timers.

Optional SCM integration resolves its public
`net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent` and
`getPlayer()` using SCM's own classloader. There is no reverse dependency or joined
SCM classpath. Already enabled and later enabled SCM instances are supported;
uncancelled character loads scan inventory/armor/ender chest the next tick, after
SCM restoration. Incompatible SCM event APIs produce a warning and leave ordinary
join/inventory expiration working. SCM disable unregisters its hook.

Verification: MockBukkit tests run without MagicSpells, using literal legacy keys,
real listener dispatch, scheduler ticks, utility events and a test-only SCM public
event fixture. Live Paper checks remain **pending**: actual SCM plugin classloader
and character restoration, real inventory interactions (both hands/armor/chests),
permission configuration and the MagicSpells passive trigger. No server was
provisioned or deployed. Roll out matching provider and consumer builds together;
rolling back only the consumer while leaving the new provider active would restore
duplicate listeners. Item data itself needs no rollback conversion.

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
  For `register`/`add`, it controls restoration only: these APIs never perform the edit.
  Capture original data/state **before** an external edit, then register afterwards.
- `Change.undo(...)`, `undoAll(...)`, and plugin-disable `restoreAll()` cancel timers.
  Explicit undo uses its own physics argument. Use the exact registered/returned
  `Change` instance, not a structurally equal copy; register each instance only once
  while active. Record components and existing method signatures are preserved.
- Timers are in-memory, not persisted countdowns. Persistent marker recovery remains
  unchanged: after a crash, orphan markers restore blocks when their entities load.
- Overlapping edits retain existing snapshot semantics, **not** a compositing stack.
  Undo restores that change's captured state (or the original when it is last).
  An older change expiring first can overwrite a newer edit still tracked on that
  block. Coordinate overlapping lifetimes/LIFO undo in the consumer when necessary.

Tests cover scheduler deadlines, early/bulk cancellation, stale callbacks, equal
records, physics flag forwarding, and untimed compatibility with MockBukkit.
Live Paper checks still needed: actual neighbor physics, tile-state restoration,
and crash/restart marker recovery; unit tests are not in-game verification.

## Player helpers

`com.sneakyutils.util.PlayerUtil` exposes Java-static and Kotlin-callable helpers:

```java
String name = PlayerUtil.getCharacterName(player);
boolean target = PlayerUtil.canTarget(player);       // creative excluded
boolean creativeTarget = PlayerUtil.canTarget(player, true);
Player exact = PlayerUtil.getPlayerExact("MinecraftName");
Player partial = PlayerUtil.getPlayer("Minecraft");
```

Call on the **server thread**. Character names are read through PlaceholderAPI's
`%sneakycharacters_character_name_noformat%`, not the display name (no added title,
prefix or gender suffix). The result is preserved exactly: no local stripping,
trimming, formatting repairs or recursive placeholder expansion. Insert it as
literal text rather than parsing it as MiniMessage. If SCM or PAPI is absent or
disabled, the expansion is missing, or the result is blank/unresolved, the helper
returns `player.getName()`. Calls observe current names and plugin state.

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

## Versioning

Builds **require a git checkout** with at least one commit. The version is:

```text
yyyy.MM.dd-<12-char-git-hash>
```

Plugin JAR name: `SneakyUtils-<version>.jar`.

## Publish flow

On every push to `main`, `.github/workflows/publish.yml` builds with Java 25, fetches Maven credentials from Infisical via OIDC, and publishes to `https://maven.sneakyrp.com/releases`.

Public Team-Sneakymouse repositories inherit organization Infisical secrets. Private repositories must configure `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN`, and allowlist this repo in Infisical.

## Local build

```bash
./gradlew check
```

## Agent / LLM instructions

Point your tool’s project instructions at **[AGENTS.md](./AGENTS.md)**. Team-wide use/contribute rules live in [team-development-policy/policy/sneakyutils.md](../team-development-policy/policy/sneakyutils.md).
