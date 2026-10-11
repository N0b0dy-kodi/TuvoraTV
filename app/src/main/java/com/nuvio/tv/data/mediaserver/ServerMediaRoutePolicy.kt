package com.nuvio.tv.data.mediaserver

import java.net.URI

/**
 * Which network path a media URL takes. A host that has a trust-on-first-use pin (see [ServerTrust]) must be
 * reached through the pinned client; everything else keeps normal validation. Pure: callers supply the pin lookup.
 *
 * Surfaces: images (Coil) and ExoPlayer take an OkHttp client with the pin; mpv/ffmpeg cannot take a pin, so it
 * is handed a per-session loopback relay (see [ServerLoopbackRelay]) instead of ever disabling TLS verification.
 */
internal object ServerMediaRoutePolicy {
    enum class Surface { IMAGE, EXOPLAYER, MPV }

    sealed interface Route {
        /** Normal validation, no change (CA-signed servers, IPTV, plain http, loopback). */
        data object Direct : Route

        /** Use an OkHttp client that trusts exactly this authority's pinned certificate. */
        data class PinnedClient(val authority: String) : Route

        /** Hand the engine a loopback URL that relays to this authority through the pinned client. */
        data class LoopbackRelay(val authority: String) : Route
    }

    fun route(url: String, surface: Surface, isPinned: (String) -> Boolean): Route {
        val authority = authorityOf(url) ?: return Route.Direct
        if (!isPinned(authority)) return Route.Direct
        return when (surface) {
            Surface.IMAGE, Surface.EXOPLAYER -> Route.PinnedClient(authority)
            Surface.MPV -> Route.LoopbackRelay(authority)
        }
    }

    /** `host:port` (lower-case, https default 443) for an https URL; null otherwise (the relay's own http loopback URL included). */
    fun authorityOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port == -1) 443 else uri.port
        return "$host:$port"
    }
}
