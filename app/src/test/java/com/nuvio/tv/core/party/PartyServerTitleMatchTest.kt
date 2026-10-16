package com.nuvio.tv.core.party

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media-server titles in a party, keyed by the server's own identity (TV twin of Mobile e195adf3b).
 * JUnit order: assertEquals(message, expected, actual).
 */
class PartyServerTitleMatchTest {
    private fun server(id: String, provider: String = "jellyfin", remote: String = "fe89machine", enabled: Boolean = true) = ServerConnection(
        id = id, providerId = provider, name = "Home", address = "http://192.168.1.5:8096", remoteServerId = remote,
        remoteUserId = "u-$id", userName = "user", credentialRef = "c-$id", enabled = enabled,
    )

    // The host's device stores the Jellyfin as record cc3e...; the guest's device stores the SAME Jellyfin as 77aa...
    private val hostServers = listOf(server("cc3efd306b9f8512"))
    private val guestServers = listOf(server("77aa00000000beef"))
    private val itemId = "fe8927be7a9ffe98ee7a8250b07f3664"

    private fun hostMovie() = PartyMedia(
        type = "movie", metaId = ServerItemRef("cc3efd306b9f8512", itemId).encode(), metaType = "movie", title = "Film",
        source = PartySource(addonId = "server:cc3efd306b9f8512", name = "1080p"),
    )

    private fun local(resolution: PartyServerResolution): PartyMedia {
        assertTrue("expected a local title, got $resolution", resolution is PartyServerResolution.Local)
        return (resolution as PartyServerResolution.Local).media
    }

    @Test
    fun hostMediaCarriesTheServersStableIdentity() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        assertEquals("identity", PartyServerRef(provider = "jellyfin", remoteServerId = "fe89machine", itemId = itemId), shared.server)
        assertEquals("old clients still see the host's own id", hostMovie().metaId, shared.metaId)
    }

    @Test
    fun sameServerOnAnotherAccountResolvesToTheGuestsOwnRecord() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        val media = local(PartyServerTitleMatch.localize(shared, guestServers))
        assertEquals("guest record id", ServerItemRef("77aa00000000beef", itemId).encode(), media.metaId)
        assertEquals("source re-pointed at the guest's server", "server:77aa00000000beef", media.source?.addonId)
    }

    @Test
    fun hostAndGuestComputeTheSamePartyKey() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        val hostKey = PartyServerTitleMatch.playerKey("movie", hostMovie().metaId, null, null, hostServers)
        val guestKey = PartyServerTitleMatch.playerKey("movie", ServerItemRef("77aa00000000beef", itemId).encode(), null, null, guestServers)
        assertEquals("host and guest players agree", hostKey, guestKey)
        assertEquals("and match the shared media", hostKey, shared.key)
    }

    @Test
    fun aGuestNotConnectedToThatServerIsToldSo() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        assertEquals("no servers", PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(shared, emptyList()))
        assertEquals("other machine", PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(shared, listOf(server("x", remote = "another"))))
        assertEquals("disabled", PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(shared, listOf(server("y", enabled = false))))
    }

    @Test
    fun embyAndJellyfinWithTheSameMachineIdAreDifferentServers() {
        val emby = listOf(server("e1", provider = "emby", remote = "embymachine"))
        val embyMovie = PartyMedia(type = "movie", metaId = ServerItemRef("e1", "42").encode(), metaType = "movie")
        val shared = PartyServerTitleMatch.withServer(embyMovie, emby)
        assertEquals("provider", "emby", shared.server?.provider)
        val guest = listOf(server("j1", provider = "jellyfin", remote = "embymachine"), server("e9", provider = "emby", remote = "EMBYMACHINE"))
        assertEquals("the Emby record, case-insensitive", ServerItemRef("e9", "42").encode(), local(PartyServerTitleMatch.localize(shared, guest)).metaId)
    }

    @Test
    fun episodesMapTheEpisodeItemToo() {
        val episode = PartyMedia(
            type = "series", metaId = ServerItemRef("cc3efd306b9f8512", "series1").encode(), metaType = "series",
            videoId = ServerItemRef("cc3efd306b9f8512", "ep7").encode(), season = 1, episode = 7,
        )
        val shared = PartyServerTitleMatch.withServer(episode, hostServers)
        assertEquals("episode item", "ep7", shared.server?.videoItemId)
        assertEquals(
            "guest episode id",
            ServerItemRef("77aa00000000beef", "ep7").encode(),
            local(PartyServerTitleMatch.localize(shared, guestServers)).videoId,
        )
    }

    @Test
    fun aStaleRecordIdLosesToTheServerIdentity() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        val guest = listOf(server("cc3efd306b9f8512", remote = "otherbox"), server("good", remote = "fe89machine"))
        assertEquals("matched by machine id", ServerItemRef("good", itemId).encode(), local(PartyServerTitleMatch.localize(shared, guest)).metaId)
    }

    @Test
    fun anOldHostWithoutTheIdentityWorksOnlyOnItsOwnRecord() {
        assertTrue("same record", PartyServerTitleMatch.localize(hostMovie(), hostServers) is PartyServerResolution.Local)
        assertEquals("other account", PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(hostMovie(), guestServers))
    }

    @Test
    fun catalogTitlesAreUntouched() {
        val catalog = PartyMedia(type = "movie", metaId = "tt0111161", metaType = "movie")
        assertEquals("not a server title", PartyServerResolution.NotServer, PartyServerTitleMatch.localize(catalog, guestServers))
        assertNull("no identity added", PartyServerTitleMatch.withServer(catalog, hostServers).server)
        assertEquals("catalog key unchanged", catalog.key, PartyServerTitleMatch.playerKey("movie", "tt0111161", null, null, hostServers))
        assertNull("unknown record", PartyServerTitleMatch.refOf(ServerItemRef("unknown", "1").encode(), null, hostServers))
    }

    @Test
    fun serverTitlesMayBeWatchedTogetherButLiveAndIptvMayNot() {
        assertTrue("server title", PartyPlayerPolicy.canUseParty("movie", ServerItemRef("cc", "1").encode(), isLive = false, isOwnSource = false))
        assertFalse("IPTV VOD", PartyPlayerPolicy.canUseParty("movie", "xtream:1:vod:9", isLive = false, isOwnSource = true))
        assertFalse("live", PartyPlayerPolicy.canUseParty("live", "xtream:1:live:9", isLive = true, isOwnSource = true))
    }

    @Test
    fun theServerIdentityTravelsOnTheWire() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        val json = PartyApi.json.encodeToString(PartyMedia.serializer(), shared)
        assertTrue("snake_case keys", json.contains("\"server\":{\"provider\":\"jellyfin\",\"remote_server_id\":\"fe89machine\",\"item_id\":\"$itemId\""))
        assertEquals("round trip", shared, PartyApi.json.decodeFromString(PartyMedia.serializer(), json))
    }
}
