package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroObservationSource
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.ui.UiState
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

/**
 * 第四轮 UI 接线的硬断言（不靠肉眼看图），逐条对上用户那几句话：
 *
 * 1. 切站方向不变 —— 切站后方向站码链、起终点、位置批次都不变；
 * 2. 切站瞬间 ETA 容器仍在 —— 蓝块不消失、位置与尺寸逐像素不变，只换成「正在获取」；
 *    并且 Empty（确实没车）与 Unavailable（失败/本站没采集）不能显示成同一句；
 * 3. 同线换站 W 不变 —— 站点列宽与相邻列间距逐像素相同（整线测一次的格宽）；
 * 4. 三条同位车能错层 —— 同一 cluster 的三条记录同 x、不同 lane，间距＝一个 lane 高；
 * 5. ETA 行的「第N班」改成**到本站先后** —— 已过本站的补车不占班号（标「已过」、车标照画），
 *    本站第 k 行与位置条第 k 班是同一辆车（旧规则里它会把本站第 1 行顶成「第 2 班」）。
 *
 * 站码、站名全部来自 `assets/metro.json`（2 号线），不手抄；车辆与分钟是构造数据。
 * 车辆位置放在参考站**上游**（画在锚点左侧），保证断言用的节点在视口内。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroEtaStripWireTest {
    @get:Rule val compose = createComposeRule()

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<MetroNetwork>(text).openedLines
    }
    private val line2: MetroLine by lazy { lines.first { it.id == "2" } }
    private val codes: List<String> by lazy { line2.liveStations.map { it.code } }
    private val names: Map<String, String> by lazy { line2.liveStations.associate { it.code to it.name } }

    /** 参考站取 2 号线中段的「海沧行政中心」，两边都有邻居，切站后站位仍然画得出来。 */
    private val referenceIndex = 9
    private val reference: String by lazy { codes[referenceIndex] }
    private val density: Float by lazy {
        ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
    }

    private fun source(code: String) = MetroObservationSource(code)

    private fun train(seconds: Int, nextIndex: Int, flag: Int, from: String = reference): MetroTrain {
        val next = codes[nextIndex]
        return MetroTrain(
            arriveSeconds = seconds, nextStationCode = next, nextStationName = names.getValue(next),
            scale = 0.0, arrivingFlag = flag, specialPosition = 0, specialPositionDesc = "",
            source = source(from)
        )
    }

    private fun direction(rows: List<MetroTrain>, positions: List<MetroTrain> = rows): MetroDirection = MetroDirection(
        origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
        startTime = "06:00", endTime = "23:00", delayMinutes = 0,
        trains = rows, stationCodes = codes, source = source(reference), positionTrains = positions,
        arrivalsAvailable = true
    )

    /**
     * 本站两条原始到站行，位置都在参考站**上游**（画在锚点左侧，视口内看得见）：
     * 第一条就在参考站前一站，第二条再往前 1.5 站。
     */
    private fun rows(): List<MetroTrain> = listOf(
        train(300, referenceIndex - 1, flag = 2),
        train(600, referenceIndex - 3, flag = 1)
    )

    private fun seed(
        station: String = reference,
        rows: List<MetroTrain> = rows(),
        positions: List<MetroTrain> = rows,
        directionIndex: Int = 0
    ): MetroData {
        val forward = direction(rows, positions)
        // 反方向只是为了 canSwap / 换向测试存在，到站行是空的（arrivalsAvailable = false）。
        val backward = MetroDirection(
            origin = forward.destination, destination = forward.origin,
            startTime = forward.startTime, endTime = forward.endTime, delayMinutes = 0,
            trains = emptyList(), stationCodes = codes.reversed(), source = source(reference),
            positionTrains = emptyList(), arrivalsAvailable = false
        )
        val frame = MetroRealtime(
            online = true, note = "", lineCode = "02000000",
            stationCode = station, stationName = names.getValue(station),
            directions = listOf(forward, backward), source = source(station), sources = listOf(source(station))
        )
        return MetroData(
            lines = lines, selected = "2", updated = "2026-11-08",
            realtime = frame, realtimeLastSuccessfulFrame = frame,
            realtimeStation = station, realtimeStationName = names.getValue(station),
            realtimeUpdated = "09:57", directionIndex = directionIndex
        )
    }

    private fun Metro(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** 位置条上的站点列（站名做 contentDescription）；滚出视口的列 bounds 为 0。 */
    private fun stripBounds(name: String) = compose.onAllNodesWithContentDescription(name).onFirst()
        .fetchSemanticsNode().boundsInRoot

    // ---- ① 切站方向不变 ----

    @Test
    fun switchStationKeepsDirection() {
        val seeded = seed()
        val before = requireNotNull(seeded.direction)
        assertTrue("起点应已是最新一次成功批次", seeded.etaState is MetroEtaState.Ready)

        val target = codes[referenceIndex - 3]
        val switched = seeded.selectRealtimeStation(target)
        val after = requireNotNull(switched.direction)

        assertEquals("切站后方向站码链必须逐项不变", before.stationCodes, after.stationCodes)
        assertEquals("起点不能退回线路首站", before.origin, after.origin)
        assertEquals("终点不能退回线路末站", before.destination, after.destination)
        assertEquals("上一批真实位置必须保留", before.positions, after.positions)
        assertEquals("新参考站的 ETA 只能进等待态", MetroEtaState.Loading(target), switched.etaState)
        // 换站后再换回原站，方向仍是同一条链（不是数组序号碰巧对齐）。
        assertEquals(
            before.stationCodes,
            requireNotNull(switched.selectRealtimeStation(reference).direction).stationCodes
        )
    }

    // ---- ② 切站瞬间 ETA 容器仍在 ----

    @Test
    fun etaContainerSurvivesStationSwitch() {
        var data by mutableStateOf(seed())
        Metro { MetroContent(UiState.Success(data), {}, {}) }
        compose.waitForIdle()

        val ready = compose.onNodeWithContentDescription("预计到达").fetchSemanticsNode().boundsInRoot

        // 切到隔壁站：位置条按新参考站重新锚定，上一批车标必须还在原位（编号还在）。
        data = data.selectRealtimeStation(codes[referenceIndex - 1])
        compose.waitForIdle()

        // 容器还在，位置与尺寸逐像素不变；只是换成「正在获取」。
        val loading = compose.onNodeWithContentDescription("预计到达").fetchSemanticsNode().boundsInRoot
        assertEquals(ready.left, loading.left, 0f)
        assertEquals(ready.top, loading.top, 0f)
        assertEquals(ready.width, loading.width, 0f)
        assertEquals(ready.height, loading.height, 0f)
        compose.onNodeWithText("正在获取").assertIsDisplayed()
        // 位置条继续用上一批观测：车标不消失，编号还在。
        compose.onNodeWithContentDescription("第1班").assertIsDisplayed()
        assertTrue("切站不能清掉上一批位置", data.direction!!.positions.size == rows().size)
    }

    @Test
    fun etaPlaceholdersDistinguishEmptyFromUnavailable() {
        val seeded = seed()
        val forward = direction(rows = emptyList(), positions = rows())
        val directions = listOf(forward, seeded.realtime!!.directions[1])
        var data by mutableStateOf(
            seeded.copy(
                realtime = seeded.realtime!!.copy(directions = directions),
                realtimeLastSuccessfulFrame = seeded.realtimeLastSuccessfulFrame!!.copy(directions = directions)
            )
        )
        assertTrue("成功批次确实没有到站行才是 Empty", data.etaState is MetroEtaState.Empty)

        Metro { MetroContent(UiState.Success(data), {}, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("暂无到站列车").assertIsDisplayed()

        // 失败：失败原因进 Unavailable，不能显示成 Empty，也不冒用旧站 ETA。
        data = data.copy(realtimeUnavailable = true, realtimeNote = "超时", realtimeLoading = false)
        compose.waitForIdle()
        assertTrue(data.etaState is MetroEtaState.Unavailable)
        compose.onNodeWithText("本站到站暂不可用").assertIsDisplayed()
        compose.onNodeWithText("暂无到站列车").assertDoesNotExist()
    }

    // ---- ③ 同线换站 W 不变 ----

    @Test
    fun cellWidthUnchangedAcrossStationSwitch() {
        var data by mutableStateOf(seed())
        Metro { MetroContent(UiState.Success(data), {}, {}) }
        compose.waitForIdle()

        /** 锚点到相邻列左沿的距离＝站距；相邻列不可见时返回 -1。 */
        fun measureStep(current: MetroData, station: String): Float {
            val order = current.direction?.stationCodes.orEmpty()
            val index = order.indexOf(station)
            val anchor = stripBounds(names.getValue(station))
            val neighbour = listOfNotNull(order.getOrNull(index + 1), order.getOrNull(index - 1))
                .map { stripBounds(names.getValue(it)) }
                .firstOrNull { it.width > 0f }
                ?: return -1f
            return kotlin.math.abs(neighbour.left - anchor.left)
        }

        /**
         * 视口里完整可见的那些列的宽度最大值＝格宽 W。
         * 被视口边缘切掉一截的列会小 1~几十 px（这是裁剪，不是格宽），所以取最大值而不是取锚点列。
         */
        fun visibleCellWidth(): Float = codes.map { names.getValue(it) to stripBounds(names.getValue(it)) }
            .filter { it.second.width > 0f }
            .maxOf { it.second.width }

        val width = visibleCellWidth()
        val step = measureStep(data, reference)
        assertTrue("至少有一列被完整量到", width > 0f)
        assertTrue("相邻站距离应已量到", step > 0f)
        assertTrue("格宽至少是官方的 68.2dp，实际 ${width / density}dp", width >= 68.2f * density - 1f)
        // 站距＝一格；每列 offset 各自取整，相邻两列可能差 1px（204.6px 的累积取整），不是格宽变化。
        assertEquals("相邻站中心间距必须等于一格", width, step, 1f)

        // 同一条线依次换参考站（含线路两端：可见窗口里的站名长短差别最大），W 与站距逐像素不变。
        for (target in listOf(codes[referenceIndex - 1], codes.last(), codes.first(), codes[referenceIndex + 3])) {
            val switched = seed().selectRealtimeStation(target)
            data = switched
            compose.waitForIdle()
            assertEquals("换站到 ${names.getValue(target)} 后格宽不许变", width, visibleCellWidth(), 0f)
            assertEquals("换站到 ${names.getValue(target)} 后站距不许变", step, measureStep(switched, target), 1f)
        }

        // 反向（换向）同样不变：站表是同一个集合的排列。
        val reversed = seed().selectRealtimeDirection(1)
        data = reversed
        compose.waitForIdle()
        assertEquals("反向格宽不许变", width, visibleCellWidth(), 0f)
        assertEquals("反向站距不许变", step, measureStep(reversed, reference), 1f)

        // 位置批次换了（同一参考站重新采样）也不变。
        val resampled = seed(positions = listOf(train(120, referenceIndex - 1, flag = 2)))
        data = resampled
        compose.waitForIdle()
        assertEquals("换一批位置后格宽不许变", width, visibleCellWidth(), 0f)
    }

    // ---- ④ 三条同位车能错层 ----

    @Test
    fun threeCoLocatedTrainsTakeSeparateLanes() {
        // 三条记录坐标完全相同（同 nextStationCode、同 flag）：数据层不会删，只标潜在碰撞。
        val same = listOf(
            train(300, referenceIndex - 2, flag = 2),
            train(600, referenceIndex - 2, flag = 2),
            train(900, referenceIndex - 2, flag = 2)
        )
        val seeded = seed(rows = same, positions = same)
        val presentations = seeded.realtime!!.directions[0].positionPresentations()
        assertEquals(listOf(1, 2, 3), presentations.map { it.ordinal })
        assertEquals(listOf(0, 1, 2), presentations.map { it.stackIndex })
        assertEquals(listOf(3, 3, 3), presentations.map { it.stackSize })
        assertEquals("三条同位记录属于同一个干扰群", 1, presentations.mapNotNull { it.clusterId }.distinct().size)

        Metro { MetroContent(UiState.Success(seeded), {}, {}) }
        compose.waitForIdle()

        val marks = listOf("第1班", "第2班", "第3班").map { label ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        // 同 x：错层不许横向挪位置，也不许把车挪到别的站。
        assertEquals(marks[0].left, marks[1].left, 0f)
        assertEquals(marks[0].left, marks[2].left, 0f)
        // 不同 lane：lane 0 贴主线（第 1 班），往上依次是 lane 1、2；层高 44dp。
        val lane = 44f * density
        assertEquals("相邻两层应正好差一个 lane 高", lane, marks[0].top - marks[1].top, 1f)
        assertEquals("相邻两层应正好差一个 lane 高", lane, marks[1].top - marks[2].top, 1f)
        assertTrue("车标图标本身不许重叠", marks[1].bottom <= marks[0].top)
        assertTrue(marks[2].bottom <= marks[1].top)
        // 最上层仍在位置条内部（没有被 clipToBounds 裁掉）。
        assertTrue("最上层不能贴到 0 以外，实际 ${marks[2].top}", marks[2].top >= 0f)
    }

    // ---- ⑤ 已过本站的补车不占班号，ETA 行与位置条同向 ----

    @Test
    fun passedTrainKeepsMarkerWithoutStealingAClass() {
        val rowFirst = train(300, referenceIndex - 1, flag = 2)
        val rowSecond = train(600, referenceIndex - 3, flag = 1)
        // 另一参考站观测到的区间车：下一站是参考站的**下一站**（flag=1），也就是刚开走、已过本站。
        val passed = train(82, referenceIndex + 1, flag = 1, from = codes[referenceIndex + 1])
        val seeded = seed(rows = listOf(rowFirst, rowSecond), positions = listOf(passed, rowFirst, rowSecond))

        // core 的全线进度序把刚开走那班排第 1 —— 这正是用户报的错位来源；投影后它没有班号。
        val presentations = seeded.realtime!!.directions[0].positionPresentations()
        assertEquals(listOf(1, 2, 3), presentations.map { it.ordinal })
        assertEquals(listOf(null, 1, 2), presentations.map { it.arrivalRowOrder })
        val ordinals = seeded.realtime!!.directions[0].stationOrdinals(reference)
        assertTrue("已过本站的车不许占班号", ordinals.trains.first().passed)
        assertEquals("到本站先后编号不许被它顶掉", listOf(1, 2), ordinals.trains.drop(1).map { it.ordinal })
        assertTrue("本站两条原始行仍是 Ready", seeded.etaState is MetroEtaState.Ready)

        Metro { MetroContent(UiState.Success(seeded), {}, {}) }
        compose.waitForIdle()

        // 本站第 1 行（300s＝5 分钟）就是位置条第 1 班；第 2 行（10 分钟）是第 2 班。
        compose.onNodeWithContentDescription("5 分钟第1班预计到达").assertIsDisplayed()
        compose.onNodeWithContentDescription("10 分钟第2班预计到达").assertIsDisplayed()
        // 已过站的车标还在：标签换成「已过」，不是丢标签。
        compose.onNodeWithContentDescription("已过").assertIsDisplayed()
        // 补车的源站 ETA 不许进本站框。
        assertTrue(seeded.direction!!.trains.none { it.nextStationCode == passed.nextStationCode })
    }
}
