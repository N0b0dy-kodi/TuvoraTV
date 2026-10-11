package com.nuvio.tv.data.mediaserver

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Pure request/response rules of the loopback relay. */
internal object ServerRelayRequestPolicy {
    sealed interface Decision {
        /** [path] (with query) starts with "/" and is served from the session's one authority. */
        data class Serve(
            val headOnly: Boolean,
            val path: String,
            val range: String? = null,
            val conditional: Map<String, String> = emptyMap()
        ) : Decision
        data class Reject(val status: Int) : Decision
    }

    private val RANGE = Regex("^bytes=\\d*-\\d*$")
    private val CONDITIONAL = mapOf("if-range" to "If-Range", "if-none-match" to "If-None-Match", "if-modified-since" to "If-Modified-Since")
    private val ALLOWED_RESPONSE_HEADERS =
        setOf("content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified", "cache-control")

    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** [headers] has lower-case names. The target is `/<token>/<path on the pinned server>[?query]`. */
    fun decide(method: String, target: String, headers: Map<String, String>, token: String, port: Int): Decision {
        if (method != "GET" && method != "HEAD") return Decision.Reject(405)
        // A browser (or a page via DNS rebinding) must never be able to read the stream.
        if ("origin" in headers || "referer" in headers) return Decision.Reject(403)
        val host = headers["host"]?.lowercase() ?: return Decision.Reject(421)
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return Decision.Reject(421)
        if (!target.startsWith("/") || target.any { it.code < 0x21 || it.code == 0x7f }) return Decision.Reject(400)
        if (target.contains('#') || target.contains('\\')) return Decision.Reject(404)
        val firstSlash = target.indexOf('/', 1)
        if (firstSlash < 0) return Decision.Reject(404)
        val presented = target.substring(1, firstSlash)
        if (!MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())) return Decision.Reject(404)
        val path = target.substring(firstSlash)
        val pathOnly = path.substringBefore('?')
        if (pathOnly.startsWith("//") || pathOnly.split('/').any { it == ".." }) return Decision.Reject(404)
        val range = headers["range"]?.takeIf { RANGE.matches(it) }
        val conditional = CONDITIONAL.mapNotNull { (lower, canonical) -> headers[lower]?.let { canonical to it } }.toMap()
        return Decision.Serve(headOnly = method == "HEAD", path = path, range = range, conditional = conditional)
    }

    /** The status given to the engine: playable statuses pass through (304 included), everything else becomes 502. */
    fun relayStatus(upstream: Int): Int = when (upstream) {
        200, 206, 304, 401, 403, 404, 416 -> upstream
        else -> 502
    }

    fun isResponseHeaderAllowed(name: String): Boolean = name.lowercase() in ALLOWED_RESPONSE_HEADERS
}

/**
 * A per-playback loopback relay for engines that cannot take a certificate pin (mpv/ffmpeg).
 *
 * - binds 127.0.0.1 only, on an ephemeral port;
 * - one unguessable 128-bit token path per session ([ServerRelayRequestPolicy]);
 * - forwards ONLY to the one host:port given at [open], through the caller's pinned client (redirects off,
 *   [RelayRedirectPolicy] decides each one), adding that session's own headers; never an open proxy;
 * - HLS playlists (200, up to 2 MiB) get references to the pinned host rewritten back through the relay;
 * - supports Range, streams in 64 KiB pieces (no whole-file buffering), HEAD without a body;
 * - closed with the player ([close] stops listening and cancels in-flight upstream calls);
 * - logs nothing: no URL, header or token ever reaches a log.
 */
class ServerLoopbackRelay private constructor(
    private val server: ServerSocket,
    private val authority: String,
    private val upstreamHeaders: Map<String, String>,
    private val client: OkHttpClient,
    private val token: String,
    initialPath: String
) : Closeable {
    private val port = server.localPort
    private val pool: ExecutorService = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val calls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var open = true

    val isOpen: Boolean get() = open

    private val base = "http://127.0.0.1:$port/$token"

    /** What the engine plays: the original path on the pinned server, behind the session token. */
    val url: String = base + initialPath

    override fun toString(): String = "ServerLoopbackRelay(port=$port)"

    private fun start() {
        Thread({
            while (open) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    pool.execute { handle(socket) }
                } catch (_: Exception) {
                    runCatching { socket.close() }
                }
            }
        }, "server-relay-accept").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        sockets += socket
        try {
            socket.soTimeout = HEADER_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val request = readRequest(input) ?: return respond(socket.getOutputStream(), 400)
            val decision = ServerRelayRequestPolicy.decide(request.method, request.target, request.headers, token, port)
            val out = socket.getOutputStream()
            when (decision) {
                is ServerRelayRequestPolicy.Decision.Reject -> respond(out, decision.status)
                is ServerRelayRequestPolicy.Decision.Serve -> forward(decision, out)
            }
        } catch (_: Exception) {
            // Peer gone or upstream failed: nothing to log (no URL may reach a log).
        } finally {
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    private fun forward(decision: ServerRelayRequestPolicy.Decision.Serve, out: OutputStream) {
        val builder = Request.Builder().url("https://$authority${decision.path}")
        upstreamHeaders.forEach { (name, value) -> builder.header(name, value) }
        decision.range?.let { builder.header("Range", it) }
        decision.conditional.forEach { (name, value) -> builder.header(name, value) }
        builder.method(if (decision.headOnly) "HEAD" else "GET", null)
        val call = client.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(builder.build())
        calls += call
        try {
            call.execute().use { response ->
                val code = response.code
                if (RelayRedirectPolicy.isRedirect(code)) {
                    // Back to the pinned server -> re-served through this session; a public https host -> the player
                    // follows it itself and validates THAT host normally; anything else -> refused.
                    when (val next = RelayRedirectPolicy.decide(code, response.header("Location"), authority)) {
                        is RelayRedirectPolicy.Decision.ViaRelay -> redirectAnswer(out, code, "/$token${next.path}")
                        is RelayRedirectPolicy.Decision.PassThrough -> redirectAnswer(out, code, next.location)
                        RelayRedirectPolicy.Decision.Refuse -> respond(out, 502)
                    }
                    return@use
                }
                val status = ServerRelayRequestPolicy.relayStatus(code)
                val contentType = response.header("Content-Type")
                if (!decision.headOnly && status == 200 && HlsPlaylistRewriter.isPlaylist(contentType, decision.path)) {
                    relayPlaylist(response.body?.source(), response, decision, out)
                    return@use
                }
                val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
                if (status == code) {
                    response.headers.forEach { (name, value) ->
                        if (ServerRelayRequestPolicy.isResponseHeaderAllowed(name) && !value.contains('\r') && !value.contains('\n')) {
                            head.append(name).append(": ").append(value).append("\r\n")
                        }
                    }
                } else {
                    head.append("Content-Length: 0\r\n")
                }
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val body = response.body
                if (!decision.headOnly && status == code && status != 304 && body != null) {
                    val source = body.source()
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // Upstream or peer failed mid-stream: close the connection, the engine will retry with a new Range.
        } finally {
            calls -= call
        }
    }

    /** A bare redirect: Location and nothing else of ours or the server's. */
    private fun redirectAnswer(out: OutputStream, status: Int, location: String) {
        val text = "HTTP/1.1 $status ${reason(status)}\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Playlists only, status 200, buffered up to the cap (larger is refused, never streamed unrewritten). */
    private fun relayPlaylist(
        source: okio.BufferedSource?,
        response: okhttp3.Response,
        decision: ServerRelayRequestPolicy.Decision.Serve,
        out: OutputStream
    ) {
        val buffer = okio.Buffer()
        if (source != null) {
            while (buffer.size <= HlsPlaylistRewriter.MAX_PLAYLIST_BYTES) {
                if (source.read(buffer, 8192L) < 0) break
            }
        }
        if (buffer.size > HlsPlaylistRewriter.MAX_PLAYLIST_BYTES) {
            respond(out, 502)
            return
        }
        val rewritten = HlsPlaylistRewriter.rewrite(buffer.readUtf8(), authority, base).toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 200 OK\r\n")
        response.headers.forEach { (name, value) ->
            if (ServerRelayRequestPolicy.isResponseHeaderAllowed(name) && !name.equals("Content-Length", true) &&
                !name.equals("Content-Range", true) && !value.contains('\r') && !value.contains('\n')
            ) {
                head.append(name).append(": ").append(value).append("\r\n")
            }
        }
        head.append("Content-Length: ").append(rewritten.size).append("\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(rewritten)
        out.flush()
    }

    private fun respond(out: OutputStream, status: Int) {
        val text = "HTTP/1.1 $status ${reason(status)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(text.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    override fun close() {
        if (!open) return
        open = false
        runCatching { server.close() }
        synchronized(calls) { calls.toList() }.forEach { runCatching { it.cancel() } }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private class ParsedRequest(val method: String, val target: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): ParsedRequest? {
        val raw = ByteArray(MAX_HEADER_BYTES)
        var length = 0
        while (length < raw.size) {
            val read = input.read(raw, length, 1)
            if (read < 0) return null
            length += read
            if (length >= 4 && raw[length - 4] == '\r'.code.toByte() && raw[length - 3] == '\n'.code.toByte() &&
                raw[length - 2] == '\r'.code.toByte() && raw[length - 1] == '\n'.code.toByte()
            ) break
        }
        if (length >= raw.size) return null
        val lines = String(raw, 0, length, Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf(':')
            if (index <= 0) return null
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        return ParsedRequest(parts[0], parts[1], headers)
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"; 304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; 421 -> "Misdirected Request"
        301 -> "Moved Permanently"; 303 -> "See Other"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
        else -> "Bad Gateway"
    }

    companion object {
        private const val MAX_CONNECTIONS = 8
        private const val HEADER_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * @param upstreamUrl the first https URL to play; the session may fetch only paths of ITS host:port;
         * @param headers the session's own request headers (auth), added upstream and never shown to the engine;
         * @param client the pinned client for exactly that URL's authority.
         */
        fun open(upstreamUrl: String, headers: Map<String, String>, client: OkHttpClient): ServerLoopbackRelay {
            val authority = requireNotNull(ServerMediaRoutePolicy.authorityOf(upstreamUrl)) { "only https server URLs are relayed" }
            val initialPath = "/" + upstreamUrl.substringAfter("://").substringAfter('/', "").substringBefore('#')
            val server = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            return ServerLoopbackRelay(server, authority, headers.toMap(), client, ServerRelayRequestPolicy.newToken(), initialPath)
                .also { it.start() }
        }
    }
}
