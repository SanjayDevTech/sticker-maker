# Sticker Maker

Make WhatsApp stickers from your photos without WhatsApp's forced white border or broken
transparency. Android, with a loud neo-brutalist UI.

## What it does

- **Pick or share in** one or more photos, then reorder them by dragging.
- **Separate** (one sticker per photo) or **Combine** (all photos as layers of one sticker).
- **Cut out** each photo:
  - **Brush:** a hard brush, 1–150 px.
  - **Auto:** erase a colour region with one tap; the Match slider re-runs that fill live.
  - **Magic:** scribble Keep or Remove marks and it finds the subject, using GrabCut-style
    colour models and a graph cut, with no AI.
  - **Erase / Restore:** every tool can also restore what was erased.
  - Undo is kept per image, and you can zoom to single pixels.
- **Combine canvas:** move, scale and rotate layers, restack, flip and delete them, with undo.
  A single photo can use it too (**Move & size**) to zoom, pan or turn it.
- **Save:** 512×512 transparent WebP under 100 KB. The white outline is optional and off by
  default.
- **Into WhatsApp:**
  - **Pack (3–30 stickers):** "Add to WhatsApp". WhatsApp reads it from the app's content
    provider.
  - **A single sticker, no pack:** **Copy** it, then paste it in a chat. Or switch to the
    **sticker keyboard** and tap one. Either way it arrives as a real sticker.

## Build

```sh
./gradlew assembleDebug
./gradlew installRelease
./gradlew :app:testDebugUnitTest   # pixel maths, graph cut, outline
```

Needs Android SDK 37. The Gradle daemon provisions its own JDK. minSdk is 31, English only.

## Stack

Kotlin · Jetpack Compose · Material 3 Expressive · Navigation 3 · DataStore · Baseline Profiles

Single module, simple layers:
- `data/`: packs, session, settings.
- `editor/`: pure pixel work (masks, flood fill, segmentation, outline, export), unit-tested
  on the JVM.
- `whatsapp/`: the provider and helpers.
- `keyboard/`: the sticker keyboard.
- `ui/`: the design system and one package per screen.

Settings → Design kit shows every component live. Conventions and hard-won facts are in
[CLAUDE.md](CLAUDE.md).

## How it was made

Built with AI assistance (Claude), directed, designed and tested by its maintainer. The commit
trailers say so too.

## License

[MIT](LICENSE) © 2026 Sticker Maker contributors.

Ships with libwebp (BSD 3-Clause), the Outfit font (SIL OFL 1.1), Material Symbols and the
AndroidX/Kotlin libraries (Apache 2.0). Their full texts are in the app under Settings → Licences.
