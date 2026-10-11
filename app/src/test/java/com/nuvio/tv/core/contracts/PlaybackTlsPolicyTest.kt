package com.nuvio.tv.core.contracts

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTlsPolicyTest {
    private val panels = setOf("panel.example.com", "stream.cdn-panel.net")
    private val servers = setOf("nas.lan", "panel.example.com") // the second is ALSO a panel host

    private fun classify(host: String?) = PlaybackTlsPolicy.classify(host, { it in panels }, { it in servers })

    @Test
    fun anIptvPanelHostMayFallBack() {
        assertEquals(PlaybackTlsClass.PermissivePanel, classify("stream.cdn-panel.net"))
        assertEquals("case and trailing dot are normalised", PlaybackTlsClass.PermissivePanel, classify("Stream.CDN-Panel.net."))
    }

    @Test
    fun aMediaServerHostIsAlwaysStrict() {
        assertEquals(PlaybackTlsClass.Strict, classify("nas.lan"))
        assertEquals("media server wins over panel", PlaybackTlsClass.Strict, classify("panel.example.com"))
    }

    @Test
    fun addonCdnAndUnknownHostsAreStrict() {
        assertEquals("add-on host", PlaybackTlsClass.Strict, classify("torrentio.strem.fun"))
        assertEquals("cdn", PlaybackTlsClass.Strict, classify("cdn.debrid.example"))
        assertEquals(PlaybackTlsClass.Strict, classify(null))
        assertEquals(PlaybackTlsClass.Strict, classify(""))
    }

    @Test
    fun theRegistryIsIdempotentPerName() {
        PlaybackTrustHosts.registerPanelHostPredicate("t", { it == "a.example" })
        PlaybackTrustHosts.registerPanelHostPredicate("t", { it == "b.example" })

        assertEquals(PlaybackTlsClass.Strict, PlaybackTrustHosts.classify("a.example"))
        assertEquals(PlaybackTlsClass.PermissivePanel, PlaybackTrustHosts.classify("b.example"))
    }
}
