package com.prof18.winkeep.internal

import com.prof18.winkeep.WindowBounds
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

internal object AwtScreenProvider {
    fun screens(): List<ScreenInfo> {
        check(EventQueue.isDispatchThread()) { "Screen enumeration must run on EDT" }
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val defaultId = environment.defaultScreenDevice.iDstring
        return environment.screenDevices.map { device ->
            val configuration = device.defaultConfiguration
            val rectangle = configuration.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            ScreenInfo(
                device.iDstring,
                WindowBounds(
                    (rectangle.x.toLong() + insets.left).toDouble(),
                    (rectangle.y.toLong() + insets.top).toDouble(),
                    (rectangle.width.toLong() - insets.left - insets.right).toDouble(),
                    (rectangle.height.toLong() - insets.top - insets.bottom).toDouble(),
                ),
                device.iDstring == defaultId,
            )
        }
    }
}
