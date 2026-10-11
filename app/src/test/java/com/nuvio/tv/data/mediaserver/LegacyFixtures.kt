package com.nuvio.tv.data.mediaserver

/**
 * What the RELEASED fork stack (v1.13.5, `core.mediaserver.store`) wrote, captured by running its own MediaServerEntryStore,
 * MediaServerTrustStore and MediaServerCredentialStore at the tag (ids and tokens are throwaway test values). The AES-GCM
 * wrapping of the credential items lives in the Android Keystore and is covered separately.
 */
internal object LegacyFixtures {
    const val JELLYFIN_MACHINE = "67a74bd4dd0f4e3aa1c2b3d4e5f60718"
    const val JELLYFIN_USER = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
    const val EMBY_MACHINE = "a6b13f3047d94c0e9b1a2c3d4e5f6071"
    const val EMBY_USER = "55fd0a68f8e24ecbaa2cc75f936d367c"
    const val JELLYFIN_TOKEN = "0123456789abcdef0123456789abcdef"
    const val EMBY_TOKEN = "fedcba9876543210fedcba9876543210"
    const val FINGERPRINT = "3FEB121948BF77E3CE43BA234893230E2BFAEBA6F4732042FBC01E7238716A37"

    /** `entries_1` of SharedPreferences `nuvio_mediaserver`. */
    const val ENTRIES_PROFILE_1 = """{"version":1,"entries":[{"key":"jellyfin|67a74bd4dd0f4e3aa1c2b3d4e5f60718|0a1b2c3d4e5f60718293a4b5c6d7e8f9","type":"jellyfin","machineId":"67a74bd4dd0f4e3aa1c2b3d4e5f60718","userId":"0a1b2c3d4e5f60718293a4b5c6d7e8f9","name":"Living room","address":"https://10.0.2.2:18443","syncAddress":true,"enabled":true,"userName":"tuvora_test","homeRows":["next_up","continue_watching"],"homeLibraries":{"f137a2dd21bbc1b99aa5c0f6bf02a805":"Movies"}},{"key":"emby|a6b13f3047d94c0e9b1a2c3d4e5f6071|55fd0a68f8e24ecbaa2cc75f936d367c","type":"emby","machineId":"a6b13f3047d94c0e9b1a2c3d4e5f6071","userId":"55fd0a68f8e24ecbaa2cc75f936d367c","name":"Den Emby","address":"https://10.0.2.2:18445","syncAddress":true,"enabled":false,"userName":"tuvora_emby","homeRows":[],"homeLibraries":{}}]}"""

    /** `entries_2`: a row that arrived from another device (no address known here). */
    const val ENTRIES_PROFILE_2 = """{"version":1,"entries":[{"key":"jellyfin|67a74bd4dd0f4e3aa1c2b3d4e5f60718|0a1b2c3d4e5f60718293a4b5c6d7e8f9","type":"jellyfin","machineId":"67a74bd4dd0f4e3aa1c2b3d4e5f60718","userId":"0a1b2c3d4e5f60718293a4b5c6d7e8f9","name":"Living room","address":null,"syncAddress":false,"enabled":true,"userName":null,"homeRows":[],"homeLibraries":{}}]}"""

    /** `trust` of the same file. */
    const val TRUST = """{"10.0.2.2:18443":"3FEB121948BF77E3CE43BA234893230E2BFAEBA6F4732042FBC01E7238716A37","10.0.2.2:18445":"3FEB121948BF77E3CE43BA234893230E2BFAEBA6F4732042FBC01E7238716A37"}"""

    /** The decrypted `cred:` items of `nuvio_mediaserver_tokens`. */
    const val JELLYFIN_CREDENTIAL = """{"accessToken":"0123456789abcdef0123456789abcdef","userName":"tuvora_test"}"""
    const val EMBY_CREDENTIAL = """{"accessToken":"fedcba9876543210fedcba9876543210"}"""
}
