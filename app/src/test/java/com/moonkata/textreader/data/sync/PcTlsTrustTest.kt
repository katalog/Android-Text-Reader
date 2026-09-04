package com.moonkata.textreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.Certificate

/** [createLenientSslContext]/[createPinnedSslContext] are wrapped around an actual TLS handshake
 * (verified on the androidTest side in [com.moonkata.textreader.data.sync.PcSyncClientTest]), so
 * here we only cover [sha256Fingerprint] — the pure logic that trust decision actually relies on. */
class PcTlsTrustTest {

    private class FakeCertificate(private val bytes: ByteArray) : Certificate("fake") {
        override fun getEncoded() = bytes
        override fun verify(key: java.security.PublicKey) {}
        override fun verify(key: java.security.PublicKey, sigProvider: String) {}
        override fun getPublicKey(): java.security.PublicKey = throw UnsupportedOperationException()
        override fun toString() = "FakeCertificate"
    }

    @Test
    fun `matches openssl-style SHA-256 fingerprint format`() {
        val cert = FakeCertificate(byteArrayOf(1, 2, 3, 4))
        val expected = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3, 4))
            .joinToString(":") { "%02X".format(it) }

        assertEquals(expected, sha256Fingerprint(cert))
    }

    @Test
    fun `is deterministic for the same certificate bytes`() {
        val bytes = "moonkata-sync-server".toByteArray()
        assertEquals(sha256Fingerprint(FakeCertificate(bytes)), sha256Fingerprint(FakeCertificate(bytes)))
    }

    @Test
    fun `differs when certificate bytes differ`() {
        val a = sha256Fingerprint(FakeCertificate(byteArrayOf(1)))
        val b = sha256Fingerprint(FakeCertificate(byteArrayOf(2)))
        assertNotEquals(a, b)
    }

    @Test
    fun `uses uppercase hex pairs joined by colons`() {
        val fingerprint = sha256Fingerprint(FakeCertificate(byteArrayOf(0)))
        assertEquals(true, Regex("^[0-9A-F]{2}(:[0-9A-F]{2})*$").matches(fingerprint))
        // SHA-256 digest is always 32 bytes -> 32 colon-separated pairs.
        assertEquals(32, fingerprint.split(":").size)
    }
}
