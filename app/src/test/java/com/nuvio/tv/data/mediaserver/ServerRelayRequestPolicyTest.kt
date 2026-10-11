package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.mediaserver.ServerRelayRequestPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRelayRequestPolicyTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val host = mapOf("host" to "127.0.0.1:4000")
    private fun decide(
        method: String = "GET",
        target: String = "/$token/Videos/1/stream.mp4",
        headers: Map<String, String> = host
    ) = ServerRelayRequestPolicy.decide(method, target, headers, token, 4000)

    @Test
    fun theTokenPathMapsToThePathOnThePinnedServer() {
        assertEquals(Decision.Serve(headOnly = false, path = "/Videos/1/stream.mp4"), decide())
        assertEquals(Decision.Serve(false, "/a.m3u8?x=1&y=2"), decide(target = "/$token/a.m3u8?x=1&y=2"))
        assertEquals(Decision.Serve(headOnly = true, path = "/Videos/1/stream.mp4"), decide(method = "HEAD"))
    }

    @Test
    fun aSingleRangeAndConditionalsAreForwarded() {
        val h = host + mapOf("range" to "bytes=100-", "if-none-match" to "\"abc\"", "authorization" to "secret")
        assertEquals(
            Decision.Serve(false, "/Videos/1/stream.mp4", "bytes=100-", mapOf("If-None-Match" to "\"abc\"")),
            decide(headers = h)
        )
    }

    @Test
    fun aMalformedOrMultiRangeIsNotForwarded() {
        for (bad in listOf("bytes=0-1,5-9", "items=1-2", "bytes=a-b")) {
            assertEquals("range '$bad' dropped", Decision.Serve(false, "/Videos/1/stream.mp4"), decide(headers = host + ("range" to bad)))
        }
    }

    @Test
    fun aWrongOrMissingTokenIsNotFound() {
        assertEquals(Decision.Reject(404), decide(target = "/wrong/x"))
        assertEquals(Decision.Reject(404), decide(target = "/"))
        assertEquals(Decision.Reject(404), decide(target = "/$token"))
        assertEquals(Decision.Reject(404), decide(target = "/${token.dropLast(1)}/x"))
        assertEquals(Decision.Reject(404), decide(target = "/${token}x/x"))
    }

    @Test
    fun traversalAndOddPathsAreRefused() {
        assertEquals(Decision.Reject(404), decide(target = "/$token/../x"))
        assertEquals(Decision.Reject(404), decide(target = "/$token//evil.example/x"))
        assertEquals(Decision.Reject(404), decide(target = "/$token/a\\b"))
        assertEquals(Decision.Reject(404), decide(target = "/$token/a#frag"))
        assertEquals(Decision.Reject(400), decide(target = "/$token/a b"))
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
    }

    @Test
    fun browserOriginatedRequestsAreRefused() {
        assertEquals(Decision.Reject(403), decide(headers = host + ("origin" to "https://evil.example")))
        assertEquals(Decision.Reject(403), decide(headers = host + ("referer" to "https://evil.example/")))
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
        for (ok in listOf(200, 206, 304, 401, 403, 404, 416)) assertEquals(ok, ServerRelayRequestPolicy.relayStatus(ok))
        assertEquals("a redirect is decided by RelayRedirectPolicy, never passed raw", 502, ServerRelayRequestPolicy.relayStatus(302))
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
