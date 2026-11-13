package com.prof18.winkeep

/** Storage for one window identity. Implementations propagate errors and cancellation. */
public interface WindowStateStore {
    public suspend fun load(): WindowSnapshot?

    public suspend fun save(snapshot: WindowSnapshot)

    /** Awaits durability of previous saves in the backing store. */
    public suspend fun flush()

    /**
     * Removes this window's saved record durably. Call it while no controller for the identity
     * is live (before composing it, or after its close completed), or a later save rewrites it.
     */
    public suspend fun clear()
}
