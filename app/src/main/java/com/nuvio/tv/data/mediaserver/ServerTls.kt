package com.nuvio.tv.data.mediaserver

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * One client per server authority. System validation runs first; ONLY when it fails does [ServerTrust] decide
 * whether a certificate this device pinned applies. There is no trust-all anywhere: a certificate that is not
 * pinned for exactly this `host:port` is refused, and hostname verification is waived only for the exact
 * pinned certificate.
 */
internal fun OkHttpClient.withServerTrust(authority: String, trust: ServerTrust): OkHttpClient {
    val system = systemTrustManager()
    val pinning = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                if (!trust.check(authority, false, classify(error, chain), fingerprint(chain[0]))) throw error
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinning), null) }
    val hostnameVerifier = HostnameVerifier { host, session ->
        if (OkHostnameVerifier.verify(host, session)) return@HostnameVerifier true
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
        trust.check(authority, false, ServerCertTrustPolicy.FailureKind.HOSTNAME_MISMATCH, fingerprint(leaf))
    }
    return newBuilder()
        .sslSocketFactory(sslContext.socketFactory, pinning)
        .hostnameVerifier(hostnameVerifier)
        .build()
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

internal fun fingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

private fun classify(error: CertificateException, chain: Array<X509Certificate>): ServerCertTrustPolicy.FailureKind {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is CertificateExpiredException) return ServerCertTrustPolicy.FailureKind.EXPIRED
        cause = cause.cause
    }
    val leaf = chain.firstOrNull() ?: return ServerCertTrustPolicy.FailureKind.OTHER
    return if (chain.size == 1 && leaf.subjectX500Principal == leaf.issuerX500Principal) {
        ServerCertTrustPolicy.FailureKind.SELF_SIGNED
    } else {
        ServerCertTrustPolicy.FailureKind.UNTRUSTED_CHAIN
    }
}
