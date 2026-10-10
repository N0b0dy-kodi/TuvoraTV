package com.nuvio.tv.data.mediaserver

/**
 * Certificate trust for a media server: system validation FIRST; only when it FAILS may the user be offered
 * trust-on-first-use of the presented leaf certificate, pinned per `host:port`; a later mismatch is a hard
 * failure that asks the user to re-trust explicitly, never a silent accept. A publicly trusted chain is never
 * pinned (renewals would break a pin). Pure: the OkHttp glue reports the facts and enforces the verdict.
 */
internal object ServerCertTrustPolicy {
    /** Why system validation failed - the platform maps its own error into one of these. */
    enum class FailureKind { UNTRUSTED_CHAIN, SELF_SIGNED, HOSTNAME_MISMATCH, EXPIRED, OTHER }

    sealed interface Verdict {
        /** Proceed: the system trusts the chain, or the pinned fingerprint matches. */
        data object Accept : Verdict

        /** System validation failed and nothing is pinned yet: show the fingerprint and ask. */
        data class AskTrustNew(val fingerprint: String) : Verdict

        /** A different certificate than the pinned one: refuse, and ask the user to re-trust the new one. */
        data class AskCertificateChanged(val pinned: String, val presented: String) : Verdict

        /** A failure trust-on-first-use must not paper over (expired, or an unrelated TLS error). */
        data class Reject(val failure: FailureKind) : Verdict
    }

    fun decide(
        systemTrusted: Boolean,
        failure: FailureKind?,
        presentedFingerprint: String,
        pinnedFingerprint: String?,
        host: String
    ): Verdict {
        if (systemTrusted) return Verdict.Accept
        val kind = failure ?: FailureKind.OTHER
        if (!canOfferTrust(kind, host)) return Verdict.Reject(kind)
        return when {
            pinnedFingerprint == null -> Verdict.AskTrustNew(presentedFingerprint)
            pinnedFingerprint == presentedFingerprint -> Verdict.Accept
            else -> Verdict.AskCertificateChanged(pinnedFingerprint, presentedFingerprint)
        }
    }

    /**
     * Self-signed / private-CA certificates of a home server qualify; a hostname mismatch qualifies only for a
     * local or IP-literal host; an expired certificate never does.
     */
    fun canOfferTrust(failure: FailureKind, host: String): Boolean = when (failure) {
        FailureKind.UNTRUSTED_CHAIN, FailureKind.SELF_SIGNED -> true
        FailureKind.HOSTNAME_MISMATCH -> isLocalHost(host) || isIpLiteral(host)
        FailureKind.EXPIRED, FailureKind.OTHER -> false
    }

    internal fun isLocalHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (':' in h) return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb")
        val n = h.split('.').takeIf { it.size == 4 && it.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) } }
            ?.map { it.toInt() } ?: return false
        if (n.any { it > 255 }) return false
        return n[0] == 10 || n[0] == 127 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168) ||
            (n[0] == 169 && n[1] == 254) || (n[0] == 100 && n[1] in 64..127)
    }

    internal fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (':' in h) return true
        val octets = h.split('.')
        return octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}
