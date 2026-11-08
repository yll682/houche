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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroTransferEvidence
import com.yll682.houche.core.MetroTransferTarget
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
 * 位置条「站名下面的换乘线路」硬断言（不靠肉眼看图）：
 *
 * 1. 有 `transferTargets` 的站在站名下**逐行**写出可换乘线路，字色＝目标线路自己的颜色；
 *    没有目标的站一个字都不画；
 * 2. 多个目标时一行一个目标：两行不重叠、都落在本格内、都不与站名最后一字相撞、都不被条底裁；
 * 3. 换乘标注只往**下**长：格宽 W 与站心逐像素不变（与「同一条线去掉 transferTargets」
 *    的渲染逐像素比——那一份就是改动前的画法）；
 * 4. 标注不可点、也不单独进无障碍树；整列一句话里多出「可换乘 2号线」，
 *    点在标注位置上仍然是切这一列（切参考站）。
 *
 * 站名/站码/线路色全部来自 `assets/metro.json` + `metroTransfers`，不手抄；
 * 唯一的合成数据是「一站两个换乘目标」（内置站表里每个换乘站都只有 1 个目标，造不出来）。
 *
 * 同一个测试里要出两张图（改前/改后）时，走 [mount] 一次 + 改 `scene` 状态重渲染，
 * 因为 Compose 测试规则不允许一个用例调两次 `setContent`。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroStripTransferTest {
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
    private val line6: MetroLine by lazy { lines.first { it.id == "6" } }

    /** 1 号线「吕厝」：官方共站码 01080000 ↔ 2 号线 02180000，位置条上应写绿色的「2号线」。 */
    private val luocuo = "01080000"

    /** 1 号线「文灶」：不是换乘站，用来验「不该画的地方一个字都没有」。 */
    private val wenzao = "01040000"

    /** 6 号线「马銮中心」：4 字站名 + 换乘，位置条要整体加高的那种。 */
    private val malian = "06110000"

    /** 1 号线选中吕厝时视口里的 7 列（站位由 78% 锚点定，不是手挑的）。 */
    private val viewport1 = listOf("将军祠", "文灶", "湖滨东路", "莲坂", "莲花路口", "吕厝", "乌石浦")

    /** 6 号线选中马銮中心时的视口列。 */
    private val viewport6 = listOf("鼎美", "马銮中心", "西滨", "碑头", "杏滨", "内茂")

    private data class Scene(val line: MetroLine, val station: String)

    private var scene by mutableStateOf<Scene?>(null)
    private var sink: (String) -> Unit = {}
    private var viewport: List<String> = emptyList()

    private fun direction(line: MetroLine) = MetroDirection(
        origin = line.from, destination = line.to, startTime = "06:00", endTime = "23:00",
        delayMinutes = 0, trains = emptyList(), stationCodes = line.liveStations.map { it.code },
        positionTrains = emptyList(), arrivalsAvailable = false
    )

    /** 把 `transferTargets` 清空的同一条线：换乘标注出现之前，这一份就是改动前的渲染结果。 */
    private fun MetroLine.withoutTransfers(): MetroLine =
        copy(stations = stations.map { it.copy(transferTargets = emptyList()) })

    /** 装一次内容（一个用例只装一次），底色用本页卡片色（浅白 / 深 `#181D23`）。 */
    private fun mount(dark: Boolean, names: List<String>, onSelect: (String) -> Unit = {}) {
        viewport = names
        sink = onSelect
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize(), color = officialPalette().Card) {
                    scene?.let { MetroTrainStrip(it.station, direction(it.line), it.line, onSelectStation = { sink(it) }) }
                }
            }
        }
        compose.waitForIdle()
    }

    /** 换成这一份数据重渲染，量各站整列 bounds 并整帧取像素。 */
    private fun capture(line: MetroLine, station: String, dark: Boolean = false): Shot {
        scene = Scene(line, station)
        compose.waitForIdle()
        val cols = viewport.associateWith {
            compose.onAllNodesWithContentDescription(it, substring = true).onFirst()
                .fetchSemanticsNode().boundsInRoot
        }
        return Shot(drawRoot(), cols, if (dark) OfficialDark else OfficialLight)
    }

    private fun drawRoot(): Bitmap {
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun hex(value: String): Color = Color(android.graphics.Color.parseColor(value))

    /**
     * 按布局常量算「站名最后一字的行盒下沿 + [StripTransferGap]」，即换乘标注第一行的上沿（px）。
     * 直接读 [MetroTrainStrip] 用的同一批 dp 常量，不在这里重抄数字。
     */
    private fun transferTopPx(name: String, selected: Boolean): Int {
        val namesTop = 47.5f + StripNameGap.value - 3.9f      // 主线中心 + 站名间距 − ink 内缩
        val lineHeight = if (selected) StripNameLineSelected.value else StripNameLine.value
        return ((namesTop + name.length * lineHeight + StripTransferGap.value) * 3f).toInt()
    }

    private fun firstTransferTopPx(vararg stations: Pair<String, Boolean>): Int =
        stations.minOf { transferTopPx(it.first, it.second) }

    private val line2Color: String get() = lines.first { it.id == "2" }.color

    // ---- 测试 1：换乘站画、非换乘站不画 -----------------------------------------------

    @Test
    fun stripDrawsTransferLineUnderTransferStationOnly() {
        mount(dark = false, names = viewport1)
        val shot = capture(line1, luocuo)

        // 吕厝在 1 号线上换 2 号线：换乘徽标的**底色**必须是 2 号线自己的绿（#52A441）。
        val green = hex(line2Color)

        val luocuoCol = shot.cols.getValue("吕厝")
        val bands = shot.bandsOf(luocuoCol, green, from = shot.trackBottom)
        assertEquals("吕厝站名下方应恰好有 1 枚 2 号线绿色徽标，实际 $bands", 1, bands.size)
        val band = bands.single()
        assertTrue(
            "标注必须落在本格水平范围内：$band vs $luocuoCol",
            band.left >= luocuoCol.left - 1 && band.right <= luocuoCol.right + 1
        )
        val nameBottom = shot.nameInkBottom(luocuoCol)
        assertTrue("标注必须与站名最后一字分开（≥2dp），实际 ${band.top - nameBottom}px", band.top - nameBottom >= 6f)
        assertTrue(
            "标注不能被条底裁掉：${band.bottom} vs ${luocuoCol.bottom}",
            band.bottom <= luocuoCol.bottom - 1
        )

        // 同屏的文灶不是换乘站：站名下面五种线路色一行都不许出现（主线本身是 1 号线橙，
        // 所以只扫站名最后一字以下，不然量到的是轨道）。
        val wenzaoCol = shot.cols.getValue("文灶")
        for (line in lines) {
            val ink = hex(line.color)
            val stray = shot.bandsOf(wenzaoCol, ink, from = shot.trackBottom)
            assertTrue("文灶不该有 ${line.id} 号线的标注，实际 $stray", stray.isEmpty())
        }
    }

    // ---- 测试 2：多个目标竖排、不重叠、不撞站名、不被裁 -------------------------------

    @Test
    fun stripStacksOneRowPerTransferTarget() {
        // 内置站表里每个换乘站只有 1 个目标，这里给吕厝加第二条（4 号线红）。
        // 三枚徽标自上而下＝本线 1 号线橙、2 号线绿、4 号线红。
        val red = lines.first { it.id == "4" }.color
        val targets = listOf(
            MetroTransferTarget("2", "02180000", "二号线", line2Color, MetroTransferEvidence.OFFICIAL_STATION_UNIQUE_CODE),
            MetroTransferTarget("4", "04030000", "四号线", red, MetroTransferEvidence.OFFICIAL_STATION_UNIQUE_CODE)
        )
        val line = line1.copy(stations = line1.stations.map {
            if (it.code == luocuo) it.copy(transferTargets = targets) else it
        })
        mount(dark = false, names = viewport1)
        val shot = capture(line, luocuo)
        val col = shot.cols.getValue("吕厝")
        val green = shot.bandsOf(col, hex(line2Color), from = shot.trackBottom)
        val redBands = shot.bandsOf(col, hex(red), from = shot.trackBottom)

        assertEquals("两个目标必须各占一行（绿只该有一行）", 1, green.size)
        assertEquals("两个目标必须各占一行（红只该有一行）", 1, redBands.size)
        // 本站线徽标（1 号线橙）排在最上面，且只有一枚。
        // 从选中环下沿往下扫：环也是 1 号线同一色，从主线底扫会把它量成一枚「徽标」。
        val ownBands = shot.bandsOf(
            col, hex(line1.color), from = (47.5f + StripRingOuter.value / 2f + 1f) * 3f
        )
        assertEquals("本站线徽标只该有一枚，实际 $ownBands", 1, ownBands.size)
        val first = green.single()
        val second = redBands.single()
        assertTrue("第 2 行必须排在第 1 行下面：$first / $second", second.top > first.top)
        assertTrue("两行不能贴在一起（至少 1px 空行）：$first / $second", second.top - first.bottom >= 2f)
        assertTrue(
            "第 2 行不能低于条底（会被裁）：${second.bottom} vs ${col.bottom}",
            second.bottom <= col.bottom - 1
        )
        // 行距＝一枚徽标 58px ＋ 官方那 12px 间隙＝70px（基准图实测 1384→1454 正好 70px）：
        // 不管线路号是几位数，两行都不会挤到一起。
        assertTrue(
            "两行行距应正好「58px 色块 + 12px 间隙」＝70px：$first / $second",
            second.top - first.top in 69f..71f
        )
        val nameBottom = shot.nameInkBottom(col)
        assertTrue("第 1 行必须与站名最后一字分开（≥2dp）：$nameBottom / $first", first.top - nameBottom >= 6f)
    }

    // ---- 测试 3：格宽/站心逐像素不变（与改动前＝去掉 transferTargets 的同一条线比） -----

    @Test
    fun stripTransferKeepsCellWidthAndStationCenters() {
        mount(dark = false, names = viewport1)
        val before = capture(line1.withoutTransfers(), luocuo)
        val withTransfer = capture(line1, luocuo)

        // ① 每一列的上沿/左沿/宽度逐像素相同 → 格宽 W、站心 x=(i+0.5)·W、选中环位置、
        //    滚动锚点、命中区上沿都没动（徽标只往下长，所以只比 top/left/width，不比 bottom）。
        for (name in viewport1) {
            assertEquals(
                "$name 列的上沿不能变（车标 lane / 主线 / 站名起点都由它定）",
                before.cols.getValue(name).top, withTransfer.cols.getValue(name).top
            )
            assertEquals(
                "$name 列的左沿不能变（站心 x=(i+0.5)·W）",
                before.cols.getValue(name).left, withTransfer.cols.getValue(name).left
            )
            assertEquals(
                "$name 列的宽度不能变（格宽 W 由站名竖排宽度决定，与换乘徽标无关）",
                before.cols.getValue(name).width, withTransfer.cols.getValue(name).width
            )
        }

        // ② 主线 + 选中环 + 箭头 + 站名上半段所在的整条横带上逐像素一致。
        //    右端点取「本屏最先出现的换乘标注上沿」：吕厝（选中，2 字）在 315px，
        //    湖滨东路（4 字）在 428px，所以这条带里不可能有标注。
        val labelTop = firstTransferTopPx("吕厝" to true, "湖滨东路" to false)
        val diff = withTransfer.diffBand(before, 0, labelTop - 2)
        assertEquals("换乘标注不能挪动任何横向几何，差异像素 $diff", 0, diff)
    }

    // ---- 测试 4：需要时才加高，且只往下长 ---------------------------------------------

    @Test
    fun stripGrowsDownwardOnlyWhenTransfersNeedRoom() {
        mount(dark = false, names = viewport6)
        val before = capture(line6.withoutTransfers(), malian)
        val withTransfer = capture(line6, malian)

        // 6 号线最长站名就是 4 个字，马銮中心的换乘徽标只能靠加高整条位置条放下。
        // 站列底边就是位置条底边（height = hitTop + hitHeight），整帧高是屏幕高，不能拿来做差。
        // 加高量＝「4dp 间距 + 2 枚徽标（本线 + 换乘）」＝ 4 + 2×19.33 + 4 = 46.66dp ≈ 140px。
        val growth = (withTransfer.cols.getValue("马銮中心").bottom - before.cols.getValue("马銮中心").bottom)
        assertTrue("换乘徽标应把位置条加高，实际 $growth", growth > 0)
        assertTrue("加高只该是「4dp + 两枚 19.33dp 徽标 + 4dp 间隙」≈ 140px，实际 ${growth}px", growth in 138f..142f)

        // 站名以上的所有东西（车标、主线、选中环、站名）一个像素都不许动。
        // 右端点取本屏最先出现的换乘徽标上沿再让 2px。
        val labelTop = firstTransferTopPx("鼎美" to false, "马銮中心" to true)
        assertEquals("加高只能往下长，不能往上长", 0, withTransfer.diffBand(before, 0, labelTop - 2))

        // 站列宽度不变：加高不换宽。
        for (name in viewport6) {
            assertEquals("$name 列宽度不能变", before.cols.getValue(name).width, withTransfer.cols.getValue(name).width)
            assertEquals("$name 列左沿不能变", before.cols.getValue(name).left, withTransfer.cols.getValue(name).left)
        }
    }

    @Test
    fun stripTransferInDarkIsNotClippedAndKeepsLineHue() {
        mount(dark = true, names = viewport6)
        val dark = capture(line6, malian, dark = true)
        val col = dark.cols.getValue("马銮中心")
        // 深色下徽标底色仍是 mL 官方线路色原值（不改色），只是外圈补 0.6dp 描边提轮廓；
        // 所以按 2 号线绿的**原色**找得到那一枚。
        val bands = dark.bandsOf(col, hex(line2Color), from = dark.trackBottom)
        assertEquals("马銮中心深色下应有 1 枚 2 号线徽标，实际 $bands", 1, bands.size)
        val band = bands.single()
        assertTrue("深色下标注也不能被裁：${band.bottom} vs ${col.bottom}", band.bottom <= col.bottom - 1)
        assertTrue("深色下标注也要与站名分开：$band", band.top - dark.nameInkBottom(col) >= 6f)
    }

    // ---- 测试 5：徽标字色（一律白，官方实测） -------------------------------------------

    /**
     * 2026-11-08 改判：徽标字色**一律白**，不再按底色亮度切深墨。
     *
     * 依据是官方基准图 `notes/refs/official-line1-train-position.jpg` 的逐像素统计：
     * 1/2/3 号线徽标（`#ED7101` / `#92D085` / `#95D9E4`）的字色主色都是 (240,240,240)＝白，
     * **连浅绿、浅青的徽标官方也用白字**，并不存在「对比度不足就换深墨」这回事。
     * 底色继续用 `metro.json` 的官方线网图色（用户 2026-11-08 明确要求「线路色用原来的官方色」）。
     *
     * 共享徽标组件把字色写死成白（[LineBadgeInk]），徽标内部不再调用 [onLineColor]；
     * 亮度阈值那套逻辑只留给「浅色线路色上写普通文字」这类非徽标场景。
     */
    @Test
    fun badgeInkIsAlwaysWhiteOnEveryLineColor() {
        assertEquals("徽标字色写死为白", androidx.compose.ui.graphics.Color.White, LineBadgeInk)
        for (line in lines) {
            val fill = hex(line.color)
            // 徽标内部用的就是这一个白字，不再按亮度分叉：1/2/3 号线原来会走深墨 #1A1A1A。
            assertEquals(
                "${line.id} 号线徽标字色应是白",
                androidx.compose.ui.graphics.Color.White,
                LineBadgeInk
            )
            if (fill.luminance() >= .20f) {
                assertTrue(
                    "${line.id} 号线底色亮度高，旧规则会给深墨——徽标必须仍走白",
                    onLineColor(fill) != LineBadgeInk
                )
            }
            // 底色一个字节都没动：仍是 metro.json 的官方线网图色。
            assertEquals(
                "${line.id} 号线底色应仍是 metro.json 的官方色",
                fill,
                hex(lines.first { it.id == line.id }.color)
            )
        }
    }

    // ---- 测试 6：无障碍 + 点标注仍然是切这一列 ---------------------------------------

    @Test
    fun stripTransferIsNotClickableButColumnIs() {
        val picks = mutableListOf<String>()
        mount(dark = false, names = viewport1, onSelect = { picks += it })
        val shot = capture(line1, luocuo)

        // 整列一句话读出来：站名 + 可换乘哪条线；换乘标注自己不生成节点（读不到第二遍）。
        compose.onNodeWithContentDescription("吕厝，可换乘 2号线").assertExists()
        compose.onNodeWithContentDescription("文灶").assertExists()
        val mentions = compose.onAllNodesWithContentDescription("2号线", substring = true).fetchSemanticsNodes()
        assertEquals("只有吕厝那一列的整句话提到 2号线，标注本身不是独立节点", 1, mentions.size)

        // 点在**标注所在的位置**（列底部）：语义仍然是「切这一列」，不是点标注本身。
        val col = shot.cols.getValue("吕厝")
        val band = shot.bandsOf(col, hex(line2Color), from = shot.trackBottom).single()
        compose.onNodeWithContentDescription("吕厝，可换乘 2号线").performTouchInput {
            click(Offset(width / 2f, (band.top + band.bottom) / 2f - col.top))
        }
        compose.waitForIdle()
        assertEquals("点标注落点也只是把这一列交出去", listOf(luocuo), picks)
    }

    // ---- 像素工具 ---------------------------------------------------------------------

    /** 一张渲染结果：像素数组 + 各站整列的 bounds（同一份渲染里量的，跨渲染比较才有意义）。 */
    private inner class Shot(val bitmap: Bitmap, val cols: Map<String, Rect>, val palette: OfficialPalette) {
        val width = bitmap.width
        val height = bitmap.height
        private val px = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

        private fun at(x: Int, y: Int): Int = px[y * width + x]

        /** 一行里有多少像素「像」这团墨（逐通道容差，抗锯齿像素也覆盖得到）。 */
        private fun hits(x0: Int, x1: Int, y: Int, ink: Int): Int {
            var n = 0
            for (x in x0..x1) if (channelDistance(at(x, y), ink) <= 40) n++
            return n
        }

        /** 该列 [from] 行以下命中 ink 的连续行区间（≥2 像素才算一行有字，滤掉单像素噪声）。 */
        fun bandsOf(col: Rect, ink: Color, from: Float = col.top): List<Rect> {
            val x0 = col.left.toInt().coerceAtLeast(0)
            val x1 = col.right.toInt().coerceAtMost(width - 1)
            val yStart = from.toInt().coerceIn(0, height - 1)
            val yEnd = col.bottom.toInt().coerceAtMost(height - 1)
            val target = ink.toArgb()
            val out = mutableListOf<Rect>()
            var start = -1
            for (y in yStart..yEnd) {
                val hit = hits(x0, x1, y, target) >= 2
                if (hit && start < 0) start = y
                if (!hit && start >= 0) {
                    out += Rect(x0.toFloat(), start.toFloat(), x1.toFloat(), (y - 1).toFloat())
                    start = -1
                }
            }
            if (start >= 0) out += Rect(x0.toFloat(), start.toFloat(), x1.toFloat(), yEnd.toFloat())
            // 1px 高的「命中」是抗锯齿边缘（主线上下沿、圆环边）在容差内蹭到的，不算一行字。
            return out.filter { it.height >= 2f }
        }

        /**
         * 主线（含箭头、选中环）的下沿：换乘标注不可能出现在这条线以上，
         * 而主线本身是线路色，按线路色找字必须从这里往下扫，否则量到的是轨道。
         */
        val trackBottom: Float = (47.5f + StripTrack.value / 2f) * 3f

        /**
         * 该列里站名最后一字的墨迹底：站名只用 [OfficialPalette.SubInk]（未选中）或
         * [OfficialPalette.StationSelected]（选中站）。容差取 20（比找线路色更紧）——
         * 深色下换乘标注的绿色 `#8EC484` 与 SubInk `#9AA3AE` 只差 42，放 40 会把标注自己量成站名。
         */
        fun nameInkBottom(col: Rect): Float {
            val inks = listOf(palette.SubInk.toArgb(), palette.StationSelected.toArgb())
            val x0 = col.left.toInt().coerceAtLeast(0)
            val x1 = col.right.toInt().coerceAtMost(width - 1)
            val yEnd = col.bottom.toInt().coerceAtMost(height - 1)
            for (y in yEnd downTo col.top.toInt().coerceAtLeast(0)) {
                for (x in x0..x1) if (inks.any { channelDistance(at(x, y), it) <= 20 }) return y.toFloat()
            }
            return col.top
        }

        /** 两张图在 [from, to) 行内差异像素数（宽高取小者）。 */
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