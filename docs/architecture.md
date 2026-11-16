# Architecture

This page maps the source and follows one window from launch to close. The public contract is in the [API reference](api.md), the record format and flush rules in [Storage and durability](storage.md), and native test coverage in [Platform testing](platform-testing.md).

## Source layout

The library is one Gradle module. Public types sit in [`src/main/kotlin/com/prof18/winkeep`](../src/main/kotlin/com/prof18/winkeep); everything under `internal/` is free to change between releases. The tracked ABI dump in [`api/winkeep.api`](../api/winkeep.api) guards the public surface.

| File | Role |
| --- | --- |
| [`rememberWinKeepState.kt`](../src/main/kotlin/com/prof18/winkeep/rememberWinKeepState.kt) | Validates the inputs, claims the identity, creates the controller and starts its load. |
| [`WinKeepState.kt`](../src/main/kotlin/com/prof18/winkeep/WinKeepState.kt) | The controller. Loads, restores, owns `windowState`, feeds observations to the reducer and offers snapshots for saving. |
| [`WinKeepEffect.kt`](../src/main/kotlin/com/prof18/winkeep/WinKeepEffect.kt) | Attaches native observation to the caller's window and removes it on disposal. |
| [`WindowSnapshot.kt`](../src/main/kotlin/com/prof18/winkeep/WindowSnapshot.kt), [`WindowSaveResult.kt`](../src/main/kotlin/com/prof18/winkeep/WindowSaveResult.kt), [`WindowPersistenceError.kt`](../src/main/kotlin/com/prof18/winkeep/WindowPersistenceError.kt) | The validated model, save outcomes and error reports. |
| [`WindowStateStore.kt`](../src/main/kotlin/com/prof18/winkeep/WindowStateStore.kt), [`PreferencesWindowStateStore.kt`](../src/main/kotlin/com/prof18/winkeep/PreferencesWindowStateStore.kt) | The store protocol and the default Java Preferences store. |
| [`internal/ActiveWindowIdentities.kt`](../src/main/kotlin/com/prof18/winkeep/internal/ActiveWindowIdentities.kt) | Process-wide set of live app and window ID pairs; a duplicate throws. |
| [`internal/RestoreBounds.kt`](../src/main/kotlin/com/prof18/winkeep/internal/RestoreBounds.kt) | Pure restoration math: picks the monitor, applies the minimum size and brings the bounds inside the usable area. |
| [`internal/AwtScreenProvider.kt`](../src/main/kotlin/com/prof18/winkeep/internal/AwtScreenProvider.kt), [`internal/ScreenInfo.kt`](../src/main/kotlin/com/prof18/winkeep/internal/ScreenInfo.kt) | Reads AWT screens and work areas, and resolves the monitor of each observation. |
| [`internal/NativeWindowObserver.kt`](../src/main/kotlin/com/prof18/winkeep/internal/NativeWindowObserver.kt) | AWT component, window-state and graphics-configuration listeners plus a Compose placement flow; samples the window on every change. On Linux it also puts the held normal bounds back after a presentation exit. |
| [`internal/WindowSnapshotReducer.kt`](../src/main/kotlin/com/prof18/winkeep/internal/WindowSnapshotReducer.kt) | Turns observations into the retained snapshot: which bounds count as normal, generations that cancel stale candidates, and the Linux hold. |
| [`internal/LinuxInsetCorrection.kt`](../src/main/kotlin/com/prof18/winkeep/internal/LinuxInsetCorrection.kt) | One-shot correction of the X11 decoration-inset growth after a floating restore. |
| [`internal/SaveCoordinator.kt`](../src/main/kotlin/com/prof18/winkeep/internal/SaveCoordinator.kt) | The 250 ms debounce, the serial writer, explicit `save()` and the terminal `saveOnClose()`. |
| [`internal/SnapshotCodec.kt`](../src/main/kotlin/com/prof18/winkeep/internal/SnapshotCodec.kt), [`internal/SnapshotValidation.kt`](../src/main/kotlin/com/prof18/winkeep/internal/SnapshotValidation.kt) | The schema 1 JSON record and the numeric rules shared by the model and the codec. |

## From launch to close

1. `rememberWinKeepState` remembers the controller by its two IDs, claims the identity in `ActiveWindowIdentities` while the controller is in composition, and starts `WinKeepState.initialize()`.
2. `initialize()` loads the record on IO. A load or decode failure is reported and treated as no record; a future schema also makes the controller read-only.
3. On the UI dispatcher it reads the screens, runs `restoreBounds`, writes size, position and placement into `windowState`, clears minimized and sets `isReady`. The caller composes its `Window` only now, so nothing is written before the load finishes.
4. `WinKeepEffect` creates a `NativeWindowObserver` on the EDT. Each AWT event or Compose placement change becomes a `NativeObservation` for the reducer.
5. The reducer keeps the last coherent floating bounds and their monitor. A floating candidate is committed only after the current EDT batch, and only if no transition bumped the generation in between.
6. Every changed snapshot goes to `SaveCoordinator`, which debounces it and hands it to the serial writer. `save()` and `saveOnClose()` bypass the debounce, write and flush.
7. Disposal of the effect removes the listeners and the Linux inset correction. When the controller leaves composition, its scope is cancelled and the identity is released. Neither deletes storage.

## Dependencies

The published artifact exposes Compose runtime and UI and kotlinx-coroutines core as API dependencies. Internally it uses kotlinx-coroutines-swing, kotlinx-serialization JSON for the codec, and Multiplatform Settings for the Preferences adapter. Versions live in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml). The [sample](../sample) adds `compose.desktop.currentOs`; the library itself ships no OS-specific Skiko artifact.
