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
     * Checks whether [source] can actually be opened right now — returns false if the file has been
     * deleted/moved or SAF permission has been revoked. Used to check ahead of time before showing a
     * "resume reading" candidate: if we skip this check and try to read directly, `openInputStream`
     * can throw `FileNotFoundException`/`SecurityException` with nothing to catch it, crashing the app.
     */
    suspend fun exists(context: Context, source: BookSource): Boolean = withContext(Dispatchers.IO) {
        try {
            when (source) {
                // Judged by whether openInputStream succeeds — this is the exact same path the real
                // read (readBytes) uses, so it's the most accurate stand-in for "can this actually be
                // read". DocumentFile.exists() only works for content:// SAF document URIs, not
                // file:// URIs (which tests use).
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
