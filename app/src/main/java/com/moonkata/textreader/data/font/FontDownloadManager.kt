package com.moonkata.textreader.data.font

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class FontDownloadState {
    data object NotDownloaded : FontDownloadState()
    data class Downloading(val progress: Float) : FontDownloadState()
    data object Downloaded : FontDownloadState()
    data class Failed(val message: String) : FontDownloadState()
}

class FontDownloadManager(private val context: Context) {

    private fun fontsDir(): File = File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    fun localFile(entry: FontCatalogEntry): File = File(fontsDir(), entry.localFileName)

    fun isDownloaded(entry: FontCatalogEntry): Boolean = localFile(entry).exists()

    fun download(entry: FontCatalogEntry): Flow<FontDownloadState> = flow {
        emit(FontDownloadState.Downloading(0f))
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(entry.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                connect()
            }
            val totalSize = connection.contentLength
            val target = localFile(entry)
            val tempFile = File(target.parentFile, "${target.name}.part")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var totalRead = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (totalSize > 0) {
                            emit(FontDownloadState.Downloading(totalRead.toFloat() / totalSize))
                        }
                    }
                }
            }
            tempFile.renameTo(target)
            emit(FontDownloadState.Downloaded)
        } catch (e: Exception) {
            emit(FontDownloadState.Failed(e.message ?: "다운로드 실패"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun delete(entry: FontCatalogEntry) {
        localFile(entry).delete()
    }
}
