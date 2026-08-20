# BetterHvNote

BetterHvNote is a Kotlin note-taking and file-transfer workspace for Hanvon
e-ink tablets. The repository contains the tablet note app, Android and desktop
NoteLink clients, and the shared transfer stack used by all three applications.

## Repository layout

```text
BetterHvNote/
|-- app/                 Tablet note application
|-- framework-stubs/     Compile-only stubs for Hanvon ROM APIs
|-- phone-app/           Android NoteLink app and shared phone/desktop sources
|-- phone-desktop/       Compose Desktop build wrapper for NoteLink
|-- transfer-core/       Platform-neutral protocol, crypto, and transfer logic
|-- transfer-android/    Android BLE, LAN, and Wi-Fi Direct integration
|-- transfer-windows/    Windows transport and native integration
|-- icon-assets/         Versioned icon sources and generation script
|-- third_party/         Git submodules used by native ink rendering
|-- gradle/              Gradle wrapper files
|-- CLAUDE.md            Architecture and contributor guidance
`-- settings.gradle.kts  Authoritative Gradle module list
```

Shared NoteLink UI and persistence sources live under
`phone-app/src/commonMain`. Desktop-specific implementation and resources live
under `phone-app/src/desktopMain`; the `phone-desktop` module wires those source
sets into the desktop distribution. Platform-neutral transfer sources are split
between `transfer-core/src/commonMain` and `transfer-core/src/main` as declared
in `transfer-core/build.gradle.kts`.

The local `hvNote/`, `tools/`, build outputs, IDE state, and architecture plans
are intentionally ignored. They are development inputs or generated artifacts,
not repository modules.

## Build and test

The project requires JDK 17 and the Android SDK configured by `local.properties`.

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug :phone-app:assembleDebug
.\gradlew.bat :phone-desktop:classes
```

On a connected Hanvon device, Android instrumentation tests can be run with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

See `CLAUDE.md` for the pen pipeline, storage invariants, native integration,
and module-specific development constraints.
