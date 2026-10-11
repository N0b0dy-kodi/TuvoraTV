package com.nuvio.tv.data.mediaserver

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

internal class TestReply(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

/** Serves [body] honouring a single `Range: bytes=a-b` request header. */
internal fun rangeReply(body: ByteArray, contentType: String, range: String?): TestReply {
    if (range != null && range.startsWith("bytes=")) {
        val (a, b) = range.removePrefix("bytes=").split("-")
        val start = a.toInt()
        val end = if (b.isEmpty()) body.size - 1 else b.toInt()
        return TestReply(
            206,
            mapOf("Content-Type" to contentType, "Content-Range" to "bytes $start-$end/${body.size}", "Accept-Ranges" to "bytes"),
            body.copyOfRange(start, end + 1)
        )
    }
    return TestReply(200, mapOf("Content-Type" to contentType, "Accept-Ranges" to "bytes"), body)
}

/**
 * A tiny HTTPS file server on 127.0.0.1 with a freshly generated self-signed certificate (JDK keytool), on raw SSL
 * sockets (the JDK http server is not on the Android unit-test classpath).
 */
internal class TestHttpsServer(port: Int = 0, private val handler: (path: String, headers: Map<String, String>) -> TestReply) {
    private val socket: SSLServerSocket
    val port: Int
    val authority: String
    val fingerprint: String

    init {
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
        socket = ssl.serverSocketFactory.createServerSocket(port, 16) as SSLServerSocket
        this.port = socket.localPort
        authority = "127.0.0.1:${this.port}"
        val cert = keyStore.getCertificate("s") as X509Certificate
        fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
        Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                Thread { serve(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(client: java.net.Socket) {
        try {
            client.use { c ->
                val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                }
                val reply = handler(requestLine.split(' ')[1], headers)
                val head = StringBuilder("HTTP/1.1 ${reply.status} X\r\n")
                reply.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                head.append("Content-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n")
                val out = c.getOutputStream()
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(reply.body)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    fun stop() = socket.close()
}
