package com.moonkata.textreader.data.file

import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset

/** Auto-detects encodings commonly found in Korean txt files, such as UTF-8 / EUC-KR (CP949). */
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
