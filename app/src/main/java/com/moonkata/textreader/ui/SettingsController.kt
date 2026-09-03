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
 * `QuickSettingsSheet`/`FontPickerSheet`/`ChapterPatternSheet`가 실제로 필요로 하는 것만 뽑은
 * 인터페이스 — 전부 책이 열려있지 않아도(서재 화면에서도) 의미 있는 "앱 전역 설정" 조작이다.
 * `ReaderViewModel`과 `LibraryViewModel` 둘 다 이 인터페이스를 구현해서, 같은 설정 시트를 리더
 * 화면과 서재 화면 양쪽에서 재사용할 수 있게 한다 — 서재 화면에서 책을 열지 않고도 폰트/여백/테마/
 * VSCode 동기화 등을 설정할 수 있어야 한다는 실사용 피드백으로 추가.
 *
 * 리더 쪽 구현은 값 저장 외에 부가 동작(자동 넘김 모드가 TTS면 그 자리에서 낭독 시작, 챕터 점프 모드
 * 전환 시 방문 이력 초기화 등)이 곁들여지고, 서재 쪽 구현은 열린 책이 없으니 값만 저장한다 — 나중에
 * 실제로 책을 열면 `ReaderViewModel`이 저장된 설정을 그대로 읽어 필요한 부가 동작을 그때 적용한다.
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

    /** 형식이 올바르지 않은 정규식이면 추가하지 않고 false를 반환한다. */
    fun addCustomChapterPattern(pattern: String): Boolean
    fun removeCustomChapterPattern(pattern: String)

    fun downloadFont(entry: FontCatalogEntry): Flow<FontDownloadState>
    fun isFontDownloaded(entry: FontCatalogEntry): Boolean

    suspend fun testSupabaseConnection(secret: String): Boolean
}
