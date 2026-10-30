package com.yll682.houche

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 启动冒烟：真实 Application + DataStore + 城市表 + 导航栈，确认首屏不会崩。
 * 网络在此环境不可用，页面应显示错误态而不是抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class LaunchSmokeTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun 冷启动显示首页并且不崩溃() {
        compose.waitForIdle()
        compose.onNodeWithText("候车").assertIsDisplayed()
        compose.onNodeWithText("搜线路 / 站点").assertIsDisplayed()
    }

    @Test fun 应用图与城市表就绪() {
        val app = ApplicationProvider.getApplicationContext<HoucheApp>()
        // 城市表 575 条，默认或上次选择必须落在表内。
        val graph = app.graph
        val cities = kotlinx.coroutines.runBlocking { graph.cities.all() }
        org.junit.Assert.assertTrue("城市表应加载完整，实际 ${cities.size} 条", cities.size > 500)
    }
}