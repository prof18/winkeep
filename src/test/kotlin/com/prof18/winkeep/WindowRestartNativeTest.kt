package com.prof18.winkeep

import com.prof18.winkeep.fixtures.WindowJourneyMain
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowRestartNativeTest {
    @Test
    fun `native window bounds survive a fresh JVM restart`() {
        assertFalse(GraphicsEnvironment.isHeadless(), "Native restart journey requires a live display")

        val appId = "winkeep-native-restart-${UUID.randomUUID()}"
        val windowId = "main-${UUID.randomUUID()}"
        val leafPath = PreferencesWindowStateStore.nodePath(appId, windowId)
        val leaf = Preferences.userRoot().node(leafPath)
        try {
            runChild("writer", appId, windowId)
            runChild("reader", appId, windowId)
        } finally {
            // Remove only this test's identity leaf; shared Preferences ancestors may contain other data.
            leaf.removeNode()
            Preferences.userRoot().flush()
        }
    }

    private fun runChild(
        mode: String,
        appId: String,
        windowId: String,
    ) {
        val output = File.createTempFile("winkeep-window-journey-", ".log")
        val process =
            ProcessBuilder(
                javaExecutable(),
                "-Djava.awt.headless=false",
                "-cp",
                System.getProperty("java.class.path"),
                WindowJourneyMain::class.java.name,
                mode,
                appId,
                windowId,
            ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            val completed = process.waitFor(NATIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val log = output.readText()
            assertTrue(completed, "$mode JVM timed out after $NATIVE_TIMEOUT_SECONDS seconds:\n$log")
            assertEquals(0, process.exitValue(), "$mode JVM failed:\n$log")
            log.lineSequence().filter { it.startsWith("PHASE ") }.forEach(::println)
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
