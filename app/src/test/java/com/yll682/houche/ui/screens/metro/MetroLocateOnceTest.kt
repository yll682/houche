package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.yll682.houche.core.BusException
import com.yll682.houche.core.MetroInitialLocation
import com.yll682.houche.data.Position
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 首屏定位接线的硬断言：**一次页面实例只问一次系统位置**，结果原样交给 VM。
 *
 * 1. 成功 → `applyInitialLocation(lat, lng, accuracy, coordinateSystem)` 只来一次，字段一个都不改写；
 * 2. 无权限 / 失败（抛异常）→ `applyInitialLocation(null)` 只来一次（null 会消费 VM 那一次机会，
 *    所以绝不能在成功结果之前先传一次 null）；
 * 3. 重组不会重新定位，也不会重复提交；
 * 4. 精度的 Float→Double 转换与 coordinateSystem 原样透传（`accuracy == null` 时传 null）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroLocateOnceTest {
    @get:Rule val compose = createComposeRule()

    private fun Metro(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun locatedPositionIsHandedOverExactlyOnce() {
        var locates = 0
        val applied = mutableListOf<MetroInitialLocation?>()
        var tick by mutableStateOf(0)
        Metro {
            Column {
                // 读一下状态：让下面的重组真的发生。
                Text("tick=$tick")
                LocateOnce(
                    locate = {
                        locates++
                        Position(lat = 24.4798, lng = 118.0889, accuracy = 18f, coordinateSystem = "GCJ02")
                    },
                    apply = { applied += it }
                )
            }
        }

        assertEquals("首屏只定位一次", 1, locates)
        assertEquals("只提交一次结果", 1, applied.size)
        val only = applied.single()
        assertEquals(24.4798, only!!.lat!!, 1e-9)
        assertEquals(118.0889, only.lng!!, 1e-9)
        assertEquals("精度按 Float→Double 原样带过去", 18.0, only.accuracyMeters!!, 1e-9)
        assertEquals("GCJ02", only.coordinateSystem)

        // 重组三次：一次都不能再定位，也不能再提交。
        repeat(3) { compose.runOnIdle { tick++ } }
        compose.waitForIdle()
        assertEquals("重组不许重新定位", 1, locates)
        assertEquals("重组不许重复提交", 1, applied.size)
    }

    @Test
    fun deniedOrFailedLocationHandsNullOnceAndNeverRetries() {
        var locates = 0
        val applied = mutableListOf<MetroInitialLocation?>()
        var tick by mutableStateOf(0)
        Metro {
            Column {
                Text("tick=$tick")
                LocateOnce(
                    locate = {
                        locates++
                        throw BusException(BusException.Kind.ARGUMENT, "请先允许定位权限")
                    },
                    apply = { applied += it }
                )
            }
        }

        assertEquals("失败也只问一次系统", 1, locates)
        assertEquals("失败要带上 null 结果", 1, applied.size)
        assertNull("无权限/失败必须传 null", applied.single())

        repeat(3) { compose.runOnIdle { tick++ } }
        compose.waitForIdle()
        assertEquals("失败后不许自己重试", 1, locates)
        assertEquals("不许再提交第二次", 1, applied.size)
    }

    @Test
    fun accuracyMissingStaysNull() {
        val applied = mutableListOf<MetroInitialLocation?>()
        Metro {
            LocateOnce(
                locate = { Position(lat = 24.46, lng = 118.10, accuracy = null, coordinateSystem = "GCJ02") },
                apply = { applied += it }
            )
        }
        assertEquals(1, applied.size)
        assertNull("没有精度就传 null，不编造精度", applied.single()!!.accuracyMeters)
        assertEquals("GCJ02", applied.single()!!.coordinateSystem)
    }
}