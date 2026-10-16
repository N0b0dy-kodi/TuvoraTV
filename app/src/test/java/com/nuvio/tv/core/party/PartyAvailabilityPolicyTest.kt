package com.nuvio.tv.core.party

import com.nuvio.tv.data.local.OFFICIAL_SERVER_CAPABILITIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit order: assertEquals(message, expected, actual). */
class PartyAvailabilityPolicyTest {

    @Test
    fun aBackendWithoutTheRpcTurnsTheFeatureOff() {
        assertEquals("PGRST202", PartyAvailabilityProbe.Missing, PartyAvailabilityPolicy.classifyFailure("PGRST202", 404))
        assertEquals("undefined function", PartyAvailabilityProbe.Missing, PartyAvailabilityPolicy.classifyFailure("42883", 404))
        assertEquals("bare 404", PartyAvailabilityProbe.Missing, PartyAvailabilityPolicy.classifyFailure(null, 404))
        assertFalse(
            "advertised but missing is unavailable, even if it looked available before",
            PartyAvailabilityPolicy.next(capability = true, current = true, probe = PartyAvailabilityProbe.Missing),
        )
    }

    @Test
    fun transientFailuresKeepTheLastAnswer() {
        assertEquals("server error", PartyAvailabilityProbe.Unreachable, PartyAvailabilityPolicy.classifyFailure("PGRST000", 503))
        assertEquals("not a PostgREST error", PartyAvailabilityProbe.Unreachable, PartyAvailabilityPolicy.classifyFailure(null, null))
        assertTrue("stays on", PartyAvailabilityPolicy.next(true, current = true, probe = PartyAvailabilityProbe.Unreachable))
        assertFalse("stays off", PartyAvailabilityPolicy.next(true, current = false, probe = PartyAvailabilityProbe.Unreachable))
    }

    @Test
    fun theKillSwitchAnswerWins() {
        assertEquals("true", PartyAvailabilityProbe.Answered(true), PartyAvailabilityPolicy.classifyBody(" true\n"))
        assertEquals("false", PartyAvailabilityProbe.Answered(false), PartyAvailabilityPolicy.classifyBody("false"))
        assertEquals("garbage means no contract", PartyAvailabilityProbe.Missing, PartyAvailabilityPolicy.classifyBody("{\"ok\":true}"))
        assertTrue("on", PartyAvailabilityPolicy.next(true, current = false, probe = PartyAvailabilityProbe.Answered(true)))
        assertFalse("off", PartyAvailabilityPolicy.next(true, current = true, probe = PartyAvailabilityProbe.Answered(false)))
    }

    @Test
    fun aServerThatDoesNotAdvertiseTheFeatureIsNeverAvailable() {
        assertFalse(
            "capability gates everything",
            PartyAvailabilityPolicy.next(capability = false, current = true, probe = PartyAvailabilityProbe.Answered(true)),
        )
    }

    @Test
    fun theOfficialBackendAdvertisesWatchParty() {
        assertTrue("watch party is on for the official backend", OFFICIAL_SERVER_CAPABILITIES.watchParty)
    }
}
