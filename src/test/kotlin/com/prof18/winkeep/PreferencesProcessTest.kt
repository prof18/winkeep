package com.prof18.winkeep

import com.prof18.winkeep.fixtures.PreferencesProcessMain
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreferencesProcessTest {
    @Test
    fun `flushed snapshot is restored by a separate JVM process`() {
        val appId = "winkeep-process-${UUID.randomUUID()}"
        val windowId = "main-${UUID.randomUUID()}"
        val nodePath = PreferencesWindowStateStore.nodePath(appId, windowId)
        val node = Preferences.userRoot().node(nodePath)
        try {
            runChild("writer", appId, windowId)
            runChild("reader", appId, windowId)
        } finally {
            node.removeNode()
            Preferences.userRoot().flush()
        }
    }

    @Test
    fun `corrupt record produces a recoverable typed decode error in child JVM`() {
        val appId = "winkeep-corrupt-${UUID.randomUUID()}"
        val windowId = "main-${UUID.randomUUID()}"
        val nodePath = PreferencesWindowStateStore.nodePath(appId, windowId)
        val node = Preferences.userRoot().node(nodePath)
        try {
            node.put(PreferencesWindowStateStore.RECORD_KEY, "not-json")
            node.flush()
            runChild("corrupt", appId, windowId)
            assertEquals("not-json", node.get(PreferencesWindowStateStore.RECORD_KEY, null))
        } finally {
            node.removeNode()
            Preferences.userRoot().flush()
        }
    }

    private fun runChild(
        mode: String,
        appId: String,
        windowId: String,
    ) {
        val output = File.createTempFile("winkeep-process-", ".log")
        val process =
            ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                PreferencesProcessMain::class.java.name,
                mode,
                appId,
                windowId,
            ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(NATIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Child JVM timed out: ${output.readText()}")
            assertEquals(0, process.exitValue(), "Child JVM failed: ${output.readText()}")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(NATIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            output.delete()
        }
    }

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return File(File(System.getProperty("java.home"), "bin"), executable).absolutePath
    }

    private companion object {
        const val NATIVE_TIMEOUT_SECONDS = 30L
    }
}
