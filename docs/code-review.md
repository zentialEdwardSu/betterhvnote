# Repository Code Review

## Changes applied

- Added Detekt 1.23.8 with default and all-rules checks, zero issue budget, and
  strict formatting/complexity thresholds in `config/detekt/detekt.yml`.
- Enabled Android Lint as errors for application and library modules, including
  Compose checks for modifier parameters, state usage, and composition safety.
- Grouped Compose state and callbacks into `EditorToolbarState`/
  `EditorToolbarActions`, `SettingsState`/`SettingsActions`, and
  `NotebookManagerState`/`NotebookManagerActions`.
- Reduced export UI parameter lists with explicit input, state, and action
  objects; split the export manager surface, task list, and dialogs into
  focused composables.
- Moved the active desktop implementation and tests into `phone-desktop/src`
  and the MSI customization scripts into `tools/windows-installer`.
- Removed the JVM `PageRendererTest` placeholder; the real renderer coverage is
  kept in `androidTest` where Android Canvas is available.
- Preserved coroutine cancellation in the tablet transfer and insertion flows;
  `CancellationException` is rethrown instead of being converted into a normal
  transfer error.

## Remaining risks

- `AppRoot` still coordinates lifecycle and modal routing; the child Compose
  APIs are now compact, but extracting the root orchestration further should
  be a separate behavior-preserving change.
- Detekt and Android Lint are intentionally fail-fast (`maxIssues: 0`,
  `ignoreFailures: false`, `abortOnError: true`). Existing historical findings
  are captured in module baselines so new violations fail the build; the
  baselines should be reduced as the affected code is touched.

## Verification

The connected build completed successfully with:

```text
./gradlew.bat test detekt lint --no-daemon
```

The old `phone-app/src/desktopMain`, `src/desktopTest`, and `installer`
directories no longer contain executable sources, tests, scripts, or duplicate
icons. Desktop implementation and tests live in `phone-desktop/src`, and MSI
customization scripts live in `tools/windows-installer`.
