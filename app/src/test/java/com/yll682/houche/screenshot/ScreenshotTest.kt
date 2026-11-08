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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.AppGraph
import com.yll682.houche.PageEntry
import com.yll682.houche.Screen
import com.yll682.houche.model
import com.yll682.houche.core.*
import com.yll682.houche.data.Position
import com.yll682.houche.ui.UiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import com.yll682.houche.ui.screens.home.*
import com.yll682.houche.ui.screens.search.*
import com.yll682.houche.ui.screens.line.*
import com.yll682.houche.ui.screens.station.*
import com.yll682.houche.ui.screens.timetable.*
import com.yll682.houche.ui.screens.city.*
import com.yll682.houche.ui.screens.metro.*
import com.yll682.houche.ui.screens.settings.*
import com.yll682.houche.ui.screens.vehicle.*
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
    // 厦门真实的长站名：用来验证横向站点图 7~8 字也能竖排完整显示。
    private val longStops = listOf(
        RouteStation("长庚医院停车场", 19),
        RouteStation("石室禅院", 20),
        RouteStation("地铁马青路", 21),
        RouteStation("海沧房产", 22),
        RouteStation("海沧建行", 23),
        RouteStation("新阳西公交场站", 24),
        RouteStation("祥露公交场站", 25)
    )
    private val longRoute = Route("807路", "807", "1", "长庚医院停车场", "大屏山公交场站", "06:40", "20:00", "一票制1元", longStops, true)
    private val longStop = Station("天安门东（地铁站）", distance = 44.0, lines = listOf(LineSummary("地铁1号线八通线", "1", to = "环球度假区（地铁站）"), LineSummary("地铁1号线八通线", "2", to = "福寿岭（地铁站）")))
    private val live = VehicleResults(listOf(Vehicle("京A·01236", 1, "at-stop", currentStation = "四惠枢纽站", dataTime = "08:26:00"), Vehicle("京A·03591", 2, "between", currentStation = "八王坟西", dataTime = "08:26:00", segmentDistance = 320.0)), "车辆位置仅供参考，请提前候车", nextDeparture = "10:05")
    private val home = HomeData(stations = listOf(stop), arrivals = mapOf(0 to arrivals), position = Position(24.48, 118.13), cityResolved = true, favoriteLines = listOf(SavedLine(city, LineSummary("91路", "1", route.from, route.to))), favoriteStations = listOf(SavedStation(city, stop)))
    private val timetableData = TimetableData(
        Timetable(emptyList(), "本时刻表显示计划发车时间，受道路通行情况影响可能临时调整，仅供参考。"),
        listOf(
            TimeGroup("06:00–07:59", false, listOf("06:30", "06:45", "07:00", "07:15", "07:30", "07:45").map { Departure(it) }),
            TimeGroup("08:00–09:59", true, listOf("08:00", "08:13", "08:26", "08:40", "08:55", "09:11", "09:28", "09:44", "09:58").map { Departure(it, status = if (it == "08:26") 2 else null) })
        )
    )

    /** 设置现在是首页的第三个 Tab，截图也要连底部导航一起拍。 */

    /**
     * 地铁演示数据直接读 assets/metro.json，不再手抄：
     * 手抄版本曾漏掉 2/3 号线的站，搜索结果里就显示成「3 站」「2 站」。
     */
    /**
     * 走生产同一条路径：解析内置站表后再用核心的 `metroTransfers` 补上换乘目标
     * （`transferTargets` 是 transient，直接解码出来是空的），这样截图里的换乘徽标
     * 和真机上看到的是同一份数据。
     */
    private val metroLines: List<MetroLine> by lazy {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = context.assets.open("metro.json").bufferedReader().use { it.readText() }
        metroTransfers(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<MetroNetwork>(text).openedLines
        )
    }
    private val metroLine1: MetroLine get() = metroLines.first { it.id == "1" }
    private val metroLine2: MetroLine get() = metroLines.first { it.id == "2" }
    private val metroLine3: MetroLine get() = metroLines.first { it.id == "3" }
    private val metroLine4: MetroLine get() = metroLines.first { it.id == "4" }
    private val metroData: MetroData get() = MetroData(metroLines, "1", "2026-11-06")

    @Composable private fun MetroShot() = MetroContent(UiState.Success(metroData), {}, {})

    @Test fun metro_light() = capture("metro", false) { MetroShot() }
    @Test fun metro_dark() = capture("metro", true) { MetroShot() }

    /** 在线资料命中时头部多一个「在线」胶囊；探测失败时这张和上面完全一样。 */
    @Test fun metroOnline_light() = capture("metro-online", false) {
        MetroContent(UiState.Success(metroData.copy(online = true)), {}, {})
    }

    /** 实时到站用官方真实响应：一号线高崎站，两个方向各三班车，含列车位置示意。 */
    private val metroRealtime: MetroRealtime by lazy {
        MetroRealtimeRemote().parse(MetroFixtures.realtime.s("rawResponse"), "01000000", "01130000")
    }
    private val metroRealtimeData: MetroData get() = metroData.copy(
        realtime = metroRealtime,
        realtimeStation = "01130000",
        realtimeStationName = "高崎",
        realtimeUpdated = "08:12"
    )

    @Test fun metroRealtime_light() = capture("metro-realtime", false) {
        MetroContent(UiState.Success(metroRealtimeData), {}, {}, {}, {})
    }
    @Test fun metroRealtime_dark() = capture("metro-realtime", true) {
        MetroContent(UiState.Success(metroRealtimeData), {}, {}, {}, {})
    }

    /**
     * 多参考站合并：同一秒的行政中心（02100000）与马青路（02090000）两份原始响应，
     * 走线上同一条路径——按站序计划最多八个参考站、只有这两份真有响应、再 mergeMetroRealtime 合并。
     * 画面要回答用户那句话：「海沧行政中心—马青路之间那班车画出来了没有」。
     * 两份都是抓包原样字节，取自 analysis/fixtures/metro-round2/（该目录已入库，
     * manifest.json 里带 sha256：a510e6d8… 与 65f7f883…），不手写、不改值。
     */
    private fun mergedRealtime(): MetroRealtime = kotlinx.coroutines.runBlocking {
        val admin = MetroFixtures.file("metro-round2/rt-02000000-095729.json").readText()
        val maqing = MetroFixtures.file("metro-round2/rt-02000000-095729-mq.json").readText()
        val http = HttpClient(MockEngine { request ->
            when (request.url.parameters["stationCode"]) {
                "02100000" -> respond(admin)
                "02090000" -> respond(maqing)
                // 其余参考站这一批本来就没有响应；线上也缺，不能让它们凭空长出车。
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        }) { install(HttpTimeout) }
        try {
            MetroRealtimeRemote(http).latestLine("02000000", metroLine2.liveStations.map { it.code }, "02100000")
        } finally {
            http.close()
        }
    }

    private val metroMerged: MetroRealtime by lazy { mergedRealtime() }
    private val metroMergedData: MetroData get() = MetroData(
        lines = metroLines,
        selected = "2",
        updated = "2026-11-08",
        realtime = metroMerged,
        realtimeStation = "02100000",
        realtimeStationName = "海沧行政中心",
        realtimeUpdated = "09:57",
        // 该方向（五缘湾 → 天竺山）里行政中心下一站才是马青路，缺车的那一段正在这里。
        directionIndex = metroMerged.directions.indexOfFirst { it.origin == "五缘湾" && it.destination == "天竺山" }
    )

    /** 截图之外的硬断言：合并真的补了位置，而且没有把别站的 ETA 冒充成本站到站行。 */
    private fun assertMergedMarker() {
        val direction = metroMerged.directions[metroMergedData.directionIndex]
        val adminIndex = direction.stationCodes.indexOf("02100000")
        val maqingIndex = direction.stationCodes.indexOf("02090000")
        assertEquals("该方向行政中心的下一站必须是马青路", adminIndex + 1, maqingIndex)
        assertEquals(listOf(332, 692, 1052), direction.trains.map { it.arriveSeconds })
        assertTrue(direction.trains.all { it.source?.referenceStationCode == "02100000" })
        assertFalse("马青路那份的区间车不能进本站到站行", direction.trains.any { it.nextStationCode == "02090000" })
        assertTrue("位置条必须多于一个车标，实际 ${direction.positions.size}", direction.positions.size > 1)
        val between = direction.positions.single { it.nextStationCode == "02090000" }
        assertEquals("02090000", between.source?.referenceStationCode)
        assertEquals(82, between.arriveSeconds)
        assertEquals(1, between.arrivingFlag)
        assertEquals(0.30, between.scale, 0.0)
        assertEquals(adminIndex + 0.5, direction.positionOf(between), 0.0)
        // 首轮合同仅关联本站三行；现在全方向班序独立保留，本站 ETA 仍只来自原始行。
        assertNull("补位置的区间车没有本站 ETA 行序", direction.arrivalRowOrderOf(between))
        assertEquals("补充区间车仍显示全方向第一班", 1, direction.arrivalOrderOf(between))
        assertEquals((1..direction.positions.size).toList(), direction.positionPresentations().map { it.ordinal })
    }

    @Test fun metroRealtimeMerge_light() {
        assertMergedMarker()
        capture("metro-realtime-merge", false) { MetroContent(UiState.Success(metroMergedData), {}, {}, {}, {}) }
    }
    @Test fun metroRealtimeMerge_dark() = capture("metro-realtime-merge", true) {
        MetroContent(UiState.Success(metroMergedData), {}, {}, {}, {})
    }

    /**
     * 2 号线、选中「新垵」：与官方基准图 `notes/refs/official-train-position.jpg` 同场景，
     * 也按官方那张的 360dp×780dp（1080×2340）出图，方便逐块对照版式。
     *
     * 到站分钟取官方截图里的同一组（第1班 3 分钟 / 第2班 9 分钟 / 第3班 16 分钟），
     * 车位也按官方那两段：第1班在马青路前一段、第2班在翁角路前一段（都有本站班序）；
     * 第3班只在 ETA 蓝块里出现、不画车位——和官方截图一致。
     * 抓包目录里没有 02070000（新垵）的原始响应，这组值是按官方截图场景重建的演示数据。
     */
    private fun xinanDirection(codes: List<String>): MetroDirection {
        val source = MetroObservationSource("02070000")
        fun train(seconds: Int, next: String, name: String, flag: Int) = MetroTrain(
            arriveSeconds = seconds, nextStationCode = next, nextStationName = name,
            scale = 0.0, arrivingFlag = flag, specialPosition = 0, specialPositionDesc = "", source = source
        )
        val first = train(170, "02080000", "翁角路", 1)
        val second = train(510, "02090000", "马青路", 1)
        val third = train(950, "02090000", "马青路", 1)
        return MetroDirection(
            origin = "五缘湾", destination = "天竺山", startTime = "06:00", endTime = "23:00",
            delayMinutes = 0, trains = listOf(first, second, third), stationCodes = codes,
            source = source, positionTrains = listOf(first, second), arrivalsAvailable = true
        )
    }

    private val xinanData: MetroData by lazy {
        // 往天竺山 = 五缘湾 → 天竺山，与 metro.json 的站序相反。
        val codes = metroLine2.liveStations.map { it.code }.reversed()
        val toTianzhu = xinanDirection(codes)
        val toWuyuan = toTianzhu.copy(
            origin = "天竺山", destination = "五缘湾", stationCodes = codes.reversed(),
            trains = emptyList(), positionTrains = emptyList()
        )
        MetroData(
            lines = metroLines, selected = "2", updated = "2026-11-08",
            realtime = MetroRealtime(
                online = true, note = "", lineCode = "02000000",
                stationCode = "02070000", stationName = "新垵",
                directions = listOf(toTianzhu, toWuyuan)
            ),
            realtimeStation = "02070000", realtimeStationName = "新垵",
            realtimeUpdated = "12:40", directionIndex = 0
        )
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
    fun metroLine2Xinan_light() = capture("metro-line2-xinan", false, 1080, 2340) {
        MetroContent(UiState.Success(xinanData), {}, {}, {}, {}, refresh = {})
    }

    /** 同一场景的深色版：官方没有深色参考图，这张是我们自己那套配色的验图。 */
    @Test @Config(qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
    fun metroLine2Xinan_dark() = capture("metro-line2-xinan", true, 1080, 2340) {
        MetroContent(UiState.Success(xinanData), {}, {}, {}, {}, refresh = {})
    }

    /**
     * 6 号线：官方色 `#92308D` 压在深卡上只有 2.45:1，比 3:1 还低——这张专门验深色下
     * 补的那根 0.6dp 描边（线路色一个字节都没改，只把轮廓提出来）。
     */
    private val line6Data: MetroData by lazy {
        val line = metroLines.first { it.id == "6" }
        val codes = line.liveStations.map { it.code }
        val names = line.liveStations.associate { it.code to it.name }
        val index = codes.size / 2
        val reference = codes[index]
        val source = MetroObservationSource(reference)
        val trains = (1..3).map { step ->
            val next = codes[(index + step).coerceAtMost(codes.lastIndex)]
            MetroTrain(
                arriveSeconds = step * 200, nextStationCode = next, nextStationName = names[next].orEmpty(),
                scale = 0.0, arrivingFlag = 1, specialPosition = 0, specialPositionDesc = "", source = source
            )
        }
        MetroData(
            lines = metroLines, selected = "6", updated = "2026-11-08",
            realtime = MetroRealtime(
                online = true, note = "", lineCode = "",
                stationCode = reference, stationName = names[reference].orEmpty(),
                directions = listOf(
                    MetroDirection(
                        origin = names[codes.first()].orEmpty(), destination = names[codes.last()].orEmpty(),
                        startTime = "06:00", endTime = "23:00", delayMinutes = 0, trains = trains,
                        stationCodes = codes, source = source, positionTrains = trains.take(2),
                        arrivalsAvailable = true
                    )
                )
            ),
            realtimeStation = reference, realtimeStationName = names[reference].orEmpty(),
            realtimeUpdated = "12:40", directionIndex = 0
        )
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
    fun metroLine6_dark() = capture("metro-line6", true, 1080, 2340) {
        MetroContent(UiState.Success(line6Data), {}, {}, {}, {}, refresh = {})
    }

    /**
     * 换乘徽标验图：3 号线、参考站「体育中心」（03030000，官方共站码可换 2 号线 02160000）。
     *
     * 卡片站名行里那枚绿胶囊＝2 号线主题色（点它跳 2 号线体育中心）；站序列表前两行正好是
     * 湖滨东路（换 1 号线橙）和体育中心（换 2 号线绿），所以一张图里能同时看到「非本线颜色」
     * 的换乘徽标和本线的线路号徽标。浅深各一张。
     */
    private val transferData: MetroData by lazy {
        val line = metroLine3
        val codes = line.liveStations.map { it.code }
        val names = line.liveStations.associate { it.code to it.name }
        val reference = "03030000"
        val index = codes.indexOf(reference)
        val source = MetroObservationSource(reference)
        // 到站行照官方真实响应的形态造（见 analysis/fixtures/metro-realtime-line2-flag2-sample.json）：
        // 第 1 行的下一站就是本站（flag=2，画在本站），第 2、3 行是本站**上游**的更远站（flag=1）。
        // 之前这里写成 codes[index + step]，把 ETA 越大的车摆到越下游，位置序与到站先后正好相反，
        // 于是「第N班」看起来整体错位一格（最近那班成了第 2 班，最远那格没班号）。真实数据不会这样。
        val trains = (1..3).map { step ->
            val next = codes[(index - step + 1).coerceAtLeast(0)]
            MetroTrain(
                arriveSeconds = step * 180, nextStationCode = next, nextStationName = names.getValue(next),
                scale = 0.0, arrivingFlag = if (step == 1) 2 else 1, specialPosition = 0, specialPositionDesc = "",
                source = source
            )
        }
        MetroData(
            lines = metroLines, selected = "3", updated = "2026-11-08",
            realtime = MetroRealtime(
                online = true, note = "", lineCode = "03000000",
                stationCode = reference, stationName = names.getValue(reference),
                directions = listOf(
                    MetroDirection(
                        origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
                        startTime = "06:00", endTime = "23:00", delayMinutes = 0, trains = trains,
                        // 本站三行都在位置批次里（生产 merge 就是这么给的），三格班号才不会缺一格。
                        stationCodes = codes, source = source, positionTrains = trains,
                        arrivalsAvailable = true
                    )
                )
            ),
            realtimeStation = reference, realtimeStationName = names.getValue(reference),
            realtimeUpdated = "12:40", directionIndex = 0
        )
    }

    @Test fun metroTransfer_light() = capture("metro-transfer", false) {
        MetroContent(UiState.Success(transferData), {}, {}, {}, {}, refresh = {})
    }
    @Test fun metroTransfer_dark() = capture("metro-transfer", true) {
        MetroContent(UiState.Success(transferData), {}, {}, {}, {}, refresh = {})
    }

    /**
     * 截图用的演示数据本身也要自洽：本站第 k 行必须正好是全方向第 k 班（越近越靠前）。
     *
     * 这里守的是曾经的坑：`nextStationCode = codes[index + step]` 把 ETA 越大的车摆到越下游，
     * 于是 ETA 三格的班号整体反了（3 分钟那格成了第 2 班），最左一格还因为没有位置记录
     * 退化成「预计到达」。真实响应里第 1 行的下一站就是本站（flag=2），第 2、3 行在本站上游。
     */
    @Test
    fun metroTransferFixtureKeepsArrivalOrderAndOrdinalsInStep() {
        val direction = requireNotNull(transferData.direction)
        val presentations = direction.positionPresentations()
        assertEquals("三行到站行都要有位置记录，否则 ETA 会少一个班号", 3, presentations.size)
        assertEquals("本站第 k 行必须就是第 k 班", listOf(1, 2, 3), presentations.map { it.arrivalRowOrder })
        assertEquals("班号必须连续没有空格", listOf(1, 2, 3), presentations.map { it.ordinal })
        assertEquals("最近的一班必须是第 1 班", 1, presentations.first().ordinal)
        assertTrue(
            "位置必须随行序向该方向上游回退，实际 ${presentations.map { it.stationPosition }}",
            presentations.map { it.stationPosition }.zipWithNext().all { (near, far) -> near > far }
        )
        assertEquals(
            "第 1 行的分钟数必须是三行里最小的",
            direction.trains.minOf { it.arriveSeconds },
            direction.trains.first().arriveSeconds
        )
    }

    /**
     * 三条同位车：同一条 `nextStationCode`、同 `arrivingFlag` 的三条记录在数据层是同一
     * 「潜在碰撞群」（`stackIndex` 0/1/2），画面必须错层——同一个 x，往上依次一层，
     * 而不是叠成一坨。浅色/深色各一张，用来核 lane 高度与标签有没有压到轨道/站名。
     */
    private val stackData: MetroData by lazy {
        val codes = metroLine2.liveStations.map { it.code }
        val names = metroLine2.liveStations.associate { it.code to it.name }
        val reference = codes[8]
        val source = MetroObservationSource(reference)
        fun at(seconds: Int) = MetroTrain(
            arriveSeconds = seconds, nextStationCode = codes[6], nextStationName = names.getValue(codes[6]),
            scale = 0.0, arrivingFlag = 2, specialPosition = 0, specialPositionDesc = "", source = source
        )
        val trains = listOf(at(300), at(600), at(900))
        MetroData(
            lines = metroLines, selected = "2", updated = "2026-11-08",
            realtime = MetroRealtime(
                online = true, note = "", lineCode = "02000000",
                stationCode = reference, stationName = names.getValue(reference),
                directions = listOf(
                    MetroDirection(
                        origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
                        startTime = "06:00", endTime = "23:00", delayMinutes = 0, trains = trains,
                        stationCodes = codes, source = source, positionTrains = trains, arrivalsAvailable = true
                    )
                ),
                source = source, sources = listOf(source)
            ),
            realtimeStation = reference, realtimeStationName = names.getValue(reference),
            realtimeUpdated = "12:40", directionIndex = 0
        )
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
    fun metroRealtimeStack_light() = capture("metro-realtime-stack", false, 1080, 2340) {
        MetroContent(UiState.Success(stackData), {}, {}, {}, {}, refresh = {})
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
    fun metroRealtimeStack_dark() = capture("metro-realtime-stack", true, 1080, 2340) {
        MetroContent(UiState.Success(stackData), {}, {}, {}, {}, refresh = {})
    }

    /**
     * 「到本站先后」班号：**5 班未到站 + 1 班刚开走**，一张图看全三件事——
     * ① 第 1 班是最先到本站那班（不是全线最靠终点那班）；
     * ② 班号不截断到 3，第 4、5 班照写；
     * ③ 已开过本站的那班车标**照画**，标签写「已过」（次要文字色，不是错误红）。
     *
     * 站码站名取自 `assets/metro.json`（2 号线），参考站「海沧行政中心」；
     * 真实响应每个参考站只回 3 行，所以「有几班」是构造的（见 `MetroStationOrdinalsTest`）。
     */
    private val manyData: MetroData by lazy {
        val codes = metroLine2.liveStations.map { it.code }
        val names = metroLine2.liveStations.associate { it.code to it.name }
        val reference = "02100000"
        val index = codes.indexOf(reference)
        val source = MetroObservationSource(reference)
        fun train(seconds: Int, at: Int, flag: Int) = MetroTrain(
            arriveSeconds = seconds, nextStationCode = codes[index + at],
            nextStationName = names.getValue(codes[index + at]),
            scale = 0.0, arrivingFlag = flag, specialPosition = 0, specialPositionDesc = "", source = source
        )
        // 未到站 5 班：本站站台 → 本站前一段 → 上一站站台 → 再往前两段，坐标与到站先后同向。
        val upcoming = listOf(
            train(120, 0, 2),
            train(240, 0, 1),
            train(360, -1, 2),
            train(480, -1, 1),
            train(600, -2, 2)
        )
        // 刚开走那班：下一站是本站的下一站（flag=1）⇒ 已过本站，车标照画。
        val passed = train(60, 1, 1)
        MetroData(
            lines = metroLines, selected = "2", updated = "2026-11-08",
            realtime = MetroRealtime(
                online = true, note = "", lineCode = "02000000",
                stationCode = reference, stationName = names.getValue(reference),
                directions = listOf(
                    MetroDirection(
                        origin = names.getValue(codes.first()), destination = names.getValue(codes.last()),
                        startTime = "06:00", endTime = "23:00", delayMinutes = 0,
                        trains = upcoming.take(3), stationCodes = codes, source = source,
                        positionTrains = listOf(passed) + upcoming, arrivalsAvailable = true
                    )
                )
            ),
            realtimeStation = reference, realtimeStationName = names.getValue(reference),
            realtimeUpdated = "12:40", directionIndex = 0
        )
    }

    /** 截图数据自洽：5 班都编号、已过站那班没有班号但仍在车标列表里、行序 1..3 与班号同向。 */
    @Test
    fun metroManyFixtureNumbersFiveClassesAndKeepsThePassedMarker() {
        val direction = requireNotNull(manyData.direction)
        val ordinals = direction.stationOrdinals(manyData.realtimeStation)
        assertEquals(listOf(1, 2, 3, 4, 5), ordinals.trains.mapNotNull { it.ordinal })
        assertEquals(1, ordinals.passedCount)
        assertEquals(listOf(1, 2, 3), (1..3).map { ordinals.ordinalOfRow(it) })
    }

    @Test fun metroRealtimeMany_light() = capture("metro-realtime-many", false) {
        MetroContent(UiState.Success(manyData), {}, {}, {}, {}, refresh = {})
    }
    @Test fun metroRealtimeMany_dark() = capture("metro-realtime-many", true) {
        MetroContent(UiState.Success(manyData), {}, {}, {}, {}, refresh = {})
    }

    @Composable private fun SettingsTab(update: UpdateState = UpdateState()) = HomeContent(
        UiState.Success(HomeData(position = Position(24.48, 118.13), cityResolved = true)), city, true, 2,
        {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> },
        settingsContent = { SettingsBody(UiState.Success(SettingsData(city, update = update)), {}, {}) }
    )

    @Composable private fun Home(state: UiState<HomeData>, segment: Int = 0, granted: Boolean = true) = HomeContent(state, city, granted, segment, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> })
    @Composable private fun Line(panel: Int, selected: Boolean = true) = LineContent(UiState.Success(LineData(lineRoute, if (selected) beijingStops[1] else null, live, if (selected) listOf(Arrival(remainingStations = 1, timeText = "约3分钟", name = "1路", direction = "1")) else emptyList())), beijing, panel, false, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})

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

    private fun capture(name: String, dark: Boolean, expectedWidth: Int = 1233, expectedHeight: Int = 2673, ready: () -> Boolean = { true }, content: @Composable () -> Unit) {
        org.robolectric.RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = HoucheTypography, shapes = HoucheShapes) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            ready()
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
    @Test fun lineUnselected_light() {
        capture("line-unselected", false) { Line(0, selected = false) }
        compose.onNodeWithText("点击下方站点选择乘车站").assertDoesNotExist()
        compose.onNodeWithText("全线车辆").assertIsDisplayed()
        compose.onNodeWithText("2 辆").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("八王坟西，1 辆车").onFirst().assert(hasClickAction())
    }
    @Test fun lineUnselected_dark() = capture("line-unselected", true) { Line(0, selected = false) }
    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun lineNarrow_light() = capture("line-narrow", false, 1080, 2400) { Line(0) }
    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun lineNarrow_dark() = capture("line-narrow", true, 1080, 2400) { Line(0) }
    @Test fun selectedStopSemantics() {
        capture("line-stops", false) { Line(0) }
        val row = compose.onNodeWithContentDescription("八王坟西，当前乘车站，1 辆车")
        row.assert(hasClickAction()).assertIsDisplayed()
        assertTrue(row.fetchSemanticsNode().children.none { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) })
        row.performClick()
    }
    @Test fun searchHistory_light() = capture("search-history", false) { SearchContent(UiState.Success(SearchData(history = listOf("91路", "实验幼儿园", "火车站", "BRT"))), {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun searchHistory_dark() = capture("search-history", true) { SearchContent(UiState.Success(SearchData(history = listOf("91路", "实验幼儿园", "火车站", "BRT"))), {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun searchEmpty_light() = capture("search-empty", false) { SearchContent(UiState.Success(SearchData("无匹配")), {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun searchEmpty_dark() = capture("search-empty", true) { SearchContent(UiState.Success(SearchData("无匹配")), {}, {}, {}, {}, {}, {}, {}, {}) }

    /** 搜索「地铁」：地铁资料在本地，公交接口还没回来时也要能看见这三条线。 */
    @Test fun searchMetro_light() = capture("search-metro", false) {
        SearchContent(
            UiState.Success(SearchData("地铁", SearchResults(emptyList(), emptyList()), metro = listOf(metroLine1, metroLine2, metroLine3, metroLine4))),
            {}, {}, {}, {}, {}, {}, {}, {}, metro = {}
        )
    }
    @Test fun searchMetro_dark() = capture("search-metro", true) {
        SearchContent(
            UiState.Success(SearchData("地铁", SearchResults(emptyList(), emptyList()), metro = listOf(metroLine1, metroLine2, metroLine3, metroLine4))),
            {}, {}, {}, {}, {}, {}, {}, {}, metro = {}
        )
    }

    /** 首页有地铁资料时，顶栏刷新左侧出现地铁入口。 */
    @Test fun homeMetro_light() = capture("home-metro", false) {
        HomeContent(
            UiState.Success(HomeData(stations = listOf(stop), position = Position(24.48, 118.13), cityResolved = true, hasMetro = true)),
            city, true, 0, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, metro = {}
        )
    }

    /** 经过真实城市表、共享资料、ViewModel 和 HomeScreen，防止中间层漏传入口回调。 */
    @Test fun homeViewModelMetro_light() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        val xiamen = kotlinx.coroutines.runBlocking { graph.cities.all().single { it.name == "厦门市" } }
        assertEquals("11", xiamen.key)
        val entry = PageEntry(Screen.Home)
        var homeVm: HomeViewModel? = null
        var settingsVm: SettingsViewModel? = null
        var opened = false
        var selectedLine: String? = "尚未点击"
        try {
            capture("home-metro-viewmodel", false, ready = {
                homeVm?.state?.value?.data?.hasMetro == true &&
                    settingsVm?.state?.value?.data?.metroSource?.isNotBlank() == true
            }) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                    val home = model(key = xiamen.key) { HomeViewModel(graph, xiamen) }
                    val settings = model(key = "settings-${xiamen.key}") { SettingsViewModel(graph, xiamen) }
                    homeVm = home
                    settingsVm = settings
                    HomeScreen(home, settings, false, 0, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, metro = {
                        opened = true
                        selectedLine = it
                    })
                }
            }
            assertFalse("首页读取地铁资料不应进入错误态", homeVm!!.state.value is UiState.Error)
            assertFalse("设置读取同份资料不应进入错误态", settingsVm!!.state.value is UiState.Error)
            val metro = compose.onNodeWithContentDescription("地铁")
            metro.assertIsDisplayed().assertHasClickAction()
            val refresh = compose.onNodeWithContentDescription("刷新").assertIsDisplayed()
            assertTrue("地铁入口应在刷新左边", metro.fetchSemanticsNode().boundsInRoot.right <= refresh.fetchSemanticsNode().boundsInRoot.left)
            metro.performClick()
            assertTrue("地铁按钮必须触发导航回调", opened)
            assertNull("顶栏入口应打开地铁首页", selectedLine)
        } finally {
            compose.runOnUiThread { entry.viewModelStore.clear() }
        }
    }
    @Test fun stationMetro_light() = capture("station-metro", false) {
        StationContent(UiState.Success(StationData(Station("吕厝", "SM000170", 365.0, lines = lines), arrivals, true,
            metro = listOf(MetroCalling(metroLine1, MetroStation(8, "吕厝", listOf("2号线"))), MetroCalling(metroLine2, MetroStation(16, "吕厝", listOf("1号线")))))), {}, { _, _ -> }, {}, {}, {})
    }
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
    @Test fun lineLongStops_light() = capture("line-long-stops", false) {
        LineContent(UiState.Success(LineData(longRoute, longStops[3], VehicleResults(listOf(
            Vehicle("闽D·28810", 22, "at-stop", currentStation = "海沧房产", dataTime = "17:06:12"),
            Vehicle("闽D·51927", 24, "between", nextStation = "祥露公交场站", dataTime = "17:06:12", segmentDistance = 410.0)
        ), "车辆位置仅供参考", nextDeparture = "17:20"), listOf(Arrival(remainingStations = 0, timeText = "即将到站", name = "807路", direction = "1")))),
            city, 0, false, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})
    }
    @Test fun lineLongStops_dark() = capture("line-long-stops", true) {
        LineContent(UiState.Success(LineData(longRoute, longStops[3], VehicleResults(listOf(
            Vehicle("闽D·28810", 22, "at-stop", currentStation = "海沧房产", dataTime = "17:06:12"),
            Vehicle("闽D·51927", 24, "between", nextStation = "祥露公交场站", dataTime = "17:06:12", segmentDistance = 410.0)
        ), "车辆位置仅供参考", nextDeparture = "17:20"), listOf(Arrival(remainingStations = 0, timeText = "即将到站", name = "807路", direction = "1")))),
            city, 0, false, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})
    }

    @Test fun scene9_light() = capture("settings", false) { SettingsTab() }
    @Test fun scene9_dark() = capture("settings", true) { SettingsTab() }
    @Test fun scene10_light() = capture("home-loading", false) { Home(UiState.Loading(HomeData(position = Position(24.48, 118.13)))) }
    @Test fun scene10_dark() = capture("home-loading", true) { Home(UiState.Loading(HomeData(position = Position(24.48, 118.13)))) }
    @Test fun scene11_light() = capture("home-error", false) { Home(UiState.Error("公交查询失败，请检查网络后重试", home)) }
    @Test fun scene11_dark() = capture("home-error", true) { Home(UiState.Error("公交查询失败，请检查网络后重试", home)) }
    @Test fun scene12_light() = capture("favorites-empty", false) { Home(UiState.Success(HomeData()), segment = 1, granted = false) }
    @Test fun scene12_dark() = capture("favorites-empty", true) { Home(UiState.Success(HomeData()), segment = 1, granted = false) }
    @Test fun longStation_light() = capture("home-long-station", false) { HomeContent(UiState.Success(HomeData(stations = listOf(longStop), position = Position(39.907, 116.401), cityResolved = true)), beijing, true, 0, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }) }
    @Test fun longStation_dark() = capture("home-long-station", true) { HomeContent(UiState.Success(HomeData(stations = listOf(longStop), position = Position(39.907, 116.401), cityResolved = true)), beijing, true, 0, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }) }

    // 车辆跟踪页：沿用 807 路海沧站序，车辆与更新时刻为演示数据。
    @Composable private fun VehicleShot() = VehicleContent(
        UiState.Success(VehicleData(
            route = longRoute,
            plate = "闽D·28810",
            boarding = RouteStation("海沧房产", 22),
            vehicle = Vehicle("闽D·28810", 21, "between", nextStation = "海沧房产", dataTime = "17:06:12", segmentDistance = 320.0),
            vehicles = listOf(
                Vehicle("闽D·28810", 21, "between", nextStation = "海沧房产", dataTime = "17:06:12", segmentDistance = 320.0),
                Vehicle("闽D·20356", 22, "at-stop", currentStation = "海沧房产", dataTime = "17:06:12"),
                Vehicle("闽D·51927", 24, "between", nextStation = "祥露公交场站", dataTime = "17:06:12", segmentDistance = 410.0)
            ),
            tracking = true,
            updatedAt = "17:06:12"
        )),
        {}, { _, _ -> }, {}, {}, {}
    )
    @Test fun settingsUpdate_light() = capture("settings-update", false) {
        SettingsTab(UpdateState(release = RemoteRelease("1.3.0", 4, "首页去掉重复入口；站点图按车辆位置区分到站与途中；新增车辆跟踪页与到站提醒；设置内新增检查更新。", "GitHub", "https://example.com/houche.apk"), hasUpdate = true))
    }
    @Test fun settingsUpdate_dark() = capture("settings-update", true) {
        SettingsTab(UpdateState(release = RemoteRelease("1.3.0", 4, "首页去掉重复入口；站点图按车辆位置区分到站与途中；新增车辆跟踪页与到站提醒；设置内新增检查更新。", "GitHub", "https://example.com/houche.apk"), hasUpdate = true))
    }

    @Test fun vehicle_light() = capture("vehicle", false) { VehicleShot() }
    @Test fun vehicle_dark() = capture("vehicle", true) { VehicleShot() }

    /** 把自适应图标真实渲染出来，供文档展示；同时出一张圆形遮罩版，接近桌面上的样子。 */
    @Test fun appIcon() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val icon = androidx.core.content.ContextCompat.getDrawable(context, com.yll682.houche.R.mipmap.ic_launcher)
            ?: error("找不到应用图标")
        fun render(size: Int, round: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (round) {
                val path = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }
                canvas.clipPath(path)
            }
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            return bitmap
        }
        val dir = File(System.getProperty("screenshots.dir"))
        dir.mkdirs()
        // 432 用于文档展示，48 用于核对桌面上的真实显示尺寸。
        for ((name, spec) in listOf(
            "app-icon" to (432 to false),
            "app-icon-round" to (432 to true),
            "app-icon-48" to (48 to true)
        )) {
            val (size, round) = spec
            val file = File(dir, "$name.png")
            file.outputStream().use { render(size, round).compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("$name 应已写出", file.length() > 0)
        }
    }
}
