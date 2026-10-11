package com.nuvio.tv.data.mediaserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDeviceIdPolicyTest {
    private val legacy = "nuvio-tv-abcdefghijklmnopqrstuvwxyz123456"

    @Test
    fun aStoredServerDeviceIdIsNeverReplaced() {
        assertEquals("tuvora-keep", ServerDeviceIdPolicy.choose(stored = "tuvora-keep", sharedClientId = legacy, hasSignedInServers = true))
        assertEquals(legacy, ServerDeviceIdPolicy.choose(stored = legacy, sharedClientId = legacy, hasSignedInServers = false))
    }

    @Test
    fun anExistingSignedInDeviceKeepsItsIdSoItsTokensStayValid() {
        assertEquals(legacy, ServerDeviceIdPolicy.choose(stored = null, sharedClientId = legacy, hasSignedInServers = true))
    }

    @Test
    fun aNewDeviceGetsTheTuvoraPrefix() {
        val id = ServerDeviceIdPolicy.choose(stored = null, sharedClientId = legacy, hasSignedInServers = false)
        assertTrue(id, id.startsWith("tuvora-tv-"))
        assertEquals("tuvora-tv-abcdefghijklmnopqrstuvwxyz123456", id)
    }

    @Test
    fun anUnknownSharedIdShapeStillGetsAStableTuvoraId() {
        assertEquals("tuvora-tv-custom-id-0123456789", ServerDeviceIdPolicy.choose(null, "custom-id-0123456789", false))
    }

    @Test
    fun theDeviceIdIsChosenOnceAndStored() {
        val store = MemoryDeviceIdStore()
        var signedIn = false
        val device = ServerDeviceId(store, sharedClientId = { legacy }, hasSignedInServers = { signedIn })
        val first = device.current()
        signedIn = true
        assertEquals("tuvora-tv-abcdefghijklmnopqrstuvwxyz123456", first)
        assertEquals("a later sign-in must not change the id the server already knows", first, ServerDeviceId(store, { legacy }, { signedIn }).current())
        assertEquals(first, store.read())
    }

    private class MemoryDeviceIdStore : ServerDeviceIdStore {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(id: String) { value = id }
    }
}
