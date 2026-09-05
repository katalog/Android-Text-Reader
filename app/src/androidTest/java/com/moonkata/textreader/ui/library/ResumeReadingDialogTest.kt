package com.moonkata.textreader.ui.library

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moonkata.textreader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumeReadingDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun confirmClick_firesOnConfirmOnly() {
        var confirmCount = 0
        var dismissCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                ResumeReadingDialog(
                    displayName = "테스트 소설.txt",
                    onConfirm = { confirmCount++ },
                    onDismiss = { dismissCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(application.getString(R.string.library_resume_reading_message, "테스트 소설.txt")).assertExists()
        composeTestRule.onNodeWithText(application.getString(R.string.library_resume_reading_confirm)).performClick()

        assertEquals(1, confirmCount)
        assertEquals(0, dismissCount)
    }

    @Test
    fun dismissClick_firesOnDismissOnly() {
        var confirmCount = 0
        var dismissCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                ResumeReadingDialog(
                    displayName = "테스트 소설.txt",
                    onConfirm = { confirmCount++ },
                    onDismiss = { dismissCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(application.getString(R.string.library_resume_reading_dismiss)).performClick()

        assertEquals(1, dismissCount)
        assertFalse("Dismissing must not also invoke the confirm callback", confirmCount > 0)
    }
}
