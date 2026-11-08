package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

class MetroPositionPresentationTest {
    private val codes = listOf("A", "B", "C", "D", "E", "F", "G", "H")

    @Test fun `全方向第1到第七班不受本站三行或输入排列限制`() {
        val source = MetroObservationSource("H", serverEpochMillis = 1_000L)
        val positions = codes.drop(1).mapIndexed { index, next -> train(next, 900 - index * 100, source) }
        val direction = direction(positions.take(3), positions)
        val presentations = direction.positionPresentations()
        assertEquals(listOf("H", "G", "F", "E", "D", "C", "B"), presentations.map { it.train.nextStationCode })
        assertEquals((1..7).toList(), presentations.map { it.ordinal })
        presentations.forEach { assertEquals(it.ordinal, direction.arrivalOrderOf(it.train)) }
        assertEquals(3, direction.trains.size)
        assertNull(direction.arrivalRowOrderOf(presentations.first().train))
        assertEquals(1, direction.arrivalRowOrderOf(presentations.last().train))
        val changed = direction.copy(trains = emptyList(), source = MetroObservationSource("B"), positionTrains = positions.reversed())
        assertEquals(presentations.map { it.train to it.ordinal }, changed.positionPresentations().map { it.train to it.ordinal })
        assertTrue(changed.positionPresentations().all { it.arrivalRowOrder == null })
    }

    @Test fun `反向站序也按向终点递增坐标编号`() {
        val positions = listOf(train("B", 1), train("G", 999), train("D", 50))
        val direction = direction(emptyList(), positions).copy(stationCodes = codes.reversed())
        assertEquals(listOf("B", "D", "G"), direction.positionPresentations().map { it.train.nextStationCode })
        assertEquals(listOf(1, 2, 3), direction.positionPresentations().map { it.ordinal })
    }

    @Test fun `同位跨参考站不直接比较ETA而同参考站按采样调整后的到达时刻排序`() {
        val fromE = train("B", 500, MetroObservationSource("E", serverEpochMillis = 1_000L))
        val fromF = train("B", 1, MetroObservationSource("F", serverEpochMillis = 1_000L))
        val cross = direction(emptyList(), listOf(fromF, fromE))
        assertEquals(listOf(fromE, fromF), cross.positionPresentations().map { it.train })
        val earlier = train("B", 10, MetroObservationSource("E", serverEpochMillis = 1_000L))
        val later = train("B", 8, MetroObservationSource("E", serverEpochMillis = 5_000L))
        val same = direction(emptyList(), listOf(later, earlier))
        assertEquals(listOf(earlier, later), same.positionPresentations().map { it.train })
        val elapsedEarlier = earlier.copy(source = MetroObservationSource("E", receivedAtElapsedMillis = 1_000L))
        val elapsedLater = later.copy(source = MetroObservationSource("E", receivedAtElapsedMillis = 5_000L))
        assertEquals(listOf(elapsedEarlier, elapsedLater), direction(emptyList(), listOf(elapsedLater, elapsedEarlier))
            .positionPresentations().map { it.train })
    }

    @Test fun `同源同坐标多车保留全部并给稳定错层元数据`() {
        val source = MetroObservationSource("E", serverEpochMillis = 1_000L)
        val first = train("B", 500, source)
        val second = train("B", 650, source)
        val direction = direction(listOf(first, second), listOf(second, first))
        val presentation = direction.positionPresentations()
        assertEquals(listOf(first, second), presentation.map { it.train })
        assertEquals(listOf(1, 2), presentation.map { it.ordinal })
        assertEquals(listOf(0, 1), presentation.map { it.stackIndex })
        assertEquals(listOf(2, 2), presentation.map { it.stackSize })
        assertEquals(1, presentation.map { it.clusterId }.distinct().size)
        assertTrue(presentation.all { it.overlapKind == MetroPositionOverlapKind.SAME_REFERENCE_OBSERVATIONS })
        assertTrue(presentation.all { it.identityConfidence == MetroTrainIdentityConfidence.SINGLE_OBSERVATION })
        assertEquals(presentation, direction.copy(positionTrains = listOf(first, second)).positionPresentations())
    }

    @Test fun `单组跨源候选无充分证据时不去重只标记身份未决重叠`() {
        val merged = mergeMetroRealtime(listOf(
            snapshot("E", listOf(train("B", 500))), snapshot("F", listOf(train("B", 600)))
        ), "E").directions.single()
        val presentation = merged.positionPresentations()
        assertEquals(2, merged.positions.size)
        assertEquals(listOf(0, 1), presentation.map { it.stackIndex })
        assertTrue(presentation.all { it.stackSize == 2 && it.overlapKind == MetroPositionOverlapKind.UNRESOLVED_CROSS_REFERENCE })
        assertTrue(presentation.all { it.identityConfidence == MetroTrainIdentityConfidence.SINGLE_OBSERVATION })
        assertEquals(1, merged.trains.size)
    }

    @Test fun `歧义同next四条不因另两组近似身份见证而合并`() {
        val left = snapshot("E", listOf(train("B", 500), train("B", 650), train("C", 900), train("D", 1200)))
        val right = snapshot("F", listOf(train("B", 600), train("B", 750), train("C", 1000), train("D", 1300)))
        val forward = mergeMetroRealtime(listOf(left, right), "E").directions.single()
        val reordered = mergeMetroRealtime(listOf(right, left), "F").directions.single()
        val b = forward.positionPresentations().filter { it.train.nextStationCode == "B" }
        assertEquals(6, forward.positions.size)
        assertEquals(4, b.size)
        assertEquals((0..3).toList(), b.map { it.stackIndex })
        assertTrue(b.all { it.stackSize == 4 && it.overlapKind == MetroPositionOverlapKind.UNRESOLVED_CROSS_REFERENCE })
        assertEquals(2, forward.positionPresentations().count {
            it.identityConfidence == MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH
        })
        assertEquals(forward.positionPresentations().map(::globalValue), reordered.positionPresentations().map(::globalValue))
    }

    @Test fun `邻近半区间和站点保留两车但标记宽度阈值可使其同属潜在碰撞群`() {
        val approaching = train("B", 30)
        val atStation = train("B", 20).copy(arrivingFlag = 2)
        val direction = direction(emptyList(), listOf(approaching, atStation))
        val presentation = direction.positionPresentations()
        assertEquals(2, direction.positions.size)
        assertEquals(listOf(1.0, 0.5), presentation.map { it.stationPosition })
        assertTrue(presentation.all { it.stackSize == 2 && it.clusterSize == 2 })
        assertEquals(1, presentation.map { it.clusterId }.distinct().size)
        val narrow = direction.positionPresentations(collisionDistanceInStations = 0.5)
        assertTrue(narrow.all { it.stackSize == 1 && it.overlapKind == MetroPositionOverlapKind.NONE })
        assertEquals(2, narrow.map { it.clusterId }.distinct().size)
    }

    @Test fun `半格连通碰撞群按稳定班序分lane并复用相隔一站的lane`() {
        val source = MetroObservationSource("E")
        val zero = train("A", 50, source).copy(arrivingFlag = 2)
        val half = train("B", 40, source)
        val one = train("B", 30, source).copy(arrivingFlag = 2)
        val positions = listOf(half, zero, one)
        val direction = direction(emptyList(), positions)
        val presentation = direction.positionPresentations()
        assertEquals(listOf(1.0, 0.5, 0.0), presentation.map { it.stationPosition })
        assertEquals(listOf(0, 1, 0), presentation.map { it.stackIndex })
        assertTrue(presentation.all { it.stackSize == 2 && it.clusterSize == 3 })
        assertEquals(1, presentation.map { it.clusterId }.distinct().size)
        assertEquals(presentation, direction.copy(positionTrains = positions.reversed()).positionPresentations())
        presentation.forEachIndexed { index, item ->
            presentation.drop(index + 1).filter { it.stackIndex == item.stackIndex }.forEach { other ->
                assertTrue(kotlin.math.abs(item.stationPosition - other.stationPosition) >= 1.0)
            }
        }
    }

    @Test fun `相同站点坐标的相反方向分别聚群且群键隔离`() {
        val forward = direction(emptyList(), listOf(train("B", 10), train("B", 20))).positionPresentations()
        val reverse = direction(emptyList(), listOf(train("G", 10), train("G", 20)))
            .copy(stationCodes = codes.reversed()).positionPresentations()
        assertEquals(forward.first().stationPosition, reverse.first().stationPosition, 0.0)
        assertNotEquals(forward.first().clusterId, reverse.first().clusterId)
        assertEquals(listOf(0, 1), forward.map { it.stackIndex })
        assertEquals(listOf(0, 1), reverse.map { it.stackIndex })
    }

    @Test fun `24批48真fixture全方向3或4组均编号且切参考站不变也不凭空造第5班`() {
        val directory = MetroFixtures.file("metro-round2/rt-02000000-095729.json").parentFile
        val remote = MetroRealtimeRemote()
        val files = directory.listFiles().orEmpty().filter { it.name.matches(Regex("rt-02000000-\\d{6}\\.json")) }
        assertEquals(24, files.size)
        val sizes = mutableMapOf<Int, Int>()
        val adjacentGaps = mutableMapOf<Double, Int>()
        var approximate = 0
        var total = 0
        files.forEach { file ->
            val admin = remote.parse(file.readText(), "02000000")
            val mq = remote.parse(directory.resolve(file.name.removeSuffix(".json") + "-mq.json").readText(), "02000000")
            val first = mergeMetroRealtime(listOf(admin, mq), admin.stationCode)
            val changed = mergeMetroRealtime(listOf(mq, admin), mq.stationCode)
            first.directions.forEach { direction ->
                val presentation = direction.positionPresentations()
                val other = changed.directions.single { it.directionKey == direction.directionKey }.positionPresentations()
                sizes[presentation.size] = sizes.getOrDefault(presentation.size, 0) + 1
                total += presentation.size
                approximate += presentation.count { it.identityConfidence == MetroTrainIdentityConfidence.APPROXIMATE_CROSS_REFERENCE_MATCH }
                assertEquals((1..presentation.size).toList(), presentation.map { it.ordinal })
                assertEquals(presentation.map(::globalValue), other.map(::globalValue))
                assertTrue(presentation.all { it.stackSize == 1 && it.overlapKind == MetroPositionOverlapKind.NONE })
                presentation.zipWithNext { left, right -> left.stationPosition - right.stationPosition }.forEach { gap ->
                    adjacentGaps[gap] = adjacentGaps.getOrDefault(gap, 0) + 1
                }
                assertEquals(3, direction.trains.size)
                presentation.forEach { assertEquals(it.ordinal, direction.arrivalOrderOf(it.train)) }
            }
        }
        assertEquals(mapOf(3 to 32, 4 to 16), sizes)
        assertEquals(160, total)
        assertEquals(128, approximate)
        assertEquals(mapOf(1.5 to 8, 2.0 to 36, 2.5 to 4, 3.0 to 60, 3.5 to 4), adjacentGaps)
        assertEquals(112, adjacentGaps.values.sum())
        assertEquals(1.5, adjacentGaps.keys.minOrNull()!!, 0.0)
        assertEquals(0, adjacentGaps.filterKeys { it < 1.0 }.values.sum())
        // 此批真实二号线数据没有不到一格的碰撞，不能宣称复现了一号线镇海路截图。
    }

    @Test fun `真实三行模板构造首站夹零边界时不同ETA仍全部错层不去重`() {
        val raw = MetroFixtures.file("metro-round2/rt-02000000-095729.json").readText()
        val original = MetroRealtimeRemote().parse(raw, "02000000").directions.first()
        // Synthetic 边界：只借真实行的 ETA/来源模板，将 next 改为首站；不声称真实快照发生过。
        val synthetic = original.trains.map { it.copy(nextStationCode = original.stationCodes.first(), nextStationName = original.origin) }
        val direction = original.copy(trains = synthetic, positionTrains = synthetic)
        val presentation = direction.positionPresentations()
        assertEquals(3, synthetic.map { it.arriveSeconds }.distinct().size)
        assertEquals(3, direction.positions.size)
        assertEquals(listOf(0.0, 0.0, 0.0), presentation.map { it.stationPosition })
        assertEquals(listOf(0, 1, 2), presentation.map { it.stackIndex })
        assertTrue(presentation.all { it.stackSize == 3 && it.clusterSize == 3 })
        assertTrue(presentation.all { it.overlapKind == MetroPositionOverlapKind.SAME_REFERENCE_OBSERVATIONS })
        assertEquals(original.trains.map { it.arriveSeconds }, direction.trains.map { it.arriveSeconds })
    }

    @Test fun `未知位置不聚群而完全重复值只在可唯一回查时返回班序`() {
        val known = train("B", 30)
        val unknown = train("UNKNOWN", 10)
        val direction = direction(emptyList(), listOf(unknown, known))
        assertEquals(listOf(known, unknown), direction.positionPresentations().map { it.train })
        assertNull(direction.positionPresentations().last().clusterId)
        assertEquals(2, direction.arrivalOrderOf(unknown))
        val duplicate = known.copy()
        val ambiguous = direction(emptyList(), listOf(known, duplicate))
        assertEquals(listOf(1, 2), ambiguous.positionPresentations().map { it.ordinal })
        assertEquals(1, ambiguous.arrivalOrderOf(known))
        assertEquals(2, ambiguous.arrivalOrderOf(duplicate))
        assertNull(ambiguous.arrivalOrderOf(known.copy()))
    }

    private fun globalValue(p: MetroPositionPresentation) = listOf(
        p.train, p.stationPosition, p.ordinal, p.clusterId, p.stackIndex, p.stackSize, p.clusterSize, p.identityConfidence, p.overlapKind
    )

    private fun train(next: String, seconds: Int, source: MetroObservationSource? = null) =
        MetroTrain(seconds, next, next, 0.3, 1, 0, "", source = source)

    private fun direction(rows: List<MetroTrain>, positions: List<MetroTrain>) =
        MetroDirection("A", "H", "06:00", "23:00", 0, rows, codes, positionTrains = positions)

    private fun snapshot(reference: String, trains: List<MetroTrain>): MetroRealtime {
        val source = MetroObservationSource(reference, serverEpochMillis = 1_000L)
        return MetroRealtime(true, "test", "L", reference, reference,
            listOf(direction(trains, trains).copy(source = source)), source = source)
    }
}
