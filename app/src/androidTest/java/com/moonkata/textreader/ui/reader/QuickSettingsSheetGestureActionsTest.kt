package com.moonkata.textreader.ui.reader

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * (`GestureActionRow`): a button showing the current choice opens a `DropdownMenu` to change it.
 * This test only exercises the first row (touch left) as a representative sample; the repository
 * round trip for all six is separately covered by `ReaderSettingsRepositoryTest`.
 */
@RunWith(AndroidJUnit4::class)
class QuickSettingsSheetGestureActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun labelFor(application: Application, action: PageGestureAction) = application.getString(
        when (action) {
            PageGestureAction.PREVIOUS_PAGE -> R.string.settings_gesture_previous_page
            PageGestureAction.NEXT_PAGE -> R.string.settings_gesture_next_page
            PageGestureAction.PREVIOUS_CHAPTER_JUMP -> R.string.settings_gesture_previous_chapter_jump
            PageGestureAction.NEXT_CHAPTER_JUMP -> R.string.settings_gesture_next_chapter_jump
            PageGestureAction.NONE -> R.string.settings_gesture_none
        },
    )

    @Test
    fun pickingAnOptionFromTheDropdown_updatesUiAndPersistsToDataStore() {
        val bookAsset = "Heuk.txt"
        TestBooks.assumeAvailable(bookAsset)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(application)
        val bookRepository = BookRepository(application, db.bookDao())
        val bookId = runBlocking { TestBooks.insertBook(application, bookRepository, bookAsset) }

        val viewModel = ReaderViewModel(application, bookId, bookRepository)
        val originalSettings = runBlocking { viewModel.settingsRepository.settingsFlow.first() }
        // NONE is never any gesture's default, so once the dropdown is open, "No action" can only
        // match the dropdown item itself — no other row's closed button shows that label to collide
        // with, unlike picking among PREVIOUS_PAGE/NEXT_PAGE/etc. which several rows default to.
        val target = PageGestureAction.NONE

        try {
            waitUntilTrue { viewModel.uiState.value.settings.touchLeftAction == originalSettings.touchLeftAction }

            composeTestRule.setContent {
                MaterialTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    QuickSettingsSheet(viewModel = viewModel, settings = uiState.settings, onDismiss = {})
                }
            }
            composeTestRule.waitForIdle()

            // The touch-left row is declared first among the six gesture rows, so if its current
            // action's label happens to match another row's too, it's still the first match in
            // document order.
            val currentLabel = labelFor(application, originalSettings.touchLeftAction)
            composeTestRule.onAllNodesWithText(currentLabel)[0].performScrollTo().performClick()

            composeTestRule.onNodeWithText(labelFor(application, target)).performClick()

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
