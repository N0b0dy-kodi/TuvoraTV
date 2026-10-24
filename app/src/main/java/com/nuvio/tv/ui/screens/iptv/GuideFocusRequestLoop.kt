package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.withFrameNanos

/**
 * Asks for focus once per frame until it lands. A row that was just scrolled into view (or unlocked
 * when fullscreen closed) is not always attached within a few frames, most of all on a slow stick; a
 * fixed handful of asks then gave up and focus fell to the group pill. Always asks [minimumAsks]
 * times (the lazy list can still move focus right after the first success), and never more than
 * [maxFrames] — a bounded wait, not a loop that can run forever.
 */
internal object GuideFocusRequestLoop {
    suspend fun requestUntilLanded(
        maxFrames: Int = 90,
        minimumAsks: Int = 3,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
        request: () -> Boolean,
    ): Boolean {
        var landed = false
        for (ask in 1..maxFrames) {
            awaitFrame()
            if (request()) landed = true
            if (landed && ask >= minimumAsks) return true
        }
        return landed
    }
}
