package com.yll682.houche.ui.screens.metro

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.AppGraph
import com.yll682.houche.core.BusNetwork
import com.yll682.houche.core.City
import com.yll682.houche.core.Metro
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroRealtimeRemote
import com.yll682.houche.core.MetroRemote
import com.yll682.houche.core.MetroStation
import com.yll682.houche.core.nearestStation
import com.yll682.houche.data.Position
import com.yll682.houche.ui.UiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MetroTabStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private val clients = mutableListOf<HttpClient>()
    private val httpRequests = AtomicInteger()
    private val realtimeRequests = AtomicInteger()
    private val city = City(key = "metro-tab-test", name = "测试城市")
    private val position = Position(24.46, 118.11, accuracy = 20f, coordinateSystem = "GCJ02")

    @After fun clear() {
        store.clear()
        idle()
        clients.forEach { it.close() }
    }

    @Test fun `同一VM定位未完成及成功后再次进入都只调用一次定位`() {
        val vm = keep(offlineGraph(unsupportedMetro()))
        val calls = AtomicInteger()
        val result = CompletableDeferred<Position>()
        val returned = CompletableDeferred<Unit>()
        val locate: suspend () -> Position = {
            calls.incrementAndGet()
            result.await().also { returned.complete(Unit) }
        }

        vm.requestInitialLocation(locate)
        vm.requestInitialLocation(locate)
        await { calls.get() == 1 && vm.state.value is UiState.Empty }
        repeat(3) { vm.requestInitialLocation(locate) }
        idle()
        assertFalse("首次定位仍应等待受控结果", returned.isCompleted)
        assertEquals("定位未完成时再次进入不能启动第二次定位", 1, calls.get())

        result.complete(position)
        await { returned.isCompleted }
        repeat(3) { vm.requestInitialLocation(locate) }
        idle()
        assertEquals("同一VM成功定位后再次进入也不能重试", 1, calls.get())
        assertEquals("不支持的城市不应发起在线请求", 0, httpRequests.get())
    }

    @Test fun `首次定位失败后同一VM再次进入不重试且页面不进入错误态`() {
        val vm = keep(offlineGraph(unsupportedMetro()))
        val calls = AtomicInteger()
        val result = CompletableDeferred<Position>()
        val returned = CompletableDeferred<Unit>()
        vm.requestInitialLocation {
            calls.incrementAndGet()
            try {
                result.await()
            } finally {
                returned.complete(Unit)
            }
        }
        await { calls.get() == 1 && vm.state.value is UiState.Empty }

        result.completeExceptionally(IOException("受控定位失败"))
        await { returned.isCompleted }
        repeat(3) {
            vm.requestInitialLocation {
                calls.incrementAndGet()
                position
            }
        }
        idle()
        assertEquals("首次失败也消费同一VM的定位机会", 1, calls.get())
        assertTrue("定位失败后仍保留无地铁资料的页面状态", vm.state.value is UiState.Empty)
        assertEquals("不支持的城市不应发起在线请求", 0, httpRequests.get())
    }

    @Test fun `站表未加载时预选线路会在加载后选中且迟到定位不能覆盖`() {
        val network = network()
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val metro = Metro(
            openIndex = {
                """[{"city":"测试城市","cityKey":"metro-tab-test"}]""".byteInputStream()
            }
        ) {
            opened.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "等待测试释放站表超时" }
            BusNetwork.json.encodeToString(network).byteInputStream()
        }
        val result = CompletableDeferred<Position>()
        val returned = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        try {
            val vm = keep(offlineGraph(metro))
            vm.requestInitialLocation {
                calls.incrementAndGet()
                result.await().also { returned.complete(Unit) }
            }
            await { opened.count == 0L && calls.get() == 1 }
            assertTrue("预选必须发生在站表到达之前", vm.state.value.data!!.lines.isEmpty())
            vm.selectLine("2")

            release.countDown()
            await { vm.state.value.data?.lines?.size == 2 }
            assertEquals("预选的线路不是默认首条线路", listOf("1", "2"), vm.state.value.data!!.lines.map { it.id })
            assertEquals("站表到达后应兑现预选线路", "2", vm.state.value.data!!.selected)
            assertEquals("线路详情也应指向预选目标", "2", vm.state.value.data!!.line?.id)
            assertEquals(
                "定位必须足以选中另一条线路，才能检验迟到结果的优先级", "1",
                vm.state.value.data!!.lines.nearestStation(
                    position.lat, position.lng, position.accuracy?.toDouble(), position.coordinateSystem
                )?.lineId
            )

            result.complete(position)
            await { returned.isCompleted }
            idle()
            assertEquals("迟到定位不得覆盖预选线路", "2", vm.state.value.data!!.selected)
            assertEquals("迟到定位也不得自动设置另一站", "", vm.state.value.data!!.realtimeStation)
            assertEquals("本次定位确已完成且只调用一次", 1, calls.get())
            assertFalse("受控在线失败不应覆盖内置站表", vm.state.value is UiState.Error)
            assertEquals("隐藏时预选线路不能发起实时请求", 0, realtimeRequests.get())
        } finally {
            release.countDown()
            result.cancel()
        }
    }

    @Test fun `默认隐藏的VM有有效线路和站点时手动与自动刷新均不请求实时数据`() {
        val vm = keep(offlineGraph(supportedMetro()))
        await { vm.state.value.data?.lines?.size == 2 }
        vm.selectLine("2")
        vm.selectStation("02020000")
        assertEquals("隐藏时仍允许选线", "2", vm.state.value.data!!.selected)
        assertEquals("隐藏时仍允许选站", "02020000", vm.state.value.data!!.realtimeStation)

        assertNull("默认隐藏时不能创建实时刷新任务", vm.refreshRealtime())
        vm.refreshRealtimeAutomatically()
        idle()
        assertEquals("隐藏时不能建立实时请求上下文", 0L, vm.state.value.data!!.realtimeRequestId)
        assertEquals("手动和自动入口均不能发起实时请求", 0, realtimeRequests.get())
    }

    @Test fun `显式隐藏不取消在途首次定位且迟到结果只更新站点不请求实时数据`() {
        val vm = keep(offlineGraph(supportedMetro()))
        await { vm.state.value.data?.lines?.size == 2 }
        assertEquals("定位前应是默认首条线路", "1", vm.state.value.data!!.selected)
        val calls = AtomicInteger()
        val result = CompletableDeferred<Position>()
        val returned = CompletableDeferred<Unit>()
        vm.requestInitialLocation {
            calls.incrementAndGet()
            result.await().also { returned.complete(Unit) }
        }
        await { calls.get() == 1 }

        vm.setRealtimeVisible(false)
        assertFalse("隐藏时首次定位尚未完成", returned.isCompleted)
        result.complete(position.copy(lat = 24.51, lng = 118.21))
        await { returned.isCompleted }
        idle()
        assertEquals("隐藏后仍应应用首次定位选线", "2", vm.state.value.data!!.selected)
        assertEquals("隐藏后仍应应用首次定位选站", "02020000", vm.state.value.data!!.realtimeStation)
        assertEquals("定位站名也应随目标站更新", "丁", vm.state.value.data!!.realtimeStationName)
        assertEquals("首次定位只执行一次", 1, calls.get())
        assertNull("定位结果不能重新开启隐藏VM的实时刷新", vm.refreshRealtime())
        assertEquals("迟到定位不能建立实时请求上下文", 0L, vm.state.value.data!!.realtimeRequestId)
        assertEquals("隐藏后的迟到定位不能发起实时请求", 0, realtimeRequests.get())
    }

    private fun keep(graph: AppGraph): MetroViewModel =
        MetroViewModel(graph, city).also { store.put("metro", it) }

    private fun unsupportedMetro(): Metro = Metro(openIndex = { "[]".byteInputStream() }) {
        error("不支持的城市不应读取完整站表")
    }

    private fun supportedMetro(): Metro = Metro(
        openIndex = { """[{"city":"测试城市","cityKey":"metro-tab-test"}]""".byteInputStream() }
    ) { BusNetwork.json.encodeToString(network()).byteInputStream() }

    private fun offlineGraph(metro: Metro): AppGraph {
        val client = HttpClient(MockEngine { request ->
            httpRequests.incrementAndGet()
            if (request.url.encodedPath.endsWith("pub/transTime/getTransTimes")) {
                realtimeRequests.incrementAndGet()
            }
            respond("{}", HttpStatusCode.BadRequest)
        }) { install(HttpTimeout) }
        clients += client
        // AppGraph 没有远端注入参数，只替换本实例客户端，避免改动共享网络状态。
        return AppGraph(app, metro).also { graph ->
            ReflectionHelpers.setField(graph, "metroRemote", MetroRemote(client))
            ReflectionHelpers.setField(graph, "metroRealtime", MetroRealtimeRemote(client))
        }
    }

    private fun network(): MetroNetwork = MetroNetwork(
        city = city.name,
        cityKey = city.key,
        lines = listOf(
            MetroLine(id = "1", name = "一号线", apiCode = "01000000", stations = listOf(
                station(1, "甲", "01010000", "24.45", "118.10"),
                station(2, "乙", "01020000", "24.46", "118.11")
            )),
            MetroLine(id = "2", name = "二号线", apiCode = "02000000", stations = listOf(
                station(1, "丙", "02010000", "24.50", "118.20"),
                station(2, "丁", "02020000", "24.51", "118.21")
            ))
        )
    )

    private fun station(order: Int, name: String, code: String, lat: String, lng: String): MetroStation =
        MetroStation(order, name, code = code, lat = lat, lng = lng, coordinateSystem = "GCJ02")

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("等待地铁Tab状态更新超时")
    }
}
