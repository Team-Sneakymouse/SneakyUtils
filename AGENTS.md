# Agent instructions (Team Sneakymouse / Lords of Minecraft)

This repo is **SneakyUtils**, a shared Paper plugin (Kotlin + Java, Paper 26.2, Gradle) that other Team-Sneakymouse plugins depend on for utilities and in-memory managers. It has no configuration or commands — bootstrap only enables managers/listeners.

Team policy for when consumers must **use** or **contribute** here: `../team-development-policy/policy/sneakyutils.md` (in the LoM workspace) or the same path under an installed `.team-policy/` payload. For substantial Paper features, persistent world state, or changes spanning components, also follow `../team-development-policy/policy/paper-plugin-maintainability.md` (or the corresponding installed policy path).

## Commands

| Task | Command |
|------|---------|
| Build + unit tests | `./gradlew check` (includes `publishPreflight`) |
| Publish preflight only | `./gradlew publishPreflight` or `bash scripts/publish-preflight.sh` |
| Unit tests only | `./gradlew test` |
| Local Paper server | `./gradlew runServer` |
| Publish (CI) | `./gradlew publishMavenPublicationToSneakyrpRepository` (requires Maven credentials) |

Builds require a **git checkout** with at least one commit (version is derived from `HEAD`).

## Architecture (keep plugins testable)

```text
src/main/kotlin/com/sneakyutils/
  SneakyUtils.kt       # Bootstrap only: construct managers, register listeners
  util/TextUtility.kt  # Pure / Bukkit-native static helpers
src/main/java/com/sneakyutils/
  util/                # Shared Java helpers + AlteredBlockManager/listener; prefer static Bukkit-native APIs
src/test/kotlin/com/sneakyutils/   # Mirror packages; *Test.kt next to the unit under test
```

Guidelines:

- Prefer constructor parameters (`JavaPlugin`, services) so tests stay simple.
- Classes loaded by MockBukkit (plugin) must be **`open`** in Kotlin.
- Consumers load this plugin at runtime and use `compileOnly("io.github.team-sneakymouse:sneakyutils:…")`.
- Marker PDC keys use the plugin NamespacedKey namespace (`sneakyutils:`); scoreboard tag is `sneakyutils_altered_block`.

## Testing policy (required)

1. If you change behavior under `src/main/`, **add or update** tests under `src/test/` in the same change.
2. Run **`./gradlew test`** (or `./gradlew check`) and fix failures before finishing the task.
3. Do not rely on `./gradlew runServer` as the only verification for logic changes.

MockBukkit lifecycle in every MockBukkit test class:

- `@BeforeEach`: `MockBukkit.mock()`
- `@AfterEach`: `MockBukkit.unmock()`

Failing tests are not acceptable. A skipped test does not demonstrate the behavior it skips; use a pure test or suitable integration evidence when MockBukkit lacks a needed API, and report any remaining gap.

## Definition of done

- [ ] Behavior covered or updated in `src/test/`
- [ ] `./gradlew check` passes locally
- [ ] No secrets committed (Maven credentials via env / Infisical only)
- [ ] `paper-plugin.yml` and POM metadata updated if plugin identity changed

## Human docs

- `README.md` — usage, dependency coordinates, publishing

## Repo-specific rules

- **Additive API only.** Every public function in `com.sneakyutils.*` is a published API that all Sneaky plugins compile against. Add overloads or new functions; don't rename, remove or change semantics. Grep `repos/*/src` for usages before touching an existing one.
- **No domain code.** Only portable Bukkit/Paper/Adventure/Java helpers belong here: no MagicSpells spell types, no consumer singletons, no game-mode models. A helper needs a second real consumer (see team policy `sneakyutils.md`).
- **Altered-block identity is frozen.** Don't rename the scoreboard tag `sneakyutils_altered_block` or the PDC keys `altered_block_data/state/world/x/y/z`; add new keys and migrate on load.
- **Update the docs list.** When you add a util file, add a line to README "What's included" and the architecture block above so agents find it before writing a duplicate.
