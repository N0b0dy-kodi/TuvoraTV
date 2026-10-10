package com.nuvio.tv.data.mediaserver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The certificates this device pinned by trust-on-first-use, per `host:port`. A pin is THIS device's decision:
 * it is never synced (the sync payload has no field for it) and is only written after the user explicitly
 * accepted a certificate the system did not trust.
 */
interface ServerTrustStore {
    fun pinned(authority: String): String?
    fun pin(authority: String, fingerprint: String)
    fun unpin(authority: String)
    fun clearAll()
}

@Singleton
class AndroidServerTrustStore @Inject constructor(
    @ApplicationContext context: Context
) : ServerTrustStore {
    // Fingerprints are public values, not secrets: plain preferences are enough.
    private val preferences = context.getSharedPreferences("media_server_trust", Context.MODE_PRIVATE)

    override fun pinned(authority: String): String? = preferences.getString(authority.lowercase(), null)

    override fun pin(authority: String, fingerprint: String) {
        preferences.edit().putString(authority.lowercase(), fingerprint).apply()
    }

    override fun unpin(authority: String) {
        preferences.edit().remove(authority.lowercase()).apply()
    }

    override fun clearAll() {
        preferences.edit().clear().apply()
    }
}

/** A certificate problem a request hit: the UI shows [fingerprint] (and [pinned] when it changed) and asks. */
class ServerCertificateException(
    failure: ServerFailure,
    val authority: String,
    val fingerprint: String,
    val pinned: String?
) : ServerException(failure, "${failure.name} $authority") {
    override fun toString(): String = "ServerCertificateException(${failure.name})"
}

/**
 * The seam between the OkHttp TLS glue and [ServerCertTrustPolicy]: the glue reports the facts of one
 * handshake through [check] and enforces the answer; a refused handshake is remembered for
 * [takeCertificateError] so the failing request surfaces the prompt instead of "unreachable".
 */
class ServerTrust(private val store: ServerTrustStore) {
    private data class Refusal(val verdict: ServerCertTrustPolicy.Verdict, val fingerprint: String)

    private val lock = Any()
    private val refusals = mutableMapOf<String, Refusal>()

    internal fun check(
        authority: String,
        systemTrusted: Boolean,
        failure: ServerCertTrustPolicy.FailureKind?,
        fingerprint: String
    ): Boolean {
        val host = authority.substringBeforeLast(':')
        val verdict = ServerCertTrustPolicy.decide(systemTrusted, failure, fingerprint, store.pinned(authority), host)
        if (verdict is ServerCertTrustPolicy.Verdict.Accept) return true
        synchronized(lock) { refusals[authority.lowercase()] = Refusal(verdict, fingerprint) }
        return false
    }

    /** The prompt for the last refused handshake at [authority] (cleared by reading it); null when it is not trust-offerable. */
    fun takeCertificateError(authority: String): ServerCertificateException? {
        val refusal = synchronized(lock) { refusals.remove(authority.lowercase()) } ?: return null
        return when (val verdict = refusal.verdict) {
            is ServerCertTrustPolicy.Verdict.AskTrustNew ->
                ServerCertificateException(ServerFailure.CERTIFICATE_UNTRUSTED, authority, verdict.fingerprint, null)
            is ServerCertTrustPolicy.Verdict.AskCertificateChanged ->
                ServerCertificateException(ServerFailure.CERTIFICATE_CHANGED, authority, verdict.presented, verdict.pinned)
            else -> null
        }
    }

    fun pinned(authority: String): String? = store.pinned(authority)

    /** The user accepted this certificate (first trust or an explicit re-trust). */
    fun pin(authority: String, fingerprint: String) = store.pin(authority, fingerprint)

    fun clearAll() = store.clearAll()
}

/** `AB:CD:EF` grouping of an upper-case hex SHA-256 for display. */
fun formatFingerprint(hex: String): String = hex.chunked(2).joinToString(":")
