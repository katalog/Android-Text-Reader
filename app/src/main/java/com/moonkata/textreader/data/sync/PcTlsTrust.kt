package com.moonkata.textreader.data.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trust logic for handling the PC tray server's self-signed certificate — .docs/PC_SYNC_SERVER_PLAN.md §5.
 * Since a private IP can't get a certificate from a public CA, this uses the SSH-style approach (TOFU:
 * save the fingerprint on first connection, and afterward only check that it matches exactly) instead of
 * the system's default trust-chain validation.
 *
 * - [createLenientSslContext]: used only during stages where nothing is trusted yet, like the "find PC"
 *   scan or the first "test connection" attempt — accepts any certificate, but at this stage either the
 *   secret isn't sent along at all (scan), or the certificate is used purely to record the fingerprint on
 *   the spot (connection test).
 * - [createPinnedSslContext]: used for actual sync (`/list`, `/file`) — only accepts a certificate that
 *   exactly matches the saved fingerprint.
 *
 * Hostname verification always passes — connections are mostly by IP, and since the basis of trust here is
 * fingerprint pinning rather than CN/SAN in the first place, hostname matching is meaningless under this
 * scheme.
 */
val acceptAllHostnameVerifier = HostnameVerifier { _, _ -> true }

fun createLenientSslContext(): SSLContext {
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
}

fun createPinnedSslContext(expectedFingerprint: String): SSLContext {
    val pinned = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val presented = chain.firstOrNull()?.let { sha256Fingerprint(it) }
            if (presented == null || !presented.equals(expectedFingerprint, ignoreCase = true)) {
                throw CertificateException("The PC's certificate fingerprint doesn't match the saved value — try the connection test again.")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    return SSLContext.getInstance("TLS").apply { init(null, pinned, SecureRandom()) }
}

/** Computes a certificate's SHA-256 fingerprint as a hex string — equivalent to `openssl x509 -fingerprint -sha256`. */
fun sha256Fingerprint(cert: Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
}
