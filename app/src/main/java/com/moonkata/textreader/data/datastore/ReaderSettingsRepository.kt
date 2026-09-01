package com.moonkata.textreader.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moonkata.textreader.model.FolderSortOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "reader_settings")

class ReaderSettingsRepository(private val context: Context) {

    private object Keys {
        val FONT_FAMILY_ID = stringPreferencesKey("font_family_id")
        val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        val LINE_HEIGHT_MULTIPLIER = floatPreferencesKey("line_height_multiplier")
        val LETTER_SPACING_SP = floatPreferencesKey("letter_spacing_sp")
        val MARGIN_HORIZONTAL_DP = floatPreferencesKey("margin_horizontal_dp")
        val MARGIN_TOP_DP = floatPreferencesKey("margin_top_dp")
        val MARGIN_BOTTOM_DP = floatPreferencesKey("margin_bottom_dp")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val CUSTOM_BG_COLOR = intPreferencesKey("custom_bg_color")
        val CUSTOM_TEXT_COLOR = intPreferencesKey("custom_text_color")
        val PAGE_TURN_MODE = stringPreferencesKey("page_turn_mode")
        val BRIGHTNESS_OVERRIDE_ENABLED = booleanPreferencesKey("brightness_override_enabled")
        val BRIGHTNESS_VALUE = floatPreferencesKey("brightness_value")
        val ORIENTATION_LOCK = stringPreferencesKey("orientation_lock")
        val LINE_BREAK_MODE = stringPreferencesKey("line_break_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEY_PAGING = booleanPreferencesKey("volume_key_paging")
        // 저장된 키 이름은 예전 "건너뛰기" 명칭 그대로 유지 — 바꾸면 기존에 저장된 챕터 점프 설정이 날아간다.
        val CHAPTER_JUMP_ENABLED = booleanPreferencesKey("chapter_skip_enabled")
        val CHAPTER_JUMP_DIVISIONS = intPreferencesKey("chapter_skip_divisions")
        val AUTO_ADVANCE_MODE = stringPreferencesKey("auto_advance_mode")
        val AUTO_PAGE_TURN_INTERVAL_SECONDS = intPreferencesKey("auto_page_turn_interval_seconds")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val LAST_USED_SAF_TREE_URI = stringPreferencesKey("last_used_saf_tree_uri")
        val LIBRARY_SORT_OPTION = stringPreferencesKey("library_sort_option")
        val CHAPTER_PATTERN_ENABLED_IDS = stringSetPreferencesKey("chapter_pattern_enabled_ids")
        val CHAPTER_CUSTOM_PATTERNS = stringSetPreferencesKey("chapter_custom_patterns")
        val TOUCH_TURN_MODE = stringPreferencesKey("touch_turn_mode")
        val SWIPE_TURN_MODE = stringPreferencesKey("swipe_turn_mode")
        val PAGE_TRANSITION_ANIMATION = stringPreferencesKey("page_transition_animation")
        val SUPABASE_SHARED_SECRET = stringPreferencesKey("supabase_shared_secret")
        val SUPABASE_VERIFIED_SECRET = stringPreferencesKey("supabase_verified_secret")
    }

    val settingsFlow: Flow<ReaderSettings> = context.dataStore.data.map { prefs ->
        val defaults = ReaderSettings()
        ReaderSettings(
            fontFamilyId = prefs[Keys.FONT_FAMILY_ID] ?: defaults.fontFamilyId,
            fontSizeSp = prefs[Keys.FONT_SIZE_SP] ?: defaults.fontSizeSp,
            lineHeightMultiplier = prefs[Keys.LINE_HEIGHT_MULTIPLIER] ?: defaults.lineHeightMultiplier,
            letterSpacingSp = prefs[Keys.LETTER_SPACING_SP] ?: defaults.letterSpacingSp,
            marginHorizontalDp = prefs[Keys.MARGIN_HORIZONTAL_DP] ?: defaults.marginHorizontalDp,
            marginTopDp = prefs[Keys.MARGIN_TOP_DP] ?: defaults.marginTopDp,
            marginBottomDp = prefs[Keys.MARGIN_BOTTOM_DP] ?: defaults.marginBottomDp,
            themePreset = prefs[Keys.THEME_PRESET]?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() } ?: defaults.themePreset,
            customBackgroundColorArgb = prefs[Keys.CUSTOM_BG_COLOR] ?: defaults.customBackgroundColorArgb,
            customTextColorArgb = prefs[Keys.CUSTOM_TEXT_COLOR] ?: defaults.customTextColorArgb,
            pageTurnMode = prefs[Keys.PAGE_TURN_MODE]?.let { runCatching { PageTurnMode.valueOf(it) }.getOrNull() } ?: defaults.pageTurnMode,
            brightnessOverrideEnabled = prefs[Keys.BRIGHTNESS_OVERRIDE_ENABLED] ?: defaults.brightnessOverrideEnabled,
            brightnessValue = prefs[Keys.BRIGHTNESS_VALUE] ?: defaults.brightnessValue,
            orientationLock = prefs[Keys.ORIENTATION_LOCK]?.let { runCatching { OrientationLock.valueOf(it) }.getOrNull() } ?: defaults.orientationLock,
            lineBreakMode = prefs[Keys.LINE_BREAK_MODE]?.let { runCatching { LineBreakMode.valueOf(it) }.getOrNull() } ?: defaults.lineBreakMode,
            keepScreenOnEnabled = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOnEnabled,
            volumeKeyPagingEnabled = prefs[Keys.VOLUME_KEY_PAGING] ?: defaults.volumeKeyPagingEnabled,
            chapterJumpEnabled = prefs[Keys.CHAPTER_JUMP_ENABLED] ?: defaults.chapterJumpEnabled,
            chapterJumpDivisions = prefs[Keys.CHAPTER_JUMP_DIVISIONS] ?: defaults.chapterJumpDivisions,
            autoAdvanceMode = prefs[Keys.AUTO_ADVANCE_MODE]?.let { runCatching { AutoAdvanceMode.valueOf(it) }.getOrNull() } ?: defaults.autoAdvanceMode,
            autoPageTurnIntervalSeconds = prefs[Keys.AUTO_PAGE_TURN_INTERVAL_SECONDS] ?: defaults.autoPageTurnIntervalSeconds,
            ttsSpeechRate = prefs[Keys.TTS_SPEECH_RATE] ?: defaults.ttsSpeechRate,
            ttsPitch = prefs[Keys.TTS_PITCH] ?: defaults.ttsPitch,
            lastUsedSafTreeUri = prefs[Keys.LAST_USED_SAF_TREE_URI] ?: defaults.lastUsedSafTreeUri,
            librarySortOption = prefs[Keys.LIBRARY_SORT_OPTION]?.let { runCatching { FolderSortOption.valueOf(it) }.getOrNull() } ?: defaults.librarySortOption,
            chapterPatternEnabledIds = prefs[Keys.CHAPTER_PATTERN_ENABLED_IDS] ?: defaults.chapterPatternEnabledIds,
            chapterCustomPatterns = prefs[Keys.CHAPTER_CUSTOM_PATTERNS] ?: defaults.chapterCustomPatterns,
            touchTurnMode = prefs[Keys.TOUCH_TURN_MODE]?.let { runCatching { TouchTurnMode.valueOf(it) }.getOrNull() } ?: defaults.touchTurnMode,
            swipeTurnMode = prefs[Keys.SWIPE_TURN_MODE]?.let { runCatching { SwipeTurnMode.valueOf(it) }.getOrNull() } ?: defaults.swipeTurnMode,
            pageTransitionAnimation = prefs[Keys.PAGE_TRANSITION_ANIMATION]
                ?.let { runCatching { PageTransitionAnimation.valueOf(it) }.getOrNull() } ?: defaults.pageTransitionAnimation,
            supabaseSharedSecret = prefs[Keys.SUPABASE_SHARED_SECRET] ?: defaults.supabaseSharedSecret,
            supabaseVerifiedSecret = prefs[Keys.SUPABASE_VERIFIED_SECRET] ?: defaults.supabaseVerifiedSecret,
        )
    }

    suspend fun updateFontFamilyId(value: String) = edit { it[Keys.FONT_FAMILY_ID] = value }
    suspend fun updateFontSizeSp(value: Float) = edit { it[Keys.FONT_SIZE_SP] = value }
    suspend fun updateLineHeightMultiplier(value: Float) = edit { it[Keys.LINE_HEIGHT_MULTIPLIER] = value }
    suspend fun updateLetterSpacingSp(value: Float) = edit { it[Keys.LETTER_SPACING_SP] = value }
    suspend fun updateMarginHorizontalDp(value: Float) = edit { it[Keys.MARGIN_HORIZONTAL_DP] = value }
    suspend fun updateMarginTopDp(value: Float) = edit { it[Keys.MARGIN_TOP_DP] = value }
    suspend fun updateMarginBottomDp(value: Float) = edit { it[Keys.MARGIN_BOTTOM_DP] = value }
    suspend fun updateThemePreset(value: ThemePreset) = edit { it[Keys.THEME_PRESET] = value.name }
    suspend fun updateCustomColors(background: Int, text: Int) = edit {
        it[Keys.CUSTOM_BG_COLOR] = background
        it[Keys.CUSTOM_TEXT_COLOR] = text
    }
    suspend fun updatePageTurnMode(value: PageTurnMode) = edit { it[Keys.PAGE_TURN_MODE] = value.name }
    suspend fun updateBrightnessOverrideEnabled(value: Boolean) = edit { it[Keys.BRIGHTNESS_OVERRIDE_ENABLED] = value }
    suspend fun updateBrightnessValue(value: Float) = edit { it[Keys.BRIGHTNESS_VALUE] = value }
    suspend fun updateOrientationLock(value: OrientationLock) = edit { it[Keys.ORIENTATION_LOCK] = value.name }
    suspend fun updateLineBreakMode(value: LineBreakMode) = edit { it[Keys.LINE_BREAK_MODE] = value.name }
    suspend fun updateKeepScreenOnEnabled(value: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = value }
    suspend fun updateVolumeKeyPagingEnabled(value: Boolean) = edit { it[Keys.VOLUME_KEY_PAGING] = value }
    suspend fun updateChapterJumpEnabled(value: Boolean) = edit { it[Keys.CHAPTER_JUMP_ENABLED] = value }
    suspend fun updateChapterJumpDivisions(value: Int) = edit { it[Keys.CHAPTER_JUMP_DIVISIONS] = value }
    suspend fun updateAutoAdvanceMode(value: AutoAdvanceMode) = edit { it[Keys.AUTO_ADVANCE_MODE] = value.name }
    suspend fun updateAutoPageTurnIntervalSeconds(value: Int) = edit { it[Keys.AUTO_PAGE_TURN_INTERVAL_SECONDS] = value }
    suspend fun updateTtsSpeechRate(value: Float) = edit { it[Keys.TTS_SPEECH_RATE] = value }
    suspend fun updateTtsPitch(value: Float) = edit { it[Keys.TTS_PITCH] = value }
    suspend fun updateLastUsedSafTreeUri(value: String?) = edit {
        if (value != null) it[Keys.LAST_USED_SAF_TREE_URI] = value else it.remove(Keys.LAST_USED_SAF_TREE_URI)
    }
    suspend fun updateLibrarySortOption(value: FolderSortOption) = edit { it[Keys.LIBRARY_SORT_OPTION] = value.name }
    suspend fun updateChapterPatternEnabledIds(value: Set<String>) = edit { it[Keys.CHAPTER_PATTERN_ENABLED_IDS] = value }
    suspend fun updateChapterCustomPatterns(value: Set<String>) = edit { it[Keys.CHAPTER_CUSTOM_PATTERNS] = value }
    suspend fun updateTouchTurnMode(value: TouchTurnMode) = edit { it[Keys.TOUCH_TURN_MODE] = value.name }
    suspend fun updateSwipeTurnMode(value: SwipeTurnMode) = edit { it[Keys.SWIPE_TURN_MODE] = value.name }
    suspend fun updatePageTransitionAnimation(value: PageTransitionAnimation) = edit { it[Keys.PAGE_TRANSITION_ANIMATION] = value.name }
    /** [verifiedSecret]을 같이 넘기면(연결 테스트 성공 시) 한 번의 커밋으로 시크릿+검증 상태를 함께 저장한다. */
    suspend fun updateSupabaseSharedSecret(value: String, verifiedSecret: String? = null) = edit {
        it[Keys.SUPABASE_SHARED_SECRET] = value
        if (verifiedSecret != null) it[Keys.SUPABASE_VERIFIED_SECRET] = verifiedSecret
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
