package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.*
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

/** Regression fixtures for empty and on-demand server item source listings. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceDiscoveryTest {
    private val session = ServerSession(ServerConnection("repro", "jellyfin", "Repro", "https://fixture.invalid", "s", "u", "u", "k"), "fixture-token")
    private val playable = """{"Id":"m","Name":"Movie","Type":"Movie","MediaSources":[{"Id":"source","Name":"1080p","SupportsDirectPlay":true,"Container":"mp4"}]}"""
    private val empty = """{"Id":"m","Name":"Movie","Type":"Movie","MediaSources":[]}"""
    private val placeholder = """{"Id":"m","Name":"Movie","Type":"Movie","MediaSources":[{"Id":"p","Type":"Placeholder"}]}"""
    private val info = """{"MediaSources":[{"Id":"source","Name":"1080p","SupportsDirectPlay":true,"Container":"mp4"}]}"""

    @Test fun populatedItemDoesNotRequestPlaybackInfo() = runBlocking {
        val h = TestHttp { playable }
        assertEquals("source", JellyfinProvider(h.client, testIdentity).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(1, h.requests.size)
    }

    @Test fun emptyItemDiscoversSourcesWithoutStartingPlayback() = runBlocking {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) info else empty }
        assertEquals("source", JellyfinProvider(h.client, testIdentity).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(2, h.requests.size)
        val request = h.requests.last()
        assertTrue(request.url.encodedPath.endsWith("/PlaybackInfo"))
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.text).jsonObject
        assertEquals(false, body.getValue("IsPlayback").jsonPrimitive.boolean)
        assertEquals(false, body.getValue("AutoOpenLiveStream").jsonPrimitive.boolean)
        assertFalse("MediaSourceId" in body)
    }

    @Test fun placeholderItemDiscoversSourcesOnce() = runBlocking {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) info else placeholder }
        assertEquals("source", JellyfinProvider(h.client, testIdentity).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(2, h.requests.size)
    }

    @Test fun mixedListingUsesRealSourceWithoutLookup() = runBlocking {
        val h = TestHttp { playable.replace("[{", "[{\"Id\":\"p\",\"Type\":\"Placeholder\"},{") }
        assertEquals("source", JellyfinProvider(h.client, testIdentity).candidates(session, "m").single().target.mediaSourceId)
        assertEquals(1, h.requests.size)
    }

    @Test fun emptyPlaybackInfoEndsEmptyWithoutRetrying() = runBlocking {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) """{"MediaSources":[]}""" else empty }
        assertTrue(JellyfinProvider(h.client, testIdentity).candidates(session, "m").isEmpty())
        assertEquals(2, h.requests.size)
    }

    @Test fun playbackInfoErrorIsNotAnEmptyResult() = runBlocking {
        val h = TestHttp { if (it.url.encodedPath.endsWith("/PlaybackInfo")) """{"ErrorCode":"NotAllowed"}""" else empty }
        val error = try {
            JellyfinProvider(h.client, testIdentity).candidates(session, "m")
            throw AssertionError("Expected a server error")
        } catch (error: ServerException) { error }
        assertEquals(ServerFailure.FORBIDDEN, error.failure)
        assertEquals(2, h.requests.size)
    }

    @Test fun lookupFailureIsNotAnEmptyResult() = runBlocking {
        val h = TestHttp(failure = {
            if (it.url.encodedPath.endsWith("/PlaybackInfo")) java.io.IOException("fixture disconnect") else null
        }) { placeholder }
        val error = try {
            JellyfinProvider(h.client, testIdentity).candidates(session, "m")
            throw AssertionError("Expected a server error")
        } catch (error: ServerException) { error }
        assertEquals(ServerFailure.UNREACHABLE, error.failure)
    }

    @Test fun lookupDecisionCoversEmptyPlaceholderAndMixedLists() {
        assertTrue(VersionListPolicy.needsPlaybackInfo(emptyList()))
        assertTrue(VersionListPolicy.needsPlaybackInfo(listOf(MediaSource(id = "p", type = "Placeholder"))))
        assertFalse(VersionListPolicy.needsPlaybackInfo(listOf(MediaSource(id = "r"))))
    }
    @Test fun stalledLookupEndsWithAnError() = checkStalledLookup(cancel = false)

    @Test fun callerCanCancelStalledLookup() = checkStalledLookup(cancel = true)

    private fun checkStalledLookup(cancel: Boolean) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val h = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Fixture was not released" }
                info
            } else empty
        }
        val scheduler = TestCoroutineScheduler()
        // Drive virtual time explicitly: real OkHttp callbacks must arrive before the clock advances.
        val pending = async(StandardTestDispatcher(scheduler)) {
            runCatching { JellyfinProvider(h.client, testIdentity).candidates(session, "m") }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (entered.count > 0 && System.nanoTime() < deadline) {
                scheduler.runCurrent()
                Thread.sleep(1)
            }
            assertEquals(0L, entered.count)
            if (cancel) {
                pending.cancel()
                scheduler.runCurrent()
                assertTrue(pending.isCancelled)
            } else {
                scheduler.advanceTimeBy(30_000)
                scheduler.runCurrent()
                val error = pending.await().exceptionOrNull()
                assertTrue(error is ServerException)
                assertEquals(ServerFailure.UNREACHABLE, (error as ServerException).failure)
            }
        } finally {
            release.countDown()
            pending.cancel()
            scheduler.runCurrent()
        }
    }

}
