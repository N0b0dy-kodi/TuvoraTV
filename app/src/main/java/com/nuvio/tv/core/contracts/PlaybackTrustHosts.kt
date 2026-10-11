package com.nuvio.tv.core.contracts

import java.util.concurrent.CopyOnWriteArrayList

/**
 * How strictly the player validates TLS for a host (pure; the shape Mobile uses too).
 *
 *  - [Strict]: normal system validation, no fallback, ever. Media servers, add-on and catalog hosts, CDNs, unknown hosts.
 *  - [PermissivePanel]: the host belongs to one of the user's IPTV playlists/panels; after a failed handshake the request
 *    may be retried with certificate checks off, because panels commonly present self-signed or mismatched certificates.
 *
 * A host that is both a panel and a media server (the user pointed both at one box) stays [Strict]: a media server is
 * never unverified.
 */
enum class PlaybackTlsClass { Strict, PermissivePanel }

object PlaybackTlsPolicy {
    fun classify(
        host: String?,
        isPanelHost: (String) -> Boolean,
        isMediaServerHost: (String) -> Boolean
    ): PlaybackTlsClass {
        val normalized = normalize(host) ?: return PlaybackTlsClass.Strict
        if (isMediaServerHost(normalized)) return PlaybackTlsClass.Strict
        return if (isPanelHost(normalized)) PlaybackTlsClass.PermissivePanel else PlaybackTlsClass.Strict
    }

    /** Lower case, no brackets, no trailing dot; null when nothing is left. */
    fun normalize(host: String?): String? =
        host?.trim()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}

/**
 * The hosts the playback stack may treat specially. Features register predicates from the composition root
 * (IPTV: its panels; media servers: their connections); the player asks [classify] and names neither feature.
 * Registration is idempotent per name.
 */
object PlaybackTrustHosts {
    private class Named(val name: String, val predicate: (String) -> Boolean)

    private val panel = CopyOnWriteArrayList<Named>()
    private val mediaServer = CopyOnWriteArrayList<Named>()

    fun registerPanelHostPredicate(name: String, predicate: (String) -> Boolean) = put(panel, name, predicate)

    fun registerMediaServerHostPredicate(name: String, predicate: (String) -> Boolean) = put(mediaServer, name, predicate)

    fun isPanelHost(host: String): Boolean = panel.any { it.predicate(host) }

    fun isMediaServerHost(host: String): Boolean = mediaServer.any { it.predicate(host) }

    fun classify(host: String?): PlaybackTlsClass = PlaybackTlsPolicy.classify(host, ::isPanelHost, ::isMediaServerHost)

    private fun put(list: CopyOnWriteArrayList<Named>, name: String, predicate: (String) -> Boolean) {
        list.removeAll { it.name == name }
        list.add(Named(name, predicate))
    }
}
