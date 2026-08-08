# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Kotlin Multiplatform + Compose Multiplatform app, **Desktop (JVM) only**. It is a port of the
"generate scoreboard theme from an image" feature originally from the Android app at
`D:\AndroidProjects\TennisScoreKeeper` (which uploaded images to a server via Ktor). Here the
recognition runs on-device via a two-stage pipeline: a Koog vision LLM agent (OpenAI GPT-4o) localizes six scoreboard components, then OpenCV measures the exact colors. An on-device OpenCV heuristic serves as the offline fallback. See `shared/.../model/theme/analysis/`.

Two Gradle modules:
- `:shared` — KMP module with a single `jvm()` target. All app code (UI, MVI, repository, models) lives here. Because the target is `jvm()`, the platform source sets are **`jvmMain` / `jvmTest`**, *not* `desktopMain`.
- `:desktopApp` — plain JVM app. Just the `main()` entry point (`MainKt`), window setup, and Koin bootstrap. Depends on `:shared`.

Build configuration is centralized in `gradle/libs.versions.toml` (version catalog). Entry point: `com.bashkevich.scoreboardthemerecognizer.MainKt`.

## Commands

```bash
./gradlew :desktopApp:run              # Run the desktop app
./gradlew :desktopApp:hotRun --auto    # Run with Compose hot reload
./gradlew :shared:jvmTest              # Run shared-module JVM tests
./gradlew :shared:compileKotlinJvm     # Compile-check shared (useful fast verify)
./gradlew :desktopApp:compileKotlin    # Compile-check desktop app
```

Native distribution formats (DMG/Msi/Deb) are configured in `desktopApp/build.gradle.kts`. Use `gradlew.bat` instead of `./gradlew` if running from cmd/PowerShell; `./gradlew` works in the project's Git Bash shell.

## Architecture

**MVI via `mvi/BaseViewModel`** — every screen's ViewModel extends `BaseViewModel<State, Event, Action>`, where the three type params implement the `UiState` / `UiEvent` / `UiAction` marker interfaces. Pattern:
- `state: StateFlow<T>` is built by `combine`-ing several private `MutableStateFlow`s (selected image, generated content, loading flags, action) and `stateIn`-ing on `viewModelScope`.
- User intents go in through `onEvent(UiEvent)`; the VM dispatches to private handlers that mutate those backing flows.
- One-shot UI side effects (snackbars, navigation) are emitted through `_action: MutableStateFlow<Action?>` via `sendAction()`, then consumed from the screen by `mvi/LaunchedUiEffectHandler` (which calls `viewModel.consumeAction()` after handling, and dismisses on lifecycle `ON_PAUSE`).

**Navigation is stubbed.** There is no `NavController` (`androidx.navigation` was dropped in this port). `LocalOnBack` (a `staticCompositionLocalOf` defined in `App.kt`) defaults to a no-op; the single `GenerateThemeScreen` is rendered directly under a `MaterialTheme` in `App.kt`. `App()` is called from `desktopApp`'s `main.kt` after `di.initKoin()`.

**Dependency injection: Koin**, started as a **global** context via `di/initKoin()` before the Compose UI starts. `themeModule` wires `ThemeRepository` (impl) and `GenerateThemeViewModel` (`viewModelOf`). Screens obtain the VM with `koinViewModel()`.

**Theme model** has a domain layer and a remote/DTO layer, converted through extension functions in `model/theme/domain/Theme.kt`:
- Domain: `ScoreboardTheme` holds Compose `Color` values (also has `DEFAULT`/`DEFAULT_1` constants, and is provided to the preview tree via `LocalScoreboardTheme`).
- Remote: `@Serializable` `ThemeBody` / `ThemeContent` / `ThemeColor` / `ThemeDto` using `#RRGGBB` hex strings + alpha. `toColor()` / `toThemeColor()` bridge the two.

**Result/error handling: `core/remote/LoadResult<S, E>`** — a sealed `Success`/`Error` type with `mapSuccess`/`mapError`/`doOnSuccess`/`doOnError`/`mapNestedSuccess` helpers. `runOperationCatching` wraps a block, catching non-cancellation `Throwable`s and routing network-shaped ones through `Throwable.toNetworkException()`, which is `expect`/`actual` (`commonMain` declares it; `jvmMain` maps `UnknownHostException`, `SocketTimeoutException`, `ConnectException`, `SSLException`). Repository methods return `LoadResult<..., Throwable>`; the ViewModel branches on `NetworkException` vs `UnauthorizedActionException` in `handleError`.

**Scoreboard preview tree** (`components/scoreboard/`) is a faithful port of the original scoreboard UI. `MatchDetailsScoreboardView` renders a `Match` against a `ScoreboardTheme`; the generate screen feeds it `DOUBLES_SAMPLE_MATCH` (from `model/match/domain`) plus the generated (or `DEFAULT`) theme so the recognized palette is visible live. Sub-components under `components/scoreboard/components/` read theme colors from `LocalScoreboardTheme`.

**File picking & resources:**
- Image selection uses Calf (`rememberFilePickerLauncher`) → reads bytes into `ImageFile(name, ByteArray)`. `EMPTY_IMAGE_FILE` is the sentinel for "no image". Supported formats checked in the VM: `.png`/`.jpg`/`.jpeg`.
- Compose Multiplatform resources live in `shared/src/commonMain/composeResources/` (`values/strings.xml`, `drawable/`). Access via the generated `scoreboardthemerecognizer.shared.generated.resources.Res` and `getString()` / `stringResource()`. Strings are used for all user-facing text (including error messages), so add new copy there rather than hardcoding.

## Theme recognition: two-stage pipeline (LLM → OpenCV) + offline fallback

`ThemeRepositoryImpl` (`model/theme/repository/ThemeRepositoryImpl.kt`) orchestrates recognition:

- **`generateThemeFromImage(...)`** first tries the **Stage-2 LLM path**: `ScoreboardThemeAgent` (`model/theme/analysis/ScoreboardThemeAgent.kt`, Koog `1.1.1`) makes one `executeStructured<AiComponentLayout>` call to OpenAI **GPT-4o** with the raw image at `temperature=0`. The LLM **never names colors** — it returns six normalized (0..1) role boxes (`AiComponentLayout`/`AiBox`, flat `@Serializable`). `measureComponentsColors` (`expect`/`actual`, jvmMain OpenCV) snaps each box to the strongest foreground glyph (local-contrast mask + morph-close + largest connected component — tolerates LLM coordinate drift) and measures background/text by **histogram mode + background-distance**. This is the architecture that worked in `D:\IdeaProjects\TennisScoreKeeperBackend` (`ThemeService` + `ScoreboardColorExtractor`); it avoids the "black instead of navy" failure of asking the LLM to name colors.
- **Offline / failure fallback:** when `OPENAI_API_KEY` is absent (read via the `readEnvironmentVariable` `expect`/`actual` — commonMain can't reference `java.*` directly), or the LLM call throws, or it returns no boxes, generation falls back to **Stage 1** — the on-device OpenCV heuristic `analyzeScoreboardImage` (`expect`/`actual`, zone-based K-Means with mode colors). The LLM returning `is_scoreboard=false` is **not** a fallback — it surfaces as `LoadResult.Error(NotAScoreboardException)`.
- **`createTheme(...)`** is still a **no-op success** — there is no persistence layer (Room/DB was dropped from this port).

The Koog (`ai.koog:koog-agents`, `commonMain`) and OpenCV (`org.openpnp:opencv`, `jvmMain`-only) dependencies are both invoked. Natives load once via `OpenCV.loadLocally()`. To exercise the LLM path: set `OPENAI_API_KEY` in the environment before `:desktopApp:run`; without it, generation silently uses the heuristic.

Keep `ThemeRepositoryImpl`'s signature/return type (`LoadResult<ThemeContent, Throwable>`) intact — the ViewModel, error handling, and preview all depend on it.
