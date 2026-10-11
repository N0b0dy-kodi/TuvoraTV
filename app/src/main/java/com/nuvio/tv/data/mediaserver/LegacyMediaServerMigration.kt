package com.nuvio.tv.data.mediaserver

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.SecretKey
import kotlin.random.Random
import kotlinx.serialization.json.Json

/** Where the old fork stack kept its data (an interface so the migration runs against a fake in tests). */
internal interface LegacyMediaServerSource {
    /** The profile ids that have an `entries_<id>` blob. */
    fun profileIds(): List<Int>
    fun entriesJson(profileId: Int): String?
    fun trustJson(): String?

    /** The decrypted `cred:<key>` JSON of one server, or null (absent, or not decryptable on this device). */
    fun credentialJson(credentialKey: String): String?

    /** The old per-install MediaBrowser DeviceId every old token is bound to. */
    fun deviceId(): String?

    /** True when any old data exists at all (so a clean install does no work). */
    fun hasAnyData(): Boolean

    /** Removes the old copies (entries, pins, tokens). Called only after the import was verified. */
    fun wipe()
}

internal interface LegacyImportMarker {
    fun isDone(): Boolean
    fun markDone()
}

internal class AndroidLegacyImportMarker(context: Context) : LegacyImportMarker {
    private val preferences = context.getSharedPreferences("media_server_migration", Context.MODE_PRIVATE)
    override fun isDone(): Boolean = preferences.getBoolean(KEY, false)
    override fun markDone() {
        preferences.edit().putBoolean(KEY, true).apply()
    }

    private companion object {
        const val KEY = "legacy_fork_import_v1"
    }
}

internal class AndroidLegacyMediaServerSource(private val context: Context) : LegacyMediaServerSource {
    private val serversName = "nuvio_mediaserver"
    private val tokensName = "nuvio_mediaserver_tokens"

    private fun exists(name: String) = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists()

    override fun hasAnyData(): Boolean = exists(serversName) || exists(tokensName)

    private val servers get() = context.getSharedPreferences(serversName, Context.MODE_PRIVATE)
    private val tokens get() = context.getSharedPreferences(tokensName, Context.MODE_PRIVATE)

    override fun profileIds(): List<Int> =
        servers.all.keys.mapNotNull { key -> key.takeIf { it.startsWith("entries_") }?.removePrefix("entries_")?.toIntOrNull() }.sorted()

    override fun entriesJson(profileId: Int): String? = servers.getString("entries_$profileId", null)

    override fun trustJson(): String? = servers.getString("trust", null)

    override fun credentialJson(credentialKey: String): String? = decrypted("cred:$credentialKey")

    override fun deviceId(): String? = decrypted("device-id")?.takeIf { it.isNotBlank() }

    private fun decrypted(itemKey: String): String? {
        val stored = tokens.getString("item.$itemKey", null) ?: return null
        val key = runCatching {
            (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(LegacyMediaServerImport.KEY_ALIAS, null) as? SecretKey)
        }.getOrNull() ?: return null
        return LegacyMediaServerImport.decryptItem(key, itemKey, stored) { Base64.decode(it, Base64.NO_WRAP) }
    }

    override fun wipe() {
        runCatching { servers.edit().clear().commit() }
        runCatching { tokens.edit().clear().commit() }
    }
}

/**
 * Runs ONCE, at the first launch of a build that no longer has the old fork media-server stack, before anything reads the
 * upstream store: every server the old stack saved on this device (per profile) is imported into the upstream per-profile
 * record, with its token, its pinned certificates and its MediaBrowser DeviceId carried over on this device.
 *
 * Safe to run twice (a marker) and non-destructive until proven: the new store is read back and must hold every imported
 * server (and its token) before the marker is set and the old copies are removed. A failure leaves everything as it was
 * and tries again on the next launch. Anything the old blobs held that cannot be read keeps the old copy in place.
 *
 * Cloud: the old stack synced an address-and-user row per server inside the playlist rows (`iptv_playlists`, source_type
 * jellyfin/emby, never a token). This migration only moves THIS device's local data; the upstream `media_servers` sync then
 * pushes it. The old rows stay on the server untouched and unread (the IPTV push is scoped to its own source types).
 */
internal class LegacyMediaServerMigration(
    private val source: LegacyMediaServerSource,
    private val persistence: ServerPersistence,
    private val trust: ServerTrustStore,
    private val deviceIds: ServerDeviceIdStore,
    private val marker: LegacyImportMarker,
    private val newId: (String) -> String = ::randomId
) {
    sealed interface Outcome {
        data object AlreadyDone : Outcome
        data object NothingToImport : Outcome
        data class Imported(val servers: Int, val tokenless: Int, val pins: Int, val keptLegacyCopy: Boolean) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun runOnce(): Outcome {
        if (marker.isDone()) return Outcome.AlreadyDone
        return try {
            run()
        } catch (error: Exception) {
            Outcome.Failed(error::class.java.simpleName)
        }
    }

    private fun run(): Outcome {
        if (!source.hasAnyData()) {
            marker.markDone()
            return Outcome.NothingToImport
        }
        var clean = true
        val perProfile = source.profileIds().map { profileId ->
            val parsed = source.entriesJson(profileId)?.let(LegacyMediaServerImport::parseEntries)
                ?: LegacyMediaServerImport.ParsedEntries(emptyList(), true)
            if (!parsed.clean) clean = false
            profileId to parsed.entries
        }
        val tokens = HashMap<String, String?>()
        fun tokenFor(entry: LegacyMediaServerImport.LegacyEntry): String? =
            tokens.getOrPut(entry.credentialKey) { LegacyMediaServerImport.parseToken(source.credentialJson(entry.credentialKey)) }

        var servers = 0
        var tokenless = 0
        val expected = mutableMapOf<Int, LegacyMediaServerImport.Mapped>()
        perProfile.forEach { (profileId, entries) ->
            if (entries.isEmpty()) return@forEach
            val raw = persistence.read(profileId)
            val existing = raw?.let { runCatching { json.decodeFromString(StoredServers.serializer(), it) }.getOrNull() }
            // A record this build cannot read is never overwritten: retry next launch.
            if (raw != null && existing == null) return Outcome.Failed("profile $profileId holds unreadable data")
            val mapped = LegacyMediaServerImport.map(entries, ::tokenFor, existing, newId)
            if (mapped.imported > 0) {
                persistence.write(profileId, json.encodeToString(StoredServers.serializer(), mapped.stored))
            }
            servers += mapped.imported
            tokenless += mapped.tokenless
            expected[profileId] = mapped
        }

        // Pins are device-local facts: carry each one the user accepted (never overwrite a pin this build already holds).
        val pins = LegacyMediaServerImport.parseTrust(source.trustJson())
        pins.forEach { (authority, fingerprint) -> if (trust.pinned(authority) == null) trust.pin(authority, fingerprint) }

        // Old tokens are bound to the old per-install DeviceId: keep presenting it (only when none was chosen yet).
        source.deviceId()?.let { if (deviceIds.read() == null) deviceIds.write(it) }

        // Verify before anything old is removed.
        expected.forEach { (profileId, mapped) ->
            val stored = persistence.read(profileId)?.let { runCatching { json.decodeFromString(StoredServers.serializer(), it) }.getOrNull() }
                ?: return Outcome.Failed("profile $profileId not readable back")
            val present = stored.connections.mapTo(mutableSetOf()) { serverKey(it.providerId, it.remoteServerId, it.remoteUserId) }
            if (!present.containsAll(mapped.keys)) return Outcome.Failed("profile $profileId is missing imported servers")
            if (!stored.tokens.keys.containsAll(mapped.stored.tokens.keys)) return Outcome.Failed("profile $profileId is missing imported tokens")
        }
        if (pins.keys.any { trust.pinned(it) == null }) return Outcome.Failed("pins not stored")

        marker.markDone()
        if (clean) source.wipe()
        return Outcome.Imported(servers, tokenless, pins.size, keptLegacyCopy = !clean)
    }

    private companion object {
        fun randomId(prefix: String): String =
            prefix + (0 until 15).joinToString("") { Random.nextInt(16).toString(16) }
    }
}
