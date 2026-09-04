package com.moonkata.textreader.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EncodingDetector is pure logic (juniversalchardet + java.nio.charset) with no Android
 * dependency at all, so anything verifiable with synthetic bytes is a plain JUnit test that runs
 * directly on the JVM without a device/emulator. The case that reads an actual fixture file
 * (UTF-8) needs a Context, so it stays in androidTest's EncodingDetectionTest.
 */
class EncodingDetectorTest {

    @Test
    fun detectsAndCorrectlyDecodesSyntheticEucKrText() {
        val original = "이것은 EUC-KR로 인코딩된 테스트 문장입니다. 한글이 잘 감지되고 복원되는지 확인합니다. ".repeat(50)
        val eucKrBytes = original.toByteArray(charset("EUC-KR"))

        val detected = EncodingDetector.detect(eucKrBytes)
        assertTrue(
            "Should be detected as an EUC-KR variant (EUC-KR/MS949/x-windows-949), actual: ${detected.name()}",
            detected.name().let { it.equals("EUC-KR", ignoreCase = true) || it.contains("949", ignoreCase = true) },
        )

        val decoded = String(eucKrBytes, detected)
        assertEquals("The string decoded with the detected encoding should match the original", original, decoded)
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
        // When detection fails, the first candidate (MS949/x-windows-949/EUC-KR/UTF-8) supported
        // by this JVM is returned — exactly which name comes out depends on the JVM
        // implementation, so we don't assert on that; we only check that a usable Charset is
        // returned without crashing.
        val detected = EncodingDetector.detect(ByteArray(0))

        assertEquals("", String(ByteArray(0), detected))
    }
}
