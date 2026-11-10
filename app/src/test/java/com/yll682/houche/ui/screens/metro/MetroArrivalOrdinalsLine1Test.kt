package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.MetroFixtures
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRealtimeRemote
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.mergeMetroRealtime
import com.yll682.houche.core.metroTransfers
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.HoucheShapes
import com.yll682.houche.ui.theme.HoucheTypography
import com.yll682.houche.ui.theme.LightColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 真实 1 号线 24 站那一轮（`app/src/test/resources/line1-multi/`，2026-11-08 17:32:30）上的
 * 「到本站先后」班号验收 —— 用户拍板的两条规则：
 *
 * - **第 1 班 = 最先到本站那班**，其余按 `p` 降序连续编号 1..N，中间不跳号；
 * - **已开过本站的车（`p > r`）不占号、标签写「已过」，但车标必须保留**。
 *
 * 三条硬断言（全部量化，不靠肉眼看图）：
 *
 * 1. 上游车号连续 1..N；下游车 `ordinal == null` → 全部「已过」；
 * 2. 车标总数不变：编号数 + 已过数 == `direction.positions.size`，渲染出的车标节点也是 11 个；
 * 3. ETA 第 k 行与位置条第 k 班是同一辆车，且第 k 行的秒数等于**明文 fixture** 里本站
 *    第 k 条到站记录（`transTimes` 升序），即「同一辆车」不是靠内部字段自证。
 *
 * 走生产同一条 `MetroRealtimeRemote.parse` + `mergeMetroRealtime` 路径：24 个站的成功响应
 * 合并成一个参考站（吕厝 `01080000`）的批次，不手抄任何站码/秒数。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi", application = android.app.Application::class)
class MetroArrivalOrdinalsLine1Test {
    @get:Rule val compose = createComposeRule()

    /** 固定时钟：24 个响应属于同一轮抓取，共用的只有服务端 `ts`。 */
    private val remote = MetroRealtimeRemote(elapsedRealtimeMillis = { 0L }, wallClockMillis = { 0L })

    /** 参考站「吕厝」。 */
    private val reference = "01080000"

    private val lines: List<MetroLine> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        metroTransfers(
            Json { ignoreUnknownKeys = true }.decodeFromString<MetroNetwork>(text).openedLines
        )
    }

    /** 17:32:30 那一轮：24 个站各一份原始响应（密文，走生产解析）。 */
    private val frame: MetroRealtime by lazy {
        val directory = requireNotNull(MetroFixtures.file("line1-multi/index.tsv").parentFile)
        val snapshots = directory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("rt-01000000-\\d{8}-173230\\.json")) }
            .sortedBy { it.name }
            .map { remote.parse(it.readText(), "01000000") }
        assertEquals("这一轮应有 24 个站的成功响应", 24, snapshots.size)
        mergeMetroRealtime(snapshots, reference, "01000000")
    }

    private val data: MetroData by lazy {
        val line1 = lines.first { it.id == "1" }
        MetroData(
            lines = lines, selected = "1", updated = "2026-11-08",
            realtime = frame, realtimeLastSuccessfulFrame = frame,
            realtimeStation = reference,
            realtimeStationName = line1.liveStations.firstOrNull { it.code == reference }?.name.orEmpty(),
            realtimeUpdated = "17:32", directionIndex = 0
        )
    }

    /** 本站明文响应（同一轮、同一站），用来独立核对 ETA 行的秒数。 */
    private val plainStationTimes: List<List<Int>> by lazy {
        val file = requireNotNull(MetroFixtures.file("line1-multi/rt-01000000-01080000-173230-plain.json"))
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(file.readText()).jsonObject
        root.getValue("data").jsonArray.map { entry ->
            entry.jsonObject.getValue("transTimes").jsonArray
                .map { it.jsonObject.getValue("arriveTime").jsonPrimitive.int }
                .sorted()
        }
    }

    /** 车标语义节点：「第N班」或「已过」；站名列的 contentDescription 是站名，不会误命中。 */
    private val markerNodes
        get() = compose.onAllNodes(
            SemanticsMatcher("车标") { node ->
                val labels = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                labels.size == 1 && (labels[0] == "已过" || labels[0].matches(Regex("第\\d+班")))
            }
        ).fetchSemanticsNodes()

    /** 同一辆车的判据：接口没有车辆 ID，合并后的记录带 `matchedObservations`，按观测三要素比。 */
    private fun sameTrain(row: MetroTrain, position: MetroTrain): Boolean =
        (row.nextStationCode == position.nextStationCode &&
            row.arriveSeconds == position.arriveSeconds &&
            row.arrivingFlag == position.arrivingFlag &&
            row.source == position.source) || position.matchedObservations.any { observation ->
            row.nextStationCode == observation.nextStationCode.ifBlank { position.nextStationCode } &&
                row.arriveSeconds == observation.arriveSeconds && row.scale == observation.scale &&
                row.arrivingFlag == observation.arrivingFlag && row.source == observation.source
        }

    // ---- ① 上游连续 1..N，下游全部「已过」（两个方向都验） ----

    @Test
    fun upstreamClassesAreContinuousAndEveryDownstreamTrainIsPassed() {
        assertEquals("两个方向各一份站序", 2, frame.directions.size)
        // 真实数据的量化基准：方向 0（吕厝在下标 7）4 班未到站 + 7 个已过；
        // 方向 1（下标 16）8 班 + 3 个已过。合计每方向 11 个车标。
        val expected = listOf(4 to 7, 8 to 3)
        frame.directions.forEachIndexed { index, direction ->
            val r = direction.stationCodes.indexOf(reference)
            val ordinals = direction.arrivalOrdinals(reference)
            assertEquals("方向 $index 的参考站下标", r, ordinals.referenceIndex)

            val upcoming = ordinals.trains.filter { !it.passed }
            val downstream = ordinals.trains.filter { it.passed }
            assertEquals("方向 $index：未到站 / 已过 的车标数", expected[index], upcoming.size to downstream.size)

            // 用户定的规则 1：其余按 p 降序连续编号 1..N，不跳号。
            assertEquals("方向 $index 的班号必须是 1..N 连续",
                (1..upcoming.size).toList(), upcoming.map { it.ordinal })
            assertEquals("方向 $index 的最大班号", upcoming.size, ordinals.maxOrdinal)
            // 越靠前（p 越大）的班号越小：p 降序与班号升序严格同向。
            assertTrue("方向 $index 的班号必须随 p 降序递增",
                upcoming.zipWithNext().all { (near, far) -> near.stationPosition > far.stationPosition })

            // 用户定的规则 2：p > r 的一律「已过」，一个班号都不许占。
            downstream.forEach { train ->
                assertNull("方向 $index：已过本站的车不许有班号（p=${train.stationPosition}）", train.ordinal)
                assertTrue("方向 $index：已过本站的车必须 p > r", train.stationPosition > r)
            }
            // 反向：没标「已过」的必须 p <= r（本站站台上的 flag=2 车 p == r，不算已过）。
            upcoming.forEach { train ->
                assertTrue("方向 $index：未过站的车不许 p > r（p=${train.stationPosition}）",
                    train.stationPosition <= r)
            }

            // 规则 2 的另一半：已过站的车标一个都不许丢 —— 它们仍在这份列表里。
            assertEquals("方向 $index：每条可见位置记录都要有车标（编号或「已过」）",
                direction.positions.size, ordinals.trains.size)
        }
    }

    // ---- ② 车标总数不变：屏幕上真的画了 11 个，其中 7 个写「已过」 ----

    @Test
    fun passedMarkersAreStillDrawnOnTheStrip() {
        val direction = requireNotNull(data.direction)
        val ordinals = direction.arrivalOrdinals(reference)
        val count = ordinals.trains.size
        assertEquals("这一轮方向 0 有 11 个车标", 11, count)
        assertEquals("修前 7 个已过的车标被编成第4–10班", 7, ordinals.passedCount)
        assertEquals("只有 4 个车标有班号", 4, count - ordinals.passedCount)

        render()

        val nodes = markerNodes
        assertEquals("车标总数不许因为没班号就减少", count, nodes.size)
        val labels = nodes.map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals("已过本站的车标逐个都画出来，标签写「已过」", 7, labels.count { it == "已过" })
        assertEquals("未到站的车标连续编号 1..4",
            listOf("第1班", "第2班", "第3班", "第4班"),
            labels.filter { it != "已过" }.sortedBy { it.drop(1).dropLast(1).toInt() })
        // 「已过」不会再被编进第 4–10 班：5 班以后的标签一个都不许出现。
        (5..10).forEach { n ->
            assertTrue("不许出现 第${n}班：$labels", labels.none { it == "第${n}班" })
        }
        // 已过车标不是「只剩一行小字」：每个车标盒都是完整的方块 + 标签。
        // 说明：视口（360dp ≈ 5.3 格）锚在吕厝，已过站的车标在下游、会被 clipToBounds 裁掉，
        // 所以用未裁剪的 layout 尺寸 `size` 判断，不用 `boundsInRoot`（会被裁成 0）。
        nodes.forEach { node ->
            val label = node.config[SemanticsProperties.ContentDescription].single()
            assertTrue("车标盒不能是空的：$label ${node.size}", node.size.width > 0 && node.size.height > 0)
        }
    }

    // ---- ③ ETA 第 k 行 == 位置条第 k 班（同一辆车），第1/2/3班 ----

    @Test
    fun etaRowKIsTheSameTrainAsClassK() {
        frame.directions.forEachIndexed { index, direction ->
            val ordinals = direction.arrivalOrdinals(reference)
            assertEquals("方向 $index：本站三个 ETA 行", 3, direction.trains.size)
            assertEquals("方向 $index：ETA 三格仍是 第1/2/3班",
                listOf(1, 2, 3), (1..3).map { ordinals.ordinalOfRow(it) })

            for (k in 1..3) {
                val row = direction.trains[k - 1]
                val byClass = ordinals.trains.single { it.ordinal == k }
                assertEquals("方向 $index 第 $k 班的 ETA 行序", k, byClass.presentation.arrivalRowOrder)
                assertTrue(
                    "方向 $index 第 $k 行与第 $k 班必须是同一辆车：" +
                        "行=${row.nextStationCode}/${row.arriveSeconds}，" +
                        "班=${byClass.presentation.train.nextStationCode}/${byClass.presentation.train.arriveSeconds}",
                    sameTrain(row, byClass.presentation.train)
                )
                // 明文 fixture 独立核对：第 k 行的秒数就是本站明文响应里第 k 条到站记录。
                assertEquals(
                    "方向 $index 第 $k 行的秒数必须与明文响应第 $k 条到站记录一致",
                    plainStationTimes[index][k - 1], row.arriveSeconds
                )
            }
        }

        render()
        val eta = compose.onAllNodes(
            SemanticsMatcher("ETA 格") { node ->
                val labels = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                labels.size == 1 && labels[0].endsWith("班预计到达")
            }
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals("ETA 必须有三格", 3, eta.size)
        assertTrue("ETA 三格只能出现 第1/2/3班，实际 $eta",
            eta.all { text -> Regex("^\\d+ 分钟第[123]班预计到达$").matches(text) })
        // ETA 与位置条讲同一件事：第 1 班那格的车标确实画在位置条上。
        compose.onNodeWithContentDescription("第1班").assertExists()
    }

    // ---- 渲染 ----

    private fun render() {
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
        compose.setContent {
            MaterialTheme(colorScheme = LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize()) { MetroContent(UiState.Success(data), {}) }
            }
        }
        compose.waitForIdle()
    }
}
