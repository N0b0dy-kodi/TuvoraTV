package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.QuickConnectState
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.quickConnectFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire contract of Jellyfin Quick Connect (verified against jellyfin QuickConnectController / UserController). */
class MediaBrowserQuickConnectTest {
    private val requests = mutableListOf<Request>()
    private var approvedAfter = 1
    private var polls = 0
    private var connectStatus = 200

    private val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val path = request.url.encodedPath
        val (code, body) = when {
            path == "/System/Info/Public" -> 200 to """{"ServerName":"Home","Version":"10.10.7","ProductName":"Jellyfin Server","Id":"srv1"}"""
            path == "/QuickConnect/Initiate" -> 200 to """{"Code":"123456","Secret":"abc","Authenticated":false}"""
            path == "/QuickConnect/Connect" -> {
                polls++
                if (connectStatus != 200) connectStatus to "Unknown secret"
                else 200 to """{"Code":"123456","Secret":"abc","Authenticated":${polls >= approvedAfter}}"""
            }
            path == "/Users/AuthenticateWithQuickConnect" ->
                200 to """{"User":{"Id":"u1","Name":"viewer"},"AccessToken":"qc-token","ServerId":"srv1"}"""
            else -> 404 to ""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val jellyfin = JellyfinProvider(http, testIdentity)
    private val emby = EmbyProvider(http, testIdentity)

    @Test
    fun onlyJellyfinOffersQuickConnect() = runTest {
        assertTrue(jellyfin.supportsQuickConnect)
        assertFalse("Emby has no Quick Connect", emby.supportsQuickConnect)
        val failure = runCatching { emby.quickConnectStart("http://nas:8096") }.exceptionOrNull() as ServerException
        assertEquals("Emby refuses without a request", ServerFailure.UNSUPPORTED, failure.failure)
        assertTrue("no request was made for Emby", requests.isEmpty())
    }

    @Test
    fun theFlowUsesTheDocumentedEndpoints() = runTest {
        approvedAfter = 2

        val last = quickConnectFlow(jellyfin, "nas:8096").toList().last() as QuickConnectState.Authorised

        assertEquals("qc-token", last.signIn.token)
        assertEquals("u1", last.signIn.userId)
        val calls = requests.map { it.method + " " + it.url.encodedPath }
        assertEquals(
            listOf(
                "GET /System/Info/Public",
                "POST /QuickConnect/Initiate",
                "GET /QuickConnect/Connect",
                "GET /QuickConnect/Connect",
                "GET /System/Info/Public",
                "POST /Users/AuthenticateWithQuickConnect"
            ),
            calls
        )
        assertEquals("abc", requests.first { it.url.encodedPath == "/QuickConnect/Connect" }.url.queryParameter("secret"))
        assertEquals("""{"Secret":"abc"}""", requests.last().text)
        assertTrue("Initiate is unauthenticated", requests.first { it.url.encodedPath == "/QuickConnect/Initiate" }
            .header("Authorization")!!.let { !it.contains("Token=") })
    }

    @Test
    fun a404OnConnectIsExpiry() = runTest {
        connectStatus = 404

        assertEquals(QuickConnectState.Expired, quickConnectFlow(jellyfin, "nas:8096").toList().last())
    }

    @Test
    fun aDisabledServerSurfacesAsAFailureNotASpinner() = runTest {
        val disabled = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val code = if (path == "/QuickConnect/Initiate") 401 else 200
            val body = if (path == "/System/Info/Public") """{"ServerName":"Home","Version":"10.10.7","Id":"srv1"}""" else ""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val last = quickConnectFlow(JellyfinProvider(disabled, testIdentity), "nas:8096").toList().last() as QuickConnectState.Failed

        assertEquals(ServerFailure.AUTH_REQUIRED, last.error.failure)
        assertTrue(last.error is ServerException)
    }
}
