package com.yll682.houche

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="zh-rCN-w411dp-h891dp-xxhdpi")
class TelegramBackTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun systemBackHalfProgressCancelRetainsPageThenCommitPopsOnce() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("搜线路 / 站点").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread {
            androidx.lifecycle.ViewModelProvider(compose.activity)[NavigationState::class.java].push(
                Screen.Stop(com.yll682.houche.core.City("xiamen", "厦门市"), com.yll682.houche.core.Station("测试站", "TEST")))
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText("测试站").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        val dispatcher=compose.activity.onBackPressedDispatcher
        val before = compose.onNodeWithText("测试站").fetchSemanticsNode().boundsInRoot.left
        fun event(p:Float)=BackEventCompat(0f,350f,p,BackEventCompat.EDGE_LEFT)
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(.5f)) }
        compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
        val middle=compose.onNodeWithText("测试站").fetchSemanticsNode().boundsInRoot.left
        assertTrue("system progress should move the current page: $before -> $middle",middle>before)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(400);compose.waitForIdle()
        assertEquals(0f,compose.onNodeWithTag("page-container").fetchSemanticsNode().boundsInRoot.left,.5f)
        compose.onNodeWithText("测试站").assertIsDisplayed()
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(.7f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle()
        compose.onNodeWithText("候车").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }
}
