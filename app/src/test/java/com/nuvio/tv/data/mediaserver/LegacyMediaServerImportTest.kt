package com.nuvio.tv.data.mediaserver

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMediaServerImportTest {
    private fun ids(prefix: String) = prefix + "x"

    @Test
    fun readsTheEntriesTheReleasedStackSerialised() {
        val parsed = LegacyMediaServerImport.parseEntries(LegacyFixtures.ENTRIES_PROFILE_1)

        assertTrue("a clean blob is fully read", parsed.clean)
        assertEquals(2, parsed.entries.size)
        val jellyfin = parsed.entries[0]
        assertEquals("jellyfin", jellyfin.type)
        assertEquals(LegacyFixtures.JELLYFIN_MACHINE, jellyfin.machineId)
        assertEquals(LegacyFixtures.JELLYFIN_USER, jellyfin.userId)
        assertEquals("Living room", jellyfin.name)
        assertEquals("https://10.0.2.2:18443", jellyfin.address)
        assertEquals("tuvora_test", jellyfin.userName)
        assertTrue(jellyfin.enabled)
        val emby = parsed.entries[1]
        assertEquals("emby", emby.type)
        assertFalse("a disabled server stays disabled", emby.enabled)
    }

    @Test
    fun anEntryWithoutAnAddressIsStillRead() {
        val entry = LegacyMediaServerImport.parseEntries(LegacyFixtures.ENTRIES_PROFILE_2).entries.single()
        assertNull(entry.address)
        assertNull(entry.userName)
        assertEquals("Living room", entry.name)
    }

    @Test
    fun anUnreadableEntryIsReportedSoTheOldCopyIsKept() {
        val raw = """{"version":1,"entries":[{"type":"jellyfin","machineId":"m","userId":"u","name":"A"},{"type":"plex","machineId":"x","userId":"y"}]}"""
        val parsed = LegacyMediaServerImport.parseEntries(raw)

        assertEquals(1, parsed.entries.size)
        assertFalse(parsed.clean)
        assertFalse(LegacyMediaServerImport.parseEntries("{not json").clean)
        assertTrue(LegacyMediaServerImport.parseEntries("{not json").entries.isEmpty())
    }

    @Test
    fun readsThePinsAndTheCredential() {
        val pins = LegacyMediaServerImport.parseTrust(LegacyFixtures.TRUST)
        assertEquals(LegacyFixtures.FINGERPRINT, pins["10.0.2.2:18443"])
        assertEquals(2, pins.size)
        assertTrue(LegacyMediaServerImport.parseTrust("garbage").isEmpty())
        assertTrue(LegacyMediaServerImport.parseTrust(null).isEmpty())

        assertEquals(LegacyFixtures.JELLYFIN_TOKEN, LegacyMediaServerImport.parseToken(LegacyFixtures.JELLYFIN_CREDENTIAL))
        assertEquals(LegacyFixtures.EMBY_TOKEN, LegacyMediaServerImport.parseToken(LegacyFixtures.EMBY_CREDENTIAL))
        assertNull(LegacyMediaServerImport.parseToken("""{"accessToken":" "}"""))
        assertNull(LegacyMediaServerImport.parseToken("{broken"))
        assertNull(LegacyMediaServerImport.parseToken(null))
    }

    @Test
    fun mapsEveryServerAndCarriesTheTokenLocally() {
        val entries = LegacyMediaServerImport.parseEntries(LegacyFixtures.ENTRIES_PROFILE_1).entries
        var counter = 0
        val mapped = LegacyMediaServerImport.map(
            legacy = entries,
            tokenFor = { if (it.type == "jellyfin") LegacyFixtures.JELLYFIN_TOKEN else LegacyFixtures.EMBY_TOKEN },
            existing = null,
            newId = { prefix -> prefix + (counter++) }
        )

        assertEquals(2, mapped.imported)
        assertEquals(0, mapped.tokenless)
        val jellyfin = mapped.stored.connections.first { it.providerId == "jellyfin" }
        assertEquals(LegacyFixtures.JELLYFIN_MACHINE, jellyfin.remoteServerId)
        assertEquals(LegacyFixtures.JELLYFIN_USER, jellyfin.remoteUserId)
        assertEquals("tuvora_test", jellyfin.userName)
        assertEquals("https://10.0.2.2:18443", jellyfin.address)
        assertEquals(LegacyFixtures.JELLYFIN_TOKEN, mapped.stored.tokens[jellyfin.credentialRef])
        assertFalse(mapped.stored.connections.first { it.providerId == "emby" }.enabled)
        assertTrue("the normal sync pushes what was imported", mapped.stored.pendingPush)
        assertTrue("libraries are fetched once", mapped.stored.libraryRefreshPending)
        assertTrue(mapped.stored.connections.all { it.libraries.isEmpty() })
    }

    @Test
    fun aServerWhoseTokenCannotBeReadIsKeptAndAsksForASignIn() {
        val entries = LegacyMediaServerImport.parseEntries(LegacyFixtures.ENTRIES_PROFILE_1).entries
        val mapped = LegacyMediaServerImport.map(entries, tokenFor = { null }, existing = null, newId = ::ids)

        assertEquals("never lose the server entry", 2, mapped.stored.connections.size)
        assertEquals(2, mapped.tokenless)
        assertTrue(mapped.stored.tokens.isEmpty())
        assertEquals("address and user stay prefilled", "https://10.0.2.2:18443", mapped.stored.connections[0].address)
        assertEquals("tuvora_test", mapped.stored.connections[0].userName)
    }

    @Test
    fun aServerWithoutAnAddressKeepsAnEmptyOneInsteadOfBeingDropped() {
        val entries = LegacyMediaServerImport.parseEntries(LegacyFixtures.ENTRIES_PROFILE_2).entries
        val mapped = LegacyMediaServerImport.map(entries, tokenFor = { null }, existing = null, newId = ::ids)

        assertEquals("", mapped.stored.connections.single().address)
        assertEquals("", mapped.stored.connections.single().userName)
    }

    @Test
    fun anExistingServerOfTheSameProviderServerAndUserIsLeftAsItIs() {
        val entries = LegacyMediaServerImport.parseEntries(LegacyFixtures.ENTRIES_PROFILE_1).entries
        val existing = StoredServers(
            connections = listOf(
                ServerConnection(
                    id = "c-kept", providerId = "jellyfin", name = "Already here", address = "https://new.example",
                    remoteServerId = LegacyFixtures.JELLYFIN_MACHINE, remoteUserId = LegacyFixtures.JELLYFIN_USER,
                    userName = "tuvora_test", credentialRef = "k-kept"
                )
            ),
            tokens = mapOf("k-kept" to "fresh-token")
        )
        val mapped = LegacyMediaServerImport.map(entries, tokenFor = { "legacy" }, existing = existing, newId = ::ids)

        assertEquals("only the emby server is new", 1, mapped.imported)
        assertEquals(2, mapped.stored.connections.size)
        assertEquals("Already here", mapped.stored.connections.first { it.providerId == "jellyfin" }.name)
        assertEquals("fresh-token", mapped.stored.tokens["k-kept"])
        assertEquals("c-kept", mapped.stored.connections.first { it.providerId == "jellyfin" }.id)
    }

    @Test
    fun nothingNewLeavesTheRecordUntouched() {
        val entries = LegacyMediaServerImport.parseEntries(LegacyFixtures.ENTRIES_PROFILE_2).entries
        val existing = StoredServers(
            connections = listOf(
                ServerConnection(
                    id = "c1", providerId = "jellyfin", name = "Here", address = "https://a",
                    remoteServerId = LegacyFixtures.JELLYFIN_MACHINE, remoteUserId = LegacyFixtures.JELLYFIN_USER,
                    userName = "u", credentialRef = "k1"
                )
            ),
            tokens = mapOf("k1" to "t")
        )
        val mapped = LegacyMediaServerImport.map(entries, tokenFor = { "x" }, existing = existing, newId = ::ids)

        assertEquals(0, mapped.imported)
        assertEquals(existing, mapped.stored)
    }

    @Test
    fun decryptsTheKeystoreItemFormatOfTheOldTokenStore() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val stored = encryptLikeTheOldStack(key, "cred:jellyfin:m:u", LegacyFixtures.JELLYFIN_CREDENTIAL)

        assertEquals(LegacyFixtures.JELLYFIN_CREDENTIAL, LegacyMediaServerImport.decryptItem(key, "cred:jellyfin:m:u", stored, Base64.getDecoder()::decode))
        assertNull("bound to its own item key", LegacyMediaServerImport.decryptItem(key, "cred:jellyfin:m:OTHER", stored, Base64.getDecoder()::decode))
        assertNull("another key", LegacyMediaServerImport.decryptItem(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(), "cred:jellyfin:m:u", stored, Base64.getDecoder()::decode))
        assertNull("not the item format", LegacyMediaServerImport.decryptItem(key, "cred:x", "nonsense", Base64.getDecoder()::decode))
    }

    companion object {
        /** The old `PlatformSecureTokenStore.write`, byte for byte: AES/GCM/NoPadding, AAD `<alias>:<item key>`, `<b64 iv>.<b64 ciphertext>`. */
        fun encryptLikeTheOldStack(key: SecretKey, itemKey: String, value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD("${LegacyMediaServerImport.KEY_ALIAS}:$itemKey".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val encoder = Base64.getEncoder()
            return "${encoder.encodeToString(cipher.iv)}.${encoder.encodeToString(encrypted)}"
        }
    }
}
