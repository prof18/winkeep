package com.prof18.winkeep

/** Storage for one window identity. Implementations propagate errors and cancellation. */
public interface WindowStateStore {
    public suspend fun load(): WindowSnapshot?

    public suspend fun save(snapshot: WindowSnapshot)

    /** Awaits durability of previous saves in the backing store. */
    public suspend fun flush()
}
