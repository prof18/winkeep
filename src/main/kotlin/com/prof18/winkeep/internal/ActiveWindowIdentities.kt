package com.prof18.winkeep.internal

internal object ActiveWindowIdentities {
    private val identities = mutableSetOf<Pair<String, String>>()

    @Synchronized
    fun acquire(
        appId: String,
        windowId: String,
    ): () -> Unit {
        val identity = appId to windowId
        check(identities.add(identity)) { "A live WinKeep controller already owns $appId / $windowId" }
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    identities.remove(identity)
                    released = true
                }
            }
        }
    }
}
