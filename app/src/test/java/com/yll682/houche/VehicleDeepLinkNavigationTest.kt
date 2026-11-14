package com.yll682.houche

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HoucheApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class Vehicle深链导航测试 {
    private val store = ViewModelStore()
    private val navigation = NavigationState().also { store.put("navigation", it) }
    private val messages = mutableListOf<Int>()
    private val calls = AtomicInteger()
    private val city = City("11", "厦门市", "0592")
    private lateinit var graph: AppGraph
    private lateinit var http: HttpClient
    private var activity: ActivityController<MainActivity>? = null
    private var reply: () -> String = {
        """{"id":"line-91","name":"91路","stationList":[{"id":"stop","name":"终点","stationOrder":1,"upperOrDown":"2"}]}"""
    }
    private val release = CountDownLatch(1)

    @Before fun `用拦截响应替换仓库以免访问网络`() {
        graph = ApplicationProvider.getApplicationContext<HoucheApp>().graph
        http = HttpClient(OkHttp) {
            engine { config { addInterceptor { chain ->
                calls.incrementAndGet()
                val payload = reply()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("成功")
                    .body("""{"success":true,"code":0,"data":$payload}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } } }
        }
        val repository = BusRepository(XmbusClient("deeplink-test-device", http), ZsgjClient(http))
        AppGraph::class.java.getDeclaredField("pending").apply { isAccessible = true }
            .set(graph, CompletableDeferred(repository))
    }

    @After fun `取消查询并清理本轮导航`() {
        release.countDown()
        activity?.destroy()
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        http.close()
    }

    private fun link(plate: String = "闽D28810", cityCode: String = "0592", direction: String = "2") =
        VehicleShareLink.buildSchemeLink(cityCode, null, "91路", direction, plate)

    private fun open(uri: String) = navigation.openVehicleLink(graph, uri) { messages.add(it) }

    private fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("异步查询应在时限内完成，messages=$messages", done())
    }

    @Test fun `缺车牌不推页且给出信息不完整提示`() {
        open("houche://vehicle?city=0592&name=91%E8%B7%AF&dir=2")
        assertEquals(listOf(R.string.vehicle_link_incomplete), messages)
        assertEquals(1, navigation.stack.size)
        assertEquals(0, calls.get())
    }

    @Test fun `非法方向不默认到另一方向`() {
        open(link(direction = "3"))
        assertEquals(listOf(R.string.vehicle_link_incomplete), messages)
        assertEquals(1, navigation.stack.size)
        assertEquals(0, calls.get())
    }

    @Test fun `线路存在但没有在线车辆仍打开详情且乘车站为空`() {
        open(link())
        assertEquals(R.string.vehicle_link_loading, messages.first())
        await { navigation.stack.size == 2 }
        val vehicle = navigation.stack.last().screen as Screen.Vehicle
        assertEquals("0592", vehicle.city.code)
        assertEquals("91路", vehicle.route.name)
        assertEquals("2", vehicle.route.direction)
        assertEquals("闽D28810", vehicle.plate)
        assertNull(vehicle.boarding)
        assertEquals(1, calls.get())
    }

    @Test fun `未知城市提示没找到且不发线路请求`() {
        open(link(cityCode = "unknown"))
        await { R.string.vehicle_link_city_not_found in messages }
        assertEquals(1, navigation.stack.size)
        assertEquals(0, calls.get())
    }

    @Test fun `线路空响应提示没找到且保留当前页`() {
        reply = { "{}" }
        open(link())
        await { R.string.vehicle_link_line_not_found in messages }
        assertEquals(1, navigation.stack.size)
    }

    @Test fun `断网提示查询失败且保留当前页`() {
        reply = { throw IOException("测试断网") }
        open(link())
        await { R.string.vehicle_link_failed in messages }
        assertEquals(1, navigation.stack.size)
    }

    @Test fun `新链接取消旧查询后只打开新车牌`() {
        reply = {
            assertTrue("释放阻塞查询", release.await(5, TimeUnit.SECONDS))
            """{"id":"line-91","name":"91路","stationList":[{"id":"stop","name":"终点","stationOrder":1,"upperOrDown":"2"}]}"""
        }
        open(link(plate = "旧车"))
        await { calls.get() > 0 }
        open(link(plate = "新车"))
        release.countDown()
        await { navigation.stack.size == 2 }
        assertEquals("新车", (navigation.stack.last().screen as Screen.Vehicle).plate)
        assertFalse(R.string.vehicle_link_failed in messages)
    }

    @Test fun `失效新链接也会取消旧查询且不推任何车辆页`() {
        reply = {
            assertTrue("释放阻塞查询", release.await(5, TimeUnit.SECONDS))
            """{"id":"line-91","name":"91路","stationList":[{"id":"stop","name":"终点","stationOrder":1,"upperOrDown":"2"}]}"""
        }
        open(link())
        await { calls.get() > 0 }
        open("houche://vehicle?city=0592")
        release.countDown()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(R.string.vehicle_link_incomplete, messages.last())
        assertEquals(1, navigation.stack.size)
    }

    @Test fun `已有异城编号不能代替目标城市的线路`() {
        navigation.push(Screen.Vehicle(City("other", "别城", "010"), Route("旧线路", "line-91", "2"), "旧车", null))
        open(VehicleShareLink.buildSchemeLink(city.code, "line-91", "91路", "2", "新车"))
        await { navigation.stack.size == 3 }
        val vehicle = navigation.stack.last().screen as Screen.Vehicle
        assertEquals("0592", vehicle.city.code)
        assertEquals("91路", vehicle.route.name)
        assertEquals("新车", vehicle.plate)
        assertEquals(1, calls.get())
    }

    private fun createActivity(intent: Intent): NavigationState {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        activity = controller
        return ViewModelProvider(controller.get())[NavigationState::class.java]
    }

    @Test fun `真实Activity冷启动从intent打开链接车辆`() {
        val coldNavigation = createActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link())))
        await { coldNavigation.stack.size == 2 }
        val vehicle = coldNavigation.stack.last().screen as Screen.Vehicle
        assertEquals("闽D28810", vehicle.plate)
        assertEquals("0592", vehicle.city.code)
        assertNull(vehicle.boarding)
        assertTrue(coldNavigation.initialVehicleIntentHandled)
    }

    @Test fun `真实Activity已打开时通过onNewIntent接收网页链接`() {
        val warmNavigation = createActivity(Intent(Intent.ACTION_MAIN))
        val incoming = Intent(Intent.ACTION_VIEW, Uri.parse(
            VehicleShareLink.buildWebLink("0592", null, "91路", "2", "闽D新车")
        ))
        activity!!.newIntent(incoming)
        await { warmNavigation.stack.size == 2 }
        assertEquals("闽D新车", (warmNavigation.stack.last().screen as Screen.Vehicle).plate)
        assertSame(incoming, activity!!.get().intent)
    }

    @Test fun `真实Activity无效深链显示Toast并保留首页`() {
        val coldNavigation = createActivity(Intent(Intent.ACTION_VIEW, Uri.parse("houche://vehicle?city=0592")))
        assertEquals(1, coldNavigation.stack.size)
        assertEquals("链接里的车辆信息不完整", ShadowToast.getTextOfLatestToast())
        assertEquals(0, calls.get())
    }

    @Test fun `真实Activity普通启动不会处理数据中的深链`() {
        val coldNavigation = createActivity(Intent(Intent.ACTION_MAIN, Uri.parse(link())))
        assertEquals(1, coldNavigation.stack.size)
        assertEquals(0, calls.get())
    }

    @Test fun `城市编号对应多个城市时不猜城市也不查询线路`() {
        open(link(cityCode = "0575_01"))
        await { R.string.vehicle_link_incomplete in messages }
        assertEquals(1, navigation.stack.size)
        assertEquals(0, calls.get())
    }

    @Test fun `本地线路隔离城市键而不是只比较区号`() {
        navigation.push(Screen.Vehicle(City("other", "伪同区号城市", "0592"), Route("旧线路", "line-91", "2"), "旧车", null))
        open(VehicleShareLink.buildSchemeLink(city.code, "line-91", "91路", "2", "新车"))
        await { navigation.stack.size == 3 }
        assertEquals("91路", (navigation.stack.last().screen as Screen.Vehicle).route.name)
        assertEquals(1, calls.get())
    }

    @Test fun `厦门返回名称编号但目标方向没有站表时提示线路没找到`() {
        reply = { """{"id":"line-91","name":"91路","stationList":[{"id":"stop","name":"终点","stationOrder":1,"upperOrDown":"1"}]}""" }
        open(link(direction = "2"))
        await { R.string.vehicle_link_line_not_found in messages }
        assertEquals(1, navigation.stack.size)
    }
}
