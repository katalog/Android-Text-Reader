package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import com.moonkata.textreader.model.SearchResult
import com.moonkata.textreader.testutil.waitUntilTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the search sheet's core contract:
 * 1. Typing alone does not trigger a search — only submitting (button or keyboard search action)
 *    does.
 * 2. Reopening the sheet shows the last query/results as-is, without triggering a re-search.
 * 3. The result nearest to the current reading position is visible immediately, without manual
 *    scrolling.
 * 4. Opening the sheet always places the cursor at the very end of the query (naturally the same
 *    as the very start when there's no existing query) — a regression test for the requirement
 *    that backspace should be able to delete the existing query immediately after reopening.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchSheetTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val searchTextLabel get() = application.getString(R.string.search_field_label)

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
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(searchTextLabel).performTextInput("테스트")
        composeTestRule.waitForIdle()
        assertEquals("Typing alone must not trigger a search", 0, searchCallCount)

        composeTestRule.onNodeWithContentDescription(application.getString(R.string.search_desc)).performClick()
        composeTestRule.waitForIdle()

        assertEquals("Tapping the search button should trigger exactly one search", 1, searchCallCount)
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
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(searchTextLabel).performTextInput("테스트")
        composeTestRule.onNodeWithText(searchTextLabel).performImeAction()
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
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("이전검색어").assertExists()
        composeTestRule.onNodeWithText("이전 검색 결과").assertExists()
        assertEquals("Reopening the search sheet must not automatically re-run the search", 0, searchCallCount)
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
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("결과 70").assertExists()
    }

    @Test
    fun resultRow_showsItsPositionAsPercentOfTheBook() {
        val results = listOf(SearchResult(offset = 25_000, snippet = "퍼센트 표시 테스트"))

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { results },
                    initialQuery = "쿼리",
                    initialResults = results,
                    currentOffset = 0,
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("25%").assertExists()
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
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val selection = composeTestRule.onNodeWithText(searchTextLabel)
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
                    fullTextLength = 100_000,
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
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // As soon as the sheet opens (with no extra tap/click to move focus), pressing backspace
        // alone should delete the last character — proving via actual behavior that the cursor is
        // already at the very end.
        composeTestRule.onNodeWithText(existingQuery).performKeyInput { pressKey(Key.Backspace) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("기존검색").assertExists()
    }

    @Test
    fun singleBackPress_dismissesImmediately() {
        var dismissCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                SearchSheet(
                    onSearch = { emptyList() },
                    initialQuery = "",
                    initialResults = emptyList(),
                    currentOffset = 0,
                    fullTextLength = 100_000,
                    onJump = {},
                    onDismiss = { dismissCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Full-screen search collapses the old sheet's two-step back press (first closes the
        // keyboard, second closes the sheet) into one — even with the field focused (keyboard up),
        // a single back press must exit right away.
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }

        waitUntilTrue { dismissCount == 1 }
        assertTrue("A single back press must dismiss exactly once", dismissCount == 1)
    }
}
