package com.yll682.houche.ui.screens.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.metroTransfers
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 位置条「站名下方换乘徽标」的逐像素硬断言（官方基准图
 * `notes/refs/official-line1-train-position.jpg` 量的数）：
 *
 * 1. 有换乘目标的站，站名下方竖排**本线徽标 + 每目标一枚**（湖滨东路：橙 1 号线 + 青 3 号线，
 *    与基准图一模一样），没有目标的站一枚都不画；
 * 2. 每枚色块 58×58px（19.33dp）、相邻间距 12px、居中于站心（±1px）、
 *    站名最后一字墨迹底 → 徽标上沿 22px；
 * 3. 徽标只往下长：格宽 W、站心、车标 lane、站名一个字都不动（与「去掉 transferTargets」逐像素比）；
 * 4. 徽标自己是装饰件（不进无障碍树），整列仍然一句话、点徽标位置仍是切这一列。
 *
 * 站名/站码/线路色全部读 `assets/metro.json` + `metroTransfers`，不手抄。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroStripTransferBadgeTest {
    @get:Rule val compose = createComposeRule()

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        metroTransfers(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<MetroNetwork>(text).openedLines
        )
    }
    private val line1: MetroLine by lazy { lines.first { it.id == "1" } }

    /** 1 号线湖滨东路：官方共站码可换 3 号线（基准图里那两枚橙 + 青徽标）。 */
    private val hubindonglu = "01050000"

    /** 1 号线文灶：不是换乘站（基准图里站名下方空着）。 */
    private val wenzao = "01040000"

    /** 选中湖滨东路时视口里的列（由 78% 锚点定，不是手挑的）。 */
    private val viewport = listOf("中山公园", "将军祠", "文灶", "湖滨东路", "莲坂")

    private var scene by mutableStateOf<Pair<MetroLine, String>?>(null)
    private var sink: (String) -> Unit = {}

    private fun direction(line: MetroLine) = MetroDirection(
        origin = line.from, destination = line.to, startTime = "06:00", endTime = "23:00",
        delayMinutes = 0, trains = emptyList(), stationCodes = line.liveStations.map { it.code },
        positionTrains = emptyList(), arrivalsAvailable = false
    )

    private fun MetroLine.withoutTransfers(): MetroLine =
        copy(stations = stations.map { it.copy(transferTargets = emptyList()) })

    private fun mount(onSelect: (String) -> Unit = {}) {
        sink = onSelect
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize(), color = officialPalette().Card) {
                    scene?.let { (line, station) ->
                        MetroTrainStrip(station, direction(line), line, onSelectStation = { sink(it) })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(line: MetroLine, station: String): Shot {
        scene = line to station
        compose.waitForIdle()
        val cols = viewport.associateWith {
            compose.onAllNodesWithContentDescription(it, substring = true).onFirst()
                .fetchSemanticsNode().boundsInRoot
        }
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return Shot(bitmap, cols)
    }

    private fun hex(value: String): Color = Color(android.graphics.Color.parseColor(value))

    private fun lineColorOf(id: String) = hex(lines.first { it.id == id }.color)

    // ---- 测试 1：徽标数量与底色 -------------------------------------------------------

    @Test
    fun stripShowsOwnLineBadgeThenOneBadgePerTransferTarget() {
        mount()
        val shot = capture(line1, hubindonglu)
        val col = shot.cols.getValue("湖滨东路")

        val own = shot.blocksOf(col, lineColorOf("1"))
        val transfer = shot.blocksOf(col, lineColorOf("3"))
        assertEquals("湖滨东路应恰好有 1 枚本线（1 号线橙）徽标，实际 $own", 1, own.size)
        assertEquals("湖滨东路应恰好有 1 枚 3 号线青徽标，实际 $transfer", 1, transfer.size)

        // 本线在前、换乘目标在后（基准图同一顺序：橙 1 号线在上、青 3 号线在下）。
        val first = own.single()
        val second = transfer.single()
        assertTrue("本线徽标必须排在换乘徽标上面：$first / $second", second.top > first.bottom)

        // 没有换乘目标的文灶：五条线任何一色的色块都不许出现。
        val wenzaoCol = shot.cols.getValue("文灶")
        for (line in lines) {
            val stray = shot.blocksOf(wenzaoCol, hex(line.color))
            assertTrue("文灶站名下方不该有 ${line.id} 号线徽标，实际 $stray", stray.isEmpty())
        }
    }

    // ---- 测试 2：逐像素几何（基准图 58 / 12 / 22px） ---------------------------------

    @Test
    fun badgeGeometryMatchesTheOfficialMeasurements() {
        mount()
        val shot = capture(line1, hubindonglu)
        val col = shot.cols.getValue("湖滨东路")
        val own = shot.blocksOf(col, lineColorOf("1")).single()
        val transfer = shot.blocksOf(col, lineColorOf("3")).single()

        assertEquals("色块宽应 58px（基准图 58px）", 58f, own.width, 1f)
        assertEquals("色块高应 58px（基准图 58px）", 58f, own.height, 1f)
        assertEquals(
            "两枚徽标上沿间距应 70px＝58px 色块 + 12px 间隙（基准图 1384→1454）",
            70f, transfer.top - own.top, 1f
        )

        // 居中于站心：列中心与色块中心之差 ≤1px。
        val columnCenter = (col.left + col.right) / 2f
        val badgeCenter = (own.left + own.right) / 2f
        assertTrue("徽标必须居中于站心：$columnCenter vs $badgeCenter", abs(columnCenter - badgeCenter) <= 1f)

        // 站名最后一字墨迹底 → 徽标上沿 22px（基准图实测 1362→1384）。
        val nameBottom = shot.nameInkBottom(col)
        assertEquals("站名最后一字到徽标上沿应 22px", 22f, own.top - nameBottom, 2f)

        // 徽标不能被条底裁、也不能压到下一列。
        assertTrue("徽标不能低于列底：${transfer.bottom} vs ${col.bottom}", transfer.bottom <= col.bottom - 1f)
        assertTrue("徽标不能越出本列：$own vs $col", own.left >= col.left - 1f && own.right <= col.right + 1f)
    }

    // ---- 测试 3：格宽 / 站心逐像素不变 ------------------------------------------------

    @Test
    fun badgesKeepCellWidthAndStationCentersPixelExact() {
        mount()
        val before = capture(line1.withoutTransfers(), hubindonglu)
        val withBadges = capture(line1, hubindonglu)

        for (name in viewport) {
            val a = before.cols.getValue(name)
            val b = withBadges.cols.getValue(name)
            assertEquals("$name 列左沿（站心 x=(i+0.5)·W）不能变", a.left, b.left)
            assertEquals("$name 列宽度（格宽 W）不能变", a.width, b.width)
            assertEquals("$name 列上沿（车标 lane）不能变", a.top, b.top)
        }

        // 徽标出现之前的整条横带（车标 + 主线 + 箭头 + 选中环 + 站名）逐像素一致。
        val labelTop = (47.5f + StripNameGap.value - 3.9f) * 3f +
            "湖滨东路".length * StripNameLineSelected.value * 3f + StripTransferGap.value * 3f
        val diff = withBadges.diffBand(before, 0, labelTop.toInt() - 2)
        assertEquals("换乘徽标不能挪动任何横向/纵向几何，差异像素 $diff", 0, diff)
    }

    // ---- 测试 4：徽标是装饰件，点它仍然是切这一列 --------------------------------------

    @Test
    fun badgesAreDecorativeAndClickingThemStillSelectsTheColumn() {
        val picks = mutableListOf<String>()
        mount(onSelect = { picks += it })
        val shot = capture(line1, hubindonglu)

        // 整列一句话读出来；徽标自己不生成节点。
        assertEquals(
            "整列应只有一句话读到「3号线」",
            1,
            compose.onAllNodesWithContentDescription("湖滨东路，可换乘 3号线").fetchSemanticsNodes().size
        )
        assertTrue(
            "徽标本身不该是独立节点（没有任何节点只叫「3号线」）",
            compose.onAllNodesWithContentDescription("3号线").fetchSemanticsNodes().isEmpty()
        )

        // 点在**徽标所在的位置**：语义仍是这一列 → 切参考站。
        val col = shot.cols.getValue("湖滨东路")
        val badge = shot.blocksOf(col, lineColorOf("3")).single()
        compose.onAllNodesWithContentDescription("湖滨东路，可换乘 3号线").onFirst().clickCenterOf(badge, col)
        compose.waitForIdle()
        assertEquals("点徽标落点仍然只是把这一列交出去", listOf(hubindonglu), picks)
    }

    private fun SemanticsNodeInteraction.clickCenterOf(badge: Rect, col: Rect) = performTouchInput {
        click(Offset((badge.left + badge.right) / 2f - col.left, (badge.top + badge.bottom) / 2f - col.top))
    }

    // ---- 像素工具 ---------------------------------------------------------------------

    private inner class Shot(val bitmap: Bitmap, val cols: Map<String, Rect>) {
        val width = bitmap.width
        val height = bitmap.height
        private val px = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

        private fun at(x: Int, y: Int): Int = px[y * width + x]

        /**
         * 该列里底色命中 [fill] 的**色块**：连续 ≥8 行、每行 ≥20 像素命中才算一枚
         * （徽标是 58×58 的实心块，字迹/抗锯齿边缘过不了这个门槛）。
         */
        fun blocksOf(col: Rect, fill: Color): List<Rect> {
            val x0 = col.left.toInt().coerceAtLeast(0)
            val x1 = col.right.toInt().coerceAtMost(width - 1)
            // 起点压在选中环下沿再让 1dp：环本身是线路色，从主线底扫会把环量成徽标。
            val yStart = (47.5f + StripRingOuter.value / 2f + 1f).times(3f).toInt().coerceIn(0, height - 1)
            val yEnd = col.bottom.toInt().coerceAtMost(height - 1)
            val target = fill.toArgb()
            fun rowHits(y: Int) = (x0..x1).filter { channelDistance(at(it, y), target) <= 40 }
            val blocks = mutableListOf<Rect>()
            var start = -1
            fun close(end: Int) {
                if (start >= 0 && end - start >= 8) {
                    var left = Int.MAX_VALUE
                    var right = Int.MIN_VALUE
                    for (y in start..end) {
                        val xs = rowHits(y)
                        if (xs.isNotEmpty()) {
                            left = minOf(left, xs.first())
                            right = maxOf(right, xs.last())
                        }
                    }
                    if (left <= right) {
                        blocks += Rect(left.toFloat(), start.toFloat(), right.toFloat(), end.toFloat())
                    }
                }
                start = -1
            }
            for (y in yStart..yEnd) {
                if (rowHits(y).size >= 20) {
                    if (start < 0) start = y
                } else {
                    close(y - 1)
                }
            }
            close(yEnd)
            return blocks
        }

        /** 主线（含箭头、选中环）下沿：徽标不可能出现在这条线以上。 */
        val trackBottom: Float = (47.5f + StripTrack.value / 2f) * 3f

        /** 该列里站名最后一字的墨迹底（站名只用 SubInk / StationSelected 两色）。 */
        fun nameInkBottom(col: Rect): Float {
            val inkList = listOf(OfficialLight.SubInk.toArgb(), OfficialLight.StationSelected.toArgb())
            val x0 = col.left.toInt().coerceAtLeast(0)
            val x1 = col.right.toInt().coerceAtMost(width - 1)
            val yEnd = col.bottom.toInt().coerceAtMost(height - 1)
            for (y in yEnd downTo col.top.toInt().coerceAtLeast(0)) {
                for (x in x0..x1) {
                    // 徽标里的深墨字 #1A1A1A 与 SubInk #666 / 选中站靛蓝 #314093 差 ≥23，
                    // 20 的容差不会把徽标里的字量成站名。
                    if (inkList.any { channelDistance(at(x, y), it) <= 20 }) return y.toFloat()
                }
            }
            return col.top
        }

        fun diffBand(other: Shot, from: Int, to: Int): Int {
            var diff = 0
            for (y in from until minOf(to, height, other.height)) {
                for (x in 0 until minOf(width, other.width)) if (at(x, y) != other.at(x, y)) diff++
            }
            return diff
        }
    }

    private fun channelDistance(a: Int, b: Int): Int = maxOf(
        abs(((a shr 16) and 255) - ((b shr 16) and 255)),
        abs(((a shr 8) and 255) - ((b shr 8) and 255)),
        abs((a and 255) - (b and 255))
    )
}