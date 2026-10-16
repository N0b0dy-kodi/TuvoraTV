package com.nuvio.tv.core.party

import org.junit.Assert.assertEquals
import org.junit.Test

/** What our backend actually sends. JUnit order: assertEquals(message, expected, actual). */
class PartyServerContractTest {

    @Test
    fun anonymousSessionsAreAskedToSignIn() {
        assertEquals("sign_in_required", PartyError.SignInRequired, partyErrorOf("sign_in_required"))
        assertEquals("unknown codes stay a network error", PartyError.Network, partyErrorOf("not_host"))
        assertEquals("kill switch", PartyError.Unavailable, partyErrorOf("unavailable"))
    }

    @Test
    fun serverEventsWithRealtimeIdsStillDecode() {
        // realtime.send adds an "id" key to every server event payload.
        val playback = PartyApi.json.decodeFromString(
            PartyPlayback.serializer(),
            """{"id":"5f0c","seq":4,"member_id":"m1","paused":false,"position_ms":1000,"rate":1.0,"at":42,"media_key":"movie|tt1||"}""",
        )
        assertEquals("seq survives the extra key", 4L, playback.seq)
        val media = PartyApi.json.decodeFromString(
            PartyMedia.serializer(),
            """{"id":"x","type":"movie","meta_id":"tt1","meta_type":"movie","title":"Film"}""",
        )
        assertEquals("media survives the extra key", "tt1", media.metaId)
        val presence = PartyApi.json.decodeFromString(
            PartyPresence.serializer(),
            """{"id":"y","member_id":"m2","status":"ready"}""",
        )
        assertEquals("presence survives the extra key", "m2", presence.memberId)
    }
}
