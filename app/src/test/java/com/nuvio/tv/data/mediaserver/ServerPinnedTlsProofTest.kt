package com.nuvio.tv.data.mediaserver

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * Proof against a REAL local HTTPS server with a freshly generated self-signed certificate (JDK keytool):
 * the pinned client reads images and ranges, a changed certificate is refused, other hosts stay validated,
 * and the loopback relay serves the same bytes (redirects and HLS included) to a plain-HTTP client.
 */
class ServerPinnedTlsProofTest {
    private val servers = mutableListOf<TestHttpsServer>()
    private val relays = mutableListOf<ServerLoopbackRelay>()
    private val video = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val image = ByteArray(2_048) { (it % 199).toByte() }
    private val seen = mutableListOf<String>()
    private val seenHeaders = mutableListOf<Map<String, String>>()

    @After
    fun tearDown() {
        relays.forEach { it.close() }
        servers.forEach { it.stop() }
    }

    private fun start(port: Int = 0, extra: (String, Int) -> TestReply? = { _, _ -> null }): TestHttpsServer {
        lateinit var self: TestHttpsServer
        self = TestHttpsServer(port) { path, headers ->
            seen += path.substringBefore('?')
            seenHeaders += headers
            extra(path, self.port) ?: when {
                path.endsWith(".jpg") -> rangeReply(image, "image/jpeg", headers["range"])
                else -> rangeReply(video, "video/mp4", headers["range"])
            }
        }
        servers += self
        return self
    }

    private val base = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private fun fetch(client: OkHttpClient, url: String, range: String? = null): Pair<Int, ByteArray> {
        val request = Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()
        return client.newCall(request).execute().use { it.code to it.body!!.bytes() }
    }

    private fun pinned(s: TestHttpsServer): Pair<ServerTrust, OkHttpClient> {
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin(s.authority, s.fingerprint)
        return trust to ServerMediaRouter(trust).pinnedClient("https://${s.authority}/x", base)!!
    }

    private fun openRelay(s: TestHttpsServer, path: String = "/v.mp4"): ServerLoopbackRelay {
        val (_, client) = pinned(s)
        return ServerLoopbackRelay.open("https://${s.authority}$path", mapOf("Authorization" to "secret-session"), client).also { relays += it }
    }

    private val noFollow = OkHttpClient.Builder().followRedirects(false).build()

    @Test
    fun theSystemValidatedClientRefusesTheSelfSignedServer() {
        val s = start()
        try {
            fetch(base, "https://${s.authority}/a.jpg")
            fail("expected a TLS failure")
        } catch (_: javax.net.ssl.SSLException) {
        }
    }

    @Test
    fun anUnpinnedServerSurfacesItsFingerprintForTheUser() {
        val s = start()
        val trust = ServerTrust(MemoryServerTrustStore())
        try {
            fetch(base.withServerTrust(s.authority, trust), "https://${s.authority}/a.jpg")
            fail("expected a refusal before the user accepts")
        } catch (_: IOException) {
        }
        val prompt = trust.takeCertificateError(s.authority)!!
        assertEquals(ServerFailure.CERTIFICATE_UNTRUSTED, prompt.failure)
        assertEquals("the prompt shows the real certificate's fingerprint", s.fingerprint, prompt.fingerprint)
    }

    @Test
    fun aPinnedServerServesImagesAndRanges() {
        val s = start()
        val (_, client) = pinned(s)

        val (code, bytes) = fetch(client, "https://${s.authority}/a.jpg")
        assertEquals(200, code)
        assertArrayEquals("image bytes", image, bytes)
        val (rangeCode, part) = fetch(client, "https://${s.authority}/v.mp4", "bytes=1000-1999")
        assertEquals(206, rangeCode)
        assertArrayEquals("range bytes", video.copyOfRange(1000, 2000), part)
    }

    @Test
    fun aChangedCertificateIsRefusedWithAReTrustPrompt() {
        val first = start()
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin(first.authority, first.fingerprint)
        first.stop()
        val second = start(port = first.port) // same host:port, new certificate
        try {
            fetch(base.withServerTrust(second.authority, trust), "https://${second.authority}/a.jpg")
            fail("a changed certificate must not be accepted silently")
        } catch (_: IOException) {
        }
        val prompt = trust.takeCertificateError(second.authority)!!
        assertEquals(ServerFailure.CERTIFICATE_CHANGED, prompt.failure)
        assertEquals(first.fingerprint, prompt.pinned)
        assertEquals(second.fingerprint, prompt.fingerprint)
    }

    @Test
    fun aPinDoesNotTrustAnotherHost() {
        val a = start()
        val b = start()
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin(a.authority, a.fingerprint)
        val router = ServerMediaRouter(trust)

        assertNotNull(router.pinnedClient("https://${a.authority}/x", base))
        assertNull("an unpinned authority gets no pinned client", router.pinnedClient("https://${b.authority}/x", base))
    }

    @Test
    fun theRelayStreamsTheSameBytesToAPlainHttpClient() {
        val s = start()
        val relay = openRelay(s)

        assertTrue("loopback only", relay.url.startsWith("http://127.0.0.1:"))
        assertTrue("the player sees the original path", relay.url.endsWith("/v.mp4"))
        val plain = OkHttpClient()
        val (code, all) = fetch(plain, relay.url)
        assertEquals(200, code)
        assertArrayEquals("full file", video, all)
        val (rangeCode, part) = fetch(plain, relay.url, "bytes=250000-")
        assertEquals(206, rangeCode)
        assertArrayEquals("tail range", video.copyOfRange(250_000, video.size), part)
        assertTrue("the session auth header reached the server", seenHeaders.all { it["authorization"] == "secret-session" })
        assertEquals(listOf("/v.mp4", "/v.mp4"), seen)
    }

    @Test
    fun theRelayRefusesAnythingButItsTokenPath() {
        val s = start()
        val relay = openRelay(s)
        val port = relay.url.substringAfter("127.0.0.1:").substringBefore('/').toInt()

        assertEquals(404, noFollow.newCall(Request.Builder().url("http://127.0.0.1:$port/not-the-token/v.mp4").build()).execute().use { it.code })
        assertEquals(405, noFollow.newCall(Request.Builder().url(relay.url).post(ByteArray(1).toRequestBody()).build()).execute().use { it.code })
        assertEquals(421, noFollow.newCall(Request.Builder().url(relay.url).header("Host", "evil.example:$port").build()).execute().use { it.code })
        assertTrue("nothing was fetched upstream for refused requests", seen.isEmpty())
    }

    @Test
    fun aRedirectBackToThePinnedServerIsServedThroughTheRelay() {
        val s = start { path, port ->
            when (path) {
                "/rel" -> TestReply(302, mapOf("Location" to "/v.mp4"))
                "/abs" -> TestReply(301, mapOf("Location" to "https://127.0.0.1:$port/v.mp4"))
                "/cdn" -> TestReply(307, mapOf("Location" to "https://cdn.example.com/dl/abc?t=1", "Set-Cookie" to "sid=1"))
                "/private" -> TestReply(302, mapOf("Location" to "https://10.0.0.5/x"))
                "/loopback" -> TestReply(302, mapOf("Location" to "https://127.0.0.1:1/x"))
                "/http" -> TestReply(302, mapOf("Location" to "http://cdn.example.com/x"))
                else -> null
            }
        }
        val relay = openRelay(s, "/rel")
        val tokenBase = relay.url.substringBeforeLast("/rel")
        val token = relay.url.substringAfter("127.0.0.1:").substringAfter('/').substringBefore('/')

        val rel = noFollow.newCall(Request.Builder().url(relay.url).build()).execute().use { it.code to it.header("Location") }
        assertEquals("relative redirect stays in the session", 302 to "/$token/v.mp4", rel)
        val (code, bytes) = fetch(OkHttpClient(), relay.url) // following it plays the file
        assertEquals(200, code)
        assertArrayEquals(video, bytes)
        val abs = noFollow.newCall(Request.Builder().url("$tokenBase/abs").build()).execute().use { it.code to it.header("Location") }
        assertTrue("absolute pinned redirect re-served via relay: $abs", abs.first == 301 && abs.second!!.endsWith("/v.mp4") && !abs.second!!.startsWith("http"))
        val cdn = noFollow.newCall(Request.Builder().url("$tokenBase/cdn").build()).execute().use { Triple(it.code, it.header("Location"), it.header("Set-Cookie")) }
        assertEquals("public https redirect passed through bare", Triple(307, "https://cdn.example.com/dl/abc?t=1", null), cdn)
        for (blocked in listOf("/private", "/loopback", "/http")) {
            assertEquals("refused: $blocked", 502, noFollow.newCall(Request.Builder().url("$tokenBase$blocked").build()).execute().use { it.code })
        }
    }

    @Test
    fun hlsPlaylistsGetPinnedReferencesRewrittenAndLengthRecomputed() {
        val s = start { path, port ->
            when (path) {
                "/live.m3u8" -> TestReply(
                    200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
                    ("#EXTM3U\n#EXTINF:6,\nhttps://127.0.0.1:$port/seg0.ts\n#EXTINF:6,\n/seg1.ts\nseg2.ts\nhttps://cdn.example.com/3.ts\n").toByteArray()
                )
                "/big.m3u8" -> TestReply(200, mapOf("Content-Type" to "application/x-mpegURL"), ByteArray(HlsPlaylistRewriter.MAX_PLAYLIST_BYTES + 10) { '#'.code.toByte() })
                else -> null
            }
        }
        val relay = openRelay(s, "/live.m3u8")
        val tokenBase = relay.url.substringBeforeLast("/live.m3u8")

        val response = noFollow.newCall(Request.Builder().url(relay.url).build()).execute()
        val text = response.use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertEquals("Content-Length is the rewritten size", body.toByteArray().size.toString(), it.header("Content-Length"))
            body
        }
        assertEquals(
            "#EXTM3U\n#EXTINF:6,\n$tokenBase/seg0.ts\n#EXTINF:6,\n$tokenBase/seg1.ts\nseg2.ts\nhttps://cdn.example.com/3.ts\n",
            text
        )
        assertEquals("a playlist over the cap is refused, never streamed unrewritten", 502,
            noFollow.newCall(Request.Builder().url("$tokenBase/big.m3u8").build()).execute().use { it.code })
    }

    @Test
    fun closingTheRelayStopsListening() {
        val s = start()
        val relay = openRelay(s)
        val port = relay.url.substringAfter("127.0.0.1:").substringBefore('/').toInt()

        relay.close()

        val refused = try {
            Socket(InetAddress.getByName("127.0.0.1"), port).close(); false
        } catch (_: IOException) {
            true
        }
        assertTrue("nothing listens after teardown", refused)
        assertFalse(relay.isOpen)
    }

    @Test
    fun theRelayRefusesToOpenForAPlainHttpTarget() {
        try {
            ServerLoopbackRelay.open("http://nas.lan:8096/v.mp4", emptyMap(), base)
            fail("plain http targets are not relayed")
        } catch (_: IllegalArgumentException) {
        }
    }
}
