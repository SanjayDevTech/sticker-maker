# Sticker Maker — working notes

Single-module Android app. Kotlin, Jetpack Compose, Material 3 (1.5 Expressive), Navigation 3,
DataStore. minSdk 31, English only, 64-bit ARM only (`abiFilters += "arm64-v8a"`; the emulators
are arm64 too).

## Build

```sh
./gradlew assembleDebug
./gradlew assembleRelease                      # R8 + resource shrinking, signed with the public github-public-release key
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
- One native dependency: **libwebp** (v1.6.0), built by CMake from a pinned, SHA-256-checked
  release tarball (`app/src/main/cpp/`). The first build downloads it, so it needs network.
  To bump it, change the URL and hash together. It's used for lossless stickers at effort 6:
  about half the size of Android's encoder with identical pixels, verified on the phone. Level 9
  is about 20× slower for 2% smaller files.
  - Stickers too detailed for lossless under 100 KB take libwebp's lossy path: sharp YUV,
    method 5, exact alpha, at the highest quality that fits. That's +4 dB PSNR over Android's
    lossy encoder. Method 6 and "best" alpha filtering cost seconds for about no gain; don't
    re-add them.
  - `WebpNative` falls back to Android's encoders if the library is missing (still stickers only:
    Android can't encode animated WebP).
  - **Animated stickers** (`StickerMotion` → `MotionPlan` → `StickerExport.encodeAnimated`): each
    frame is encoded once as a full still, in parallel, and joined with libwebpmux. Don't switch
    to `WebPAnimEncoder`: it tries 4–8 encodes per frame looking for changed rectangles, which a
    sticker moving every frame never has (measured 7–23 s per sticker, against 1–4 s now).
    Lossless when the first frame shows the loop could fit. Otherwise lossy: three sample
    frames give bytes per frame at each quality, which sets quality (and, for GIFs, how many
    frames to keep) before the single full pass. Frames render in order and at most one per
    core is in flight, so memory stays flat for long clips.
  - **GIFs** (`editor/Gif`): our own decoder, since Android never hands out single GIF frames.
    Trimmed to ≤ 10 s, at most 25 fps, fitted into the square; too heavy for 500 KB at quality
    50 → drop every third frame and re-estimate. A 10 s 480×270 GIF takes ~5–6 s on the Pixel 9.
  - **Videos** (`editor/VideoFrames`): go through exactly the GIF path. `Gif` and `VideoFrames`
    both implement `Animation`; `SessionImage.isAnimated` covers both and `isVideo` tells them
    apart (labels and the default trim only). On import the video's first minute is decoded
    once into JPEGs at ≤ 512 px and ≤ 25 fps (sound dropped), so scrubbing and playback never
    seek a decoder.
    - Decoding: `MediaCodec` → GPU (`editor/FrameGrabber`: rotates upright, box-filters down
      to ≤ 512, reads back only the small frame), with JPEG writing on a pool beside it. The
      old `MediaMetadataRetriever` path is the fallback (e.g. HDR that can't be tone-mapped).
      Emulator: 60 s 720p loads in ≈ 6 s (was 42 s with the retriever).
    - Progressive: import returns once the first 3 s (the default trim) are ready; the rest
      loads in the background. `VideoFrames.loaded` lists the ready stretches; the trim bar
      hatches the rest and keeps the window inside a ready stretch. Arrange shows a `LoadRing`
      per item while importing and on video cards still loading.
    - One video decodes at a time (`Turns`): parallel decoders were slower in total on the
      emulator. Priority: every video's first 3 s, then the stretch the trim bar shows
      (`focus`, jumping from the key frame before it), then the rest of the video open in the
      editor, then the other videos in list order (`order`).
    - Before decoding, `MediaCodecList.findDecoderForFormat` (codec, size, profile) picks the
      decoder; if none exists, import fails at once with `UnsupportedVideo` ("can't play its
      format"), skipping the retriever. HDR: tone-mapping is requested; if the decoder can't,
      HLG loads unconverted (flagged `hdrUnconverted`, noted in the trim panel) and PQ goes to
      the retriever. The retriever's per-frame fallback gives up after 5 misses at the start.
      Untested on real HDR / unsupported-codec cases (the Pixel 9 handles all of them).
    - The trim bar shows at most 20 s at once and scrolls for longer clips (drag the frames,
      or hold a dragged end by an edge), with an overview line underneath.
    - Video is far busier than most GIFs. Each frame is a full still, so 500 KB goes fast:
      videos start with a **3 s** trim (still extendable to 10 s), and below 8 fps is a
      `lastResort` set (quality drops first). Combined stickers may also go below 8 fps,
      but only as a last resort. Measured on a busy 720p clip: 3 s → 30 frames, 414 KB;
      10 s → 45 frames, 470 KB.
    - Session cleanup deletes the frames while the Save screen may still be playing them,
      so a missing frame decodes as "keep the last one", never as a crash.
  - **Clips** (`editor/StickerClip`): anything already moving (a GIF's or video's trim, a combined
    sticker) is a `StickerClip` of frames at 512; Save plays (`ClipPlayer`) and encodes them
    the same way. A `Composition` is the combine canvas frozen: the longest GIF sets the length
    (else the longest motion), and shorter loops stretch to repeat a whole number of times
    (`Choreography`). The canvas preview draws with the same placement/motion maths.

## Architecture: keep it simple

- No clean-architecture layers, no use cases, no domain module, no interfaces with one impl.
- `data/`: stores and repositories, exposing `Flow`s and suspend functions.
- `editor/`: pure pixel/geometry work with no Android types where possible (`MaskOps`,
  `Segmenter`, `GridMaxFlow`, `Outline`), unit-tested on the JVM. `whatsapp/` and
  `keyboard/` hold the WhatsApp integration.
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
  `ToastHost`, `BrutalSlider`, `BrutalTextField`, `PackTray` and `SquigglyShape`/`WobblingShape`.
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
- Toasts (`rememberToastState()` + `ToastHost`) stack. The bottom 2 are live and count down;
  the 3rd peeks out from behind the stack; the rest are hidden. They are cleared only by
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

## WhatsApp: verified facts (Pixel 9, Nov 2026)

These were checked on a real device. Don't re-litigate them without new evidence.

- **Packs (3–30):** `whatsapp/StickerContentProvider` serves them, matching WhatsApp's
  official sample (paths, columns, `com.whatsapp.sticker.READ`), and `ENABLE_STICKER_PACK`
  adds them.
  - WhatsApp writes pack name, publisher and app link into stickers sent from a pack.
- **Deleted packs** are *retired*, not dropped (`PackIndex.retired`). The provider keeps
  serving them until WhatsApp's whitelist check says WhatsApp no longer has them.
  - Unknown pack IDs get a "Deleted pack" stand-in, so WhatsApp can still remove packs
    deleted before retiring existed.
- **Animated packs:** a pack is all still or all animated (`animated_sticker_pack`), never mixed.
  Animated stickers: ≤ 500 KB, ≤ 10 s, frames ≥ 8 ms (from the official sample's validator).
- **WhatsApp only refreshes its pack list after it restarts**, for adds and removes alike.
  An app can't force that.
- **Single stickers without a pack:** WhatsApp's message box accepts `image/webp.wasticker`
  (read via `adb shell dumpsys input_method`).
  - A clipboard clip with that MIME type, pasted into a chat, arrives as a real sticker.
  - The sticker keyboard (`keyboard/StickerKeyboardService`, commit-content API) uses the
    same type.
- **Dead ends, don't retry:**
  - Sharing a WebP with `ACTION_SEND` arrives as a **photo**.
  - Sticker EXIF metadata (pack, publisher, emojis) is **blanked**: WhatsApp re-encodes
    pasted and inserted stickers.
- **Compose in the keyboard:** pass `StickerTheme(fillScreen = false)`. The default root
  Surface is `fillMaxSize`, which made the IME window full screen.
  - Keep the panel a fixed height. Resizing the IME window per frame is sluggish, because
    the app behind relays out too.
- **Backup** (`data/PackBackup`, Settings): one zip with `backup.json` + each pack's files.
  Restore only adds packs whose id isn't live (a retired one comes back); it never replaces or
  removes. Ids are kept and `image_data_version` is bumped, so "Added to WhatsApp" stays exact:
  it's always asked of WhatsApp, never stored. Android's own zip checks reject `..` paths.
- **Package visibility:** the manifest's `<queries>` lists `com.whatsapp` and
  `com.whatsapp.w4b`.
- **Before any release:** `applicationId` is still `com.example.*`, which the Play Store
  rejects. Changing it later means a fresh install with no data.

## Release and performance

- R8 runs over all packages (`optimization { enable = true }`). Keep rules go in
  `src/main/keepRules/`, which already keeps line numbers and strips `Log.v/d/i` in release.
- Regenerate the baseline profile after adding a major screen, and extend the journey in
  `BaselineProfileGenerator` to cover it.
- The editing flow (pick, cut out, Move & size, Combine, GIF, Save, Pack) needs a photo, so it's
  recorded by hand instead: `app/src/main/baselineProfiles/editing-journey.txt`, merged with the
  generated profile at build time and never overwritten by it. To re-record on the emulator,
  install the `nonMinifiedRelease` APK, `pm clear` + `cmd package compile --reset`, use the app,
  then broadcast `androidx.profileinstaller.action.SAVE_PROFILE` and `pm dump-profiles
  --dump-classes-and-methods`; the profile lands in `/data/misc/profman/`.
- The profile is recorded on `nonMinifiedRelease` with R8 off (`androidComponents` in
  `app/build.gradle.kts`). With R8 on there, rules come out in renamed classes and are useless.
- Before claiming a UI change works, check it with a release build on a device/emulator.

## Signing

- Release builds are signed with `github-public-release.jks` (repo root). It is **public on purpose**:
  key and passwords are committed so anyone can build an APK that updates a GitHub release.
  It protects nothing, so never use it for the Play Store; that would need a private key.

## Git

- Default branch `main`. Commit as `Anonymous <anonymous@example.com>` (set in the repo's local config).
- Imperative subject, plus a body that explains *why* when it isn't obvious.
