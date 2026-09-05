package com.moonkata.textreader.ui.theme

import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `deriveColorScheme` is what makes the library/TOC/search/settings screens follow the same
 * reading theme (Light/Dark/Sepia/Custom) as the reader viewer itself, instead of silently
 * following the phone's system dark mode — this covers that every preset's background/text ends
 * up matching `ReaderThemePresets` (the source of truth the viewer itself uses), and that a
 * Custom theme's dark/light base is picked correctly from its background's luminance.
 */
class ThemeTest {

    @Test
    fun lightPreset_usesReaderThemePresetsColors() {
        val scheme = deriveColorScheme(ReaderSettings(themePreset = ThemePreset.LIGHT))
        assertEquals(ReaderThemePresets.LIGHT.background, scheme.background)
        assertEquals(ReaderThemePresets.LIGHT.text, scheme.onBackground)
        assertEquals(scheme.background, scheme.surface)
        assertEquals(scheme.onBackground, scheme.onSurface)
    }

    @Test
    fun darkPreset_usesReaderThemePresetsColors() {
        val scheme = deriveColorScheme(ReaderSettings(themePreset = ThemePreset.DARK))
        assertEquals(ReaderThemePresets.DARK.background, scheme.background)
        assertEquals(ReaderThemePresets.DARK.text, scheme.onBackground)
    }

    @Test
    fun sepiaPreset_usesReaderThemePresetsColors() {
        val scheme = deriveColorScheme(ReaderSettings(themePreset = ThemePreset.SEPIA))
        assertEquals(ReaderThemePresets.SEPIA.background, scheme.background)
        assertEquals(ReaderThemePresets.SEPIA.text, scheme.onBackground)
    }

    @Test
    fun customPreset_withDarkBackground_usesCustomColorsAndTheDarkBasePalette() {
        val settings = ReaderSettings(
            themePreset = ThemePreset.CUSTOM,
            customBackgroundColorArgb = 0xFF000000.toInt(),
            customTextColorArgb = 0xFFFFFFFF.toInt(),
        )
        val scheme = deriveColorScheme(settings)
        assertEquals(ReaderThemePresets.forSettings(settings).background, scheme.background)
        assertEquals(ReaderThemePresets.forSettings(settings).text, scheme.onBackground)
        // A dark custom background should pick the dark palette's accent colors (primary etc.),
        // not just override background/text — proves the luminance-based base selection, not only
        // that the override itself works.
        assertEquals(Purple80, scheme.primary)
    }

    @Test
    fun customPreset_withLightBackground_usesCustomColorsAndTheLightBasePalette() {
        val settings = ReaderSettings(
            themePreset = ThemePreset.CUSTOM,
            customBackgroundColorArgb = 0xFFFFFFFF.toInt(),
            customTextColorArgb = 0xFF000000.toInt(),
        )
        val scheme = deriveColorScheme(settings)
        assertEquals(ReaderThemePresets.forSettings(settings).background, scheme.background)
        assertEquals(ReaderThemePresets.forSettings(settings).text, scheme.onBackground)
        assertEquals(Purple40, scheme.primary)
    }
}
