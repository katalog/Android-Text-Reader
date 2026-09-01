package com.moonkata.textreader.data.file

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.testutil.TestBooks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 픽스처 파일을 읽는 케이스만 여기 남는다 — Context(에셋 접근)가 필요해서 androidTest다.
 * EUC-KR/ASCII 합성 바이트 케이스는 Android 의존성이 없어 app/src/test의 EncodingDetectorTest로 옮김.
 */
@RunWith(AndroidJUnit4::class)
class EncodingDetectionTest {

    @Test
    fun detectsUtf8OnARealFixtureNovel() {
        val bookAsset = "Static.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val file = TestBooks.copyToCache(application, bookAsset)

        val sample = file.readBytes().copyOf(minOf(file.length().toInt(), 256 * 1024))
        val charset = EncodingDetector.detect(sample)

        assertEquals("UTF-8", charset.name())
    }
}
