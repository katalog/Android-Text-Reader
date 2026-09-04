package com.moonkata.textreader.ui

import com.moonkata.textreader.data.datastore.AutoAdvanceMode
import com.moonkata.textreader.data.datastore.LineBreakMode
import com.moonkata.textreader.data.datastore.OrientationLock
import com.moonkata.textreader.data.datastore.PageTransitionAnimation
import com.moonkata.textreader.data.datastore.PageTurnMode
import com.moonkata.textreader.data.datastore.SwipeTurnMode
import com.moonkata.textreader.data.datastore.ThemePreset
import com.moonkata.textreader.data.datastore.TouchTurnMode
import com.moonkata.textreader.data.font.FontCatalogEntry
import com.moonkata.textreader.data.font.FontDownloadState
import kotlinx.coroutines.flow.Flow

/**
 * An interface distilled down to just what `QuickSettingsSheet`/`FontPickerSheet`/`ChapterPatternSheet`
 * actually need — all "app-wide setting" operations that stay meaningful even without a book open
 * (e.g. from the library screen). Both `ReaderViewModel` and `LibraryViewModel` implement this interface
 * so the same settings sheets can be reused on both the reader screen and the library screen — added
 * based on real-usage feedback that users should be able to configure font/margins/theme/VSCode sync
 * etc. without opening a book from the library screen.
 *
 * The reader-side implementation layers on side effects beyond just persisting the value (e.g. starting
 * TTS narration immediately if the auto-advance mode is set to TTS, resetting the visit history when
 * toggling chapter jump mode), while the library-side implementation just persists the value since there's
 * no open book — later, when a book is actually opened, `ReaderViewModel` reads the persisted settings and
 * applies the necessary side effects at that point.
 */
interface SettingsController {
    fun setFontSizeSp(value: Float)
    fun setLineHeightMultiplier(value: Float)
    fun setLetterSpacingSp(value: Float)
    fun setMarginHorizontalDp(value: Float)
    fun setMarginTopDp(value: Float)
    fun setMarginBottomDp(value: Float)
    fun setThemePreset(value: ThemePreset)
    fun setPageTurnMode(value: PageTurnMode)
    fun setBrightnessOverrideEnabled(value: Boolean)
    fun setBrightnessValue(value: Float)
    fun setOrientationLock(value: OrientationLock)
    fun setLineBreakMode(value: LineBreakMode)
    fun setKeepScreenOnEnabled(value: Boolean)
    fun setVolumeKeyPagingEnabled(value: Boolean)
    fun setChapterJumpEnabled(value: Boolean)
    fun setChapterJumpDivisions(value: Int)
    fun setAutoPageTurnIntervalSeconds(value: Int)
    fun selectFont(fontId: String)
    fun setTouchTurnMode(value: TouchTurnMode)
    fun setSwipeTurnMode(value: SwipeTurnMode)
    fun setPageTransitionAnimation(value: PageTransitionAnimation)
    fun setSupabaseSharedSecret(value: String)
    fun setAutoAdvanceMode(mode: AutoAdvanceMode)

    fun toggleChapterPattern(id: String, enabled: Boolean)

    /** If the pattern is not a well-formed regex, it is not added and false is returned. */
    fun addCustomChapterPattern(pattern: String): Boolean
    fun removeCustomChapterPattern(pattern: String)

    fun downloadFont(entry: FontCatalogEntry): Flow<FontDownloadState>
    fun isFontDownloaded(entry: FontCatalogEntry): Boolean

    suspend fun testSupabaseConnection(secret: String): Boolean

    /** The reason the last [testSupabaseConnection] failed (null if it succeeded or hasn't been tried yet) — shown as-is on screen. */
    fun lastSupabaseTestError(): String?
}
