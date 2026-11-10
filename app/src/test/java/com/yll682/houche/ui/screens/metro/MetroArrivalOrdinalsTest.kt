package com.yll682.houche.ui.screens.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.City
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroFixtures
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroObservationSource
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRealtimeRemote
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.mergeMetroRealtime
import com.yll682.houche.core.minutesUntil
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.HomeTab
import com.yll682.houche.ui.screens.home.HomeContent
import com.yll682.houche.ui.screens.home.HomeData
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「班号改成到本站先后」的四条硬断言（对应任务书那四条，全部真跑）：
 *
 * 1. 最先到本站那班 = 第 1 班，往后连续递增，**中间不跳号**；
 * 2. 超过 3 班时（第 4、5 班）照样编号，**不截断到 3**；
 * 3. 已过本站的车（`p > r`）**不占班号**：标签写「已过」，车标仍然画出来；
 * 4. ETA 第 k 行与位置条第 k 班是**同一辆车**（同一 fixture 双向校验）。
 *
 * 这里调的是 UI 侧的 `arrivalOrdinals`（到本站先后）。`core` 的 `stationOrdinals` 是另一套
 * 全线进度序口径（已过站也给编号），两者名字不同、互不遮蔽；位置条与 ETA 只用前者。
 *
 * 第 1、3、4 条用官方真实响应：`analysis/fixtures/metro-round2/` 的 095729 那一对
 * （行政中心 02100000 + 马青路 02090000，2026-11-08 09:57 抓包），走生产同一条
 * `parse` + `mergeMetroRealtime` 路径，不手抄任何站码/秒数。
 *
 * 真实样本里每个参考站只回 3 行，两份合并后一个方向最多 4 条位置记录，所以第 2 条
 * （≥5 班）只能在 2 号线真实站序上**构造** 5 条未到站记录；站码与站名仍全部取自
 * `assets/metro.json`（不手抄），只是把「有几班」改成任务书要求的 5 班。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class MetroArrivalOrdinalsTest {
    @get:Rule val compose = createComposeRule()

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<MetroNetwork>(text).openedLines
    }
    private val line2: MetroLine by lazy { lines.first { it.id == "2" } }

    /** 天竺山 → 五缘湾（`metro.json` 的站序就是行驶顺序）。 */
    private val codes: List<String> by lazy { line2.liveStations.map { it.code } }
    private val names: Map<String, String> by lazy { line2.liveStations.associate { it.code to it.name } }

    /** 真实批次：行政中心 + 马青路两份官方响应按生产路径合并。 */
    private val realBatch: MetroRealtime by lazy {
        fun parse(name: String, station: String) =
            MetroRealtimeRemote().parse(MetroFixtures.file("metro-round2/$name").readText(), "02000000", station)
        mergeMetroRealtime(
            listOf(parse("rt-02000000-095729.json", "02100000"), parse("rt-02000000-095729-mq.json", "02090000")),
            "02100000", "02000000"
        )
    }

    /** 该批次里「五缘湾 → 天竺山」那个方向（站点名在 `metro.json` 里是反序的）。 */
    private val realData: MetroData by lazy {
        MetroData(
            lines = lines, selected = "2", updated = "2026-11-08",
            realtime = realBatch, realtimeLastSuccessfulFrame = realBatch,
            realtimeStation = "02100000", realtimeStationName = names.getValue("02100000"),
            realtimeUpdated = "09:57",
            directionIndex = realBatch.directions.indexOfFirst { it.stationCodes.first() == "02320000" }
        )
    }

    private fun Metro(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) {
                    HomeContent(
                        state = UiState.Success(HomeData(hasMetro = true)),
                        city = City("11", "厦门市"), granted = false,
                        segment = HomeTab.METRO, selectSegment = {},
                        locate = {}, search = {}, cities = {},
                        line = { _, _, _ -> }, station = { _, _ -> },
                        metroContent = content
                    )
                }
            }
        }
    }

    private fun marker(label: String) = compose.onAllNodesWithContentDescription(label).onFirst()

    /**
     * 「同一辆车」的判据：接口没有车辆 ID，合并后的位置记录会带上 `matchedObservations`
     * （近似跨参考站关联），所以不能拿 data class 相等当身份——按观测身份三要素比。
     */
    private fun sameTrain(row: MetroTrain, position: MetroTrain): Boolean =
        (row.nextStationCode == position.nextStationCode &&
            row.arriveSeconds == position.arriveSeconds &&
            row.arrivingFlag == position.arrivingFlag &&
            row.source == position.source) || position.matchedObservations.any { observation ->
            row.nextStationCode == observation.nextStationCode.ifBlank { position.nextStationCode } &&
                row.arriveSeconds == observation.arriveSeconds && row.scale == observation.scale &&
                row.arrivingFlag == observation.arrivingFlag && row.source == observation.source
        }

    private fun bounds(label: String) = marker(label).fetchSemanticsNode().boundsInRoot

    // ---- ① 最先到本站那班 = 第 1 班，往后连续递增，不跳号 ----

    @Test
    fun nearestArrivalIsClassOneWithoutGaps() {
        val direction = requireNotNull(realData.direction)
        val reference = "02100000"
        val r = direction.stationCodes.indexOf(reference)
        val ordinals = direction.arrivalOrdinals(reference)

        assertEquals("参考站下标必须在该方向站序里", r, ordinals.referenceIndex)
        // 真实数据里马青路那份的区间车下一站是马青路（flag=1），真实 scale=.30，坐标落在本站下游 .30 站：
        // 它已过本站；进度连续变化，不能再以旧半格规则判断。
        val passed = ordinals.trains.single { it.passed }
        assertEquals(r + 0.3, passed.stationPosition, 1e-9)

        // 用户拍板：已过本站的车不占号，只画车标 + 「已过」。
        assertNull("已过本站的车不许占班号", passed.ordinal)
        assertNull("已过本站的车没有本站 ETA 行", passed.presentation.arrivalRowOrder)

        val numbered = ordinals.trains.filter { !it.passed }
        assertEquals("第 1..3 班连续、不跳号", listOf(1, 2, 3), numbered.map { it.ordinal })
        assertEquals("已过站的车不再占用第 4 班", 3, ordinals.maxOrdinal)
        assertEquals("位置条上只有一班标「已过」", 1, ordinals.passedCount)
        // 第 1 班 = 未到站里坐标最大的那班（最先到本站）。
        assertEquals(numbered.maxOf { it.stationPosition }, numbered.single { it.ordinal == 1 }.stationPosition, 0.0)
        // 它同时就是本站原始到站行的第 1 行那辆车。
        assertTrue(
            "第 1 班必须是本站到站行第 1 行那辆车，实际 ${numbered.single { it.ordinal == 1 }.presentation.train}",
            sameTrain(direction.trains.first(), numbered.single { it.ordinal == 1 }.presentation.train)
        )
        // 车标动画身份仍用 core 的全线进度序（只做 compose key，不显示）。
        assertEquals("车标 key 仍取 core 的全线进度序", 1, numbered.single { it.ordinal == 1 }.markerKey)

        Metro { MetroContent(UiState.Success(realData), {}) }
        compose.waitForIdle()

        // 位置条：第 1 班离本站最近（同一 anchor 下 x 最大），第 2 班在它左边，班号不许跳。
        val first = bounds("第1班")
        marker("第1班").assertIsDisplayed()
        marker("第2班").assertExists()
        marker("第3班").assertExists()
        // 已过站那班：标签是「已过」，且车标照画在这条线的下游（第 1 班右侧）。
        val passedBounds = bounds("已过")
        assertTrue("已过站的车标应在第 1 班右侧（下游）", passedBounds.left > first.right - 1f)
        marker("第4班").assertDoesNotExist()
    }

    // ---- ② 5 班要能编到第 5 班（不截断到 3） ----

    /**
     * 2 号线真实站序上的 5 班：本站「海沧行政中心」上游 5 班（每班至少相隔一站），
     * 再加一班刚开走（本站下游半站）的区间车。站码站名全部来自 `metro.json`。
     */
    private fun fiveClassDirection(): MetroDirection {
        val reference = "02100000"
        val r = codes.indexOf(reference)
        val source = MetroObservationSource(reference)
        fun at(seconds: Int, nextIndex: Int, flag: Int) = MetroTrain(
            arriveSeconds = seconds, nextStationCode = codes[nextIndex],
            nextStationName = names.getValue(codes[nextIndex]), scale = if (flag == 2) 1.0 else 0.5, arrivingFlag = flag,
            specialPosition = 0, specialPositionDesc = "", source = source
        )
        // 未到站 5 班，坐标与到站先后同向（越近秒数越小）：
        // p = r、r-1、r-2、r-3、r-4，避免旧演示把车堆在半站内。
        val upcoming = listOf(
            at(120, r, 2),
            at(240, r - 1, 2),
            at(360, r - 2, 2),
            at(480, r - 3, 2),
            at(600, r - 4, 2)
        )
        // 已开走的补充身份 p = r+1.5，与本站车保持足够间隔。
        val passed = at(60, r + 2, 1)
        return MetroDirection(
            origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
            startTime = "06:00", endTime = "23:00", delayMinutes = 0,
            trains = upcoming.take(3), stationCodes = codes, source = source,
            positionTrains = listOf(passed) + upcoming, arrivalsAvailable = true
        )
    }

    @Test
    fun fifthClassIsNumberedNotTruncated() {
        val direction = fiveClassDirection()
        val ordinals = direction.arrivalOrdinals("02100000")
        // 已过站那班不占号（null 在列表最前面，因为它坐标最大），其余 5 班连续编号 1..5。
        assertEquals(listOf(null, 1, 2, 3, 4, 5), ordinals.trains.map { it.ordinal })
        assertEquals(5, ordinals.maxOrdinal)
        assertEquals(1, ordinals.passedCount)
        assertTrue("已过站那班仍然在车标列表里", ordinals.trains.any { it.passed })

        val frame = MetroRealtime(
            online = true, note = "", lineCode = "02000000",
            stationCode = "02100000", stationName = names.getValue("02100000"),
            directions = listOf(direction), source = direction.source
        )
        val data = MetroData(
            lines = lines, selected = "2", updated = "2026-11-08",
            realtime = frame, realtimeLastSuccessfulFrame = frame,
            realtimeStation = "02100000", realtimeStationName = names.getValue("02100000"),
            realtimeUpdated = "09:57", directionIndex = 0
        )
        assertTrue("三行到站行仍在", data.etaState is MetroEtaState.Ready)

        Metro { MetroContent(UiState.Success(data), {}) }
        compose.waitForIdle()

        // 五个未到站的身份各自编号，不截断到三班；其中 1..3 对应本站 ETA。
        for (n in 1..5) marker("第${n}班").assertIsDisplayed()
        // 已过本站那班只标「已过」，不占号：第 6 班不存在。
        marker("已过").assertExists()
        marker("第6班").assertDoesNotExist()
        val lefts = (1..5).map { bounds("第${it}班").left }
        assertTrue("班号必须随到站先后从右往左递增，实际 $lefts", lefts.zipWithNext().all { (near, far) -> near > far })
        // 行序与班号同向：第 k 行就是第 k 班。
        assertEquals(listOf(1, 2, 3), (1..3).map { ordinals.ordinalOfRow(it) })
    }

    // ---- ③ 已过本站的车使用补充编号，车标仍保留 ----

    @Test
    fun passedTrainStillDrawsItsMarker() {
        val direction = requireNotNull(realData.direction)
        val ordinals = direction.arrivalOrdinals("02100000")
        // 本批四个身份位置均满足站距约束：本站三班 + 一个「已过」车标。
        val drawn = ordinals.trains.size
        assertEquals("每条位置记录都要有车标", direction.positions.size, drawn)
        assertEquals("三个班号加一个「已过」", 3, ordinals.maxOrdinal)
        assertEquals(1, ordinals.passedCount)

        Metro { MetroContent(UiState.Success(realData), {}) }
        compose.waitForIdle()

        val passedNodes = compose.onAllNodesWithContentDescription("已过").fetchSemanticsNodes()
        assertEquals("已过站的车标有且只有一个", 1, passedNodes.size)
        marker("已过").assertIsDisplayed()
        // 「已过」标签下方是方块车标本身：方块图标没被丢，标签才可能有左边这一块的墨迹。
        val box = marker("已过").fetchSemanticsNode().boundsInRoot
        assertTrue("车标盒宽度应是完整的 ${StripMarkerBox}dp", box.width > 0f && box.height > 0f)
        // 班号一个不少：第 1..3 班都在（位置条没有因为多一个已过车标而少画）；
        // 离本站太远的车标会被视口裁掉一截，所以只断言「存在」（标签没消失），不要求整块可见。
        marker("第1班").assertIsDisplayed()
        for (n in 2..3) marker("第${n}班").assertExists()

        // 2.0.x 车标使用当前线路纯色及白色列车图形；只统计语义节点顶部的图标区域。
        val bitmap = screenshot()
        val passedInk = lineColorPixels(bitmap, box)
        val leadInk = lineColorPixels(bitmap, bounds("第1班"))
        assertTrue("已过车标必须画出二号线色块，实际像素 $passedInk", passedInk > 300)
        assertTrue("第 1 班车标必须画出二号线色块，实际像素 $leadInk", leadInk > 300)
        assertTrue("已过车标的色块内必须保留白色列车图形", trainGlyphPixels(bitmap, box) > 80)
        assertTrue("第 1 班车标的色块内必须保留白色列车图形", trainGlyphPixels(bitmap, bounds("第1班")) > 80)
        val ratio = passedInk.toFloat() / leadInk.toFloat()
        assertTrue("已过车标的图标应与第 1 班那枚一样大，实际比例 $ratio", ratio in .6f..1.4f)
    }

    // ---- ④ ETA 第 k 行与位置条第 k 班是同一辆车 ----

    @Test
    fun etaRowAndClassAreTheSameTrain() {
        val direction = requireNotNull(realData.direction)
        val ordinals = direction.arrivalOrdinals("02100000")
        assertTrue("真实批次必须已取到本站到站行", realData.etaState is MetroEtaState.Ready)
        assertEquals(3, direction.trains.size)

        // 双向校验：第 k 行 → 第 k 班；第 k 班 → 第 k 行；两边的 MetroTrain 是同一个对象。
        for (k in 1..3) {
            assertEquals("第 $k 行必须落在第 $k 班", k, ordinals.ordinalOfRow(k))
            val row = direction.trains[k - 1]
            val byClass = ordinals.trains.single { it.ordinal == k }
            assertEquals("第 $k 班的 ETA 行序必须是 $k", k, byClass.presentation.arrivalRowOrder)
            assertTrue(
                "第 $k 行与第 $k 班必须是同一辆车，行=${row.nextStationCode}/${row.arriveSeconds} " +
                    "班=${byClass.presentation.train.nextStationCode}/${byClass.presentation.train.arriveSeconds}",
                sameTrain(row, byClass.presentation.train)
            )
            // 行序（ETA 从小到大）与坐标（从近到远）同向，不许反。
            assertTrue(
                "第 $k 班必须比自己后面那班更近本站",
                k == 3 || byClass.stationPosition > ordinals.trains.single { it.ordinal == k + 1 }.stationPosition
            )
        }
        // 已过站那班没有本站 ETA 行，不能把它的秒数塞进本站框。
        val passed = ordinals.trains.single { it.passed }
        assertNull("已过站的车不许有本站行序", passed.presentation.arrivalRowOrder)
        assertNull(ordinals.ordinalOfRow(4))

        Metro { MetroContent(UiState.Success(realData), {}) }
        compose.waitForIdle()

        // UI 侧同一件事：ETA 第 k 格的班号 = 位置条第 k 班，且分钟数来自本站第 k 行。
        for (k in 1..3) {
            val minutes = minutesUntil(direction.trains[k - 1].arriveSeconds)
            compose.onNodeWithContentDescription("$minutes 分钟第${k}班预计到达").assertIsDisplayed()
        }
        // 一个都没退化成「预计到达」。
        val degraded = compose.onAllNodesWithContentDescription("预计到达").fetchSemanticsNodes()
        assertEquals("除了 ETA 容器标题，不该再有裸「预计到达」", 1, degraded.size)
        assertNotNull(compose.onNodeWithContentDescription("预计到达"))
    }

    // ---- 截图与取色工具（与 MetroStripInteractionTest / ScreenshotTest 同一套做法） ----

    private fun screenshot(): Bitmap {
        val node = compose.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    /** 车标语义节点上半部的线路色像素，排除下方标签及更低处的轨道。 */
    private fun lineColorPixels(bitmap: Bitmap, bounds: androidx.compose.ui.geometry.Rect): Int {
        var count = 0
        val expected = android.graphics.Color.parseColor(line2.color)
        val left = bounds.left.toInt().coerceAtLeast(0)
        val right = (bounds.left + bounds.width).toInt().coerceAtMost(bitmap.width)
        val top = bounds.top.toInt().coerceAtLeast(0)
        // 图标在车标盒顶部 20.7dp 内；标签文字在下方，取上半段避免把「已过」的灰字算进来。
        val bottom = (bounds.top + bounds.height / 2f).toInt().coerceAtMost(bitmap.height)
        for (y in top until bottom) {
            for (x in left until right) {
                val p = bitmap.getPixel(x, y)
                val red = (p shr 16) and 255
                val green = (p shr 8) and 255
                val blue = p and 255
                if (kotlin.math.abs(red - android.graphics.Color.red(expected)) <= 10 &&
                    kotlin.math.abs(green - android.graphics.Color.green(expected)) <= 10 &&
                    kotlin.math.abs(blue - android.graphics.Color.blue(expected)) <= 10) count++
            }
        }
        return count
    }

    private fun trainGlyphPixels(bitmap: Bitmap, bounds: androidx.compose.ui.geometry.Rect): Int {
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        val size = StripTrainSize.value * density
        val inset = 2 * density
        val left = (bounds.center.x - size / 2 + inset).toInt().coerceAtLeast(0)
        val right = (bounds.center.x + size / 2 - inset).toInt().coerceAtMost(bitmap.width)
        val top = (bounds.top + inset).toInt().coerceAtLeast(0)
        val bottom = (bounds.top + size - inset).toInt().coerceAtMost(bitmap.height)
        var white = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 230 &&
                android.graphics.Color.green(pixel) > 230 &&
                android.graphics.Color.blue(pixel) > 230) white++
        }
        return white
    }
}
