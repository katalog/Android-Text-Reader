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
 * PC 트레이 서버의 자체 서명 인증서를 다루는 신뢰 로직 — .docs/PC_SYNC_SERVER_PLAN.md §5.
 * 사설 IP엔 공인 CA가 인증서를 못 주므로, 시스템 기본 신뢰 체인 검증 대신 SSH 방식(TOFU: 최초 접속
 * 때 지문을 저장해두고 이후로는 그 지문과 정확히 같은지만 확인)을 쓴다.
 *
 * - [createLenientSslContext]: "PC 찾기" 스캔이나 "연결 테스트" 최초 시도처럼 아직 아무것도 못 믿는
 *   단계에서만 쓴다 — 어떤 인증서든 받아들이지만, 이 단계에선 시크릿도 같이 보내지 않거나(스캔) 딱
 *   그 자리에서 지문을 저장하는 용도로만 쓴다(연결 테스트).
 * - [createPinnedSslContext]: 실제 동기화(`/list`, `/file`)에서 쓴다 — 저장해둔 지문과 정확히 일치하는
 *   인증서만 받아들인다.
 *
 * 호스트명 검증은 항상 통과시킨다 — IP로 접속하는 경우가 대부분이고, 애초에 신뢰 근거가 CN/SAN이
 * 아니라 지문 고정이라 호스트명 일치는 이 방식에서 의미가 없다.
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
                throw CertificateException("PC 인증서 지문이 저장된 값과 다릅니다 — 연결 테스트를 다시 해보세요.")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    return SSLContext.getInstance("TLS").apply { init(null, pinned, SecureRandom()) }
}

/** 인증서의 SHA-256 지문을 16진수 문자열로 — `openssl x509 -fingerprint -sha256`와 같은 계산. */
fun sha256Fingerprint(cert: Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
}
