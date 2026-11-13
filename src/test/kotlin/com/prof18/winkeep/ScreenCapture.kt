package com.prof18.winkeep

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Color
import java.awt.Robot
import java.awt.Window

/** The colour native test windows paint over their whole content. */
internal val nativeTestMarker = Color(0x28, 0x7D, 0x56)

/**
 * Whether fullscreen is AWT's simulated mode. On Windows Compose enters fullscreen through
 * GraphicsDevice.setFullScreenWindow, which sizes the decorated frame to the screen and keeps its
 * title bar, so the screen edges never show the content and tests check the bounds instead.
 */
internal fun simulatesFullscreen(): Boolean = System.getProperty("os.name").startsWith("Windows")

/**
 * Returns a [Robot] when it can read the pixels of the showing [window], or null when it cannot.
 * Under XWayland every capture comes back black, so tests there check fullscreen by bounds alone.
 */
internal suspend fun screenReader(window: Window): Robot? {
    val robot = Robot()

    fun center(): Color {
        val bounds = window.bounds
        val insets = window.insets
        return robot.getPixelColor(
            bounds.x + insets.left + (bounds.width - insets.left - insets.right) / 2,
            bounds.y + insets.top + (bounds.height - insets.top - insets.bottom) / 2,
        )
    }
    return withTimeoutOrNull(5_000) {
        while (center() != nativeTestMarker) delay(50)
        robot
    }
}
