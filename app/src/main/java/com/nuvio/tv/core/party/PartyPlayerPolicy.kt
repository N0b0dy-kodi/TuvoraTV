package com.nuvio.tv.core.party

/**
 * The player-side party rules that are not part of the per-tick loop ([PartyPlayerSyncPolicy]):
 * which titles may be watched as a party, when the local controls are locked by the host-only
 * rule, whether the host's stream may be shared, where a joiner starts, and whether leaving the
 * player should pause the room.
 */
internal object PartyPlayerPolicy {

    /**
     * A party needs a title every member can resolve from their own add-ons, on a shared timeline.
     * So: never a live channel (no common position), never a cloud-library file, and never an
     * own-source item (an IPTV playlist or media-server id only the host's account can play).
     */
    fun canUseParty(
        contentType: String?,
        contentId: String?,
        isLive: Boolean,
        isOwnSource: Boolean,
    ): Boolean {
        if (contentId.isNullOrBlank() || contentType.isNullOrBlank()) return false
        if (contentType.equals("cloud", ignoreCase = true)) return false
        if (contentType.equals("live", ignoreCase = true) || contentType.equals("tv", ignoreCase = true)) return false
        return !isLive && !isOwnSource
    }

    /** The player shows the party's media. */
    fun isSynced(party: ActiveParty?, playerKey: String): Boolean = party?.media?.key == playerKey

    /** Host-only party, this member is not the host, and it is watching the party's media. */
    fun isControlLocked(party: ActiveParty?, playerKey: String): Boolean =
        party != null && !party.canControl && party.media.key == playerKey

    /**
     * Only the host shares, only when it opted in, and only a plain web link: a torrent, a
     * media-server session or an own-source link would either not play elsewhere or hand out the
     * host's account.
     */
    fun mayShareStream(
        party: ActiveParty?,
        isTorrent: Boolean,
        isServerStream: Boolean,
        isOwnSource: Boolean,
        url: String?,
    ): Boolean {
        if (party == null || !party.isHost || !party.sharing) return false
        if (isTorrent || isServerStream || isOwnSource) return false
        return !url.isNullOrBlank() && isShareableStreamUrl(url)
    }

    /** Where a member opening the party's media should start: the room's position, else null. */
    fun resumePositionMs(party: ActiveParty?, playback: PartyPlayback?, playerKey: String, partyNowMs: Long): Long? {
        if (!isSynced(party, playerKey)) return null
        val current = playback ?: return null
        if (current.mediaKey != null && current.mediaKey != playerKey) return null
        return PartySync.expectedPositionMs(current, partyNowMs)
    }

    /**
     * Leaving the player while the room plays: pause it for everyone, unless someone else is still
     * watching the same title (then they keep going without us).
     */
    fun pauseRoomOnLeave(party: ActiveParty?, playback: PartyPlayback?, playerKey: String): Boolean {
        if (party == null || playback == null || playback.paused) return false
        if (party.media.key != playerKey) return false
        val othersWatching = party.presence.values.any {
            it.memberId != party.memberId && it.mediaKey == party.media.key && it.status != PartyMemberStatus.Browsing
        }
        return !othersWatching
    }
}
