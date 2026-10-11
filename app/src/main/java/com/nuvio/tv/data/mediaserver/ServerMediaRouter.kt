package com.nuvio.tv.data.mediaserver

import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

/**
 * Applies [ServerMediaRoutePolicy] with the device's pins. Pinned clients are derived from the caller's base
 * client (so its interceptors, timeouts and dispatcher stay) and cached per authority; the trust manager reads
 * the pin at handshake time, so a re-trust needs no rebuild. Pins never reach logs or sync.
 */
class ServerMediaRouter(private val trust: ServerTrust) {
    private val clients = ConcurrentHashMap<Pair<String, OkHttpClient>, OkHttpClient>()

    internal fun route(url: String, surface: ServerMediaRoutePolicy.Surface): ServerMediaRoutePolicy.Route =
        ServerMediaRoutePolicy.route(url, surface) { trust.pinned(it) != null }

    /** The client for [url] when its host is pinned (derived from [base]); null = use the normal client. */
    fun pinnedClient(url: String, base: OkHttpClient): OkHttpClient? {
        val route = route(url, ServerMediaRoutePolicy.Surface.EXOPLAYER) as? ServerMediaRoutePolicy.Route.PinnedClient
            ?: return null
        return clients.getOrPut(route.authority to base) { base.withServerTrust(route.authority, trust) }
    }

    /** Whether mpv must be given a relay for [url] (its host is pinned). */
    fun needsRelay(url: String): Boolean =
        route(url, ServerMediaRoutePolicy.Surface.MPV) is ServerMediaRoutePolicy.Route.LoopbackRelay
}

/** Process-wide handle for code that is not Hilt-built (the player's media-source factory, the image loader). */
object ServerMediaRouting {
    @Volatile
    var router: ServerMediaRouter? = null
}
