package com.moonkata.textreader

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A minimal smoke test confirming the app actually launches and reaches the library screen
 * without crashing. Depending on whether a folder was previously selected (by another test or
 * real usage), the FAB label lands on either "Add folder" or "Change folder" — rather than forcing
 * a reset to a specific state, the test passes as long as either one is present, so it runs
 * reliably regardless of environment (another developer's PC, state left by a previous test).
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunches_libraryScaffoldRenders() {
        // ExtendedFloatingActionButton does not merge its icon+text into a single merged semantics
        // node, so the default lookup against the merged tree can't find the text inside it — look
        // it up in the unmerged tree instead.
        val addFolderLabel = composeTestRule.activity.getString(R.string.library_add_folder)
        val changeFolderLabel = composeTestRule.activity.getString(R.string.library_change_folder)
        composeTestRule
            .onNode(hasText(addFolderLabel) or hasText(changeFolderLabel), useUnmergedTree = true)
            .assertExists()
    }
}
