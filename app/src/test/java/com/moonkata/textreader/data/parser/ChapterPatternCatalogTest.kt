package com.moonkata.textreader.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 자기 검증(각 프리셋의 example이 실제로 자기 pattern에 매칭되는지, id 중복 방지)과
 * buildRegexList의 조합/제외 로직. id 유일성 검증은 지금 프리셋이 하나뿐이라 당장은 자명하지만,
 * 나중에 프리셋을 추가할 때 복붙 실수를 잡아주는 회귀 방지용. (커스텀 정규식 오류 처리·챕터
 * 중복집계 방지는 ChapterDetectorEdgeCaseTest에서 이미 다룸)
 */
class ChapterPatternCatalogTest {

    @Test
    fun everyPreset_ownExampleMatchesItsOwnPattern() {
        for (preset in ChapterPatternCatalog.presets) {
            assertTrue(
                "프리셋 '${preset.id}'의 example(\"${preset.example}\")이 자기 pattern에 매칭돼야 함",
                preset.pattern.matches(preset.example),
            )
        }
    }

    @Test
    fun everyPreset_hasAUniqueId() {
        val ids = ChapterPatternCatalog.presets.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun buildRegexList_withNothingEnabledAndNoCustomPatterns_isEmpty() {
        val patterns = ChapterPatternCatalog.buildRegexList(enabledIds = emptySet(), customPatterns = emptySet())

        assertTrue(patterns.isEmpty())
    }

    @Test
    fun buildRegexList_disablingAPreset_excludesItsPattern() {
        val allDisabled = ChapterPatternCatalog.buildRegexList(enabledIds = emptySet(), customPatterns = emptySet())
        val allEnabled = ChapterPatternCatalog.buildRegexList(
            enabledIds = ChapterPatternCatalog.defaultEnabledIds,
            customPatterns = emptySet(),
        )

        assertEquals(0, allDisabled.size)
        assertEquals(ChapterPatternCatalog.presets.size, allEnabled.size)
    }

    @Test
    fun buildRegexList_putsBuiltinPatternsBeforeCustomPatterns() {
        val patterns = ChapterPatternCatalog.buildRegexList(
            enabledIds = ChapterPatternCatalog.defaultEnabledIds,
            customPatterns = setOf("""^Chapter \d+$"""),
        )

        assertEquals(ChapterPatternCatalog.presets.size + 1, patterns.size)
        assertEquals(ChapterPatternCatalog.presets.map { it.pattern.pattern }, patterns.take(ChapterPatternCatalog.presets.size).map { it.pattern })
    }
}
