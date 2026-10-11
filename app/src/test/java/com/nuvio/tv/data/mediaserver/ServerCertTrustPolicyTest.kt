package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.mediaserver.ServerCertTrustPolicy.FailureKind
import com.nuvio.tv.data.mediaserver.ServerCertTrustPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCertTrustPolicyTest {
    private fun decide(
        systemTrusted: Boolean = false,
        failure: FailureKind? = FailureKind.SELF_SIGNED,
        presented: String = "AA",
        pinned: String? = null,
        host: String = "192.168.1.10"
    ) = ServerCertTrustPolicy.decide(systemTrusted, failure, presented, pinned, host)

    @Test
    fun aPubliclyTrustedChainIsAcceptedAndNeverPinned() {
        assertEquals("system trust wins", Verdict.Accept, decide(systemTrusted = true, failure = null, pinned = "OLD"))
    }

    @Test
    fun firstSeenSelfSignedAsksTheUser() {
        assertEquals("first seen asks", Verdict.AskTrustNew("AA"), decide())
    }

    @Test
    fun aMatchingPinIsAccepted() {
        assertEquals("pinned fingerprint matches", Verdict.Accept, decide(pinned = "AA"))
    }

    @Test
    fun aChangedCertificateIsNeverAcceptedSilently() {
        assertEquals(
            "mismatch asks to re-trust",
            Verdict.AskCertificateChanged(pinned = "OLD", presented = "AA"),
            decide(pinned = "OLD")
        )
    }

    @Test
    fun anExpiredCertificateIsRejectedEvenWhenPinned() {
        assertEquals("expired is refused", Verdict.Reject(FailureKind.EXPIRED), decide(failure = FailureKind.EXPIRED, pinned = "AA"))
    }

    @Test
    fun aHostnameMismatchIsOnlyForgivenOnALan() {
        assertEquals(Verdict.AskTrustNew("AA"), decide(failure = FailureKind.HOSTNAME_MISMATCH, host = "192.168.1.10"))
        assertEquals(
            "public host with a wrong name is refused",
            Verdict.Reject(FailureKind.HOSTNAME_MISMATCH),
            decide(failure = FailureKind.HOSTNAME_MISMATCH, host = "media.example.com")
        )
    }

    @Test
    fun anUnknownFailureIsRefused() {
        assertEquals(Verdict.Reject(FailureKind.OTHER), decide(failure = null))
    }

    @Test
    fun trustIsScopedToOneAuthority() {
        val trust = ServerTrust(MemoryServerTrustStore())
        assertFalse("first handshake is refused until the user accepts", trust.check("192.168.1.10:8920", false, FailureKind.SELF_SIGNED, "AA"))
        trust.pin("192.168.1.10:8920", "AA")

        assertTrue("pinned authority passes", trust.check("192.168.1.10:8920", false, FailureKind.SELF_SIGNED, "AA"))
        assertFalse("same cert on another host is not trusted", trust.check("192.168.1.11:8920", false, FailureKind.SELF_SIGNED, "AA"))
        assertFalse("same host another port is not trusted", trust.check("192.168.1.10:9000", false, FailureKind.SELF_SIGNED, "AA"))
    }

    @Test
    fun aChangedFingerprintHardFailsWithARetrustPrompt() {
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.pin("nas.lan:8920", "OLD")

        assertFalse(trust.check("nas.lan:8920", false, FailureKind.SELF_SIGNED, "NEW"))
        val error = trust.takeCertificateError("nas.lan:8920")!!

        assertEquals(ServerFailure.CERTIFICATE_CHANGED, error.failure)
        assertEquals("OLD", error.pinned)
        assertEquals("NEW", error.fingerprint)
        assertEquals("the pin is untouched until the user re-trusts", "OLD", trust.pinned("nas.lan:8920"))
    }

    @Test
    fun anUntrustedCertificateOffersTheFingerprint() {
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.check("nas.lan:8920", false, FailureKind.SELF_SIGNED, "AA")
        val error = trust.takeCertificateError("nas.lan:8920")!!

        assertEquals(ServerFailure.CERTIFICATE_UNTRUSTED, error.failure)
        assertEquals("AA", error.fingerprint)
        assertNull(error.pinned)
        assertNull("reading clears it", trust.takeCertificateError("nas.lan:8920"))
    }

    @Test
    fun anExpiredFailureIsNotOfferedForTrust() {
        val trust = ServerTrust(MemoryServerTrustStore())
        trust.check("nas.lan:8920", false, FailureKind.EXPIRED, "AA")

        assertNull(trust.takeCertificateError("nas.lan:8920"))
    }

    @Test
    fun anExpiredRefusalIsRememberedForThirtySecondsEvenThoughItIsNotOffered() {
        var now = 1_000L
        val trust = ServerTrust(MemoryServerTrustStore(), nowMs = { now })
        assertFalse("nothing refused yet", trust.hasRefusal("nas.lan:8920"))
        trust.check("nas.lan:8920", false, FailureKind.EXPIRED, "AA")

        assertNull(trust.takeCertificateError("nas.lan:8920"))
        assertTrue("reading an unofferable refusal keeps it", trust.hasRefusal("nas.lan:8920"))
        now += 30_001L
        assertFalse("the refusal ages out", trust.hasRefusal("nas.lan:8920"))
    }

    @Test
    fun displayFingerprintIsGroupedHex() {
        assertEquals("AB:CD:EF:01", formatFingerprint("ABCDEF01"))
    }

    @Test
    fun theSyncPayloadNeverCarriesAPin() {
        val keys = listOf(
            SyncedServer("c1", "jellyfin", "Home", "https://nas.lan:8920", "s1", "u1", "viewer", "")
        ).toSyncPayload().toString()

        assertFalse("no fingerprint/pin field syncs", keys.contains("fingerprint", ignoreCase = true) || keys.contains("pin", ignoreCase = true))
    }
}
