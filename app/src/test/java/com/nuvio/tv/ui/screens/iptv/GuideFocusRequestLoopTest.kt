package com.nuvio.tv.ui.screens.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving fullscreen asks a channel row for focus. On a slow stick the row is not ready within three
 * frames, the old three-ask loop gave up, and focus fell to the group pill. The loop now keeps asking
 * until focus lands.
 */
class GuideFocusRequestLoopTest {
    private fun run(maxFrames: Int = 100, minimumAsks: Int = 3, request: () -> Boolean): Pair<Boolean, Int> = runBlocking {
        var asks = 0
        val landed = GuideFocusRequestLoop.requestUntilLanded(maxFrames, minimumAsks, awaitFrame = {}) { asks++; request() }
        landed to asks
    }

    @Test fun `a row that is ready at once is still asked the minimum number of times`() {
        val (landed, asks) = run { true }
        assertTrue(landed); assertEquals(3, asks)
    }

    @Test fun `a row that only becomes ready after many frames still gets focus`() {
        var calls = 0
        val (landed, asks) = run { ++calls > 20 }
        // Ready on the 21st ask; the minimum of three is long met, so it stops there.
        assertTrue("landed", landed); assertEquals(21, asks)
    }

    @Test fun `a row that never becomes ready stops at the frame budget and reports it`() {
        val (landed, asks) = run(maxFrames = 40) { false }
        assertFalse(landed); assertEquals(40, asks)
    }
}
