# Agent instructions (Team Sneakymouse / Lords of Minecraft)

This repo is **SneakyUtils**, a shared Paper plugin (Kotlin + Java, Paper 26.2, Gradle) that other Team-Sneakymouse plugins depend on for utilities and in-memory managers.

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
src/main/kotlin/<package>/
  SneakyUtils.kt       # Bootstrap only: config, register commands/listeners, managers
  util/                # Pure / Bukkit-native static helpers (see TextUtility)
  commands/            # Thin adapters
src/main/java/<package>/
  manager/             # Stateful managers (see AlteredBlockManager)
src/test/kotlin/<package>/   # Mirror packages; *Test.kt next to the unit under test
```

Guidelines:

- Prefer constructor parameters (`JavaPlugin`, services) so tests stay simple.
- Classes loaded by MockBukkit (plugin, commands) must be **`open`** in Kotlin.
- Consumers load this plugin at runtime and use `compileOnly("io.github.team-sneakymouse:sneakyutils:…")`.

## Testing policy (required)

1. If you change behavior under `src/main/`, **add or update** tests under `src/test/` in the same change.
2. Run **`./gradlew test`** (or `./gradlew check`) and fix failures before finishing the task.
3. Do not rely on `./gradlew runServer` as the only verification for logic changes.

MockBukkit lifecycle in every MockBukkit test class:

- `@BeforeEach`: `MockBukkit.mock()`
- `@AfterEach`: `MockBukkit.unmock()`

Skipped tests from unimplemented MockBukkit APIs are acceptable; failing tests are not.

## Definition of done

- [ ] Behavior covered or updated in `src/test/`
- [ ] `./gradlew check` passes locally
- [ ] No secrets committed (Maven credentials via env / Infisical only)
- [ ] `paper-plugin.yml` and POM metadata updated if plugin identity changed

## Human docs

- `README.md` — usage, dependency coordinates, publishing
