package com.yll682.houche

import com.yll682.houche.core.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RepositoryTest {
    private fun http(reply: (Request) -> String) = HttpClient(OkHttp) {
        engine { config { addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("成功")
                .body(reply(chain.request()).toResponseBody("application/json".toMediaType())).build()
        } } }
    }
    @Test fun citiesAndNormalization() = runBlocking {
        val file = sequenceOf(File("src/main/assets/cities.json"), File("app/src/main/assets/cities.json")).first { it.exists() }
        var reads = 0
        val cities = Cities { reads++; file.inputStream() }
        assertEquals(575, cities.all().size)
        assertTrue(cities.search("XIAMEN").any { it.isXiamen })
        assertTrue(cities.grouped().containsKey("其他"))
        assertEquals("北京市", cities.defaultCity("0").name)
        assertEquals(1, reads)
        assertFalse(City("x", "厦门市", "").isXiamen)
        assertEquals(mapOf("a" to "", "b" to "", "c" to "＜站＞"), normalizeParams(mapOf("a" to null, "b" to "null", "c" to "<站>")))
    }
    @Test fun strictArguments() = runBlocking {
        val client = ZsgjClient()
        for (params in listOf(mapOf("CITYNAME" to "北京市"), mapOf("CITYNAME" to "北京市", "KEYWORD" to "1路", "CITYKEY" to ""))) {
            try { client.publicQuery("114", params); fail("应拒绝参数集合不符") }
            catch (error: BusException) { assertEquals(BusException.Kind.ARGUMENT, error.kind) }
        }
    }
    @Test fun arrivalsAndVehicleSorting() {
        val raw = Json.parseToJsonElement("""{"routeOnStationRTimeInfoList":[
            {"busNumber":"","busToStationCount":"-1","planTime":"07:00"},
            {"busNumber":"远车","busToStationCount":"3","busToStationDistance":"900"},
            {"busNumber":"近车","busToStationCount":1,"busToStationDistance":100}]}""")
        val arrivals = publicArrivals(raw)
        assertEquals(listOf("近车", "远车", ""), arrivals.map { it.plate })
        assertTrue(arrivals.last().waiting)
        assertNull(arrivals.last().distance)
        assertEquals("07:00", arrivals.last().nextDeparture)
        val vehicles = listOf(Vehicle("未知", 2, "unknown"), Vehicle("已过", 6, "at-stop"), Vehicle("途中", 4, "between"), Vehicle("到站", 4, "at-stop"))
        assertEquals(listOf("到站", "途中", "已过", "未知"), sortVehicles(vehicles, 5).map { it.plate })
    }
    @Test fun cacheAndNativeMapping() = runBlocking {
        var now = 0L
        val calls = AtomicInteger()
        val http = http { request ->
            calls.incrementAndGet()
            assertEquals("POST", request.method)
            assertEquals("91路", request.url.queryParameter("busLineName"))
            assertEquals("android", request.header("client-type"))
            """{"success":true,"code":0,"data":{"id":161,"name":"91路","suStation":"起点","euStation":"终点","suTime":"06:00","euTime":"22:00","comments":"票价","stationList":[{"id":"b","name":"第二站","stationOrder":"2","upperOrDown":"1","gcjLat":"24.5","gcjLng":"118.1"},{"id":"a","name":"第一站","stationOrder":1,"upperOrDown":1},{"name":"反向","stationOrder":1,"upperOrDown":2}]}}"""
        }
        try {
            val repository = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), ZsgjClient(http)) { now }
            val city = City("11", "厦门市", "0592")
            val result = repository.lineDetail(city, "91路", "1")
            assertEquals(listOf("第一站", "第二站"), result.stations.map { it.name })
            assertTrue(result.showTimetable)
            assertEquals("161", result.id)
            assertEquals(result, repository.lineDetail(city, "91路", "1"))
            assertEquals(1, calls.get())
            now = 15_000_000_000L
            repository.lineDetail(city, "91路", "1")
            assertEquals(2, calls.get())
            repository.clearCache()
            repository.lineDetail(city, "91路", "1")
            assertEquals(3, calls.get())
        } finally { http.close() }
    }
    @Test fun nearbyEnrichmentIsConcurrentAndIsolatesFailure() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val completed = AtomicInteger()
        val http = http { request ->
            val form = request.body!!.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }
            if (form.contains("CMD=106")) """{"status":1,"data":[{"name":"A","lat":1,"lon":2,"dis":30},{"name":"B","lat":1,"lon":2,"dis":-1},{"name":"C","lat":1,"lon":2,"dis":10},{"name":"D","lat":1,"lon":2,"dis":20}]}"""
            else {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                // 等待其他站查询进入，验证实际并发；限定时间避免失败挂死。
                val deadline = System.nanoTime() + 2_000_000_000L
                while (peak.get() < 3 && System.nanoTime() < deadline) Thread.yield()
                active.decrementAndGet()
                completed.incrementAndGet()
                if (form.contains("STATIONNAME=B")) """{"status":0}""" else """{"status":"1","data":[{"lineName":"1路","upperOrDown":"1","from":"起点","to":"终点","stationOrder":"2"}]}"""
            }
        }
        try {
            val result = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), ZsgjClient(http)).nearby(City("0", "北京市", "010"), 1.0, 2.0)
            assertEquals(3, peak.get())
            assertEquals(3, completed.get())
            assertEquals(listOf("C", "D", "A", "B"), result.map { it.name })
            assertEquals("1路", result.first().lines.first().name)
            assertNotNull(result.last().linesError)
            assertTrue(result[1].lines.isEmpty())
        } finally { http.close() }
    }
}
