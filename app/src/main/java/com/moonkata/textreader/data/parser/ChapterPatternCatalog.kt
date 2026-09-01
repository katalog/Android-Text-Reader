package com.moonkata.textreader.data.parser

/** 목차(챕터) 자동인식에 쓰이는 정규식 패턴 하나 — 내장 프리셋. */
data class ChapterPatternPreset(
    val id: String,
    val label: String,
    val example: String,
    val pattern: Regex,
)

object ChapterPatternCatalog {

    val presets: List<ChapterPatternPreset> = listOf(
        ChapterPatternPreset(
            id = "hash",
            label = "## 로 시작",
            example = "## 제1장 평범하기 그지없는 노르만인 수도사",
            pattern = Regex("""^##.*$"""),
        ),
    )

    val defaultEnabledIds: Set<String> = presets.map { it.id }.toSet()

    /** 켜진 내장 패턴 + 사용자가 추가한 커스텀 정규식을 합쳐 실제로 사용할 목록을 만든다. 잘못된 정규식은 조용히 걸러낸다. */
    fun buildRegexList(enabledIds: Set<String>, customPatterns: Set<String>): List<Regex> {
        val builtins = presets.filter { it.id in enabledIds }.map { it.pattern }
        val customs = customPatterns.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        return builtins + customs
    }
}
