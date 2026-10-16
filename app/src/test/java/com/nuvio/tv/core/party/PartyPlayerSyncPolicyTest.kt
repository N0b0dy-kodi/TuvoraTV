package com.nuvio.tv.core.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV player's party loop, decided without a player. JUnit order: assertEquals(message, expected, actual).
 */
class PartyPlayerSyncPolicyTest {
    private val media = PartyMedia(type = "series", metaId = "tt1", metaType = "series", season = 1, episode = 2, title = "Show")
    private val nextMedia = media.copy(episode = 3)

    private fun party(
        memberId: String = "me",
        hostMemberId: String = "me",
        hostOnly: Boolean = false,
        partyMedia: PartyMedia = media,
    ) = ActiveParty(
        id = "p",
        code = "ABC234",
        memberId = memberId,
        hostMemberId = hostMemberId,
        members = listOf(PartyMember("me", "Me"), PartyMember("host", "Host")),
        presence = emptyMap(),
        media = partyMedia,
        maxMembers = 4,
        connected = true,
        hostOnly = hostOnly,
    )

    private fun playback(
        seq: Long = 1,
        memberId: String = "host",
        paused: Boolean = false,
        positionMs: Long = 60_000,
        rate: Float = 1f,
        at: Long = 1_000_000,
    ) = PartyPlayback(seq = seq, memberId = memberId, paused = paused, positionMs = positionMs, rate = rate, at = at, mediaKey = media.key)

    private fun input(
        party: ActiveParty = party(),
        playback: PartyPlayback? = playback(),
        monoMs: Long = 10_000,
        partyNowMs: Long = 1_000_000,
        playerKey: String = media.key,
        positionMs: Long? = 60_000,
        playing: Boolean = true,
        rate: Float = 1f,
        loading: Boolean = false,
        rendered: Boolean = true,
        inBackground: Boolean = false,
        behind: Boolean = false,
        behindShownMs: Long? = null,
        nudgeRate: Float? = null,
    ) = PartyTickInput(
        monoMs = monoMs,
        partyNowMs = partyNowMs,
        party = party,
        playerKey = playerKey,
        playback = playback,
        positionMs = positionMs,
        durationMs = 3_600_000,
        playing = playing,
        rate = rate,
        loading = loading,
        hasError = false,
        inBackground = inBackground,
        rendered = rendered,
        playbackEnded = false,
        previewSeekPending = false,
        behind = behind,
        behindShownMs = behindShownMs,
        nudgeRate = nudgeRate,
    )

    /** A state that has already applied [seq] and settled, so the next tick is a correction tick. */
    private fun settled(seq: Long = 1, memberId: String = "host") = PartyTickState(
        previousKey = media.key,
        lastSeq = seq to memberId,
        lastStatus = PartyStatusReport(PartyMemberStatus.Ready, media.key, null),
        wasRendered = true,
        publishedInitial = true,
        lastSeekMs = 0L,
    )

    private inline fun <reified T : PartyTickAction> PartyTickResult.only(): List<T> = actions.filterIsInstance<T>()

    @Test
    fun firstTickAppliesThePartyStateAndReportsReady() {
        val result = PartyPlayerSyncPolicy.tick(PartyTickState(), input(playback = playback(paused = true, positionMs = 90_000), playing = true))
        assertEquals("a fresh session adopts the room's pause", listOf(PartyTickAction.SetPaused(true)), result.only<PartyTickAction.SetPaused>())
        assertEquals("and jumps to the room's position", listOf(PartyTickAction.Seek(90_000)), result.only<PartyTickAction.Seek>())
        assertEquals(
            "presence says ready on this title",
            listOf(PartyTickAction.ReportStatus(PartyStatusReport(PartyMemberStatus.Ready, media.key, null))),
            result.only<PartyTickAction.ReportStatus>(),
        )
        assertEquals("the applied state is remembered", 1L to "host", result.state.lastSeq)
    }

    @Test
    fun remotePlayAppliesWithClockOffsetAndSeekLead() {
        // Room says: playing from 60 s at server time 1_000_000; it is now 1_004_000 on the party clock.
        val state = settled().copy(seekLeadMs = 400)
        val result = PartyPlayerSyncPolicy.tick(
            state,
            input(playback = playback(seq = 2, positionMs = 60_000, at = 1_000_000), partyNowMs = 1_004_000, positionMs = 20_000, playing = false),
        )
        assertEquals("a paused player resumes for a remote play", listOf(PartyTickAction.SetPaused(false)), result.only<PartyTickAction.SetPaused>())
        assertEquals("seeks to the room's timeline plus the learned lead", listOf(PartyTickAction.Seek(64_400)), result.only<PartyTickAction.Seek>())
        assertTrue("the next correction measures the lead again", result.state.measureSeekLead)
    }

    @Test
    fun remoteRateChangeIsAdopted() {
        val result = PartyPlayerSyncPolicy.tick(settled(), input(playback = playback(seq = 2, rate = 1.5f), rate = 1f))
        assertEquals("the room's speed becomes ours", listOf(PartyTickAction.SetRate(1.5f)), result.only<PartyTickAction.SetRate>())
    }

    @Test
    fun ownEchoWhilePlayingDoesNotSeek() {
        val mine = playback(seq = 2, memberId = "me", positionMs = 55_000)
        val result = PartyPlayerSyncPolicy.tick(settled(), input(playback = mine, positionMs = 60_000))
        assertEquals("our own playing state never yanks us back", emptyList<PartyTickAction.Seek>(), result.only<PartyTickAction.Seek>())
    }

    @Test
    fun hostSeedsTheFirstPlaybackOnlyOnce() {
        val first = PartyPlayerSyncPolicy.tick(PartyTickState(previousKey = media.key), input(playback = null, positionMs = 42_000, playing = true))
        assertEquals(
            "the host publishes where it is",
            listOf(PartyTickAction.Publish(paused = false, positionMs = 42_000, rate = 1f)),
            first.only<PartyTickAction.Publish>(),
        )
        val second = PartyPlayerSyncPolicy.tick(first.state, input(playback = null, positionMs = 42_250))
        assertEquals("and never again", emptyList<PartyTickAction.Publish>(), second.only<PartyTickAction.Publish>())
    }

    @Test
    fun guestNeverSeedsTheFirstPlayback() {
        val result = PartyPlayerSyncPolicy.tick(PartyTickState(previousKey = media.key), input(party = party(hostMemberId = "host"), playback = null))
        assertEquals("only the host seeds", emptyList<PartyTickAction.Publish>(), result.only<PartyTickAction.Publish>())
    }

    @Test
    fun outsideToggleIsPublishedByAMemberWhoMayControl() {
        val state = settled()
        val first = PartyPlayerSyncPolicy.tick(state, input(playback = playback(paused = false), playing = false, monoMs = 10_000))
        assertEquals("a short mismatch waits", emptyList<PartyTickAction>(), first.actions)
        val later = PartyPlayerSyncPolicy.tick(first.state, input(playback = playback(paused = false), playing = false, monoMs = 11_300))
        assertEquals(
            "a lasting local pause is told to the room",
            listOf(PartyTickAction.Publish(paused = true, positionMs = 60_000, rate = 1f)),
            later.only<PartyTickAction.Publish>(),
        )
    }

    @Test
    fun hostOnlyGuestWhoPausesFallsBehindInsteadOfPublishing() {
        val guestParty = party(hostMemberId = "host", hostOnly = true)
        val first = PartyPlayerSyncPolicy.tick(settled(), input(party = guestParty, playing = false, monoMs = 10_000))
        val later = PartyPlayerSyncPolicy.tick(first.state, input(party = guestParty, playing = false, monoMs = 11_300))
        assertEquals("a guest cannot pause the room", emptyList<PartyTickAction.Publish>(), later.only<PartyTickAction.Publish>())
        assertEquals("it pauses for itself", listOf(PartyTickAction.SetBehind(true)), later.only<PartyTickAction.SetBehind>())
    }

    @Test
    fun hostOnlyGuestWhoResumesAgainstAPausedRoomIsPausedBack() {
        val guestParty = party(hostMemberId = "host", hostOnly = true)
        val paused = playback(paused = true)
        val first = PartyPlayerSyncPolicy.tick(settled(), input(party = guestParty, playback = paused, playing = true, monoMs = 10_000))
        val later = PartyPlayerSyncPolicy.tick(first.state, input(party = guestParty, playback = paused, playing = true, monoMs = 11_300))
        assertEquals("the room stays paused", listOf(PartyTickAction.SetPaused(true)), later.only<PartyTickAction.SetPaused>())
        assertEquals("nothing is published", emptyList<PartyTickAction.Publish>(), later.only<PartyTickAction.Publish>())
    }

    @Test
    fun smallDriftIsNudgedAndClearedWhenClose() {
        val nudged = PartyPlayerSyncPolicy.tick(settled(), input(positionMs = 60_400))
        assertEquals("ahead by 400 ms plays 5% slower", listOf(PartyTickAction.Nudge(0.95f)), nudged.only<PartyTickAction.Nudge>())
        val close = PartyPlayerSyncPolicy.tick(nudged.state, input(positionMs = 60_050, monoMs = 10_600, nudgeRate = 0.95f))
        assertEquals("back in sync drops the nudge", listOf(PartyTickAction.ClearNudge(1f)), close.only<PartyTickAction.ClearNudge>())
    }

    @Test
    fun largeDriftSeeksAndRespectsTheSettleWindow() {
        val result = PartyPlayerSyncPolicy.tick(settled(), input(positionMs = 50_000))
        assertEquals("10 s off seeks", listOf(PartyTickAction.Seek(60_000)), result.only<PartyTickAction.Seek>())
        val tooSoon = PartyPlayerSyncPolicy.tick(result.state, input(positionMs = 50_000, monoMs = 11_000))
        assertEquals("no second seek while the first settles", emptyList<PartyTickAction.Seek>(), tooSoon.only<PartyTickAction.Seek>())
    }

    @Test
    fun seekLeadIsLearnedFromHowFarASeekLandedBehind() {
        val state = settled().copy(measureSeekLead = true)
        val result = PartyPlayerSyncPolicy.tick(state, input(positionMs = 59_700))
        assertEquals("landing 300 ms behind aims 300 ms ahead next time", 300L, result.state.seekLeadMs)
        assertFalse("measured once", result.state.measureSeekLead)
    }

    @Test
    fun behindMemberShowsTheGapAndRejoinsWhenClose() {
        val guestParty = party(hostMemberId = "host", hostOnly = true)
        val far = PartyPlayerSyncPolicy.tick(settled(), input(party = guestParty, positionMs = 30_000, behind = true))
        assertEquals("the gap is shown", listOf(PartyTickAction.ShowBehind(30_000)), far.only<PartyTickAction.ShowBehind>())
        assertEquals(
            "presence reports behind, rounded to 5 s",
            PartyStatusReport(PartyMemberStatus.Behind, media.key, 30_000),
            far.state.lastStatus,
        )
        val close = PartyPlayerSyncPolicy.tick(far.state, input(party = guestParty, positionMs = 59_000, behind = true, behindShownMs = 30_000, monoMs = 10_250))
        assertEquals("within 2 s while playing rejoins", listOf(PartyTickAction.SetBehind(false)), close.only<PartyTickAction.SetBehind>())
        assertTrue("and tells the guest", PartyTickAction.CaughtUp in close.actions)
        assertEquals(
            "and re-applies the room state at once",
            listOf(PartyTickAction.SetPaused(false)),
            close.only<PartyTickAction.SetPaused>(),
        )
    }

    @Test
    fun aMemberWhoMayControlIsNeverBehind() {
        val result = PartyPlayerSyncPolicy.tick(settled(), input(behind = true, positionMs = 30_000))
        assertEquals("control wins over behind", listOf(PartyTickAction.SetBehind(false)), result.only<PartyTickAction.SetBehind>())
    }

    @Test
    fun hostMovingToTheNextEpisodeSwitchesTheParty() {
        val result = PartyPlayerSyncPolicy.tick(settled(), input(playerKey = nextMedia.key))
        assertTrue("the host tells the room", PartyTickAction.PublishMediaSwitch in result.actions)
    }

    @Test
    fun guestMovingOnDoesNotSwitchTheParty() {
        val result = PartyPlayerSyncPolicy.tick(settled(), input(party = party(hostMemberId = "host"), playerKey = nextMedia.key))
        assertFalse("only the host changes what's playing", PartyTickAction.PublishMediaSwitch in result.actions)
        assertEquals("and it does not sync a title the room is not watching", emptyList<PartyTickAction.Seek>(), result.only<PartyTickAction.Seek>())
    }

    @Test
    fun backgroundReportsBrowsingAndReappliesOnReturn() {
        val away = PartyPlayerSyncPolicy.tick(settled(), input(inBackground = true, nudgeRate = 1.05f))
        assertEquals(
            "away from the screen",
            PartyMemberStatus.Browsing,
            away.state.lastStatus?.status,
        )
        assertEquals("a nudge never outlives the screen", listOf(PartyTickAction.ClearNudge(1f)), away.only<PartyTickAction.ClearNudge>())
        val back = PartyPlayerSyncPolicy.tick(away.state, input(monoMs = 10_250, playing = false))
        assertEquals("the room state is forced back on", listOf(PartyTickAction.SetPaused(false)), back.only<PartyTickAction.SetPaused>())
    }

    @Test
    fun bufferingIsReportedOnlyAfterASecond() {
        val start = PartyPlayerSyncPolicy.tick(settled(), input(loading = true, monoMs = 10_000))
        assertEquals("a blip stays ready", PartyMemberStatus.Ready, start.state.lastStatus?.status)
        val long = PartyPlayerSyncPolicy.tick(start.state, input(loading = true, monoMs = 11_100))
        assertEquals("a real stall says buffering", PartyMemberStatus.Buffering, long.state.lastStatus?.status)
    }

    @Test
    fun unrenderedPlayerReportsLoadingAndWaits() {
        val result = PartyPlayerSyncPolicy.tick(PartyTickState(previousKey = media.key), input(rendered = false))
        assertEquals("loading until the first frame", PartyMemberStatus.Loading, result.state.lastStatus?.status)
        assertEquals("no seek before the first frame", emptyList<PartyTickAction.Seek>(), result.only<PartyTickAction.Seek>())
    }
}
