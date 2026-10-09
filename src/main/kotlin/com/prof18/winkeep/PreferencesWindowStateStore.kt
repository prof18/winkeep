package com.prof18.winkeep

import com.prof18.winkeep.internal.SnapshotCodec
import com.prof18.winkeep.internal.validateIdentity
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.prefs.Preferences

/**
 * Stores one compact, versioned record in an identity-isolated Preferences node.
 * Construction performs no backing-store work; operations initialize the delegate on IO.
 */
public class PreferencesWindowStateStore internal constructor(
    private val appId: String,
    private val windowId: String,
    private val backingFactory: () -> PreferencesBacking,
) : WindowStateStore {
    private var backing: PreferencesBacking? = null

    init {
        validateIdentity(appId, windowId)
    }

    public constructor(appId: String, windowId: String) : this(appId, windowId, {
        val delegate = Preferences.userRoot().node(nodePath(appId, windowId))
        PreferencesBacking(PreferencesSettings(delegate), delegate::flush)
    })

    internal constructor(appId: String, windowId: String, settings: Settings, flush: () -> Unit) :
        this(appId, windowId, { PreferencesBacking(settings, flush) })

    public override suspend fun load(): WindowSnapshot? =
        withContext(Dispatchers.IO) {
            delegate().settings.getStringOrNull(RECORD_KEY)?.let { SnapshotCodec.decode(it, appId, windowId) }
        }

    public override suspend fun save(snapshot: WindowSnapshot): Unit =
        withContext(Dispatchers.IO) {
            val record = SnapshotCodec.encode(snapshot, appId, windowId)
            require(record.length <= Preferences.MAX_VALUE_LENGTH) { "Window record exceeds Preferences value limit" }
            delegate().settings.putString(RECORD_KEY, record)
        }

    public override suspend fun flush(): Unit = withContext(Dispatchers.IO) { delegate().flush() }

    private fun delegate(): PreferencesBacking =
        synchronized(this) {
            backing ?: backingFactory().also { backing = it }
        }

    internal companion object {
        const val RECORD_KEY = "window-state-v1"

        fun nodePath(
            appId: String,
            windowId: String,
        ): String {
            validateIdentity(appId, windowId)
            return "com/prof18/winkeep/v1/${hash(appId)}/${hash(windowId)}"
        }

        private fun hash(id: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

internal data class PreferencesBacking(
    val settings: Settings,
    val flush: () -> Unit,
)
