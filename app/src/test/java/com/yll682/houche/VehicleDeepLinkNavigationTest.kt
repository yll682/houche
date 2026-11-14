package com.yll682.houche

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
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

    private fun `线路链接`(
        cityCode: String = "0592",
        lineId: String? = null,
        name: String = "91路",
        direction: String = "2"
    ) = VehicleShareLink.buildLineSchemeLink(cityCode, lineId, name, direction)

    private fun `断言线路页面`(
        state: NavigationState = navigation,
        expectedCity: City = city,
        name: String = "91路",
        direction: String = "2"
    ): Screen.Line {
        val screen = state.stack.last().screen
        assertTrue("分享线路应打开线路页，实际为 $screen", screen is Screen.Line)
        val page = screen as Screen.Line
        assertEquals(expectedCity.key, page.city.key)
        assertEquals(expectedCity.name, page.city.name)
        assertEquals(expectedCity.code, page.city.code)
        assertEquals(LineSummary(name = name, direction = direction), page.line)
        assertNull("线路分享不附带乘车站", page.station)
        return page
    }

    private fun `断言提示资源可读`(message: Int) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("string", app.resources.getResourceTypeName(message))
        assertTrue("提示资源应能解析为可读文字", app.getString(message).any { it.isLetter() })
    }

    private fun `断言无效线路参数被拒绝`(vararg queries: String) {
        val previous = navigation.stack.toList()
        for (prefix in listOf("houche://line?", "https://houche.rainlodyn.cn/share/?type=line&")) {
            for (query in queries) {
                val uri = prefix + query
                messages.clear()
                open(uri)
                assertEquals("无效线路链接应提示信息不完整：$uri", listOf(R.string.vehicle_link_incomplete), messages)
                `断言提示资源可读`(messages.single())
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("无效线路链接不能改动当前页面：$uri", previous, navigation.stack.toList())
                assertEquals("无效线路链接不能查询网络：$uri", 0, calls.get())
            }
        }
    }

    private fun `开始阻塞车辆查询`(): Pair<Job, CountDownLatch> {
        val returned = CountDownLatch(1)
        reply = {
            try {
                assertTrue("释放旧车辆阻塞查询", release.await(10, TimeUnit.SECONDS))
                """{"id":"line-91","name":"91路","stationList":[{"id":"stop","name":"终点","stationOrder":1,"upperOrDown":"2"}]}"""
            } finally {
                returned.countDown()
            }
        }
        open(link(plate = "旧车"))
        await { calls.get() == 1 }
        assertEquals(listOf(R.string.vehicle_link_loading), messages)
        val job = navigation.viewModelScope.coroutineContext[Job]!!.children.single()
        return job to returned
    }

    @Test fun `线路协议无车牌和类型参数也直接打开线路页`() {
        val home = navigation.stack.single()
        open("houche://line?city=0592&name=91%E8%B7%AF&dir=2")
        await { navigation.stack.size == 2 }
        `断言线路页面`()
        assertSame(home, navigation.stack.first())
        assertTrue("线路入口不显示车辆加载提示", messages.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun `线路携带过期编号仍按名称方向打开且不预查详情`() {
        open(`线路链接`(lineId = "expired-line-id", name = "19路（区间）", direction = "1"))
        await { navigation.stack.size == 2 }
        `断言线路页面`(name = "19路（区间）", direction = "1")
        assertTrue(messages.isEmpty())
        assertEquals("线路编号不应触发线路详情预查询", 0, calls.get())
    }

    @Test fun `网页线路类型无车牌也直接打开线路页`() {
        open("https://houche.rainlodyn.cn/share/?type=line&city=0592&line=expired-line-id&name=91%E8%B7%AF&dir=1")
        await { navigation.stack.size == 2 }
        `断言线路页面`(direction = "1")
        assertTrue("网页线路入口不显示车辆加载提示", messages.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun `线路跨城入口使用目标城市资料并保留名称方向`() {
        navigation.push(Screen.Search(city))
        val previous = navigation.stack.toList()
        open(`线路链接`(cityCode = "0512", lineId = "opaque-id", name = "游1路", direction = "1"))
        await { navigation.stack.size == 3 }
        val page = `断言线路页面`(expectedCity = City("115", "苏州市", "0512"), name = "游1路", direction = "1")
        assertEquals("江苏", page.city.province)
        assertEquals("suzhou", page.city.pinyin)
        assertEquals(previous, navigation.stack.take(previous.size))
        assertTrue(messages.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun `线路缺少或留空城市时拒绝且提示资源可读`() {
        `断言无效线路参数被拒绝`(
            "name=91%E8%B7%AF&dir=2",
            "city=&name=91%E8%B7%AF&dir=2",
            "city=%20&name=91%E8%B7%AF&dir=2"
        )
    }

    @Test fun `线路缺少或留空名称时编号也不能替代且提示资源可读`() {
        `断言无效线路参数被拒绝`(
            "city=0592&line=line-91&dir=2",
            "city=0592&line=line-91&name=&dir=2",
            "city=0592&line=line-91&name=%20&dir=2"
        )
    }

    @Test fun `线路缺失或非法方向在查询城市前拒绝且提示资源可读`() {
        `断言无效线路参数被拒绝`(
            "city=unknown&name=91%E8%B7%AF",
            "city=unknown&name=91%E8%B7%AF&dir=",
            "city=unknown&name=91%E8%B7%AF&dir=0",
            "city=unknown&name=91%E8%B7%AF&dir=3",
            "city=unknown&name=91%E8%B7%AF&dir=-1",
            "city=unknown&name=91%E8%B7%AF&dir=01",
            "city=unknown&name=91%E8%B7%AF&dir=other",
            "city=unknown&name=91%E8%B7%AF&dir=%20"
        )
    }

    @Test fun `线路未知城市使用城市没找到资源且不推页不查询网络`() {
        navigation.push(Screen.Search(city))
        navigation.cityVisible = true
        val previous = navigation.stack.toList()
        open(`线路链接`(cityCode = "unknown", lineId = "line-91"))
        await { messages.isNotEmpty() }
        assertEquals(listOf(R.string.vehicle_link_city_not_found), messages)
        `断言提示资源可读`(messages.single())
        assertEquals(previous, navigation.stack.toList())
        assertTrue("入口失败保留城市选择浮层", navigation.cityVisible)
        assertEquals(0, calls.get())
    }

    @Test fun `线路城市区号存在歧义时拒绝且不查询网络`() {
        open(`线路链接`(cityCode = "0575_01"))
        await { messages.isNotEmpty() }
        assertEquals(listOf(R.string.vehicle_link_incomplete), messages)
        `断言提示资源可读`(messages.single())
        assertEquals(1, navigation.stack.size)
        assertEquals(0, calls.get())
    }

    @Test fun `城市选择浮层中打开线路会关浮层并保留原页面`() {
        navigation.push(Screen.Search(City("115", "苏州市", "0512")))
        navigation.cityVisible = true
        val previous = navigation.stack.toList()
        open(`线路链接`())
        await { navigation.stack.size == 3 }
        `断言线路页面`()
        assertFalse("成功进入线路页必须关闭城市选择浮层", navigation.cityVisible)
        assertEquals(previous, navigation.stack.take(previous.size))
        assertTrue(messages.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun `线路入口不等待车辆仓库初始化也不查询网络`() {
        val pending = AppGraph::class.java.getDeclaredField("pending").apply { isAccessible = true }
        val previous = pending.get(graph)
        val unresolved = CompletableDeferred<BusRepository>()
        pending.set(graph, unresolved)
        try {
            open(`线路链接`(lineId = "line-91"))
            await { navigation.stack.size == 2 }
            `断言线路页面`()
            assertFalse("线路入口只依赖城市资料", unresolved.isCompleted)
            assertTrue(messages.isEmpty())
            assertEquals(0, calls.get())
        } finally {
            pending.set(graph, previous)
        }
    }

    @Test fun `线路城市资料加载失败时提示资源可读且保留当前页面`() {
        val cities = AppGraph::class.java.getDeclaredField("cities").apply { isAccessible = true }
        val previousCities = cities.get(graph)
        val previous = navigation.stack.toList()
        cities.set(graph, Cities { throw IOException("测试城市资料读取失败") })
        try {
            open(`线路链接`())
            await { messages.isNotEmpty() }
            assertEquals(listOf(R.string.vehicle_link_failed), messages)
            `断言提示资源可读`(messages.single())
            assertEquals(previous, navigation.stack.toList())
            assertEquals(0, calls.get())
        } finally {
            cities.set(graph, previousCities)
        }
    }

    @Test fun `新线路取消阻塞车辆查询并在旧响应释放后仍不推车辆页`() {
        val (oldJob, returned) = `开始阻塞车辆查询`()
        messages.clear()
        open(`线路链接`(lineId = "new-line-id", name = "19路", direction = "1"))
        assertTrue("新线路入口应立即取消旧车辆任务", oldJob.isCancelled)
        await { navigation.stack.size == 2 }
        assertEquals("无需等待旧车辆响应即可打开线路", 1L, release.count)
        `断言线路页面`(name = "19路", direction = "1")
        val lineEntry = navigation.stack.last()
        assertTrue("新线路不能显示车辆加载或失败提示", messages.isEmpty())
        assertEquals("仅旧车辆请求访问网络", 1, calls.get())

        release.countDown()
        await { returned.count == 0L && oldJob.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, navigation.stack.size)
        assertSame("旧请求结束后仍保留新线路页", lineEntry, navigation.stack.last())
        assertFalse(navigation.stack.any { it.screen is Screen.Vehicle })
        assertTrue("已取消请求不能迟到提示查询失败", messages.isEmpty())
        assertEquals(1, calls.get())
    }

    @Test fun `失效新线路同样取消阻塞车辆查询且不迟到推页`() {
        val (oldJob, returned) = `开始阻塞车辆查询`()
        navigation.cityVisible = true
        val previous = navigation.stack.toList()
        messages.clear()
        open("houche://line?city=0592&dir=2")
        assertTrue("无效的新线路也应取消旧车辆任务", oldJob.isCancelled)
        assertEquals(listOf(R.string.vehicle_link_incomplete), messages)
        `断言提示资源可读`(messages.single())

        release.countDown()
        await { returned.count == 0L && oldJob.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(previous, navigation.stack.toList())
        assertTrue(navigation.cityVisible)
        assertEquals("旧车辆取消后不追加失败提示", listOf(R.string.vehicle_link_incomplete), messages)
        assertEquals("只有被取消的旧车辆发出请求", 1, calls.get())
    }

    @Test fun `真实页面冷启动接收线路协议且不显示车辆加载提示`() {
        ShadowToast.reset()
        val coldNavigation = createActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
            "houche://line?city=0592&name=91%E8%B7%AF&dir=2"
        )))
        await { coldNavigation.stack.size == 2 }
        `断言线路页面`(state = coldNavigation)
        assertTrue(coldNavigation.initialVehicleIntentHandled)
        assertNull("线路冷启动不应显示车辆加载提示", ShadowToast.getTextOfLatestToast())
        assertEquals(0, calls.get())
    }

    @Test fun `真实页面热启动接收网页线路并关闭城市浮层`() {
        val warmNavigation = createActivity(Intent(Intent.ACTION_MAIN))
        warmNavigation.cityVisible = true
        ShadowToast.reset()
        val incoming = Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://houche.rainlodyn.cn/share/?type=line&city=0512&line=expired-line-id&name=%E6%B8%B81%E8%B7%AF&dir=1"
        ))
        activity!!.newIntent(incoming)
        await { warmNavigation.stack.size == 2 }
        `断言线路页面`(state = warmNavigation, expectedCity = City("115", "苏州市", "0512"), name = "游1路", direction = "1")
        assertFalse(warmNavigation.cityVisible)
        assertSame(incoming, activity!!.get().intent)
        assertNull("线路热启动不应显示车辆加载提示", ShadowToast.getTextOfLatestToast())
        assertEquals(0, calls.get())
    }

    @Test fun `真实页面无效线路显示可读资源提示并保留首页`() {
        ShadowToast.reset()
        val coldNavigation = createActivity(Intent(Intent.ACTION_VIEW, Uri.parse("houche://line?city=0592&dir=2")))
        assertEquals(1, coldNavigation.stack.size)
        `断言提示资源可读`(R.string.vehicle_link_incomplete)
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(app.getString(R.string.vehicle_link_incomplete), ShadowToast.getTextOfLatestToast())
        assertEquals(0, calls.get())
    }

    @Test fun `真实页面普通启动不会处理数据中的线路链接`() {
        ShadowToast.reset()
        val coldNavigation = createActivity(Intent(Intent.ACTION_MAIN, Uri.parse(`线路链接`())))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, coldNavigation.stack.size)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, calls.get())
    }
}
