package com.moonkata.textreader.data.font

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

object FontResolver {
    fun resolve(context: Context, fontId: String): FontFamily {
        if (fontId == FontCatalog.SYSTEM_DEFAULT_ID) return FontFamily.Default
        val entry = FontCatalog.findById(fontId) ?: return FontFamily.Default
        val file = FontDownloadManager(context).localFile(entry)
        return if (file.exists()) FontFamily(Font(file)) else FontFamily.Default
    }
}
