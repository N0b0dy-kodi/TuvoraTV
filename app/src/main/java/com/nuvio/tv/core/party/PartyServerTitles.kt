package com.nuvio.tv.core.party

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerItemRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A media-server title's account-independent identity, carried in [PartyMedia.server]. */
@Serializable
data class PartyServerRef(
    val provider: String,
    @SerialName("remote_server_id") val remoteServerId: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("video_item_id") val videoItemId: String? = null,
)

sealed interface PartyServerResolution {
    data object NotServer : PartyServerResolution
    data class Local(val media: PartyMedia) : PartyServerResolution
    data object NotConnected : PartyServerResolution
}

/**
 * Media-server titles in a party (TV twin of Mobile `PartyServerTitleMatch`, e195adf3b). Their ids
 * (`srv1:<record>:<item>`) name the HOST DEVICE's local server record, which no other account or
 * device has, so the party also carries the server's own identity ([PartyServerRef]: provider + the
 * Jellyfin/Emby machine id + item ids). Every device keys the title by that portable identity, and a
 * guest maps it onto ITS OWN connected record. Pure.
 */
object PartyServerTitleMatch {
    private const val PORTABLE_PREFIX = "srv:"

    /** The identity of a local server title, or null when [metaId] is not one of this device's server titles. */
    fun refOf(metaId: String, videoId: String?, connections: List<ServerConnection>): PartyServerRef? {
        val item = ServerItemRef.parse(metaId) ?: return null
        val connection = connections.firstOrNull { it.id == item.connectionId } ?: return null
        val video = ServerItemRef.parse(videoId)?.takeIf { it.connectionId == item.connectionId }?.itemId
        return PartyServerRef(
            provider = connection.providerId,
            remoteServerId = connection.remoteServerId,
            itemId = item.itemId,
            videoItemId = video?.takeIf { it != item.itemId },
        )
    }

    /** The account-independent id a party keys a local server title by; null for anything else. */
    fun portableMetaId(metaId: String, connections: List<ServerConnection>): String? =
        refOf(metaId, null, connections)?.let(::portableId)

    fun portableId(ref: PartyServerRef): String =
        "$PORTABLE_PREFIX${ref.provider.lowercase()}:${ref.remoteServerId.lowercase()}:${ref.itemId}"

    /** The party key of what this player shows: server titles by their portable id, everything else as is. */
    fun playerKey(metaType: String, metaId: String, season: Int?, episode: Int?, connections: List<ServerConnection>): String =
        partyMediaKey(metaType, portableMetaId(metaId, connections) ?: metaId, season, episode)

    /** The media a host shares: unchanged, plus [PartyMedia.server] when it is one of this device's server titles. */
    fun withServer(media: PartyMedia, connections: List<ServerConnection>): PartyMedia {
        if (media.server != null) return media
        val ref = refOf(media.metaId, media.videoId, connections) ?: return media
        return media.copy(server = ref)
    }

    /** What a guest plays for [media]: its own record's ids, or [PartyServerResolution.NotConnected]. */
    fun localize(media: PartyMedia, connections: List<ServerConnection>): PartyServerResolution {
        val ref = media.server
        val sharedItem = ServerItemRef.parse(media.metaId)
        if (ref == null) {
            if (sharedItem == null) return PartyServerResolution.NotServer
            // An older host: only its own record id to go on.
            val same = connections.firstOrNull { it.id == sharedItem.connectionId && it.enabled }
            return if (same != null) PartyServerResolution.Local(media) else PartyServerResolution.NotConnected
        }
        val connection = connections.firstOrNull {
            it.enabled && it.providerId.equals(ref.provider, ignoreCase = true) &&
                it.remoteServerId.equals(ref.remoteServerId, ignoreCase = true)
        } ?: return PartyServerResolution.NotConnected
        val oldConnection = sharedItem?.connectionId
        val videoItem = ref.videoItemId
            ?: ServerItemRef.parse(media.videoId)?.itemId?.takeIf { media.videoId != media.metaId }
        val source = media.source?.let { source ->
            val addon = source.addonId
            if (oldConnection != null && addon != null && addon.startsWith("server:$oldConnection")) {
                source.copy(addonId = "server:${connection.id}" + addon.removePrefix("server:$oldConnection"))
            } else {
                source
            }
        }
        return PartyServerResolution.Local(
            media.copy(
                metaId = ServerItemRef(connection.id, ref.itemId).encode(),
                videoId = videoItem?.let { ServerItemRef(connection.id, it).encode() }
                    ?: media.videoId?.takeIf { !ServerItemRef.isServerId(it) },
                source = source,
            ),
        )
    }
}
