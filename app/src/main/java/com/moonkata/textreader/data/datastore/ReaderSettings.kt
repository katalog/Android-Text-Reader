package com.moonkata.textreader.data.datastore

import com.moonkata.textreader.data.parser.ChapterPatternCatalog
import com.moonkata.textreader.model.FolderSortOption

enum class ThemePreset { LIGHT, DARK, SEPIA, CUSTOM }
enum class PageTurnMode { HORIZONTAL_PAGE, VERTICAL_SCROLL }
enum class OrientationLock { AUTO, PORTRAIT, LANDSCAPE }
enum class LineBreakMode { PRESERVE, REFLOW }
enum class AutoAdvanceMode { OFF, TIMER, TTS }

/**
 * What a page-turn gesture (touch zone or swipe direction) does. Each of the six gestures
 * (touch left/right, swipe left/right/up/down) is assigned one of these independently, so e.g.
 * swiping up can jump chapters while a left tap still turns the page normally.
 */
enum class PageGestureAction { PREVIOUS_PAGE, NEXT_PAGE, PREVIOUS_CHAPTER_JUMP, NEXT_CHAPTER_JUMP }

/** Transition effect when turning a page. NONE: instant switch, SLIDE: both pages slide together, COVER: the new page slides over the top. */
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
    val chapterJumpDivisions: Int = 4,
    val autoAdvanceMode: AutoAdvanceMode = AutoAdvanceMode.OFF,
    val autoPageTurnIntervalSeconds: Int = 15,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val lastUsedSafTreeUri: String? = null,
    val librarySortOption: FolderSortOption = FolderSortOption.NAME_ASC,
    val chapterPatternEnabledIds: Set<String> = ChapterPatternCatalog.defaultEnabledIds,
    val chapterCustomPatterns: Set<String> = emptySet(),
    val touchLeftAction: PageGestureAction = PageGestureAction.PREVIOUS_PAGE,
    val touchRightAction: PageGestureAction = PageGestureAction.NEXT_PAGE,
    val swipeLeftAction: PageGestureAction = PageGestureAction.NEXT_PAGE,
    val swipeRightAction: PageGestureAction = PageGestureAction.PREVIOUS_PAGE,
    val swipeUpAction: PageGestureAction = PageGestureAction.NEXT_CHAPTER_JUMP,
    val swipeDownAction: PageGestureAction = PageGestureAction.PREVIOUS_CHAPTER_JUMP,
    val pageTransitionAnimation: PageTransitionAnimation = PageTransitionAnimation.NONE,
    // VSCode reading-position sync (.docs/VSCODE_SYNC_PLAN.md) — the Supabase URL/publishable key are
    // fine to be public anyway (RLS is the actual line of defense), so they're hardcoded in
    // SupabaseConfig; this settings class holds only the shared secret that actually needs
    // protecting (§1 "secret management" decision).
    val supabaseSharedSecret: String = "",
    /** The secret value that last passed a connection test — shown as "connected" only when it matches
     * [supabaseSharedSecret]. Changing the secret automatically makes this differ, so re-verification
     * is naturally required without any separate invalidation logic. */
    val supabaseVerifiedSecret: String = "",
    // PC tray-server file sync (.docs/PC_SYNC_SERVER_PLAN.md) — same pattern as Supabase: just a host +
    // shared secret is enough, no real account credentials (since we control the protocol ourselves).
    val pcSyncHost: String = "",
    val pcSyncSecret: String = "",
    /** The (host, secret) pair from the last successful connection test — shown as "connected" only
     * when it exactly matches the current settings values. */
    val pcSyncVerifiedHost: String = "",
    val pcSyncVerifiedSecret: String = "",
    /** SHA-256 fingerprint of the PC server's self-signed certificate — on a successful connection
     * test, the certificate received at that time is stored as trusted for "this PC" (TOFU, see §5).
     * Not a value the user enters directly; the app fills it in automatically during the connection
     * test. */
    val pcSyncPinnedFingerprint: String = "",
    /** The time the last successful PC sync finished (device clock, epoch millis) — 0 means it has
     * never succeeded yet. If a remote file's
     * [last-modified time][com.moonkata.textreader.data.sync.PcRemoteFile.lastModifiedMillis] is later
     * than this value, it's treated as "changed on the PC since then" and re-fetched even if its size
     * matches the local copy (see PcSyncFileManager.kt — comparing size alone would miss content that
     * happened to change to the same character count). */
    val pcSyncLastCompletedAtMillis: Long = 0L,
)
