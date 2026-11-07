package com.yll682.houche.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 测试地铁页实际使用的加载流程，无需 Android 主线程或新增协程测试依赖。 */
class MetroLoaderTest {
    private val city = City(key = "11", name = "厦门市", code = "0592", pinyin = "xiamen")
    private val bundled get() = BusNetwork.json.decodeFromString<MetroNetwork>(MetroFixtures.file("metro-from-api.json").readText())
    private val metro = Metro(openIndex = { asset("metro-cities.json").inputStream() }) { asset("metro.json").inputStream() }
    private fun asset(name: String): File = sequenceOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }

    @Test fun `在线请求先启动且等待时立即展示内置随后采用在线三号线站序`() = runBlocking {
        withTimeout(5_000) {
            val pending = CompletableDeferred<MetroProbe>()
            val displayed = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val snapshots = mutableListOf<MetroSnapshot>()
            val loader = MetroLoader(
                fallback = { order += "读取内置"; bundled },
                latest = { order += "请求在线"; pending.await() },
                hasMetro = metro::supports
            )
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                loader.load(city).collect {
                    snapshots += it
                    displayed.complete(Unit)
                }
            }
            displayed.await()
            assertEquals(listOf("请求在线", "读取内置"), order)
            assertEquals(1, snapshots.size)
            assertFalse(snapshots.single().online)
            assertEquals(21, snapshots.single().network!!.lines.first { it.id == "3" }.stations.size)
            // 从真实响应明文变更线路归属和站序，验证解析结果会覆盖内置的二十一站。
            val root = MetroFixtures.plaintext as JsonObject
            val stations = root.list("data").filter { it.s("lineCode") == "01000000" }.reversed().mapIndexed { index, item ->
                JsonObject((item as JsonObject) + mapOf(
                    "lineCode" to JsonPrimitive("03000000"), "lineName" to JsonPrimitive("三号线"),
                    "stationCode" to JsonPrimitive(if (index == 0) "03010000" else "03020000"),
                    "firstBusTime" to JsonPrimitive("07:15"), "showFlag" to JsonPrimitive(false)
                ))
            }
            val response = MetroFixtures.envelope(JsonObject(root + ("data" to JsonArray(stations))).toString())
            pending.complete(MetroRemote().parse(response))
            job.join()
            val latest = snapshots.last()
            assertTrue(latest.online)
            assertEquals(listOf("3"), latest.network!!.lines.map { it.id })
            assertEquals(listOf("中山公园", "镇海路"), latest.network.lines.single().liveStations.map { it.name })
            assertEquals("07:15", latest.network.lines.single().stations.first().first)
            assertTrue(latest.network.lines.single().stations.all { !it.opened })
            assertEquals("", latest.network.updated)
        }
    }

    @Test fun `在线失败只展示一次内置不发出任何错误状态`() = runBlocking {
        val offline = bundled
        val loader = MetroLoader({ offline }, { MetroProbe(false, "请求超时或维护中") }, metro::supports)
        val snapshots = loader.load(city).toList()
        assertEquals(listOf(MetroSnapshot(offline)), snapshots)
    }

    @Test fun `内置没有的六号线和新增线路仍能在线显示`() = runBlocking {
        val offline = bundled.copy(lines = bundled.lines.filter { it.id != "6" })
        val six = bundled.lines.first { it.id == "6" }
        val extra = six.copy(id = "7", name = "地铁新增线路")
        val snapshots = MetroLoader({ offline }, { MetroProbe(true, "在线", listOf(six, extra)) }, metro::supports).load(city).toList()
        assertTrue(snapshots.last().online)
        assertEquals(listOf("6", "7"), snapshots.last().network!!.openedLines.map { it.id })
    }

    @Test fun `厦门内置资料不可用也不阻止在线加载`() = runBlocking {
        val probe = MetroRemote().parse(MetroFixtures.sample.s("rawResponse"))
        val snapshots = MetroLoader({ null }, { probe }, metro::supports).load(city).toList()
        assertTrue(snapshots.single().online)
        assertEquals(probe.lines, snapshots.single().network!!.lines)
    }

    @Test fun `其他城市不请求厦门站表`() = runBlocking {
        var requested = false
        val loader = MetroLoader(metro::networkFor, { requested = true; MetroProbe(false, "不应请求") }, metro::supports)
        for (code in listOf("", "0592")) {
            assertNull(loader.load(City(key = "1", name = "北京市", code = code)).toList().single().network)
            assertFalse(requested)
        }
    }

    @Test fun `城市编号或市名命中资料时不依赖区号且在线先于内置读取`() = runBlocking {
        withTimeout(5_000) {
            val cities = listOf(city.copy(code = "已变更"), City(key = "11", name = ""), City(key = "", name = "厦门市"))
            for (candidate in cities) {
                val order = mutableListOf<String>()
                val pending = CompletableDeferred<MetroProbe>()
                val source = Metro(openIndex = { asset("metro-cities.json").inputStream() }) {
                    order += "打开完整内置站表"
                    asset("metro.json").inputStream()
                }
                val loader = MetroLoader(source::networkFor, {
                    order += "请求在线"
                    pending.await()
                }, source::supports)
                val job = launch {
                    loader.load(candidate).collect {
                        if (!it.online) {
                            assertEquals(listOf("请求在线", "打开完整内置站表"), order)
                            pending.complete(MetroProbe(true, "在线", bundled.lines))
                        }
                    }
                }
                job.join()
                assertEquals(listOf("请求在线", "打开完整内置站表"), order)
            }
        }
    }

    @Test fun `离开页面会取消仍在等待的在线请求`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val loader = MetroLoader({ bundled }, {
                try { started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
            }, metro::supports)
            val job = async { loader.load(city).toList() }
            started.await()
            job.cancelAndJoin()
            cancelled.await()
        }
    }
}
