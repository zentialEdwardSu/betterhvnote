# BetterHvNote

BetterHvNote is a Kotlin note-taking and file-transfer workspace for Hanvon
e-ink tablets. The repository contains the tablet note app, Android and desktop
NoteLink clients, and the shared transfer stack used by all three applications.

## Repository layout

```text
BetterHvNote/
|-- app/                 Tablet note application
|-- framework-stubs/     Compile-only stubs for Hanvon ROM APIs
|-- phone-app/           Android NoteLink app and shared NoteLink sources
|-- phone-desktop/       Compose Desktop NoteLink application
|-- transfer-core/       Platform-neutral protocol, crypto, and transfer logic
|-- transfer-android/    Android BLE, LAN, and Wi-Fi Direct integration
|-- transfer-windows/    Windows transport and native integration
|-- icon-assets/         Versioned icon sources and generation script
|-- tools/windows-installer/ Windows MSI customization scripts
|-- third_party/         Git submodules used by native ink rendering
|-- gradle/              Gradle wrapper files
|-- CLAUDE.md            Architecture and contributor guidance
`-- settings.gradle.kts  Authoritative Gradle module list
```

Shared NoteLink UI and persistence sources live under
`phone-app/src/commonMain`. Desktop-specific implementation and resources live
under `phone-desktop/src/main`; the `phone-desktop` module wires these source
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
.\gradlew.bat detekt lint
```

On a connected Hanvon device, Android instrumentation tests can be run with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

See `CLAUDE.md` for the pen pipeline, storage invariants, native integration,
and module-specific development constraints.

## PDF notebooks

The tablet app can import a local PDF as a separate notebook. MuPDF renders the
unchanged source pages and supplies fixed-layout page geometry and structured
text. Direct pen strokes remain BetterHvNote objects; a PDF region can also be
linked to a full-screen note page as either a screenshot or extracted text.
Study navigation inserts those linked pages after their source page, while read
navigation skips them. Export grafts the original PDF pages and annotations,
then adds BetterHvNote strokes as editable PDF Ink annotations.

Encrypted PDFs, reflow, and editing annotations already embedded in the source
PDF are intentionally outside the first implementation phase.

## License and source availability

BetterHvNote is licensed under `AGPL-3.0-or-later`; its corresponding source is
published at https://github.com/zentialEdwardSu/betterhvnote. PDF Ink annotation
export uses the fixed `com.artifex.mupdf:fitz:1.28.0` artifact under MuPDF's AGPL
terms. See `LICENSE` and `NOTICE` before distributing an APK.

The Hanvon declarations in `framework-stubs` are compile-only descriptions of
external interfaces supplied by the device ROM. They are not packaged in the
application. Third-party code remains covered by its own accompanying license.
