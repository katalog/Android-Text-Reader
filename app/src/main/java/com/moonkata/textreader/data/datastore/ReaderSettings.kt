package com.moonkata.textreader.data.datastore

import com.moonkata.textreader.data.parser.ChapterPatternCatalog
import com.moonkata.textreader.model.FolderSortOption

enum class ThemePreset { LIGHT, DARK, SEPIA, CUSTOM }
enum class PageTurnMode { HORIZONTAL_PAGE, VERTICAL_SCROLL }
enum class OrientationLock { AUTO, PORTRAIT, LANDSCAPE }
enum class LineBreakMode { PRESERVE, REFLOW }
enum class AutoAdvanceMode { OFF, TIMER, TTS }

/** 화면 좌/우 탭으로 페이지를 넘길 때의 매핑. STANDARD: 왼쪽=이전/오른쪽=다음, BOTH_NEXT: 양쪽 다 다음. */
enum class TouchTurnMode { STANDARD, BOTH_NEXT }

/** 좌/우 스와이프로 페이지를 넘길 때의 매핑. STANDARD: <- 다음/-> 이전, BOTH_NEXT: 양방향 다 다음. */
enum class SwipeTurnMode { STANDARD, BOTH_NEXT }

/** 페이지 넘길 때 전환 효과. NONE: 즉시 전환, SLIDE: 두 페이지가 함께 밀림, COVER: 새 페이지가 위로 덮음. */
enum class PageTransitionAnimation { NONE, SLIDE, COVER }

data class ReaderSettings(
    val fontFamilyId: String = "system_default",
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.5f,
    val letterSpacingSp: Float = 0f,
    val marginHorizontalDp: Float = 20f,
    val marginTopDp: Float = 24f,
    val marginBottomDp: Float = 24f,
    val themePreset: ThemePreset = ThemePreset.LIGHT,
    val customBackgroundColorArgb: Int = 0xFFFFFFFF.toInt(),
    val customTextColorArgb: Int = 0xFF000000.toInt(),
    val pageTurnMode: PageTurnMode = PageTurnMode.VERTICAL_SCROLL,
    val brightnessOverrideEnabled: Boolean = false,
    val brightnessValue: Float = 0.5f,
    val orientationLock: OrientationLock = OrientationLock.AUTO,
    val lineBreakMode: LineBreakMode = LineBreakMode.PRESERVE,
    val keepScreenOnEnabled: Boolean = true,
    val volumeKeyPagingEnabled: Boolean = false,
    val chapterJumpEnabled: Boolean = false,
    val chapterJumpDivisions: Int = 4,
    val autoAdvanceMode: AutoAdvanceMode = AutoAdvanceMode.OFF,
    val autoPageTurnIntervalSeconds: Int = 15,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val lastUsedSafTreeUri: String? = null,
    val librarySortOption: FolderSortOption = FolderSortOption.NAME_ASC,
    val chapterPatternEnabledIds: Set<String> = ChapterPatternCatalog.defaultEnabledIds,
    val chapterCustomPatterns: Set<String> = emptySet(),
    val touchTurnMode: TouchTurnMode = TouchTurnMode.STANDARD,
    val swipeTurnMode: SwipeTurnMode = SwipeTurnMode.STANDARD,
    val pageTransitionAnimation: PageTransitionAnimation = PageTransitionAnimation.NONE,
    // VSCode 읽기 위치 동기화(.docs/VSCODE_SYNC_PLAN.md) — Supabase URL/publishable key는 어차피
    // 공개돼도 되는 값(RLS가 실제 방어선)이라 SupabaseConfig에 고정값으로 박아두고, 여기 설정에는
    // 진짜 지켜야 하는 공유 시크릿만 둔다(§1 "시크릿 관리" 결정).
    val supabaseSharedSecret: String = "",
    /** 마지막으로 연결 테스트에 성공한 시크릿 값 — [supabaseSharedSecret]과 같을 때만 "연결됨" 표시.
     * 시크릿을 바꾸면 자동으로 이 값과 달라지므로 별도 무효화 로직 없이 자연스럽게 재검증을 요구한다. */
    val supabaseVerifiedSecret: String = "",
)
