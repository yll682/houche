package com.yll682.houche.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN", application = Application::class)
class ErrorCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun upstreamMessageIsVisibleAndFullMessageRemainsAccessible() {
        val message = "线路不存在".repeat(30)
        var retries = 0
        compose.setContent { MaterialTheme { ErrorCard(message) { retries++ } } }
        val text = compose.onNodeWithText(message.take(120)).assertIsDisplayed()
        compose.onNodeWithContentDescription(message).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
        compose.onNode(hasText(app.getString(R.string.retry)) and hasClickAction()).performClick()
        assertEquals(1, retries)
    }

    @Test fun missingAndBlankMessagesKeepOnlyRetryText() {
        var message: String? by mutableStateOf(null)
        var retries = 0
        compose.setContent { MaterialTheme { ErrorCard(message) { retries++ } } }
        listOf(null, "", "  ").forEach { value ->
            compose.runOnIdle { message = value }
            compose.onNodeWithText(app.getString(R.string.error_generic)).assertDoesNotExist()
            compose.onAllNodes(hasText("", substring = true)).assertCountEquals(1)
            compose.onNode(hasText(app.getString(R.string.retry)) and hasClickAction()).performClick()
        }
        assertEquals(3, retries)
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
}
