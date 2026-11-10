package com.yll682.houche

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.yll682.houche.core.MetroLine
import com.yll682.houche.ui.components.CapsuleTabs
import com.yll682.houche.ui.screens.metro.MetroLineTabs
import com.yll682.houche.ui.theme.HoucheTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CapsuleTabsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val selections = mutableListOf<Int>()
    private val haptics = mutableListOf<HapticFeedbackType>()
    private val labels = (1..8).map(Int::toString)
    private val scrollable = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    private fun strip() = compose.onNode(scrollable)
    private fun scrollPosition() = strip().fetchSemanticsNode()
        .config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun showTabs(
        initial: Int = 0,
        longPressDrag: Boolean = true,
        tabs: List<String> = labels
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var selected by remember { mutableIntStateOf(initial) }
            HoucheTheme {
                CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        haptics += hapticFeedbackType
                    }
                }) {
                    Box(Modifier.width(216.dp)) {
                        CapsuleTabs(
                            tabs, selected, { selected = it; selections += it },
                            padding = PaddingValues(0.dp), longPressDrag = longPressDrag
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun holdAt(xFraction: Float = 1f / 6f) {
        strip().performTouchInput {
            down(Offset(width * xFraction, height / 2f))
            advanceEventTime(600)
        }
        // 分批注入时 advanceEventTime 只移动输入时间戳；长按协程还需真正经过测试时钟。
        compose.mainClock.advanceTimeBy(600)
    }

    @Test fun overflowingLongPressDragCommitsNewLineOnlyOnRelease() {
        showTabs()
        holdAt()
        strip().performTouchInput { moveTo(center) }
        compose.runOnIdle { assertTrue("拖动预览不提交线路", selections.isEmpty()) }
        strip().performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(1), selections) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("2").assertIsSelected()
    }

    @Test fun overflowingOrdinarySwipeScrollsWithoutSelecting() {
        showTabs()
        strip().performTouchInput {
            swipe(Offset(width * .9f, height / 2f), Offset(width * .1f, height / 2f), 200)
        }
        compose.mainClock.advanceTimeBy(500)
        assertTrue("普通横拖必须真正滚动", scrollPosition() > 0f)
        compose.runOnIdle {
            assertTrue("长按前滑动不能切换线路", selections.isEmpty())
        }
    }

    @Test fun longPressDragIncludesTheExistingScrollOffset() {
        showTabs(initial = 4)
        assertTrue("选中后段线路时应已滚动", scrollPosition() > 0f)
        holdAt(.5f)
        strip().performTouchInput {
            moveTo(Offset(width * 5f / 6f, height / 2f))
            up()
        }
        compose.runOnIdle { assertEquals(listOf(5), selections) }
    }

    @Test fun cancelledLongPressDragDoesNotSelectAndTheNextGestureStillWorks() {
        showTabs()
        holdAt()
        strip().performTouchInput { moveTo(center); cancel() }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        compose.onNodeWithContentDescription("1").assertIsSelected()
        holdAt()
        strip().performTouchInput { moveTo(center); up() }
        compose.runOnIdle { assertEquals(listOf(1), selections) }
    }

    @Test fun holdingOutsideTheRightEdgeKeepsScrollingToTheLastLine() {
        showTabs()
        holdAt()
        strip().performTouchInput { moveTo(Offset(width + 20f, height / 2f)) }
        val afterMove = scrollPosition()
        // 没有新的 move 事件，固定手指的位置仍应驱动后续帧滚动。
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue("视口外停留时要继续自动滚动", scrollPosition() > afterMove)
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        strip().performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(labels.lastIndex), selections) }
    }

    @Test fun holdingOutsideTheLeftEdgeKeepsScrollingToTheFirstLine() {
        showTabs(initial = labels.lastIndex)
        val initialScroll = scrollPosition()
        holdAt(5f / 6f)
        strip().performTouchInput { moveTo(Offset(-20f, height / 2f)) }
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue("左侧视口外停留时要自动向前滚动", scrollPosition() < initialScroll)
        strip().performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(0), selections) }
    }

    @Test fun eachCrossedLineGivesOneTextHandleHaptic() {
        showTabs()
        holdAt()
        strip().performTouchInput {
            moveTo(center)
            moveTo(Offset(width * 5f / 6f, height / 2f))
            moveBy(Offset(1f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.TextHandleMove, HapticFeedbackType.TextHandleMove), haptics)
            assertEquals(listOf(2), selections)
        }
    }

    @Test fun defaultOverflowingTabsKeepTheirExistingScrollBehaviorAfterHolding() {
        showTabs(longPressDrag = false)
        holdAt()
        strip().performTouchInput {
            moveTo(Offset(width * .05f, height / 2f))
            up()
        }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("默认关闭长按选择的标签仍可滚动", scrollPosition() > 0f)
        compose.runOnIdle {
            assertTrue(selections.isEmpty())
        }
    }

    @Test fun fittingTabsStillSelectWithAnOrdinaryDrag() {
        showTabs(longPressDrag = false, tabs = listOf("站点", "车辆"))
        compose.onNodeWithContentDescription("站点").performTouchInput {
            down(center)
            moveTo(Offset(width * 1.5f, height / 2f))
            up()
        }
        compose.runOnIdle { assertEquals(listOf(1), selections) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("车辆").assertIsSelected()
    }

    @Test fun fittingMetroTabsStillSelectWithoutALongPress() {
        showTabs(tabs = listOf("1", "2"))
        compose.onNodeWithContentDescription("1").performTouchInput {
            down(center)
            moveTo(Offset(width * 1.5f, height / 2f))
            up()
        }
        compose.runOnIdle { assertEquals(listOf(1), selections) }
    }

    @Test fun metroLineTabsEnableLongPressDragging() {
        val lines = labels.map { MetroLine(id = it, name = it) }
        val selectedLines = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var selected by remember { androidx.compose.runtime.mutableStateOf("1") }
            HoucheTheme {
                Box(Modifier.width(240.dp).testTag("metro-lines")) {
                    MetroLineTabs(lines, selected) { selected = it; selectedLines += it }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        holdAt()
        strip().performTouchInput { moveTo(center); up() }
        compose.runOnIdle { assertEquals(listOf("2"), selectedLines) }
    }
}
