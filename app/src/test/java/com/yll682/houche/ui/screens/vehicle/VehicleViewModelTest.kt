package com.yll682.houche.ui.screens.vehicle

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.AppGraph
import com.yll682.houche.core.BusException
import com.yll682.houche.core.BusRepository
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.XmbusClient
import com.yll682.houche.core.ZsgjClient
import com.yll682.houche.data.AlightReminderState
import com.yll682.houche.data.Prefs
import com.yll682.houche.data.TrackedVehicle
import com.yll682.houche.data.VehicleAlightSelection
import com.yll682.houche.data.VehicleTracker
import com.yll682.houche.ui.UiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VehicleViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private val clients = mutableListOf<HttpClient>()
    private val city = City("11", "厦门市", "0592")
    private val route = Route("M26", "26", "1", stations = listOf(
        RouteStation("第一站", 1, "1"), RouteStation("第二站", 2, "2")
    ))

    @Before fun resetTracking() = runBlocking {
        Prefs(app).setTrackedVehicle(null)
        Prefs(app).setVehicleAlightSelection(null)
        VehicleTracker.stopIfTargetMissing(app) {}
        VehicleTracker.serviceStopped()
    }

    @After fun clear() {
        store.clear()
        clients.forEach { it.close() }
        resetTracking()
    }









    @Test fun `连续失败逐次累计而成功重置并只在成功时更新时间`() {
        val server = Server()
        val repo = repository(server)
        var time = "12:03:11"
        var clockCalls = 0
        val vm = keep(VehicleViewModel(AppGraph(app), city, route, "闽D12345",
            repositoryProvider = { repo }, now = { clockCalls++; time }))
        await { vm.state.value.data!!.updatedAt != null }
        val previous = vm.state.value.data!!
        assertEquals(0, previous.staleRetry)
        assertEquals("12:03:11", previous.updatedAt)
        assertEquals("闽D12345", previous.vehicle!!.plate)
        assertEquals(1, clockCalls)
        server.failed = true
        repo.clearCache()
        time = "12:04:22"
        for (count in 1..3) {
            assertFalse(refresh(vm))
            val failed = vm.state.value.data!!
            assertTrue(vm.state.value is UiState.Error)
            assertEquals(count, failed.staleRetry)
            assertEquals(previous.updatedAt, failed.updatedAt)
            assertEquals(previous.vehicle, failed.vehicle)
            assertEquals(previous.vehicles, failed.vehicles)
            assertEquals(1, clockCalls)
        }
        server.failed = false
        assertTrue(refresh(vm))
        assertTrue(vm.state.value is UiState.Success)
        assertEquals(0, vm.state.value.data!!.staleRetry)
        assertEquals("12:04:22", vm.state.value.data!!.updatedAt)
        assertEquals(2, clockCalls)
        server.failed = true
        repo.clearCache()
        assertFalse(refresh(vm))
        assertEquals(1, vm.state.value.data!!.staleRetry)
        assertEquals("12:04:22", vm.state.value.data!!.updatedAt)
        assertEquals(2, clockCalls)
    }

    @Test fun `首次失败没有成功时间而成功空结果同样清零失败次数`() {
        val server = Server(failed = true, plate = null)
        val repo = repository(server)
        val vm = keep(VehicleViewModel(AppGraph(app), city, route, "闽D12345",
            repositoryProvider = { repo }, now = { "12:03:11" }))
        await { vm.state.value is UiState.Error }
        assertEquals(1, vm.state.value.data!!.staleRetry)
        assertNull(vm.state.value.data!!.updatedAt)
        assertFalse(refresh(vm))
        assertEquals(2, vm.state.value.data!!.staleRetry)
        assertNull(vm.state.value.data!!.updatedAt)
        server.failed = false
        assertTrue(refresh(vm))
        val empty = vm.state.value.data!!
        assertTrue(vm.state.value is UiState.Success)
        assertEquals(0, empty.staleRetry)
        assertEquals("12:03:11", empty.updatedAt)
        assertTrue(empty.offline)
        assertNull(empty.vehicle)
        assertTrue(empty.vehicles.isEmpty())
    }

    @Test fun `取消实时请求不算一次失败也不改成功时间`() {
        val repo = repository(Server())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var block = false
        val vm = keep(VehicleViewModel(AppGraph(app), city, route, "闽D12345",
            repositoryProvider = {
                if (block) { started.complete(Unit); release.await() }
                repo
            }, now = { "12:03:11" }))
        await { vm.state.value.data!!.updatedAt != null }
        val previous = vm.state.value
        block = true
        val refresh = vm.refresh()
        try {
            await { started.isCompleted }
            refresh.cancel()
            await { refresh.isCompleted }
            assertTrue(refresh.isCancelled)
            assertEquals(previous, vm.state.value)
            assertEquals(0, vm.state.value.data!!.staleRetry)
        } finally { release.complete(Unit) }
    }

    @Test fun `切车后旧请求失败不能计入新车连续失败次数`() {
        val repo = repository(Server())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failOnce = false
        var time = "12:03:11"
        val vm = keep(VehicleViewModel(AppGraph(app), city, route, "闽D12345",
            repositoryProvider = {
                if (failOnce) {
                    failOnce = false
                    started.complete(Unit)
                    release.await()
                    throw BusException(BusException.Kind.NETWORK, "旧车请求失败")
                }
                repo
            }, now = { time }))
        await { vm.state.value.data!!.updatedAt != null }
        failOnce = true
        val refresh = vm.refresh()
        try {
            await { started.isCompleted }
            vm.selectVehicle("闽D67890")
            time = "12:04:22"
            release.complete(Unit)
            await { refresh.isCompleted && vm.state.value.data!!.updatedAt == time }
            assertEquals("闽D67890", vm.state.value.data!!.plate)
            assertEquals(0, vm.state.value.data!!.staleRetry)
            assertTrue(vm.state.value is UiState.Success)
        } finally { release.complete(Unit) }
    }

    @Test fun `清除下车站后UI保持跟踪和后台运行且不抹掉查询错误`() {
        val alight = route.stations.last()
        val target = TrackedVehicle(city, route.name, "闽D12345", null, route,
            alightName = alight.name, alightOrder = alight.order,
            reminder = AlightReminderState(beforeOrder = alight.order))
        runBlocking {
            Prefs(app).setTrackedVehicle(target)
            Prefs(app).setVehicleAlightSelection(VehicleAlightSelection(city.key, route.id, route.direction, target.plate, alight))
            assertTrue(VehicleTracker.restored(app, target))
        }
        VehicleTracker.serviceStarted()
        val repo = repository(Server(failed = true))
        val vm = keep(VehicleViewModel(AppGraph(app), city, route, target.plate, repositoryProvider = { repo }))
        vm.bindContext(app)
        await {
            val data = vm.state.value.data!!
            vm.state.value is UiState.Error && data.tracking && data.backgroundActive && data.alight == alight
        }
        val observed = mutableListOf<UiState<VehicleData>>()
        val observation = vm.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.state.collect { observed += it }
        }
        try {
            vm.clearAlight()
            await { vm.state.value.data!!.alight == null && VehicleTracker.active.value?.alight == null }
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            val expected = target.copy(alightName = null, alightOrder = null,
                revision = target.revision + 1, reminder = AlightReminderState())
            assertEquals(expected, VehicleTracker.active.value)
            assertTrue(VehicleTracker.backgroundActive.value)
            runBlocking {
                assertEquals(expected, Prefs(app).trackedVehicle.first())
                assertNull(Prefs(app).vehicleAlightSelection.first())
            }
            assertTrue(vm.state.value.data!!.tracking)
            assertTrue(vm.state.value.data!!.backgroundActive)
            assertTrue(vm.state.value is UiState.Error)
            assertEquals(1, vm.state.value.data!!.staleRetry)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it is UiState.Error && it.data!!.tracking && it.data!!.backgroundActive })
        } finally { observation.cancel() }
    }

    private fun keep(vm: VehicleViewModel): VehicleViewModel = vm.also { store.put("vehicle", it) }

    private fun refresh(vm: VehicleViewModel): Boolean {
        var succeeded: Boolean? = null
        val job = vm.viewModelScope.launch { succeeded = vm.refreshLive() }
        await { job.isCompleted }
        assertFalse(job.isCancelled)
        return checkNotNull(succeeded)
    }


    private class Server(var failed: Boolean = false, var plate: String? = "闽D12345") {
        val arrivalOrders = CopyOnWriteArrayList<Int>()
    }

    private fun repository(server: Server): BusRepository {
        val engine = MockEngine { request ->
            val response = if (request.url.encodedPath == "/ApiData.do") {
                val form = (request.body as FormDataContent).formData
                assertEquals("104", form["CMD"])
                assertEquals(city.name, form["CITYNAME"])
                assertEquals(route.name, form["LINENAME"])
                assertEquals(route.direction, form["DIRECTION"])
                server.arrivalOrders += checkNotNull(form["STATIONORDER"]).toInt()
                """{"status":"1","routeOnStationRTimeInfoList":[{"busNumber":"${server.plate}","busToStationCount":6,"busToStationTimeTips":"14分钟"}]}"""
            } else {
                assertEquals("busList", request.url.encodedPath.substringAfterLast('/'))
                val records = server.plate?.let {
                    """{"plateNo":"$it","stationOrder":1,"nextStationName":"第二站","speed":0}"""
                }.orEmpty()
                if (server.failed) """{"success":false,"code":1}"""
                else """{"code":0,"data":[$records]}"""
            }
            respond(response, headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) })
        }
        val http = HttpClient(engine).also { clients += it }
        return BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), ZsgjClient(http))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("等待车辆查询状态超时")
    }
}
