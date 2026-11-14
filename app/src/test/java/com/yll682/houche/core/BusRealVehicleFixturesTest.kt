package com.yll682.houche.core

import com.yll682.houche.data.AlightReminderEvent
import com.yll682.houche.data.AlightReminderState
import com.yll682.houche.data.decideAlightReminder
import com.yll682.houche.data.remainingStopsToTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.security.MessageDigest

private fun fixtureText(name: String): String =
    requireNotNull(Bus真实车辆夹具测试::class.java.getResourceAsStream("/bus-fixtures/$name")) {
        "缺少真实夹具：$name"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

private fun fixture(name: String): JsonElement = Json.parseToJsonElement(fixtureText(name))

private fun fixtureRepository(http: HttpClient) =
    BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), ZsgjClient(http))

private val xiamenFixtureCity = City("11", "厦门市", "0592")

/**
 * 第 12 轮期望文件保持原字节；其中零速 unknown 按第 13 轮口径明确改断言为本站 at-stop。
 * 原始站序、下一站和正速度锚点继续使用冻结值；期望不调用被测解析函数生成。
 * 每份 busList 单独列为参数，断言逐车执行，包含不同参照站及多轮实测。
 */
@RunWith(Parameterized::class)
class Bus真实车辆夹具测试(private val file: String, private val expected: JsonElement) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun samples(): Collection<Array<Any>> =
            fixture("vehicle-expectations.json").field("samples").items()
                .map { arrayOf<Any>(it.s("file"), it) }
    }

    private fun stops(): List<RouteStation> = fixture(expected.s("lineDetail")).field("data")
        .list("stationList").filter { it.s("upperOrDown") == expected.s("direction") }
        .map { RouteStation(it.s("name"), it.i("stationOrder"), it.field("id").text()) }

    @Test fun `生产仓库逐车解析站序状态下一站和原始诊断字段`() = runBlocking {
        var vehicleRequests = 0
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath.substringAfterLast('/')
            val response = when (path) {
                "lineDetail" -> {
                    assertEquals(expected.s("line"), request.url.parameters["busLineName"])
                    fixtureText(expected.s("lineDetail"))
                }
                "busList" -> {
                    vehicleRequests++
                    val anchor = stops().single { it.order == expected.i("referenceOrder") }
                    assertEquals(expected.s("direction"), request.url.parameters["direction"])
                    assertEquals(anchor.order.toString(), request.url.parameters["stationSeq"])
                    assertEquals(anchor.id, request.url.parameters["stationId"])
                    assertEquals(anchor.name, request.url.parameters["stationName"])
                    assertEquals(fixture(expected.s("lineDetail")).field("data").s("id"), request.url.parameters["busLineId"])
                    fixtureText(file)
                }
                else -> error("不应发出其他请求：$path")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        http.use {
            val repo = fixtureRepository(http)
            val route = repo.lineDetail(xiamenFixtureCity, expected.s("line"), expected.s("direction"))
            val anchor = route.stations.single { it.order == expected.i("referenceOrder") }
            val result = repo.vehicles(xiamenFixtureCity, route, anchor)
            assertEquals(1, vehicleRequests)
            val actual = result.vehicles.associateBy { it.plate }
            val source = fixture(file).list("data").associateBy { it.s("plateNo") }
            assertEquals(expected.list("vehicles").map { it.s("plate") }.toSet(), actual.keys)
            assertEquals(expected.list("vehicles").size, result.vehicles.size)
            for (row in expected.list("vehicles")) {
                val message = "$file ${row.s("plate")}"
                val vehicle = actual.getValue(row.s("plate"))
                val raw = source.getValue(vehicle.plate)
                val stopped = raw.d("speed") == 0.0
                assertEquals(message, if (stopped) row.i("reportedOrder") else row.i("order"), vehicle.order)
                assertEquals(message, if (stopped) "at-stop" else row.s("positionState"), vehicle.positionState)
                assertEquals(message, row.i("reportedOrder"), vehicle.currentStationOrder)
                assertEquals(message, row.field("nextStation").text(), vehicle.nextStation)
                assertEquals(message, row.i("reportedOrder"), vehicle.reportedOrder)
                assertEquals(message, raw.field("inOrOut").text(), vehicle.inOrOut)
                assertEquals(message, raw.field("dataTime").text(), vehicle.dataTime)
                assertEquals(message, raw.d("lat"), vehicle.lat)
                assertEquals(message, raw.d("lng"), vehicle.lng)
                assertEquals(message,
                    if (stopped) route.stations.single { it.order == vehicle.reportedOrder }.name else null,
                    vehicle.currentStation)
                // 上游未提供有效站段距离，不能把占位零当作真实距离。
                assertNull(message, vehicle.segmentDistance)
            }
        }
    }

    @Test fun `同方向真实站表逐车确认一基站序和下一站角色`() {
        val stations = stops()
        assertEquals((1..stations.size).toList(), stations.map { it.order }.sortedBy { it })
        val expectedByPlate = expected.list("vehicles").associateBy { it.s("plate") }
        for (raw in fixture(file).list("data")) {
            val reported = requireNotNull(raw.i("stationOrder"))
            assertEquals(1, stations.count { it.order == reported })
            val next = stations.single { it.order == reported + 1 }
            val name = raw.field("nextStationName").text()
            if (name != null) assertEquals("$file ${raw.s("plateNo")}", next.name, name)
            assertEquals(next.name, expectedByPlate.getValue(raw.s("plateNo")).s("nextStation"))
        }
    }

    @Test fun `车标锚点行驶时落在相邻两站而零速落在本站中心`() {
        val stations = stops()
        for (raw in fixture(file).list("data")) {
            val vehicle = parseXiamenVehicle(raw, stations)
            if (vehicle.positionState == "between") {
                val next = requireNotNull(resolveVehicleStation(vehicle, stations))
                assertEquals(vehicle.reportedOrder!! + 1, next.order)
                assertEquals(vehicle.nextStation, next.name)
                assertEquals(1, stations.count { it.order == next.order!! - 1 })
            } else {
                assertEquals(0.0, raw.d("speed"))
                assertEquals("at-stop", vehicle.positionState)
                assertEquals(vehicle.reportedOrder, vehicle.order)
                assertEquals(vehicle.reportedOrder, resolveVehicleStation(vehicle, stations)?.order)
            }
            assertEquals(raw.i("stationOrder"), vehicle.currentStationOrder)
        }
    }

    @Test fun `真实车辆到全部目标站的站差均使用当前站而非绘图锚点`() {
        val stations = stops()
        for (raw in fixture(file).list("data")) {
            val vehicle = parseXiamenVehicle(raw, stations)
            val reported = requireNotNull(raw.i("stationOrder"))
            for (target in stations) {
                assertEquals("$file ${vehicle.plate} -> ${target.order}",
                    target.order!! - reported, remainingStopsToTarget(target, vehicle))
            }
        }
    }

    @Test fun `真实车辆前一站到站驶离和过站按当前站口径提醒并去重`() {
        val stations = stops()
        for (raw in fixture(file).list("data")) {
            val vehicle = parseXiamenVehicle(raw, stations)
            val reported = requireNotNull(raw.i("stationOrder"))
            for (offset in listOf(1, 0, -1)) {
                val target = stations.singleOrNull { it.order == reported + offset } ?: continue
                val before = AlightReminderState(beforeOrder = if (offset == 1) null else target.order)
                val decision = decideAlightReminder(
                    remainingStopsToTarget(target, vehicle), target.order, before,
                    atStop = vehicle.positionState == "at-stop", vehicle = vehicle
                )
                val expectedEvent = when {
                    offset == 1 -> AlightReminderEvent.BEFORE
                    offset < 0 || vehicle.positionState == "between" -> AlightReminderEvent.PASSED
                    else -> AlightReminderEvent.ARRIVED
                }
                assertEquals("$file ${vehicle.plate} 站差=$offset", expectedEvent, decision.event)
                assertEquals(offset != 1, decision.state.stopped)
                // 将 previous 序列化再恢复，和后台恢复使用同一状态，不能重复提醒。
                val restored = Json.decodeFromString<AlightReminderState>(Json.encodeToString(decision.state))
                val repeated = decideAlightReminder(offset, target.order, restored,
                    atStop = vehicle.positionState == "at-stop", vehicle = vehicle)
                assertEquals(AlightReminderEvent.NONE, repeated.event)
                assertEquals(restored, repeated.state)
            }
        }
    }
}

/** 以下为人工构造的异常/边界输入，不冒充真实上游样本。 */
class Bus车辆解析边界测试 {
    private val stations = listOf(RouteStation("甲站", 1, "A"), RouteStation("乙站", 2, "B"), RouteStation("丙站", 3, "C"))
    private fun parse(fields: String, stops: List<RouteStation> = stations): Vehicle =
        parseXiamenVehicle(Json.parseToJsonElement("""{"plateNo":"边界测试车",$fields}"""), stops)

    @Test fun `正速度优先于进出站字段和静止计数但前方锚点始终加一`() {
        for (state in listOf("0", "1", "9")) {
            val vehicle = parse(""""stationOrder":1,"nextStationName":"乙站","inOrOut":"$state","speed":"150","motionLessTime":99""")
            assertEquals(2, vehicle.order)
            assertEquals(1, vehicle.reportedOrder)
            assertEquals("between", vehicle.positionState)
        }
    }

    @Test fun `零速按本站停靠且不依赖进出站字段或静止计数`() {
        for (state in listOf("0", "1")) for (motion in listOf(0, 1, 8, 999)) {
            val vehicle = parse(""""stationOrder":1,"nextStationName":"乙站","inOrOut":"$state","speed":0,"motionLessTime":$motion""")
            assertEquals("at-stop", vehicle.positionState)
            assertEquals(1, vehicle.order)
            assertEquals(1, vehicle.currentStationOrder)
            assertEquals(1, vehicle.reportedOrder)
            assertEquals("乙站", vehicle.nextStation)
        }
    }

    @Test fun `速度缺失负数非法类型和非有限值均保持未知`() {
        for (speed in listOf("", ""","speed":null""", ""","speed":-1""", ""","speed":"NaN"""",
            ""","speed":"Infinity"""", ""","speed":{}""", ""","speed":[]""", ""","speed":"bad"""")) {
            val vehicle = parse(""""stationOrder":1,"nextStationName":"乙站"$speed""")
            assertEquals("unknown", vehicle.positionState)
            assertNull(vehicle.order)
        }
    }

    @Test fun `下一站名与原始序号加一冲突时不平移也不定位`() {
        for (speed in listOf(0, 150)) {
            val vehicle = parse(""""stationOrder":1,"nextStationName":"丙站","speed":$speed""")
            assertEquals("unknown", vehicle.positionState)
            assertNull(vehicle.order)
            assertEquals(1, vehicle.reportedOrder)
            assertEquals(1, vehicle.currentStationOrder)
            assertEquals("丙站", vehicle.nextStation)
        }
    }

    @Test fun `原始序号缺失或非法时不能只凭下一站名倒推定位`() {
        val vehicle = parse(""""nextStationName":"乙站","speed":150""")
        assertNull(vehicle.reportedOrder)
        assertNull(vehicle.order)
        assertNull(vehicle.currentStationOrder)
        assertEquals("unknown", vehicle.positionState)
        for (order in listOf("null", "0", "-1", "99", "2147483647", "\"bad\"", "{}", "[]", "\"\"")) {
            val invalid = parse(""""stationOrder":$order,"nextStationName":"乙站","speed":150""")
            assertEquals("unknown", invalid.positionState)
            assertNull(invalid.order)
        }
    }

    @Test fun `下一站缺失可由有效相邻站序补全但异常结构不当作缺失`() {
        for (name in listOf("", ",\"nextStationName\":null", ",\"nextStationName\":\"\"")) {
            val vehicle = parse(""""stationOrder":1,"speed":150$name""")
            assertEquals(2, vehicle.order)
            assertEquals("乙站", vehicle.nextStation)
        }
        for (name in listOf("{}", "[]")) {
            assertEquals("unknown", parse(""""stationOrder":1,"speed":150,"nextStationName":$name""").positionState)
        }
    }

    @Test fun `空表缺段重复站序首站之前终点之后都不猜区间`() {
        val fields = """"stationOrder":1,"nextStationName":"乙站","speed":150"""
        for (stops in listOf(emptyList(), stations.drop(1), stations + stations.first(), stations + stations[1],
            stations + stations[1].copy(name = ""))) {
            assertEquals("unknown", parse(fields, stops).positionState)
        }
        assertEquals("unknown", parse(""""stationOrder":3,"speed":150""").positionState)
        assertEquals("unknown", parse(""""nextStationName":"甲站","speed":150""").positionState)
        assertEquals("unknown", parse(""""stationOrder":1,"speed":150""", listOf(RouteStation("", 1), stations[1])).positionState)
        assertEquals("unknown", parse(""""stationOrder":1,"speed":150""", listOf(stations[0], RouteStation("", 2))).positionState)
    }

    @Test fun `环线重名无站序不能取第一个而明确站序可以消歧`() {
        val loop = listOf(RouteStation("同名站", 1), RouteStation("中间站", 2), RouteStation("同名站", 3))
        assertEquals("unknown", parse(""""nextStationName":"同名站","speed":150""", loop).positionState)
        assertEquals(3, parse(""""stationOrder":2,"nextStationName":"同名站","speed":150""", loop).order)
    }

    @Test fun `空响应及上游列表结构异常保持无可用位置且不崩溃`() = runBlocking {
        for (data in listOf("[]", "null", "{}", "42", "\"bad\"", "[null,{},42,\"bad\"]", null)) {
            val response = if (data == null) """{"code":0}""" else """{"code":0,"data":$data}"""
            HttpClient(MockEngine {
                respond(response, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }).use { http ->
                val result = fixtureRepository(http).vehicles(xiamenFixtureCity,
                    Route("边界线路", "test", "1", stations = stations))
                assertTrue(result.vehicles.all { it.positionState == "unknown" && it.order == null })
                if (data != "[null,{},42,\"bad\"]") assertTrue(result.vehicles.isEmpty())
                else assertEquals(4, result.vehicles.size)
            }
        }
    }

    @Test fun `真实九十一路第二十九站零速车在本站而正速度车锚点前移`() {
        val sample = fixture("vehicle-expectations.json").list("samples").first {
            it.s("file") == "xiamen-91路-d2-ref1-busList-20261114T043246218Z-6.json"
        }
        val stops = fixture(sample.s("lineDetail")).field("data").list("stationList")
            .filter { it.s("upperOrDown") == "2" }.map { RouteStation(it.s("name"), it.i("stationOrder")) }
        assertEquals("林后", stops.single { it.order == 29 }.name)
        assertEquals("高崎T3候机楼", stops.single { it.order == 30 }.name)
        val raw = fixture(sample.s("file")).list("data").single { it.s("plateNo") == "闽D10312D" }
        assertEquals(29, raw.i("stationOrder"))
        val stopped = parseXiamenVehicle(raw, stops)
        assertEquals("高崎T3候机楼", stopped.nextStation)
        assertEquals(29, stopped.order)
        assertEquals("at-stop", stopped.positionState)
        assertEquals(29, stopped.currentStationOrder)
        val moving = fixture(sample.s("file")).list("data").single { it.s("plateNo") == "闽D17399D" }
        assertEquals(5, parseXiamenVehicle(moving, stops).order)
    }
}

@RunWith(Parameterized::class)
class Bus北京空车辆夹具测试(private val file: String, private val lineFile: String, private val direction: String, private val reference: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun samples(): Collection<Array<Any>> = fixture("vehicle-expectations.json").list("publicSamples")
            .map { arrayOf<Any>(it.s("file"), it.s("lineDetail"), it.s("direction"), it.i("referenceOrder")!!) }
    }

    @Test fun `真实北京请求成功但没有车辆不能验证索引和状态映射`() = runBlocking {
        val city = City("0", "北京市", "010")
        var vehicleRequests = 0
        HttpClient(MockEngine { request ->
            val params = (request.body as FormDataContent).formData
            assertEquals("北京市", params["CITYNAME"])
            assertEquals("1路", params["LINENAME"])
            assertEquals(direction, params["DIRECTION"])
            val response = when (params["CMD"]) {
                "103" -> fixtureText(lineFile)
                "104" -> {
                    vehicleRequests++
                    assertEquals(reference.toString(), params["STATIONORDER"])
                    fixtureText(file)
                }
                else -> error("不应发出其他指令")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }).use { http ->
            val repo = fixtureRepository(http)
            val route = repo.lineDetail(city, "1路", direction)
            assertEquals(if (direction == "1") 29 else 28, route.stations.size)
            val result = repo.vehicles(city, route, route.stations.single { it.order == reference })
            assertEquals(1, vehicleRequests)
            assertEquals(0, result.hasReal)
            assertEquals(0, result.runState)
            assertTrue(result.vehicles.isEmpty())
            assertTrue(result.arrivals.isEmpty())
            // speedlist 非空且全为 450，单位/含义未验证，不能拿来冒充逐车 ETA。
            assertTrue(result.segments.items().isNotEmpty())
            assertTrue(result.segments.items().all { it.d("speed") == 450.0 })
        }
    }
}

class Bus夹具完整性测试 {
    @Test fun `一百份响应均与来源清单的字节数和摘要相符`() {
        val responses = fixture("fixture-manifest.json").list("responses")
        assertEquals(100, responses.size)
        for (entry in responses) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/bus-fixtures/${entry.s("fileName")}")).use { it.readBytes() }
            assertEquals(entry.s("fileName"), entry.i("bytes"), bytes.size)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(entry.s("fileName"), entry.s("sha256"), digest)
        }
    }

    @Test fun `历史冻结期望覆盖全部响应且零速未知仅为第十二轮旧口径`() {
        val manifest = fixture("fixture-manifest.json").list("responses")
        val expected = fixture("vehicle-expectations.json")
        assertEquals(manifest.filter { it.s("interface") == "busList" }.map { it.s("fileName") }.toSet(),
            expected.list("samples").map { it.s("file") }.toSet())
        assertEquals(manifest.filter { it.s("interface") == "cmd104" }.map { it.s("fileName") }.toSet(),
            expected.list("publicSamples").map { it.s("file") }.toSet())
        assertEquals(204, expected.list("samples").sumOf { it.list("vehicles").size })
        assertEquals(126, expected.list("samples").flatMap { it.list("vehicles") }.count { it.s("positionState") == "between" })
        assertEquals(78, expected.list("samples").flatMap { it.list("vehicles") }.count { it.s("positionState") == "unknown" })
        val parsed = expected.list("samples").flatMap { sample ->
            val stations = fixture(sample.s("lineDetail")).field("data").list("stationList")
                .filter { it.s("upperOrDown") == sample.s("direction") }
                .map { RouteStation(it.s("name"), it.i("stationOrder")) }
            fixture(sample.s("file")).list("data").map { parseXiamenVehicle(it, stations) }
        }
        assertEquals(126, parsed.count { it.positionState == "between" })
        assertEquals(78, parsed.count { it.positionState == "at-stop" })
        assertEquals(0, parsed.count { it.positionState == "unknown" })
    }

    @Test fun `真实车辆时间距离字段全为占位零或空值不能当作到站估算`() {
        val rows = fixture("vehicle-expectations.json").list("samples").flatMap { fixture(it.s("file")).list("data") }
        assertEquals(204, rows.size)
        for (raw in rows) {
            assertEquals(0, raw.i("dist2Next"))
            assertEquals(0, raw.i("time2Next"))
            for (field in listOf("distance2Next", "stationNumToGo", "remainingTime", "remainingDistance")) {
                assertEquals(field, JsonNull, raw.field(field))
            }
        }
    }

    @Test fun `进出站两值均有移动和零速且正速度可与静止计数同时出现`() {
        val rows = fixture("vehicle-expectations.json").list("samples").flatMap { fixture(it.s("file")).list("data") }
        assertEquals(setOf("0", "1"), rows.map { it.s("inOrOut") }.toSet())
        val stopped = mapOf("0" to 30, "1" to 48)
        val moving = mapOf("0" to 22, "1" to 104)
        for (state in stopped.keys) {
            val group = rows.filter { it.s("inOrOut") == state }
            assertEquals(stopped.getValue(state), group.count { it.d("speed") == 0.0 })
            assertEquals(moving.getValue(state), group.count { it.d("speed")!! > 0.0 })
        }
        assertEquals(28, rows.count { it.d("speed")!! > 0.0 && it.i("motionLessTime")!! > 0 })
    }
}
