package com.nuvio.tv.ui.screens.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientPlaybackOwnershipTest {
    @Test fun `idle deadline cannot cover a player even when no remote key arrives`() {
        var now = 0L
        val controller = AmbientIdleController { now }
        val lease = controller.blockForPlayback()
        now = 600_000L
        controller.show()
        assertTrue(controller.playbackBlocked.value)
        assertFalse(controller.isShowing.value)
        lease.close()
        assertFalse(controller.playbackBlocked.value)
        assertEquals(now, controller.lastInteractionAt.value)
        controller.show()
        assertTrue(controller.isShowing.value)
    }

    @Test fun `preview to fullscreen overlap and duplicate disposal retain the arriving owner`() {
        val controller = AmbientIdleController { 0L }
        val preview = controller.blockForPlayback()
        val fullscreen = controller.blockForPlayback()
        preview.close()
        preview.close()
        controller.show()
        assertTrue(controller.playbackBlocked.value)
        assertFalse(controller.isShowing.value)
        fullscreen.close()
        assertFalse(controller.playbackBlocked.value)
    }

    @Test fun `starting playback dismisses slides already on screen`() {
        val controller = AmbientIdleController { 0L }
        controller.show()
        assertTrue(controller.isShowing.value)
        controller.blockForPlayback().use {
            assertFalse(controller.isShowing.value)
            controller.show()
            assertFalse(controller.isShowing.value)
        }
    }
}
