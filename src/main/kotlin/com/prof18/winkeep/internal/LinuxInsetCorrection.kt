package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedPlacement
import java.awt.Insets
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/*
 * On X11, an AWT frame can receive corrected decoration insets after it is shown. The toolkit
 * then grows the outer frame by the inset delta, which a persistence library would record as a
 * user resize, so the window drifts on every launch. This puts the restored size back once.
 */

internal fun isLinuxDesktop(osName: String? = System.getProperty("os.name")): Boolean =
    osName.orEmpty().startsWith("Linux", ignoreCase = true)

internal fun shouldCorrectInsets(
    isLinux: Boolean,
    restoredPlacement: SavedPlacement,
): Boolean = isLinux && restoredPlacement == SavedPlacement.Floating

internal enum class InsetDecision {
    /** Keep listening. */
    Wait,

    /** Set the restored size back and stop listening. */
    Restore,

    /** Stop listening without changing the window. */
    Finish,
}

/** Pure decision logic for the first post-show size change of a restored window. */
internal class InsetCorrectionDecider(
    private val restoredWidth: Int,
    private val restoredHeight: Int,
) {
    private var opened = false
    private var packedInsets: Insets? = null

    fun onResized(
        width: Int,
        height: Int,
        insets: Insets,
    ): InsetDecision {
        if (!opened && packedInsets == null) packedInsets = Insets(insets.top, insets.left, insets.bottom, insets.right)
        if (width == restoredWidth && height == restoredHeight) return InsetDecision.Wait
        val before = packedInsets
        val correction =
            before != null &&
                before != insets &&
                width == restoredWidth + insets.left + insets.right - before.left - before.right &&
                height == restoredHeight + insets.top + insets.bottom - before.top - before.bottom
        return when {
            correction -> InsetDecision.Restore
            opened -> InsetDecision.Finish
            else -> InsetDecision.Wait
        }
    }

    /** Called when the window opens, or at attach time when it is already showing. */
    fun onOpened(
        width: Int,
        height: Int,
        insets: Insets,
    ): InsetDecision {
        opened = true
        if (packedInsets == null) packedInsets = Insets(insets.top, insets.left, insets.bottom, insets.right)
        return if (width != restoredWidth || height != restoredHeight) InsetDecision.Restore else InsetDecision.Wait
    }
}

/**
 * Attaches the one-shot correction to [window] and returns a detach function. It is inert, and
 * touches no window listener, unless [shouldCorrectInsets] holds. [canRestore] is checked again
 * before resizing so a window the user already maximized is left alone.
 */
internal fun attachLinuxInsetCorrection(
    window: Window,
    restoredWidth: Int,
    restoredHeight: Int,
    restoredPlacement: SavedPlacement,
    canRestore: () -> Boolean,
    isLinux: Boolean = isLinuxDesktop(),
): () -> Unit {
    if (!shouldCorrectInsets(isLinux, restoredPlacement)) return {}
    val decider = InsetCorrectionDecider(restoredWidth, restoredHeight)
    var active = true
    lateinit var finish: (Boolean) -> Unit
    val resized =
        object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                apply(decider.onResized(window.width, window.height, window.insets), finish)
            }
        }
    val lifecycle =
        object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) {
                apply(decider.onOpened(window.width, window.height, window.insets), finish)
            }

            override fun windowClosed(event: WindowEvent) = finish(false)
        }
    finish = { restore ->
        if (active) {
            active = false
            window.removeWindowListener(lifecycle)
            window.removeComponentListener(resized)
            // Restoring before the observer's deferred check discards the transient size candidate.
            if (restore && canRestore()) window.setSize(restoredWidth, restoredHeight)
        }
    }
    window.addWindowListener(lifecycle)
    window.addComponentListener(resized)
    if (window.isShowing) apply(decider.onOpened(window.width, window.height, window.insets), finish)
    return { finish(false) }
}

private fun apply(
    decision: InsetDecision,
    finish: (Boolean) -> Unit,
) {
    when (decision) {
        InsetDecision.Wait -> Unit
        InsetDecision.Restore -> finish(true)
        InsetDecision.Finish -> finish(false)
    }
}
