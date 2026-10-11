package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.mediaserver.ServerRelayRequestPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRelayRequestPolicyTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private fun decide(
        method: String = "GET",
        target: String = "/$token",
        headers: Map<String, String> = mapOf("host" to "127.0.0.1:4000")
    ) = ServerRelayRequestPolicy.decide(method, target, headers, token, 4000)

    @Test
    fun theTokenPathIsServed() {
        assertEquals(Decision.Serve(headOnly = false, range = null), decide())
        assertEquals(Decision.Serve(headOnly = false, range = null), decide(target = "/$token/video.mp4"))
        assertEquals(Decision.Serve(headOnly = true, range = null), decide(method = "HEAD"))
    }

    @Test
    fun aSingleRangeIsForwarded() {
        val h = mapOf("host" to "127.0.0.1:4000", "range" to "bytes=100-")
        assertEquals(Decision.Serve(false, "bytes=100-"), decide(headers = h))
    }

    @Test
    fun aMalformedOrMultiRangeIsNotForwarded() {
        for (bad in listOf("bytes=0-1,5-9", "items=1-2", "bytes=a-b", "bytes=1-2\r\nX: y")) {
            val h = mapOf("host" to "127.0.0.1:4000", "range" to bad)
            assertEquals("range '$bad' dropped", Decision.Serve(false, null), decide(headers = h))
        }
    }

    @Test
    fun aWrongOrMissingTokenIsNotFound() {
        assertEquals(Decision.Reject(404), decide(target = "/wrong"))
        assertEquals(Decision.Reject(404), decide(target = "/"))
        assertEquals(Decision.Reject(404), decide(target = "/${token.dropLast(1)}"))
        assertEquals(Decision.Reject(404), decide(target = "/${token}x"))
        assertEquals("no traversal", Decision.Reject(404), decide(target = "/$token/../x"))
        assertEquals(Decision.Reject(404), decide(target = "/$token/a/b"))
    }

    @Test
    fun onlyGetAndHeadAreAllowed() {
        for (m in listOf("POST", "PUT", "DELETE", "CONNECT", "OPTIONS")) {
            assertEquals(m, Decision.Reject(405), decide(method = m))
        }
    }

    @Test
    fun aForeignHostHeaderIsRefusedAgainstDnsRebinding() {
        assertEquals(Decision.Reject(421), decide(headers = mapOf("host" to "evil.example:4000")))
        assertEquals(Decision.Reject(421), decide(headers = mapOf("host" to "127.0.0.1:4001")))
        assertEquals(Decision.Reject(421), decide(headers = emptyMap()))
        assertEquals(Decision.Serve(false, null), decide(headers = mapOf("host" to "localhost:4000")))
    }

    @Test
    fun browserOriginatedRequestsAreRefused() {
        assertEquals(Decision.Reject(403), decide(headers = mapOf("host" to "127.0.0.1:4000", "origin" to "https://evil.example")))
        assertEquals(Decision.Reject(403), decide(headers = mapOf("host" to "127.0.0.1:4000", "referer" to "https://evil.example/")))
    }

    @Test
    fun tokensAreLongAndUnique() {
        val a = ServerRelayRequestPolicy.newToken()
        val b = ServerRelayRequestPolicy.newToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b)
    }

    @Test
    fun onlyPlayableUpstreamStatusesPassThrough() {
        assertEquals(200, ServerRelayRequestPolicy.relayStatus(200))
        assertEquals(206, ServerRelayRequestPolicy.relayStatus(206))
        assertEquals(416, ServerRelayRequestPolicy.relayStatus(416))
        assertEquals(404, ServerRelayRequestPolicy.relayStatus(404))
        assertEquals(401, ServerRelayRequestPolicy.relayStatus(401))
        assertEquals("redirects are never followed or exposed", 502, ServerRelayRequestPolicy.relayStatus(302))
        assertEquals(502, ServerRelayRequestPolicy.relayStatus(500))
    }

    @Test
    fun onlyMediaHeadersAreReturned() {
        for (ok in listOf("Content-Type", "content-length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
            assertTrue(ok, ServerRelayRequestPolicy.isResponseHeaderAllowed(ok))
        }
        for (no in listOf("Set-Cookie", "Location", "Authorization", "X-Emby-Token", "Server", "Transfer-Encoding")) {
            assertTrue(no, !ServerRelayRequestPolicy.isResponseHeaderAllowed(no))
        }
    }
}
