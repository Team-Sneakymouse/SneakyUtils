# SneakyUtils

Shared **Paper** utilities and managers for Team-Sneakymouse / Lords of Minecraft plugins.

This is a live Paper plugin (not a compile-only library). Install the JAR on the server so managers keep working memory; depend on the published artifact at compile time for the API.

## What’s included (v1)

- `TextUtility` — Unicode escape decoding (`\uXXXX` / `\UXXXXXXXX`), legacy `&`/`§` → MiniMessage → Adventure `Component`, plus balanced line wrapping
- `AlteredBlockManager` — reversible temporary block changes with persistent Marker fallback (scoreboard tag `sneakyutils_altered_block`, PDC keys under `sneakyutils:`)
- MagicsSpells-ported static helpers under `com.sneakyutils.util`: location/XP/color/params, `BlockUtils`, `EntityPdc` (injectable namespace), attribute/particle/sound/potion name resolution, geometry (`BoundingBox` / `bounds`), `AtomicFiles`, and small Bukkit/stdlib helpers
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

## Plain character names

```java
String name = PlayerNameUtils.getCharacterName(player);
```

Call `com.sneakyutils.util.PlayerNameUtils.getCharacterName(Player)` on the **server thread**.
It reads SneakyCharacterManager's raw `Character.get(player).getName()` and
locally strips angle-bracket tags (`<[^>]*>`, matching SCM), standard legacy `&`/`§` formatting codes (case insensitive),
compact hex `&#RRGGBB` / `§#RRGGBB`, and expanded hex `&x&R&R&G&G&B&B` /
`§x§R§R§G§G§B§B`. If the manager is absent/disabled, its API cannot be invoked,
or the character/name is missing or blank **after sanitization**, it returns
`player.getName()`. Each call reads current state; nonformat literal text and
spaces are preserved, including literal `&x` unless it starts a complete expanded
hex code. Angle-bracket tags are removed even when not recognized MiniMessage tags;
names are not otherwise parsed as MiniMessage or placeholders.

SneakyCharacterManager and PlaceholderAPI are **not required** by SneakyUtils.
The optional bridge resolves the API through the enabled manager's classloader,
not SneakyUtils' classpath. Do not add a SneakyUtils → SCM load-before/dependency
edge: SCM already requires SneakyUtils. Consumers still declare their normal
runtime dependency on SneakyUtils. Calls before SCM is enabled deliberately fall
back; callers needing a character at startup must wait for SCM to be ready.

API source verified in [SneakyCharacterManager-Paper at `fbb7aa3f3e8d65c2ef6c95b0253f835daea383ec`](https://github.com/Team-Sneakymouse/SneakyCharacterManager-Paper/tree/fbb7aa3f3e8d65c2ef6c95b0253f835daea383ec):
`paper/.../handlers/character/Character.java` supplies public static `get(Player)`
and instance `getName()` (the raw stored name) and `getNameUnformatted()`;
`handlers/Placeholders.java` uses `getNameUnformatted()`
for `character_name_noformat`. This uses the character name, not the display name
(no prefix, title, or gender suffix). SCM's current `util/ChatUtility.stripFormatting`
removes angle-bracket tags and legacy `&` codes, but not `§` codes or all hex
formats. Calling it first destroys expanded ampersand hex (`&x&F&F&A&A&0&0Momo`
becomes `&xMomo`), so this helper sanitizes the raw name locally, matching SCM
angle-tag removal while handling the intact legacy/hex formats listed above.

Tests exercise the bridge with a contract fixture, not the real SCM plugin.
Remaining server checks: Paper plugin-classloader isolation with both real JARs,
SCM absent/disabled, character selection/rename, and representative formatting.
The active-character map is a plain `HashMap`, hence the server-thread contract.

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
