package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

/** 离线纯函数回归：基准为真实一、二号线响应，边界变体不冒充新采集的响应。 */
class MetroTrainPositionTest {
    private val realtime = MetroRealtimeRemote().parse(MetroFixtures.realtime.s("rawResponse"))
    private val line2Sample = BusNetwork.json.parseToJsonElement(MetroFixtures.file("metro-realtime-line2-flag2-sample.json").readText())
    private val line2Realtime = MetroRealtimeRemote().parse(line2Sample.s("rawResponse"), "02000000", "02170000")
    private val stationTable = BusNetwork.json.parseToJsonElement(MetroFixtures.file("metro-from-api.json").readText())

    @Test fun `真实实时站码与同线路站表一致且换乘站不能借用另一线站码`() {
        listOf("1" to MetroFixtures.realtime, "2" to line2Sample).forEach { (lineId, sample) ->
            val line = stationTable.list("lines").single { it.s("id") == lineId }
            val codes = line.list("stations").associate { it.s("name") to it.s("code") }
            val raw = BusNetwork.json.parseToJsonElement(sample.s("plaintext"))
            raw.list("data").forEach { direction ->
                assertEquals(codes.size, direction.list("wayStations").size)
                direction.list("wayStations").forEach { station ->
                    assertEquals(station.s("name"), codes[station.s("name")], station.s("stationCode"))
                }
            }
        }
        val codes = stationTable.list("lines").single { it.s("id") == "1" }.list("stations").associate { it.s("name") to it.s("code") }
        val line2 = stationTable.list("lines").single { it.s("id") == "2" }
        val transfer = line2.list("stations").single { it.s("name") == "吕厝" }
        assertEquals("01080000", codes["吕厝"])
        assertEquals("02180000", transfer.s("code"))
    }

    @Test fun `真实二号线密文逐字匹配明文且flag2必须落在本站`() {
        val outer = BusNetwork.json.parseToJsonElement(line2Sample.s("rawResponse"))
        assertEquals(line2Sample.s("plaintext"), MetroGateway.decrypt(outer.s("responseData")))
        assertTrue(line2Realtime.note, line2Realtime.online)
        assertEquals("02170000", line2Realtime.stationCode)
        assertEquals("育秀东路", line2Realtime.stationName)
        assertEquals(listOf("天竺山", "五缘湾"), line2Realtime.directions.map { it.destination })
        assertEquals(line2Realtime.directions.first().stationCodes.reversed(), line2Realtime.directions.last().stationCodes)
        val positions = listOf(listOf(15.0, 11.5, 8.5), listOf(16.0, 12.5, 10.5))
        line2Realtime.directions.forEachIndexed { index, direction ->
            assertEquals(listOf(2, 1, 1), direction.trains.map { it.arrivingFlag })
            val first = direction.trains.first()
            assertEquals("02170000", first.nextStationCode)
            assertEquals("育秀东路", first.nextStationName)
            assertEquals(if (index == 0) 0.62 else 0.64, first.scale, 0.0)
            assertEquals(if (index == 0) 44 else 42, first.arriveSeconds)
            assertEquals(0, line2Realtime.remainingStops(direction, first))
            assertTrue(first.arriving)
            direction.trains.forEachIndexed { trainIndex, train ->
                assertEquals(positions[index][trainIndex], direction.positionOf(train), 0.0)
            }
        }
    }

    @Test fun `行政中心和马青路场景用真实站表验证双向区间但不冒充历史实时响应`() {
        val stations = stationTable.list("lines").single { it.s("id") == "2" }.list("stations")
        assertEquals(32, stations.size)
        assertEquals("马青路", stations[8].s("name"))
        assertEquals("02090000", stations[8].s("code"))
        assertEquals("海沧行政中心", stations[9].s("name"))
        assertEquals("02100000", stations[9].s("code"))
        val forward = realtime.directions.first().copy(
            origin = stations.first().s("name"), destination = stations.last().s("name"),
            stationCodes = stations.map { it.s("code") }
        )
        val reverse = forward.copy(origin = forward.destination, destination = forward.origin, stationCodes = forward.stationCodes.reversed())
        val train = forward.trains.first().copy(nextStationCode = "02100000", nextStationName = "海沧行政中心", arrivingFlag = 1)
        assertEquals(8.5, forward.positionOf(train), 0.0)
        assertEquals(22.5, reverse.positionOf(train.copy(nextStationCode = "02090000", nextStationName = "马青路")), 0.0)
    }

    @Test fun `真实响应的双向下一站均落在该方向的前一段`() {
        val forward = realtime.directions.first()
        val reverse = realtime.directions.last()
        assertEquals("岩内", forward.destination)
        assertEquals("镇海路", reverse.destination)
        assertEquals(forward.stationCodes.reversed(), reverse.stationCodes)

        val train = forward.trains.first()
        assertEquals("01110000", train.nextStationCode)
        assertEquals("火炬园", train.nextStationName)
        assertEquals(0.31, train.scale, 0.0)
        assertEquals(1, train.arrivingFlag)
        // 塘边(9)—火炬园(10)，不能把火炬园解释成刚离开的站。
        assertEquals(9.5, forward.positionOf(train), 0.0)
        // 同一个下一站，反向的上一站是殿前(12)，火炬园在13。
        assertEquals(12.5, reverse.positionOf(train), 0.0)

        val reverseTrain = reverse.trains.first()
        assertEquals("01140000", reverseTrain.nextStationCode)
        assertEquals("集美学村", reverseTrain.nextStationName)
        assertEquals(0.62, reverseTrain.scale, 0.0)
        // 园博苑(9)—集美学村(10)。
        assertEquals(9.5, reverse.positionOf(reverseTrain), 0.0)
    }

    @Test fun `比例为零接近一或越界都不改变官方段中点和站点位置`() {
        // 此处仅对比例构造边界变体；flag=2 另由真实二号线响应覆盖。
        val scales = listOf(0.0, 0.000001, 0.999999, 1.0, -0.5, 1.5, Double.NaN, Double.POSITIVE_INFINITY)
        realtime.directions.forEach { direction ->
            val train = direction.trains.first()
            scales.forEach { scale ->
                assertEquals(9.5, direction.positionOf(train.copy(scale = scale, arrivingFlag = 1)), 0.0)
                assertEquals(10.0, direction.positionOf(train.copy(scale = scale, arrivingFlag = 2)), 0.0)
            }
        }
    }

    @Test fun `双向首站和末站均有明确边界且不会越过线路端点`() {
        realtime.directions.forEach { direction ->
            assertEquals(24, direction.stationCodes.size)
            val train = direction.trains.first()
            listOf(0.0, 0.999999, 1.0).forEach { scale ->
                val atFirst = train.copy(nextStationCode = direction.stationCodes.first(), scale = scale)
                val atLast = train.copy(nextStationCode = direction.stationCodes.last(), scale = scale)
                assertEquals(0.0, direction.positionOf(atFirst.copy(arrivingFlag = 1)), 0.0)
                assertEquals(0.0, direction.positionOf(atFirst.copy(arrivingFlag = 2)), 0.0)
                assertEquals(22.5, direction.positionOf(atLast.copy(arrivingFlag = 1)), 0.0)
                assertEquals(23.0, direction.positionOf(atLast.copy(arrivingFlag = 2)), 0.0)
            }
        }
    }

    @Test fun `参考站在两端或未知不会改变列车的站序坐标`() {
        realtime.directions.forEach { direction ->
            val train = direction.trains.first()
            val atOrigin = realtime.copy(stationCode = direction.stationCodes.first())
            val atDestination = realtime.copy(stationCode = direction.stationCodes.last())
            assertEquals(10, atOrigin.remainingStops(direction, train))
            assertEquals(13, atDestination.remainingStops(direction, train))
            assertEquals(-1, realtime.copy(stationCode = "不在该线").remainingStops(direction, train))
            assertEquals(9.5, direction.positionOf(train), 0.0)
            assertEquals(10.0, direction.positionOf(train.copy(arrivingFlag = 2)), 0.0)
        }
    }

    @Test fun `缺失或其他线路的下一站不能伪造坐标`() {
        val direction = realtime.directions.first()
        val train = direction.trains.first()
        val withGap = direction.copy(stationCodes = listOf("", "01110000"))
        assertEquals(-1.0, withGap.positionOf(train.copy(nextStationCode = "")), 0.0)
        assertEquals(-1.0, direction.positionOf(train.copy(nextStationCode = " ")), 0.0)
        // 同名换乘站的另一条线路编码也不在该方向站序中。
        assertEquals(-1.0, direction.positionOf(train.copy(nextStationCode = "02180000", nextStationName = "吕厝")), 0.0)
        assertEquals(-1.0, direction.copy(stationCodes = emptyList()).positionOf(train), 0.0)
    }

    @Test fun `未知或空状态按已知下一站保守显示前段而不宣称到站`() {
        val scales = listOf(0.0, 0.3, 0.999999, 1.0, -0.5, 1.5, Double.NaN, Double.POSITIVE_INFINITY)
        realtime.directions.forEach { direction ->
            val train = direction.trains.first()
            listOf(null, -1, 0, 3, Int.MAX_VALUE).forEach { flag ->
                scales.forEach { scale ->
                    val unknown = train.copy(arrivingFlag = flag, scale = scale)
                    assertEquals(9.5, direction.positionOf(unknown), 0.0)
                    assertFalse(unknown.arriving)
                    assertFalse(unknown.approaching)
                    assertEquals(0.0, direction.positionOf(unknown.copy(nextStationCode = direction.stationCodes.first())), 0.0)
                    assertEquals(22.5, direction.positionOf(unknown.copy(nextStationCode = direction.stationCodes.last())), 0.0)
                    assertEquals(-1.0, direction.positionOf(unknown.copy(nextStationCode = "其他线路")), 0.0)
                }
            }
        }
    }
}
