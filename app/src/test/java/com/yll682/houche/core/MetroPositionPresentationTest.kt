package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** 身份数、原始 ETA 行与可见车标分别验收；隐藏冲突位置不意味着认作同一辆车。 */
class MetroPositionPresentationTest {
    private val codes = ('A'..'L').map(Char::toString)
    private val source = MetroObservationSource("H", serverEpochMillis = 1_000L)

    @Test fun `本站三行锚定123且已过本站和其他全线身份使用其余唯一编号`() {
        val rows = listOf(train("G", 120), train("E", 420), train("B", 720))
        val passed = train("K", 20, MetroObservationSource("L"))
        val extra = train("I", 300, MetroObservationSource("L"))
        val direction = direction(rows, listOf(extra, rows[2], passed, rows[0], rows[1]))
        val presentation = direction.positionPresentations()
        assertEquals(listOf("K", "I", "G", "E", "B"), presentation.map { it.train.nextStationCode })
        assertEquals(listOf(4, 5, 1, 2, 3), presentation.map { it.ordinal })
        assertEquals((1..5).toList(), presentation.map { it.ordinal }.sorted())
        rows.forEachIndexed { index, row ->
            assertEquals(index + 1, direction.arrivalOrderOf(row))
            assertEquals(index + 1, direction.arrivalRowOrderOf(row))
        }
        assertNull(direction.arrivalRowOrderOf(passed))
        val projected = direction.stationOrdinals("H")
        assertEquals(listOf(1, 2, 3), (1..3).map(projected::ordinalOfRow))
        assertEquals(2, projected.passedCount)
        assertEquals(presentation.map { it.ordinal }, projected.trains.map { it.ordinal })
        assertEquals(5, projected.maxOrdinal)
        assertEquals(presentation, direction.copy(positionTrains = direction.allIdentifiedTrains.reversed()).positionPresentations())
    }

    @Test fun `匹配身份跨next区间边界仍关联本站原始ETA行`() {
        val row = train("G", 100)
        val representative = train("F", 220, MetroObservationSource("I")).copy(matchedObservations = listOf(
            MetroTrainObservation(source, 100, row.scale, row.arrivingFlag, "G"),
            MetroTrainObservation(MetroObservationSource("I"), 220, row.scale, row.arrivingFlag, "F")
        ))
        val direction = direction(listOf(row), listOf(representative))
        assertEquals(1, direction.arrivalRowOrderOf(representative))
        assertEquals(1, direction.positionPresentations().single().ordinal)
        assertEquals(MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH,
            direction.positionPresentations().single().identityConfidence)
    }

    @Test fun `同一站台多条同源观测只画一个并完整保留身份与ETA`() {
        val rows = listOf(100, 400, 700).map { train("F", it).copy(scale = 1.0, arrivingFlag = 2) }
        val direction = direction(rows, rows.reversed())
        assertEquals(3, direction.allIdentifiedTrains.size)
        assertEquals(3, direction.trains.size)
        assertEquals(1, direction.positions.size)
        assertSame(rows.first(), direction.positions.single())
        val visible = direction.positionPresentations().single()
        assertEquals(0, visible.stackIndex)
        assertEquals(1, visible.stackSize)
        assertEquals(3, visible.clusterSize)
        assertEquals(MetroPositionOverlapKind.SAME_REFERENCE_OBSERVATIONS, visible.overlapKind)
        assertEquals(2, direction.positionSuppressions().size)
        assertTrue(direction.positionSuppressions().all {
            it.reason == MetroPositionSuppressionReason.UNRESOLVED_CLOSE_POSITION
        })
        assertEquals(listOf(1, 2, 3), (1..3).map(direction.stationOrdinals("H")::ordinalOfRow))
    }

    @Test fun `首站三个待发计划不合并身份但只有最近待发占一个站位`() {
        val rows = listOf(164, 344, 644).map {
            train("A", it, source.copy(referenceStationCode = "A")).copy(scale = 0.0, scaleKnown = false)
        }
        val direction = direction(rows, rows.reversed())
        assertEquals(3, direction.allIdentifiedTrains.size)
        assertEquals(listOf(164, 344, 644), direction.trains.map { it.arriveSeconds })
        assertEquals(listOf(0.0), direction.positions.map { direction.positionOf(it) })
        assertEquals(164, direction.positions.single().arriveSeconds)
        assertEquals(MetroTrainPositionState.TERMINAL_PENDING, direction.positionStateOf(rows.first()))
        rows.drop(1).forEach { assertEquals(MetroTrainPositionState.SCHEDULED, direction.positionStateOf(it)) }
        assertTrue(direction.positionSuppressions().all { it.reason == MetroPositionSuppressionReason.SCHEDULED_DEPARTURE })
        assertEquals(listOf(1, 2, 3), rows.map(direction::arrivalOrderOf))
    }

    @Test fun `始发端已在站台的车优先于后续待发计划`() {
        val atPlatform = train("A", 13, source.copy(referenceStationCode = "A"))
            .copy(scaleKnown = false, arrivingFlag = 2)
        val pending = atPlatform.copy(arriveSeconds = 313, arrivingFlag = 1)
        val later = pending.copy(arriveSeconds = 613)
        val direction = direction(listOf(atPlatform, pending, later), listOf(later, pending, atPlatform))
        assertEquals(listOf(atPlatform), direction.positions)
        assertEquals(MetroTrainPositionState.AT_STATION, direction.positionStateOf(atPlatform))
        assertEquals(2, direction.positionSuppressions().count { it.reason == MetroPositionSuppressionReason.SCHEDULED_DEPARTURE })
    }

    @Test fun `未来首站待发占位不能挤掉已经发车零点三七站的在途列车`() {
        val actual = train("B", 90).copy(scale = 0.37)
        val pending = train("A", 252, source.copy(referenceStationCode = "A")).copy(scaleKnown = false)
        val direction = direction(listOf(actual, pending), listOf(pending, actual))
        assertEquals(2, direction.allIdentifiedTrains.size)
        assertTrue(direction.allIdentifiedTrains.all { it.matchedObservations.isEmpty() })
        assertEquals(listOf(actual), direction.positions)
        assertEquals(0.37, direction.positionPresentations().single().stationPosition, 1e-9)
        assertEquals(1, direction.positionPresentations().single().ordinal)
        val suppressed = direction.positionSuppressions().single()
        assertSame(pending, suppressed.train)
        assertSame(actual, suppressed.retainedTrain)
        assertEquals(MetroPositionSuppressionReason.SCHEDULED_DEPARTURE, suppressed.reason)
        assertEquals(MetroTrainPositionState.RUNNING, direction.positionStateOf(actual))
        assertEquals(MetroTrainPositionState.SCHEDULED, direction.positionStateOf(pending))
    }

    @Test fun `反向首站同样只保留一个待发站位`() {
        val rows = listOf(252, 552, 852).map {
            train("L", it, source.copy(referenceStationCode = "L")).copy(scaleKnown = false)
        }
        val reverse = direction(rows, rows).copy(stationCodes = codes.reversed())
        assertEquals(3, reverse.allIdentifiedTrains.size)
        assertEquals(listOf(rows.first()), reverse.positions)
        assertEquals(0.0, reverse.positionPresentations().single().stationPosition, 0.0)
        assertEquals(2, reverse.positionSuppressions().size)
    }

    @Test fun `小于一站的任意同向位置不得并排画且阈值不能降到零点八绕过约束`() {
        val positions = listOf(train("B", 100).copy(scale = 0.0),
            train("B", 200).copy(scale = 0.79), train("C", 300).copy(scale = 0.6),
            train("D", 400).copy(scale = 0.8), train("G", 500).copy(scale = 0.4))
        val direction = direction(emptyList(), positions)
        assertEquals(5, direction.allIdentifiedTrains.size)
        assertTrue(direction.positions.size < 5)
        assertPhysicalSpacing(direction)
        assertEquals(direction.positionPresentations(), direction.positionPresentations(0.8))
        assertPhysicalSpacing(direction, direction.positionPresentations(1.5).map { it.stationPosition }, 1.5)
        assertTrue(direction.positionSuppressions().all { it.reason == MetroPositionSuppressionReason.UNRESOLVED_CLOSE_POSITION })
    }

    @Test fun `恰好一站是可见边界且不横向挪动车标坐标`() {
        val positions = listOf(train("B", 100), train("C", 200), train("D", 300))
        val direction = direction(positions, positions)
        assertEquals(3, direction.positions.size)
        assertEquals(listOf(2.3, 1.3, 0.3), direction.positionPresentations().map { it.stationPosition })
        assertPhysicalSpacing(direction)
        assertTrue(direction.positionSuppressions().isEmpty())
    }

    @Test fun `跨参考站同位只隐藏未决位置而不伪造合并身份`() {
        val left = train("F", 100, source.copy(referenceStationCode = "G"))
        val right = train("F", 400, source.copy(referenceStationCode = "I"))
        val direction = direction(listOf(left), listOf(right, left))
        assertEquals(2, direction.allIdentifiedTrains.size)
        assertTrue(direction.allIdentifiedTrains.all { it.matchedObservations.isEmpty() })
        assertEquals(listOf(left), direction.positions)
        assertEquals(MetroPositionOverlapKind.UNRESOLVED_CROSS_REFERENCE,
            direction.positionPresentations().single().overlapKind)
        assertEquals(MetroPositionSuppressionReason.UNRESOLVED_CLOSE_POSITION,
            direction.positionSuppressions().single().reason)
    }

    @Test fun `位置缺失不影响本站三行编号也不制造站位`() {
        val rows = listOf(train("UNKNOWN", 120), train("E", 420), train("B", 720))
        val direction = direction(rows, rows)
        assertEquals(3, direction.allIdentifiedTrains.size)
        assertEquals(2, direction.positions.size)
        assertEquals(listOf(2, 3), direction.positionPresentations().map { it.ordinal })
        assertEquals(MetroPositionSuppressionReason.UNKNOWN_POSITION, direction.positionSuppressions().single().reason)
        assertEquals(listOf(1, 2, 3), (1..3).map(direction.stationOrdinals("H")::ordinalOfRow))
        assertEquals(1, direction.arrivalOrderOf(rows.first()))
    }

    @Test fun `部分本站行未关联时不让补充身份冒用本站编号`() {
        val firstRow = train("G", 120)
        val missingRow = train("D", 420)
        val unrelated = train("K", 20, source.copy(referenceStationCode = "L"))
        val direction = direction(listOf(firstRow, missingRow), listOf(unrelated, firstRow))
        assertEquals(2, direction.allIdentifiedTrains.size)
        assertEquals(listOf(firstRow), direction.positions)
        assertEquals(1, direction.positionPresentations().single().ordinal)
        assertNull(direction.arrivalOrderOf(unrelated))
        assertEquals(MetroPositionSuppressionReason.UNRESOLVED_IDENTITY_ASSOCIATION,
            direction.positionSuppressions().single().reason)
        assertEquals(listOf(1, 2), (1..2).map(direction.stationOrdinals("H")::ordinalOfRow))
        assertEquals(listOf(firstRow, missingRow), direction.trains)
    }

    @Test fun `歧义关联不猜同车也不为未决位置发本站编号`() {
        val row = train("F", 100)
        val first = row.copy()
        val second = row.copy()
        val direction = direction(listOf(row), listOf(first, second))
        assertEquals(2, direction.allIdentifiedTrains.size)
        assertTrue(direction.positions.isEmpty())
        assertNull(direction.arrivalOrderOf(first))
        assertEquals(2, direction.positionSuppressions().size)
        assertTrue(direction.positionSuppressions().all {
            it.reason == MetroPositionSuppressionReason.UNRESOLVED_IDENTITY_ASSOCIATION
        })
        assertEquals(1, direction.stationOrdinals("H").ordinalOfRow(1))
    }

    @Test fun `只剩一个身份却关联ETA第三行时不得画编号3或补造身份`() {
        val rows = listOf(train("G", 100), train("E", 400), train("B", 700))
        val direction = direction(rows, listOf(rows.last()))
        assertEquals(1, direction.allIdentifiedTrains.size)
        assertTrue(direction.positions.isEmpty())
        assertNull(direction.arrivalOrderOf(rows.last()))
        assertEquals(MetroPositionSuppressionReason.UNRESOLVED_IDENTITY_ASSOCIATION,
            direction.positionSuppressions().single().reason)
        assertEquals(listOf(1, 2, 3), (1..3).map(direction.stationOrdinals("H")::ordinalOfRow))
    }

    @Test fun `真实吕厝两轮双向比例进度和ETA共同证明next是驶向端`() {
        val before = parseLine1("01080000", "173230")
        val after = parseLine1("01080000", "173330")
        before.directions.forEach { first ->
            val second = after.directions.single { it.stationCodes == first.stationCodes }
            val r = first.stationCodes.indexOf("01080000")
            val p0 = first.positionOf(first.trains.first())
            val p1 = second.positionOf(second.trains.first())
            assertTrue("应仍在驶向本站，不能提前画过本站", p0 < r && p1 < r)
            assertTrue("60秒后沿本方向前进", p1 > p0)
            assertTrue(abs(first.trains.first().arriveSeconds - second.trains.first().arriveSeconds - 60) <= 1)
        }
        val forward = before.directions.single { it.stationCodes.first() == "01010000" }
        val reverse = before.directions.single { it.stationCodes.first() == "01240000" }
        assertEquals(6.0, forward.positionOf(forward.trains.first()), 1e-9)
        assertEquals(14.86, reverse.positionOf(reverse.trains.first()), 1e-9)
        val nextForward = after.directions.single { it.stationCodes == forward.stationCodes }
        val nextReverse = after.directions.single { it.stationCodes == reverse.stationCodes }
        assertEquals(6.51, nextForward.positionOf(nextForward.trains.first()), 1e-9)
        assertEquals(15.37, nextReverse.positionOf(nextReverse.trains.first()), 1e-9)
    }

    @Test fun `全站两轮身份数与可见物理位置分开验收且本站ETA保持原样`() {
        listOf("173230", "173330").forEach { round ->
            val snapshots = (1..24).map { parseLine1("01%02d0000".format(it), round) }
            val merged = mergeMetroRealtime(snapshots, "01080000", "01000000")
            merged.directions.forEach { direction ->
                assertTrue("$round ${direction.destination}: identity=${direction.allIdentifiedTrains.size}",
                    direction.allIdentifiedTrains.size in 12..14)
                assertTrue("含多班待发时，可见数应少于身份数", direction.positions.size < direction.allIdentifiedTrains.size)
                assertPhysicalSpacing(direction)
                assertEquals(direction.positions, direction.positionPresentations().map { it.train })
                val originals = snapshots.single { it.stationCode == "01080000" }.directions
                    .single { it.stationCodes == direction.stationCodes }.trains
                assertEquals(originals, direction.trains)
                assertEquals(listOf(1, 2, 3), (1..3).map(direction.stationOrdinals("01080000")::ordinalOfRow))
                val p = direction.positionPresentations()
                assertEquals(p.size, p.map { it.ordinal }.distinct().size)
                assertTrue(p.all { it.ordinal in 1..direction.allIdentifiedTrains.size })
                p.filter { it.arrivalRowOrder != null }.forEach { assertEquals(it.arrivalRowOrder, it.ordinal) }
            }
        }
    }

    private fun assertPhysicalSpacing(direction: MetroDirection,
        values: List<Double> = direction.positions.map { direction.positionOf(it) }, minimum: Double = 1.0) {
        values.forEachIndexed { index, left -> values.drop(index + 1).forEach { right ->
            assertTrue("同向车距 ${abs(left - right)} < $minimum: $values", abs(left - right) + 1e-9 >= minimum)
        } }
    }

    private fun parseLine1(station: String, round: String): MetroRealtime = MetroRealtimeRemote().parse(
        MetroFixtures.file("line1-multi/rt-01000000-$station-$round.json").readText(), "01000000", station
    )

    private fun train(next: String, seconds: Int, observation: MetroObservationSource = source) =
        MetroTrain(seconds, next, next, 0.3, 1, 0, "", source = observation)

    private fun direction(rows: List<MetroTrain>, positions: List<MetroTrain>) =
        MetroDirection("A", "L", "06:00", "23:00", 0, rows, codes, source, positionTrains = positions)
}
