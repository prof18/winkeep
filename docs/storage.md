# Storage and durability

The default backend wraps Multiplatform Settings' Preferences adapter internally and retains its actual Java Preferences delegate for flushing. It stores one compact JSON value under `window-state-v1` in:

```text
com/prof18/winkeep/v1/<SHA-256 UTF-8 appId hex>/<SHA-256 UTF-8 windowId hex>
```

Both IDs are nonblank and at most 256 Unicode code points. Hashing avoids slash injection and native Preferences node-name limits. Records preserve the original IDs and verify them on load. The library owns only the selected app/window leaf; it never clears unrelated settings or parents. Physical backing locations belong to the platform Preferences implementation, not to a library-managed filesystem path.

## v1 record

Field order is `schemaVersion`, `appId`, `windowId`, `normalBounds`, `placement`, `monitor`. Normal bounds contain nullable `x`/`y` and numeric `width`/`height`. Monitor is null or an object with string `id` and concrete `workArea` bounds. Placement uses `Floating`, `Maximized` or `Fullscreen`. Unknown placement strings recover as Floating; unknown object fields are ignored. Known fields require their documented types and complete position pairs.

Schema is inspected before version-specific fields. Only an unquoted positive integer token is accepted; fractional, exponent, negative, zero and quoted versions are malformed. Any larger positive integer, including beyond Long range, is a future schema. It enters read-only recovery: open defaults, report once, preserve the raw value and disable all saves/flush writes for that controller. Malformed current data can be replaced after a valid native observation.

Encoding rejects records longer than `Preferences.MAX_VALUE_LENGTH` Kotlin String characters before overwriting the old value. This is a character limit, not UTF-8 byte length. Numbers must also remain finite and representable through Float/native Int conversion.

## Durability and ownership

Changes debounce for 250 ms. A timer submits an immutable snapshot to a serial writer; canceling a timer does not cancel an in-flight save. Close freezes the final native tuple on EDT, waits for the writer, saves the final sequence and flushes the same delegate. It must be awaited while the Window remains attached/showing. Disposal alone is not durability, and forced termination can lose pending work. The contract does not promise force-kill recovery.

Custom stores implement [the same protocol](api.md). New default-store users receive the single-record format.

There is no automatic reset or migration into another backend. Reset is an explicit user action after close/disposal, limited to the identified leaf or custom store's owned record. Do not delete Preferences parents or another app/window identity while troubleshooting.
