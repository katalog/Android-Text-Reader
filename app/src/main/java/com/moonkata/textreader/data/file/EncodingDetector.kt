package com.moonkata.textreader.data.file

import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset

/** UTF-8 / EUC-KR(CP949) 등 한국어 txt 파일에서 흔한 인코딩을 자동 감지한다. */
object EncodingDetector {

    fun detect(sampleBytes: ByteArray): Charset {
        val detector = UniversalDetector(null)
        detector.handleData(sampleBytes, 0, sampleBytes.size)
        detector.dataEnd()
        val detected = detector.detectedCharset
        detector.reset()

        val candidates = listOfNotNull(detected, "MS949", "x-windows-949", "EUC-KR", "UTF-8")
        for (name in candidates) {
            if (runCatching { Charset.isSupported(name) }.getOrDefault(false)) {
                return Charset.forName(name)
            }
        }
        return Charsets.UTF_8
    }
}
