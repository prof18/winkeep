package com.prof18.winkeep

/** The failed boundary, reported without converting cancellation into a storage error. */
public enum class WindowPersistenceOperation {
    /** Backing-store initialization/read or unsupported schema. */
    Load,

    /** Malformed current-version record or invalid custom-store snapshot. */
    Decode,

    /** Current monitor enumeration failed; validated defaults remain available. */
    Screens,

    Save,

    Flush,
}

/** A recoverable storage or screen lookup failure, delivered on the UI dispatcher. */
public data class WindowPersistenceError(
    public val operation: WindowPersistenceOperation,
    /** Original ordinary exception; cancellation is propagated separately. */
    public val cause: Exception,
)
