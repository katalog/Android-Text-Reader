package com.moonkata.textreader.data.file

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.testutil.TestBooks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Only the case that reads a real fixture file remains here — it's in androidTest because it needs
 * Context (asset access). The EUC-KR/ASCII synthetic byte cases have no Android dependency and were
 * moved to EncodingDetectorTest in app/src/test.
 */
@RunWith(AndroidJUnit4::class)
class EncodingDetectionTest {

    @Test
    fun detectsUtf8OnARealFixtureNovel() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val file = TestBooks.copyToCache(application, bookAsset)

        val sample = file.readBytes().copyOf(minOf(file.length().toInt(), 256 * 1024))
        val charset = EncodingDetector.detect(sample)

        assertEquals("UTF-8", charset.name())
    }
}
