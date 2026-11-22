# BetterHvNote

BetterHvNote is an alternative note-taking application for Hanvon e-ink stylus tablets (currently targeting the **Hanvon N10 Pro 2**), paired with **NoteLink** companion apps for **Android** and **Windows**. It focuses on paper-like low-latency writing, clean e-ink interaction, vector PDF annotation, and frictionless cross-device transfer.

> **Device compatibility:** BetterHvNote currently supports the **Hanvon N10 Pro 2** (ARM64, custom Hanvon ROM). NoteLink runs on **Android** and **Windows x64** over LAN (no Bluetooth required on Windows), with optional BLE discovery/control and Android Wi-Fi Direct fallback.

## Key Features

### Low-Latency Vector Ink & E-Ink Editing
- **Native hardware ink overlay:** Integrates with the Hanvon ROM's low-latency pen overlay service so strokes appear immediately without screen refresh lag, while storing full vector stroke paths and pressure geometry instead of flattening strokes into a bitmap.
- **Configurable pens & editing:** Supports Normal Pen (pressure-sensitive swept-disc geometry matching live ROM ink), Pencil, and Marker, plus stroke/partial eraser, lasso selection, transforms, and command-based undo/redo.
- **Tail eraser & hardware shortcuts:** Touching the stylus tail inside an active selection deletes all selected items in one gesture (restorable with a single undo). The K1–K8 physical button binding panel supports per-context shortcuts and parameterized pen presets (slot, type, width, color).
- **Clipboard insertion & offline backup:** Insert images or text directly from the system clipboard (with optional hardware key bindings). **Settings → Backup & Restore** exports full self-contained ZIP archives (notebooks, images, PDFs, offline templates, and settings) with verified restore and one-step rollback to the pre-restore snapshot. NoteLink pairings stay device-local and are not migrated in backups.
- **Monochrome e-ink UI:** Scoped e-ink design system (`EinkTheme` / `EinkTokens`) with crisp 1 dp borders, zero-radius chrome, and 44 dp touch targets across the command-catalog toolbar, flyouts, managers, and settings surfaces.

### PDF Notebooks & Margin Insert Sheets
- **Local PDF annotation:** Import local PDFs as dedicated notebooks powered by MuPDF (`com.artifex.mupdf:fitz`) with fixed-layout rendering and structured text extraction.
- **Insert sheets & back-linked excerpts:** Add blank note pages ("insert sheets") after any PDF page for extended margin notes, and clip screenshots or extracted text from PDF regions onto any insert sheet with one-tap navigation back to the source PDF page. Switch between **Study mode** (includes insert sheets) and **Read mode** (skips them). Multi-insert notebooks display a dedicated insert-sheet thumbnail column (or two rows when docked horizontally).
- **Flexible PNG & PDF export:** Export single pages as 2× PNGs or single/range/all-page PDFs in two modes:
  - **Vector mode (default):** Grafts original PDF pages and annotations and embeds BetterHvNote strokes as editable PDF Ink annotations.
  - **Fast raster mode (opt-in):** Rasterizes each page once at 2× (capped at 8 MP / 8192 px) for significantly faster export on dense notebooks.
  - Includes cross-task page caching, cancellable export queues, progress/ETA reporting, and per-task run diagnostics.
- *Current limitations:* Encrypted PDFs, PDF reflow, and editing pre-existing annotations embedded in source PDFs are not supported.

### NoteLink Companion Transfer (Android & Windows)
- **Leave-free transfer workflow:** Send images, text, and PDFs from Android or Windows directly into BetterHvNote—or push exported PDFs/PNGs back from the tablet—without leaving the note editor.
- **NoteLink v4 encrypted protocol:** Uses Protobuf v4 with SRP-6a code-entry pairing, one-RTT PSK handshakes, and per-link encryption. Supports warm multi-NoteLink sessions, racing **LAN** (UDP 39818 discovery + TCP 39817 control/transfer) against **BLE**, plus **Wi-Fi Direct (P2P)** fallback on Android. A Windows PC without Bluetooth can pair and transfer over LAN alone.
- **Android share intake:** Accepts single or batch shares from the system share sheet, screenshot tools, and third-party apps. Sniffs content by magic bytes, filename, and declared MIME type independently per item so one unsupported item never discards the rest of a batch.
- **Send history & ordering:** Maintains a dedicated `send_history.db` (default retention: 100 entries / 30 days / 512 MiB) with one-tap re-send and switchable **Queue (FIFO)** / **Stack (LIFO)** delivery order.
- **Windows portable deployment:** Distributed as a self-contained portable ZIP with single-instance window activation and one-click UAC firewall setup creating inbound rules `NoteLink-TCP-39817` (TCP 39817) and `NoteLink-UDP-39818` (UDP 39818). Includes `Diagnose-NoteLink-Firewall.ps1` for troubleshooting blocked profiles.
  - Manual equivalent commands (Administrator):
    ```powershell
    netsh advfirewall firewall add rule name=NoteLink-TCP-39817 dir=in action=allow protocol=TCP localport=39817 profile=any
    netsh advfirewall firewall add rule name=NoteLink-UDP-39818 dir=in action=allow protocol=UDP localport=39818 profile=any
    ```

### Updating BetterHvNote via NoteLink
Once a compatible version of BetterHvNote and NoteLink are paired, you can update the tablet app from **Settings → General → App Updates → Update via NoteLink**:
- The tablet initiates the request; Android or Windows NoteLink fetches the latest stable GitHub Release, downloads the ARM64 APK, and verifies it against the official `SHA256SUMS.txt` before transferring it over the paired encrypted link.
- BetterHvNote verifies the package name, version, and signing certificate before handing the APK to the Android system installer (grant "Install unknown apps" on first use; interrupted installs can be resumed from the same settings page).
- If you prefer to supply the APK locally, choose **Import Note APK** in NoteLink settings. The file may be renamed, but its SHA-256 hash must match the latest stable official release asset. Pre-releases are not distributed through this path, and direct downloads from GitHub Releases remain available as a fallback.

## Repository Layout

```text
BetterHvNote/
|-- app/                 Tablet note application (`com.betterhv.note`, minSdk 29 / targetSdk 37, arm64-v8a)
|-- framework-stubs/     Compile-only stubs for Hanvon ROM APIs
|-- phone-app/           Android NoteLink application (`com.betterhv.note.sender`)
|-- phone-desktop/       Compose Desktop NoteLink application (`phone-desktop/src/main`)
|-- transfer-core/       Platform-neutral NoteLink v4 protocol, crypto, i18n, and transfer logic
|-- transfer-android/    Android BLE, LAN, and Wi-Fi Direct transport integration
|-- transfer-windows/    Windows Kotlin/JNA and native WinRT/LAN transport integration
|-- update-core/         Release update checking, SHA-256 verification, and download coordination
|-- docs/                Architecture ADRs, protocol specs, and acceptance checklists
|-- tools/               Local release build (`build_release.py`) and ELF inspection (`elfsyms.py`) scripts
|-- icon-assets/         Versioned icon sources and generation script
|-- .github/             CI workflows, tag-triggered release workflows, and Python release/changelog scripts
|-- third_party/         Git submodules used by native ink stroke modeling
|-- gradle/              Gradle wrapper and centralized version catalog (`libs.versions.toml`)
|-- CLAUDE.md            Architecture invariants and contributor guidance
|-- FULL_CHANGELOG.md    Auto-generated per-product release history
`-- settings.gradle.kts  Authoritative Gradle module list
```

Local reference material (`/hvNote/`), build outputs, IDE state, and scratch files are ignored via `.gitignore`.

## Build and Test

Requires **JDK 17** and an Android SDK configured in `local.properties`. All Gradle outputs across modules are centralized under `build/<module>/` (for example, `build/app/outputs/apk/`, `build/phone-app/outputs/apk/`, and `build/phone-desktop/distributions/`).

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug :phone-app:assembleDebug
.\gradlew.bat :phone-desktop:classes
.\gradlew.bat :phone-desktop:packagePortableZip
.\gradlew.bat detekt lint
```

You can also build release deliverables (and optionally install Android APKs via `adb`) using `tools/build_release.py`:

```powershell
python tools/build_release.py
python tools/build_release.py n10pro notelink-android --install
```

On a connected Hanvon device, run Android instrumentation tests with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

See [CLAUDE.md](CLAUDE.md) for the pen pipeline architecture, storage invariants, native JNI integration, and module boundaries, and [FULL_CHANGELOG.md](FULL_CHANGELOG.md) for detailed release history.

## Releases

GitHub Actions automatically builds, verifies, and publishes pre-releases when annotated product tags are pushed:
- `note-vX.Y.Z` publishes **BetterHvNote** (`BetterHvNote-X.Y.Z-android-arm64.apk` + `SHA256SUMS.txt`).
- `notelink-vX.Y.Z` publishes **NoteLink** (`NoteLink-X.Y.Z-android.apk`, `NoteLink-X.Y.Z-windows-x64.zip` + `SHA256SUMS.txt`).

Release notes and `FULL_CHANGELOG.md` are generated by Python scripts under `.github/scripts/`, scoped per product against the previous release of that product.

## License and Source Availability

BetterHvNote is licensed under `AGPL-3.0-or-later`; its corresponding source is published at <https://github.com/zentialEdwardSu/betterhvnote>. PDF Ink annotation export uses the fixed `com.artifex.mupdf:fitz:1.28.0` artifact under MuPDF's AGPL terms. See [LICENSE](LICENSE) and [NOTICE](NOTICE) before distributing an APK.

The Hanvon declarations in `framework-stubs` are compile-only descriptions of external interfaces supplied by the device ROM and are never packaged into the application. Third-party components remain covered by their respective licenses.
