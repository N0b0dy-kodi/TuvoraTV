package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.mediaserver.ServerMediaRoutePolicy.Route
import com.nuvio.tv.data.mediaserver.ServerMediaRoutePolicy.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerMediaRoutePolicyTest {
    private val pinned = setOf("nas.lan:8920", "192.168.1.10:443")
    private fun route(url: String, surface: Surface) =
        ServerMediaRoutePolicy.route(url, surface) { it in pinned }

    @Test
    fun authorityIsHostAndPortWithHttpsDefault() {
        assertEquals("nas.lan:8920", ServerMediaRoutePolicy.authorityOf("https://NAS.lan:8920/Videos/1/stream?x=1"))
        assertEquals("192.168.1.10:443", ServerMediaRoutePolicy.authorityOf("https://192.168.1.10/Items/1/Images/Primary"))
    }

    @Test
    fun onlyHttpsHasAnAuthority() {
        assertNull(ServerMediaRoutePolicy.authorityOf("http://nas.lan:8096/x"))
        assertNull(ServerMediaRoutePolicy.authorityOf("not a url"))
        assertNull(ServerMediaRoutePolicy.authorityOf("file:///sdcard/a.mp4"))
    }

    @Test
    fun aPinnedHostUsesThePinnedClientForImagesAndExoPlayer() {
        assertEquals(Route.PinnedClient("nas.lan:8920"), route("https://nas.lan:8920/a.jpg", Surface.IMAGE))
        assertEquals(Route.PinnedClient("nas.lan:8920"), route("https://nas.lan:8920/v.mp4", Surface.EXOPLAYER))
    }

    @Test
    fun aPinnedHostGoesThroughTheRelayForMpv() {
        assertEquals(Route.LoopbackRelay("nas.lan:8920"), route("https://nas.lan:8920/v.mp4", Surface.MPV))
    }

    @Test
    fun anUnpinnedHostKeepsNormalValidation() {
        assertEquals("CA-signed servers are untouched", Route.Direct, route("https://media.example.com/v.mp4", Surface.EXOPLAYER))
        assertEquals(Route.Direct, route("https://media.example.com/v.mp4", Surface.MPV))
        assertEquals(Route.Direct, route("https://media.example.com/a.jpg", Surface.IMAGE))
    }

    @Test
    fun theSameHostOnAnotherPortIsNotPinned() {
        assertEquals(Route.Direct, route("https://nas.lan:9000/v.mp4", Surface.EXOPLAYER))
    }

    @Test
    fun plainHttpIptvAndLoopbackAreNeverRouted() {
        assertEquals(Route.Direct, route("http://nas.lan:8920/v.mp4", Surface.MPV))
        assertEquals("IPTV/Xtream stays on its own path", Route.Direct, route("http://iptv.example.com:8080/live/u/p/1.ts", Surface.EXOPLAYER))
        assertEquals("the relay's own URL is never relayed again", Route.Direct, route("http://127.0.0.1:41234/tok/v.mp4", Surface.MPV))
    }
}
