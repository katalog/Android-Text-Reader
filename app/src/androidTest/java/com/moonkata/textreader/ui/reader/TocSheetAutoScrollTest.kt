package com.moonkata.textreader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 목차 시트를 열면 지금 읽고 있는 챕터가 수동 스크롤 없이 바로 화면에 보여야 한다(자동 스크롤) —
 * 챕터가 많은 실제 소설에서 목차를 열 때마다 맨 위부터 스크롤해야 했던 문제의 회귀 테스트.
 */
@RunWith(AndroidJUnit4::class)
class TocSheetAutoScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun openingToc_scrollsToCurrentChapterWithoutManualScroll_andJumpsOnClick() {
        val chapters = (1..100).map { Chapter(title = "제${it}장", charOffset = it * 1000) }
        // 현재 위치는 60번째 챕터 한가운데 — 자동 스크롤이 없다면 맨 위(1번째)부터 보여서
        // LazyColumn이 화면 밖 항목까지 조립하지 않는 한 "제60장"은 아예 안 보여야 정상이다.
        val currentOffset = chapters[59].charOffset + 500
        var jumpedTo: Int? = null

        composeTestRule.setContent {
            MaterialTheme {
                TocSheet(
                    chapters = chapters,
                    currentOffset = currentOffset,
                    onJump = { jumpedTo = it },
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("제60장").assertExists()

        composeTestRule.onNodeWithText("제60장").performClick()
        assertEquals(chapters[59].charOffset, jumpedTo)
    }

    @Test
    fun emptyChapterList_showsNoTocMessage() {
        composeTestRule.setContent {
            MaterialTheme {
                TocSheet(chapters = emptyList(), currentOffset = 0, onJump = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("목차를 찾을 수 없어요").assertExists()
    }
}
