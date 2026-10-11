package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.mediaserver.RelayRedirectPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRedirectPolicyTest {
    private val authority = "nas.local:8920"
    private fun decide(location: String?, status: Int = 302) = RelayRedirectPolicy.decide(status, location, authority)

    @Test
    fun `a redirect to a public https host is passed to the player`() {
        assertEquals("debrid cdn", Decision.PassThrough("https://cdn.debrid.example/dl/abc?t=1"), decide("https://cdn.debrid.example/dl/abc?t=1"))
        assertEquals("ip-looking label of a real domain", Decision.PassThrough("https://203.0.113.9.example.com/x"), decide("https://203.0.113.9.example.com/x"))
        assertEquals("public ipv4 literal", Decision.PassThrough("https://93.184.216.34/x"), decide("https://93.184.216.34/x"))
        assertEquals("public ipv6 literal", Decision.PassThrough("https://[2606:4700::1111]/x"), decide("https://[2606:4700::1111]/x"))
        listOf(301, 302, 303, 307, 308).forEach { assertEquals("status $it", Decision.PassThrough("https://cdn.example.com/x"), decide("https://cdn.example.com/x", it)) }
    }

    @Test
    fun `a redirect back to the pinned server is served through the relay`() {
        assertEquals("absolute", Decision.ViaRelay("/Videos/1/stream?x=1"), decide("https://nas.local:8920/Videos/1/stream?x=1"))
        assertEquals("root relative", Decision.ViaRelay("/a/b"), decide("/a/b"))
        assertEquals("case-insensitive", Decision.ViaRelay("/a"), decide("HTTPS://NAS.LOCAL:8920/a"))
    }

    @Test
    fun `loopback private and link-local targets are refused`() {
        listOf(
            "https://localhost/x", "https://foo.localhost/x", "https://127.0.0.1/x", "https://127.1/x", "https://0x7f.0.0.1/x",
            "https://2130706433/x", "https://017700000001/x", "https://0.0.0.0/x", "https://10.0.0.5/x", "https://172.16.0.1/x",
            "https://172.31.255.255/x", "https://192.168.1.1/x", "https://169.254.169.254/x", "https://100.64.0.1/x",
            "https://224.0.0.1/x", "https://255.255.255.255/x", "https://[::1]/x", "https://[::]/x", "https://[::ffff:127.0.0.1]/x",
            "https://[::ffff:7f00:1]/x", "https://[fd00::1]/x", "https://[fe80::1]/x", "https://[2002:7f00:1::]/x", "https://[64:ff9b::7f00:1]/x",
        ).forEach { assertEquals("refused: $it", Decision.Refuse, decide(it)) }
    }

    @Test
    fun `names that resolve to private space are refused`() {
        listOf(
            "https://nas/x", "https://printer.local/x", "https://router.lan/x", "https://box.home.arpa/x", "https://svc.internal/x",
            "https://127.0.0.1.nip.io/x", "https://10-0-0-1.sslip.io/x", "https://app.192.168.1.5.nip.io/x", "https://anything.localtest.me/x",
        ).forEach { assertEquals("refused: $it", Decision.Refuse, decide(it)) }
        assertEquals("a public embedded address is not private", Decision.PassThrough("https://8.8.8.8.nip.io/x"), decide("https://8.8.8.8.nip.io/x"))
    }

    @Test
    fun `downgrades and malformed locations are refused`() {
        listOf(
            "http://cdn.example.com/x", "//cdn.example.com/x", "ftp://cdn.example.com/x", "https://user:pw@cdn.example.com/x",
            "https://cdn.example.com/x y", "javascript:alert(1)", "https:///x", "", "not a url",
        ).forEach { assertEquals("refused: $it", Decision.Refuse, decide(it)) }
        assertEquals("no location", Decision.Refuse, decide(null))
        assertEquals("not a redirect", Decision.Refuse, decide("https://cdn.example.com/x", status = 200))
        assertEquals("not modified is not a redirect", Decision.Refuse, decide("https://cdn.example.com/x", status = 304))
    }

    @Test
    fun `host classification helper`() {
        assertTrue("public name", RelayRedirectPolicy.isPublicHost("cdn.example.com"))
        assertFalse("empty", RelayRedirectPolicy.isPublicHost(""))
        assertFalse("zone id", RelayRedirectPolicy.isPublicHost("fe80::1%eth0"))
        assertTrue("statuses", RelayRedirectPolicy.isRedirect(307) && !RelayRedirectPolicy.isRedirect(300) && !RelayRedirectPolicy.isRedirect(304))
    }
}
