package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideFocusRestorePolicyTest {
    @Test fun `after zapping the guide highlights the channel now playing, not the one entered from`() {
        assertEquals("nature", GuideFocusRestorePolicy.target(playing = "nature", origin = "news", visibleIds = listOf("news", "sports", "nature")))
    }

    @Test fun `with no zap the playing channel is the entry row anyway`() {
        assertEquals("news", GuideFocusRestorePolicy.target(playing = "news", origin = "news", visibleIds = listOf("sports", "news")))
    }

    @Test fun `a playing channel that is not in this list falls back to the entry row`() {
        // e.g. All favorites mixes playlists, or a catalog update removed it.
        assertEquals("news", GuideFocusRestorePolicy.target(playing = "elsewhere", origin = "news", visibleIds = listOf("sports", "news")))
        assertEquals("news", GuideFocusRestorePolicy.target(playing = null, origin = "news", visibleIds = listOf("sports", "news")))
    }

    @Test fun `with neither playing nor entry row visible it falls back to the first row`() {
        assertEquals("nature", GuideFocusRestorePolicy.target(playing = "gone", origin = "also-gone", visibleIds = listOf("nature", "sports")))
        assertNull(GuideFocusRestorePolicy.target(playing = "gone", origin = "also-gone", visibleIds = emptyList()))
    }

    @Test fun `reordered rows do not matter because identities are used`() {
        assertEquals("sports", GuideFocusRestorePolicy.target(playing = "sports", origin = "news", visibleIds = listOf("sports", "news")))
    }
}
