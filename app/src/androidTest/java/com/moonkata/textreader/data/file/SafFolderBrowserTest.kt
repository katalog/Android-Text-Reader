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
 * `SafFolderBrowser.listZipEntries`는 테스트가 하나도 없었다 — `listFolder`는 진짜 SAF 트리 URI가
 * 있어야 해서(`DocumentFile.fromTreeUri`) 자동화하기 어렵지만(그래서 `FakeFolderBrowser`로 대체),
 * `listZipEntries`는 `contentResolver.openInputStream`만 쓰기 때문에 `file://` URI로도 실제 로직을
 * 그대로 검증할 수 있다.
 */
@RunWith(AndroidJUnit4::class)
class SafFolderBrowserTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val browser = SafFolderBrowser(application)

    /**
     * STORED(비압축) 방식으로 크기/CRC를 미리 계산해 로컬 헤더에 바로 써넣는다 — DEFLATED로 그냥
     * 스트리밍해서 쓰면(ZipOutputStream 기본값) 자바가 data descriptor(데이터 뒤에 크기를 붙이는
     * 방식)를 쓰는 경우가 있어, ZipInputStream으로 앞에서부터 읽을 때(`listZipEntries`가 하는 그대로)
     * 아직 그 엔트리의 바이트를 다 안 읽은 시점엔 `ZipEntry.size`가 -1(모름)일 수 있다. 실제 배포되는
     * zip 도구들은 스트리밍이 아니라 파일 크기를 미리 알고 쓰므로 이 문제가 거의 없지만, 테스트에서
     * `size`를 검증하려면 그 경로를 피해야 한다.
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
