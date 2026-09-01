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
