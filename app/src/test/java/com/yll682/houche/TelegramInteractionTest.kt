package com.yll682.houche

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.yll682.houche.core.City
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.HomeTab
import com.yll682.houche.ui.screens.home.*
import com.yll682.houche.ui.screens.search.*
import com.yll682.houche.ui.screens.settings.*
import com.yll682.houche.ui.theme.HoucheTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TelegramInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val city = City("xiamen", "厦门市", "0592", "福建省", "xiamen")

    @Test fun destinationsSwitchAndExposeSelectedState() {
        val selections = mutableListOf<Int>()
        compose.setContent {
            var segment by remember { mutableIntStateOf(HomeTab.BUS) }
            HoucheTheme {
                HomeContent(UiState.Success(HomeData()), city, false, segment, { segment = it; selections += it }, {}, {}, {}, { _, _, _ -> }, { _, _ -> },
                    metroContent = { Box(Modifier.fillMaxSize()) { Text("地铁内容") } },
                    settingsContent = { Box(Modifier.fillMaxSize()) { Text("设置内容") } })
            }
        }
        assertTabs("公交")
        tab("地铁").performClick()
        compose.onNodeWithText("地铁内容").assertIsDisplayed()
        compose.onNodeWithText("列车位置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithContentDescription("重新定位").assertDoesNotExist()
        compose.onNodeWithText(city.name).assertDoesNotExist()
        compose.onNodeWithText("搜线路 / 站点").assertDoesNotExist()
        assertTabs("地铁")
        tab("收藏").performClick()
        compose.onNode(isSelected() and hasText("收藏")).assertIsDisplayed()
        compose.onNodeWithText("地铁内容").assertDoesNotExist()
        assertTabs("收藏")
        tab("设置").performClick()
        compose.waitUntil(3000) { compose.onNodeWithText("设置内容").isDisplayed() }
        compose.onNodeWithText("设置内容").assertIsDisplayed()
        compose.onNode(isSelected() and hasText("设置")).assertIsDisplayed()
        assertTabs("设置")
        tab("公交").performClick()
        compose.waitForIdle()
        compose.waitUntil(3000) { compose.onNodeWithText("搜线路 / 站点").isDisplayed() }
        compose.onNodeWithText("搜线路 / 站点").assertIsDisplayed()
        assertTabs("公交")
        compose.runOnIdle { assertEquals(listOf(HomeTab.METRO, HomeTab.FAVORITES, HomeTab.SETTINGS, HomeTab.BUS), selections) }
    }

    @Test fun metroTabRemainsAvailableWithoutMetroDataAndReselectKeepsItsContent() {
        var entered = 0
        var disposed = 0
        compose.setContent {
            var segment by remember { mutableIntStateOf(HomeTab.BUS) }
            HoucheTheme {
                HomeContent(
                    UiState.Success(HomeData(hasMetro = false)), city, false, segment,
                    { segment = it }, {}, {}, {}, { _, _, _ -> }, { _, _ -> },
                    metroContent = {
                        DisposableEffect(Unit) {
                            entered++
                            onDispose { disposed++ }
                        }
                        Text("暂无地铁资料")
                    }
                )
            }
        }
        assertTabs("公交")
        tab("地铁").performClick()
        compose.onNodeWithText("暂无地铁资料").assertIsDisplayed()
        assertTabs("地铁")
        tab("地铁").performClick()
        compose.runOnIdle {
            assertEquals("重复选择地铁 Tab 不重建内容", 1, entered)
            assertEquals(0, disposed)
        }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun assertTabs(selected: String) {
        listOf("公交", "地铁", "收藏", "设置").forEach { label ->
            tab(label).assertIsDisplayed()
            if (label == selected) tab(label).assertIsSelected() else tab(label).assertIsNotSelected()
        }
        val bounds = listOf("公交", "地铁", "收藏", "设置").map { tab(it).fetchSemanticsNode().boundsInRoot }
        org.junit.Assert.assertTrue(bounds.zipWithNext().all { (left, right) -> left.right <= right.left })
    }

    @Test fun recentSearchDeletionUpdatesImmediatelyAndKeepsOtherItems() {
        var chosen = ""
        compose.setContent {
            var data by remember { mutableStateOf(SearchData(history = listOf("91路", "火车站"))) }
            HoucheTheme {
                SearchContent(UiState.Success(data), {}, {}, {}, {}, { chosen = it }, {},
                    { data = data.copy(history = emptyList()) },
                    { keyword -> data = data.copy(history = data.history - keyword) })
            }
        }
        compose.onAllNodesWithContentDescription("删除搜索记录")[0].performClick()
        compose.onNodeWithText("91路").assertDoesNotExist()
        compose.onNodeWithText("火车站").performClick()
        compose.runOnIdle { assertEquals("火车站", chosen) }
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("火车站").assertDoesNotExist()
    }

    @Test fun settingsActionsRemainConnected() {
        var cities = 0
        var updates = 0
        compose.setContent {
            var data by remember { mutableStateOf(SettingsData(city)) }
            HoucheTheme {
                SettingsBody(UiState.Success(data), { cities++ }, { data = data.copy(cleared = true) }, checkUpdate = { updates++ })
            }
        }
        compose.onNodeWithText("默认城市").performClick()
        compose.onNodeWithText("清除查询缓存").performClick()
        compose.onNodeWithText("已清除").assertIsDisplayed()
        compose.onNodeWithText("检查更新").performClick()
        compose.runOnIdle { assertEquals(1, cities); assertEquals(1, updates) }
    }
}
