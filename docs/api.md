# Public API

Everything lives in the package `com.prof18.winkeep`. The generated Dokka reference ships in the javadoc jar. The [sample](../sample/src/main/kotlin/com/prof18/winkeep/sample/Main.kt) compiles the full sequence: create the state, compose the window when ready, attach the effect, await the close hook.

## Controller and effect

| Symbol | Contract |
| --- | --- |
| `rememberWinKeepState(appId, windowId, defaultSize, minimumSize, store, onError)` | Creates a controller remembered by the two IDs. `defaultSize` defaults to 800 × 600; `minimumSize` defaults to `DpSize.Unspecified`. Blank IDs, IDs longer than 256 Unicode code points and invalid sizes throw `IllegalArgumentException` immediately. |
| `WinKeepState` | Owns one stable `windowState: WindowState` and a read-only `isReady: Boolean`. Compose the caller-owned `Window` only after `isReady` is true. |
| `FrameWindowScope.WinKeepEffect(state)` | Attach once inside that `Window`. A second attachment, or a second live controller with the same app and window IDs, throws `IllegalStateException`. Disposal removes native listeners and jobs; it never deletes storage. |
| `WinKeepState.save(): WindowSaveResult` | Non-terminal save. Captures the current coherent geometry, writes it through the same serial writer as debounced saves (replacing a pending one) and flushes. Observation continues. Use it when a window is hidden instead of closed, or from an application-level quit handler. After `saveOnClose` has started it returns `NothingToSave`; if a close overtakes a queued `save`, the close writes the newer state and `save` returns the close result. |
| `WinKeepState.saveOnClose(): WindowSaveResult` | Terminal close hook. Await it before hiding or removing the `Window` or exiting. It freezes the current geometry, waits for earlier writes, saves the final snapshot and flushes. Repeated calls return the same result. Observation stops for good, also after a failure. |
| `WindowSaveResult` | `Saved`, `NothingToSave` (no coherent state was ever observed, for example the window never showed), `ReadOnly` (future-schema record kept untouched) or `Failed` (save or flush failed; also reported through `onError`). |

For the same IDs, `store`, `defaultSize` and `minimumSize` are captured initialization inputs: changing them later does not replace or reload the controller. Remember a custom store at the call site. Changing an ID creates a new controller; switching backends requires closing the window and composing a fresh controller. `onError` is a live callback updated on recomposition.

`minimumSize`, when specified, must have both dimensions positive and finite. Restored and default sizes are raised to at least that size and then clamped to the usable area, so a screen smaller than the minimum wins. WinKeep does not set the AWT minimum size on the caller-owned window; set `window.minimumSize` as well.

The store is read on IO. Screen enumeration and native sampling run on the EDT. State is applied before `isReady` becomes true, so creating the `Window` afterwards avoids a default-size flash and any write-before-load race. Load or screen failures open validated defaults and report details.

## Store protocol

`WindowStateStore` has four suspending methods:

```kotlin
suspend fun load(): WindowSnapshot?
suspend fun save(snapshot: WindowSnapshot)
suspend fun flush()
suspend fun clear()
```

Each store owns one identity. `null` means no record. Implementations propagate ordinary exceptions and cancellation; `flush` must await durability of earlier saves, and `clear` removes the record durably. The controller orders writes and runs store operations on IO.

`PreferencesWindowStateStore(appId, windowId)` is the default. Its constructor validates the IDs without touching Preferences; the first suspending call creates and keeps the delegate on IO. `clear()` removes this identity's record key and flushes; it is the supported reset path. [Storage](storage.md) describes identity hashing, the record and ownership.

## Snapshot model

The model types are immutable classes with value `equals`, `hashCode` and `toString` (no `copy` or destructuring). Their constructors validate input, so an invalid snapshot cannot be built: each throws `IllegalArgumentException` naming the bad field.

| Symbol / fields | Meaning |
| --- | --- |
| `WindowSnapshot(normalBounds, placement, monitor)` | One retained state. `normalBounds` and `monitor` form a pair. |
| `WindowBounds(position, width, height)` | Decorated outer frame in classic AWT user-space units. `position` is a `WindowOrigin`, or null to let the platform place the window. `width` and `height` must be finite, positive, survive Float and native Int conversion and round to at least one unit. |
| `WindowOrigin(x, y)` | Concrete outer-frame origin. Coordinates must be finite and fit Float and native Int; they can be negative. |
| `SavedMonitor(id, workArea)` | AWT device `id` and its usable `workArea`, excluding taskbar, dock and menu insets. A null monitor means the lookup was unavailable. |
| `ScreenArea(x, y, width, height)` | Concrete screen rectangle with the same numeric rules as `WindowOrigin` and `WindowBounds`. |
| `SavedPlacement` | `Floating`, `Maximized`, `Fullscreen`; minimized state is never stored. |
| `UnsupportedSchemaException(version)` | A stored record has a newer schema. `version` is the exact positive integer as a string, which may exceed Long range. The controller opens defaults read-only, keeps the raw record, reports once and writes nothing; `save` and `saveOnClose` return `ReadOnly`. |

The schema version is a storage detail of the default store and is not part of `WindowSnapshot`.

Restoration prefers the saved monitor ID, then the monitor with the largest overlap, then the default screen, then the lowest device ID. On a moved monitor the window keeps its offset from the work area origin. The size is clamped and the position is brought fully inside the usable area. With no usable screen, the window opens at the caller size with the platform-chosen position.

Normal bounds are recorded only from showing, visible, non-minimized windows whose native and Compose placements are both `Floating`. A candidate is checked again after the current EDT event batch; placement transitions, hiding, minimizing, detaching and closing cancel it. While maximized or fullscreen, the retained normal bounds and their monitor stay paired. The monitor used by the maximized or fullscreen presentation itself is not stored, so a window maximized on monitor B can reopen on its retained normal monitor A.

Native observations look up only the window's own graphics configuration. A failing lookup is reported once per attachment as `Screens`, and those observations carry no monitor.

On Linux, a window restored as `Floating` gets a one-time correction: if the first resize after it is shown is exactly the X11 decoration-inset correction of the restored size, the restored size is set back and the transient size is not saved. It does nothing on macOS and Windows.

On Linux, when a window returns to `Floating` from maximized or fullscreen, WinKeep holds the normal bounds it had before for 1.5 seconds. During the hold it puts those bounds back on the window whenever they differ, and records no other floating bounds, including bounds the app sets itself. After the hold, the bounds the window manager kept count as usual. It does nothing on macOS and Windows.

## Error reporting

`WindowPersistenceError(operation, cause)` carries the original ordinary exception. `WindowPersistenceOperation` values are:

- `Load`: backing store creation or read failed, a custom store threw, or the record has an unsupported schema.
- `Decode`: the default store's current-version record is malformed or holds invalid geometry.
- `Screens`: monitor lookup failed.
- `Save`: a snapshot write failed.
- `Flush`: durability failed.

Callbacks run on the UI dispatcher. An exception thrown by the callback is logged and does not break cleanup. `CancellationException` propagates and is never reported. After a failed background save, the next changed snapshot is written again; there is no retry loop.
