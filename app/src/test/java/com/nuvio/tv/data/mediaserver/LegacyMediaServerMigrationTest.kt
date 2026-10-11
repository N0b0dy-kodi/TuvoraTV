package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMediaServerMigrationTest {
    private class FakeSource(
        val entries: Map<Int, String> = mapOf(1 to LegacyFixtures.ENTRIES_PROFILE_1, 2 to LegacyFixtures.ENTRIES_PROFILE_2),
        val trust: String? = LegacyFixtures.TRUST,
        val credentials: Map<String, String> = mapOf(
            "jellyfin:${LegacyFixtures.JELLYFIN_MACHINE}:${LegacyFixtures.JELLYFIN_USER}" to LegacyFixtures.JELLYFIN_CREDENTIAL,
            "emby:${LegacyFixtures.EMBY_MACHINE}:${LegacyFixtures.EMBY_USER}" to LegacyFixtures.EMBY_CREDENTIAL
        ),
        val device: String? = "0123456789abcdef0123456789abcdef"
    ) : LegacyMediaServerSource {
        var wiped = false
        override fun profileIds() = entries.keys.sorted()
        override fun entriesJson(profileId: Int) = entries[profileId]
        override fun trustJson() = trust
        override fun credentialJson(credentialKey: String) = credentials[credentialKey]
        override fun deviceId() = device
        override fun hasAnyData() = entries.isNotEmpty() || credentials.isNotEmpty() || trust != null
        override fun wipe() { wiped = true }
    }

    private class MemoryMarker : LegacyImportMarker {
        var done = false
        override fun isDone() = done
        override fun markDone() { done = true }
    }

    private class MemoryDeviceIds(var id: String? = null) : ServerDeviceIdStore {
        override fun read() = id
        override fun write(id: String) { this.id = id }
    }

    private class FailingPersistence : ServerPersistence {
        override fun read(profileId: Int): String? = null
        override fun write(profileId: Int, value: String?) = throw java.io.IOException("disk full")
        override fun clear() {}
    }

    private val persistence = MemoryServerPersistence()
    private val trust = MemoryServerTrustStore()
    private val deviceIds = MemoryDeviceIds()
    private val marker = MemoryMarker()
    private var counter = 0
    private val newId: (String) -> String = { prefix -> prefix + "id" + (counter++) }

    private fun migration(source: LegacyMediaServerSource, persistence: ServerPersistence = this.persistence) =
        LegacyMediaServerMigration(source, persistence, trust, deviceIds, marker, newId)

    private fun repository(profile: Int = 1) = ServerRepository(
        persistence = persistence,
        providers = emptyList(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        initialProfileId = profile
    ).also { it.ensureLoaded() }

    @Test
    fun seededLegacyStorageEndsUpAsSignedInServersInEveryProfile() {
        val source = FakeSource()

        val outcome = migration(source).runOnce()

        assertEquals(LegacyMediaServerMigration.Outcome.Imported(servers = 3, tokenless = 0, pins = 2, keptLegacyCopy = false), outcome)
        val profileOne = repository(1)
        val connections = profileOne.uiState.value.connections
        assertEquals(listOf("jellyfin", "emby"), connections.map { it.providerId })
        val jellyfin = connections.first { it.providerId == "jellyfin" }
        assertEquals("the token travels, locally", LegacyFixtures.JELLYFIN_TOKEN, profileOne.session(jellyfin.id)?.token)
        assertEquals("https://10.0.2.2:18443", jellyfin.address)
        assertTrue("a disabled server stays disabled", connections.first { it.providerId == "emby" }.let { !it.enabled })
        val profileTwo = repository(2)
        assertEquals("per-profile ownership is kept", 1, profileTwo.uiState.value.connections.size)
        assertEquals(
            "the same server and user is signed in under both profiles (one credential per server and user)",
            LegacyFixtures.JELLYFIN_TOKEN,
            profileTwo.session(profileTwo.uiState.value.connections.single().id)?.token
        )
        assertTrue(marker.done)
        assertTrue("the old copies are removed only after the new store verifiably has the data", source.wiped)
    }

    @Test
    fun aServerWhoseCredentialCannotBeReadLandsOnSignInAgainWithAddressAndUserKept() {
        val source = FakeSource(
            credentials = mapOf("emby:${LegacyFixtures.EMBY_MACHINE}:${LegacyFixtures.EMBY_USER}" to LegacyFixtures.EMBY_CREDENTIAL)
        )

        val outcome = migration(source).runOnce()

        assertEquals(LegacyMediaServerMigration.Outcome.Imported(servers = 3, tokenless = 2, pins = 2, keptLegacyCopy = false), outcome)
        val repository = repository(1)
        val jellyfin = repository.uiState.value.connections.first { it.providerId == "jellyfin" }
        assertNull(repository.session(jellyfin.id))
        assertEquals(ServerFailure.AUTH_REQUIRED, repository.uiState.value.failures[jellyfin.id])
        assertEquals("tuvora_test", jellyfin.userName)
        assertEquals("https://10.0.2.2:18443", jellyfin.address)
    }

    @Test
    fun pinsAndTheDeviceIdAreCarriedOnThisDevice() {
        migration(FakeSource()).runOnce()

        assertEquals(LegacyFixtures.FINGERPRINT, trust.pinned("10.0.2.2:18443"))
        assertEquals(LegacyFixtures.FINGERPRINT, trust.pinned("10.0.2.2:18445"))
        assertEquals("tokens are bound to the old DeviceId", "0123456789abcdef0123456789abcdef", deviceIds.id)
    }

    @Test
    fun aDeviceIdAlreadyChosenIsNeverReplaced() {
        deviceIds.id = "tuvora-tv-existing"
        migration(FakeSource()).runOnce()

        assertEquals("tuvora-tv-existing", deviceIds.id)
    }

    @Test
    fun runningAgainDoesNothing() {
        migration(FakeSource()).runOnce()
        val after = persistence.values.toMap()

        val again = migration(FakeSource()).runOnce()

        assertEquals(LegacyMediaServerMigration.Outcome.AlreadyDone, again)
        assertEquals(after, persistence.values)
    }

    @Test
    fun aFreshInstallHasNothingToImportAndIsMarkedDone() {
        val outcome = migration(FakeSource(entries = emptyMap(), trust = null, credentials = emptyMap(), device = null)).runOnce()

        assertEquals(LegacyMediaServerMigration.Outcome.NothingToImport, outcome)
        assertTrue(marker.done)
        assertTrue(persistence.values.isEmpty())
    }

    @Test
    fun aFailedWriteLeavesTheOldDataAndTriesAgainNextLaunch() {
        val source = FakeSource()

        val outcome = migration(source, FailingPersistence()).runOnce()

        assertTrue(outcome.toString(), outcome is LegacyMediaServerMigration.Outcome.Failed)
        assertFalse("no marker: it runs again", marker.done)
        assertFalse("nothing old was removed", source.wiped)
        assertEquals("then it works", true, migration(source).runOnce() is LegacyMediaServerMigration.Outcome.Imported)
    }

    @Test
    fun aRecordTheNewStoreCannotReadIsNeverOverwritten() {
        persistence.values[1] = "{corrupt"
        val source = FakeSource()

        val outcome = migration(source).runOnce()

        assertTrue(outcome is LegacyMediaServerMigration.Outcome.Failed)
        assertEquals("{corrupt", persistence.values[1])
        assertFalse(source.wiped)
        assertFalse(marker.done)
    }

    @Test
    fun anUnreadableLegacyEntryKeepsTheOldCopyButStillImportsTheRest() {
        val damaged = FakeSource(entries = mapOf(1 to LegacyFixtures.ENTRIES_PROFILE_1.replace("\"type\":\"emby\"", "\"type\":\"plex\"")))

        val outcome = migration(damaged).runOnce()

        assertEquals(LegacyMediaServerMigration.Outcome.Imported(servers = 1, tokenless = 0, pins = 2, keptLegacyCopy = true), outcome)
        assertFalse("something could not be read: the old copy stays", damaged.wiped)
        assertTrue(marker.done)
    }

    @Test
    fun serversTheNewStoreAlreadyHasAreNotDuplicated() {
        migration(FakeSource()).runOnce()
        marker.done = false
        val first = persistence.values.getValue(1)

        migration(FakeSource()).runOnce()

        assertEquals("same provider, server and user: nothing added", first, persistence.values.getValue(1))
        assertEquals(2, repository(1).uiState.value.connections.size)
    }

    @Test
    fun theImportedRecordsAskForOneLibraryRefreshAndAreQueuedForSync() {
        migration(FakeSource()).runOnce()

        val stored = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(StoredServers.serializer(), persistence.values.getValue(1))
        assertTrue(stored.pendingPush)
        assertTrue(stored.libraryRefreshPending)
    }

    @Test
    fun theLibrariesOfAnImportedServerAreFetchedOnceOnLoad() {
        val provider = FakeServerProvider()
        val stored = StoredServers(
            connections = listOf(
                ServerConnection(
                    id = "c1", providerId = provider.id, name = "Box", address = "https://fake.example",
                    remoteServerId = "s1", remoteUserId = "u1", userName = "viewer", credentialRef = "k1"
                )
            ),
            tokens = mapOf("k1" to "token"),
            libraryRefreshPending = true
        )
        persistence.values[1] = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(StoredServers.serializer(), stored)

        val repository = ServerRepository(persistence, listOf(provider), CoroutineScope(Dispatchers.Unconfined))
        repository.ensureLoaded()

        assertEquals(listOf("10", "20"), repository.uiState.value.connections.single().libraries.map { it.id })
        val after = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(StoredServers.serializer(), persistence.values.getValue(1))
        assertFalse("one pass, not a loop", after.libraryRefreshPending)
    }
}
