package com.prof18.winkeep

/** Outcome of [WinKeepState.save] and [WinKeepState.saveOnClose]. */
public enum class WindowSaveResult {
    /** The current snapshot was written and the store flushed it. */
    Saved,

    /** No coherent window state was ever observed, so nothing was written. */
    NothingToSave,

    /** A future-schema record is preserved read-only, so nothing was written. */
    ReadOnly,

    /** Saving or flushing failed; the failure was also reported through `onError`. */
    Failed,
}
