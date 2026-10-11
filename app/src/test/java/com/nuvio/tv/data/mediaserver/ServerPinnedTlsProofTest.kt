package com.nuvio.tv.data.mediaserver

import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Proof against a REAL local HTTPS server with a freshly generated self-signed certificate (JDK keytool):
 * the pinned client reads images and ranges, a changed certificate is refused, other hosts stay validated,
 * and the loopback relay serves the same bytes to a plain-HTTP client (what mpv sees).
 */
class ServerPinnedTlsProofTest {
    private val servers = mutableListOf<TestHttps>()
    private val relays = mutableListOf<ServerLoopbackRelay>()
    private val video = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val image = ByteArray(2_048) { (it % 199).toByte() }

    @After
    fun tearDown() {
        relays.forEach { it.close() }
        servers.forEach { it.stop() }
    }

    private class Started(val server: TestHttps, val authority: String, val fingerprint: String, val port: Int)

    private fun start(port: Int = 0, requests: MutableList<String>? = null): Started {
        val dir = Files.createTempDirectory("tls-proof").toFile()
        val store = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=media-test", "-storetype", "PKCS12", "-keystore", store.absolutePath,
            "-storepass", "changeit", "-keypass", "changeit"
        ).redirectErrorStream(true).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed" }
        val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "changeit".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = TestHttps(ssl.serverSocketFactory.createServerSocket(port, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket) { path, range ->
            requests?.add(path)
            val body = if (path.endsWith(".jpg")) image else video
            Triple(if (path.endsWith(".jpg")) "image/jpeg" else "video/mp4", body, range)
        }
        servers += server
        val cert = keyStore.getCertificate("s") as X509Certificate
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
        val actualPort = server.port
        return Started(server, "127.0.0.1:$actualPort", fp, actualPort)
    }

    private val base = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private fun fetch(client: OkHttpClient, url: String, range: String? = null): Pair<Int, ByteArray> {
        val request = Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()
        return client.newCall(request).execute().use { it.code to it.body!!.bytes() }
    }

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
        val client = base.withServerTrust(s.authority, trust)

        try {
            fetch(client, "https://${s.authority}/a.jpg")
            fail("expected a refusal before the user accepts")
        } catch (_: java.io.IOException) {
        }
        val prompt = trust.takeCertificateError(s.authority)!!
        assertEquals(ServerFailure.CERTIFICATE_UNTRUSTED, prompt.failure)
        assertEquals("the prompt shows the real certificate's fingerprint", s.fingerprint, prompt.fingerprint)
    }

    @Test
    fun aPinnedServerServesImagesAndRanges() {
        val s = start()
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin(s.authority, s.fingerprint)
        val client = ServerMediaRouter(trust).pinnedClient("https://${s.authority}/a.jpg", base)!!

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
        first.server.stop()
        val second = start(port = first.port) // same host:port, new certificate
        val client = base.withServerTrust(second.authority, trust)

        try {
            fetch(client, "https://${second.authority}/a.jpg")
            fail("a changed certificate must not be accepted silently")
        } catch (_: java.io.IOException) {
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
        val seen = mutableListOf<String>()
        val s = start(requests = seen)
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin(s.authority, s.fingerprint)
        val pinned = ServerMediaRouter(trust).pinnedClient("https://${s.authority}/v.mp4", base)!!
        val relay = ServerLoopbackRelay.open("https://${s.authority}/v.mp4", emptyMap(), pinned, "video.mp4")
        relays += relay

        assertTrue("loopback only", relay.url.startsWith("http://127.0.0.1:"))
        val plain = OkHttpClient()
        val (code, all) = fetch(plain, relay.url)
        assertEquals(200, code)
        assertArrayEquals("full file", video, all)
        val (rangeCode, part) = fetch(plain, relay.url, "bytes=250000-")
        assertEquals(206, rangeCode)
        assertArrayEquals("tail range", video.copyOfRange(250_000, video.size), part)
        assertEquals("the upstream saw the real path", listOf("/v.mp4", "/v.mp4"), seen)
    }

    @Test
    fun theRelayRefusesAnythingButItsTokenPath() {
        val s = start()
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin(s.authority, s.fingerprint)
        val pinned = ServerMediaRouter(trust).pinnedClient("https://${s.authority}/v.mp4", base)!!
        val relay = ServerLoopbackRelay.open("https://${s.authority}/v.mp4", emptyMap(), pinned, null)
        relays += relay
        val port = relay.url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        val plain = OkHttpClient()

        val wrong = plain.newCall(Request.Builder().url("http://127.0.0.1:$port/not-the-token").build()).execute().use { it.code }
        assertEquals(404, wrong)
        val post = plain.newCall(
            Request.Builder().url(relay.url).post(okhttp3.RequestBody.create(null, ByteArray(1))).build()
        ).execute().use { it.code }
        assertEquals(405, post)
        val rebinding = plain.newCall(Request.Builder().url(relay.url).header("Host", "evil.example:$port").build()).execute().use { it.code }
        assertEquals(421, rebinding)
    }

    @Test
    fun closingTheRelayStopsListening() {
        val s = start()
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin(s.authority, s.fingerprint)
        val pinned = ServerMediaRouter(trust).pinnedClient("https://${s.authority}/v.mp4", base)!!
        val relay = ServerLoopbackRelay.open("https://${s.authority}/v.mp4", emptyMap(), pinned, null)
        val port = relay.url.substringAfter("127.0.0.1:").substringBefore('/').toInt()

        relay.close()

        val refused = try {
            Socket(InetAddress.getByName("127.0.0.1"), port).close(); false
        } catch (_: java.io.IOException) {
            true
        }
        assertTrue("nothing listens after teardown", refused)
        assertFalse(relay.isOpen)
    }

    @Test
    fun theRelayRefusesToOpenForAnUnpinnedClientTarget() {
        try {
            ServerLoopbackRelay.open("http://nas.lan:8096/v.mp4", emptyMap(), base, null)
            fail("plain http targets are not relayed")
        } catch (_: IllegalArgumentException) {
        }
    }
}

/** A tiny range-capable HTTPS file server (raw SSL sockets; the JDK http server is not on the Android test classpath). */
private class TestHttps(
    private val socket: SSLServerSocket,
    private val resolve: (path: String, range: String?) -> Triple<String, ByteArray, String?>
) {
    val port: Int = socket.localPort

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                Thread {
                    try {
                        client.use { c ->
                            val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val requestLine = reader.readLine() ?: return@use
                            var range: String? = null
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.lowercase().startsWith("range:")) range = line.substringAfter(':').trim()
                            }
                            val path = requestLine.split(' ')[1]
                            val (type, body, _) = resolve(path, range)
                            val out = c.getOutputStream()
                            val head: String
                            val slice: ByteArray
                            if (range != null && range!!.startsWith("bytes=")) {
                                val (a, b) = range!!.removePrefix("bytes=").split("-")
                                val start = a.toInt()
                                val end = if (b.isEmpty()) body.size - 1 else b.toInt()
                                slice = body.copyOfRange(start, end + 1)
                                head = "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${body.size}\r\n"
                            } else {
                                slice = body
                                head = "HTTP/1.1 200 OK\r\n"
                            }
                            out.write((head + "Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                            out.write(slice)
                            out.flush()
                        }
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() = socket.close()
}
