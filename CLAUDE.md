# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

BetterHvNote is an Android app that replaces the stock note-taking app on Hanvon e-ink stylus tablets (e.g. N10Pro, running a custom ROM). The device ROM exposes a proprietary low-latency pen overlay service (`android.os.HvPenDrawManager` / `HvPenDrawListener`, obtained via `getSystemService("hvpen")`). This codebase is a clean-room Kotlin rewrite of the stock app's pen pipeline, reverse-engineered from the decompiled vendor app (referred to in comments as "hvNote 7.16") and ported bit-exact where the ROM's expected behavior is undocumented (coordinate transforms, mode constants).

`inkengine-plan.md` is the long-term architecture spec (in Chinese). The code now implements Phases 1–6: vector ink, Notebook/Page/Scene/UUID, uniform-grid spatial queries, eraser/lasso/transform editing, Command-based undo/redo, SQLite+WAL autosave/recovery/migrations, and multi-page navigation with a three-Scene cache and asynchronous vector thumbnails. **Phase 7 export** is now implemented (PNG/PDF export with vector strokes, see `export/` package). Future image/audio/sync/AI providers are not implemented. When adding features, check the cited `inkengine-plan.md` section numbers and preserve the existing Core → storage/render boundary.

## Tooling preference

For refactoring, building, or deploying to a device, prefer the Android Studio MCP tools first (project sync, build, run/deploy, refactor operations) over raw `./gradlew` shell invocations or manual multi-file edits. Fall back to the Gradle CLI commands below only if the Android Studio MCP is unavailable or doesn't cover the operation.

## Build and test

```bash
./gradlew assembleDebug        # build debug APK
./gradlew test                 # run all JVM unit tests
./gradlew :app:testDebugUnitTest --tests "com.betterhv.note.ink.StrokeGeometryTest"   # single test class
./gradlew :app:testDebugUnitTest --tests "*.StrokeBuilderTest.someMethodName"          # single test method
```

The build uses JDK 17 (`JavaVersion.VERSION_17`), centralized dependency/plugin versions in `gradle/libs.versions.toml`, and `config/detekt/detekt.yml` (`warningsAsErrors: true`).

The framework-free `ink`, `doc`, `tool`, and pure `storage` components use plain JUnit4 without Robolectric. Android SQLite, `android.graphics`, `View`, and vendor JNI/ROM integration (`PenDrawView`, `ThumbnailManager`, `PenGeometry`, `NativeSelfTest`) still require an Android device for end-to-end verification.

GitHub Actions runs JVM tests, detekt, Android lint, Windows tests/lint, and protobuf schema lint for pull requests and pushes to `main` (release APK and Windows portable-package builds run in the tag-triggered `release.yml` workflow).
All Gradle build outputs across every module are centralized under the root `build/<module>/` directory (e.g., `build/app/outputs/apk/`, `build/phone-app/outputs/apk/`, `build/phone-desktop/distributions/`).
Locally, `./gradlew test` and `./gradlew assembleDebug` remain the baseline checks.

## Architecture

### Module layout

- `:app` — the application module (`com.betterhv.note`), namespace/applicationId `com.betterhv.note`, `minSdk 29`/`compileSdk 37`/`targetSdk 37`, `arm64-v8a` only. Internal packages under `com.betterhv.note`:
  - root (`MainActivity`, `PenDrawView`, `PenGeometry`, `Toolbar`, `AppCommands`, `EditorInteraction`, `EditorModes`, `StylusClickResolver`, `UiInputBlockState`, `NoteAppInstaller`, `NoteI18n`, `EventLog`, `NativeSelfTest`)
  - `backup/`, `canvas/`, `doc/` (+ `doc/commands/`), `export/`, `hardware/`, `ink/`, `jni/` (`InkStrokeModelerJNI` + `libink_stroke_modeler_jni.so`), `notebook/`, `notelink/`, `pdf/`, `settings/`, `storage/`, `template/`, `tool/`, `toolbar/`, `ui/`
- `:framework-stubs` — a `java-library` module providing **compile-only** stubs for the ROM's custom framework classes (`android.os.HvPenDrawManager`, `android.os.HvPenDrawListener`). These exist purely so the app compiles; the real implementations are baked into the device ROM's `framework.jar` and are never packaged into the APK (see `compileOnly(project(":framework-stubs"))` in `app/build.gradle.kts`). Method bodies in the stubs just `throw new RuntimeException("stub")` — they are never actually invoked, only linked against.
- `:phone-app` — the Android NoteLink sender/receiver application (`com.betterhv.note.sender`, `minSdk 29`/`compileSdk 37`/`targetSdk 37`). Subpackages under `com.betterhv.note.sender`: root (`MainActivity`, `TransferForegroundService`, `ReceiveBootReceiver`), `data/` (`PhoneQueueRepository`, `ExportInboxRepository`, `PhoneSendHistoryStore`, `NoteLinkSettings`), `share/` (`ShareIntake`, `SharePlan`, `ShareUndoNotifier`/`ShareUndoReceiver`, `ShareShortcutPublisher`), `service/` (`PhoneCommandProcessor`, `NoteLinkTransferRuntime`, `PhonePairingMode`).
- `:phone-desktop` — the Compose Desktop NoteLink application (`phone-desktop/src/main`). Desktop UI (`NoteLinkDashboard`), SQLDelight schemas (`src/main/sqldelight/{queue,inbox,history}`), tests, resources, and portable ZIP packaging (`packagePortableZip`) live under this module.
- `:transfer-core` — platform-neutral NoteLink v4 transfer protocol (`transfer-core/src/main`), cryptography, observability, `NoteLinkI18n` (`com.betterhv.transfer.core.i18n`), and shared file-transfer orchestration.
- `:transfer-android` — Android BLE, LAN, and Wi-Fi Direct discovery/session integration used by the tablet and phone apps.
- `:transfer-windows` — Windows Kotlin/JNA transport plus the native Wi-Fi Direct integration used by desktop NoteLink.
- `:update-core` — platform-neutral Note App / NoteLink update checking, GitHub Release asset resolution, SHA-256 verification, and download coordination.
- `icon-assets/` — versioned icon sources and the generation script for app icons.
- `.github/` — CI and tag-triggered pre-release workflows. `note-vX.Y.Z` publishes
  BetterHvNote; `notelink-vX.Y.Z` publishes Android and portable Windows NoteLink builds.
- `tools/` — tracked repository scripts (`tools/build_release.py`, `tools/elfsyms.py`).
- `hvNote/` — decompiled reference material from the original vendor app (smali, extracted `.so`s, APK) used as the porting source. Read-only reference, not part of the build (excluded via `/hvNote/` in `.gitignore`).

### NoteLink queues, history, and Windows deployment

- **Share intake** (`ShareIntake` + `ContentSniffer`): the provider MIME type is only a hint. Content is sniffed from magic bytes, then file name, then declared MIME, and each item is classified independently so one unsupported item never discards the batch. `MainActivity` is `singleTask`, consumes a share only on first creation, and treats a content-free `ACTION_SEND` as "open the app".
- **Send history**: both platforms keep a separate `send_history.db` plus a `history/` payload directory; do not add history tables to the queue database. `SendHistoryRecorder` owns the 100-entry / 30-day / 512 MiB retention policy, the re-send decision and crash reconciliation. Commit order is "rename payload → insert history row → delete queue row"; `reconcileHistory()` finishes interrupted commits and removes orphaned payloads.
- **Send order**: `SendOrder` (`QUEUE`/`STACK`) is a sender-side setting applied by `SendOrderSelector` through `QueueStore.next`; the NoteLink v4 protocol is unchanged, so older peers keep working.
- **Windows single instance**: `SingleInstanceGuard` holds a file lock and answers `SHOW` on a loopback port, so a second launch fronts the running window instead of starting another receiver.
- **Windows firewall**: `WindowsFirewallRuleChecker` trusts only `NOTELINK|...` ASCII markers printed by PowerShell and drains stdout on a reader thread; the installer and bundled scripts elevate through Base64 `-EncodedCommand`, so paths with spaces or non-ASCII characters never enter a command line. Keep new script output ASCII/UTF-8.

### The pen input pipeline (core data flow)

```
ROM hvpen service (native, low-latency overlay)
      |  onPenTouchUpStatus(up, points) — fires multiple times per physical gesture,
      |  batches uncorrelated with each other
      v
PenDrawView (HvPenDrawListener impl)
      |  brackets a gesture using its own ACTION_DOWN/UP (the ROM's batches don't
      |  correlate to one gesture on their own); converts digitizer coords to view
      |  coords via PenGeometry.pointSysToClient
      v
StrokeBuilder  — one instance per gesture
      |  per point: InputFilter (drop near-duplicate samples) -> StrokeSmoother
      |  (Google ink-stroke-modeler, transactional whole-stroke output)
      |  at pen-up: StrokeSimplifier (RamerDouglasPeuckerSimplifier, pressure-aware RDP)
      v
Stroke (immutable, ink/Stroke.kt) — the authoritative vector record
      |  StrokeGeometry.build() computes the shared swept-disc fill from the
      |  centerline + PenStyle.widthAt(point) (cached, since it's expensive)
      v
InkRenderer — draws the outline as a filled Path via Android Canvas/Skia
      |  into PenDrawView's own cached Bitmap (the "document" bitmap is a
      |  regenerable cache of the stroke list, never the source of truth)
      v
View.postInvalidate(dirtyRect) — only the touched region is repainted
```

Key invariants to preserve when touching this path (see extensive rationale comments in `PenDrawView.kt`):
- **Strokes are the source of truth; the bitmap is a rebuildable cache** (spec §2.1). Any new editing feature must mutate the `strokes` list and repaint from it, not touch pixels directly.
- The ROM paints its own low-latency overlay independently of the document bitmap. During writing, keep that overlay as the live image; at a structural materialization point, first commit the vector stroke, redraw the bitmap, then call `penDraw.resetData()` so the old overlay copy does not remain beside the materialized result.
- `PenGeometry` is a bit-exact port of decompiled vendor bytecode (coordinate transforms depending on device model / screen origin / rotation). Comments explicitly warn not to "simplify" the branch structure — it mirrors the original smali register-by-register and encodes device quirks that aren't otherwise documented.
- Eraser mode is selected on the ROM side via `setDrawStatus` with an *encoded width* (`eraserWidth + 0x69`), not a mode enum — this is a reverse-engineered vendor quirk, not arbitrary.
- The ROM callback's third float is a vendor-computed geometric width in screen pixels. For `NormalPen`, divide it by the exact integer width configured for that gesture and store the dimensionless ratio in the existing `InkPoint.pressure` scalar slot. Do not clip it: any non-finite or out-of-range scalar makes the modeler return the filtered raw stroke in full. `Pencil` and `Marker` store `1f` and resolve to fixed width.
- All three brushes use the same modeler and swept-disc geometry. `PenStyle.widthAt(InkPoint)` is the sole geometry width boundary: `NormalPen` uses `baseWidth * point.pressure`; `Pencil` and `Marker` use `baseWidth`. Brush-specific rendering is limited to color/alpha.

### Toolbar tools and the e-ink UI layer

- `com.betterhv.note.toolbar` is framework-free. `ToolbarTool` has four sealed branches by primary side effect (canvas, floating window, full-screen page, immediate document action); `ToolbarCatalog` is the explicit source of the 14 tools, shared actions, commands and window specs. Adding a tool means adding one tool class and one registration in `ToolbarCatalog.tools`; its primary and gesture commands then appear automatically in the K1–K8 binding directory. Do not reintroduce `ToolbarCommandItem` or per-tool `when` branches in `MainActivity`.
- `com.betterhv.note.ui` owns `EinkTheme`, `EinkTokens` and shared e-ink components. Use its monochrome colors, 0-radius/1dp-border geometry, 44dp touch targets and typography roles for the toolbar, its opened surfaces, and the application settings page (including `HardwareBindingsPanel`). `EinkTheme` is scoped, not installed at the app root; pen-color swatches and document thumbnails are content exceptions.
- Tool actions return pure `ToolbarEffect` values. `ToolEffectSink` in `AppRoot` maps them to state and `PenDrawView`; do not call editor/UI side effects from a tool object.

### The `ink` package (`com.betterhv.note.ink`) is framework-free by design

Everything in this package (`InkPoint`, `Stroke`, `PenStyle`, `PressureCurve`, `StrokeGeometry`, `StrokeBuilder`, `StrokeSimplifier`, `StrokeSmoother`, `InputFilter`, `Bounds`) has zero Android dependencies — colors are packed ARGB ints, geometry is flat `FloatArray`s, no `android.graphics.*` types leak in. `InkRenderer` (also in this package) is the sole exception and is explicitly called out in its own doc comment as "the one place in the ink pipeline allowed to know about android.graphics". Keep new ink/geometry code in this package framework-free — that's what makes it JVM-testable without Robolectric and is a deliberate architectural boundary from `inkengine-plan.md` §93 (Core must not depend on UI framework, e-ink SDK, or any specific rendering backend).

### JNI / native library bindings

`com.betterhv.note.jni.InkStrokeModelerJNI` binds the C++ `ink_stroke_modeler_jni` library (`libink_stroke_modeler_jni.so`, built via `app/src/main/cpp/CMakeLists.txt` against `third_party/ink-stroke-modeler`). Preserve its exact package/class/method signature and `NativeSelfTest` smoke test. `tools/elfsyms.py <lib.so> [filter]` is available for inspecting ELF64 dynamic symbols in native binaries.

## Working conventions specific to this repo

- Comments in this codebase frequently cite `inkengine-plan.md` by section number (e.g. "spec §22") and/or the decompiled vendor source ("hvNote 7.16 smali", "HandView", "PslOp", "MemoView"). When modifying pipeline code, check whether a similar cross-reference should be added/updated — these comments are load-bearing documentation of *why*, not incidental.
- Do not "clean up" or refactor `PenGeometry`'s branch structure or the ROM mode constants in `PenDrawView` — they encode reverse-engineered, per-device-model vendor behavior with no independent spec to verify against; a simplification that looks equivalent can silently break specific hardware/rotation combinations.

### Export system (`com.betterhv.note.export`)

The export functionality (Phase 7) follows the architecture principle: **strokes are source of truth**. Export never reads from rendered bitmaps; it renders fresh from `PageSnapshot`.

**Key components:**
- `ExportTaskRepository` stores reusable single-page, fixed-selection, and all-page tasks in the notebook SQLite database. Schema v9 adds `export_tasks.render_mode` (`VECTOR` default); existing tasks migrate to vector mode. Page references, run reports, and artifact metadata also live here.
- `PageRenderer` uses one Android Canvas path for 2x PNG and PDF output, including multiline text, object transforms, and image EXIF orientation. `renderObjectsOnto()` overlays objects on an existing bitmap for fast PDF pages.
- `ExportEngine` has two PDF ink strategies selected per task:
  - `ExportRenderMode.VECTOR` (default): one editable PDF Ink annotation per stroke, composed in a single document save per page.
  - `ExportRenderMode.FAST`: each page (template/PDF background + images + text + all strokes) is rasterized once at 2x with an 8 MP/8192 px cap and embedded as one image object. Faster, but ink is not editable per stroke.
- Page files are shared across tasks by `ExportPageCacheKey` (`pageId + contentRevision + background + rendererVersion + mode`) under `files/exports/pages/`; single-page PDF tasks publish the page file directly instead of merging.
- `ExportViewModel` owns the active job, per-task render mode, progress/cancellation, Downloads destination, and task list state. `ExportProgress`/`ExportProgressSink` report page-level progress, elapsed/ETA, and throttle updates; native PDF saves are an uninterruptible boundary (cancel applies after the call returns).
- `ExportManagerScreen` is the full-screen, cross-notebook task manager opened from both the toolbar and notebook cards.

**Export formats:**
- **PNG**: Single page only, 2x resolution, ARGB_8888; PDF source pages are composited from the MuPDF background plus ink (no temporary PDF).
- **PDF**: Single page, page range, or all pages; vector editable ink or fast raster pages, chosen when creating the task.

**File locations:**
- `files/exports/artifacts/` holds task artifacts, `files/exports/pages/` holds shared page files, and `files/exports/reports/<taskId>.report` holds the last run's timings/counters. All writes use temp files + atomic moves.
- User-facing local copies are published through MediaStore to `Downloads/BetterHvNote`.

**Observability:**
- Every run writes a structured report (mode, pages, cache hits/misses, render/graft/save/verify/finalize durations, bytes, result) and one `EventLog` line.
- Task rows show the last duration/mode; the Info dialog shows the full report and error diagnostics.

**NoteLink integration:**
- BLE capability negotiation and push commands extend the existing protocol without renumbering Phone→Note commands.
- NoteLink remains discoverable after pairing, uses the NoteLink v4 control protocol and encrypted file channel for PDF/PNG (LAN first, Android Wi-Fi Direct fallback), and keeps a persistent inbox.
- Artifact IDs make retry idempotent; the receiver validates declared length and SHA-256 before committing a file.

**Known limitations:**
- Fast mode is not editable per stroke and usually produces a larger PDF than vector mode; it is opt-in per task.
- Native `PDFDocument.save()`/`PdfDocument.writeTo()` cannot be interrupted, so cancel takes effect after the current save returns.
- Multi-page output still re-merges every page; this is not a true incremental PDF update.
- Imported PDF annotations are preserved and displayed read-only; editing those source annotations is not supported.
- Imported encrypted PDFs and PDF reflow are not supported.
- Large notebooks (>100 pages) not stress-tested
- Memory: renders one page at a time, recycles bitmaps immediately

**Testing:**
- `ExportPageCacheKeyTest`, `ExportRevisionTest`, `ExportRasterTest`, `ExportRunReportCodecTest`, `ExportProgressSinkTest`, and `ExportProgressPresentationTest` cover the framework-free export model.
- `PageRendererInstrumentedTest` covers actual Android PNG/PDF rendering, page dimensions, vector annotations, and multi-page PDF assembly.
- `ExportFastModeInstrumentedTest`, `ExportCacheInstrumentedTest`, `ExportCancellationInstrumentedTest`, and `ExportMigrationInstrumentedTest` cover fast raster pages (including PDF sources), cross-task cache sharing, cancel atomicity, and the v8→v9 migration.
- `ExportBenchmarkInstrumentedTest` measures a 12-page × 60-stroke notebook. On the N10 Pro reference device (two runs): vector cold ≈36–50 s, fast cold ≈6.2–6.4 s (≈6–8×), fast artifact 3.5 MB vs vector 2.4 MB; a one-page edit re-renders only 1/12 pages.
- `BleQueueProtocolTest` covers the backward-compatible export push command/response codec.

When adding a format, extend `ExportFormat`, task validation, `ExportEngine.generate()`, and NoteLink's accepted MIME allowlist together.
