package com.nuvio.tv.core.network

import android.app.Application
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Realtime channel joins (private watch-party:<id>, RLS on realtime.messages) must carry the signed-in
 * user's session JWT, never the non-JWT `sb_publishable_…` API key. TV twin of Mobile 615f1bdce/720f18ae8.
 * JUnit order: assertEquals(message, expected, actual).
 *
 * Robolectric, a real `exp` claim and close() are load-bearing: the Auth plugin needs Dispatchers.Main
 * (a bare JVM lacks it), Realtime parses `exp` when a session lands, and a leaked client's failure surfaces
 * in the NEXT suite of the JVM as "uncaught exceptions before the test started" (it broke
 * RealtimeSocketFactoryTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RealtimeAccessTokenTest {
    private val apiKey = "sb_publishable_testkey"

    private fun client() = createSupabaseClient("https://example.test", apiKey) {
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            alwaysAutoRefresh = false
            autoLoadFromStorage = false
        }
        install(Realtime) { accessToken = realtimeAccessToken }
    }

    @Test
    fun signedInChannelsAuthoriseWithTheSessionJwtNotTheApiKey() = runBlocking {
        val client = client()
        try {
            val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEiLCJleHAiOjQxMDI0NDQ4MDB9.sig"
            client.auth.importSession(
                UserSession(accessToken = jwt, refreshToken = "r", expiresIn = 3600, tokenType = "bearer", user = null),
                autoRefresh = false,
            )
            assertEquals("the join token is the session JWT", jwt, client.realtime.config.accessToken(client))
        } finally {
            client.close()
        }
    }

    @Test
    fun signedOutTheProviderGivesNothing() = runBlocking {
        val client = client()
        try {
            assertNull("no session, no token (the library then uses the API key as anon)", client.realtime.config.accessToken(client))
        } finally {
            client.close()
        }
    }
}
