package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 「从近到远」排序的回归用例：主键是「到该站还剩几站」，
 * 没有站数的行（等待发车 / 计划发车 / 信息不全）排在有站数的行之后。
 */
class ArrivalOrderTest {

    private fun arrival(
        name: String, stations: Int? = null, distance: Double? = null, waiting: Boolean = false,
        nextDeparture: String? = null, direction: String = "1"
    ) = Arrival(remainingStations = stations, distance = distance, waiting = waiting,
        nextDeparture = nextDeparture, name = name, direction = direction)

    @Test fun `有站数的行按站数升序而不是距离`() {
        val rows = listOf(
            arrival("807路", 13, 400.0),
            arrival("891路", 3, 900.0),
            arrival("854路", 1, 2600.0),
            arrival("832路", 2, 1500.0)
        )
        assertEquals(listOf("854路", "832路", "891路", "807路"), orderArrivalsByNear(rows).map { it.name })
    }

    @Test fun `没有站数的行垫底且不把行莫名推到前面`() {
        val rows = listOf(
            // 只有距离、没有站数（信息不全）——旧实现会把它排到最前，这里必须垫底。
            arrival("859路", distance = 120.0),
            arrival("680路", waiting = true, nextDeparture = "17:20"),
            arrival("854路", 2),
            arrival("805路", 5, 3000.0)
        )
        assertEquals(listOf("854路", "805路", "680路", "859路"), orderArrivalsByNear(rows).map { it.name })
    }

    @Test fun `等待发车与计划发车按下一班时间升序`() {
        val rows = listOf(
            arrival("834路", waiting = true, nextDeparture = "18:05"),
            arrival("831路", waiting = true, nextDeparture = "17:20"),
            arrival("833路", waiting = true, nextDeparture = "07:05")
        )
        assertEquals(listOf("833路", "831路", "834路"), orderArrivalsByNear(rows).map { it.name })
    }

    @Test fun `站数相同时按距离再按线路号自然序稳定排序`() {
        val rows = listOf(
            arrival("854路", 2, 1200.0),
            arrival("891路", 2, 900.0),
            arrival("807路", 2, null),      // 没距离的排在同站数里有距离的之后
            arrival("100路", 2, null)
        )
        val once = orderArrivalsByNear(rows).map { it.name }
        assertEquals(listOf("891路", "854路", "100路", "807路"), once)
        // 同一份数据重复排序结果一致（稳定、可复现）。
        assertEquals(once, orderArrivalsByNear(rows).map { it.name })
    }

    @Test fun `线路号按自然序比较`() {
        assertTrue(naturalLineOrder.compare("9路", "10路") < 0)
        assertTrue(naturalLineOrder.compare("807路", "854路") < 0)
        assertTrue(naturalLineOrder.compare("B2路", "B10路") < 0)
        assertEquals(0, naturalLineOrder.compare("854路", "854路"))
    }

    @Test fun `线路列表取每条线最近一班排名字段且无实时数据的垫底`() {
        val lines = listOf(LineSummary("807路", "1"), LineSummary("854路", "1"), LineSummary("680路", "1"), LineSummary("702路", "2"))
        val arrivals = listOf(
            arrival("807路", 13, 400.0),
            arrival("854路", 2, 1200.0),
            arrival("680路", waiting = true, nextDeparture = "17:20")
        )
        assertEquals(listOf("854路", "807路", "680路", "702路"), orderLinesByNear(lines, arrivals).map { it.name })
    }

    @Test fun `同线路多辆车取最近一班的站数`() {
        val lines = listOf(LineSummary("807路", "1"), LineSummary("854路", "1"))
        val arrivals = listOf(arrival("807路", 13), arrival("807路", 1), arrival("854路", 2))
        assertEquals(listOf("807路", "854路"), orderLinesByNear(lines, arrivals).map { it.name })
    }

    @Test fun `站点聚合结果按站数从近到远`() = runBlocking {
        val city = City("11", "厦门市", "0592")
        fun native(data: String, v2: Boolean) = """{"success":true,"code":${if (v2) 200 else 0},"data":$data}"""
        fun line(name: String, carNo: String, remain: Int, dist: Int?) =
            """{"lineName":"$name","upDown":"1","carNo":"$carNo","remainNum":$remain""" +
                (dist?.let { ""","remainDist":$it""" } ?: "") + ""","endStationName":"终点"}"""
        val detail = """{"name":"新垵村","stationNo":"A","gcjLat":24,"gcjLng":118,"relativeStationNoList":["A"],""" +
            """"lineList":[{"lineName":"807路","upperOrDownInLine":"1","stationOrderInLine":3},""" +
            """{"lineName":"854路","upperOrDownInLine":"1","stationOrderInLine":3},""" +
            """{"lineName":"891路","upperOrDownInLine":"1","stationOrderInLine":3},""" +
            """{"lineName":"680路","upperOrDownInLine":"1","stationOrderInLine":3}]}"""
        val buses = "[${line("807路", "车1", 13, 5200)},${line("854路", "车2", 2, null)}," +
            "${line("891路", "车3", 2, 900)},${line("680路", "", 0, null)}]"
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath.substringAfterLast('/')
            respond(
                if (path == "exactStationDetail") native(detail, false) else native(buses, true),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        })
        try {
            val repo = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", client), ZsgjClient(client)) { 0L }
            val result = repo.stationArrivals(city, Station("新垵村", "A"))
            assertEquals(listOf("891路", "854路", "807路", "680路"), result.arrivals.map { it.name })
        } finally {
            client.close()
        }
    }
}