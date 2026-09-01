package com.moonkata.textreader.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EncodingDetector는 Android 의존성이 전혀 없는 순수 로직(juniversalchardet + java.nio.charset)이라
 * 합성 바이트로 검증 가능한 것들은 기기/에뮬레이터 없이 JVM에서 바로 도는 일반 JUnit 테스트로 둔다.
 * 실제 픽스처 파일(UTF-8)을 읽는 케이스는 Context가 필요해 androidTest의 EncodingDetectionTest에 남는다.
 */
class EncodingDetectorTest {

    @Test
    fun detectsAndCorrectlyDecodesSyntheticEucKrText() {
        val original = "이것은 EUC-KR로 인코딩된 테스트 문장입니다. 한글이 잘 감지되고 복원되는지 확인합니다. ".repeat(50)
        val eucKrBytes = original.toByteArray(charset("EUC-KR"))

        val detected = EncodingDetector.detect(eucKrBytes)
        assertTrue(
            "EUC-KR 계열(EUC-KR/MS949/x-windows-949)로 감지되어야 함, 실제: ${detected.name()}",
            detected.name().let { it.equals("EUC-KR", ignoreCase = true) || it.contains("949", ignoreCase = true) },
        )

        val decoded = String(eucKrBytes, detected)
        assertEquals("감지된 인코딩으로 복원한 문자열이 원문과 같아야 함", original, decoded)
    }

    @Test
    fun fallsBackToUtf8ForPlainAsciiText() {
        val ascii = "Plain ASCII text with no Korean at all.".repeat(20)
        val bytes = ascii.toByteArray(Charsets.US_ASCII)

        val detected = EncodingDetector.detect(bytes)
        val decoded = String(bytes, detected)

        assertEquals(ascii, decoded)
    }

    @Test
    fun detectsUtf8ForKoreanUtf8Bytes() {
        val original = "UTF-8로 인코딩된 한글 문장을 잘 감지하는지 확인한다.".repeat(30)
        val utf8Bytes = original.toByteArray(Charsets.UTF_8)

        val detected = EncodingDetector.detect(utf8Bytes)

        assertEquals("UTF-8", detected.name())
        assertEquals(original, String(utf8Bytes, detected))
    }

    @Test
    fun emptyInput_doesNotCrashAndProducesAUsableCharset() {
        // 감지 실패 시 후보(MS949/x-windows-949/EUC-KR/UTF-8) 중 이 JVM이 지원하는 첫 번째가 나온다 —
        // 정확히 어떤 이름이 나오는지는 JVM 구현에 달려있어 단정하지 않고, 크래시 없이 쓸 수 있는
        // Charset을 돌려주는지만 확인한다.
        val detected = EncodingDetector.detect(ByteArray(0))

        assertEquals("", String(ByteArray(0), detected))
    }
}
