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

## Plain character names

```java
String name = PlayerNameUtils.getCharacterName(player);
```

Call `com.sneakyutils.util.PlayerNameUtils.getCharacterName(Player)` on the **server thread**.
It returns SneakyCharacterManager's `Character.get(player).getNameUnformatted()`;
if the manager is absent/disabled, its API cannot be invoked, or the character/name
is missing or blank, it returns `player.getName()`. Each call reads current state;
nonblank names are returned unchanged (including surrounding spaces).

SneakyCharacterManager and PlaceholderAPI are **not required** by SneakyUtils.
The optional bridge resolves the API through the enabled manager's classloader,
not SneakyUtils' classpath. Do not add a SneakyUtils → SCM load-before/dependency
edge: SCM already requires SneakyUtils. Consumers still declare their normal
runtime dependency on SneakyUtils. Calls before SCM is enabled deliberately fall
back; callers needing a character at startup must wait for SCM to be ready.

API source verified in [SneakyCharacterManager-Paper at `fbb7aa3f3e8d65c2ef6c95b0253f835daea383ec`](https://github.com/Team-Sneakymouse/SneakyCharacterManager-Paper/tree/fbb7aa3f3e8d65c2ef6c95b0253f835daea383ec):
`paper/.../handlers/character/Character.java` supplies public static `get(Player)`
and instance `getNameUnformatted()`; `handlers/Placeholders.java` uses the latter
for `character_name_noformat`. This matches that API, not the display name (no
prefix, title, or gender suffix). Its current `util/ChatUtility.stripFormatting`
removes angle-bracket tags and legacy `&` codes, but not `§` codes or all hex
formats; this helper does not silently change SCM's formatting semantics.

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
