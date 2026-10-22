package com.nuvio.tv.core.party

import org.junit.Assert.assertEquals
import org.junit.Test

/** JUnit order: assertEquals(message, expected, actual). */
class PartyChatAvailabilityPolicyTest {

    @Test
    fun theHintStartsHiddenAndAnAnsweredRpcRevealsIt() {
        assertEquals("answered reveals", true, PartyChatAvailabilityPolicy.next(false, PartyChatProbe.Answered))
        assertEquals("answered keeps", true, PartyChatAvailabilityPolicy.next(true, PartyChatProbe.Answered))
    }

    @Test
    fun aMissingFunctionHidesItAgain() {
        assertEquals("missing hides", false, PartyChatAvailabilityPolicy.next(true, PartyChatProbe.Missing))
        assertEquals("PGRST202", PartyChatProbe.Missing, PartyChatAvailabilityPolicy.probeOfFailure("PGRST202", 404))
        assertEquals("bare 404", PartyChatProbe.Missing, PartyChatAvailabilityPolicy.probeOfFailure(null, 404))
    }

    @Test
    fun aNetworkBlipKeepsTheLastAnswer() {
        assertEquals("blip while shown", true, PartyChatAvailabilityPolicy.next(true, PartyChatProbe.Inconclusive))
        assertEquals("blip while hidden", false, PartyChatAvailabilityPolicy.next(false, PartyChatProbe.Inconclusive))
        assertEquals("5xx", PartyChatProbe.Inconclusive, PartyChatAvailabilityPolicy.probeOfFailure("PGRST000", 503))
        assertEquals("not postgrest", PartyChatProbe.Inconclusive, PartyChatAvailabilityPolicy.probeOfFailure(null, null))
    }

    @Test
    fun onlyAWellFormedMessagesBodyCountsAsAnswered() {
        assertEquals("history", PartyChatProbe.Answered, PartyChatAvailabilityPolicy.classifyBody("""{"messages":[]}"""))
        assertEquals("error answer", PartyChatProbe.Inconclusive, PartyChatAvailabilityPolicy.classifyBody("""{"error":"party_ended"}"""))
        assertEquals("garbage", PartyChatProbe.Inconclusive, PartyChatAvailabilityPolicy.classifyBody("<html>"))
    }

    @Test
    fun theKillSwitchOrAMissingPartyBackendHidesTheHintWhateverChatSaid() {
        assertEquals("shown", true, PartyChatAvailabilityPolicy.hintVisible(partyAvailable = true, chatAvailable = true))
        assertEquals("party off", false, PartyChatAvailabilityPolicy.hintVisible(partyAvailable = false, chatAvailable = true))
        assertEquals("chat off", false, PartyChatAvailabilityPolicy.hintVisible(partyAvailable = true, chatAvailable = false))
    }
}
