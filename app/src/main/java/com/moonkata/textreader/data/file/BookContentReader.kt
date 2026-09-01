package com.moonkata.textreader.data.file

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

object BookContentReader {

    data class ReadResult(val text: String, val encoding: String)

    private const val SAMPLE_SIZE = 256 * 1024

    suspend fun read(context: Context, source: BookSource): ReadResult = withContext(Dispatchers.IO) {
        val bytes = readBytes(context, source)
        val sample = bytes.copyOf(minOf(bytes.size, SAMPLE_SIZE))
        val charset = EncodingDetector.detect(sample)
        ReadResult(String(bytes, charset), charset.name())
    }

    /**
     * [source]가 실제로 열 수 있는 상태인지 확인한다 — 파일이 삭제/이동되었거나 SAF 권한이 회수되면
     * false. "이어서 읽기" 후보를 띄우기 전에 미리 확인하는 용도: 확인 없이 바로 읽으려 들면
     * `openInputStream`이 `FileNotFoundException`/`SecurityException`을 던져 잡는 곳 없이 앱이
     * 죽는다.
     */
    suspend fun exists(context: Context, source: BookSource): Boolean = withContext(Dispatchers.IO) {
        try {
            when (source) {
                // openInputStream 성공 여부로 판단한다 — 실제 읽기(readBytes)가 쓰는 것과 똑같은 경로라,
                // "이 경로로 읽을 수 있는가"를 가장 정확하게 흉내낸다. DocumentFile.exists()는
                // content:// SAF 문서 URI 전용이라 file:// URI(테스트에서 씀)에는 안 통한다.
                is BookSource.PlainTxt -> context.contentResolver.openInputStream(source.uri)?.use { } != null
                is BookSource.ZipEntryTxt -> zipEntryExists(context, source)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun zipEntryExists(context: Context, source: BookSource.ZipEntryTxt): Boolean {
        return context.contentResolver.openInputStream(source.zipUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == source.entryName) return@use true
                    entry = zis.nextEntry
                }
                false
            }
        } == true
    }

    private fun readBytes(context: Context, source: BookSource): ByteArray {
        return when (source) {
            is BookSource.PlainTxt -> {
                context.contentResolver.openInputStream(source.uri)?.use { it.readBytes() } ?: ByteArray(0)
            }
            is BookSource.ZipEntryTxt -> {
                context.contentResolver.openInputStream(source.zipUri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        var result: ByteArray? = null
                        while (entry != null) {
                            if (entry.name == source.entryName) {
                                result = zis.readBytes()
                                break
                            }
                            entry = zis.nextEntry
                        }
                        result ?: ByteArray(0)
                    }
                } ?: ByteArray(0)
            }
        }
    }
}
