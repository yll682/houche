package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** 真实密文用于协议回归，构造变体用于覆盖缺字段、空列车和网络失败。 */
class MetroRealtimeTest {
    private val remote = MetroRealtimeRemote()
    private val sample get() = MetroFixtures.realtime
    private val plaintext get() = BusNetwork.json.parseToJsonElement(sample.s("plaintext")) as JsonObject

    @Test fun `真实实时密文解出样本明文且与原始响应一致`() {
        val outer = BusNetwork.json.parseToJsonElement(sample.s("rawResponse"))
        assertEquals(sample.s("cipherHexUpper"), outer.s("responseData"))
        assertEquals(sample.s("plaintext"), MetroGateway.decrypt(sample.s("cipherHexUpper")))
    }

    @Test fun `真实响应保留两个方向三班车与秒数位置和本站窗口`() {
        val result = remote.parse(sample.s("rawResponse"), "01000000", "01130000")
        assertTrue(result.note, result.online)
        assertEquals("01000000", result.lineCode)
        assertEquals("01130000", result.stationCode)
        assertEquals("高崎", result.stationName)
        assertEquals(listOf("镇海路", "岩内"), result.directions.map { it.origin })
        assertEquals(listOf("岩内", "镇海路"), result.directions.map { it.destination })
        assertEquals(listOf(3, 3), result.directions.map { it.trains.size })
        val direction = result.directions.first()
        assertEquals(plaintext.list("data").first().s("transStartTime"), direction.startTime)
        assertEquals(plaintext.list("data").first().s("transEndTime"), direction.endTime)
        assertEquals(0, direction.delayMinutes)
        val train = direction.trains.first()
        assertEquals(322, train.arriveSeconds)
        assertEquals("01110000", train.nextStationCode)
        assertEquals("火炬园", train.nextStationName)
        assertEquals(0.31, train.scale, 0.000001)
        assertEquals(1, train.arrivingFlag)
        assertFalse(train.arriving)
        assertTrue(train.approaching)
    }

    @Test fun `高崎在两方向站序下标不同仍分别剩二站和一站`() {
        val result = remote.parse(sample.s("rawResponse"))
        val first = result.directions.first()
        val second = result.directions.last()
        assertEquals(12, first.stationCodes.indexOf(result.stationCode))
        assertEquals(11, second.stationCodes.indexOf(result.stationCode))
        assertEquals(2, result.remainingStops(first, first.trains.first()))
        assertEquals(1, result.remainingStops(second, second.trains.first()))
    }

    @Test fun `方向数组反转仍以各自终点和站序解释列车`() {
        val root = plaintext
        val reversed = JsonObject(root + ("data" to JsonArray(root.list("data").reversed())))
        val result = remote.parse(MetroFixtures.envelope(reversed.toString()))
        assertEquals(listOf("岩内", "镇海路"), result.directions.map { it.origin })
        assertEquals(listOf("镇海路", "岩内"), result.directions.map { it.destination })
        assertEquals(listOf(1, 2), result.directions.map { result.remainingStops(it, it.trains.first()) })
    }

    @Test fun `真实六班列车按全站证实的方向比例定位`() {
        val result = remote.parse(sample.s("rawResponse"))
        // 样本下一站下标分别为 10、7、4 和 10、8、5；不能共用正向站序。
        val expectedPositions = listOf(listOf(9.31, 6.54, 3.59), listOf(9.62, 7.05, 4.11))
        expectedPositions.forEachIndexed { directionIndex, positions ->
            val direction = result.directions[directionIndex]
            positions.forEachIndexed { trainIndex, position ->
                assertEquals("方向 $directionIndex 第 $trainIndex 班", position, direction.positionOf(direction.trains[trainIndex]), 0.0)
            }
        }
    }

    @Test fun `进站原值透传且不把未知状态推断为已到站`() {
        val direction = minimalDirection()
        val train = direction.list("transTimes").single() as JsonObject
        val expectedStates = mapOf(-1 to (false to false), 0 to (false to false), 1 to (false to true), 2 to (true to false), 3 to (false to false))
        expectedStates.forEach { (flag, states) ->
            val changed = JsonObject(train + ("arrivingFlag" to JsonPrimitive(flag)))
            val body = JsonObject(direction + ("transTimes" to JsonArray(listOf(changed))))
            val parsed = remote.parse(response(JsonArray(listOf(body)))).directions.single().trains.single()
            assertEquals(flag, parsed.arrivingFlag)
            assertEquals("标记 $flag 的进站状态", states.first, parsed.arriving)
            assertEquals("标记 $flag 的接近状态", states.second, parsed.approaching)
        }
        val missing = JsonObject(train - "arrivingFlag")
        val body = JsonObject(direction + ("transTimes" to JsonArray(listOf(missing))))
        val parsed = remote.parse(response(JsonArray(listOf(body)))).directions.single().trains.single()
        assertNull(parsed.arrivingFlag)
        assertFalse(parsed.arriving)
        assertFalse(parsed.approaching)
    }

    @Test fun `下一站为本站记零站而任一站码未知记负一`() {
        val result = remote.parse(response(), "01000000")
        val direction = result.directions.single()
        val train = direction.trains.single()
        assertEquals(0, result.remainingStops(direction, train.copy(nextStationCode = result.stationCode)))
        assertEquals(-1, result.copy(stationCode = "未知").remainingStops(direction, train))
        assertEquals(-1, result.remainingStops(direction, train.copy(nextStationCode = "未知")))
        assertEquals(-1, result.copy(stationCode = "").remainingStops(direction.copy(stationCodes = listOf("")), train))
        assertEquals(-1, result.remainingStops(direction.copy(stationCodes = listOf("", result.stationCode)), train.copy(nextStationCode = "")))
    }

    @Test fun `整批未知空和缺失flag保留原状态且已知下一站仍有车标位置`() {
        val direction = minimalDirection()
        val original = direction.list("transTimes").single() as JsonObject
        val trains = listOf(JsonPrimitive(3), JsonNull, null).mapIndexed { index, flag ->
            val values = original + mapOf(
                "arriveTime" to JsonPrimitive(60 + index * 60),
                "arriveNextStationCode" to JsonPrimitive("01020000"),
                "arriveNextStationScale" to JsonPrimitive(0.3 + index * 0.1)
            )
            JsonObject(if (flag == null) values - "arrivingFlag" else values + ("arrivingFlag" to flag))
        }
        val body = JsonObject(direction + ("transTimes" to JsonArray(trains)))
        val parsed = remote.parse(response(JsonArray(listOf(body)))).directions.single()
        assertEquals(3, parsed.trains.size)
        assertEquals(listOf(3, null, null), parsed.trains.map { it.arrivingFlag })
        parsed.trains.forEachIndexed { index, train ->
            assertEquals(0.3 + index * 0.1, train.scale, 0.0)
            assertEquals(0.3 + index * 0.1, parsed.positionOf(train), 0.0)
            assertFalse(train.arriving)
            assertFalse(train.approaching)
        }
    }

    @Test fun `站码缺失保留站序空位而不缩短剩余站数`() {
        val direction = minimalDirection()
        val stations = direction.list("wayStations").toMutableList()
        stations.add(1, JsonObject(mapOf("name" to JsonPrimitive("缺码站"))))
        val result = remote.parse(response(JsonArray(listOf(JsonObject(direction + ("wayStations" to JsonArray(stations)))))))
        val parsed = result.directions.single()
        assertEquals(listOf("01010000", "", "01020000"), parsed.stationCodes)
        assertEquals(2, result.remainingStops(parsed, parsed.trains.single()))
    }

    @Test fun `空首末班表示该方向没有班次`() {
        assertEquals(MetroServiceState.NO_SERVICE, metroServiceState("", "", 720))
    }

    @Test fun `只有首班时刻只判断是否未到首班`() {
        assertEquals(MetroServiceState.NOT_STARTED, metroServiceState("06:00", "", 359))
        assertEquals(MetroServiceState.RUNNING, metroServiceState("06:00", "", 360))
        assertEquals(MetroServiceState.RUNNING, metroServiceState("06:00", "", 1439))
    }

    @Test fun `只有末班时刻只判断是否已过末班`() {
        assertEquals(MetroServiceState.RUNNING, metroServiceState("", "23:24", 0))
        assertEquals(MetroServiceState.RUNNING, metroServiceState("", "23:24", 1404))
        assertEquals(MetroServiceState.FINISHED, metroServiceState("", "23:24", 1405))
    }

    @Test fun `完整窗口包含首末班边界且区分前后时刻`() {
        val expected = mapOf(
            359 to MetroServiceState.NOT_STARTED,
            360 to MetroServiceState.RUNNING,
            720 to MetroServiceState.RUNNING,
            1404 to MetroServiceState.RUNNING,
            1405 to MetroServiceState.FINISHED
        )
        expected.forEach { (now, state) -> assertEquals("当前分钟 $now", state, metroServiceState("06:00", "23:24", now)) }
        assertEquals(MetroServiceState.RUNNING, metroServiceState("00:00", "23:59", 0))
        assertEquals(MetroServiceState.RUNNING, metroServiceState("00:00", "23:59", 1439))
    }

    @Test fun `非法时间不猜测运营窗口而可解析的一端仍生效`() {
        for (invalid in listOf("无效", "6:00", "24:00", "23:60", "-1:00", "06:0x", "06:00 ", "  ", "０６:００")) {
            assertEquals(invalid, MetroServiceState.RUNNING, metroServiceState(invalid, "", 0))
            assertEquals(invalid, MetroServiceState.RUNNING, metroServiceState("", invalid, 1439))
            assertEquals(invalid, MetroServiceState.RUNNING, metroServiceState(invalid, invalid, 720))
            assertEquals(invalid, MetroServiceState.NOT_STARTED, metroServiceState("06:00", invalid, 359))
            assertEquals(invalid, MetroServiceState.FINISHED, metroServiceState(invalid, "23:24", 1405))
        }
    }

    @Test fun `秒数向上取整至少一分钟且极值不溢出`() {
        val cases = mapOf(Int.MIN_VALUE to 1, -1 to 1, 0 to 1, 1 to 1, 59 to 1, 60 to 1, 61 to 2, 120 to 2, 322 to 6, Int.MAX_VALUE to 35791395)
        cases.forEach { (seconds, minutes) -> assertEquals("秒数 $seconds", minutes, minutesUntil(seconds)) }
    }

    @Test fun `在线线路保留八位码而离线线路可从首站推导`() {
        val lines = MetroRemote().parse(MetroFixtures.sample.s("rawResponse")).lines
        assertEquals(listOf("01000000", "04000000"), lines.map { it.apiCode })
        val line = MetroLine("1", "地铁一号线", stations = listOf(MetroStation(1, "镇海路", code = "01010000")))
        assertEquals("01000000", line.realtimeCode())
        assertEquals("02000000", line.copy(apiCode = "02000000").realtimeCode())
        assertEquals("", line.copy(stations = emptyList()).realtimeCode())
        assertEquals("", line.copy(stations = listOf(MetroStation(1, "缺码站"))).realtimeCode())
    }

    @Test fun `真实内置快照兼容显式线路码且缺省时可从站码推导`() {
        val asset = sequenceOf(File("src/main/assets/metro.json"), File("app/src/main/assets/metro.json")).first { it.isFile }
        val network = BusNetwork.json.decodeFromString<MetroNetwork>(asset.readText())
        assertEquals(mapOf("1" to "", "2" to "", "3" to "", "4" to "04000000", "6" to "06000000"), network.lines.associate { it.id to it.apiCode })
        val expected = listOf("01000000", "02000000", "03000000", "04000000", "06000000")
        assertEquals(expected, network.lines.map { it.realtimeCode() })
        assertEquals("旧快照即使没有显式线路码仍能推导", expected, network.lines.map { it.copy(apiCode = "").realtimeCode() })
    }

    @Test fun `内层报错非 JSON 与空方向一律不可用且带原因`() {
        val cases = listOf(
            "<html>502 Bad Gateway</html>",
            MetroFixtures.envelope("""{"code":10007,"msg":"线路编码错误"}"""),
            response(JsonArray(emptyList())),
            MetroFixtures.envelope("不是 JSON"),
            """{"responseData":"00"}""",
            "{}", "[]", "null"
        )
        cases.forEach { body ->
            val result = remote.parse(body)
            assertFalse(body, result.online)
            assertTrue(body, result.note.isNotBlank())
        }
        assertEquals("实时接口报错：10007 线路编码错误", remote.parse(cases[1]).note)
    }

    @Test fun `外层显式拒绝与内层缺失无效状态码都不能在线成功`() {
        assertEquals("网关拒绝：10000 无效请求", remote.parse("""{"code":10000,"msg":"无效请求"}""").note)
        for (code in listOf(JsonNull, JsonArray(emptyList()), JsonPrimitive("无效"))) {
            val root = JsonObject(mapOf("code" to code, "data" to JsonArray(listOf(minimalDirection()))))
            assertFalse(remote.parse(MetroFixtures.envelope(root.toString())).online)
        }
        assertFalse(remote.parse(MetroFixtures.envelope("""{"data":[]}""")).online)
        assertFalse(remote.parse(response(JsonNull)).online)
        assertFalse(remote.parse(response(JsonObject(emptyMap()))).online)
    }

    @Test fun `暂无列车仍保留方向运营窗口且坏列车条目不会伪造秒数`() {
        val direction = minimalDirection()
        val empty = JsonObject(direction + ("transTimes" to JsonArray(listOf(JsonNull, JsonPrimitive(1), JsonObject(emptyMap())))))
        val result = remote.parse(response(JsonArray(listOf(JsonNull, empty))))
        assertTrue(result.note, result.online)
        assertTrue(result.directions.single().trains.isEmpty())
        assertEquals("06:00", result.directions.single().startTime)
    }

    @Test fun `可选字段缺失安全兜底且进站原值与特殊位置均保留`() {
        val direction = minimalDirection()
        val train = JsonObject(mapOf("arriveTime" to JsonPrimitive(1), "arrivingFlag" to JsonPrimitive(-1), "specialPosition" to JsonPrimitive(2), "specialPositionDesc" to JsonPrimitive("特殊位置")))
        val body = JsonObject(direction.filterKeys { it !in setOf("transStartTime", "transEndTime", "arriveTimeDelay") } + ("transTimes" to JsonArray(listOf(train))))
        val result = remote.parse(response(JsonArray(listOf(body))))
        val parsed = result.directions.single()
        assertEquals("", parsed.startTime)
        assertEquals("", parsed.endTime)
        assertEquals(0, parsed.delayMinutes)
        assertEquals(-1, parsed.trains.single().arrivingFlag)
        assertFalse(parsed.trains.single().arriving)
        assertFalse(parsed.trains.single().approaching)
        assertEquals(0.0, parsed.trains.single().scale, 0.0)
        assertEquals(2, parsed.trains.single().specialPosition)
        assertEquals("特殊位置", parsed.trains.single().specialPositionDesc)
        val ordinary = remote.parse(response()).directions.single().trains.single()
        assertFalse(ordinary.arriving)
        assertEquals(0, ordinary.specialPosition)
        assertEquals("", ordinary.specialPositionDesc)
    }

    @Test fun `MockEngine 看到必需空值头查询参数和不含 query 的签名`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        client { request ->
            requests += request
            respond(response())
        }.use { http ->
            val result = MetroRealtimeRemote(http).latest("01000000", "01020000")
            assertTrue(result.note, result.online)
        }
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("app.i-amtr.com", request.url.host)
        assertEquals("/iets-web-app/pub/transTime/getTransTimes", request.url.encodedPath)
        assertEquals("01000000", request.url.parameters["lineCode"])
        assertEquals("01020000", request.url.parameters["stationCode"])
        for (header in listOf("token", "ticket-log", "device-model", "area-code", "longitude", "latitude")) {
            assertEquals(header, listOf(""), request.headers.getAll(header))
        }
        mapOf("client-id" to "1", "client-type" to "android", "client-key" to "AMTR-IETS-0002", "app-version" to "4.0.9", "version" to "3.0", "Accept-Encoding" to "gzip").forEach { (key, value) ->
            assertEquals(key, value, request.headers[key])
        }
        val deviceId = requireNotNull(request.headers["device-id"])
        val timestamp = requireNotNull(request.headers["timestamp"])
        assertEquals(MetroGateway.deviceId(), deviceId)
        assertEquals(MetroGateway.signature(deviceId, timestamp), request.headers["sign"])
    }

    @Test fun `省略站码时不发送该参数并读取响应选择的默认站`() = runBlocking {
        client { request ->
            assertEquals("01000000", request.url.parameters["lineCode"])
            assertNull(request.url.parameters["stationCode"])
            respond(response())
        }.use { http ->
            val result = MetroRealtimeRemote(http).latest("01000000")
            assertTrue(result.note, result.online)
            assertEquals("01020000", result.stationCode)
            assertEquals("中山公园", result.stationName)
        }
    }

    @Test fun `实时接口支持带压缩头的 gzip 响应`() = runBlocking {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(response().toByteArray(Charsets.UTF_8)) }
        client { respond(output.toByteArray(), headers = headersOf("Content-Encoding", "gzip")) }.use { http ->
            val result = MetroRealtimeRemote(http).latest("01000000")
            assertTrue(result.note, result.online)
        }
    }

    @Test fun `HTTP 错误不能被有效响应体掩盖且不隐式重试`() = runBlocking {
        for ((status, expectedRequests) in listOf(HttpStatusCode.BadRequest to 1, HttpStatusCode.TooManyRequests to 1, HttpStatusCode.ServiceUnavailable to 1)) {
            var requests = 0
            client {
                requests++
                respond(response(), status)
            }.use { http ->
                val result = MetroRealtimeRemote(http).latest("01000000", "01020000")
                assertFalse(result.online)
                assertTrue(result.note, result.note.contains("HTTP ${status.value}"))
                assertEquals("01000000", result.lineCode)
                assertEquals("01020000", result.stationCode)
                assertEquals(expectedRequests, requests)
            }
        }
    }

    @Test fun `网络异常收敛为诊断且后续调用可以恢复`() = runBlocking {
        var requests = 0
        client {
            requests++
            if (requests == 1) throw IOException("离线")
            respond(response())
        }.use { http ->
            val source = MetroRealtimeRemote(http)
            val failed = source.latest("01000000")
            assertFalse(failed.online)
            assertTrue(failed.note, failed.note.contains("IOException"))
            assertTrue(source.latest("01000000").online)
            assertEquals(2, requests)
        }
    }

    @Test fun `整体八秒超时收敛为不可用且不会无限等待`() = runBlocking {
        client {
            delay(MetroGateway.TIMEOUT_MILLIS * 2)
            respond(response())
        }.use { http ->
            val result = withTimeout(MetroGateway.TIMEOUT_MILLIS + 2_000) { MetroRealtimeRemote(http).latest("01000000") }
            assertFalse(result.online)
            assertTrue(result.note, result.note.contains("超时"))
        }
    }

    @Test fun `调用方取消仍传播以停止已离开页面的请求`() = runBlocking {
        client {
            delay(1_000)
            respond(response())
        }.use { http ->
            try {
                withTimeout(50) { MetroRealtimeRemote(http).latest("01000000") }
                fail("调用方取消不应被转换成普通失败")
            } catch (_: TimeoutCancellationException) {
                // 仅自身八秒超时转换为诊断，外部取消必须保留。
            }
        }
    }

    private fun minimalDirection(): JsonObject = BusNetwork.json.parseToJsonElement("""
        {"stationCode":"01020000","stationName":"中山公园","arriveTimeDelay":0,
         "transStartTime":"06:00","transEndTime":"23:24",
         "transTimes":[{"arriveTime":61,"arriveNextStationCode":"01010000","arriveNextStationName":"镇海路","arriveNextStationScale":0.5,"arrivingFlag":0}],
         "wayStations":[{"stationCode":"01010000","name":"镇海路"},{"stationCode":"01020000","name":"中山公园"}]}
    """.trimIndent()) as JsonObject

    private fun response(data: JsonElement = JsonArray(listOf(minimalDirection()))): String = MetroFixtures.envelope(
        JsonObject(mapOf("code" to JsonPrimitive(0), "msg" to JsonPrimitive("success"), "data" to data)).toString()
    )

    private fun client(reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(reply)) { install(HttpTimeout) }
}
