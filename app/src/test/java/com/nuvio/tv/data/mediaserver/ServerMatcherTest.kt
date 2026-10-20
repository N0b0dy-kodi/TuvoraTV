package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMatcherTest {
    private val tmdbService = mockk<TmdbService> {
        coEvery { ensureTmdbId(any(), any(), any()) } returns null
        coEvery { tmdbToImdb(any(), any()) } returns null
    }
    private val metaRepository = mockk<MetaRepository> {
        every { getCachedMeta(any(), any()) } returns null
    }

    private fun matcher(provider: FakeServerProvider = FakeServerProvider()): Triple<ServerMatcher, ServerRepository, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider)
        return Triple(ServerMatcher(repository, tmdbService, metaRepository), repository, connection)
    }

    @Test
    fun buildsRequestsFromCatalogIds() {
        val (matcher, _, _) = matcher()
        val movie = matcher.request("movie", "tt0111161", null, null)!!
        assertEquals(ServerMediaKind.MOVIE, movie.kind)
        assertEquals("tt0111161", movie.ids.imdb)

        val episode = matcher.request("series", "tmdb:1399:2:5", null, null)!!
        assertEquals(1399L, episode.ids.tmdb)
        assertEquals("tmdb:1399", episode.parentId)
        assertEquals(2, episode.season)
        assertEquals(5, episode.episode)
    }

    @Test
    fun acceptsEveryCatalogIdNamespace() {
        val (matcher, _, _) = matcher()
        assertEquals(123L, matcher.request("movie", "kitsu:123", null, null)!!.ids.kitsu)
        assertNull(matcher.request("series", "kitsu:123:5", null, null))
        val episode = matcher.request("series", "kitsu:123:5", 1, 5)!!
        assertEquals(1, episode.season)
        assertEquals(5, episode.episode)
        val index = LibraryIndex(listOf(ServerIndexEntry("a", TrackingExternalIds(kitsu = 123, anilist = 9))))
        assertEquals(listOf("a"), index.lookup(TrackingExternalIds(kitsu = 123)))
        assertTrue(index.lookup(TrackingExternalIds(kitsu = 123, anilist = 8)).isEmpty())
    }

    @Test
    fun skipsRequestsWithoutExactIds() {
        val (matcher, _, _) = matcher()
        assertNull(matcher.request("movie", "someaddon:123", null, null))
        assertNull(matcher.request("movie", ServerItemRef("c1", "x").encode(), null, null))
        assertNull(matcher.request("channel", "tt0111161", null, null))
    }

    @Test
    fun rejectsConflictingIdentifiersButKeepsDuplicateVersions() {
        val index = LibraryIndex(
            listOf(
                ServerIndexEntry("a", TrackingExternalIds(imdb = "tt1", tmdb = 10)),
                ServerIndexEntry("b", TrackingExternalIds(imdb = "tt1", tmdb = 99)),
                ServerIndexEntry("c", TrackingExternalIds(tmdb = 10)),
                ServerIndexEntry("d", TrackingExternalIds(imdb = "tt2"))
            )
        )
        assertEquals(listOf("a", "c"), index.lookup(TrackingExternalIds(imdb = "tt1", tmdb = 10)).sorted())
        assertTrue(index.lookup(TrackingExternalIds(imdb = "tt404")).isEmpty())
    }

    @Test
    fun rejectsEpisodesWithDifferentAirDates() {
        assertTrue(datesCompatible("2011-04-17", "2011-04-18T01:00:00.0000000Z"))
        assertTrue(datesCompatible(null, "2011-04-18"))
        assertFalse(datesCompatible("2011-04-17", "2011-05-01"))
    }

    @Test
    fun matchesMoviesByExactId() = runBlocking {
        val provider = FakeServerProvider().apply {
            indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161")
            indexedIds["43"] = TrackingExternalIds(imdb = "tt0068646")
        }
        val (matcher, _, connection) = matcher(provider)
        val request = matcher.request("movie", "tt0111161", null, null)!!

        assertTrue(matcher.supports(connection, ServerMediaKind.MOVIE))
        assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, request, forceRefresh = false))
    }

    @Test
    fun matchesEpisodesThroughTheMatchedSeries() = runBlocking {
        val provider = FakeServerProvider().apply {
            indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947")
            episodes[1 to 1] = ServerEpisode(FakeServerProvider.EPISODE_ID, "2011-04-17")
        }
        val (matcher, _, connection) = matcher(provider)

        val hit = matcher.request("series", "tt0944947:1:1", 1, 1)!!
        assertEquals(
            listOf(ServerItemRef(connection.id, FakeServerProvider.EPISODE_ID)),
            matcher.match(connection, hit, forceRefresh = false)
        )

        val miss = matcher.request("series", "tt0944947:1:2", 1, 2)!!
        assertTrue(matcher.match(connection, miss, forceRefresh = false).isEmpty())
    }

    @Test
    fun convertsTmdbIdsBeforeMatching() = runBlocking {
        val provider = FakeServerProvider().apply { indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161") }
        coEvery { tmdbService.tmdbToImdb(278, "movie") } returns "tt0111161"
        val (matcher, _, connection) = matcher(provider)

        val request = matcher.request("movie", "tmdb:278", null, null)!!

        assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, request, forceRefresh = false))
    }

    @Test
    fun coldServerDiscoveryStaysPendingWithoutOpeningSettings() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val provider = FakeServerProvider().apply {
            beforeLibraries = { gate.await() }
            indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161")
        }
        val (_, saved) = fakeServerRepository(provider)
        val persistence = MemoryServerPersistence().apply {
            values[1] = kotlinx.serialization.json.Json.encodeToString(StoredServers.serializer(), StoredServers(
                connections = listOf(saved.copy(libraries = emptyList())),
                tokens = mapOf(saved.credentialRef to "fixture-token"), libraryRefreshPending = true
            ))
        }
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val repository = ServerRepository(persistence, listOf(provider), owner)
            repository.ensureLoaded()
            assertEquals(setOf(saved.id), repository.uiState.value.loadingLibraries)
            val streams = ServerStreams(repository, ServerMatcher(repository, tmdbService, metaRepository))
            assertTrue(streams.canServe("movie", "tt0111161"))
            val source = streams.sources("movie", "tt0111161", null, null).single()
            gate.complete(Unit)
            assertEquals("42", source.load().single().serverTarget?.item?.itemId)
            assertEquals(1, provider.libraryLoads)
        } finally {
            gate.complete(Unit)
            owner.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun anUnrelatedServerEditDoesNotInvalidateAValidResponse() = runBlocking {
        val (_, repository, connection) = matcher()
        repository.store(connection.copy(id = "other", credentialRef = "other-token"), "fixture")
        val response = repository.call(connection.id) { _, _ ->
            repository.setEnabled("other", false)
            "valid response"
        }
        assertEquals("valid response", response)
    }

    @Test
    fun aProfileSwitchRejectsTheOldServerResponse() = runBlocking {
        val (_, repository, connection) = matcher()
        try {
            repository.call(connection.id) { _, _ ->
                repository.selectProfile(2)
                "old profile response"
            }
            throw AssertionError("old profile response was accepted")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertTrue(repository.uiState.value.connections.isEmpty())
        }
    }

    @Test
    fun seriesAvailabilityRecognizesAnEligibleServerBeforeChoosingAnEpisode() {
        val (matcher, repository, _) = matcher()
        val streams = ServerStreams(repository, matcher)
        assertTrue(streams.canServe("series", "tt0944947"))
        assertFalse(streams.canServe("series", "unknown:abc"))
    }

    @Test
    fun sharedLookupAddsServerSourcesForCatalogItems() = runBlocking {
        val provider = FakeServerProvider().apply { indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161") }
        val (matcher, repository, connection) = matcher(provider)
        val streams = ServerStreams(repository, matcher)

        val found = streams.sources("movie", "tt0111161", null, null).single().load()
        assertEquals(ServerItemRef(connection.id, "42"), found.single().serverTarget?.item)

        assertTrue(streams.sources("movie", "tt0068646", null, null).single().load().isEmpty())
    }
}
