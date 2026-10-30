package com.yll682.houche.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.yll682.houche.core.*
import com.yll682.houche.data.Position
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.screens.home.*
import com.yll682.houche.ui.screens.search.*
import com.yll682.houche.ui.screens.line.*
import com.yll682.houche.ui.screens.station.*
import com.yll682.houche.ui.screens.timetable.*
import com.yll682.houche.ui.screens.city.*
import com.yll682.houche.ui.screens.settings.*
import com.yll682.houche.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    // 站名、线路、方向、首末班与发车时刻摘自 docs/live-check.md；车辆与到站分钟为演示数据。
    private val city = City("xiamen", "厦门市", "0592", "福建省", "xiamen")
    private val lines = listOf(LineSummary("3路", "2", "第一码头", "莲花五村"), LineSummary("45路", "2", "厦大（南普陀）", "金尚公交场站"), LineSummary("130路", "1", "火车站南广场", "长浩东路"))
    private val stop = Station("实验幼儿园", "SM000170", distance = 365.0, platformNumbers = listOf("SM000169", "SM000170"), lines = lines)
    private val arrivals = lines.mapIndexed { i, l -> Arrival(remainingStations = if (i == 0) 0 else i + 1, timeText = if (i == 0) "即将到站" else "约${i * 3}分钟", name = l.name, direction = l.direction, to = l.to) }
    private val stops = listOf(RouteStation("高崎T4公交场站", 1), RouteStation("前埔公交场站", 32))
    private val route = Route("91路", "a2e913fc4967b27f1d72edd76cbcc831", "1", "高崎T4公交场站", "前埔公交场站", "06:30", "22:00", "全程一票制，票价 1 元", stops, true)
    private val beijing = City("beijing", "北京市", province = "北京市", pinyin = "beijing")
    private val beijingStops = listOf(
        RouteStation("四惠枢纽站", 1),
        RouteStation("八王坟西", 2),
        RouteStation("郎家园", 3),
        RouteStation("大北窑东", 4),
        RouteStation("大北窑西", 5),
        RouteStation("永安里路口西", 6),
        RouteStation("日坛路", 7),
        RouteStation("北京站口东", 8),
        RouteStation("东单路口西", 9),
        RouteStation("天安门东", 10),
        RouteStation("天安门西", 11),
        RouteStation("西单路口东", 12),
        RouteStation("复兴门内", 13),
        RouteStation("南礼士路", 14),
        RouteStation("工会大楼", 15),
        RouteStation("木樨地西", 16),
        RouteStation("军事博物馆", 17),
        RouteStation("公主坟", 18),
        RouteStation("翠微路口", 19),
        RouteStation("万寿路口西", 20),
        RouteStation("东翠路口", 21),
        RouteStation("沙沟路口西", 22),
        RouteStation("五棵松桥东", 23),
        RouteStation("永定路口东", 24),
        RouteStation("玉泉路口西", 25),
        RouteStation("八宝山", 26),
        RouteStation("地铁八宝山站", 27),
        RouteStation("老山南路东口", 28),
        RouteStation("老山公交场站", 29)
    )
    private val lineRoute = Route("1路", "11", "1", "四惠枢纽站", "老山公交场站", "05:00", "23:00", "票价2-6元", beijingStops, true)
    private val longStop = Station("天安门东（地铁站）", distance = 44.0, lines = listOf(LineSummary("地铁1号线八通线", "1", to = "环球度假区（地铁站）"), LineSummary("地铁1号线八通线", "2", to = "福寿岭（地铁站）")))
    private val live = VehicleResults(listOf(Vehicle("京A·01236", 1, "at-stop", currentStation = "四惠枢纽站", dataTime = "08:26:00"), Vehicle("京A·03591", 2, "between", segmentDistance = 320.0)), "车辆位置仅供参考，请提前候车")
    private val home = HomeData(stations = listOf(stop), arrivals = mapOf(0 to arrivals), position = Position(24.48, 118.13), cityResolved = true, favoriteLines = listOf(SavedLine(city, LineSummary("91路", "1", route.from, route.to))), favoriteStations = listOf(SavedStation(city, stop)))
    private val timetableData = TimetableData(
        Timetable(emptyList(), "本时刻表显示计划发车时间，受道路通行情况影响可能临时调整，仅供参考。"),
        listOf(
            TimeGroup("06:00–07:59", false, listOf("06:30", "06:45", "07:00", "07:15", "07:30", "07:45").map { Departure(it) }),
            TimeGroup("08:00–09:59", true, listOf("08:00", "08:13", "08:26", "08:40", "08:55", "09:11", "09:28", "09:44", "09:58").map { Departure(it, status = if (it == "08:26") 2 else null) })
        )
    )

    @Composable private fun Home(state: UiState<HomeData>, segment: Int = 0, granted: Boolean = true) = HomeContent(state, city, granted, segment, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> })
    @Composable private fun Line(panel: Int) = LineContent(UiState.Success(LineData(lineRoute, beijingStops[1], live, listOf(Arrival(remainingStations = 1, timeText = "约3分钟", name = "1路", direction = "1")))), beijing, panel, false, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})

    // Compose 1.7 的内置 captureToImage 在 Robolectric 等待窗口重绘时超时：
    // https://github.com/robolectric/robolectric/issues/8071
    // 仍截图真实 Compose View；仅将窗口 PixelCopy 换成 NATIVE Canvas，绝不手绘页面。
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

    private fun capture(name: String, dark: Boolean, expectedWidth: Int = 1233, expectedHeight: Int = 2673, content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(expectedWidth, bitmap.width)
        assertEquals(expectedHeight, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = pixels.asSequence().filterIndexed { i, _ -> i % 16 == 0 }.map { p -> (((p shr 16) and 255) * .2126 + ((p shr 8) and 255) * .7152 + (p and 255) * .0722) }.toList()
        val mean = luminance.average()
        val variance = luminance.sumOf { (it - mean) * (it - mean) } / luminance.size
        assertTrue("截图应含有可见内容，实际方差=$variance", variance > 100.0)
        val dir = File(requireNotNull(System.getProperty("screenshots.dir"))).apply { mkdirs() }
        val file = File(dir, "$name-${if (dark) "dark" else "light"}.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("单张 PNG 不超过 400KB：${file.length()}", file.length() <= 400 * 1024)
    }
    @Test fun scene0_light() = capture("home-nearby", false) { Home(UiState.Success(home)) }
    @Test fun scene0_dark() = capture("home-nearby", true) { Home(UiState.Success(home)) }
    @Test fun scene1_light() = capture("home-favorites", false) { Home(UiState.Success(home), segment = 1) }
    @Test fun scene1_dark() = capture("home-favorites", true) { Home(UiState.Success(home), segment = 1) }
    @Test fun scene2_light() = capture("home-permission", false) { Home(UiState.Success(HomeData(permissionDenied = true)), granted = false) }
    @Test fun scene2_dark() = capture("home-permission", true) { Home(UiState.Success(HomeData(permissionDenied = true)), granted = false) }
    @Test fun scene3_light() = capture("search-results", false) { SearchContent(UiState.Success(SearchData("91路", SearchResults(listOf(LineSummary("91路", "1", route.from, route.to)), listOf(stop)))), {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun scene3_dark() = capture("search-results", true) { SearchContent(UiState.Success(SearchData("91路", SearchResults(listOf(LineSummary("91路", "1", route.from, route.to)), listOf(stop)))), {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun scene4_light() = capture("line-stops", false) { Line(0) }
    @Test fun scene4_dark() = capture("line-stops", true) { Line(0) }
    @Test fun scene5_light() = capture("line-vehicles", false) { Line(1) }
    @Test fun scene5_dark() = capture("line-vehicles", true) { Line(1) }
    @Test fun scene6_light() = capture("station", false) { StationContent(UiState.Success(StationData(stop, arrivals, true)), {}, { _, _ -> }, {}, {}, {}) }
    @Test fun scene6_dark() = capture("station", true) { StationContent(UiState.Success(StationData(stop, arrivals, true)), {}, { _, _ -> }, {}, {}, {}) }
    @Test fun scene7_light() = capture("timetable", false) { TimetableContent(UiState.Success(timetableData), route, emptySet(), {}, {}, {}) }
    @Test fun scene7_dark() = capture("timetable", true) { TimetableContent(UiState.Success(timetableData), route, emptySet(), {}, {}, {}) }
    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun timetableNarrow_light() = capture("timetable-narrow", false, 1080, 2400) { TimetableContent(UiState.Success(timetableData), route, emptySet(), {}, {}, {}) }
    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun timetableNarrow_dark() = capture("timetable-narrow", true, 1080, 2400) { TimetableContent(UiState.Success(timetableData), route, emptySet(), {}, {}, {}) }
    @Test fun scene8_light() = capture("city-picker", false) { CityContent(CityData(listOf(city, City("beijing", "北京市", province = "北京市"), City("fuzhou", "福州市", province = "福建省")), city), {}, {}, {}, {}, {}) }
    @Test fun scene8_dark() = capture("city-picker", true) { CityContent(CityData(listOf(city, City("beijing", "北京市", province = "北京市"), City("fuzhou", "福州市", province = "福建省")), city), {}, {}, {}, {}, {}) }
    @Test fun scene9_light() = capture("settings", false) { SettingsContent(UiState.Success(SettingsData(city)), {}, {}, {}) }
    @Test fun scene9_dark() = capture("settings", true) { SettingsContent(UiState.Success(SettingsData(city)), {}, {}, {}) }
    @Test fun scene10_light() = capture("home-loading", false) { Home(UiState.Loading(HomeData(position = Position(24.48, 118.13)))) }
    @Test fun scene10_dark() = capture("home-loading", true) { Home(UiState.Loading(HomeData(position = Position(24.48, 118.13)))) }
    @Test fun scene11_light() = capture("home-error", false) { Home(UiState.Error("公交查询失败，请检查网络后重试", home)) }
    @Test fun scene11_dark() = capture("home-error", true) { Home(UiState.Error("公交查询失败，请检查网络后重试", home)) }
    @Test fun scene12_light() = capture("favorites-empty", false) { Home(UiState.Success(HomeData()), segment = 1, granted = false) }
    @Test fun scene12_dark() = capture("favorites-empty", true) { Home(UiState.Success(HomeData()), segment = 1, granted = false) }
    @Test fun longStation_light() = capture("home-long-station", false) { HomeContent(UiState.Success(HomeData(stations = listOf(longStop), position = Position(39.907, 116.401), cityResolved = true)), beijing, true, 0, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }) }
    @Test fun longStation_dark() = capture("home-long-station", true) { HomeContent(UiState.Success(HomeData(stations = listOf(longStop), position = Position(39.907, 116.401), cityResolved = true)), beijing, true, 0, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }) }
}
