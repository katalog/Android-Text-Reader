package com.moonkata.textreader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 검색 시트의 핵심 계약 회귀 테스트:
 * 1. 타이핑만으로는 검색이 실행되지 않고, 제출(버튼/키보드 검색 액션)해야만 실행된다.
 * 2. 시트를 다시 열었을 때 마지막 검색어/결과를 그대로 보여주고, 그걸로 다시 검색을 트리거하지 않는다.
 * 3. 지금 읽고 있는 위치와 가장 가까운 결과가 수동 스크롤 없이 바로 보인다.
 * 4. 시트를 열면 커서가 항상 검색어 맨 끝에 있다(기존 검색어가 없으면 자연히 맨 앞과 같음) — 다시
 *    열자마자 백스페이스로 기존 검색어를 지울 수 있어야 한다는 요구사항의 회귀 테스트.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun typingAlone_doesNotSearch_onlySearchButtonDoes() {
        var searchCallCount = 0
        val canned = listOf(SearchResult(offset = 10, snippet = "찾은 결과 스니펫"))

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { searchCallCount++; canned },
                    initialQuery = "",
                    initialResults = emptyList(),
                    currentOffset = 0,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("본문 검색").performTextInput("테스트")
        composeTestRule.waitForIdle()
        assertEquals("타이핑만으로는 검색이 실행되면 안 됨", 0, searchCallCount)

        composeTestRule.onNodeWithContentDescription("검색").performClick()
        composeTestRule.waitForIdle()

        assertEquals("검색 버튼을 누르면 정확히 한 번 실행돼야 함", 1, searchCallCount)
        composeTestRule.onNodeWithText("찾은 결과 스니펫").assertExists()
    }

    @Test
    fun keyboardSearchAction_alsoTriggersSearch() {
        var searchCallCount = 0
        val canned = listOf(SearchResult(offset = 10, snippet = "키보드로 찾은 결과"))

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { searchCallCount++; canned },
                    initialQuery = "",
                    initialResults = emptyList(),
                    currentOffset = 0,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("본문 검색").performTextInput("테스트")
        composeTestRule.onNodeWithText("본문 검색").performImeAction()
        composeTestRule.waitForIdle()

        assertEquals(1, searchCallCount)
        composeTestRule.onNodeWithText("키보드로 찾은 결과").assertExists()
    }

    @Test
    fun initialQueryAndResults_areShownWithoutCallingOnSearch() {
        var searchCallCount = 0
        val canned = listOf(SearchResult(offset = 10, snippet = "이전 검색 결과"))

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { searchCallCount++; canned },
                    initialQuery = "이전검색어",
                    initialResults = canned,
                    currentOffset = 0,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("이전검색어").assertExists()
        composeTestRule.onNodeWithText("이전 검색 결과").assertExists()
        assertEquals("검색 시트를 다시 열었을 때 자동으로 재검색하면 안 됨", 0, searchCallCount)
    }

    @Test
    fun nearestResultToCurrentOffset_isVisibleWithoutManualScroll() {
        val results = (0 until 100).map { SearchResult(offset = it * 1000, snippet = "결과 $it") }
        val currentOffset = results[70].offset

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { results },
                    initialQuery = "쿼리",
                    initialResults = results,
                    currentOffset = currentOffset,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("결과 70").assertExists()
    }

    @Test
    fun openingWithNoExistingQuery_cursorStartsAtTheBeginning() {
        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { emptyList() },
                    initialQuery = "",
                    initialResults = emptyList(),
                    currentOffset = 0,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val selection = composeTestRule.onNodeWithText("본문 검색")
            .fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]

        assertEquals(TextRange(0), selection)
    }

    @Test
    fun openingWithExistingQuery_cursorStartsAtTheEnd() {
        val existingQuery = "기존검색어"

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { emptyList() },
                    initialQuery = existingQuery,
                    initialResults = emptyList(),
                    currentOffset = 0,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val selection = composeTestRule.onNodeWithText(existingQuery)
            .fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]

        assertEquals(TextRange(existingQuery.length), selection)
    }

    @Test
    fun openingWithExistingQuery_backspaceImmediatelyDeletesTheLastCharacter() {
        val existingQuery = "기존검색어"

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { emptyList() },
                    initialQuery = existingQuery,
                    initialResults = emptyList(),
                    currentOffset = 0,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // 시트가 열리자마자(추가로 탭/클릭해서 초점을 옮기지 않고) 곧바로 백스페이스만 눌러도
        // 마지막 글자가 지워져야 한다 — 커서가 이미 맨 끝에 있다는 걸 실제 동작으로 증명한다.
        composeTestRule.onNodeWithText(existingQuery).performKeyInput { pressKey(Key.Backspace) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("기존검색").assertExists()
    }
}
