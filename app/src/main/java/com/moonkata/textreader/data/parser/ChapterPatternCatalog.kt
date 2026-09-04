package com.moonkata.textreader.data.parser

import androidx.annotation.StringRes
import com.moonkata.textreader.R

/** One regex pattern used for automatic table-of-contents (chapter) detection — a built-in preset. */
data class ChapterPatternPreset(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val exampleRes: Int,
    val pattern: Regex,
)

object ChapterPatternCatalog {

    val presets: List<ChapterPatternPreset> = listOf(
        ChapterPatternPreset(
            id = "hash",
            labelRes = R.string.chapter_pattern_preset_hash_label,
            exampleRes = R.string.chapter_pattern_preset_hash_example,
            pattern = Regex("""^##.*$"""),
        ),
    )

    val defaultEnabledIds: Set<String> = presets.map { it.id }.toSet()

    /** Combines the enabled built-in presets with the user's custom regexes into the list actually used. Invalid regexes are silently filtered out. */
    fun buildRegexList(enabledIds: Set<String>, customPatterns: Set<String>): List<Regex> {
        val builtins = presets.filter { it.id in enabledIds }.map { it.pattern }
        val customs = customPatterns.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        return builtins + customs
    }
}
