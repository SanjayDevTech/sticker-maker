# Sticker Maker

An Android app with a loud, neo-brutalist UI. Its purpose is still to come; this is the foundation.

## Build

```sh
./gradlew assembleDebug
./gradlew installRelease
```

Needs Android SDK 37. The Gradle daemon provisions its own JDK. minSdk is 31.

## Stack

Kotlin · Jetpack Compose · Material 3 Expressive · Navigation 3 · DataStore · Baseline Profiles

Single module. `data/` holds stores, `ui/theme` the design tokens, `ui/components` the
component kit, and `ui/screens` one package per screen. Settings → Design kit shows every
component live. Conventions are in [CLAUDE.md](CLAUDE.md).
