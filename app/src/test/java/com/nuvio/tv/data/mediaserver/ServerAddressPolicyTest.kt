package com.nuvio.tv.data.mediaserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressPolicyTest {
    @Test
    fun anExplicitSchemeIsHonouredAndTriedAlone() {
        assertEquals(listOf("http://10.0.2.2:8096"), ServerAddressPolicy.attempts("http://10.0.2.2:8096"))
        assertEquals(listOf("https://nas.local:8920/jf"), ServerAddressPolicy.attempts(" https://nas.local:8920/jf "))
    }

    @Test
    fun noSchemeTriesHttpsFirstAndPlainHttpOnlyAfterIt() {
        assertEquals(
            listOf("https://10.0.2.2:18443", "http://10.0.2.2:18443"),
            ServerAddressPolicy.attempts("10.0.2.2:18443")
        )
    }

    @Test
    fun theJellyfinAndEmbyPortConventionsNeverBypassHttpsFirst() {
        assertEquals("https://host:8096", ServerAddressPolicy.attempts("host:8096").first())
        assertEquals("https://host:8920", ServerAddressPolicy.attempts("host:8920").first())
        assertEquals("https://host", ServerAddressPolicy.attempts("host").first())
    }

    @Test
    fun blankInputHasNoAttempts() {
        assertTrue(ServerAddressPolicy.attempts("   ").isEmpty())
    }

    @Test
    fun onlyAFailureToConnectFallsBackToPlainHttp() {
        assertTrue(ServerAddressPolicy.fallsBackToPlainHttp(ServerAddressPolicy.Outcome.CONNECT_FAILED))
        assertFalse(
            "a certificate error leads to the trust prompt, never to http",
            ServerAddressPolicy.fallsBackToPlainHttp(ServerAddressPolicy.Outcome.CERTIFICATE)
        )
        assertFalse(
            "a server that answered over https stays on https",
            ServerAddressPolicy.fallsBackToPlainHttp(ServerAddressPolicy.Outcome.SERVER_ANSWERED)
        )
    }

    @Test
    fun theErrorsAreClassified() {
        assertEquals(
            ServerAddressPolicy.Outcome.CERTIFICATE,
            ServerAddressPolicy.outcomeOf(ServerCertificateException(ServerFailure.CERTIFICATE_UNTRUSTED, "h:1", "AA", null))
        )
        assertEquals(
            "refused but not offerable (expired)",
            ServerAddressPolicy.Outcome.CERTIFICATE,
            ServerAddressPolicy.outcomeOf(ServerCertificateRefusedException())
        )
        assertEquals(ServerAddressPolicy.Outcome.CONNECT_FAILED, ServerAddressPolicy.outcomeOf(ServerConnectException()))
        assertEquals(
            ServerAddressPolicy.Outcome.SERVER_ANSWERED,
            ServerAddressPolicy.outcomeOf(ServerException(ServerFailure.UNREACHABLE, "HTTP 503"))
        )
        assertEquals(
            ServerAddressPolicy.Outcome.SERVER_ANSWERED,
            ServerAddressPolicy.outcomeOf(ServerException(ServerFailure.NOT_FOUND))
        )
    }

    @Test
    fun thePlainHttpAddressOfferedToTheUserIsTheSameHostAndPort() {
        assertEquals("http://10.0.2.2:18443", ServerAddressPolicy.plainHttpOf("https://10.0.2.2:18443"))
        assertNull("already plain", ServerAddressPolicy.plainHttpOf("http://10.0.2.2:18443"))
    }
}
