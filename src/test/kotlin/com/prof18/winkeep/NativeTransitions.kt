package com.prof18.winkeep

import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Counts window transitions once the platform reports them finished.
 *
 * AWT and Compose report a requested minimize or fullscreen change at once, while macOS is still
 * animating it, and macOS drops an opposite request made before the animation ends. Tests wait on
 * these counters before reversing or closing a transition.
 */
internal class NativeTransitions(
    private val window: Window,
) {
    @Volatile var minimized: Int = 0
        private set

    @Volatile var restored: Int = 0
        private set

    @Volatile var maximized: Int = 0
        private set

    @Volatile var enteredFullscreen: Int = 0
        private set

    @Volatile var exitedFullscreen: Int = 0
        private set

    private val state =
        WindowStateListener { event: WindowEvent ->
            val was = event.oldState and Frame.ICONIFIED != 0
            val now = event.newState and Frame.ICONIFIED != 0
            if (!was && now) minimized++
            if (was && !now) restored++
            val zoomed = Frame.MAXIMIZED_BOTH
            if (event.oldState and zoomed != zoomed && event.newState and zoomed == zoomed) maximized++
        }
    private val fullscreen: Any? = macFullscreenListener()

    /** Only macOS reports fullscreen completion; elsewhere tests fall back to presentation checks. */
    val reportsFullscreen: Boolean get() = fullscreen != null

    /**
     * Whether a maximize has finished since [before] was read from [maximized]. macOS sends the
     * state event while the zoom is still animating, so there the window must also fill the usable
     * screen area. Other platforms report the event once the window is maximized.
     */
    fun maximizedSince(before: Int): Boolean {
        if (maximized <= before) return false
        if (fullscreen == null) return true
        val configuration = window.graphicsConfiguration
        val screen = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        val usable =
            Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom,
            )
        return window.bounds == usable
    }

    /** Whether fullscreen has finished entering since [before] was read from [enteredFullscreen]. */
    fun enteredFullscreenSince(before: Int): Boolean = fullscreen == null || enteredFullscreen > before

    /** Whether fullscreen has finished exiting since [before] was read from [exitedFullscreen]. */
    fun exitedFullscreenSince(before: Int): Boolean = fullscreen == null || exitedFullscreen > before

    fun attach() {
        window.addWindowStateListener(state)
        fullscreen?.let { fullscreenListeners("addFullScreenListenerTo", it) }
    }

    fun detach() {
        window.removeWindowStateListener(state)
        fullscreen?.let { fullscreenListeners("removeFullScreenListenerFrom", it) }
    }

    private fun fullscreenListeners(
        method: String,
        listener: Any,
    ) {
        fullscreenUtilities.getMethod(method, Window::class.java, listenerType).invoke(null, window, listener)
    }

    private fun macFullscreenListener(): Any? {
        if (!System.getProperty("os.name").startsWith("Mac")) return null
        val handler =
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "windowEnteredFullScreen" -> enteredFullscreen++
                    "windowExitedFullScreen" -> exitedFullscreen++
                    "equals" -> return@InvocationHandler proxy === args?.get(0)
                    "hashCode" -> return@InvocationHandler System.identityHashCode(proxy)
                    "toString" -> return@InvocationHandler "NativeTransitions.fullscreen"
                }
                null
            }
        return Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType), handler)
    }

    private companion object {
        val fullscreenUtilities: Class<*> by lazy { Class.forName("com.apple.eawt.FullScreenUtilities") }
        val listenerType: Class<*> by lazy { Class.forName("com.apple.eawt.FullScreenListener") }
    }
}
