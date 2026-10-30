package com.yll682.houche

import com.yll682.houche.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod

// JUnit 默认忽略；-Dlive=true 时仅对本测试类解除 Ignore，无须改源代码。
class LiveApiRunner(type: Class<*>) : BlockJUnit4ClassRunner(type) {
    private val enabled = System.getProperty("live") == "true"
    override fun isIgnored(child: FrameworkMethod): Boolean = if (enabled) false else super.isIgnored(child)

}
@RunWith(LiveApiRunner::class)
class LiveApiTest {
    private val repository = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef"))
    private val xm = City("11", "厦门市", "0592")
    private val bj = City("0", "北京市", "010")
    private fun record(text: String) { println("联网自检：$text") }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenSearch() = runBlocking {
        val result = repository.search(xm, "91路")
        record("厦门市 搜索 91路：线路=${result.lines.size}，站点=${result.stations.size}，样例=${result.lines.firstOrNull()}")
        Assert.assertTrue(result.lines.isNotEmpty())
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenLine() = runBlocking {
        val result = repository.lineDetail(xm, "91路", "1")
        record("厦门市 线路详情：name=${result.name}，id=${result.id}，方向=${result.direction}，站点=${result.stations.size}，首末=${result.first}/${result.last}，起终=${result.from}/${result.to}，首站=${result.stations.firstOrNull()}")
        Assert.assertTrue(result.stations.size > 5)
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenNearby() = runBlocking {
        val result = repository.nearby(xm, 24.48, 118.13)
        record("厦门市 附近 (24.48,118.13)：站点=${result.size}，首站=${result.firstOrNull()}")
        Assert.assertTrue(result.isNotEmpty())
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun beijingSearch() = runBlocking {
        val result = repository.search(bj, "1路")
        record("北京市 搜索 1路：线路=${result.lines.size}，站点=${result.stations.size}，样例=${result.lines.firstOrNull()}")
        Assert.assertTrue(result.lines.isNotEmpty())
    }

    private suspend fun <T> probe(label: String, block: suspend () -> T): T {
        record("开始 $label")
        try { return block().also { record("$label：$it") } }
        catch (error: Exception) { record("$label：失败 ${error.javaClass.simpleName}: ${error.message}；原因=${error.cause}"); throw error }
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenStationRealtime() = runBlocking {
        val station = repository.stationDetail(xm, "实验幼儿园", "SM000170", 24.48214279, 118.12726919)
        Assert.assertTrue(station.lines.isNotEmpty())
        probe("厦门 stationLineBus（同时补 CMD 115 时间）") { repository.stationBuses(xm, station) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun beijingStationRealtime() = runBlocking {
        val station = repository.stationDetail(bj, "天安门东", lat = 39.9074, lng = 116.4014)
        record("北京站点实时使用 CMD 209 选中的坐标：${station.lat}, ${station.lng}")
        probe("北京 CMD 115 站点实时") { repository.stationBuses(bj, station) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun beijingPlatforms() = runBlocking {
        probe("北京 CMD 209 站台详情（补 CMD 115）") { repository.stationDetail(bj, "天安门东", lat = 39.9074, lng = 116.4014) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenVehicles() = runBlocking {
        val route = repository.lineDetail(xm, "91路", "1")
        Assert.assertTrue(route.stations.isNotEmpty())
        probe("厦门 busList 车辆") { repository.vehicles(xm, route, route.stations[route.stations.size / 2]) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenArrivals() = runBlocking {
        probe("厦门线路 CMD 104 到站") { repository.arrivals(xm, "91路", "1", 16) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun beijingVehicles() = runBlocking {
        val route = repository.lineDetail(bj, "1路", "1")
        record("北京 CMD 103 详情：$route")
        Assert.assertTrue(route.stations.isNotEmpty())
        probe("北京 CMD 104 车辆和到站") { repository.vehicles(bj, route, route.stations[route.stations.size / 2]) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun xiamenTimetable() = runBlocking {
        val route = repository.lineDetail(xm, "91路", "1")
        probe("厦门 timeTable 时刻表") { repository.timetable(xm, route) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun beijingTimetable() = runBlocking {
        val route = repository.lineDetail(bj, "1路", "1")
        Assert.assertTrue(route.id.isNotBlank())
        probe("北京 CMD 207 时刻表") { repository.timetable(bj, route) }
        Unit
    }
    @Ignore("联网测试默认关闭，使用 -Dlive=true 启用")
    @Test fun beijingNearby() = runBlocking {
        val stations = probe("北京 CMD 106 附近站点及前三站 CMD 115") { repository.nearby(bj, 39.9074, 116.4014) }
        Assert.assertTrue(stations.isNotEmpty())
    }
}
