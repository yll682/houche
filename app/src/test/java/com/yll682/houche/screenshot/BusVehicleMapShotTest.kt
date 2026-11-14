package com.yll682.houche.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.core.VehicleResults
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.screens.line.LineContent
import com.yll682.houche.ui.screens.line.LineData
import com.yll682.houche.ui.screens.vehicle.VehicleContent
import com.yll682.houche.ui.screens.vehicle.VehicleData
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
 * 公交「站点图 + 下车提醒」这一轮的两张验收图，以及站名竖排的硬断言。
 *
 * 站名用用户报的原例：厦门 803 路「第一门医院海沧院区」（9 字）与「地铁林埭SM海沧」（8 码点）。
 * 从前 9 个字会被 `chunked(8)` 拆成两竖列，现在必须是一列 9 行 —— 盒子只往上长，列宽不变。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class BusVehicleMapShotTest {
    @get:Rule val compose = createComposeRule()

    private val city = City("xiamen", "厦门市", "0592", "福建省", "xiamen")

    private val stops = listOf(
        RouteStation("海沧行政中心", 11),
        RouteStation("海沧房产", 12),
        RouteStation("海沧建行", 13),
        RouteStation("地铁林埭SM海沧", 14),
        RouteStation("第一门医院海沧院区", 15),
        RouteStation("新阳西公交场站", 16)
    )
    private val route = Route("803路", "803", "1", "海沧行政中心", "新阳西公交场站", "06:30", "21:30", "全程一票制，票价 1 元", stops, true)

    // 这辆车在「海沧房产 → 海沧建行」之间：order 是绘图锚点（前方站 13），reportedOrder 是上游原值（12）。
    private val vehicles = listOf(
        Vehicle("闽D·28810", 13, "between", reportedOrder = 12, currentStation = "海沧房产", nextStation = "海沧建行", dataTime = "17:06:12"),
        Vehicle("闽D·20356", 12, "at-stop", currentStation = "海沧房产", dataTime = "17:06:12"),
        Vehicle("闽D·51927", 16, "between", reportedOrder = 15, currentStation = "第一门医院海沧院区", nextStation = "新阳西公交场站", dataTime = "17:06:12")
    )

    private val live = VehicleResults(vehicles, "车辆位置仅供参考，请提前候车", nextDeparture = "17:20")

    /** 线路页：选中的乘车站＝9 字长站名，站点图要把它整串竖排在一列里。 */
    @Test
    fun lineStationNameStaysInOneColumn() {
        capture("line-station-one-column") {
            LineContent(
                UiState.Success(LineData(route = route, selected = stops[4], live = live)),
                city, 0, false, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}
            )
        }
        // 站序列表里也有同名节点：只看站点图（route-strip）里的那一个。
        val inStrip = hasAnyAncestor(hasTestTag("route-strip"))
        val nine = compose.onNode(hasContentDescription("第一门医院海沧院区") and inStrip).assertIsDisplayed().fetchSemanticsNode()
        val eight = compose.onNode(hasContentDescription("地铁林埭SM海沧") and inStrip).assertIsDisplayed().fetchSemanticsNode()
        // 9 个字＝9 行，8 个字＝8 行：高度必须差整整一行（从前拆列时两者都是 8 行，高度一样）。
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        val line = 18f * density
        assertTrue(
            "9 字站名必须比 8 字站名高出一整行（18sp）：${nine.size.height} vs ${eight.size.height}",
            nine.size.height - eight.size.height >= line * 0.9f
        )
        // 列宽保持一致：不许因为名字长就横向加宽或拆成两列。
        assertEquals("两站的列宽必须一致", eight.size.width, nine.size.width)
        // 线路页的车标仍然不可点（onVehicleClick 传 null）。
        compose.onNodeWithContentDescription("闽D·28810 · 海沧房产").assertDoesNotExist()
    }

    /** 车辆详情页：站点图 + 下车提醒站（提醒站＝9 字长站名，顺带验证它也没有被拆列）。 */
    @Test
    fun vehiclePageShowsMapAndAlightStation() {
        val data = VehicleData(
            route = route,
            plate = "闽D·28810",
            boarding = stops[2],
            vehicle = vehicles.first(),
            vehicles = vehicles,
            tracking = true,
            updatedAt = "17:06:12",
            alight = stops[4],
            backgroundActive = true
        )
        capture("vehicle-map-alight") {
            VehicleContent(
                UiState.Success(data),
                back = {}, share = { _, _ -> }, toggleTracking = {}, notify = {}, refresh = {},
                selectVehicle = {}, selectAlight = {}, clearAlight = {}
            )
        }
        compose.onNodeWithText("站点图").assertIsDisplayed()
        // 剩余站数卡优先按下车提醒站算：标题写「下车提醒站」，站名写提醒站，剩余 15 − 12 = 3 站。
        assertEquals(
            "剩余站数卡标题与操作区那一行都写「下车提醒站」",
            2,
            compose.onAllNodesWithText("下车提醒站").fetchSemanticsNodes().size
        )
        compose.onNodeWithText("3").assertIsDisplayed()
        // 操作区那一行显示提醒站名；跟踪开关文案与后台跟踪胶囊。
        assertTrue(
            "提醒站名要在操作区与剩余站数卡里都出现",
            compose.onAllNodesWithText("第一门医院海沧院区").fetchSemanticsNodes().size >= 2
        )
        // 操作区在首屏之下（站点图那一列有 9 行，站得高）：断言它在屏幕范围内即可。
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val track = compose.onNodeWithText("停止跟踪").fetchSemanticsNode().boundsInRoot
        assertTrue("跟踪开关那一行必须在屏幕内：$track", track.bottom <= root.bottom)
        compose.onNodeWithText("后台跟踪中").assertExists()
        // 站点图上的车标可点：点它切换车辆。
        compose.onNodeWithContentDescription("闽D·28810 · 海沧房产").assertIsDisplayed()
    }

    /** 车辆详情页往下滚：操作区（跟踪开关、下车提醒站、后台跟踪中）与本线车辆列表。 */
    @Test
    fun vehiclePageActionsWithBackgroundPill() {
        val data = VehicleData(
            route = route,
            plate = "闽D·28810",
            boarding = stops[2],
            vehicle = vehicles.first(),
            vehicles = vehicles,
            tracking = true,
            updatedAt = "17:06:12",
            alight = stops[4],
            backgroundActive = true
        )
        capture("vehicle-map-alight-actions", beforeCapture = {
            compose.onNodeWithText("分享").performScrollTo()
            compose.waitForIdle()
        }) {
            VehicleContent(
                UiState.Success(data),
                back = {}, share = { _, _ -> }, toggleTracking = {}, notify = {}, refresh = {},
                selectVehicle = {}, selectAlight = {}, clearAlight = {}
            )
        }
        // 胶囊排在操作卡之后（分享行下方），滚到「分享」时它在屏外：只断言存在，不断言可见。
        compose.onNodeWithText("后台跟踪中").assertExists()
        compose.onNodeWithText("停止跟踪").assertIsDisplayed()
        compose.onNodeWithText("通知设置").assertIsDisplayed()
    }

    /**
     * Compose 1.7 的内置 captureToImage 在 Robolectric 等待窗口重绘时超时：
     * https://github.com/robolectric/robolectric/issues/8071
     * 仍截图真实 Compose View；仅将窗口 PixelCopy 换成 NATIVE Canvas，绝不手绘页面。
     */
    private fun SemanticsNodeInteraction.captureToImage(): ImageBitmap {
        val node = fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap.asImageBitmap()
    }

    private fun capture(
        name: String,
        dark: Boolean = false,
        expectedWidth: Int = 1233,
        expectedHeight: Int = 2673,
        beforeCapture: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        org.robolectric.RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            true
        }
        compose.waitForIdle()
        beforeCapture()
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(1233, bitmap.width)
        assertEquals(2673, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = pixels.asSequence().filterIndexed { i, _ -> i % 16 == 0 }
            .map { p -> (((p shr 16) and 255) * .2126 + ((p shr 8) and 255) * .7152 + (p and 255) * .0722) }.toList()
        val mean = luminance.average()
        val variance = luminance.sumOf { (it - mean) * (it - mean) } / luminance.size
        assertTrue("截图应含有可见内容，实际方差=$variance", variance > 100.0)
        val dir = File(requireNotNull(System.getProperty("screenshots.dir"))).apply { mkdirs() }
        val file = File(dir, "$name-${if (dark) "dark" else "light"}.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("单张 PNG 不超过 400KB：${file.length()}", file.length() <= 400 * 1024)
    }
}
