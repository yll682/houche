package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

/** 用运行秒和原始 ETA 验证身份，身份总数与位置层可见数分别检验。 */
class MetroRealtimeMergeTest {
    private val remote = MetroRealtimeRemote(elapsedRealtimeMillis = { 0L }, wallClockMillis = { 0L })
    private val codes = listOf("A", "B", "C", "D", "E", "F", "G")
    private val profile = MetroRunningTimeProfile(codes, codes.indices.map { it * 100 })

    @Test fun `两轮全线真实响应从144条关联为26和27个身份且本站ETA不变`() {
        listOf("173230" to listOf(13, 13), "173330" to listOf(13, 14)).forEach { (tag, counts) ->
            val snapshots = realSnapshots(tag)
            assertEquals(24, snapshots.size)
            assertEquals(144, snapshots.sumOf { it.directions.sumOf { d -> d.trains.size } })
            val result = mergeMetroRealtime(snapshots, "01080000", "01000000")
            assertEquals(counts, result.directions.map { it.allIdentifiedTrains.size })
            assertEquals(setOf("01010000", "01240000"), result.directions.map { it.stationCodes.first() }.toSet())
            result.directions.forEach { d ->
                val original = snapshots.single { it.stationCode == "01080000" }.directions.single { it.directionKey == d.directionKey }
                assertEquals(original.trains, d.trains)
                assertEquals(72, d.allIdentifiedTrains.sumOf { if (it.matchedObservations.isEmpty()) 1 else it.matchedObservations.size })
                assertEquals(setOf(1, 2, 3), d.allIdentifiedTrains.mapNotNull { d.arrivalRowOrderOf(it) }.toSet())
                d.allIdentifiedTrains.forEach { train ->
                    val raw = train.matchedObservations
                    assertEquals(raw.size, raw.map { it.source.referenceStationCode }.distinct().size)
                    assertTrue(raw.all { it.nextStationCode in d.stationCodes })
                }
                // 三个首端未来班次仍是三个身份，不因同一站点坐标合为一车或全塞在站台。
                assertEquals(3, d.allIdentifiedTrains.count { it.nextStationCode == d.stationCodes.first() })
                assertTrue(d.positions.size < d.allIdentifiedTrains.size)
            }
            val reordered = mergeMetroRealtime(snapshots.reversed().map { it.copy(directions = it.directions.reversed()) }, "01080000", "01000000")
            assertEquals(identities(result), identities(reordered))
            assertEquals(identities(result), identities(mergeMetroRealtime(snapshots, "01150000", "01000000")))
        }
    }

    @Test fun `既有24批二号线真实双参考样本仍保留本站行与每一条原始证据`() {
        val directory = MetroFixtures.file("metro-round2/rt-02000000-095729.json").parentFile
        val files = directory.listFiles().orEmpty().filter { it.name.matches(Regex("rt-02000000-\\d{6}\\.json")) }.sortedBy { it.name }
        assertEquals(24, files.size)
        var identityCount = 0
        files.forEach { file ->
            val selected = remote.parse(file.readText(), "02000000")
            val other = remote.parse(directory.resolve(file.name.removeSuffix(".json") + "-mq.json").readText(), "02000000")
            val result = mergeMetroRealtime(listOf(selected, other), selected.stationCode)
            result.directions.forEach { d ->
                val original = selected.directions.single { it.directionKey == d.directionKey }
                assertEquals(original.trains, d.trains)
                assertEquals(6, d.allIdentifiedTrains.sumOf { it.matchedObservations.size.coerceAtLeast(1) })
                assertEquals(setOf(1, 2, 3), d.allIdentifiedTrains.mapNotNull { d.arrivalRowOrderOf(it) }.toSet())
                identityCount += d.allIdentifiedTrains.size
            }
        }
        assertTrue(identityCount < 288)
    }

    @Test fun `单列跨参考站双证据足够无需另两车作见证且可跨next`() {
        val result = merge(listOf(
            snapshot("E", listOf(train("C", 500, .9))),
            snapshot("F", listOf(train("D", 600, .1)))
        ))
        assertEquals(1, result.allIdentifiedTrains.size)
        assertEquals(setOf("C", "D"), result.allIdentifiedTrains.single().matchedObservations.map { it.nextStationCode }.toSet())
        assertEquals(1, result.arrivalRowOrderOf(result.allIdentifiedTrains.single()))
        assertEquals(listOf(500), result.trains.map { it.arriveSeconds })
    }

    @Test fun `位置吻合但到站时刻不符以及时刻吻合但位置不符都不认同车`() {
        val left = snapshot("E", listOf(train("B", 500, .2)))
        assertEquals(2, merge(listOf(left, snapshot("F", listOf(train("B", 801, .2))))).allIdentifiedTrains.size)
        assertEquals(2, merge(listOf(left, snapshot("F", listOf(train("E", 600, .2))))).allIdentifiedTrains.size)
    }

    @Test fun `相邻车运行秒相差超过280不会误并且同源三班保持独立`() {
        val result = merge(listOf(
            snapshot("E", listOf(train("B", 500, .2), train("E", 800, .2), train("G", 1100, .8))),
            snapshot("F", listOf(train("B", 600, .3), train("E", 900, .3), train("G", 1200, .9)))
        ))
        assertEquals(3, result.allIdentifiedTrains.size)
        assertTrue(result.allIdentifiedTrains.all { it.matchedObservations.size == 2 })
        assertEquals(3, result.trains.size)
    }

    @Test fun `缺失及零一边界比例只由anchor辅助且未来计划不按同坐标合并`() {
        listOf(train("A", 600, 0.0).copy(scaleKnown = false), train("A", 600, 0.0), train("A", 600, 1.0)).forEach { weak ->
            assertEquals(1, merge(listOf(snapshot("E", listOf(train("D", 500, .4))), snapshot("F", listOf(weak)))).allIdentifiedTrains.size)
        }
        val schedules = merge(listOf(
            snapshot("E", listOf(train("A", 500, 1.0), train("A", 680, 1.0), train("A", 980, 1.0))),
            snapshot("F", listOf(train("A", 620, 1.0), train("A", 800, 1.0), train("A", 1100, 1.0)))
        ))
        assertEquals(3, schedules.allIdentifiedTrains.size)
        assertTrue(schedules.allIdentifiedTrains.all { it.matchedObservations.size == 2 })
        assertEquals(setOf(500, 680, 980), schedules.allIdentifiedTrains.map { it.arriveSeconds }.toSet())
    }

    @Test fun `位置或anchor只有链式相邻时不会跨越成同一身份`() {
        val positionChain = listOf(
            snapshot("E", listOf(train("C", 400, .1))),
            snapshot("F", listOf(train("D", 500, .1))),
            snapshot("G", listOf(train("E", 600, .1)))
        )
        val anchorChain = listOf(
            snapshot("E", listOf(train("A", 400, 1.0))),
            snapshot("F", listOf(train("A", 650, 1.0))),
            snapshot("G", listOf(train("A", 900, 1.0)))
        )
        listOf(positionChain, anchorChain).forEach { samples ->
            val result = merge(samples)
            assertEquals(2, result.allIdentifiedTrains.size)
            assertEquals(3, result.allIdentifiedTrains.sumOf { it.matchedObservations.size.coerceAtLeast(1) })
            assertEquals(identities(mergeMetroRealtime(samples, "E")), identities(mergeMetroRealtime(samples.reversed(), "E")))
        }
    }

    @Test fun `参考站缺失时跨源相隔180秒的首站未来计划仍是两个身份`() {
        val result = merge(listOf(
            snapshot("E", listOf(train("A", 500, 1.0))),
            snapshot("F", listOf(train("A", 780, 1.0)))
        ))
        assertEquals(2, result.allIdentifiedTrains.size)
        assertTrue(result.allIdentifiedTrains.all { it.matchedObservations.isEmpty() })
    }

    @Test fun `七秒采样差同时校正位置和anchor而旧批或无共同时间域不配对`() {
        val left = snapshot("E", listOf(train("B", 500, .2)), 1_000L)
        val later = snapshot("F", listOf(train("B", 593, .27)), 8_000L)
        assertEquals(1, merge(listOf(left, later)).allIdentifiedTrains.size)
        val stale = snapshot("F", listOf(train("B", 591, .29)), 10_000L)
        assertEquals(2, merge(listOf(left, stale)).allIdentifiedTrains.size)
        assertEquals(2, merge(listOf(left, withoutSourceTime(later))).allIdentifiedTrains.size)
    }

    @Test fun `无效运行资料比例来源和未知next保留观测而不套用别站时间`() {
        val left = snapshot("E", listOf(train("B", 500, .2)))
        val right = snapshot("F", listOf(train("B", 600, .2)))
        listOf(
            right.copy(directions = right.directions.map { it.copy(runningTimeProfile = null) }),
            snapshot("F", listOf(train("UNKNOWN", 600, .2))),
            snapshot("F", listOf(train("B", 600, Double.NaN))),
            snapshot("F", listOf(train("B", 600, -.1))),
            snapshot("F", listOf(train("B", 600, .2).copy(source = MetroObservationSource("G", serverEpochMillis = 1_000L))))
        ).forEach { incompatible -> assertEquals(2, merge(listOf(left, incompatible)).allIdentifiedTrains.size) }
    }

    @Test fun `反向与内部站链不同的方向绝不合并`() {
        val first = snapshot("E", listOf(train("B", 500, .2)))
        listOf(codes.reversed(), listOf("A", "C", "B", "D", "E", "F", "G"), listOf("A", "", "C", "D", "E", "F", "G")).forEach { otherCodes ->
            val other = snapshot("F", listOf(train("B", 600, .2))).let { s -> s.copy(directions = s.directions.map { it.copy(stationCodes = otherCodes) }) }
            val result = mergeMetroRealtime(listOf(first, other), "E")
            assertEquals(2, result.directions.size)
            assertEquals(2, result.directions.sumOf { it.allIdentifiedTrains.size })
        }
    }

    @Test fun `没有本站响应时只补身份不制造本站ETA且新轮不续旧车`() {
        val other = snapshot("F", listOf(train("B", 600, .2)))
        val result = mergeMetroRealtime(listOf(MetroRealtime(false, "HTTP 500", "L", "E"), other), "E")
        assertTrue(result.online)
        assertFalse(result.directions.single().arrivalsAvailable)
        assertTrue(result.directions.single().trains.isEmpty())
        assertEquals(1, result.directions.single().allIdentifiedTrains.size)
        val empty = merge(listOf(snapshot("E", emptyList(), 20_000L)))
        assertTrue(empty.arrivalsAvailable)
        assertTrue(empty.allIdentifiedTrains.isEmpty())
        val newSample = merge(listOf(snapshot("E", listOf(train("B", 500, .2)), 1_000L), snapshot("E", listOf(train("D", 800, .2)), 2_000L)))
        assertEquals(listOf("D"), newSample.allIdentifiedTrains.map { it.nextStationCode })
        assertTrue(other.directions.single().copy(trains = emptyList()).allIdentifiedTrains.isEmpty())
    }

    @Test fun `未带时间的重复参考响应不能互相删掉`() {
        val sample = withoutSourceTime(snapshot("E", listOf(train("B", 500, .2))))
        assertEquals(2, merge(listOf(sample, sample.copy())).allIdentifiedTrains.size)
    }

    private fun realSnapshots(tag: String): List<MetroRealtime> {
        val directory = MetroFixtures.file("line1-multi/index.tsv").parentFile
        return directory.listFiles().orEmpty().filter { it.name.matches(Regex("rt-01000000-\\d{8}-$tag.json")) }.sortedBy { it.name }
            .map { remote.parse(it.readText(), "01000000") }
    }

    private fun train(next: String, seconds: Int, scale: Double) = MetroTrain(seconds, next, next, scale, 1, 0, "")
    private fun snapshot(reference: String, trains: List<MetroTrain>, at: Long = 1_000L): MetroRealtime {
        val source = MetroObservationSource(reference, serverEpochMillis = at)
        return MetroRealtime(true, "test", "L", reference, reference, listOf(
            MetroDirection("同名始发", "同名终点", "06:00", "23:00", 0, trains, codes, source = source, runningTimeProfile = profile)
        ), source = source)
    }
    private fun withoutSourceTime(snapshot: MetroRealtime): MetroRealtime {
        val source = MetroObservationSource(snapshot.stationCode)
        return snapshot.copy(source = source, directions = snapshot.directions.map { it.copy(source = source) })
    }
    private fun merge(samples: List<MetroRealtime>) = mergeMetroRealtime(samples, "E").directions.single()
    private fun identities(result: MetroRealtime) = result.directions.associate { d -> d.directionKey to d.allIdentifiedTrains }
}
