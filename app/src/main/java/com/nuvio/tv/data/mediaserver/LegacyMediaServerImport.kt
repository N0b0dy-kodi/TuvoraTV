package com.nuvio.tv.data.mediaserver

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One-time import of the media servers the previously RELEASED fork stack (TV 1.13.0 - 1.13.5, package
 * `core.mediaserver`, since deleted) saved on this device. Self-contained on purpose: it reads the OLD stored bytes
 * without any of the deleted classes, so the stack can stay gone.
 *
 * The old storage, per device (all of it local; only an address-and-user row ever synced, see [LegacyMediaServerMigration]):
 *  - SharedPreferences `nuvio_mediaserver`: `entries_<profileId>` = `{"version":1,"entries":[entry...]}` and `trust` =
 *    `{"host:port":"SHA256HEX"}` (the TOFU pins);
 *  - SharedPreferences `nuvio_mediaserver_tokens`: `item.<key>` = `<b64 iv>.<b64 ciphertext>` (Keystore AES-256-GCM, alias
 *    `com.nuvio.media.mediaserver.tokens.v1`, AAD `<alias>:<key>`), for `cred:<type>:<machineId>:<userId>` (a JSON
 *    `{"accessToken","userName"}`) and `device-id` (the MediaBrowser DeviceId every old token is bound to).
 */
internal object LegacyMediaServerImport {
    const val KEY_ALIAS = "com.nuvio.media.mediaserver.tokens.v1"

    /** One saved server of the old stack. [address] is null when this device never learned one (a row synced from another device). */
    data class LegacyEntry(
        val type: String,
        val machineId: String,
        val userId: String,
        val name: String,
        val address: String?,
        val enabled: Boolean,
        val userName: String?
    ) {
        /** The old credential key: `{type}:{machineId}:{userId}`. */
        val credentialKey: String get() = "$type:$machineId:$userId"
        val key: String get() = serverKey(type, machineId, userId)
    }

    class ParsedEntries(val entries: List<LegacyEntry>, val clean: Boolean)

    /** The entries of one `entries_<profileId>` blob. [clean] is false when anything in it could not be read (the legacy copy is then kept). */
    fun parseEntries(raw: String): ParsedEntries {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ParsedEntries(emptyList(), false)
        val array = root["entries"] as? JsonArray ?: return ParsedEntries(emptyList(), false)
        var clean = true
        val entries = array.mapNotNull { element ->
            val entry = runCatching { entryOf(element.jsonObject) }.getOrNull()
            if (entry == null) clean = false
            entry
        }
        return ParsedEntries(entries, clean)
    }

    private fun entryOf(entry: JsonObject): LegacyEntry? {
        fun text(name: String): String? = (entry[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val type = text("type")?.takeIf { it == "jellyfin" || it == "emby" } ?: return null
        val machineId = text("machineId") ?: return null
        val userId = text("userId") ?: return null
        return LegacyEntry(
            type = type,
            machineId = machineId,
            userId = userId,
            name = text("name") ?: type.replaceFirstChar { it.uppercase() },
            address = text("address"),
            enabled = (entry["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            userName = text("userName")
        )
    }

    /** `{"host:port":"FINGERPRINT"}`, lower-cased authority, blank values dropped; unreadable -> empty. */
    fun parseTrust(raw: String?): Map<String, String> {
        val root = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return emptyMap()
        return root.mapNotNull { (authority, value) ->
            val fingerprint = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (fingerprint.isEmpty()) null else authority.trim().lowercase() to fingerprint
        }.toMap()
    }

    /** The access token of an old `cred:` item (`{"accessToken","userName"}`), or null when blank/unreadable. */
    fun parseToken(rawCredential: String?): String? {
        val root = rawCredential?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        return (root["accessToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * The old Keystore item format: `<b64 iv>.<b64 ciphertext>`, AES-GCM with the item key as AAD (`<alias>:<item key>`),
     * so a blob copied to another key does not decrypt. Null when it does not.
     */
    fun decryptItem(secretKey: SecretKey, itemKey: String, stored: String, decode: (String) -> ByteArray): String? = try {
        val parts = stored.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, decode(parts[0])))
        cipher.updateAAD("$KEY_ALIAS:$itemKey".toByteArray(Charsets.UTF_8))
        cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    class Mapped(val stored: StoredServers, val imported: Int, val tokenless: Int, val keys: List<String>)

    /**
     * Legacy entries -> the upstream per-profile record. Pure.
     *  - Never loses a server: an entry whose token is missing/undecryptable lands WITHOUT a token, which the repository
     *    shows as "Sign in again" (address and user prefilled); an entry without an address keeps an empty one.
     *  - De-duplicates by provider + server + user: a server the upstream store already has is left exactly as it is.
     *  - The access token is carried into this device's protected store only; the pushed sync payload blanks it.
     *  - Libraries are not copied (the old stack's Home-row opt-ins are not a library scope): the record asks for a refresh.
     *  - pendingPush: the normal media_servers sync pushes the imported servers (address/user, never the token).
     */
    fun map(
        legacy: List<LegacyEntry>,
        tokenFor: (LegacyEntry) -> String?,
        existing: StoredServers?,
        newId: (prefix: String) -> String
    ): Mapped {
        val base = existing ?: StoredServers()
        val have = base.connections.mapTo(mutableSetOf()) { serverKey(it.providerId, it.remoteServerId, it.remoteUserId) }
        val connections = base.connections.toMutableList()
        val tokens = base.tokens.toMutableMap()
        val keys = mutableListOf<String>()
        var tokenless = 0
        legacy.forEach { entry ->
            if (!have.add(entry.key)) return@forEach
            val token = tokenFor(entry)
            val credentialRef = newId("k")
            connections += ServerConnection(
                id = newId("c"),
                providerId = entry.type,
                name = entry.name,
                address = entry.address.orEmpty().trimEnd('/'),
                remoteServerId = entry.machineId,
                remoteUserId = entry.userId,
                userName = entry.userName.orEmpty(),
                credentialRef = credentialRef,
                libraries = emptyList(),
                enabled = entry.enabled,
                useCatalogMetadata = false,
                importWatchState = false
            )
            if (token != null) tokens[credentialRef] = token else tokenless++
            keys += entry.key
        }
        val imported = keys.size
        return Mapped(
            stored = base.copy(
                connections = connections,
                tokens = tokens,
                pendingPush = base.pendingPush || imported > 0,
                libraryRefreshPending = base.libraryRefreshPending || imported > 0
            ),
            imported = imported,
            tokenless = tokenless,
            keys = keys
        )
    }
}
