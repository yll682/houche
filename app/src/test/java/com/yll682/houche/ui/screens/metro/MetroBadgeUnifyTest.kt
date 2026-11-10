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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createComposeRule
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
 * 线路徽标统一（2026-11-08）的三条硬断言：
 *
 * 1. **字色一律白**：tab 徽标 / 卡片站名行的本站线路号 / 列表里的换乘徽标 / 位置条换乘标注，
 *    四个使用点全部逐像素验「有白字、没有深墨字」——官方三个徽标实测都是白字
 *    （连浅绿、浅青的徽标官方也用白字），所以底色亮度再高也不换深墨。
 * 2. **并排的徽标同规格**：同一行里并排出现的「本站线路号」与「换乘」必须同一尺寸
 *    （都等于小徽标档 19.3dp＝58px），不能再一个圆、一个胶囊。
 * 3. **格宽与站心逐像素未变**：徽标换画法不动位置条的几何（与「同一条线去掉 transferTargets」
 *    的渲染逐像素比）。
 *
 * 数据全部来自 `assets/metro.json` 走生产路径 `metroTransfers`；「并排」用的是用户点名的那一站：
 * 2 号线的**鼎美**（02040000，可换 6 号线 06100000）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroBadgeUnifyTest {
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
    private val line2: MetroLine by lazy { lines.first { it.id == "2" } }

    /** 2 号线鼎美（用户截图里的那一站）：可换 6 号线，所以「本站线路号 + 换乘」并排出现。 */
    private val dingmei = "02040000"

    /** 1 号线湖滨东路：位置条上会出现「本线 1 号线 + 换乘 3 号线」两枚小徽标。 */
    private val hubindonglu = "01050000"

    private val viewport = listOf("中山公园", "将军祠", "文灶", "湖滨东路", "莲坂")

    // ---- 场景：一个用例只 setContent 一次，靠状态换场景重渲染 ------------------------

    private sealed class Scene {
        data object Tabs : Scene()
        data class Row(val line: MetroLine, val code: String) : Scene()
        data class Strip(val line: MetroLine, val code: String) : Scene()
    }

    private var scene by mutableStateOf<Scene>(Scene.Tabs)

    private fun direction(line: MetroLine) = MetroDirection(
        origin = line.from, destination = line.to, startTime = "06:00", endTime = "23:00",
        delayMinutes = 0, trains = emptyList(), stationCodes = line.liveStations.map { it.code },
        positionTrains = emptyList(), arrivalsAvailable = false
    )

    private fun mount() {
        RuntimeEnvironment.setQualifiers("+notnight")
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize(), color = officialPalette().Card) {
                    when (val s = scene) {
                        is Scene.Tabs -> MetroLineTabs(lines, "2", {})
                        is Scene.Row -> {
                            val station = s.line.liveStations.first { it.code == s.code }
                            StationRow(station.name, station.code, s.line, loading = false, onRefresh = {}, onTransfer = { _, _ -> })
                        }
                        is Scene.Strip -> MetroTrainStrip(s.code, direction(s.line), s.line)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(): Shot {
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        compose.waitForIdle()
        return Shot(bitmap)
    }

    /** 位置条里主线与选中环的下沿：换乘徽标一定在它下面（主线本身也是线路色）。 */
    private fun stripBadgeTop(): Int =
        ((47.5f + StripRingOuter.value / 2f + 1f) * 3f).toInt()

    // ---- 测试 1：四个使用点的字色一律白 ---------------------------------------------

    @Test
    fun everyLineBadgeInkIsWhite() {
        mount()

        // (2)(3) 卡片站名行：并排的「本站线路号」与「换乘」两枚徽标。
        scene = Scene.Row(line2, dingmei)
        compose.waitForIdle()
        val row = capture()
        val own = row.blocksOf(Color(android.graphics.Color.parseColor(line2.color))).single()
        val transfer = row.blocksOf(Color(android.graphics.Color.parseColor("#92308D"))).single()
        for ((what, block) in listOf("本站线路号" to own, "换乘" to transfer)) {
            val ink = row.inkOf(block, inset = 8f)
            assertTrue("$what 徽标里必须有白字，实际白像素 ${ink.white}", ink.white > 0)
            assertEquals("$what 徽标里不许出现深墨字", 0, ink.dark)
        }

        // (4) 位置条站名下方：换乘标注（1 号线橙 + 3 号线青）。
        scene = Scene.Strip(line1, hubindonglu)
        compose.waitForIdle()
        val strip = capture()
        for (id in listOf("1", "3")) {
            val fill = Color(android.graphics.Color.parseColor(lines.first { it.id == id }.color))
            // 只扫主线以下：主线本身和选中环也是线路色，但它们在站名上面。
            val block = strip.blocksOf(fill, fromRow = stripBadgeTop()).single()
            val ink = strip.inkOf(block, inset = 8f)
            assertTrue("位置条 $id 号线徽标里必须有白字，实际白像素 ${ink.white}", ink.white > 0)
            assertEquals("位置条 $id 号线徽标里不许出现深墨字", 0, ink.dark)
        }
    }

    // ---- 测试 2：并排的徽标同一级、同一尺寸 -------------------------------------------

    @Test
    fun sideBySideBadgesShareTheSameSize() {
        mount()
        scene = Scene.Row(line2, dingmei)
        compose.waitForIdle()

        val shot = capture()
        val own = shot.blocksOf(Color(android.graphics.Color.parseColor(line2.color))).single()
        val transfer = shot.blocksOf(Color(android.graphics.Color.parseColor("#92308D"))).single()

        // 两枚都是小徽标档 19.3dp＝58px（官方位置条换乘徽标 58×58px）。
        assertEquals("本站线路号徽标应为 58px（19.3dp）见方", 58f, own.width, 1f)
        assertEquals("本站线路号徽标应为 58px（19.3dp）见方", 58f, own.height, 1f)
        assertEquals("换乘徽标应为 58px（19.3dp）见方", 58f, transfer.width, 1f)
        assertEquals("换乘徽标应为 58px（19.3dp）见方", 58f, transfer.height, 1f)

        // 并排：两枚 bounds 完全相等（宽高一致），且左右相邻、垂直方向重叠。
        assertEquals("并排两枚徽标的宽度必须相等", own.width, transfer.width, 0f)
        assertEquals("并排两枚徽标的高度必须相等", own.height, transfer.height, 0f)
        assertTrue("本站线路号徽标应在换乘徽标左边", own.right <= transfer.left)
        assertTrue(
            "并排两枚徽标应在同一水平线上（垂直方向重叠）",
            own.top < transfer.bottom && transfer.top < own.bottom
        )
    }

    // ---- 测试 3：格宽与站心逐像素未变 -------------------------------------------------

    @Test
    fun stripCellWidthAndStationCentersStayPixelExact() {
        mount()

        scene = Scene.Strip(line1, hubindonglu)
        compose.waitForIdle()
        val withBadges = capture()
        val withCols = viewport.associateWith {
            compose.onAllNodesWithContentDescription(it, substring = true).onFirst()
                .fetchSemanticsNode().boundsInRoot
        }

        // 同一��线、同一参考站，只是把换乘目标清空＝徽标出现之前的画法。
        val plain = line1.copy(stations = line1.stations.map { it.copy(transferTargets = emptyList()) })
        scene = Scene.Strip(plain, hubindonglu)
        compose.waitForIdle()
        val withoutBadges = capture()
        val withoutCols = viewport.associateWith {
            compose.onAllNodesWithContentDescription(it, substring = true).onFirst()
                .fetchSemanticsNode().boundsInRoot
        }

        for (name in viewport) {
            val a = withoutCols.getValue(name)
            val b = withCols.getValue(name)
            assertEquals("$name 列左沿（站心 x=(i+0.5)·W）不能变", a.left, b.left, 0f)
            assertEquals("$name 列宽度（格宽 W）不能变", a.width, b.width, 0f)
            assertEquals("$name 列上沿（车标 lane）不能变", a.top, b.top, 0f)
        }

        // 徽标上沿以下的横带逐像素一致（徽标只往下长，不挪动任何东西）。
        val bandTop = (47.5f + StripNameGap.value - 3.9f) * 3f +
            "湖滨东路".length * StripNameLineSelected.value * 3f + StripTransferGap.value * 3f
        assertEquals("换乘徽标不能挪动任何横向/纵向几何", 0, withBadges.diffBand(withoutBadges, 0, bandTop.toInt() - 2))
    }

    // ---- 像素工具 ---------------------------------------------------------------------

    private inner class Shot(val bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        private val px = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

        private fun at(x: Int, y: Int): Int = px[y * width + x]

        private fun channelDistance(a: Int, b: Int): Int = maxOf(
            abs(((a shr 16) and 255) - ((b shr 16) and 255)),
            abs(((a shr 8) and 255) - ((b shr 8) and 255)),
            abs((a and 255) - (b and 255))
        )

        /**
         * 画面里底色命中 [fill] 的**色块**：连续 ≥8 行、每行 ≥20 像素命中才算一块
         * （徽标是 58×58 的实心块，字迹与抗锯齿边缘都过不了这个门槛）。
         */
        fun blocksOf(fill: Color, fromRow: Int = 0, toRow: Int = height): List<Rect> {
            val target = fill.toArgb()
            fun rowHits(y: Int) = (0 until width).filter { channelDistance(at(it, y), target) <= 40 }
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
                    if (left <= right) blocks += Rect(left.toFloat(), start.toFloat(), right.toFloat(), end.toFloat())
                }
                start = -1
            }
            for (y in fromRow.coerceIn(0, height - 1)..toRow.coerceIn(0, height - 1)) {
                if (rowHits(y).size >= 20) {
                    if (start < 0) start = y
                } else {
                    close(y - 1)
                }
            }
            close(toRow.coerceIn(0, height - 1))
            return blocks
        }

        /** 徽标块内部的字色统计：白字像素数 与 深墨（#1A1A1A）像素数。 */
        fun inkOf(block: Rect, inset: Float): Ink {
            val x0 = (block.left + inset).toInt().coerceIn(0, width - 1)
            val x1 = (block.right - inset).toInt().coerceIn(0, width - 1)
            val y0 = (block.top + inset).toInt().coerceIn(0, height - 1)
            val y1 = (block.bottom - inset).toInt().coerceIn(0, height - 1)
            var white = 0
            var dark = 0
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val p = at(x, y)
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    if (r >= 200 && g >= 200 && b >= 200) white++
                    if (channelDistance(p, 0xFF1A1A1A.toInt()) <= 40) dark++
                }
            }
            return Ink(white, dark)
        }

        /** 两张图在 [from, to) 行内的差异像素数。 */
        fun diffBand(other: Shot, from: Int, to: Int): Int {
            var diff = 0
            for (y in from until minOf(to, height, other.height)) {
                for (x in 0 until minOf(width, other.width)) if (at(x, y) != other.at(x, y)) diff++
            }
            return diff
        }
    }

    private class Ink(val white: Int, val dark: Int)
}
