package com.yll682.houche

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import com.yll682.houche.ui.components.LocalSheetHost
import com.yll682.houche.ui.components.SheetHost
import com.yll682.houche.ui.components.SheetHostLayer
import com.yll682.houche.ui.components.TelegramSheet
import com.yll682.houche.ui.theme.HoucheTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 弹层走根部承载层（不是 Dialog）：内容画在承载层里，页面移除后承载层清空。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SheetHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sheetIsDrawnByHostLayerInsideWindowAndClearedWhenRemoved() {
        val host = SheetHost()
        var show = true
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            HoucheTheme {
                CompositionLocalProvider(LocalSheetHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        if (visible.value) TelegramSheet(dismiss = { show = false }) { Text("弹层内容") }
                        SheetHostLayer(host)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("弹层内容").assertIsDisplayed()
        val panel = compose.onNodeWithTag("sheet-panel").assertIsDisplayed()
        val bounds = panel.fetchSemanticsNode().boundsInRoot
        // 面板必须整个落在窗口里（底部不超出屏幕）。
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("面板底边 ${bounds.bottom} 不应超出窗口 ${root.bottom}", bounds.bottom <= root.bottom + 1f)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertNull("弹层所属页面移除后承载层应清空", host.content)
        assertEquals(true, show)
    }
}

