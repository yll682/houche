package com.yll682.houche.ui.screens.home

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.data.Position
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.screens.station.StationViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 驱动真实首页定位入口和站点详情入口；所有公交响应来自 MockEngine。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StationArrivalsViewModelsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private val clients = mutableListOf<HttpClient>()
    private val city = City("11", "厦门市", "0592")
    private fun <T : ViewModel> keep(key: String, vm: T): T = vm.also { store.put(key, it) }

    @After fun clear() {
        store.clear()
        clients.forEach { it.close() }
    }

    @Test fun `首页五个站点与详情均先解析同一站台并返回一致到站`() {
        val graph = AppGraph(app)
        val homeServer = Server()
        val homeRepo = repository(homeServer)
        val home = keep("home", HomeViewModel(graph, city, { homeRepo }, { Position(24.5, 118.1) }))
        home.locate()
        await { home.state.value.data!!.liveStates.size == 5 && home.state.value.data!!.liveStates.values.all { it is StationLiveState.Ready } }
        val data = home.state.value.data!!
        assertEquals(5, data.stations.size)
        assertEquals((0..4).toSet(), data.arrivals.keys)
        assertTrue("第四第五个站同样发实时查询", homeServer.nativeRequests.containsAll(listOf("5", "6")))
        assertEquals(listOf("M26", "M18"), data.arrivals.getValue(2).filter { it.platformNumber == "3" }.map { it.name })
        assertEquals(listOf(2, 3), data.arrivals.getValue(2).filter { it.platformNumber == "3" }.map { it.remainingStations })
        assertEquals(setOf("3", "4"), data.arrivals.getValue(2).map { it.platformNumber }.toSet())
        assertTrue(data.stations[2].lines.any { it.name == "M99" })
        assertEquals(24.503, data.stations[2].lat!!, 0.000001)

        // 独立 repository 防止详情仅命中首页缓存，让两个 VM 都走完整解析入口。
        val detailServer = Server()
        val detailRepo = repository(detailServer)
        val station = keep("detail", StationViewModel(graph, city, data.stations[2], { detailRepo }))
        await { station.state.value.data!!.liveState is StationLiveState.Ready }
        assertEquals(data.arrivals.getValue(2).filter { it.platformNumber == "3" }, station.state.value.data!!.arrivals)
        assertEquals(setOf("3"), detailServer.nativeRequests.toSet())
        assertFalse(station.state.value is UiState.Error)

        val singleServer = Server()
        val singleRepo = repository(singleServer)
        val single = keep("single", StationViewModel(graph, city, data.stations[1], { singleRepo }))
        await { single.state.value.data!!.liveState is StationLiveState.Ready }
        assertEquals(data.arrivals.getValue(1), single.state.value.data!!.arrivals)
        assertEquals(setOf("2"), singleServer.nativeRequests.toSet())
    }

    @Test fun `一个站失败和一个站台失败均保留明确状态与成功资料`() {
        val graph = AppGraph(app)
        val server = Server(failures = setOf("2", "4"))
        val repo = repository(server)
        val home = keep("home", HomeViewModel(graph, city, { repo }, { Position(24.5, 118.1) }))
        home.locate()
        await { home.state.value.data!!.liveStates.size == 5 && home.state.value.data!!.liveStates.values.none { it is StationLiveState.Loading } }
        val homeData = home.state.value.data!!
        assertTrue(homeData.liveStates.getValue(0) is StationLiveState.Ready)
        assertTrue(homeData.liveStates.getValue(1) is StationLiveState.Failed)
        assertTrue(homeData.liveStates.getValue(2) is StationLiveState.Partial)
        assertEquals(setOf("M18", "M26"), homeData.arrivals.getValue(2).map { it.name }.toSet())
        assertTrue(homeData.liveErrors.getValue(1)!!.isNotBlank())
        assertTrue(homeData.liveErrors.getValue(2)!!.isNotBlank())
        val failed = keep("failed", StationViewModel(graph, city, homeData.stations[1], { repo }))
        val partial = keep("partial", StationViewModel(graph, city, homeData.stations[2], { repo }))
        await { failed.state.value.data!!.liveState is StationLiveState.Failed && partial.state.value.data!!.liveState is StationLiveState.Ready }
        assertTrue(failed.state.value is UiState.Error)
        assertEquals(homeData.arrivals.getValue(1), failed.state.value.data!!.arrivals)
        assertEquals(homeData.arrivals.getValue(2).filter { it.platformNumber == "3" }, partial.state.value.data!!.arrivals)
    }

    @Test fun `真正空响应与已知线路缺报不混为暂无`() {
        val graph = AppGraph(app)
        val repo = repository(Server(empty = setOf("5", "6"), noLines = setOf("5")))
        val home = keep("home", HomeViewModel(graph, city, { repo }, { Position(24.5, 118.1) }))
        home.locate()
        await { home.state.value.data!!.liveStates.size == 5 && home.state.value.data!!.liveStates.values.none { it is StationLiveState.Loading } }
        val data = home.state.value.data!!
        assertSame(StationLiveState.Empty, data.liveStates.getValue(3))
        val missing = data.liveStates.getValue(4)
        assertTrue(missing is StationLiveState.Partial)
        assertEquals("806", missing.unreportedLines.single().line.name)
        assertTrue(data.liveErrors.containsKey(4))
        val detail = keep("empty", StationViewModel(graph, city, data.stations[3], { repo }))
        await { detail.state.value.data!!.liveState === StationLiveState.Empty }
        assertTrue(detail.state.value.data!!.arrivals.isEmpty())
    }

    @Test fun `慢站尚未完成时已有站点逐个更新且未完成站保留加载状态`() {
        val graph = AppGraph(app)
        val gate = Gate()
        val repo = repository(Server(gateNumber = "3", gate = gate))
        val home = keep("home", HomeViewModel(graph, city, { repo }, { Position(24.5, 118.1) }))
        home.locate()
        try {
            await { gate.started.isCompleted && home.state.value.data!!.liveStates[0] is StationLiveState.Ready }
            assertTrue(home.state.value.data!!.liveStates[2] is StationLiveState.Loading)
            assertFalse(home.state.value.data!!.liveErrors.containsKey(2))
            gate.release.complete(Unit)
            await { home.state.value.data!!.liveStates[2] is StationLiveState.Ready }
        } finally { gate.release.complete(Unit) }
    }

    @Test fun `详情切换站点后旧慢请求不能覆盖所选站点`() {
        val graph = AppGraph(app)
        val gate = Gate()
        val repo = repository(Server(gateNumber = "3", gate = gate))
        val station = keep("detail", StationViewModel(graph, city, Station("温头溪", "3"), { repo }))
        try {
            await { gate.started.isCompleted }
            station.load(Station("祥禾广场", "2"))
            await { station.state.value.data!!.station.number == "2" && station.state.value.data!!.liveState is StationLiveState.Ready }
            gate.release.complete(Unit)
            await { gate.finished.isCompleted }
            idle()
            assertEquals("2", station.state.value.data!!.station.number)
            assertEquals(listOf("803"), station.state.value.data!!.arrivals.map { it.name })
        } finally { gate.release.complete(Unit) }
    }

    @Test fun `站台往返切换保持顺序并查询对应编号和线路`() {
        val server = Server()
        val repo = repository(server)
        val vm = keep("platforms", StationViewModel(AppGraph(app), city, Station("温头溪", "3"), { repo }))
        await { vm.state.value.data!!.liveState is StationLiveState.Ready }
        assertEquals(listOf("3", "4"), vm.state.value.data!!.platforms.map { it.number })
        assertEquals(0, vm.state.value.data!!.selectedPlatform)
        assertEquals(setOf("M18", "M26"), vm.state.value.data!!.station.lines.map { it.name }.toSet())
        vm.selectPlatform(1)
        assertEquals(1, vm.state.value.data!!.selectedPlatform)
        assertTrue(vm.state.value is UiState.Loading)
        await { vm.state.value.data!!.liveState is StationLiveState.Ready }
        assertEquals(listOf("3", "4"), vm.state.value.data!!.platforms.map { it.number })
        assertEquals("4", vm.state.value.data!!.station.number)
        assertEquals(listOf("M99"), vm.state.value.data!!.station.lines.map { it.name })
        assertEquals(listOf("4"), vm.state.value.data!!.arrivals.map { it.platformNumber })
        assertTrue(server.nativeRequests.contains("4"))
        vm.selectPlatform(0)
        await { vm.state.value.data!!.liveState is StationLiveState.Ready }
        assertEquals(0, vm.state.value.data!!.selectedPlatform)
        assertEquals("3", vm.state.value.data!!.station.number)
        assertEquals(setOf("M18", "M26"), vm.state.value.data!!.arrivals.map { it.name }.toSet())
    }

    @Test fun `目标站台加载和失败不被其他站台掩盖`() {
        val gate = Gate()
        val repo = repository(Server(failures = setOf("4"), gateNumber = "4", gate = gate))
        val vm = keep("failure", StationViewModel(AppGraph(app), city, Station("温头溪", "3"), { repo }))
        try {
            await { vm.state.value.data!!.liveState is StationLiveState.Ready }
            vm.selectPlatform(1)
            await { gate.started.isCompleted }
            assertTrue(vm.state.value is UiState.Loading)
            assertEquals(1, vm.state.value.data!!.selectedPlatform)
            assertTrue(vm.state.value.data!!.arrivals.isEmpty())
            gate.release.complete(Unit)
            await { vm.state.value.data!!.liveState is StationLiveState.Failed }
            assertTrue(vm.state.value is UiState.Error)
            assertFalse(vm.state.value.data!!.liveState.message.isNullOrBlank())
            assertEquals(1, vm.state.value.data!!.selectedPlatform)
            vm.selectPlatform(0)
            await { vm.state.value.data!!.liveState is StationLiveState.Ready }
            assertEquals(0, vm.state.value.data!!.selectedPlatform)
        } finally { gate.release.complete(Unit) }
    }

    @Test fun `重新定位替换列表后旧下标查询不能写入新站点`() {
        val graph = AppGraph(app)
        val gate = Gate()
        val calls = AtomicInteger()
        val repo = repository(Server(gateNumber = "3", gate = gate, relocate = true))
        val home = keep("home", HomeViewModel(graph, city, { repo }, { Position(if (calls.incrementAndGet() == 1) 24.5 else 24.6, 118.1) }))
        home.locate()
        try {
            await { gate.started.isCompleted }
            home.locate()
            await { home.state.value.data!!.stations.singleOrNull()?.number == "2" && home.state.value.data!!.liveStates[0] is StationLiveState.Ready }
            gate.release.complete(Unit)
            await { gate.finished.isCompleted }
            idle()
            val data = home.state.value.data!!
            assertEquals(listOf("2"), data.stations.map { it.number })
            assertEquals(setOf(0), data.arrivals.keys)
            assertEquals(listOf("803"), data.arrivals.getValue(0).map { it.name })
        } finally { gate.release.complete(Unit) }
    }

    private class Gate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }

    private class Server(
        val failures: Set<String> = emptySet(),
        val empty: Set<String> = emptySet(),
        val noLines: Set<String> = emptySet(),
        val gateNumber: String? = null,
        val gate: Gate? = null,
        val relocate: Boolean = false
    ) {
        val nativeRequests = ConcurrentLinkedQueue<String>()
        fun name(number: String) = when (number) { "1" -> "后柯南里"; "2" -> "祥禾广场"; "3", "4" -> "温头溪"; else -> "站点$number" }
        fun lines(number: String) = if (number in noLines) emptyList() else when (number) {
            "1", "3" -> listOf("M26", "M18")
            "2" -> listOf("803")
            "4" -> listOf("M99")
            else -> listOf("80$number")
        }
        fun station(number: String, nearby: Boolean = false): String {
            val lat = if (nearby) 24.0 else 24.5 + number.toInt() * 0.001
            val lineList = lines(number).joinToString(",") { """{"lineName":"$it","upperOrDownInLine":"1","euStation":"终点"}""" }
            val relatives = if (number in setOf("3", "4")) "\"3\",\"4\"" else "\"$number\""
            return """{"name":"${name(number)}","stationNo":"$number","gcjLat":$lat,"gcjLng":118.1,"distance":${number.toInt() * 10},"relativeStationNoList":[$relatives],"lineList":[$lineList]}"""
        }
    }

    private fun repository(server: Server): BusRepository {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.substringAfterLast('/')
            val number = request.url.parameters["stationNo"].orEmpty()
            val response = when (path) {
                "nearestExactStation" -> {
                    val numbers = if (!server.relocate) listOf("1", "2", "3", "5", "6")
                        else if (request.url.parameters["lat"] == "24.5") listOf("3") else listOf("2")
                    """{"code":0,"data":[${numbers.joinToString(",") { server.station(it, true) }}]}"""
                }
                "exactStationDetail" -> """{"code":0,"data":${server.station(number)}}"""
                "stationLineBus" -> {
                    server.nativeRequests.add(number)
                    assertEquals("实时请求采用已解析的站台坐标", 24.5 + number.toInt() * 0.001,
                        request.url.parameters["stationLat"]!!.toDouble(), 0.000001)
                    if (number == server.gateNumber) withContext(NonCancellable) {
                        server.gate!!.started.complete(Unit)
                        server.gate.release.await()
                        server.gate.finished.complete(Unit)
                    }
                    if (number in server.failures) """{"success":false,"code":1}"""
                    else {
                        val records = if (number in server.empty) "" else server.lines(number).mapIndexed { index, line ->
                            """{"lineName":"$line","upDown":"1","endStationName":"终点","carNo":"车$number$index","remainNum":${index + 2},"remainDist":${(index + 2) * 100}}"""
                        }.joinToString(",")
                        """{"code":200,"data":[$records]}"""
                    }
                }
                "ApiData.do" -> """{"status":1,"data":[]}"""
                else -> error("未预期的请求：$path")
            }
            respond(response, headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) })
        }
        val http = HttpClient(engine).also { clients += it }
        return BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), ZsgjClient(http))
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("等待首页或站点详情到站状态超时")
    }
}
