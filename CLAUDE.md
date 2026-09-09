# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

BetterHvNote is an Android app that replaces the stock note-taking app on Hanvon e-ink stylus tablets (e.g. N10Pro, running a custom ROM on API 34/Android 14). The device ROM exposes a proprietary low-latency pen overlay service (`android.os.HvPenDrawManager` / `HvPenDrawListener`, obtained via `getSystemService("hvpen")`) and several vendor native libraries for pen rendering, gesture recognition, and e-ink dithering. This codebase is a clean-room Kotlin rewrite of the stock app's pen pipeline, reverse-engineered from the decompiled vendor app (referred to in comments as "hvNote 7.16") and ported bit-exact where the ROM's expected behavior is undocumented (coordinate transforms, mode constants).

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

`build.sh` sets `JAVA_HOME` to a JDK 17 install and runs `assembleDebug` — useful as a reference for the required JDK version if `./gradlew` fails with a Java version error.

The framework-free `ink`, `doc`, `tool`, and pure `storage` components use plain JUnit4 without Robolectric. Android SQLite, `android.graphics`, `View`, and vendor JNI/ROM integration (`PenDrawView`, `ThumbnailManager`, `PenGeometry`, `NativeSelfTest`) still require an Android device for end-to-end verification.

GitHub Actions runs JVM tests, detekt, Android lint, both Android debug builds,
and a Windows portable-package build for pull requests and pushes to `main`.
Locally, `./gradlew test` and `./gradlew assembleDebug` remain the baseline checks.

## Architecture

### Module layout

- `:app` — the application module (`com.betterhv.note`), namespace/applicationId `com.betterhv.note`, `minSdk 28`/`targetSdk 34`, `arm64-v8a` only.
- `:framework-stubs` — a `java-library` module providing **compile-only** stubs for the ROM's custom framework classes (`android.os.HvPenDrawManager`, `android.os.HvPenDrawListener`). These exist purely so the app compiles; the real implementations are baked into the device ROM's `framework.jar` and are never packaged into the APK (see `compileOnly(project(":framework-stubs"))` in `app/build.gradle.kts`). Method bodies in the stubs just `throw new RuntimeException("stub")` — they are never actually invoked, only linked against.
- `:phone-app` — the Android NoteLink sender/receiver application. Its `src/commonMain` tree is also consumed by the desktop build.
- `:phone-desktop` — the Compose Desktop NoteLink application. Desktop implementation, tests, resources, and packaging live under this module; shared UI remains in `phone-app/src/commonMain`.
- `:transfer-core` — platform-neutral transfer protocol, cryptography, observability, and shared file-transfer orchestration.
- `:transfer-android` — Android BLE, LAN, and Wi-Fi Direct discovery/session integration used by the tablet and phone apps.
- `:transfer-windows` — Windows Kotlin/JNA transport plus the native Wi-Fi Direct integration used by desktop NoteLink.
- `icon-assets/` — versioned icon sources and the generation script for app icons.
- `.github/` — CI and tag-triggered pre-release workflows. `note-vX.Y.Z` publishes
  BetterHvNote; `notelink-vX.Y.Z` publishes Android and portable Windows NoteLink builds.
- `tools/elfsyms.py` — standalone ELF64 dynamic-symbol dumper (no deps) for inspecting the vendor `.so` files under `app/src/main/jniLibs/arm64-v8a/` when porting JNI bindings, to confirm exact exported symbol names before writing a Java/Kotlin wrapper.
- `hvNote/` — decompiled reference material from the original vendor app (smali, extracted `.so`s, APK) used as the porting source. Read-only reference, not part of the build (excluded via `.gitignore`).

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
- `StrokeRenderer` + `HWPenEngine` (the vendor's native pen rasterizer) are legacy/Phase-0 code paths, superseded by `InkRenderer`'s own Skia-based rendering. `NativeSelfTest` keeps a smoke-test around for the native libs in case a future editing feature (e.g. eraser trace mode) still needs them — don't assume they're dead code without checking current usage.

### The `ink` package (`com.betterhv.note.ink`) is framework-free by design

Everything in this package (`InkPoint`, `Stroke`, `PenStyle`, `PressureCurve`, `StrokeGeometry`, `StrokeBuilder`, `StrokeSimplifier`, `StrokeSmoother`, `InputFilter`, `Bounds`) has zero Android dependencies — colors are packed ARGB ints, geometry is flat `FloatArray`s, no `android.graphics.*` types leak in. `InkRenderer` (also in this package) is the sole exception and is explicitly called out in its own doc comment as "the one place in the ink pipeline allowed to know about android.graphics". Keep new ink/geometry code in this package framework-free — that's what makes it JVM-testable without Robolectric and is a deliberate architectural boundary from `inkengine-plan.md` §93 (Core must not depend on UI framework, e-ink SDK, or any specific rendering backend).

### JNI / native library bindings

Three vendor native libraries are wrapped by hand-ported JNI classes, each with a hard constraint on **exact package + class name**, because the `.so` either does dynamic `JNI_OnLoad` registration against that descriptor or has statically-linked symbols named `Java_<package>_<Class>_<method>`:

| Wrapper class | Native lib | Purpose |
|---|---|---|
| `hanvon.aebr.dither.HVDitherJNI` | `libhvdither.so` | E-ink grayscale dithering |
| `hanvon.aebr.hvnote.jni.GraphJniUtil` | `libHwGraphUtil.so` | Shape/gesture recognition |
| `hanvon.aebr.penengine.HWPenEngine` | `libhw_PenEngine.so` | Native pen stroke rasterizer (legacy path, see above) |

Before adding or changing a native method signature, verify the exported symbol name against the actual `.so` with `python tools/elfsyms.py <lib.so> [filter]` rather than trusting decompiled smali alone — comments note at least one case (`GraphJniUtil.getVersionFromJni`) where a smali-visible method turned out to be absent from the compiled binary.

## Working conventions specific to this repo

- Comments in this codebase frequently cite `inkengine-plan.md` by section number (e.g. "spec §22") and/or the decompiled vendor source ("hvNote 7.16 smali", "HandView", "PslOp", "MemoView"). When modifying pipeline code, check whether a similar cross-reference should be added/updated — these comments are load-bearing documentation of *why*, not incidental.
- Do not "clean up" or refactor `PenGeometry`'s branch structure or the ROM mode constants in `PenDrawView` — they encode reverse-engineered, per-device-model vendor behavior with no independent spec to verify against; a simplification that looks equivalent can silently break specific hardware/rotation combinations.

### Export system (`com.betterhv.note.export`)

The export functionality (Phase 7) follows the architecture principle: **strokes are source of truth**. Export never reads from rendered bitmaps; it renders fresh from `PageSnapshot`.

**Key components:**
- `ExportTaskRepository` stores reusable single-page, fixed-selection, and all-page tasks in the notebook SQLite database.
- `PageRenderer` uses one Android Canvas path for 2x PNG and vector PDF output, including multiline text, object transforms, and image EXIF orientation.
- `ExportEngine` caches one PDF per `pageId + contentRevision + rendererVersion`; multi-page output re-renders only stale pages and combines the cached pages with MuPDF page grafting.
- `ExportViewModel` owns the active job, progress/cancellation, Downloads destination, and task list state.
- `ExportManagerScreen` is the full-screen, cross-notebook task manager opened from both the toolbar and notebook cards.

**Export formats:**
- **PNG**: Single page only, 2x resolution, ARGB_8888
- **PDF**: Single page, page range, or all pages, vector strokes (scalable)

**File locations:**
- Internal reusable artifacts and page caches live below `files/exports/` and are written atomically.
- User-facing local copies are published through MediaStore to `Downloads/BetterHvNote`.

**NoteLink integration:**
- BLE capability negotiation and push commands extend the existing protocol without renumbering Phone→Note commands.
- NoteLink remains discoverable after pairing, uses the v3 control protocol and encrypted file channel for PDF/PNG (LAN first, Android Wi-Fi Direct fallback), and keeps a persistent inbox.
- Artifact IDs make retry idempotent; the receiver validates declared length and SHA-256 before committing a file.

**Known limitations:**
- Imported PDF annotations are preserved and displayed read-only; editing those source annotations is not supported.
- Imported encrypted PDFs and PDF reflow are not supported.
- Large notebooks (>100 pages) not stress-tested
- Memory: renders one page at a time, recycles bitmaps immediately

**Testing:**
- `ExportRevisionTest` covers revision/order fingerprints, stale-page calculation, and source-invalid state.
- `PageRendererInstrumentedTest` covers actual Android PNG/PDF rendering, page dimensions, and multi-page PDF assembly.
- `BleQueueProtocolTest` covers the backward-compatible export push command/response codec.

When adding a format, extend `ExportFormat`, task validation, `ExportEngine.generate()`, and NoteLink's accepted MIME allowlist together.
