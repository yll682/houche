package com.yll682.houche.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 真实站表定义预期；合成资料仅验证缺失、歧义和错误关联的拒绝分支。 */
class MetroTransfersTest {
    private data class ExpectedTransfer(val name: String, val members: List<Pair<String, String>>)

    private val expected = listOf(
        ExpectedTransfer("湖滨东路", listOf("1" to "01050000", "3" to "03020000")),
        ExpectedTransfer("吕厝", listOf("1" to "01080000", "2" to "02180000")),
        ExpectedTransfer("火炬园", listOf("1" to "01110000", "3" to "03070000")),
        ExpectedTransfer("官任", listOf("1" to "01180000", "6" to "06180000")),
        ExpectedTransfer("厦门北站", listOf("1" to "01230000", "4" to "04030000")),
        ExpectedTransfer("鼎美", listOf("2" to "02040000", "6" to "06100000")),
        ExpectedTransfer("马銮中心", listOf("2" to "02050000", "6" to "06110000")),
        ExpectedTransfer("体育中心", listOf("2" to "02160000", "3" to "03030000")),
        ExpectedTransfer("五缘湾", listOf("2" to "02320000", "3" to "03120000")),
        ExpectedTransfer("洪坑", listOf("3" to "03150000", "4" to "04080000"))
    )

    private fun asset(name: String): File = sequenceOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        .first { it.isFile }

    private fun official(): List<MetroLine> = MetroRemote().parse(
        MetroFixtures.file("metro-round2/allstation.json").readText()
    ).also { assertTrue(it.note, it.online) }.lines

    private fun bundled(): MetroNetwork = runBlocking {
        Metro({ asset("metro-cities.json").inputStream() }, { asset("metro.json").inputStream() }).network()
    }

    @Test fun `真实原始密文与真实内置资产给出完整相同的二十个定向换乘目标`() {
        val online = official()
        val offline = bundled().lines
        for (lines in listOf(online, offline)) {
            val byId = lines.associateBy { it.id }
            val calls = lines.flatMap { line -> line.stations.map { (line.id to it.code) to it } }.toMap()
            val expectedIdentities = expected.flatMap { it.members }.toSet()
            assertEquals(20, expectedIdentities.size)
            assertEquals(expectedIdentities, calls.filterValues { it.transferTargets.isNotEmpty() }.keys)
            for (group in expected) {
                for (source in group.members) {
                    val station = calls.getValue(source)
                    assertEquals(group.name, station.name)
                    val targetIdentity = group.members.single { it != source }
                    val target = station.transferTargets.single()
                    assertEquals(targetIdentity, target.lineId to target.stationCode)
                    assertEquals(group.name, calls.getValue(targetIdentity).name)
                    assertEquals(byId.getValue(target.lineId).color, target.color)
                    assertEquals(byId.getValue(target.lineId).name.removePrefix("地铁"), target.lineName)
                    assertTrue(target.lineName in station.transfer)
                    assertEquals(
                        if (group.name == "鼎美") MetroTransferEvidence.FLAG_NAME_AND_COORDINATES
                        else MetroTransferEvidence.OFFICIAL_STATION_UNIQUE_CODE,
                        target.evidence
                    )
                }
            }
        }
        fun targets(lines: List<MetroLine>) = lines.flatMap { line ->
            line.stations.map { (line.id to it.code) to it.transferTargets }
        }.toMap()
        assertEquals(targets(online), targets(offline))
    }

    @Test fun `资产一百零二站的换乘字段逐码来自真实官方响应且坐标保持原值`() {
        val outer = BusNetwork.json.parseToJsonElement(MetroFixtures.file("metro-round2/allstation.json").readText())
        val inner = BusNetwork.json.parseToJsonElement(checkNotNull(MetroGateway.decrypt(outer.s("responseData"))))
        val raw = inner.list("data").filter { it.s("vehicle") == "METRO" }.associateBy { it.s("stationCode") }
        val stations = bundled().lines.flatMap { it.stations }
        assertEquals(102, stations.size)
        assertEquals(raw.keys, stations.map { it.code }.toSet())
        for (station in stations) {
            val source = raw.getValue(station.code)
            assertEquals(source.s("stationUniqueCode"), station.stationUniqueCode)
            assertEquals(source.s("changeFlag"), station.changeFlag.toString())
            assertEquals(source.s("longitude"), station.lng)
            assertEquals(source.s("latitude"), station.lat)
            assertEquals("BD09", station.coordinateSystem)
        }
        assertEquals(20, stations.count { it.changeFlag == true })
        assertEquals(9, stations.groupBy { it.stationUniqueCode }.count { it.value.size > 1 })
        assertTrue(official().flatMap { it.stations }.all { it.coordinateSystem == "BD09" })
    }

    private fun station(
        code: String,
        name: String = "换乘站",
        unique: String = "01010000",
        flag: Boolean? = true,
        lng: String = "118.134372",
        lat: String = "24.495066"
    ) = MetroStation(
        1, name, code = code, lng = lng, lat = lat,
        stationUniqueCode = unique, changeFlag = flag, coordinateSystem = "BD09"
    )

    private fun lines(a: MetroStation, b: MetroStation): List<MetroLine> = listOf(
        MetroLine("1", "地铁一号线", color = "#DA7028", stations = listOf(a)),
        MetroLine("2", "地铁二号线", color = "#52A441", stations = listOf(b))
    )

    private fun assertNoTargets(input: List<MetroLine>) {
        assertTrue(metroTransfers(input).flatMap { it.stations }.all { it.transferTargets.isEmpty() })
    }

    @Test fun `官方共同共站码允许原始坐标不同并仍使用目标线路自己的站码`() {
        val result = metroTransfers(lines(station("01010000"), station("02010000", lng = "118.135")))
        assertEquals("02010000", result.first().stations.single().transferTargets.single().stationCode)
        assertEquals(MetroTransferEvidence.OFFICIAL_STATION_UNIQUE_CODE,
            result.first().stations.single().transferTargets.single().evidence)
    }

    @Test fun `不同名近邻同名前缀和仅精确同名都不能证明可换乘`() {
        val source = station("01010000", name = "湖滨东路")
        assertNoTargets(lines(source, station("02010000", name = "厦门火车站", unique = "02010000")))
        assertNoTargets(lines(source, station("02010000", name = "湖滨东路北", unique = "02010000")))
        assertNoTargets(lines(source, station("02010000", name = "湖滨东路", unique = "02010000", lng = "118.145")))
    }

    @Test fun `缺少官方字段或有效坐标时保持保守不建立导航关系`() {
        val valid = station("01010000")
        val invalid = listOf(
            station("02010000", flag = null), station("02010000", flag = false),
            station("02010000", unique = ""), station("02010000", lng = ""),
            station("02010000", lng = "NaN"), station("02010000", lat = "Infinity"),
            station("02010000", lat = "91"), station("02010000", lng = "0", lat = "0"),
            station("")
        )
        for (candidate in invalid) assertNoTargets(lines(valid, candidate))
    }

    @Test fun `只有双方官方换乘标记加精确同名和相同BD09坐标才补全鼎美式关系`() {
        val source = station("01010000")
        val other = station("02010000", unique = "02010000", lng = "118.1343720")
        val result = metroTransfers(lines(source, other))
        assertEquals(MetroTransferEvidence.FLAG_NAME_AND_COORDINATES,
            result.first().stations.single().transferTargets.single().evidence)
        assertNoTargets(lines(source, other.copy(coordinateSystem = "unconfirmed")))
        assertNoTargets(lines(source, other.copy(coordinateSystem = "WGS84")))
    }

    @Test fun `相同占位共站码或非法站码不能提供跨线身份`() {
        val invalidCodes = listOf("", "0", "null", "00000000", "0101", "１２３４５６７８", "abcdefgh")
        for (code in invalidCodes) {
            assertNoTargets(lines(station("01010000", unique = code), station("02010000", unique = code)))
            assertNoTargets(lines(station("01010000"), station(code)))
        }
    }

    @Test fun `同线路重复身份或多个可能目标均不靠列表顺序猜选`() {
        val source = station("01010000")
        val target = station("02010000")
        val input = lines(source, target)
        assertNoTargets(listOf(input.first(), input.last().copy(stations = listOf(target, target.copy(code = "02020000")))))
        assertNoTargets(listOf(input.first(), input.last().copy(stations = listOf(target, target))))
        assertNoTargets(input + input.last())
    }

    @Test fun `重复补齐幂等且目标顺序不随源线路排列变化`() {
        val input = lines(station("01010000"), station("02010000")) + listOf(
            MetroLine("10", "地铁十号线", stations = listOf(station("10010000"))),
            MetroLine("3", "地铁三号线", stations = listOf(station("03010000")))
        )
        val first = metroTransfers(input)
        assertEquals(first, metroTransfers(first))
        val expectedOrder = first.first().stations.single().transferTargets
        assertEquals(listOf("2", "3", "10"), expectedOrder.map { it.lineId })
        val reversed = metroTransfers(input.reversed()).single { it.id == "1" }.stations.single().transferTargets
        assertEquals(expectedOrder, reversed)
    }

    @Test fun `旧换乘文字保留但不反向生成跳转且派生目标不会持久化`() {
        val legacy = MetroStation(1, "旧站", transfer = listOf("二号线", "BRT"), code = "01010000")
        val previousTarget = MetroTransferTarget("2", "02010000", "二号线", "#52A441",
            MetroTransferEvidence.OFFICIAL_STATION_UNIQUE_CODE)
        val clean = metroTransfers(lines(legacy.copy(transferTargets = listOf(previousTarget)), station("02010000")))
            .first().stations.single()
        assertEquals(legacy.transfer, clean.transfer)
        assertTrue(clean.transferTargets.isEmpty())
        val json = BusNetwork.json.encodeToString(legacy.copy(transferTargets = listOf(previousTarget)))
        assertFalse(json.contains("transferTargets"))
        assertEquals("unconfirmed", BusNetwork.json.decodeFromString<MetroStation>(json).coordinateSystem)
    }
}
