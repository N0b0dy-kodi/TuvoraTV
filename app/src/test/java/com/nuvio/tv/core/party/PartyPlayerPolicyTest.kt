package com.nuvio.tv.core.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit order: assertEquals(message, expected, actual). */
class PartyPlayerPolicyTest {
    private val media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie", title = "Film")

    private fun party(
        memberId: String = "me",
        hostMemberId: String = "me",
        hostOnly: Boolean = false,
        sharing: Boolean = true,
        presence: Map<String, PartyPresence> = emptyMap(),
    ) = ActiveParty(
        id = "p",
        code = "ABC234",
        memberId = memberId,
        hostMemberId = hostMemberId,
        members = emptyList(),
        presence = presence,
        media = media,
        maxMembers = 4,
        connected = true,
        hostOnly = hostOnly,
        sharing = sharing,
    )

    @Test
    fun ordinaryAddonTitlesCanBeWatchedTogether() {
        assertTrue("an add-on movie", PartyPlayerPolicy.canUseParty("movie", "tt1", isLive = false, isOwnSource = false))
        assertTrue("an add-on episode", PartyPlayerPolicy.canUseParty("series", "tt2", isLive = false, isOwnSource = false))
    }

    @Test
    fun titlesOthersCannotResolveOrThatHaveNoTimelineAreExcluded() {
        assertFalse("no id", PartyPlayerPolicy.canUseParty("movie", null, isLive = false, isOwnSource = false))
        assertFalse("cloud file", PartyPlayerPolicy.canUseParty("cloud", "x", isLive = false, isOwnSource = false))
        assertFalse("live type", PartyPlayerPolicy.canUseParty("live", "xtream:1:live:9", isLive = true, isOwnSource = true))
        assertFalse("live by id", PartyPlayerPolicy.canUseParty("movie", "abc", isLive = true, isOwnSource = false))
        assertFalse("IPTV VOD is the host's playlist", PartyPlayerPolicy.canUseParty("movie", "xtream:1:vod:9", isLive = false, isOwnSource = true))
    }

    @Test
    fun hostOnlyLocksGuestsOnThePartyTitleOnly() {
        val guest = party(hostMemberId = "host", hostOnly = true)
        assertTrue("guest on the party's title", PartyPlayerPolicy.isControlLocked(guest, media.key))
        assertFalse("guest on something else", PartyPlayerPolicy.isControlLocked(guest, "movie|tt9|null|null"))
        assertFalse("host", PartyPlayerPolicy.isControlLocked(party(hostOnly = true), media.key))
        assertFalse("everyone controls", PartyPlayerPolicy.isControlLocked(party(hostMemberId = "host"), media.key))
        assertFalse("no party", PartyPlayerPolicy.isControlLocked(null, media.key))
    }

    @Test
    fun onlyTheOptedInHostSharesAPlainWebLink() {
        val url = "https://cdn.example.com/v.mkv"
        assertTrue("host sharing http", PartyPlayerPolicy.mayShareStream(party(), false, false, false, url))
        assertFalse("not opted in", PartyPlayerPolicy.mayShareStream(party(sharing = false), false, false, false, url))
        assertFalse("guest", PartyPlayerPolicy.mayShareStream(party(hostMemberId = "host"), false, false, false, url))
        assertFalse("torrent", PartyPlayerPolicy.mayShareStream(party(), true, false, false, url))
        assertFalse("media server session", PartyPlayerPolicy.mayShareStream(party(), false, true, false, url))
        assertFalse("own source credentials", PartyPlayerPolicy.mayShareStream(party(), false, false, true, url))
        assertFalse("local proxy", PartyPlayerPolicy.mayShareStream(party(), false, false, false, "http://127.0.0.1:8080/v"))
    }

    @Test
    fun joinerStartsAtTheRoomPosition() {
        val playing = PartyPlayback(seq = 3, memberId = "host", paused = false, positionMs = 10_000, at = 1_000, mediaKey = media.key)
        assertEquals("10 s + 5 s elapsed", 15_000L, PartyPlayerPolicy.resumePositionMs(party(), playing, media.key, partyNowMs = 6_000))
        assertNull("other title", PartyPlayerPolicy.resumePositionMs(party(), playing, "movie|tt9|null|null", 6_000))
        assertNull("stale playback of another title", PartyPlayerPolicy.resumePositionMs(party(), playing.copy(mediaKey = "x"), media.key, 6_000))
        assertNull("nothing published yet", PartyPlayerPolicy.resumePositionMs(party(), null, media.key, 6_000))
    }

    @Test
    fun leavingPausesTheRoomOnlyWhenNobodyElseIsWatching() {
        val playing = PartyPlayback(seq = 1, memberId = "me", paused = false, positionMs = 0, at = 0, mediaKey = media.key)
        assertTrue("alone", PartyPlayerPolicy.pauseRoomOnLeave(party(), playing, media.key))
        val friendWatching = party(presence = mapOf("f" to PartyPresence("f", PartyMemberStatus.Ready, media.key)))
        assertFalse("a friend keeps watching", PartyPlayerPolicy.pauseRoomOnLeave(friendWatching, playing, media.key))
        val friendBrowsing = party(presence = mapOf("f" to PartyPresence("f", PartyMemberStatus.Browsing, media.key)))
        assertTrue("a browsing friend does not count", PartyPlayerPolicy.pauseRoomOnLeave(friendBrowsing, playing, media.key))
        assertFalse("already paused", PartyPlayerPolicy.pauseRoomOnLeave(party(), playing.copy(paused = true), media.key))
        assertFalse("watching something else", PartyPlayerPolicy.pauseRoomOnLeave(party(), playing, "movie|tt9|null|null"))
    }
}
