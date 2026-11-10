package com.yll682.houche.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroFixtures
import com.yll682.houche.core.City
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRealtimeRemote
import com.yll682.houche.core.mergeMetroRealtime
import com.yll682.houche.core.metroTransfers
import com.yll682.houche.core.positionOf
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.HomeTab
import com.yll682.houche.ui.screens.home.HomeContent
import com.yll682.houche.ui.screens.home.HomeData
import com.yll682.houche.ui.screens.metro.MetroContent
import com.yll682.houche.ui.screens.metro.MetroData
import com.yll682.houche.ui.screens.metro.arrivalOrdinals
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
 * 真实一号线「吕厝」17:32:30 那一轮的 24 站明文响应，走**生产**解析（`MetroRealtimeRemote.parse`）
 * 与合并（`mergeMetroRealtime`），再按生产页面的方式渲染一张浅色截图。
 *
 * 这个用例守的是用户 17:15 报的严重 bug：位置条上堆了 10 辆车、ETA 写成「第10班」。
 * 它同时是**硬断言**：车标数、同格堆叠、ETA 班号三条都在这里量化，不靠肉眼看图。
 *
 * 还有用户拍板的班号规则：**第 1 班 = 最先到本站那班**，**已开过本站的车不占号、标「已过」，
 * 但车标必须保留**。真实这一轮里 11 个车标是「第1..4班 + 7 个已过」，一个都不许丢。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi", application = android.app.Application::class)
class Line1MultiShotTest {
    @get:Rule val compose = createComposeRule()

    /** 固定时钟：24 个响应属于同一轮抓取，共用的只有服务端 `ts`。 */
    private val remote = MetroRealtimeRemote(elapsedRealtimeMillis = { 0L }, wallClockMillis = { 0L })

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        metroTransfers(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<MetroNetwork>(text).openedLines
        )
    }

    private val line1: MetroLine get() = lines.first { it.id == "1" }

    /** 参考站「吕厝」。 */
    private val reference = "01080000"

    /** 17:32:30 那一轮：24 个站各一份原始响应。 */
    private val frame: MetroRealtime by lazy {
        val directory = requireNotNull(MetroFixtures.file("line1-multi/index.tsv").parentFile)
        val snapshots = directory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("rt-01000000-\\d{8}-173230\\.json")) }
            .sortedBy { it.name }
            .map { remote.parse(it.readText(), "01000000") }
        assertEquals("这一轮应有 24 个站的成功响应", 24, snapshots.size)
        assertTrue("24 个响应都必须解析成功", snapshots.all { it.online && it.directions.size == 2 })
        mergeMetroRealtime(snapshots, reference, "01000000")
    }

    private val data: MetroData by lazy {
        MetroData(
            lines = lines, selected = "1", updated = "2026-11-08",
            realtime = frame,
            realtimeStation = reference,
            realtimeStationName = line1.liveStations.firstOrNull { it.code == reference }?.name.orEmpty(),
            realtimeUpdated = "17:32", directionIndex = 0
        )
    }

    /** 车标语义节点：「第N班」或「已过」，站名列的 contentDescription 是站名，不会误命中。 */
    private val markerNodes
        get() = compose.onAllNodes(
            SemanticsMatcher("车标") { node ->
                val labels = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                labels.size == 1 && (labels[0] == "已过" || labels[0].matches(Regex("第\\d+班")))
            }
        ).fetchSemanticsNodes()

    @Test
    fun realLine1PositionBatchIsSparseAndEtaStartsFromOne() {
        // ---- 数据层：身份数 / 可见车标数 ----
        assertEquals("两个方向各一份站序", 2, frame.directions.size)
        frame.directions.forEachIndexed { index, direction ->
            val visible = direction.positions.size
            // 第六轮验收基准：真实数据下每方向 13 个身份、11 个可见车标（修前是 54–60 条候选堆成 10 辆）。
            assertEquals("方向 $index 的列车身份数", 13, direction.allIdentifiedTrains.size)
            assertEquals("方向 $index 的可见车标数", 11, visible)
            // 同向任意两个可见车标至少相隔一站（core 的物理下限），不允许同格堆放。
            val positions = direction.positions.map { direction.positionOf(it) }.sortedDescending()
            positions.zipWithNext().forEach { (down, up) ->
                assertTrue("同向车标不得挤在同一格：$positions", down - up >= 1.0)
            }
        }

        val direction = requireNotNull(data.direction)
        val ordinals = direction.arrivalOrdinals(reference)
        // ---- ETA 三格班号必须是 第1/2/3班 ----
        assertEquals("本站 ETA 三行各自锚定 1..3 班", listOf(1, 2, 3), (1..3).map { ordinals.ordinalOfRow(it) })
        assertEquals("本站三行 ETA 不许出现第 10 班这种编号", 3, direction.trains.size)
        // 用户拍板：已过本站的车**不占号**，只画车标 + 「已过」；其余按 p 降序连续编号 1..N。
        assertTrue(
            "已过本站的车不许占班号：${ordinals.trains.filter { it.passed }.map { it.ordinal }}",
            ordinals.trains.filter { it.passed }.all { it.ordinal == null }
        )
        assertEquals("未到站的车连续编号 1..N，中间不跳号", (1..ordinals.maxOrdinal).toList(),
            ordinals.trains.mapNotNull { it.ordinal })
        assertEquals("已过本站的车标数", 7, ordinals.passedCount)
        assertEquals("车标一个都不能少：编号 + 已过", direction.positions.size, ordinals.trains.size)

        // ---- 渲染层：位置条上真正画出的车标 ----
        render()
        val nodes = markerNodes
        assertEquals(
            "位置条车标数必须等于本方向可见位置数（不能把被抑制的身份也画出来）",
            direction.positions.size, nodes.size
        )
        // 同一格堆叠＝两个车标中心横坐标几乎重合；真实数据下应为 0。
        // 位置条横向滚动 + clipToBounds：`boundsInRoot` 会被视口裁掉（屏外车标读成 0），
        // 所以量堆叠必须用未裁剪的 layout 坐标 `positionInRoot`。
        val centers = nodes.map { it.positionInRoot.x + it.size.width / 2f }.sorted()
        val stacked = centers.zipWithNext().count { (left, right) -> kotlin.math.abs(right - left) < 8f }
        assertEquals("不能有车标堆在同一格上，实测中心横坐标 $centers", 0, stacked)
        // 视口（360dp ≈ 5.3 格）里肉眼能看到的车标数，与截图核对用。
        val onScreen = nodes.count { it.boundsInRoot.width > 1f && it.boundsInRoot.height > 1f }
        // 车标标签：未到站的一次编号、从 1 起连续、不重复；已过本站的标「已过」且一个都不许丢。
        val labels = nodes.map { it.config[SemanticsProperties.ContentDescription].single() }
        // 「已过」本来就会重复出现（多个车标都标它），唯一性只对班号成立。
        val numbered = labels.filter { it != "已过" }
        assertEquals("同一批车标不许重复编号：$labels", numbered.size, numbered.toSet().size)
        assertTrue("车标标签只能是「第N班」或「已过」：$labels",
            labels.all { it == "已过" || it.matches(Regex("第\\d+班")) })
        assertEquals(
            "位置条班号必须从第1班起连续，实测 $labels",
            (1..(nodes.size - labels.count { it == "已过" })).map { "第${it}班" },
            labels.filter { it != "已过" }.sortedBy { it.drop(1).dropLast(1).toInt() }
        )
        assertEquals("已过本站的车标必须照样画出来", 7, labels.count { it == "已过" })
        assertEquals("11 个车标里 4 班有号、7 个已过", listOf(4, 7),
            listOf(labels.count { it != "已过" }, labels.count { it == "已过" }))

        // ---- 渲染层：ETA 三格文案 ----
        val eta = compose.onAllNodes(
            SemanticsMatcher("ETA 格") { node ->
                val labels = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                labels.size == 1 && labels[0].endsWith("班预计到达")
            }
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals("ETA 必须有三格", 3, eta.size)
        assertTrue("ETA 三格只能出现 第1/2/3班，实际 $eta",
            eta.all { text -> Regex("^\\d+ 分钟第[123]班预计到达$").matches(text) })
        assertTrue("ETA 不能出现「第10班」，实际 $eta", eta.none { it.contains("第10班") })

        // 三个实测数字（车标数 / 同格最大堆叠 / ETA 班号）+ 另一方向的对照，写进 notes 与截图报告。
        val other = frame.directions[1]
        println(
            "LINE1 markers=${nodes.size} onScreen=$onScreen identities=${direction.allIdentifiedTrains.size} " +
                "stacked=$stacked eta=$eta labels=$labels positions=${direction.positions.map { direction.positionOf(it) }} " +
                "passed=${ordinals.passedCount} passedLabeled=${labels.count { it == "已过" }} " +
                "otherDirection(identities=${other.allIdentifiedTrains.size}, markers=${other.positions.size}, " +
                "passed=${other.arrivalOrdinals(reference).passedCount})"
        )
    }

    @Test
    fun metroLine1Multi_light() {
        render()
        compose.onNodeWithText("列车位置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        listOf("公交", "地铁", "收藏", "设置").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        val bitmap = compose.onRoot().let { root ->
            val node = root.fetchSemanticsNode()
            val view = (node.root as ViewRootForTest).view
            lateinit var shot: Bitmap
            compose.runOnUiThread {
                shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(shot))
            }
            shot
        }
        assertEquals(1080, bitmap.width)
        assertEquals(2340, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = pixels.asSequence().filterIndexed { index, _ -> index % 16 == 0 }
            .map { p -> (((p shr 16) and 255) * .2126 + ((p shr 8) and 255) * .7152 + (p and 255) * .0722) }
            .toList()
        val mean = luminance.average()
        assertTrue("截图应含有可见内容", luminance.sumOf { (it - mean) * (it - mean) } / luminance.size > 100.0)
        val file = File(requireNotNull(System.getProperty("screenshots.dir")), "metro-line1-multi-light.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("单张 PNG 不超过 400KB：${file.length()}", file.length() <= 400 * 1024)
    }

    private fun render() {
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) {
                    HomeContent(
                        state = UiState.Success(HomeData(hasMetro = true)),
                        city = City("xiamen", "厦门市"), granted = false,
                        segment = HomeTab.METRO, selectSegment = {},
                        locate = {}, search = {}, cities = {},
                        line = { _, _, _ -> }, station = { _, _ -> },
                        metroContent = { MetroContent(UiState.Success(data), {}, refresh = {}) }
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
