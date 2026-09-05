package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import com.moonkata.textreader.data.datastore.PageGestureAction
import com.moonkata.textreader.data.db.AppDatabase
import com.moonkata.textreader.data.repository.BookRepository
import com.moonkata.textreader.testutil.TestBooks
import com.moonkata.textreader.testutil.waitUntilTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the "Page-turn options" section's six independent gesture-action pickers
 * (`GestureActionRow`), added when the old two-value `TouchTurnMode`/`SwipeTurnMode` were replaced
 * by one `PageGestureAction` choice per gesture. All six rows render the same four chip labels, so
 * `onAllNodesWithText` returns one match per row in the same top-to-bottom order they're laid out
 * in `QuickSettingsSheet` (touch left, touch right, swipe left, swipe right, swipe up, swipe down)
 * — this test only exercises the first row (touch left) as a representative sample; the repository
 * round trip for all six is separately covered by `ReaderSettingsRepositoryTest`.
 */
@RunWith(AndroidJUnit4::class)
class QuickSettingsSheetGestureActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clickingAChip_inTheFirstGestureRow_updatesUiAndPersistsToDataStore() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
        val target = PageGestureAction.entries.first { it != originalSettings.touchLeftAction }
        val targetLabel = mapOf(
            PageGestureAction.PREVIOUS_PAGE to "Previous page",
            PageGestureAction.NEXT_PAGE to "Next page",
            PageGestureAction.PREVIOUS_CHAPTER_JUMP to "Previous chapter jump",
            PageGestureAction.NEXT_CHAPTER_JUMP to "Next chapter jump",
        ).getValue(target)

        try {
            waitUntilTrue { viewModel.uiState.value.settings.touchLeftAction == originalSettings.touchLeftAction }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    QuickSettingsSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            // Scrolls to the row's own label first (it lives directly in the sheet's outer
            // vertically-scrollable Column) so the row is actually in the viewport before touching
            // its chips, which sit in their own nested horizontally-scrollable Row.
            composeTestRule.onNodeWithText(application.getString(R.string.settings_touch_left)).performScrollTo()

            // Row order in QuickSettingsSheet is touch-left, touch-right, swipe-left, swipe-right,
            // swipe-up, swipe-down — index 0 is touch-left's chip for this label.
            val chipNode = composeTestRule.onAllNodesWithText(targetLabel)[0].performScrollTo()

            // Invokes the chip's OnClick semantics action directly rather than performClick(): a
            // FilterChip nested two scrollables deep (outer sheet Column, inner gesture-row Row)
            // inside a ModalBottomSheet's own Popup window reports boundsInRoot as all-zero here
            // (confirmed by direct inspection), and performClick() relies on those bounds to
            // resolve which node to click — so it silently no-ops instead of toggling the chip.
            // Invoking the action straight off the already-fetched SemanticsNode sidesteps that
            // resolution entirely and reaches the real onSelect callback.
            chipNode.fetchSemanticsNode().config[SemanticsActions.OnClick].action?.invoke()

            composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.settings.touchLeftAction == target }

            val persisted = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
            assertEquals("touchLeftAction change must be persisted to DataStore", target, persisted.touchLeftAction)
        } finally {
            runBlocking {
                viewModel.settingsRepository.updateTouchLeftAction(originalSettings.touchLeftAction)
                db.bookDao().getById(bookId).first()?.let { bookRepository.deleteBook(it) }
            }
        }
    }
}
