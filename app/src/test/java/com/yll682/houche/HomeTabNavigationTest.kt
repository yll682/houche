package com.yll682.houche

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.yll682.houche.core.City
import com.yll682.houche.core.Station
import com.yll682.houche.ui.HomeTab
import com.yll682.houche.ui.screens.metro.MetroViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class HomeTabNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun navigation() = ViewModelProvider(compose.activity)[NavigationState::class.java]
    private fun metro(home: PageEntry): MetroViewModel? = home.viewModelStore.keys()
        .mapNotNull { home.viewModelStore[it] as? MetroViewModel }.singleOrNull()
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())
    private fun ready() = compose.waitUntil(10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText("搜线路 / 站点").fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitMetro(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                condition()
            }
        } catch (timeout: ComposeTimeoutException) {
            val navigation = navigation()
            val home = navigation.stack.first()
            val vm = metro(home)
            throw AssertionError(
                "$description: segment=${navigation.segment}, pending=${navigation.pendingMetroLine}, " +
                    "metroCity=${navigation.metroCity}, vmCity=${vm?.city}, state=${vm?.state?.value}, " +
                    "storeKeys=${home.viewModelStore.keys()}", timeout
            )
        }
    }

    @Test fun metroModelIsCreatedOnFirstVisitAndRetainedAcrossAllFourTabs() {
        ready()
        val home = navigation().stack.single()
        compose.runOnIdle { assertNull("公交冷启动不创建地铁 VM", metro(home)) }
        tab("地铁").performClick()
        awaitMetro("首次进地铁应加载线路") { metro(home)?.state?.value?.data?.lines?.any { it.id == "2" } == true }
        val model = requireNotNull(metro(home))
        compose.runOnUiThread { model.selectLine("2") }
        listOf("收藏", "设置", "公交", "地铁").forEach { label ->
            tab(label).performClick()
            tab(label).assertIsSelected()
            compose.runOnIdle {
                assertSame("切到 $label 保留首页条目", home, navigation().stack.single())
                assertSame("切到 $label 保留同城地铁 VM", model, metro(home))
                assertEquals("切 Tab 不重置选线", "2", model.state.value.data!!.selected)
            }
        }
        compose.onNodeWithText("列车位置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithTag("metro-stations").assertIsDisplayed()
    }

    @Test fun metroEntryPopsNestedPagesAndConsumesPendingLineOnlyOnce() {
        ready()
        val navigation = navigation()
        val home = navigation.stack.single()
        compose.runOnUiThread {
            navigation.push(Screen.Search(City("11", "厦门市")))
            navigation.push(Screen.Stop(City("11", "厦门市"), Station("吕厝", "TEST")))
            navigation.metro("2")
        }
        awaitMetro("入口应消费待选线路") {
            navigation.pendingMetroLine == null &&
                metro(home)?.state?.value?.data?.selected == "2"
        }
        compose.runOnIdle {
            assertSame(home, navigation.stack.single())
            assertEquals(HomeTab.METRO, navigation.segment)
        }
        val model = requireNotNull(metro(home))
        compose.runOnUiThread { model.selectLine("1") }
        tab("公交").performClick()
        tab("地铁").performClick()
        compose.runOnIdle {
            assertSame(model, metro(home))
            assertEquals("已消费的线路入口不能在重入时覆盖手动选线", "1", model.state.value.data!!.selected)
            assertNull(navigation.pendingMetroLine)
        }
        compose.onNode(hasText("地铁") and isSelected()).assertIsDisplayed()
    }

    @Test fun systemBackFromEachNonBusTabReturnsToBusAndRetainsHomeStore() {
        ready()
        val navigation = navigation()
        val home = navigation.stack.single()
        tab("地铁").performClick()
        awaitMetro("首次进地铁应创建 VM") { metro(home) != null }
        val models = home.viewModelStore.keys().associateWith { home.viewModelStore[it] }
        listOf("地铁", "收藏", "设置").forEach { label ->
            if (label != "地铁") tab(label).performClick()
            tab(label).assertIsSelected()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            tab("公交").assertIsSelected()
            compose.runOnIdle {
                assertEquals("$label 返回公交 Tab", HomeTab.BUS, navigation.segment)
                assertSame(home, navigation.stack.single())
                assertEquals(models.keys, home.viewModelStore.keys())
                models.forEach { (key, model) -> assertSame("$label 返回后保留 $key", model, home.viewModelStore[key]) }
                assertFalse("Tab 返回不退出 Activity", compose.activity.isFinishing)
            }
        }
    }

    @Test fun metroEntryUsesTheSourceCityAndKeepsTheBusCityPreference() {
        ready()
        val navigation = navigation()
        val home = navigation.stack.single()
        val root = ViewModelProvider(compose.activity)[RootViewModel::class.java]
        val busCity = root.state.value.data!!.city
        val source = City("beijing", "北京市", province = "北京市", pinyin = "beijing")
        assertNotEquals("用另一个城市检验入口上下文", busCity?.key, source.key)
        compose.runOnUiThread { navigation.metro("2", source) }
        awaitMetro("跨城入口应使用来源城市创建 VM") {
            metro(home)?.city == source && navigation.pendingMetroLine == null
        }
        compose.runOnIdle {
            assertEquals(source, navigation.metroCity)
            assertEquals(busCity, root.state.value.data!!.city)
        }
        tab("公交").performClick()
        compose.runOnIdle {
            assertSame(home, navigation.stack.single())
            assertEquals("地铁入口不改全局公交城市", busCity, root.state.value.data!!.city)
        }
    }
}
