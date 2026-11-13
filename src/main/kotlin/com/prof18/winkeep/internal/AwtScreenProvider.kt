package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedMonitor
import kotlinx.coroutines.CancellationException
import java.awt.EventQueue
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

internal object AwtScreenProvider {
    /** Enumerates every display; used once per controller for restoration. */
    fun screens(): List<ScreenInfo> {
        check(EventQueue.isDispatchThread()) { "Screen enumeration must run on EDT" }
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val defaultId = environment.defaultScreenDevice.iDstring
        return environment.screenDevices.map { device ->
            info(device.defaultConfiguration, isDefault = device.iDstring == defaultId)
        }
    }

    /** Looks up only the given configuration's device; used on every observed window event. */
    fun screenOf(configuration: GraphicsConfiguration?): ScreenInfo? {
        check(EventQueue.isDispatchThread()) { "Screen lookup must run on EDT" }
        return configuration?.let { info(it, isDefault = false) }
    }

    private fun info(
        configuration: GraphicsConfiguration,
        isDefault: Boolean,
    ): ScreenInfo {
        val rectangle = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        return ScreenInfo(
            configuration.device.iDstring,
            Rect(
                (rectangle.x.toLong() + insets.left).toDouble(),
                (rectangle.y.toLong() + insets.top).toDouble(),
                (rectangle.width.toLong() - insets.left - insets.right).toDouble(),
                (rectangle.height.toLong() - insets.top - insets.bottom).toDouble(),
            ),
            isDefault,
        )
    }
}

/**
 * Resolves the monitor of each native observation. A failing lookup is reported at most once
 * per instance (one instance per attachment) and yields no monitor.
 */
internal class MonitorLookup(
    private val source: () -> ScreenInfo?,
    private val report: (Exception) -> Unit,
) {
    private var reported = false

    fun current(): SavedMonitor? {
        val screen =
            try {
                source()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!reported) {
                    reported = true
                    report(error)
                }
                null
            } ?: return null
        return screen.workArea.toScreenAreaOrNull()?.let { SavedMonitor(screen.id, it) }
    }
}
