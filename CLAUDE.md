# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

BetterHvNote is an Android app that replaces the stock note-taking app on Hanvon e-ink stylus tablets (e.g. N10Pro, running a custom ROM on API 34/Android 14). The device ROM exposes a proprietary low-latency pen overlay service (`android.os.HvPenDrawManager` / `HvPenDrawListener`, obtained via `getSystemService("hvpen")`) and several vendor native libraries for pen rendering, gesture recognition, and e-ink dithering. This codebase is a clean-room Kotlin rewrite of the stock app's pen pipeline, reverse-engineered from the decompiled vendor app (referred to in comments as "hvNote 7.16") and ported bit-exact where the ROM's expected behavior is undocumented (coordinate transforms, mode constants).

`inkengine-plan.md` is the long-term architecture spec (in Chinese) for the full note app — Notebook/Page/Scene, Command pattern with Undo/Redo, spatial index, SQLite+WAL persistence, tile-cache rendering, PDF/PNG export, and later image/audio/AI features. **The actual code implements only an early slice of that plan**: Phase 1 ("Ink Engine") — vector strokes as the source of truth, pressure-mapped variable-width geometry, online smoothing/filtering, RDP simplification, and a Skia-based renderer with dirty-rect repaint. There is no Document Core (Notebook/Page/UUID), no Command/Undo system, no persistence layer, and no spatial index yet — erasing is currently a linear scan over all strokes. When implementing new features, check `inkengine-plan.md` section numbers (referenced in code comments as "spec §N") for the target architecture, but don't assume anything beyond the ink engine already exists.

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

Only the `com.betterhv.note.ink` package has tests, and deliberately so — see the comment in `app/build.gradle.kts`: that package has no Android dependencies, so plain JUnit4 on the JVM covers it fully with no Robolectric/instrumentation needed. Anything touching `android.graphics`, `View`, or the vendor JNI/ROM classes (`PenDrawView`, `StrokeRenderer`, `PenGeometry`, `NativeSelfTest`) is not unit-testable this way and has no test coverage — verify changes to those files on-device.

There is no CI config in this repo; `./gradlew test` and `./gradlew assembleDebug` are the checks to run before considering a change done.

## Architecture

### Module layout

- `:app` — the application module (`com.betterhv.note`), namespace/applicationId `com.betterhv.note`, `minSdk 28`/`targetSdk 34`, `arm64-v8a` only.
- `:framework-stubs` — a `java-library` module providing **compile-only** stubs for the ROM's custom framework classes (`android.os.HvPenDrawManager`, `android.os.HvPenDrawListener`). These exist purely so the app compiles; the real implementations are baked into the device ROM's `framework.jar` and are never packaged into the APK (see `compileOnly(project(":framework-stubs"))` in `app/build.gradle.kts`). Method bodies in the stubs just `throw new RuntimeException("stub")` — they are never actually invoked, only linked against.
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
      |  (OneEuroSmoother, online) -> accumulate
      |  at pen-up: StrokeSimplifier (RamerDouglasPeuckerSimplifier, pressure-aware RDP)
      v
Stroke (immutable, ink/Stroke.kt) — the authoritative vector record
      |  StrokeGeometry.build() computes a variable-width ribbon outline from the
      |  centerline + pressure curve (cached, since it's expensive)
      v
InkRenderer — draws the outline as a filled Path via Android Canvas/Skia
      |  into PenDrawView's own cached Bitmap (the "document" bitmap is a
      |  regenerable cache of the stroke list, never the source of truth)
      v
View.postInvalidate(dirtyRect) — only the touched region is repainted
```

Key invariants to preserve when touching this path (see extensive rationale comments in `PenDrawView.kt`):
- **Strokes are the source of truth; the bitmap is a rebuildable cache** (spec §2.1). Any new editing feature must mutate the `strokes` list and repaint from it, not touch pixels directly.
- The ROM paints its own low-latency overlay ink independently of what this app does; `penDraw.resetData()` must be called after consuming each batch or the ROM's copy lingers on screen alongside the app's own rendering.
- `PenGeometry` is a bit-exact port of decompiled vendor bytecode (coordinate transforms depending on device model / screen origin / rotation). Comments explicitly warn not to "simplify" the branch structure — it mirrors the original smali register-by-register and encodes device quirks that aren't otherwise documented.
- Eraser mode is selected on the ROM side via `setDrawStatus` with an *encoded width* (`eraserWidth + 0x69`), not a mode enum — this is a reverse-engineered vendor quirk, not arbitrary.
- Pressure arrives from the ROM in undocumented raw units; `PenDrawView.PRESSURE_MAX` (4095) is an unverified working assumption flagged for validation against real hardware logs (via the on-screen `EventLog` overlay).
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
