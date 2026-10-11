package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.MemoryServerTrustStore
import com.nuvio.tv.data.mediaserver.ServerCertTrustPolicy
import com.nuvio.tv.data.mediaserver.ServerCertificateException
import com.nuvio.tv.data.mediaserver.ServerCertificateRefusedException
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerPlainHttpException
import com.nuvio.tv.data.mediaserver.ServerTrust
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

private inline fun <reified T : Throwable> failureOf(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw error
    }
    fail("expected ${T::class.java.simpleName}")
    throw AssertionError()
}

/** A server address typed without a scheme: https first, plain http only after a failure to CONNECT, never silently. */
class ServerAddressFallbackTest {
    private class World(
        val httpsConnects: Boolean,
        val httpsStatusBody: String? = null,
        val certificate: ServerCertTrustPolicy.FailureKind? = null
    ) {
        val trust = ServerTrust(MemoryServerTrustStore())
        val http = TestHttp(
            failure = { request ->
                if (!request.url.isHttps) return@TestHttp null
                if (certificate != null) {
                    trust.check("${request.url.host}:${request.url.port}", false, certificate, "AA")
                    return@TestHttp IOException("handshake refused")
                }
                if (!httpsConnects) IOException("connection refused") else null
            },
            responder = { request ->
                if (request.url.encodedPath.endsWith("/System/Info/Public")) {
                    if (request.url.isHttps && httpsStatusBody != null) httpsStatusBody
                    else """{"Id":"srv1","ServerName":"Den","Version":"10.10.7","ProductName":"Jellyfin Server"}"""
                } else {
                    """{"AccessToken":"tok","ServerId":"srv1","User":{"Id":"u1","Name":"viewer"}}"""
                }
            },
            statusOf = { request -> if (request.url.isHttps && httpsStatusBody != null) 503 else 200 }
        )
        val provider = JellyfinProvider(http.client, testIdentity, trust)
        val schemes get() = http.requests.map { it.url.scheme }
    }

    @Test
    fun httpsAnsweringIsUsedAndNoPlainHttpRequestIsEverMade() = runTest {
        val world = World(httpsConnects = true)
        val signIn = world.provider.signIn("10.0.2.2:18443", "viewer", "pw")
        assertEquals("https://10.0.2.2:18443", signIn.address)
        assertTrue("schemes ${world.schemes}", world.schemes.all { it == "https" })
    }

    @Test
    fun httpsThatCannotConnectAsksBeforePlainHttpAndSendsNoSecret() = runTest {
        val world = World(httpsConnects = false)
        val failure = failureOf<ServerPlainHttpException> { world.provider.signIn("10.0.2.2:8096", "viewer", "pw") }
        assertEquals("http://10.0.2.2:8096", failure.httpAddress)
        assertTrue("no credentials may travel before the user confirmed plain http", world.http.requests.none { it.method == "POST" })
        assertTrue("the plain http probe reaches the server (public info only)", world.http.requests.any { !it.url.isHttps })
    }

    @Test
    fun aCertificateErrorGoesToTheTrustPromptAndNeverToHttp() = runTest {
        val world = World(httpsConnects = false, certificate = ServerCertTrustPolicy.FailureKind.SELF_SIGNED)
        failureOf<ServerCertificateException> { world.provider.signIn("10.0.2.2:18443", "viewer", "pw") }
        assertTrue("schemes ${world.schemes}", world.schemes.none { it == "http" })
    }

    @Test
    fun aRefusalThatCannotBeTrustedStillNeverFallsBackToHttp() = runTest {
        val world = World(httpsConnects = false, certificate = ServerCertTrustPolicy.FailureKind.EXPIRED)
        failureOf<ServerCertificateRefusedException> { world.provider.signIn("10.0.2.2:18443", "viewer", "pw") }
        assertTrue("schemes ${world.schemes}", world.schemes.none { it == "http" })
    }

    @Test
    fun anExplicitHttpAddressIsHonouredAndHttpsIsNeverTried() = runTest {
        val world = World(httpsConnects = false)
        val signIn = world.provider.signIn("http://10.0.2.2:8096", "viewer", "pw")
        assertEquals("http://10.0.2.2:8096", signIn.address)
        assertTrue("schemes ${world.schemes}", world.schemes.none { it == "https" })
    }

    @Test
    fun anHttpsServerThatAnswersWithAnErrorStaysOnHttps() = runTest {
        val world = World(httpsConnects = true, httpsStatusBody = "unavailable")
        val failure = failureOf<ServerException> { world.provider.signIn("nas.local", "viewer", "pw") }
        assertEquals(ServerFailure.UNREACHABLE, failure.failure)
        assertTrue("schemes ${world.schemes}", world.schemes.none { it == "http" })
    }

    @Test
    fun quickConnectAsksBeforePlainHttpToo() = runTest {
        val world = World(httpsConnects = false)
        failureOf<ServerPlainHttpException> { world.provider.quickConnectStart("10.0.2.2:8096") }
        assertTrue("no Initiate before the user confirmed", world.http.requests.none { it.method == "POST" })
    }
}
