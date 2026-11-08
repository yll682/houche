package com.yll682.houche.core

import com.yll682.houche.ui.screens.metro.MetroData
import com.yll682.houche.ui.screens.metro.beginRealtimeRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 用户确认四、六号线已开通；本表只列出 allStation activeFlag=true 且真实实时
 * wayStations 逐码一致的站点。缺号并不代表漏站，不能按数字范围补出不存在的站。
 */
internal object MetroOpenedLineEvidence {
    val apiCodes = mapOf("4" to "04000000", "6" to "06000000")
    val stationCodes = mapOf(
        "4" to listOf("04010000", "04020000", "04030000", "04040000", "04050000", "04060000", "04080000", "04090000"),
        "6" to listOf("06030000", "06040000", "06050000", "06060000", "06070000", "06080000", "06090000", "06100000", "06110000", "06130000", "06140000", "06150000", "06160000", "06170000", "06180000", "06190000", "06200000")
    )
}

/** 真实历史密文通过 MockEngine 离线回放；成功不表示当前线上可达。 */
class MetroOpenedLinesTest {
    private fun network(): MetroNetwork {
        val file = sequenceOf(File("src/main/assets/metro.json"), File("app/src/main/assets/metro.json")).first { it.isFile }
        return BusNetwork.json.decodeFromString(file.readText())
    }

    private fun resource(name: String): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/metro-opened-lines/$name")
    ) { "缺少真实历史响应 $name" }.use { it.readBytes() }

    @Test fun `仅核实站码的四号线六号线更新运营状态且不改变官方线路码`() {
        val raw = BusNetwork.json.parseToJsonElement(MetroFixtures.file("metro-round2/allstation.json").readText())
        val official = BusNetwork.json.parseToJsonElement(checkNotNull(MetroGateway.decrypt(raw.s("responseData"))))
        assertEquals(0, official.i("code"))
        for ((id, codes) in MetroOpenedLineEvidence.stationCodes) {
            val apiCode = MetroOpenedLineEvidence.apiCodes.getValue(id)
            val line = network().lines.single { it.id == id }
            val stations = official.list("data").filter { it.s("lineCode") == apiCode }
            assertEquals(codes, stations.map { it.s("stationCode") }.sorted())
            assertTrue(stations.all { it.s("activeFlag") == "true" })
            assertTrue("历史隐藏标记不能再作为整线未开通结论", stations.all { it.s("showFlag") == "false" })
            assertEquals(codes, line.liveStations.map { it.code })
            assertTrue(line.opened)
            assertTrue(line.stations.all { it.opened })
            assertEquals(0, line.pendingCount)
            assertEquals(apiCode, line.apiCode)
            assertEquals(apiCode, line.realtimeCode())
            assertEquals("旧站码推导也必须与真实 lineCode 一致", apiCode, line.copy(apiCode = "").realtimeCode())
            assertEquals(stations.map { it.s("name") }.sorted(), line.stations.map { it.name }.sorted())
        }
    }

    @Test fun `真实四号线六号线响应离线通过实际请求路径并提供可绘位置`() = runBlocking {
        val manifest = BusNetwork.json.parseToJsonElement(resource("manifest.json").toString(Charsets.UTF_8))
        for (capture in manifest.list("captures")) {
            val raw = resource(capture.s("file"))
            val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            assertEquals(capture.s("sha256"), sha)
            val apiCode = capture.s("lineCode")
            val reference = capture.s("referenceStationCode")
            val line = network().lines.single { it.apiCode == apiCode }
            val prepared = checkNotNull(MetroData(lines = listOf(line), selected = line.id, realtimeStation = reference)
                .beginRealtimeRequest(1L)).second
            assertEquals(apiCode, prepared.lineCode)
            assertEquals(reference, prepared.stationCode)
            assertEquals(line.liveStations.map { it.code }, prepared.stationCodes)
            var requests = 0
            val engine = MockEngine { request ->
                requests++
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("app.i-amtr.com", request.url.host)
                assertEquals("/iets-web-app/pub/transTime/getTransTimes", request.url.encodedPath)
                assertEquals(apiCode, request.url.parameters["lineCode"])
                assertEquals(reference, request.url.parameters["stationCode"])
                respond(raw.toString(Charsets.UTF_8))
            }
            HttpClient(engine) { install(HttpTimeout) }.use { http ->
                val result = MetroRealtimeRemote(http).latest(prepared.lineCode, prepared.stationCode)
                assertTrue("这是历史响应的离线回放，不是线上可达性探针", result.online)
                assertEquals(1, requests)
                assertEquals(apiCode, result.lineCode)
                assertEquals(reference, result.stationCode)
                assertEquals(capture.s("serverTimestamp").toLong(), result.source!!.serverEpochMillis)
                assertEquals(2, result.directions.size)
                result.directions.forEach { direction ->
                    assertEquals(line.stations.map { it.code }.toSet(), direction.stationCodes.toSet())
                    assertEquals(3, direction.trains.size)
                    direction.trains.forEach { train ->
                        val position = direction.positionOf(train)
                        assertTrue(position.isFinite() && position >= 0 && position <= direction.stationCodes.lastIndex)
                    }
                }
                // 原始密文的 scale 分别为 .23/.13/1 与 .64/.10/.38，旧半格坐标已被全站证据推翻。
                val expected = if (line.id == "4") listOf(3.23, 0.13, 0.0) else listOf(6.64, 4.1, 1.38)
                result.directions.first().trains.forEachIndexed { index, train ->
                    assertEquals(expected[index], result.directions.first().positionOf(train), 1e-9)
                }
            }
        }
    }

    @Test fun `旧隐藏运营标记也不会阻止已具站码线路发起实时查询`() {
        for (line in network().lines.filter { it.id in MetroOpenedLineEvidence.apiCodes }) {
            val historical = line.copy(opened = false, stations = line.stations.map { it.copy(opened = false) })
            val request = MetroData(lines = listOf(historical), selected = historical.id).beginRealtimeRequest(1L)
            assertNotNull("opened 只控制样式，不能误称此次数据修正移除了请求过滤", request)
            assertEquals(line.apiCode, request!!.second.lineCode)
        }
    }

    @Test fun `真实在线历史站表进入展示层后与内置开通状态一致且原数据未被修改`() {
        val raw = MetroFixtures.file("metro-round2/allstation.json").readText()
        val online = MetroRemote().parse(raw)
        assertTrue(online.online)
        val normalized = normalizeMetroOpenings(online.lines)
        for (line in normalized) {
            val original = online.lines.single { it.id == line.id }
            val bundled = network().lines.single { it.id == line.id }
            assertEquals(bundled.stations.associate { it.code to it.opened }, line.stations.associate { it.code to it.opened })
            if (line.id in MetroOpenedLineEvidence.apiCodes) {
                assertFalse(original.opened)
                assertTrue(original.stations.all { !it.opened })
                assertTrue(line.opened)
                assertTrue(line.stations.all { it.opened })
                assertEquals(original.stations, line.stations.map { it.copy(opened = false) })
            } else {
                assertSame(original, line)
            }
        }
        assertEquals(normalized, normalizeMetroOpenings(normalized))
    }

    @Test fun `开通归一不把同线缺号或未来站全开也不修改别线`() {
        val confirmed = MetroStation(1, "已核实", opened = false, code = "04010000")
        val missing = MetroStation(2, "未核实缺号", opened = false, code = "04070000")
        val future = MetroStation(3, "未核实新段", opened = false, code = "04100000")
        val otherLineCode = MetroStation(4, "另一线站码", opened = false, code = "06030000")
        val line = MetroLine("4", "测试", opened = false, stations = listOf(confirmed, missing, future, otherLineCode), apiCode = "04000000")
        val result = normalizeMetroOpenings(listOf(line)).single()
        assertTrue(result.opened)
        assertEquals(listOf(true, false, false, false), result.stations.map { it.opened })
        assertEquals(line.copy(opened = true, stations = listOf(confirmed.copy(opened = true), missing, future, otherLineCode)), result)
        val unknown = line.copy(id = "5", apiCode = "05000000")
        assertSame(unknown, normalizeMetroOpenings(listOf(unknown)).single())
        val onlyFuture = line.copy(stations = listOf(future))
        assertSame(onlyFuture, normalizeMetroOpenings(listOf(onlyFuture)).single())
        assertFalse(normalizeMetroOpenings(listOf(onlyFuture)).single().opened)
    }
}
