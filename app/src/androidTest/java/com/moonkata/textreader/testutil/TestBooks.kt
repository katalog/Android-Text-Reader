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
 * Copies the public-domain novel fixtures (Yi Kwang-su's Mujeong/Heuk, Project Gutenberg's
 * Moby-Dick/Dracula) committed under `app/src/androidTest/assets/books/` into a cache file so tests
 * can use them.
 *
 * Assets can only be read via an `AssetManager` stream, but `BookSource.PlainTxt` needs a real `Uri`,
 * so the `file://` URI of the copied cache file is used instead — `ContentResolver` reads `file://`
 * URIs directly without needing SAF permission.
 */
object TestBooks {

    /** Copies a path relative to assets/books/ (e.g. "Heuk.txt") into a cache file and returns that File. */
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

    /** Skips the test instead of failing it if the fixture isn't available in this environment (a different PC/CI). */
    fun assumeAvailable(assetName: String) {
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val exists = try {
            instrumentationContext.assets.open("books/$assetName").use { true }
        } catch (e: FileNotFoundException) {
            false
        }
        Assume.assumeTrue("Skipping: no novel fixture available: books/$assetName", exists)
    }

    /** Copies the fixture into cache and registers it with [bookRepository], returning the bookId. */
    suspend fun insertBook(applicationContext: Context, bookRepository: BookRepository, assetName: String): Long {
        assumeAvailable(assetName)
        val file = copyToCache(applicationContext, assetName)
        val source = BookSource.PlainTxt(Uri.fromFile(file))
        return bookRepository.findOrCreateBook(source, assetName, file.length())
    }
}
