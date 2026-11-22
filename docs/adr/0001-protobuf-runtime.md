# ADR 0001 — protobuf runtime and code generation

- Status: accepted (2026-10-31)
- Supersedes: none
- Related: `.dsh/specs/notelink-protobuf-v4/design.md` §4.1, task 2

## Context

The v4 protocol needs one message contract shared by the Android tablet app, the Android
NoteLink app and the Windows desktop app. The candidate runtimes were Google's
`protobuf-kotlin-lite` (with the protobuf Gradle plugin) and Square Wire 5 (pure Kotlin,
no reflection).

The repository builds with Gradle 9.7.1, Kotlin 2.4.10 and AGP 9.3.2, and `transfer-core`
is a plain Kotlin/JVM module consumed by all three targets. Android release builds shrink
with R8. The protocol schema must stay independent of the runtime choice.

## Decision

Use **Google `protobuf-javalite` 4.36.2** with the **`com.google.protobuf` Gradle plugin
0.10.0**, generating **Java lite** classes (`option optimize_for = LITE_RUNTIME`,
`java_multiple_files = true`).

- `transfer-core` applies `id("com.google.protobuf")`, pins `com.google.protobuf:protoc:4.36.2`,
  and configures the existing `java` builtin with `option("lite")`.
- The runtime is exposed as `api("com.google.protobuf:protobuf-javalite:4.36.2")` so the
  Android and desktop consumers link against exactly one version.
- Generated Java sources are an implementation detail; Kotlin call sites use the usual
  `newBuilder()`/`parseFrom()` API, so migrating to Kotlin DSL or Wire later only touches
  `transfer-core` and its build file.

## Why not the Kotlin generator

`protobuf-kotlin-lite` needs `protoc-gen-kotlin` in addition to `protoc`. Generics-heavy
Kotlin DSL output also increases method count and R8 work for no protocol benefit. The Java
lite API is stable, has no reflection, preserves unknown fields, and keeps the build
independent of a second code-generator artifact. If the Kotlin DSL becomes desirable, it can
be added per-module without changing the `.proto` files or the wire format.

## Why not Wire 5

Wire is attractive (pure Kotlin, streaming, small runtime), but:

- its edition/proto3 feature coverage and unknown-field round-trip behaviour need a separate
  compatibility harness;
- the repository already validates Java toolchains on all three targets;
- a later switch only affects `transfer-core`, because the schema and the record layer do not
  depend on generated type identity.

Wire remains the fallback if the Java lite runtime causes a measurable APK-size or R8 issue.

## Consequences

- Positive: one codegen path for JVM, Android and Windows; no reflection; unknown fields are
  preserved by `GeneratedMessageLite`; the schema is runtime-neutral.
- Negative: generated sources are verbose; Kotlin call sites are less idiomatic; `protoc`
  adds a build-time download (pinned, resolved from Maven Central).
- Guard: `SchemaCompatibilityTest` covers round-trip, oneof, unknown fields and enum zero;
  `RecordCodecTest` covers framing independent of the generated API.
