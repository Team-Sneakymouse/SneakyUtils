# SneakyUtils

Shared **Paper** utilities and managers for Team-Sneakymouse / Lords of Minecraft plugins.

This is a live Paper plugin (not a compile-only library). Install the JAR on the server so managers keep working memory; depend on the published artifact at compile time for the API.

## What’s included (v1)

- `TextUtility` — legacy `&`/`§` → MiniMessage → Adventure `Component`, plus balanced line wrapping
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

Point your tool’s project instructions at **[AGENTS.md](./AGENTS.md)**.
