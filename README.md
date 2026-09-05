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
|-- .github/             CI, release workflows, and release tag checks
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
.\gradlew.bat :phone-desktop:packagePortableZip
.\gradlew.bat detekt lint
```

On a connected Hanvon device, Android instrumentation tests can be run with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

See `CLAUDE.md` for the pen pipeline, storage invariants, native integration,
and module-specific development constraints.

## CI and releases

Pull requests and pushes to `main` run JVM tests, detekt, Android lint, both
Android debug builds, and the Windows portable-package build.

Releases use two independent version streams:

- `note-vX.Y.Z` builds `BetterHvNote-X.Y.Z-android-arm64.apk`.
- `notelink-vX.Y.Z` builds `NoteLink-X.Y.Z-android.apk` and
  `NoteLink-X.Y.Z-windows-x64.zip`.

Pushing either tag creates a GitHub pre-release with generated release notes and
`SHA256SUMS.txt`. After testing the downloaded packages, manually edit the
GitHub release and clear the pre-release flag. The in-app update checker ignores
pre-releases, so users only see a version after that manual promotion. The
release workflow can also be run manually with an existing tag; it never creates
or moves tags. A manual rerun may replace assets on an existing pre-release, but
refuses to alter a release that has already been promoted.

Both Android apps use one PKCS12 release key. Generate and securely back up the
keystore outside this repository. A new one can be created with:

```powershell
keytool -genkeypair -v `
  -keystore "D:\SecureBackup\betterhv-release.p12" `
  -storetype PKCS12 `
  -alias betterhv-release `
  -keyalg RSA `
  -keysize 4096 `
  -validity 36500
```

If `keytool` asks for a separate key password, press Enter to reuse the store
password. The workflow only accepts PKCS12 content and always restores it as a
`.p12` file. Configure these GitHub Actions secrets:

```text
ANDROID_RELEASE_KEYSTORE_BASE64
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
```

`ANDROID_RELEASE_KEYSTORE_BASE64` is the Base64 representation of the complete
PKCS12 file. Also configure the non-secret repository variable
`ANDROID_RELEASE_CERT_SHA256` with the signing certificate's SHA-256 fingerprint;
the workflow normalizes spaces and colons before comparing it with each APK.

The release key does not require a Google Play account. It must remain unchanged
for the lifetime of each application. Current development releases used the
Android debug key, so the first formally signed build cannot update those
installations in place. Back up BetterHvNote data before uninstalling a debug-key
installation and installing the first formally signed APK.

The Windows ZIP is portable and unsigned. Extract the entire archive, run
`Allow-NoteLink-Firewall.ps1` manually with PowerShell, approve the administrator
prompt, and then start `NoteLink.exe`. NoteLink checks the expected inbound TCP
39817 rule at startup and warns when it is absent or incorrect. The archive also
contains `Remove-NoteLink-Firewall.ps1`; Windows SmartScreen may show an unknown
publisher warning on first launch.

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
