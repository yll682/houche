package com.yll682.houche.ui.screens.vehicle

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.DarkColors
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 车辆详情页「跟踪这辆车」开关的动效自检。
 *
 * 用户原话：「点击跟踪这辆车，竟然只是生硬地打了一个勾，并且页面直接改变」。
 * 这一轮把跟踪行尾部图标、文案、「后台跟踪中」胶囊、剩余站数卡都换成平滑过渡，
 * 这里出「未跟踪 / 跟踪中」两张图，并断言选中态图标确实是主色（不是硬切出来的灰箭头）。
 *
 * 截图方式沿用仓库既有的 Robolectric 做法：截真实 Compose View，
 * 只把窗口 PixelCopy 换成 NATIVE Canvas（Compose 1.7 内置 captureToImage 在
 * Robolectric 下会超时，见 https://github.com/robolectric/robolectric/issues/8071）。
 * 输出目录由 `screenshots.dir` 系统属性指定。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class VehicleTrackingMotionShotTest {
    @get:Rule val compose = createComposeRule()

    // 803 路海沧段：提醒站用 9 字长名，顺带看长文本有没有把行撑变形。
    private val stops = listOf(
        RouteStation("海沧行政中心", 11),
        RouteStation("海沧房产", 12),
        RouteStation("海沧建行", 13),
        RouteStation("地铁林埭SM海沧", 14),
        RouteStation("第一门医院海沧院区", 15),
        RouteStation("新阳西公交场站", 16)
    )
    private val route = Route("803路", "803", "1", "海沧行政中心", "新阳西公交场站", "06:30", "21:30", "全程一票制，票价 1 元", stops, true)
    private val vehicles = listOf(
        Vehicle("闽D·28810", 13, "between", reportedOrder = 12, currentStation = "海沧房产", nextStation = "海沧建行", dataTime = "17:06:12"),
        Vehicle("闽D·20356", 12, "at-stop", currentStation = "海沧房产", dataTime = "17:06:12")
    )

    private val offLabel = "跟踪这辆车"
    private val onLabel = "停止跟踪"

    /** 提醒站 15 号、车在 12 号：剩余 3 站。 */
    private fun vehicleData(tracking: Boolean) = VehicleData(
        route = route,
        plate = "闽D·28810",
        boarding = stops[2],
        vehicle = vehicles.first(),
        vehicles = vehicles,
        tracking = tracking,
        updatedAt = "17:06:12",
        alight = stops[4],
        backgroundActive = tracking
    )

    @Composable
    private fun Page(dark: Boolean, data: VehicleData) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                VehicleContent(
                    UiState.Success(data),
                    back = {}, share = { _, _ -> }, toggleTracking = {}, notify = {}, refresh = {},
                    selectVehicle = {}, selectAlight = {}, clearAlight = {}
                )
            }
        }
    }

    /** 未跟踪：箭头是灰的，文案是「跟踪这辆车」，没有「后台跟踪中」胶囊。 */
    @Test
    fun trackingOffScreenshot() {
        compose.setContent { Page(dark = false, data = vehicleData(tracking = false)) }
        compose.waitForIdle()
        scrollToActions()
        val shot = cropActions(captureView(), offLabel)
        save(shot.bitmap, "vehicle-tracking-off-light")

        compose.onNodeWithText(offLabel).assertExists()
        // 未跟踪时不该有后台跟踪胶囊。
        assertEquals(0, compose.onAllNodesWithText("后台跟踪中").fetchSemanticsNodes().size)
        // 尾部图标是灰箭头：这一块不该出现主色。
        assertTrue("未跟踪时尾部图标不应带主色，实际 ${maxBlueness(shot)}", maxBlueness(shot) < 40)
    }

    /** 跟踪中：勾是主色的，文案是「停止跟踪」，胶囊在操作卡下面。 */
    @Test
    fun trackingOnScreenshot() {
        compose.setContent { Page(dark = false, data = vehicleData(tracking = true)) }
        compose.waitForIdle()
        scrollToActions()
        val shot = cropActions(captureView(), onLabel)
        save(shot.bitmap, "vehicle-tracking-on-light")

        compose.onNodeWithText(onLabel).assertExists()
        compose.onNodeWithText("后台跟踪中").assertExists()
        // 选中态一眼能认出来：尾部图标必须是主色。
        assertTrue("跟踪中时尾部图标应为主色，实际 ${maxBlueness(shot)}", maxBlueness(shot) > 140)
        // 数字仍然在（剩余 3 站），别让动画组件把内容吃掉。
        compose.onNodeWithText("3").assertExists()
    }

    /** 深色主题同样出两张，确认主色与胶囊在深底上也读得出来。 */
    @Test
    fun trackingOffDarkScreenshot() {
        compose.setContent { Page(dark = true, data = vehicleData(tracking = false)) }
        compose.waitForIdle()
        scrollToActions()
        save(cropActions(captureView(), offLabel).bitmap, "vehicle-tracking-off-dark")
    }

    @Test
    fun trackingOnDarkScreenshot() {
        compose.setContent { Page(dark = true, data = vehicleData(tracking = true)) }
        compose.waitForIdle()
        scrollToActions()
        save(cropActions(captureView(), onLabel).bitmap, "vehicle-tracking-on-dark")
    }

    /**
     * 切换过程本身：不是硬切。
     *
     * 手动推进帧时钟，在动画中途取一帧 —— 那一帧里蓝勾正在淡入（还没到主色的浓度），
     * 切完再等动画结束，浓度才到顶。硬切的话中途帧会是「一点主色都没有」或「一次到位」。
     */
    @Test
    fun trackingSwitchIsNotAHardCut() {
        compose.mainClock.autoAdvance = false
        var tracking by mutableStateOf(false)
        compose.setContent { Page(dark = false, data = vehicleData(tracking)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        scrollToActions()

        tracking = true
        // 先走一帧让重组与动画起跑，再推进 60ms 取动画中段。
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(60)
        compose.waitForIdle()
        val mid = cropActions(captureView(), onLabel)
        save(mid.bitmap, "vehicle-tracking-transition-light")

        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val settled = cropActions(captureView(), onLabel)
        save(settled.bitmap, "vehicle-tracking-settled-light")

        val midBlueness = maxBlueness(mid)
        val settledBlueness = maxBlueness(settled)
        assertTrue("中途帧应已出现主色（正在淡入），实际 $midBlueness", midBlueness > 15)
        assertTrue(
            "中途帧应还没到主色浓度（是渐变不是硬切）：中途 $midBlueness 结束 $settledBlueness",
            midBlueness < settledBlueness
        )
        assertTrue("动画结束后尾部图标应为主色，实际 $settledBlueness", settledBlueness > 140)
    }

    /**
     * 把操作区滚进屏幕。
     *
     * 这一页上半屏是站点图，操作区在下面；直接对跟踪行做 performScrollTo 没用
     * （它本来就在视口里，滚动量为 0），这里直接对列表发一次 ScrollBy，
     * 让操作卡、胶囊、提示文案一起进画面。
     */
    private fun scrollToActions() {
        compose.onNode(
            hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        ).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 900f) }
        compose.waitForIdle()
    }

    /** 截真实 Compose View：窗口 PixelCopy 换成 NATIVE Canvas，不手绘页面。 */
    private fun captureView(): Bitmap {
        val node = compose.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap.asImageBitmap().asAndroidBitmap()
    }

    /** 一张截图：位图 + 它在整屏里的起始 y + 行尾图标那一块（整屏坐标）。 */
    private data class Shot(val bitmap: Bitmap, val top: Int, val icon: Box)

    /** 整屏坐标里的一块矩形（像素）。 */
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 裁出操作卡这一段：跟踪行 → 提醒站行 → 胶囊 → 提示文案。 */
    private fun cropActions(full: Bitmap, label: String): Shot {
        val density = density()
        val row = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val pill = compose.onAllNodesWithText("后台跟踪中").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        val scope = compose.onAllNodes(hasText("后台持续跟踪", substring = true)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        val bottom = listOfNotNull(pill?.bottom, scope?.bottom).maxOrNull() ?: (row.bottom + 120 * density)
        val top = (row.top - 8 * density).toInt().coerceIn(0, full.height)
        val end = (bottom + 8 * density).toInt().coerceIn(top + 1, full.height)
        // 行尾图标那一块（整屏坐标）：行右缘往回 44dp 见方，避开左侧的图标。
        val icon = Box(
            left = (row.right - 44 * density).toInt().coerceIn(0, full.width),
            top = (row.center.y - 16 * density).toInt().coerceIn(0, full.height),
            right = (row.right - 10 * density).toInt().coerceIn(0, full.width),
            bottom = (row.center.y + 16 * density).toInt().coerceIn(0, full.height)
        )
        return Shot(Bitmap.createBitmap(full, 0, top, full.width, end - top), top, icon)
    }

    /**
     * 行尾图标那一小块的「蓝度」：主色是蓝的（b 明显大于 r），灰箭头与白底都不蓝。
     * 取区域内 (b - r) 的最大值：笔画最粗的那几个像素就是图标本来的颜色，
     * 淡入到一半时它也只有一半浓度 —— 正好用来分辨「没开始 / 淡入中 / 到位」。
     */
    private fun maxBlueness(shot: Shot): Int {
        // 采样区随截图一起记下：胶囊淡入时整页会挪动，事后再取行坐标就取偏了。
        val left = shot.icon.left.coerceIn(0, shot.bitmap.width)
        val right = shot.icon.right.coerceIn(left + 1, shot.bitmap.width)
        val top = (shot.icon.top - shot.top).coerceIn(0, shot.bitmap.height)
        val bottom = (shot.icon.bottom - shot.top).coerceIn(top + 1, shot.bitmap.height)
        var best = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = shot.bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 255
                val b = pixel and 255
                if (b - r > best) best = b - r
            }
        }
        return best
    }

    private fun density(): Float = ApplicationProvider.getApplicationContext<android.content.Context>()
        .resources.displayMetrics.density

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(requireNotNull(System.getProperty("screenshots.dir"))).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG 写出失败：$name" }
        }
    }
}
