package com.moonkata.textreader.testutil

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.moonkata.textreader.data.file.BookSource
import com.moonkata.textreader.data.repository.BookRepository
import org.junit.Assume
import java.io.File
import java.io.FileNotFoundException

/**
 * `app/src/androidTest/assets/books/`에 로컬로 넣어둔 실제 소설 픽스처(저작권/용량 문제로 gitignore되어
 * 다른 개발자 PC나 CI에는 없을 수 있음)를 테스트에서 쓸 수 있게 캐시 파일로 복사한다.
 *
 * asset은 `AssetManager` 스트림으로만 읽히는데 `BookSource.PlainTxt`는 실제 `Uri`가 필요해서, 복사한
 * 캐시 파일의 `file://` URI를 대신 쓴다 — `ContentResolver`는 SAF 권한 없이도 file:// URI를 그대로 읽는다.
 */
object TestBooks {

    /** assets/books/ 밑의 상대 경로(예: "Static.txt")를 캐시 파일로 복사해 그 File을 반환한다. */
    fun copyToCache(applicationContext: Context, assetName: String): File {
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val target = File(File(applicationContext.cacheDir, "test_books"), assetName)
        target.parentFile?.mkdirs()
        if (!target.exists()) {
            instrumentationContext.assets.open("books/$assetName").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target
    }

    /** 픽스처가 이 환경에 없으면(다른 PC/CI) 실패시키지 않고 테스트를 건너뛴다. */
    fun assumeAvailable(assetName: String) {
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val exists = try {
            instrumentationContext.assets.open("books/$assetName").use { true }
        } catch (e: FileNotFoundException) {
            false
        }
        Assume.assumeTrue("테스트용 소설 픽스처가 없어 건너뜀: books/$assetName", exists)
    }

    /** 픽스처를 캐시로 복사하고 [bookRepository]에 등록해 bookId를 반환한다. */
    suspend fun insertBook(applicationContext: Context, bookRepository: BookRepository, assetName: String): Long {
        assumeAvailable(assetName)
        val file = copyToCache(applicationContext, assetName)
        val source = BookSource.PlainTxt(Uri.fromFile(file))
        return bookRepository.findOrCreateBook(source, assetName, file.length())
    }
}
