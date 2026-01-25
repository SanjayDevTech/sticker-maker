# Sticker Maker — working notes

Single-module Android app. Kotlin, Jetpack Compose, Material 3 (1.5 Expressive), Navigation 3,
DataStore. minSdk 31, English only.

## Build

```sh
./gradlew assembleDebug
./gradlew assembleRelease                      # R8 + resource shrinking, debug-signed for now
./gradlew :app:generateBaselineProfile         # needs a device/emulator; commit the output
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   # real device (emulators refuse)
./gradlew :app:lintRelease
```

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
  `StickerSnackbarHost` and `SquigglyShape`.
- Hard shadows paint outside bounds. Give containers end/bottom padding of at least the
  shadow size (lists use `end = ScreenGutter + ShadowL`).
- Icons are Material Symbols Rounded (weight 600, filled) as vector XML in `res/drawable`.
  Fetch new ones from `google/material-design-icons` (`symbols/android/<name>/materialsymbolsrounded/<name>_wght600fill1_24px.xml`)
  and strip the `android:tint="?attr/colorControlNormal"` line.
- `ui/screens/designkit` is a live gallery of every component. Add new components there.

## UX and motion rules

- No haptics or vibration anywhere.
- Every animation uses a `Motion` spec: spatial springs may overshoot, effects never do.
  Screen transitions are duration-based so predictive back can scrub them.
- Lists: stable `key`s, `animateItem()`, and `staggeredEntrance(index)` for first appearance.
- Destructive actions happen immediately and offer Undo (`snackbar.showUndo(...)`), with no
  confirm dialogs. Use bottom sheets rather than dialogs.
- Show skeletons, not spinners. Empty states get an icon tile, a headline, a line of help and an action.
- Container transforms between screens use `Modifier.sharedBlock(key)` with the same key on both sides.
- Decorative loops (wobble, pulse, stagger) must check `LocalReduceMotion`.
- Remember derived/expensive values and mark UI models `@Immutable`.

## Release and performance

- R8 runs over all packages (`optimization { enable = true }`). Keep rules go in
  `src/main/keepRules/`, which already keeps line numbers and strips `Log.v/d/i` in release.
- Regenerate the baseline profile after adding a major screen, and extend the journey in
  `BaselineProfileGenerator` to cover it.
- Before claiming a UI change works, check it with a release build on a device/emulator.

## Git

- Default branch `main`. Commit as `Anonymous <anonymous@example.com>` (set in the repo's local config).
- Imperative subject, plus a body that explains *why* when it isn't obvious.
