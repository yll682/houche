package com.yll682.houche.core

import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 48 份新增真实密文离线走生产解密、解析和定位；构造边界仅在其他测试中使用。 */
class MetroRealtimeRound2Test {
    private val remote = MetroRealtimeRemote()
    private val directory get() = fixture("rt-02000000-095729-mq.json").parentFile

    @Test fun `48份真实响应双向288车完整保留且均能在自身站序定位`() {
        val tableCodes = BusNetwork.json.parseToJsonElement(fixture("line2-stations.json").readText())
            .list("stations").map { it.s("stationCode") }
        assertEquals(32, tableCodes.size)
        assertEquals("02010000", tableCodes.first())
        assertEquals("02320000", tableCodes.last())
        val files = directory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("rt-02000000-\\d{6}(?:-mq)?\\.json")) }
            .sortedBy { it.name }
        assertEquals(48, files.size)
        val flags = mutableMapOf<Int?, Int>()
        var trainCount = 0
        files.forEach { file ->
            val raw = file.readText()
            val body = plaintext(raw)
            val entries = body.list("data")
            val result = remote.parse(raw, "02000000")
            assertTrue("${file.name}: ${result.note}", result.online)
            assertEquals(file.name, 2, result.directions.size)
            assertEquals(if (file.name.endsWith("-mq.json")) "02090000" else "02100000", result.stationCode)
            assertEquals(entries.first().s("stationCode"), result.stationCode)
            assertEquals(result.directions.first().stationCodes.reversed(), result.directions.last().stationCodes)
            result.directions.forEachIndexed { directionIndex, direction ->
                val entry = entries[directionIndex]
                assertEquals(32, direction.stationCodes.size)
                val expectedOrder = when (direction.destination) {
                    "天竺山" -> tableCodes.reversed()
                    "五缘湾" -> tableCodes
                    else -> error("${file.name}: 未审计方向 ${direction.destination}")
                }
                assertEquals("${file.name}: ${direction.destination} 官方站序", expectedOrder, direction.stationCodes)
                val expectedReferenceIndex = when (result.stationCode to direction.destination) {
                    "02100000" to "天竺山" -> 22
                    "02100000" to "五缘湾" -> 9
                    "02090000" to "天竺山" -> 23
                    "02090000" to "五缘湾" -> 8
                    else -> error("${file.name}: 未审计参考站或方向")
                }
                val referenceIndex = direction.stationCodes.indexOf(result.stationCode)
                assertEquals("${file.name}: reference=${result.stationCode}", expectedReferenceIndex, referenceIndex)
                assertEquals(3, direction.trains.size)
                assertEquals(entry.list("transTimes").size, direction.trains.size)
                direction.trains.forEachIndexed { trainIndex, train ->
                    val source = entry.list("transTimes")[trainIndex]
                    assertEquals(source.i("arriveTime"), train.arriveSeconds)
                    assertEquals(source.s("arriveNextStationCode"), train.nextStationCode)
                    assertEquals(source.s("arriveNextStationName"), train.nextStationName)
                    assertEquals(source.d("arriveNextStationScale")!!, train.scale, 0.0)
                    assertEquals(source.i("arrivingFlag"), train.arrivingFlag)
                    val nextIndex = direction.stationCodes.indexOf(train.nextStationCode)
                    assertTrue("${file.name}: next=${train.nextStationCode}", nextIndex >= 0)
                    // audit-summary 的全部 288 条记录均未超过查询参考站；不把 abs 当作方向证据。
                    assertTrue("${file.name}: nextIndex=$nextIndex referenceIndex=$referenceIndex", nextIndex <= referenceIndex)
                    assertEquals(referenceIndex - nextIndex, result.remainingStops(direction, train))
                    val expected = when (train.arrivingFlag) {
                        1 -> (nextIndex - 0.5).coerceAtLeast(0.0)
                        2 -> nextIndex.toDouble()
                        else -> error("真实新样本出现未审计 flag=${train.arrivingFlag}")
                    }
                    val actual = direction.positionOf(train)
                    assertEquals("${file.name}: $directionIndex/$trainIndex", expected, actual, 0.0)
                    assertTrue(actual.isFinite() && actual >= 0.0 && actual <= direction.stationCodes.lastIndex)
                    flags[train.arrivingFlag] = flags.getOrDefault(train.arrivingFlag, 0) + 1
                    trainCount++
                }
            }
        }
        assertEquals(288, trainCount)
        assertEquals(mapOf(1 to 272, 2 to 16), flags)
    }

    @Test fun `095729真实行政中心到马青路车仅在马青路参考响应出现而非解析丢车`() {
        val admin = parse("rt-02000000-095729.json")
        val maqing = parse("rt-02000000-095729-mq.json")
        assertEquals("02100000", admin.stationCode)
        assertEquals("02090000", maqing.stationCode)
        val adminDirection = admin.directions.single { it.destination == "天竺山" }
        val mqDirection = maqing.directions.single { it.destination == "天竺山" }
        assertEquals(adminDirection.stationCodes, mqDirection.stationCodes)
        assertEquals(listOf("海沧湾公园", "建业路", "育秀东路"), adminDirection.trains.map { it.nextStationName })
        assertFalse(adminDirection.trains.any { it.nextStationCode == "02090000" })
        val train = mqDirection.trains.single { it.nextStationCode == "02090000" }
        assertEquals("马青路", train.nextStationName)
        assertEquals(0.30, train.scale, 0.0)
        assertEquals(1, train.arrivingFlag)
        assertEquals(82, train.arriveSeconds)
        assertEquals(22, mqDirection.stationCodes.indexOf("02100000"))
        assertEquals(23, mqDirection.stationCodes.indexOf(train.nextStationCode))
        assertEquals(22.5, mqDirection.positionOf(train), 0.0)
        // 相反方向同名下一站的另一班车在翁角路—马青路，不能用它填补本方向的区间。
        val otherDirection = admin.directions.single { it.destination == "五缘湾" }
        val otherTrain = otherDirection.trains.single { it.nextStationCode == "02090000" }
        assertEquals(0.94, otherTrain.scale, 0.0)
        assertEquals(137, otherTrain.arriveSeconds)
        assertEquals(7.5, otherDirection.positionOf(otherTrain), 0.0)
        listOf(admin, maqing).forEach { result ->
            assertEquals(listOf(3, 3), result.directions.map { it.trains.size })
            assertTrue(result.directions.all { direction -> direction.trains.all { direction.positionOf(it) >= 0.0 } })
        }
    }

    @Test fun `同一真实列车坐标不依赖参考站且方向必须分别取站序`() {
        val result = parse("rt-02000000-095729-mq.json")
        val direction = result.directions.single { it.destination == "天竺山" }
        val train = direction.trains.single { it.nextStationCode == "02090000" }
        listOf(direction.stationCodes.first(), direction.stationCodes.last(), "02100000", "不在站序", "").forEach { reference ->
            val changedReference = result.copy(stationCode = reference)
            assertEquals(22.5, direction.positionOf(train), 0.0)
            val stops = changedReference.remainingStops(direction, train)
            if (reference !in direction.stationCodes) assertEquals(-1, stops)
            else assertTrue(stops >= 0)
        }
        val reverse = result.directions.single { it.destination == "五缘湾" }
        assertEquals(7.5, reverse.positionOf(train), 0.0)
    }

    private fun fixture(name: String): File = MetroFixtures.file("metro-round2/$name")
    private fun parse(name: String): MetroRealtime = remote.parse(fixture(name).readText(), "02000000")
    private fun plaintext(raw: String): JsonElement {
        val outer = BusNetwork.json.parseToJsonElement(raw)
        return BusNetwork.json.parseToJsonElement(requireNotNull(MetroGateway.decrypt(outer.s("responseData"))))
    }
}
