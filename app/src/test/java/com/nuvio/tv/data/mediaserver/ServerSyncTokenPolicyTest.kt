package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Media-server access tokens are per-device credentials: they never leave the device and never arrive from one. */
class ServerSyncTokenPolicyTest {
    private fun synced(id: String, token: String, remoteUserId: String = "u1") = SyncedServer(
        id = id,
        providerId = "jellyfin",
        name = "Home",
        address = "http://192.168.1.10:8096",
        remoteServerId = "s1",
        remoteUserId = remoteUserId,
        userName = "viewer",
        token = token
    )

    @Test
    fun pushedPayloadCarriesNoToken() {
        val entry = listOf(synced("c1", "secret-token")).toSyncPayload().jsonArray.single().jsonObject

        assertEquals("pushed token must be empty", "", entry["token"]?.jsonPrimitive?.contentOrNull.orEmpty())
        assertEquals("address still syncs", "http://192.168.1.10:8096", entry["address"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user name still syncs", "viewer", entry["user_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("server id still syncs", "s1", entry["remote_server_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun emptyRemoteTokenKeepsTheLocalOne() {
        val merged = mergeSyncedServers(
            local = listOf(synced("local-id", "local-token")),
            remote = listOf(synced("remote-id", "")),
            syncedKeys = null
        )

        assertEquals("one server after merge", 1, merged.size)
        assertEquals("local token survives", "local-token", merged.single().token)
        assertEquals("local id survives", "local-id", merged.single().id)
    }

    @Test
    fun aRemoteTokenIsNeverAdopted() {
        val merged = mergeSyncedServers(
            local = emptyList(),
            remote = listOf(synced("remote-id", "legacy-token-from-another-client")),
            syncedKeys = null
        )

        assertEquals("a server from elsewhere lands without a token", "", merged.single().token)
    }

    @Test
    fun aServerFromAnotherDeviceLandsInSignInAgainState() {
        val persistence = MemoryServerPersistence()
        val repository = ServerRepository(persistence, emptyList(), CoroutineScope(Dispatchers.Unconfined))
        repository.store(
            ServerConnection(
                id = "c1", providerId = "jellyfin", name = "Home", address = "http://192.168.1.10:8096",
                remoteServerId = "s1", remoteUserId = "u1", userName = "viewer", credentialRef = "k1"
            ),
            "local-token"
        )
        val snapshot = checkNotNull(repository.syncSnapshot(1))
        val remote = listOf(synced("r1", "", "u1"), synced("r2", "", "u2"))
        val merged = mergeSyncedServers(snapshot.servers, remote, snapshot.syncedKeys)

        repository.applySync(snapshot, merged, remote.map { it.key }.toSet())

        assertEquals("this device's token is untouched", "local-token", repository.session("c1")?.token)
        val arrived = repository.uiState.value.connections.single { it.remoteUserId == "u2" }
        assertNull("no token stored for the arrived server", repository.session(arrived.id))
        assertEquals(
            "arrived server asks for sign-in on this device",
            ServerFailure.AUTH_REQUIRED,
            repository.uiState.value.failures[arrived.id]
        )
        assertNotNull("name and address synced", arrived.address)
    }

    @Test
    fun aTokenlessServerStaysInTheSyncSnapshot() {
        val repository = ServerRepository(MemoryServerPersistence(), emptyList(), CoroutineScope(Dispatchers.Unconfined))
        val snapshot0 = checkNotNull(repository.syncSnapshot(1))
        val remote = listOf(synced("r2", "", "u2"))
        repository.applySync(snapshot0, mergeSyncedServers(snapshot0.servers, remote, snapshot0.syncedKeys), setOf(remote.single().key))

        val snapshot = checkNotNull(repository.syncSnapshot(1))

        assertEquals("tokenless server is not dropped (would be deleted remotely)", 1, snapshot.servers.size)
    }
}
