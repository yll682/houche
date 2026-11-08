package com.yll682.houche.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** 直接读取真实内置资源与官方抓取基准，避免演示资料掩盖城市、站数或状态失配。 */
class MetroDataTest {
    private fun asset(name: String): File = sequenceOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }
    private val metro = Metro(openIndex = { asset("metro-cities.json").inputStream() }) { asset("metro.json").inputStream() }
    private val xiamen = City(key = "11", name = "厦门市", code = "0592", province = "福建", pinyin = "xiamen")

    @Test fun `真实城市 key 能命中五条地铁线路`() = runBlocking {
        val network = metro.networkFor(xiamen)
        assertNotNull("厦门市必须能取到兜底资料", network)
        assertEquals(listOf("1", "2", "3", "4", "6"), network!!.openedLines.map { it.id })
    }

    @Test fun `连续两次查询同一城市复用完整且一致的缓存`() = runBlocking {
        val indexReads = AtomicInteger()
        val networkReads = AtomicInteger()
        val source = Metro(openIndex = {
            indexReads.incrementAndGet()
            asset("metro-cities.json").inputStream()
        }) {
            networkReads.incrementAndGet()
            asset("metro.json").inputStream()
        }
        val first = source.networkFor(xiamen)
        assertNotNull(first)
        assertSame(first, source.networkFor(xiamen))
        assertEquals(1, indexReads.get())
        assertEquals(1, networkReads.get())
    }

    @Test fun `索引读取或解析首次失败不会缓存不支持城市的结论`() = runBlocking {
        for (malformed in listOf(false, true)) {
            val reads = AtomicInteger()
            val source = Metro(openIndex = {
                if (reads.incrementAndGet() == 1) {
                    if (!malformed) throw IOException("索引首次读取失败")
                    "[{".byteInputStream()
                } else asset("metro-cities.json").inputStream()
            }) { asset("metro.json").inputStream() }
            assertLoadFailure { source.networkFor(xiamen) }
            assertTrue(source.supports(xiamen))
            assertNotNull(source.networkFor(xiamen))
            assertEquals(2, reads.get())
        }
    }

    @Test fun `站表读取或解析首次失败不污染缓存且后续可重试`() = runBlocking {
        for (malformed in listOf(false, true)) {
            val reads = AtomicInteger()
            val source = Metro(openIndex = { asset("metro-cities.json").inputStream() }) {
                if (reads.incrementAndGet() == 1) {
                    if (!malformed) throw IOException("站表首次读取失败")
                    "{\"lines\":[".byteInputStream()
                } else asset("metro.json").inputStream()
            }
            assertLoadFailure { source.networkFor(xiamen) }
            assertTrue(source.supports(xiamen))
            val recovered = source.networkFor(xiamen)
            assertNotNull(recovered)
            assertEquals(5, recovered!!.openedLines.size)
            assertSame(recovered, source.networkFor(xiamen))
            assertSame(recovered, source.network())
            assertEquals(2, reads.get())
        }
    }

    @Test fun `并发查询城市索引和完整站表只发布一次成功缓存`() = runBlocking {
        withTimeout(5_000) {
            val indexReads = AtomicInteger()
            val networkReads = AtomicInteger()
            val source = Metro(openIndex = {
                indexReads.incrementAndGet()
                asset("metro-cities.json").inputStream()
            }) {
                networkReads.incrementAndGet()
                asset("metro.json").inputStream()
            }
            (0 until 30).map { index ->
                async(Dispatchers.Default) {
                    when (index % 3) {
                        0 -> assertTrue(source.supports(xiamen))
                        1 -> assertEquals(5, source.network().openedLines.size)
                        else -> assertEquals(5, source.networkFor(xiamen)!!.openedLines.size)
                    }
                }
            }.awaitAll()
            assertEquals(1, indexReads.get())
            assertEquals(1, networkReads.get())
        }
    }

    private suspend fun assertLoadFailure(load: suspend () -> Unit) {
        try {
            load()
            fail("读取或解析失败必须抛出异常，不能缓存为没有资料")
        } catch (error: BusException) {
            assertNotNull(error.cause)
        }
    }

    @Test fun `城市索引与内置站表一致且区号变更不影响匹配`() = runBlocking {
        val index = BusNetwork.json.decodeFromString<List<MetroCity>>(asset("metro-cities.json").readText())
        val network = metro.network()
        assertEquals(listOf(MetroCity(network.city, network.cityKey)), index)
        assertTrue(network.openedLines.isNotEmpty())
        assertTrue(metro.supports(xiamen.copy(code = "已变更")))
        assertNotNull(metro.networkFor(xiamen.copy(code = "")))
        assertTrue(metro.supports(City(key = "11", name = "")))
        assertTrue(metro.supports(City(key = "", name = "厦门市")))
        assertFalse(metro.supports(City(key = "1", name = "北京市", code = "0592")))
        assertFalse(metro.supports(City(key = "", name = "")))
    }

    @Test fun `查询城市索引和不支持的城市不读取完整内置站表`() = runBlocking {
        var opened = false
        val source = Metro(openIndex = { asset("metro-cities.json").inputStream() }) {
            opened = true
            asset("metro.json").inputStream()
        }
        assertTrue(source.supports(xiamen))
        assertNull(source.networkFor(City(key = "1", name = "北京市", code = "0592")))
        assertFalse(opened)
    }

    @Test fun `内置兜底除逐站核实的开通状态线路码和官方换乘字段外与原始抓取逐字段一致`() {
        val bundled = BusNetwork.json.parseToJsonElement(asset("metro.json").readText()) as JsonObject
        val official = BusNetwork.json.parseToJsonElement(MetroFixtures.file("metro-from-api.json").readText())
        // 原始抓取仍保留 showFlag=false 的历史状态；只允许已核实站码的开通修正，
        // 不能为了让资产通过测试而覆盖历史证据，或顺手改掉时刻、坐标和站序。
        val originalLines = bundled.list("lines").map { line ->
            val approved = MetroOpenedLineEvidence.stationCodes[line.s("id")].orEmpty()
            val value = line as JsonObject
            if (approved.isNotEmpty()) assertEquals(MetroOpenedLineEvidence.apiCodes[line.s("id")], value.s("apiCode"))
            val stations = value.list("stations").map { station ->
                val legacy = JsonObject((station as JsonObject).filterKeys {
                    it !in setOf("stationUniqueCode", "changeFlag", "coordinateSystem")
                })
                if (station.s("code") !in approved) legacy else {
                    assertEquals("true", station.s("opened"))
                    JsonObject(legacy + ("opened" to JsonPrimitive(false)))
                }
            }
            val original = if (approved.isEmpty()) value else JsonObject(value.filterKeys { it != "apiCode" })
            JsonObject(original + ("stations" to JsonArray(stations)))
        }
        assertEquals(official, JsonObject(bundled + ("lines" to JsonArray(originalLines))))
    }

    @Test fun `五条线共一百零二站且保留官方线路色`() = runBlocking {
        val lines = metro.networkFor(xiamen)!!.openedLines
        assertEquals(mapOf("1" to 24, "2" to 32, "3" to 21, "4" to 8, "6" to 17), lines.associate { it.id to it.liveStations.size })
        assertEquals(102, lines.sumOf { it.liveStations.size })
        assertEquals(mapOf("1" to "#DA7028", "2" to "#52A441", "3" to "#52B5C8", "4" to "#E50012", "6" to "#92308D"), lines.associate { it.id to it.color })
    }

    @Test fun `已开通线路完整展示且混合站表不会过滤未运营站点`() = runBlocking {
        val lines = metro.networkFor(xiamen)!!.openedLines
        assertEquals(mapOf("1" to 0, "2" to 0, "3" to 0, "4" to 0, "6" to 0), lines.associate { it.id to it.pendingCount })
        val mixed = MetroLine("测试", "测试线路", stations = listOf(MetroStation(2, "未运营站", opened = false), MetroStation(1, "运营站")))
        assertEquals(listOf("运营站", "未运营站"), mixed.liveStations.map { it.name })
        assertEquals(1, mixed.pendingCount)
    }

    @Test fun `逐站时间坐标与站码保留且站序递增`() = runBlocking {
        val lines = metro.networkFor(xiamen)!!.openedLines
        lines.forEach { line ->
            assertEquals(line.stations.size, line.stations.map { it.code }.distinct().size)
            assertEquals(line.stations.map { it.code }.sorted(), line.liveStations.map { it.code })
            assertEquals((1..line.stations.size).toList(), line.liveStations.map { it.order })
        }
        val station = lines.first().stations.first()
        assertEquals("01010000", station.code)
        assertEquals("06:00", station.first)
        assertEquals("23:00", station.last)
        assertEquals("118.089852", station.lng)
        assertEquals("24.457147", station.lat)
        assertEquals("集美软件园北", lines.first { it.id == "4" }.from)
        assertEquals("角江路", lines.first { it.id == "6" }.from)
    }

    @Test fun `资料城市能被真实城市表命中`() = runBlocking {
        val network = metro.network()
        val cities = BusNetwork.json.decodeFromString<List<City>>(asset("cities.json").readText())
        assertTrue(cities.any { it.key == network.cityKey && it.name == network.city })
    }

    @Test fun `同名站的换乘查询与标注双向对称`() = runBlocking {
        val calls = metro.callingAt(xiamen, "吕厝站")
        assertEquals(listOf("1", "2"), calls.map { it.line.id }.sorted())
        assertEquals(listOf("二号线"), calls.first { it.line.id == "1" }.station.transfer)
        assertEquals(listOf("一号线"), calls.first { it.line.id == "2" }.station.transfer)
        val hongkeng = metro.callingAt(xiamen, "洪坑")
        assertEquals(listOf("四号线"), hongkeng.first { it.line.id == "3" }.station.transfer)
        assertEquals(listOf("三号线"), hongkeng.first { it.line.id == "4" }.station.transfer)
    }

    @Test fun `官方站表未提供的国铁与 BRT 换乘不补造`() = runBlocking {
        val calls = metro.callingAt(xiamen, "厦门北站")
        assertEquals(listOf("1", "4"), calls.map { it.line.id }.sorted())
        assertTrue(calls.all { "国铁" !in it.station.transfer && "BRT" !in it.station.transfer })
    }

    @Test fun `别的城市没有厦门地铁资料`() = runBlocking {
        assertNull(metro.networkFor(City(key = "1", name = "北京市", pinyin = "beijing")))
    }

    @Test fun `关键词能搜到五条线及四号线六号线站点`() = runBlocking {
        assertEquals(5, metro.lines(xiamen, "地铁").size)
        assertTrue(metro.lines(xiamen, "吕厝").isNotEmpty())
        assertTrue(metro.lines(xiamen, "岩内").any { it.id == "1" })
        assertTrue(metro.lines(xiamen, "集美软件园北").any { it.id == "4" })
        assertTrue(metro.lines(xiamen, "角江路").any { it.id == "6" })
    }
}
