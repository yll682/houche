package com.yll682.houche.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用仓库里真实的 assets/metro.json 和 cities.json 做回归：
 * 曾经把 cityKey 写成 "xiamen"，而厦门市的真实 key 是 "11"，导致地铁整块静默消失，
 * 截图测试用的是演示数据所以没发现。这里直接读真实资源，堵住这类失配。
 */
class MetroDataTest {

    private val metro = Metro { File("src/main/assets/metro.json").inputStream() }
    private val xiamen = City(key = "11", name = "厦门市", code = "0592", province = "福建", pinyin = "xiamen")

    @Test fun `真实城市 key 能命中地铁资料`() {
        val network = kotlinx.coroutines.runBlocking { metro.networkFor(xiamen) }
        assertNotNull("厦门市(key=11) 必须能取到地铁资料，否则首页入口会消失", network)
        assertEquals(3, network!!.openedLines.size)
    }

    @Test fun `三条线的站数取自内置资料`() {
        val network = kotlinx.coroutines.runBlocking { metro.networkFor(xiamen) }!!
        val counts = network.openedLines.associate { it.id to it.liveStations.size }
        assertEquals(24, counts["1"])
        assertEquals(32, counts["2"])
        assertEquals(21, counts["3"])
    }

    @Test fun `每个 cityKey 都能被真实城市表命中`() {
        val network = kotlinx.coroutines.runBlocking { metro.network() }
        val cities = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<List<City>>(File("src/main/assets/cities.json").readText())
        // 资料里的 cityKey 必须在城市表里真实存在，否则 networkFor 永远返回 null。
        val hit = cities.any { it.key == network.cityKey || it.pinyin == network.cityKey || it.name == network.city }
        assertTrue("cityKey=${network.cityKey} 在城市表里找不到对应城市", hit)
    }

    @Test fun `换乘查询能跨线路命中`() {
        val calls = kotlinx.coroutines.runBlocking { metro.callingAt(xiamen, "吕厝") }
        assertEquals(listOf("1", "2"), calls.map { it.line.id }.sorted())
    }

    @Test fun `别的城市没有地铁资料`() {
        val other = City(key = "1", name = "北京市", pinyin = "beijing")
        assertNull(kotlinx.coroutines.runBlocking { metro.networkFor(other) })
    }

    @Test fun `关键词能搜到地铁线路`() {
        val hit = kotlinx.coroutines.runBlocking { metro.lines(xiamen, "地铁") }
        assertEquals(3, hit.size)
        assertTrue(kotlinx.coroutines.runBlocking { metro.lines(xiamen, "吕厝") }.isNotEmpty())
        assertTrue(kotlinx.coroutines.runBlocking { metro.lines(xiamen, "岩内") }.any { it.id == "1" })
    }
}