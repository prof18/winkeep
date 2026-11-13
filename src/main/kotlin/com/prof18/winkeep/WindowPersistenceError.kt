package com.prof18.winkeep

/** The failed boundary, reported without converting cancellation into a storage error. */
public enum class WindowPersistenceOperation {
    /** Backing-store initialization/read or unsupported schema. */
    Load,

    /** Malformed or invalid current-version record in the default Preferences store. */
    Decode,

    /** Monitor lookup failed; restoration uses validated defaults and observation records no monitor. */
    Screens,

    Save,

    Flush,
}

/** A recoverable storage or screen lookup failure, delivered on the UI dispatcher. */
public class WindowPersistenceError(
    public val operation: WindowPersistenceOperation,
    /** Original ordinary exception; cancellation is propagated separately. */
    public val cause: Exception,
) {
    /** Errors are equal when the operation and the identical cause instance match. */
    override fun equals(other: Any?): Boolean = other is WindowPersistenceError && operation == other.operation && cause === other.cause

    override fun hashCode(): Int = operation.hashCode() * 31 + System.identityHashCode(cause)

    override fun toString(): String = "WindowPersistenceError(operation=$operation, cause=$cause)"
}
