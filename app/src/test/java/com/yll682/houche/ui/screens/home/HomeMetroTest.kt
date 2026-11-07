package com.yll682.houche.ui.screens.home

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 真实资源和偏好，主线程由 Robolectric 驱动；无需协程测试依赖或在线请求。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeMetroTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private fun asset(name: String) = app.assets.open(name)
    private fun city(graph: AppGraph) = runBlocking { graph.cities.all().single { it.name == "厦门市" } }
    private fun <T : ViewModel> keep(key: String, vm: T): T = vm.also { store.put(key, it) }

    @After fun clear() { store.clear() }

    @Test fun `首页与设置读取同一份厦门资料且收藏更新不丢地铁入口`() {
        val graph = AppGraph(app)
        val city = city(graph)
        assertEquals("11", city.key)
        val home = keep("home", HomeViewModel(graph, city))
        val settings = keep("settings", SettingsViewModel(graph, city))
        await { home.state.value.data?.hasMetro == true && settings.state.value.data?.metroSource?.isNotBlank() == true }
        assertFalse(home.state.value is UiState.Error)
        assertFalse(settings.state.value is UiState.Error)
        assertEquals(runBlocking { graph.metro.networkFor(city)!!.source }, settings.state.value.data!!.metroSource)
        val saved = SavedLine(city, LineSummary("地铁入口回归线路", "1"))
        runBlocking { graph.prefs.toggleLine(saved) }
        await { saved in home.state.value.data!!.favoriteLines }
        assertTrue(home.state.value.data!!.hasMetro)
        runBlocking { graph.prefs.toggleLine(saved) }
        await { saved !in home.state.value.data!!.favoriteLines }
        assertTrue(home.state.value.data!!.hasMetro)
    }

    @Test fun `索引首次读取失败会自动重试且首页不进入错误态`() {
        val reads = AtomicInteger()
        val networkReads = AtomicInteger()
        val metro = Metro(openIndex = {
            if (reads.incrementAndGet() == 1) throw IOException("索引暂时不可读")
            asset("metro-cities.json")
        }) {
            networkReads.incrementAndGet()
            asset("metro.json")
        }
        val graph = AppGraph(app, metro)
        val home = keep("home", HomeViewModel(graph, city(graph)))
        await { home.state.value.data!!.hasMetro }
        assertEquals(2, reads.get())
        assertEquals("首页入口只需轻量城市索引", 0, networkReads.get())
        assertFalse(home.state.value is UiState.Error)
    }

    @Test fun `两次索引读取失败后仍可通过刷新重新恢复入口`() {
        val failIndex = AtomicBoolean(true)
        val reads = AtomicInteger()
        val metro = Metro(openIndex = {
            reads.incrementAndGet()
            if (failIndex.get()) throw IOException("索引暂时不可读")
            asset("metro-cities.json")
        }) { asset("metro.json") }
        val graph = AppGraph(app, metro)
        val home = keep("home", HomeViewModel(graph, city(graph)))
        await { reads.get() == 2 }
        assertFalse(home.state.value.data!!.hasMetro)
        assertFalse(home.state.value is UiState.Error)
        failIndex.set(false)
        await {
            home.refreshMetro()
            home.state.value.data!!.hasMetro
        }
        assertEquals(3, reads.get())
    }

    @Test fun `站表首次读取失败不隐藏首页入口且设置后续读取可恢复`() {
        val reads = AtomicInteger()
        val metro = Metro(openIndex = { asset("metro-cities.json") }) {
            if (reads.incrementAndGet() == 1) throw IOException("站表首次读取失败")
            asset("metro.json")
        }
        val graph = AppGraph(app, metro)
        val city = city(graph)
        val home = keep("home", HomeViewModel(graph, city))
        val failedSettings = keep("failed-settings", SettingsViewModel(graph, city))
        await { home.state.value.data!!.hasMetro && failedSettings.state.value is UiState.Error }
        assertFalse(home.state.value is UiState.Error)
        val recoveredSettings = keep("settings", SettingsViewModel(graph, city))
        await { recoveredSettings.state.value.data!!.metroSource.isNotBlank() }
        assertTrue(home.state.value.data!!.hasMetro)
        assertEquals(2, reads.get())
    }

    @Test fun `在线请求等待或失败都不影响同一资料实例上的首页入口`() = runBlocking {
        withTimeout(5_000) {
            val graph = AppGraph(app)
            val city = city(graph)
            val request = CompletableDeferred<MetroProbe>()
            val started = CompletableDeferred<Unit>()
            val loader = MetroLoader(graph.metro::networkFor, {
                started.complete(Unit)
                request.await()
            }, graph.metro::supports)
            val loading = async(Dispatchers.Default) { loader.load(city).toList() }
            started.await()
            val home = keep("home", HomeViewModel(graph, city))
            await { home.state.value.data!!.hasMetro }
            assertFalse(loading.isCompleted)
            request.complete(MetroProbe(false, "离线回归：在线请求失败"))
            val snapshots = loading.await()
            assertEquals(1, snapshots.size)
            assertFalse(snapshots.single().online)
            assertNotNull(snapshots.single().network)
            home.refreshMetro()
            await { home.state.value.data!!.hasMetro }
            assertFalse(home.state.value is UiState.Error)
        }
    }

    @Test fun `挂起读取地铁索引期间产生的定位权限状态不会被旧数据覆盖`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val metro = Metro(openIndex = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "等待测试释放索引读取超时" }
            asset("metro-cities.json")
        }) { asset("metro.json") }
        val graph = AppGraph(app, metro)
        val home = keep("home", HomeViewModel(graph, city(graph)))
        try {
            await { started.count == 0L }
            home.denied()
            release.countDown()
            await { home.state.value.data!!.hasMetro }
            assertTrue(home.state.value.data!!.permissionDenied)
        } finally {
            release.countDown()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("等待首页或设置状态更新超时")
    }
}
