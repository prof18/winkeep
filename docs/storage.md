# Storage and durability

The default store, `PreferencesWindowStateStore`, wraps Multiplatform Settings' Preferences adapter internally and keeps the underlying Java Preferences node for flushing. It stores one compact JSON value under the key `window-state-v1` in the node:

```text
com/prof18/winkeep/v1/<SHA-256 of UTF-8 appId, hex>/<SHA-256 of UTF-8 windowId, hex>
```

Both IDs must be non-blank and at most 256 Unicode code points. Hashing avoids slash injection and Preferences node-name limits; the record also stores the original IDs and checks them on load. The store only touches its own key in its own node. Where Preferences keeps that node on disk is up to the platform implementation.

## Resetting

Because the node path is hashed, users cannot find it by hand. The supported reset path is:

```kotlin
PreferencesWindowStateStore(appId, windowId).clear()
```

`clear()` removes only this identity's record key and flushes. Call it while no controller for that identity is live, before it is created or after its `saveOnClose()` has finished; otherwise a later save writes the record again. Custom stores implement `clear()` for their own record. Do not delete Preferences parents or nodes of other identities.

## Record format (schema 1)

The fields, in order, are `schemaVersion`, `appId`, `windowId`, `normalBounds`, `placement`, `monitor`.

- `normalBounds` has nullable `x` and `y` (both null or both numbers) and numeric `width` and `height`.
- `monitor` is null or an object with a string `id` and a `workArea` with numeric `x`, `y`, `width` and `height`.
- `placement` is `Floating`, `Maximized` or `Fullscreen`. An unknown placement string reads as `Floating`; unknown object fields are ignored.

Geometry must follow the model rules in the [API reference](api.md): finite numbers that fit Float and native Int, and dimensions that round to at least one unit. A record that breaks them, or has the wrong types or a half-set position, is a `Decode` error and the window opens with defaults. A malformed record is replaced by the next valid save.

The schema version is read first. Only an unquoted positive integer is accepted; fractional, exponent, negative, zero and quoted values are malformed. Any larger integer, including one beyond Long range, is a future schema: the controller opens defaults, reports `Load` once with `UnsupportedSchemaException`, keeps the raw value and writes nothing for its lifetime. The schema version is owned by this store and is not part of the public `WindowSnapshot`.

`save` rejects a record longer than `Preferences.MAX_VALUE_LENGTH` characters (a Kotlin String length, not UTF-8 bytes) before overwriting the old value.

## Durability

Changes are debounced for 250 ms. The timer hands an immutable snapshot to a serial writer; cancelling the timer never cancels a write in progress. Debounced writes are not flushed by themselves.

- `save()` captures the current geometry on the EDT, replaces any pending debounced write, waits for earlier writes, then saves and flushes. The window keeps being observed.
- `saveOnClose()` freezes the final geometry on the EDT, waits for earlier writes, saves the final snapshot and flushes the same Preferences node. Await it while the window is still attached and showing.

Disposing the composition alone does not flush, and a force-killed process can lose unflushed changes.

Custom stores implement [the same protocol](api.md#store-protocol) and must make `flush` and `clear` durable for their backend.
