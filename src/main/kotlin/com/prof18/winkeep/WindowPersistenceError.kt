package com.prof18.winkeep

/** The failed boundary, reported without converting cancellation into a storage error. */
public enum class WindowPersistenceOperation { Load, Decode, Screens, Save, Flush }

/** A recoverable storage or screen lookup failure. */
public data class WindowPersistenceError(
    public val operation: WindowPersistenceOperation,
    public val cause: Exception,
)
