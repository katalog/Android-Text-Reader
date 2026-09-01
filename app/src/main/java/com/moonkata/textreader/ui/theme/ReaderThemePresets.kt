package com.moonkata.textreader.ui.theme

import androidx.compose.ui.graphics.Color
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.ThemePreset

data class ReaderColors(val background: Color, val text: Color)

object ReaderThemePresets {
    val LIGHT = ReaderColors(Color(0xFFFFFFFF), Color(0xFF1A1A1A))
    val DARK = ReaderColors(Color(0xFF121212), Color(0xFFE0E0E0))
    val SEPIA = ReaderColors(Color(0xFFF4ECD8), Color(0xFF3B2F1E))

    fun forSettings(settings: ReaderSettings): ReaderColors = when (settings.themePreset) {
        ThemePreset.LIGHT -> LIGHT
        ThemePreset.DARK -> DARK
        ThemePreset.SEPIA -> SEPIA
        ThemePreset.CUSTOM -> ReaderColors(
            Color(settings.customBackgroundColorArgb),
            Color(settings.customTextColorArgb),
        )
    }
}
