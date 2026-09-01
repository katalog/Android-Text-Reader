package com.moonkata.textreader.data.font

data class FontCatalogEntry(
    val id: String,
    val displayName: String,
    val license: String,
    val downloadUrl: String,
    val localFileName: String,
)

/**
 * 가독성 좋은 무료(오픈 라이선스) 한글 폰트 큐레이션 목록.
 * 다운로드 URL은 각 폰트 배포처(GitHub 등)가 바뀌면 깨질 수 있으므로,
 * 실제 배포 전에는 링크가 살아있는지 한 번 더 확인하는 것을 권장.
 */
object FontCatalog {
    const val SYSTEM_DEFAULT_ID = "system_default"

    val entries: List<FontCatalogEntry> = listOf(
        FontCatalogEntry(
            id = "nanum_gothic",
            displayName = "나눔고딕",
            license = "OFL-1.1",
            // naver/nanumfont 저장소엔 "나눔고딕코딩"(고정폭) ZIP 릴리스만 있고 fonts/ 폴더 자체가
            // 없어졌다 — 실제 나눔고딕/나눔명조는 Google Fonts 저장소에 있는 걸로 대체.
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
            // ridi/RIDIBatang 저장소 자체가 없어졌다(리디 쪽 저장소 정리로 추정) — 원문 라이선스
            // 고지(OFL-1.1, 저작권 리디주식회사)를 그대로 유지해 미러링하는 fonts-archive로 대체.
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
