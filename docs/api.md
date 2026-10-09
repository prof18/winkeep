# Public API

The package is `com.prof18.winkeep`. The generated Dokka reference ships in the javadoc archive. [The sample](../sample/src/main/kotlin/com/prof18/winkeep/sample/Main.kt) compiles the complete loading/window/effect/close sequence.

## Controller and effect

| Symbol | Contract |
| --- | --- |
| `rememberWinKeepState(appId, windowId, defaultSize, store, onError)` | Creates a controller remembered by the two IDs. Defaults are 800 × 600 classic units. Blank IDs, more than 256 Unicode code points, and invalid caller dimensions fail immediately. |
| `WinKeepState` | Owns one stable `windowState: WindowState` and read-only `isReady: Boolean`. Compose the caller-owned Window only after readiness. |
| `FrameWindowScope.WinKeepEffect(state)` | Attach once inside that Window. Duplicate controller attachments or duplicate live app/window identities fail clearly. Effect disposal removes native listeners/jobs; disposal of the controller's composition releases the identity. Neither deletes storage. |
| `WinKeepState.saveOnClose(): Boolean` | Terminal close hook. Await it before hiding/removing the Window or exiting the application. It freezes current coherent native bounds, waits for existing writes, saves the final snapshot and flushes. Repeated calls join the same result. Ordinary observation stops permanently, including after failure. |

For the same IDs, `store` and `defaultSize` are captured initialization inputs. Later changes do not replace/reload the controller. Remember a custom store at the call site. Changing an ID creates a new controller; switching a backend requires orderly close/disposal followed by fresh composition. `onError` is a live callback updated on recomposition.

Load runs on IO. Screen enumeration and native tuples run on EDT. State is applied before readiness, so creating the Window after `isReady` avoids a default-size flash and write-before-load race. Recoverable load/screen failures open validated defaults and report details. The caller may still close after a persistence failure.

## Store protocol

`WindowStateStore` has three suspending methods:

```kotlin
suspend fun load(): WindowSnapshot?
suspend fun save(snapshot: WindowSnapshot)
suspend fun flush()
```

Each store owns one identity. Null means absent data. Implementations propagate ordinary exceptions and cancellation; `flush` must await durability of preceding saves. The controller orders writes and wraps custom operations on IO. It does not require Koin or Settings in public signatures.

`PreferencesWindowStateStore(appId, windowId)` is the default. Its constructor validates IDs without touching Preferences; first suspending access initializes and retains the delegate on IO. [Storage details](storage.md) describe identity hashing, the record and ownership.

## Snapshot model

| Symbol / fields | Meaning |
| --- | --- |
| `WindowSnapshot(schemaVersion, normalBounds, placement, monitor)` | v1 immutable state. `schemaVersion` defaults to 1. Bounds and monitor form one retained pair. |
| `WindowBounds(x, y, width, height)` | Decorated outer-frame rectangle in classic AWT user-space units. Both `x`/`y` are concrete or both null. `width`/`height` must be finite, positive and round to at least one representable native unit. Positions can be negative. |
| `SavedMonitor(id, workArea)` | AWT device `id` and concrete usable `workArea`, excluding taskbar/dock/menu insets. Null monitor means lookup was unavailable. |
| `SavedPlacement` | `Floating`, `Maximized`, `Fullscreen`; minimized state is never persisted. |
| `UnsupportedSchemaException(version)` | A future schema whose exact positive integer `version` string can exceed Long range. Controllers recover read-only, preserve the raw record, report once and return false from close without saves/flush writes. |

Stored invalid geometry cannot reach native setters. Restoration prefers saved monitor ID, then largest positive intersection, then the default screen/deterministic ID. Matching a moved monitor preserves relative offset. Dimensions are clamped and position is brought fully inside the usable area. Empty/unavailable screens use caller dimensions with unspecified position.

Normal bounds are accepted only from showing, visible, non-minimized windows whose native and Compose placements both agree on Floating. Deferred candidates are canceled by transitions, hide/minimize, detach and close. In non-floating presentation, normal bounds and their monitor remain paired. v1 does not separately remember the monitor used for maximized/fullscreen presentation; a window moved to B while maximized can reopen on its retained normal monitor A.

## Error reporting

`WindowPersistenceError(operation, cause)` carries the original ordinary exception. `WindowPersistenceOperation` values are:

- `Load`: backing initialization/read or unsupported schema.
- `Decode`: malformed current records or invalid custom snapshots.
- `Screens`: monitor lookup failure.
- `Save`: snapshot write failure.
- `Flush`: durability failure.

Callbacks run on Main. An ordinary callback exception is logged once per invocation without recursion and does not break cleanup. CancellationException propagates and is never reported as corruption. Changed snapshots may retry after an ordinary background-save failure; there is no infinite retry loop. Close returns false on failure and remains terminal.
