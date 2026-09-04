package com.moonkata.textreader.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumeReadingDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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

        composeTestRule.onNodeWithText("Continue \"테스트 소설.txt\"?").assertExists()
        composeTestRule.onNodeWithText("Continue").performClick()

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

        composeTestRule.onNodeWithText("Not now").performClick()

        assertEquals(1, dismissCount)
        assertFalse("Dismissing must not also invoke the confirm callback", confirmCount > 0)
    }
}
