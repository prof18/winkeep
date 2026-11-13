package com.prof18.winkeep

import com.prof18.winkeep.internal.InsetCorrectionDecider
import com.prof18.winkeep.internal.InsetDecision
import com.prof18.winkeep.internal.isLinuxDesktop
import com.prof18.winkeep.internal.shouldCorrectInsets
import java.awt.Insets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxInsetCorrectionTest {
    private val packed = Insets(0, 0, 0, 0)
    private val corrected = Insets(37, 1, 1, 1)

    @Test
    fun `operating system gate is active only on Linux`() {
        assertTrue(isLinuxDesktop("Linux"))
        assertTrue(isLinuxDesktop("linux"))
        assertFalse(isLinuxDesktop("Mac OS X"))
        assertFalse(isLinuxDesktop("Windows 11"))
        assertFalse(isLinuxDesktop(null))
    }

    @Test
    fun `correction applies only to a floating restore on Linux`() {
        assertTrue(shouldCorrectInsets(isLinux = true, restoredPlacement = SavedPlacement.Floating))
        assertFalse(shouldCorrectInsets(isLinux = true, restoredPlacement = SavedPlacement.Maximized))
        assertFalse(shouldCorrectInsets(isLinux = true, restoredPlacement = SavedPlacement.Fullscreen))
        assertFalse(shouldCorrectInsets(isLinux = false, restoredPlacement = SavedPlacement.Floating))
    }

    @Test
    fun `post-show resize by exactly the inset delta restores the size`() {
        val decider = InsetCorrectionDecider(800, 600)
        assertEquals(InsetDecision.Wait, decider.onResized(800, 600, packed))
        assertEquals(InsetDecision.Wait, decider.onOpened(800, 600, packed))
        assertEquals(InsetDecision.Restore, decider.onResized(802, 638, corrected))
    }

    @Test
    fun `inset correction before open is detected from the packed insets`() {
        val decider = InsetCorrectionDecider(800, 600)
        assertEquals(InsetDecision.Wait, decider.onResized(800, 600, packed))
        assertEquals(InsetDecision.Restore, decider.onResized(802, 638, corrected))
    }

    @Test
    fun `size already different when the window opens is restored`() {
        assertEquals(InsetDecision.Restore, InsetCorrectionDecider(800, 600).onOpened(802, 638, corrected))
    }

    @Test
    fun `already showing window at attach with the restored size keeps waiting for a correction`() {
        val decider = InsetCorrectionDecider(800, 600)
        assertEquals(InsetDecision.Wait, decider.onOpened(800, 600, corrected))
        // Insets did not change after attach, so a later resize is the user's and ends observation.
        assertEquals(InsetDecision.Finish, decider.onResized(900, 700, corrected))
    }

    @Test
    fun `user resize after open that is not the inset delta stops observation without restoring`() {
        val decider = InsetCorrectionDecider(800, 600)
        decider.onResized(800, 600, packed)
        decider.onOpened(800, 600, packed)
        assertEquals(InsetDecision.Finish, decider.onResized(1000, 700, corrected))
    }

    @Test
    fun `unrelated resize before open keeps waiting`() {
        val decider = InsetCorrectionDecider(800, 600)
        decider.onResized(800, 600, packed)
        assertEquals(InsetDecision.Wait, decider.onResized(810, 600, packed))
    }

    @Test
    fun `inset change with a different size delta is not a correction`() {
        val decider = InsetCorrectionDecider(800, 600)
        decider.onResized(800, 600, packed)
        decider.onOpened(800, 600, packed)
        assertEquals(InsetDecision.Finish, decider.onResized(802, 640, corrected))
    }

    @Test
    fun `packed insets are copied so later mutation of the AWT object cannot fake a correction`() {
        val mutable = Insets(0, 0, 0, 0)
        val decider = InsetCorrectionDecider(800, 600)
        decider.onResized(800, 600, mutable)
        mutable.set(37, 1, 1, 1)
        assertEquals(InsetDecision.Restore, decider.onResized(802, 638, mutable))
    }
}
