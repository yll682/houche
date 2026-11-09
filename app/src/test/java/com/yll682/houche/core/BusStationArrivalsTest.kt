package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 全部是协议合成用例，用于回归路径与状态；不是 2026-11-08 现场响应。 */
class BusStationArrivalsTest {
    private val city = City("11", "厦门市", "0592")
    private fun client(reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine { reply(it) })
    private fun MockRequestHandleScope.json(value: String) = respond(value, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    private fun repository(http: HttpClient, now: () -> Long = { 0L }) = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), ZsgjClient(http), now)
    private fun native(data: String, v2: Boolean = false) = """{"success":true,"code":${if (v2) 200 else 0},"data":$data}"""
    private fun detail(number: String = "A", relatives: String = "\"A\"", lines: String = "", lat: Int = 24) =
        """{"name":"测试站","stationNo":"$number","gcjLat":$lat,"gcjLng":118,"relativeStationNoList":[$relatives],"lineList":[$lines]}"""
    private fun summary(name: String = "M18", direction: String = "1") = """{"lineName":"$name","upperOrDownInLine":"$direction","stationOrderInLine":3}"""
    private fun arrival(name: String = "M18", direction: String = "1", remaining: Int = 3) =
        """{"lineName":"$name","upDown":"$direction","carNo":"测试车","remainNum":$remaining,"remainDist":300,"endStationName":"终点"}"""
    private fun HttpRequestData.path() = url.encodedPath.substringAfterLast('/')

    @Test fun `浅站和详情共用规范参数查询全部编号且保留站台来源`() = runBlocking {
        val sent = ConcurrentLinkedQueue<HttpRequestData>()
        client { request ->
            sent += request
            when (request.path()) {
                "exactStationDetail" -> {
                    val number = request.url.parameters["stationNo"]!!
                    json(native(detail(number, "\"A\",\"B\",\"A\"", summary(if (number == "A") "M18" else "M26"), if (number == "A") 24 else 25)))
                }
                "stationLineBus" -> {
                    val number = request.url.parameters["stationNo"]!!
                    assertEquals(if (number == "A") "24.0" else "25.0", request.url.parameters["stationLat"])
                    assertEquals("20", request.url.parameters["lineNum"])
                    assertEquals(v2Sign("stationLineBus", request.url.parameters.entries().associate { it.key to it.value.single() }, request.headers["timestamp"]!!), request.headers["sign"])
                    json(native("[${arrival(if (number == "A") "M18" else "M26")}]", true))
                }
                else -> json("""{"status":1,"data":[]}""")
            }
        }.use { http ->
            val repo = repository(http)
            val shallow = Station("测试站", "A", lat = 1.0, lng = 2.0, distance = 50.0, platformNumbers = listOf("A", "B"))
            val home = repo.stationArrivals(city, shallow)
            assertEquals(StationArrivalsStatus.READY, home.status)
            assertEquals(setOf("M18", "M26"), home.station.lines.map { it.name }.toSet())
            assertEquals(setOf("A", "B"), home.arrivals.map { it.platformNumber }.toSet())
            assertEquals(50.0, home.station.distance)
            assertEquals(2, home.station.platforms.size)
            val count = sent.size
            assertEquals(home, repo.stationArrivals(city, home.station))
            assertEquals(count, sent.size)
            assertEquals(2, sent.count { it.path() == "stationLineBus" })
            assertEquals(2, sent.count { it.path() == "exactStationDetail" })
        }
    }

    @Test fun `补充时间失败保留原生车辆并立即重试而不重复核心请求`() = runBlocking {
        val core = AtomicInteger()
        val times = AtomicInteger()
        client { request -> when (request.path()) {
            "exactStationDetail" -> json(native(detail(lines = summary())))
            "stationLineBus" -> { core.incrementAndGet(); json(native("[${arrival()}]", true)) }
            else -> if (times.incrementAndGet() == 1) json("""{"status":0}""") else json("""{"status":1,"data":[{"lineName":"M18","upperOrDown":"1","neardis":"2分钟"}]}""")
        } }.use { http ->
            val repo = repository(http)
            val first = repo.stationArrivals(city, Station("测试站", "A"))
            assertEquals(StationArrivalsStatus.PARTIAL, first.status)
            assertEquals(3, first.arrivals.single().remainingStations)
            assertTrue(first.errors.isNotEmpty())
            val retry = repo.stationArrivals(city, first.station)
            assertEquals(StationArrivalsStatus.READY, retry.status)
            assertEquals("2分钟", retry.arrivals.single().timeText)
            assertEquals(1, core.get())
            assertEquals(2, times.get())
        }
    }

    @Test fun `一个站台核心失败仍可用其他台且失败不缓存`() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        client { request -> when (request.path()) {
            "exactStationDetail" -> json(native(detail(request.url.parameters["stationNo"]!!, "\"A\",\"B\"", summary())))
            "stationLineBus" -> {
                val number = request.url.parameters["stationNo"]!!
                val count = calls.computeIfAbsent(number) { AtomicInteger() }.incrementAndGet()
                if (number == "B" && count == 1) json("""{"code":500,"success":false}""") else json(native("[${arrival()}]", true))
            }
            else -> json("""{"status":1,"data":[]}""")
        } }.use { http ->
            val repo = repository(http)
            val first = repo.stationArrivals(city, Station("测试站", "A"))
            assertEquals(StationArrivalsStatus.PARTIAL, first.status)
            assertEquals("A", first.arrivals.single().platformNumber)
            assertEquals("B", first.unreportedLines.single().platformNumber)
            val retry = repo.stationArrivals(city, first.station)
            assertEquals(StationArrivalsStatus.READY, retry.status)
            // 同线、同方向、同车号在不同站台的记录仍分别保留。
            assertEquals(2, retry.arrivals.size)
            assertEquals(1, calls["A"]!!.get())
            assertEquals(2, calls["B"]!!.get())
        }
    }

    @Test fun `合法空数组与缺失空值错误类型分别给空记录和查询失败`() = runBlocking {
        for (data in listOf("null", "{}", "\"bad\"", "[null]", "[{}]", "[42]", null)) {
            client { request -> when (request.path()) {
                "exactStationDetail" -> json(native(detail()))
                else -> json(if (data == null) """{"success":true,"code":200}""" else native(data, true))
            } }.use { http ->
                val result = repository(http).stationArrivals(city, Station("测试站", "A"))
                assertEquals("malformed $data", StationArrivalsStatus.UNAVAILABLE, result.status)
                assertTrue(result.errors.isNotEmpty())
            }
        }
        client { request -> json(native(if (request.path() == "exactStationDetail") detail() else "[]", request.path() == "stationLineBus")) }.use { http ->
            val result = repository(http).stationArrivals(city, Station("测试站", "A"))
            assertEquals(StationArrivalsStatus.EMPTY, result.status)
            assertTrue(result.arrivals.isEmpty())
        }
    }

    @Test fun `已知线路缺报保持未知不捏造等待车辆`() = runBlocking {
        client { request -> json(native(if (request.path() == "exactStationDetail") detail(lines = summary()) else "[]", request.path() == "stationLineBus")) }.use { http ->
            val result = repository(http).stationArrivals(city, Station("测试站", "A"))
            assertEquals(StationArrivalsStatus.PARTIAL, result.status)
            assertTrue(result.arrivals.isEmpty())
            assertEquals("M18", result.unreportedLines.single().line.name)
        }
    }

    @Test fun `无编号先按名称解析并使用上游规范编号和坐标`() = runBlocking {
        client { request -> when (request.path()) {
            "stationDetail" -> json(native(detail("CANON", "\"CANON\"", lat = 25)))
            "stationLineBus" -> {
                assertEquals("CANON", request.url.parameters["stationNo"])
                assertEquals("25.0", request.url.parameters["stationLat"])
                json(native("[]", true))
            }
            else -> error("unexpected ${request.path()}")
        } }.use { http ->
            val result = repository(http).stationArrivals(city, Station("测试站"))
            assertEquals("CANON", result.station.number)
            assertEquals(StationArrivalsStatus.EMPTY, result.status)
        }
    }

    @Test fun `详情返回的规范编号优先于请求编号`() = runBlocking {
        val core = AtomicInteger()
        client { request -> when (request.path()) {
            "exactStationDetail" -> {
                assertEquals("ALIAS", request.url.parameters["stationNo"])
                json(native(detail("CANON", "\"CANON\"")))
            }
            else -> {
                core.incrementAndGet()
                assertEquals("CANON", request.url.parameters["stationNo"])
                json(native("[]", true))
            }
        } }.use { http ->
            val result = repository(http).stationArrivals(city, Station("测试站", "ALIAS"))
            assertEquals("CANON", result.station.number)
            assertEquals(1, core.get())
        }
    }

    @Test fun `同时活跃的首页和详情查询共享全部在途请求`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        client { request ->
            count.incrementAndGet()
            when (request.path()) {
                "exactStationDetail" -> {
                    entered.complete(Unit)
                    release.await()
                    json(native(detail(lines = summary())))
                }
                "stationLineBus" -> { delay(30); json(native("[${arrival()}]", true)) }
                else -> { delay(30); json("""{"status":1,"data":[]}""") }
            }
        }.use { http ->
            val repo = repository(http)
            withTimeout(5_000) {
                val home = async { repo.stationArrivals(city, Station("测试站", "A", 1.0, 2.0)) }
                entered.await()
                val station = async { repo.stationArrivals(city, Station("测试站", "A", 24.0, 118.0)) }
                delay(50)
                assertEquals(1, count.get())
                release.complete(Unit)
                val results = awaitAll(home, station)
                assertTrue(results.all { it.status == StationArrivalsStatus.READY })
                assertEquals(results.first().arrivals, results.last().arrivals)
                assertEquals(3, count.get())
            }
        }
    }

    @Test fun `原生详情缓存五分钟而核心十五秒且请求线路上限覆盖全部已知线路`() = runBlocking {
        val now = AtomicLong()
        val details = AtomicInteger()
        val core = AtomicInteger()
        val lines = (1..25).joinToString(",") { summary("$it") }
        client { request -> when (request.path()) {
            "exactStationDetail" -> { details.incrementAndGet(); json(native(detail(lines = lines))) }
            else -> {
                core.incrementAndGet()
                assertEquals("25", request.url.parameters["lineNum"])
                json(native("[]", true))
            }
        } }.use { http ->
            val repo = repository(http, now::get)
            val stop = Station("测试站", "A")
            repo.stationArrivals(city, stop)
            now.set(15_000_000_000L)
            repo.stationArrivals(city, stop)
            assertEquals(1, details.get())
            assertEquals(2, core.get())
            now.set(300_000_000_000L)
            repo.stationArrivals(city, stop)
            assertEquals(2, details.get())
            assertEquals(3, core.get())
        }
    }

    @Test fun `聚合站点的详情过期失败时降级仍保留各台线路归属`() = runBlocking {
        val now = AtomicLong()
        client { request -> when (request.path()) {
            "exactStationDetail" -> {
                if (now.get() > 0) json("""{"success":false,"code":500}""")
                else {
                    val number = request.url.parameters["stationNo"]!!
                    json(native(detail(number, "\"A\",\"B\"", summary(if (number == "A") "M18" else "M26"))))
                }
            }
            "stationLineBus" -> json(native("[${arrival(if (request.url.parameters["stationNo"] == "A") "M18" else "M26")}]", true))
            else -> json("""{"status":1,"data":[]}""")
        } }.use { http ->
            val repo = repository(http, now::get)
            val first = repo.stationArrivals(city, Station("测试站", "A"))
            assertEquals(StationArrivalsStatus.READY, first.status)
            now.set(300_000_000_000L)
            val fallback = repo.stationArrivals(city, first.station)
            assertEquals(StationArrivalsStatus.PARTIAL, fallback.status)
            assertEquals(2, fallback.arrivals.size)
            assertTrue(fallback.unreportedLines.isEmpty())
            assertEquals(listOf("M18"), fallback.station.platforms.single { it.number == "A" }.lines.map { it.name })
            assertEquals(listOf("M26"), fallback.station.platforms.single { it.number == "B" }.lines.map { it.name })
        }
    }

    @Test fun `全仓站台请求共用三个并发许可`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        client { request ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                delay(30)
                if (request.path() == "exactStationDetail") {
                    val number = request.url.parameters["stationNo"]!!
                    json(native(detail(number, "\"$number\"")))
                } else json(native("[]", true))
            } finally { active.decrementAndGet() }
        }.use { http ->
            val repo = repository(http)
            withTimeout(5_000) {
                (1..8).map { async { repo.stationArrivals(city, Station("测试站", "P$it")) } }.awaitAll()
            }
            assertEquals(3, peak.get())
        }
    }

    @Test fun `发起页取消不取消活跃等待页且取消请求不入缓存`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        client {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                awaitCancellation()
            } else json(native(detail()))
        }.use { http ->
            val repo = repository(http)
            withTimeout(5_000) {
                val owner = async { repo.stationDetail(city, "测试站", "A") }
                entered.await()
                val waiter = async { repo.stationDetail(city, "测试站", "A", 1.0, 2.0) }
                delay(50)
                assertEquals(1, calls.get())
                owner.cancelAndJoin()
                assertEquals("A", waiter.await().number)
                assertEquals(2, calls.get())
            }
        }
    }

    @Test fun `其他城市查询所有明确坐标站台并保留各自来源`() = runBlocking {
        val queried = ConcurrentLinkedQueue<String>()
        client { request ->
            val form = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (form.contains("CMD=209")) json("""{"status":1,"info":[{"name":"测试站","lat":1,"lon":2},{"name":"测试站","lat":3,"lon":4}]}""")
            else {
                queried += form
                json("""{"status":1,"data":[{"lineName":"1路","upperOrDown":"1","to":"终点","neartext":"2站","neardis":"3分钟"}]}""")
            }
        }.use { http ->
            val result = repository(http).stationArrivals(City("0", "北京市", "010"), Station("测试站", lat = 1.0, lng = 2.0))
            assertEquals(StationArrivalsStatus.READY, result.status)
            assertEquals(setOf(1.0, 3.0), result.arrivals.map { it.platformLat }.toSet())
            assertEquals(2, queried.size)
        }
    }

    @Test fun `站点页仅查询所选坐标站台`() = runBlocking {
        val queried = ConcurrentLinkedQueue<String>()
        client { request ->
            val form = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (form.contains("CMD=209")) json("""{"status":1,"info":[{"name":"测试站","lat":1,"lon":2},{"name":"测试站","lat":3,"lon":4}]}""")
            else {
                queried += form
                val name = if (form.contains("MYLAT=3.0")) "2路" else "1路"
                json("""{"status":1,"data":[{"lineName":"$name","upperOrDown":"1","to":"终点"}]}""")
            }
        }.use { http ->
            val result = repository(http).stationPlatformArrivals(City("0", "北京市", "010"), Station("测试站", lat = 3.0, lng = 4.0))
            assertEquals(StationArrivalsStatus.READY, result.status)
            assertEquals(listOf("2路"), result.arrivals.map { it.name })
            assertEquals(listOf("2路"), result.station.lines.map { it.name })
            assertEquals(2, result.station.platforms.size)
            assertTrue(queried.all { it.contains("MYLAT=3.0") })
        }
    }
}
