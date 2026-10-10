package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerQuickConnectTest {
    private class ScriptedProvider(
        private val polls: (Int) -> Result<Boolean>,
        private val start: () -> Result<ServerQuickConnectRequest> = {
            Result.success(ServerQuickConnectRequest("http://nas:8096", "123456", "secret"))
        },
        private val complete: () -> Result<ServerSignIn> = {
            Result.success(ServerSignIn("http://nas:8096", "Home", "s1", "u1", "viewer", "device-token"))
        },
        private val delegate: ServerProvider = FakeServerProvider()
    ) : ServerProvider by delegate {
        var pollCount = 0
        override val supportsQuickConnect: Boolean = true
        override suspend fun quickConnectStart(address: String) = start().getOrThrow()
        override suspend fun quickConnectApproved(request: ServerQuickConnectRequest): Boolean = polls(pollCount++).getOrThrow()
        override suspend fun quickConnectComplete(request: ServerQuickConnectRequest) = complete().getOrThrow()
    }

    private fun transport() = Result.failure<Boolean>(ServerException(ServerFailure.UNREACHABLE))

    @Test
    fun approvalYieldsASignInAndStops() = runTest {
        val provider = ScriptedProvider(polls = { Result.success(it >= 2) })

        val states = quickConnectFlow(provider, "nas").toList()

        assertEquals("requesting first", QuickConnectState.Requesting, states.first())
        assertTrue("waiting shows the code", states.any { it is QuickConnectState.Waiting && it.code == "123456" })
        assertEquals("completing before done", QuickConnectState.Completing, states[states.size - 2])
        val done = states.last() as QuickConnectState.Authorised
        assertEquals("token comes from the exchange", "device-token", done.signIn.token)
        assertEquals("three polls", 3, provider.pollCount)
    }

    @Test
    fun anUnknownSecretIsAnExpiredTerminalState() = runTest {
        val provider = ScriptedProvider(polls = { Result.failure(ServerException(ServerFailure.NOT_FOUND)) })

        assertEquals(QuickConnectState.Expired, quickConnectFlow(provider, "nas").toList().last())
        assertEquals("no polling after expiry", 1, provider.pollCount)
    }

    @Test
    fun anUnapprovedCodeExpiresAtTheDeadlineInsteadOfPollingForever() = runTest {
        val provider = ScriptedProvider(polls = { Result.success(false) })

        val states = quickConnectFlow(provider, "nas").toList()

        assertEquals(QuickConnectState.Expired, states.last())
        assertTrue("bounded by the request lifetime", currentTime <= QuickConnectCadence.LIFETIME_MS + QuickConnectCadence.STEADY_POLL_MS)
        assertTrue("it did wait out the lifetime", currentTime >= QuickConnectCadence.LIFETIME_MS)
    }

    @Test
    fun repeatedTransportFailuresEndInFailedWithRetry() = runTest {
        val provider = ScriptedProvider(polls = { transport() })

        val last = quickConnectFlow(provider, "nas").toList().last() as QuickConnectState.Failed

        assertEquals(ServerFailure.UNREACHABLE, last.error.failure)
        assertEquals(QuickConnectCadence.MAX_CONSECUTIVE_FAILURES, provider.pollCount)
    }

    @Test
    fun aSuccessfulPollResetsTheFailureCount() = runTest {
        val provider = ScriptedProvider(polls = { i ->
            when {
                i in 0..3 -> transport()
                i == 4 -> Result.success(false)
                i in 5..8 -> transport()
                else -> Result.success(true)
            }
        })

        assertTrue(quickConnectFlow(provider, "nas").toList().last() is QuickConnectState.Authorised)
    }

    @Test
    fun aStartFailureIsTerminalAndCarriesTheCertificatePrompt() = runTest {
        val cert = ServerCertificateException(ServerFailure.CERTIFICATE_UNTRUSTED, "nas:8920", "AA", null)
        val provider = ScriptedProvider(polls = { Result.success(false) }, start = { Result.failure(cert) })

        val last = quickConnectFlow(provider, "nas").toList().last() as QuickConnectState.Failed

        assertEquals(cert, last.error)
        assertEquals("never polled", 0, provider.pollCount)
    }

    @Test
    fun aFailedExchangeIsFailedNotAuthorised() = runTest {
        val provider = ScriptedProvider(
            polls = { Result.success(true) },
            complete = { Result.failure(ServerException(ServerFailure.AUTH_REQUIRED)) }
        )

        val last = quickConnectFlow(provider, "nas").toList().last() as QuickConnectState.Failed

        assertEquals(ServerFailure.AUTH_REQUIRED, last.error.failure)
    }

    @Test
    fun cancellingTheCollectorStopsThePolling() = runTest {
        val provider = ScriptedProvider(polls = { Result.success(false) })
        val job = launch { quickConnectFlow(provider, "nas").collect { } }
        advanceTimeBy(10_000)
        val seen = provider.pollCount
        job.cancel()
        advanceTimeBy(60_000)

        assertTrue("polled while alive", seen > 0)
        assertEquals("no poll after cancel", seen, provider.pollCount)
    }

    @Test
    fun cadenceIsGentleAndLabelled() {
        assertEquals(QuickConnectCadence.FIRST_POLL_MS, QuickConnectCadence.pollDelayMs(0))
        assertEquals(QuickConnectCadence.STEADY_POLL_MS, QuickConnectCadence.pollDelayMs(10))
        assertEquals("123 456", QuickConnectCadence.displayCode("123456"))
        assertEquals("9:40", QuickConnectCadence.countdownLabel(580_000))
        assertEquals("0:00", QuickConnectCadence.countdownLabel(0))
    }
}
