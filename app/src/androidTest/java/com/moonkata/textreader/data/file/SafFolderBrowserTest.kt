package com.moonkata.textreader.data.file

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `SafFolderBrowser.listZipEntries` had no tests at all — `listFolder` needs a real SAF tree URI
 * (`DocumentFile.fromTreeUri`), which makes it hard to automate (hence substituting
 * `FakeFolderBrowser` for it), but `listZipEntries` only uses
 * `contentResolver.openInputStream`, so the real logic can be verified as-is with a `file://` URI too.
 */
@RunWith(AndroidJUnit4::class)
class SafFolderBrowserTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val browser = SafFolderBrowser(application)

    /**
     * Precomputes size/CRC and writes them straight into the local header using STORED
     * (uncompressed) mode — if it were just streamed out as DEFLATED (ZipOutputStream's default),
     * Java can end up using a data descriptor (a scheme that appends the size after the data), so
     * when reading from the front with ZipInputStream (exactly what `listZipEntries` does),
     * `ZipEntry.size` can be -1 (unknown) at a point where that entry's bytes haven't all been read
     * yet. Real-world zip tools rarely hit this since they know the file size up front rather than
     * streaming, but tests that need to verify `size` must avoid that path.
     */
    private fun zipFile(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("saf_zip_test", ".zip", application.cacheDir)
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    @Test
    fun listsOnlyTxtEntries_ignoringOtherExtensions() = runBlocking {
        val zip = zipFile("book.txt" to "내용", "cover.jpg" to "x", "notes.md" to "y")

        val entries = browser.listZipEntries(Uri.fromFile(zip))

        assertEquals(listOf("book.txt"), entries.map { it.name })
    }

    @Test
    fun stripsDirectoryPrefixFromTheDisplayedName() = runBlocking {
        val zip = zipFile("series/sub/chapter1.txt" to "내용")

        val entries = browser.listZipEntries(Uri.fromFile(zip))

        assertEquals("chapter1.txt", entries.single().name)
        val source = entries.single().source as BookSource.ZipEntryTxt
        assertEquals("series/sub/chapter1.txt", source.entryName)
    }

    @Test
    fun skipsDirectoryEntries() = runBlocking {
        val file = File.createTempFile("saf_zip_test_dir", ".zip", application.cacheDir)
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("folder/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("folder/book.txt"))
            zos.write("내용".toByteArray())
            zos.closeEntry()
        }

        val entries = browser.listZipEntries(Uri.fromFile(file))

        assertEquals(1, entries.size)
        assertEquals("book.txt", entries.single().name)
    }

    @Test
    fun emptyZip_returnsEmptyList() = runBlocking {
        val zip = zipFile()
        assertTrue(browser.listZipEntries(Uri.fromFile(zip)).isEmpty())
    }

    @Test
    fun corruptedOrMissingFile_returnsEmptyList_insteadOfThrowing() = runBlocking {
        val notReallyAZip = File.createTempFile("not_a_zip", ".zip", application.cacheDir).apply {
            writeText("이건 zip이 아닙니다")
        }
        assertTrue(browser.listZipEntries(Uri.fromFile(notReallyAZip)).isEmpty())

        val missing = Uri.fromFile(File(application.cacheDir, "definitely_does_not_exist.zip"))
        assertTrue(browser.listZipEntries(missing).isEmpty())
    }

    @Test
    fun eachEntry_carriesTheZipUriAndCorrectSize() = runBlocking {
        val content = "내용이 좀 더 긴 파일입니다"
        val zip = zipFile("a.txt" to content)
        val zipUri = Uri.fromFile(zip)

        val entry = browser.listZipEntries(zipUri).single()

        val source = entry.source as BookSource.ZipEntryTxt
        assertEquals(zipUri, source.zipUri)
        assertEquals(content.toByteArray().size.toLong(), entry.sizeBytes)
    }
}
