package com.yll682.houche.ui.screens.vehicle

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.AppGraph
import com.yll682.houche.core.BusRepository
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.XmbusClient
import com.yll682.houche.core.ZsgjClient
import com.yll682.houche.core.field
import com.yll682.houche.core.i
import com.yll682.houche.core.list
import com.yll682.houche.core.s
import com.yll682.houche.data.Prefs
import com.yll682.houche.data.VehicleTracker
import com.yll682.houche.data.remainingStopsToTarget
import com.yll682.houche.ui.UiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VehicleArrivalFixtureTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private var http: HttpClient? = null

    @Before fun resetTracking() = runBlocking {
        Prefs(app).setTrackedVehicle(null)
        Prefs(app).setVehicleAlightSelection(null)
        VehicleTracker.stopIfTargetMissing(app) {}
        VehicleTracker.serviceStopped()
    }

    @After fun clear() {
        store.clear()
        http?.close()
        resetTracking()
    }

    @Test fun `真实803路车辆站数与上游剩余站数一致`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/vehicle/remaining-vs-eta-803-order36.json"))
            .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }
        val provenance = fixture.field("provenance")
        assertEquals("projects/houche/analysis/vehicle/remaining-vs-eta-803路-d1-20261116T105816.json",
            provenance.s("captureFile"))
        assertEquals("projects/houche/analysis/vehicle/capture_remaining_vs_eta.py", provenance.s("captureScript"))
        assertEquals("2026-11-16T10:58:16+0000", provenance.s("capturedAt"))

        val rawVehicle = fixture.list("rawBusList").single()
        val plate = rawVehicle.s("plateNo")
        val rawArrival = fixture.field("rawCmd104").field(plate)
        val city = City("11", "厦门市", "0592")
        val route = Route(fixture.s("line"), rawVehicle.s("lqBusLineId"), fixture.s("direction"),
            stations = fixture.list("stations").map { RouteStation(it.s("name"), it.i("order"), it.s("id")) })
        val target = route.stations.single { it.order == fixture.field("anchor").i("stationOrder") }
        assertEquals("闽D06982D", plate)
        assertEquals("803路", route.name)
        assertEquals("1", route.direction)
        assertEquals(36, target.order)
        assertEquals(target, route.stations.last())
        assertEquals(fixture.field("anchor").s("id"), target.id)
        assertEquals(fixture.field("anchor").s("name"), target.name)
        assertEquals(30, rawVehicle.i("stationOrder"))
        assertEquals(6, rawArrival.i("busToStationCount"))

        val vehicleRequests = AtomicInteger()
        val otherRequests = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            val tail = request.url.encodedPath.substringAfterLast('/')
            assertEquals("只应请求车辆位置，不应再请求 CMD104：${request.url}", "busList", tail)
            vehicleRequests.incrementAndGet()
            assertEquals(route.id, request.url.parameters["busLineId"])
            assertEquals(route.direction, request.url.parameters["direction"])
            assertEquals("36", request.url.parameters["stationSeq"])
            assertEquals(target.id, request.url.parameters["stationId"])
            assertEquals(target.name, request.url.parameters["stationName"])
            // 抓取脚本已解包 data；仅在传输边界补生产客户端要求的成功封套。
            val response = buildJsonObject {
                put("code", 0)
                put("data", fixture.field("rawBusList"))
            }
            respond(response.toString(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }).also { http = it }
        val repository = BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", client), ZsgjClient(client))
        // 使用抓取时明确选定的 36 站，经过真实 repository 和生产解析器加载车辆页。
        val vm = VehicleViewModel(AppGraph(app), city, route, plate, boarding = target,
            repositoryProvider = { repository })
            .also { store.put("vehicle", it) }
        awaitResult(vm)
        assertTrue("车辆页加载失败：${vm.state.value}", vm.state.value is UiState.Success)
        val data = requireNotNull(vm.state.value.data)
        val vehicle = requireNotNull(data.vehicle)
        assertEquals(target, data.boarding)
        assertNull(data.alight)
        assertFalse(data.offline)
        assertEquals(1, vehicleRequests.get())
        assertEquals(0, otherRequests.get())
        assertEquals(plate, vehicle.plate)
        assertEquals(30, vehicle.reportedOrder)
        assertEquals(30, vehicle.currentStationOrder)
        assertEquals("at-stop", vehicle.positionState)
        assertEquals("柯达", vehicle.currentStation)
        assertEquals("惠佐", vehicle.nextStation)
        // 站数是客户端算的：目标站序 − 车辆当前站序，与同次抓包上游给的剩余站数一致。
        assertEquals(6, remainingStopsToTarget(target, vehicle))
        assertEquals(rawArrival.i("busToStationCount"), remainingStopsToTarget(target, vehicle))
    }

    private fun awaitResult(vm: VehicleViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.state.value is UiState.Success || vm.state.value is UiState.Error) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("等待真实车辆夹具加载超时：${vm.state.value}")
    }
}
