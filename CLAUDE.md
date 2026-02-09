# Sticker Maker — working notes

Single-module Android app. Kotlin, Jetpack Compose, Material 3 (1.5 Expressive), Navigation 3,
DataStore. minSdk 31, English only.

## Build

```sh
./gradlew assembleDebug
./gradlew assembleRelease                      # R8 + resource shrinking, debug-signed for now
./gradlew :app:generateBaselineProfile         # emulator only (see below); commit the output
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   # real device (emulators refuse)
./gradlew :app:lintRelease
./gradlew :app:compileReleaseKotlin -PcomposeReports --rerun   # stability report → app/build/compose_compiler/
```

The owner's Pixel 9 (Android 17 user build) **cannot run Macrobenchmark or dump profiles**:
`trace_marker` isn't visible and the profile comes back empty. Generate baseline profiles on an
emulator. For real-device numbers, time cold starts with `am start -W` and frames with
`dumpsys gfxinfo`. Run and test everything else on the physical phone, which uses a
display-size override (356 dpi), so find tap targets with `uiautomator dump`, not fixed
coordinates.

## Dependencies

- Stable versions only, unless a feature exists only in a pre-release. Then pin just that
  artifact with a comment saying why and when to drop it (see `material3` in the catalog).
- Everything goes through `gradle/libs.versions.toml`.
- No Hilt, no Room/Retrofit/etc. until a feature actually needs them.

## Architecture: keep it simple

- No clean-architecture layers, no use cases, no domain module, no interfaces with one impl.
- `data/`: stores and repositories, exposing `Flow`s and suspend functions.
- `ui/screens/<feature>/`: `XScreen.kt` plus an `XViewModel.kt` when the screen has real state.
  A ViewModel exposes one `StateFlow<XUiState>` (`stateIn(WhileSubscribed(5_000))`), and
  screens collect it with `collectAsStateWithLifecycle`. Screens with no persistent state skip the VM.
- `AppContainer`: manual DI with `by lazy` vals, provided via `LocalAppContainer`. Get VMs with
  `appViewModel { XViewModel(it.settings) }` (scoped to the nav entry).
- Navigation 3: `@Serializable data object/class XRoute : NavKey` in `Routes.kt`, wired in
  `AppNavigation.kt`. Push with `backStack.push(...)`, which ignores double taps.
- `Application.onCreate` stays empty. Anything startup-heavy is lazy or deferred.

## Design system: neo-brutalist, not minimal

The references are Gumroad and a pastel neo-brutalist dating-app shot: flat pastel blocks, ink
outlines, hard offset shadows, chunky type, playful tilt. UI/UX is the point of this app, so
go bold: big buttons, squiggly edges and stickers slapped on things are all welcome.

- Use tokens only: `Palette`, `AppTheme.colors` (canvas/surface/ink/inkMuted/onBlock),
  `Dimens`, `Shapes`, `StickerTypography` (Outfit variable font), `Motion`. No magic numbers.
- Pastels are the same in both themes. Text on a pastel always uses `onBlock` (dark). Dark
  mode is AMOLED black with cream ink.
- Build from `BrutalSurface` (outline + hard shadow + press-sinks-into-shadow). Higher-level
  pieces live in `ui/components`: `ChunkyButton`, `IconTile`, `StickerCard`, `StickerTag`,
  `SegmentedPicker`, `StickerTopBar`, `StickerBottomSheet`, `EmptyState`, `SkeletonBlock`,
  `ToastHost` and `SquigglyShape`/`WobblingShape`.
- Hard shadows paint outside bounds. Give containers end/bottom padding of at least the
  shadow size (lists use `end = ScreenGutter + ShadowL`).
- Never fade a brutal component with a plain `graphicsLayer { alpha = … }`. Alpha < 1 renders
  offscreen, clipped to the bounds, and that cuts off the shadow. Set
  `compositingStrategy = CompositingStrategy.ModulateAlpha`, and drive alpha from a
  non-overshooting spec (`Motion.effects*`), never a bouncy one.
- Icons are Material Symbols Rounded (weight 600, filled) as vector XML in `res/drawable`.
  Fetch new ones from `google/material-design-icons` (`symbols/android/<name>/materialsymbolsrounded/<name>_wght600fill1_24px.xml`)
  and strip the `android:tint="?attr/colorControlNormal"` line.
- `ui/screens/designkit` is a live gallery of every component. Add new components there.

## UX and motion rules

- No haptics or vibration anywhere.
- Every animation uses a `Motion` spec: spatial springs may overshoot, effects never do.
  Screen transitions are duration-based so predictive back can scrub them.
- Lists: stable `key`s, `animateItem()`, and `staggeredEntrance(index)` for first appearance.
- Destructive actions happen immediately and offer Undo (`toasts.showUndo(...)`), with no
  confirm dialogs.
- Toasts (`rememberToastState()` + `ToastHost`) stack. The bottom 3 are live and count down;
  the 4th peeks out from behind the stack; the rest are hidden. They are cleared only by
  swiping left or right (timeouts slide away the same way), with no close buttons. Launch
  one coroutine per `show()`. Use bottom sheets rather than dialogs.
- Show skeletons, not spinners. Empty states get an icon tile, a headline, a line of help and an action.
- Container transforms between screens use `Modifier.sharedBlock(key)` with the same key on both sides.
- Decorative loops (wobble, pulse, stagger) must check `LocalReduceMotion`.

## Recomposition and animation performance

These follow Google's Compose performance guidance, audited 2026-11-07.

- Never read an animated value in composition. Read it in a lambda modifier
  (`graphicsLayer {}`, `offset {}`, `drawBehind {}`/`drawWithCache {}`) or a `ColorProducer`
  (`BasicText(color = { … })`). Animated shapes go through `WobblingShape(…, phaseState)`,
  never `SquigglyShape(phase = phase.value)`.
- For an animated colour on an icon, draw the painter in `drawBehind` with
  `ColorFilter.tint(state.value)`.
- `remember(keys)` any non-trivial object built in a composable (e.g. the theme's ColorScheme).
- Lazy lists get a stable `key` and a `contentType`. Read CompositionLocals (`AppTheme.colors`)
  once above the list, not per item.
- Small, fixed content (about 10 items or fewer) uses `Column` + `verticalScroll`, not
  `LazyColumn`. Use `rememberCollapsed(scrollState)` for its top bar.
- Use `derivedStateOf` for scroll-driven booleans. Switch to `computedStateOf` once the
  Compose runtime is 1.13 or later.
- Keep render layers minimal. `BrutalSurface` uses one layer for press offset and clip; add an
  alpha layer only when it's needed.
- Re-run the compose reports after adding models: every restartable composable should stay
  skippable.

## Release and performance

- R8 runs over all packages (`optimization { enable = true }`). Keep rules go in
  `src/main/keepRules/`, which already keeps line numbers and strips `Log.v/d/i` in release.
- Regenerate the baseline profile after adding a major screen, and extend the journey in
  `BaselineProfileGenerator` to cover it.
- Before claiming a UI change works, check it with a release build on a device/emulator.

## Git

- Default branch `main`. Commit as `Anonymous <anonymous@example.com>` (set in the repo's local config).
- Imperative subject, plus a body that explains *why* when it isn't obvious.
