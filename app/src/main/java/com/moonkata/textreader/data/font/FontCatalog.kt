package com.moonkata.textreader.data.font

data class FontCatalogEntry(
    val id: String,
    val displayName: String,
    val license: String,
    val downloadUrl: String,
    val localFileName: String,
)

/**
 * A curated list of readable, free (open-license) Korean fonts.
 * Download URLs can break if a font's distribution source (GitHub, etc.) changes, so it's
 * recommended to double-check that links are still alive before an actual release.
 */
object FontCatalog {
    const val SYSTEM_DEFAULT_ID = "system_default"

    val entries: List<FontCatalogEntry> = listOf(
        FontCatalogEntry(
            id = "nanum_gothic",
            displayName = "나눔고딕",
            license = "OFL-1.1",
            // The naver/nanumfont repo now only has a "NanumGothicCoding" (monospace) ZIP release, and
            // the fonts/ folder itself is gone — using the actual NanumGothic/NanumMyeongjo from the
            // Google Fonts repo instead.
            downloadUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/nanumgothic/NanumGothic-Regular.ttf",
            localFileName = "nanum_gothic.ttf",
        ),
        FontCatalogEntry(
            id = "nanum_myeongjo",
            displayName = "나눔명조",
            license = "OFL-1.1",
            downloadUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/nanummyeongjo/NanumMyeongjo-Regular.ttf",
            localFileName = "nanum_myeongjo.ttf",
        ),
        FontCatalogEntry(
            id = "noto_sans_kr",
            displayName = "본고딕 (Noto Sans KR)",
            license = "OFL-1.1",
            downloadUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanskr/NotoSansKR%5Bwght%5D.ttf",
            localFileName = "noto_sans_kr.ttf",
        ),
        FontCatalogEntry(
            id = "ridibatang",
            displayName = "리디바탕",
            license = "OFL-1.1",
            // The ridi/RIDIBatang repo itself is gone (presumably RIDI cleaned up their repos) —
            // using fonts-archive instead, which mirrors it while keeping the original license
            // notice intact (OFL-1.1, copyright RIDI Corporation).
            downloadUrl = "https://raw.githubusercontent.com/fonts-archive/RIDIBatang/main/RIDIBatang.otf",
            localFileName = "ridibatang.otf",
        ),
        FontCatalogEntry(
            id = "pretendard",
            displayName = "Pretendard",
            license = "OFL-1.1",
            downloadUrl = "https://raw.githubusercontent.com/orioncactus/pretendard/main/packages/pretendard/dist/public/static/Pretendard-Regular.otf",
            localFileName = "pretendard.otf",
        ),
    )

    fun findById(id: String): FontCatalogEntry? = entries.find { it.id == id }
}
