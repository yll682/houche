package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

class MetroRealtimeSelectionStateTest {
    private val codes = listOf("A", "B", "C", "D")
    private val sourceA = MetroObservationSource("A", receivedAtElapsedMillis = 1_000, receivedAtEpochMillis = 1_800_000_001_000)
    private val sourceB = MetroObservationSource("B", receivedAtElapsedMillis = 4_000, receivedAtEpochMillis = 1_800_000_004_000)

    @Test fun `切站等待保留完整成功批次与反向位置但不把旧站ETA投影到新站`() {
        val frame = frame("A", sourceA)
        val before = MetroRealtimeState(frame = frame).selectDirection(1)
        val switched = before.selectStation("B", "乙站")

        assertSame(frame, switched.frame)
        assertEquals(before.selectedDirectionKey, switched.selectedDirectionKey)
        assertEquals(codes.reversed(), switched.direction!!.stationCodes)
        assertEquals(before.direction!!.positions, switched.direction!!.positions)
        assertTrue(switched.direction!!.trains.isEmpty())
        assertEquals("B", switched.displayFrame!!.stationCode)
        assertEquals("乙站", switched.displayFrame!!.stationName)
        assertEquals(sourceA, switched.lastSuccessAt)
        assertEquals(MetroEtaState.Loading("B"), switched.etaState)
        assertEquals(5_000L, switched.positionsAt(6_000).ageMillis)
    }

    @Test fun `切站与响应方向重排后key和index仍指向原方向`() {
        val initial = MetroRealtimeState(frame = frame("A", sourceA)).selectDirection(1)
        val fromB = frame("B", sourceB).let { it.copy(directions = it.directions.reversed()) }
        val completed = initial.selectStation("B", "乙站").beginRequest().complete(fromB)

        assertEquals(0, completed.directionIndex)
        assertEquals(0, completed.selectedDirectionIndex)
        assertEquals(initial.selectedDirectionKey, completed.selectedDirectionKey)
        assertEquals(codes.reversed(), completed.direction!!.stationCodes)
        assertEquals(sourceB, (completed.etaState as MetroEtaState.Ready).lastSuccessAt)
        assertTrue((completed.etaState as MetroEtaState.Ready).trains.all { it.source!!.referenceStationCode == "B" })
        val other = completed.selectDirection(1)
        assertEquals(codes, other.selectedDirectionKey!!.stationCodes)
        assertEquals(other.direction!!.directionKey, other.selectedDirectionKey)
    }

    @Test fun `选中站不在缓存批次时完成为不可用且缓存采样不续命`() {
        val original = frame("A", sourceA)
        val cachedAtB = original.copy(stationCode = "B", stationName = "乙站", directions = original.directions.map {
            it.copy(trains = emptyList(), positionTrains = it.allIdentifiedTrains, arrivalsAvailable = false)
        })
        val completed = MetroRealtimeState(frame = original).selectStation("B", "乙站").complete(cachedAtB)

        assertTrue(completed.etaState is MetroEtaState.Unavailable)
        assertNull(completed.etaState.lastSuccessAt)
        assertEquals(original.directions.map { it.positions }, completed.displayFrame!!.directions.map { it.positions })
        assertEquals(sourceA, completed.lastSuccessAt)
        assertFalse(completed.positionsAt(20_999).isStale)
        assertTrue(completed.positionsAt(21_000).isStale)
        assertEquals(20_000L, completed.positionsAt(21_000).ageMillis)
    }

    @Test fun `真实成功空车和本站缺失分别是Empty和Unavailable`() {
        val empty = frame("B", sourceB).copy(directions = frame("B", sourceB).directions.map { it.copy(trains = emptyList()) })
        val completed = MetroRealtimeState().selectStation("B", "乙站").complete(empty)
        assertEquals(MetroEtaState.Empty("B", sourceB), completed.etaState)

        val missing = completed.complete(empty.copy(directions = empty.directions.map { it.copy(arrivalsAvailable = false) }))
        assertTrue(missing.etaState is MetroEtaState.Unavailable)
        assertFalse(missing.loading)
    }

    @Test fun `失败保留成功frame和采样但ETA不可用且陈旧年龄持续增长`() {
        val original = frame("A", sourceA)
        val failed = MetroRealtimeState(frame = original).beginRequest().complete(MetroRealtime(false, "超时"))
        assertSame(original, failed.frame)
        assertEquals(MetroEtaState.Unavailable("A", "超时", sourceA), failed.etaState)
        assertEquals(original.directions.map { it.positions }, failed.displayFrame!!.directions.map { it.positions })
        assertTrue(failed.displayFrame!!.directions.all { it.trains.isEmpty() })
        assertTrue(failed.positionsAt(2_000).isStale)
        assertEquals(1_000L, failed.positionsAt(2_000).ageMillis)
        assertTrue(failed.beginRequest().positionsAt(2_000).isStale)
        val failedAgain = failed.beginRequest().complete(MetroRealtime(false, "仍超时"))
        assertEquals(sourceA, failedAgain.lastSuccessAt)
        assertEquals(29_000L, failedAgain.positionsAt(30_000).ageMillis)
        assertTrue(failedAgain.positionsAt(30_000).isStale)
    }

    @Test fun `快速切站再返回在真实响应前始终loading且不污染原frame`() {
        val original = frame("A", sourceA)
        val state = MetroRealtimeState(frame = original).selectDirection(1)
            .selectStation("B", "乙站").beginRequest()
            .selectStation("C", "丙站").beginRequest()
            .selectStation("A", "甲站")
        assertSame(original, state.frame)
        assertEquals(MetroEtaState.Loading("A", sourceA), state.etaState)
        assertEquals(codes.reversed(), state.direction!!.stationCodes)
        assertEquals(original.directions.last().positions, state.direction!!.positions)
        assertEquals(sourceA, state.lastSuccessAt)
    }

    @Test fun `同名终点或不完整站码链不能代替方向key`() {
        val selected = frame("A", sourceA).copy(directions = frame("A", sourceA).directions.map { it.copy(destination = "同名终点") })
        val state = MetroRealtimeState(frame = selected).selectDirection(1)
        val reordered = selected.copy(directions = selected.directions.reversed())
        val completed = state.complete(reordered)
        assertEquals(codes.reversed(), completed.selectedDirectionKey!!.stationCodes)
        assertEquals(0, completed.selectedDirectionIndex)

        val incomplete = selected.copy(directions = listOf(selected.directions.first().copy(stationCodes = listOf("A", "", "D"))))
        assertNull(completed.complete(incomplete).selectedDirectionKey)
        assertEquals(0, completed.complete(incomplete).selectedDirectionIndex)
    }

    @Test fun `方向key消失才夹紧索引并同步新key`() {
        val state = MetroRealtimeState(frame = frame("A", sourceA)).selectDirection(1)
        val onlyForward = frame("A", sourceA).let { it.copy(directions = it.directions.take(1)) }
        val completed = state.complete(onlyForward)
        assertEquals(0, completed.directionIndex)
        assertEquals(MetroDirectionKey(codes), completed.selectedDirectionKey)
        assertEquals(completed.selectedDirectionKey, completed.direction!!.directionKey)
    }

    @Test fun `首次响应前保留旧索引偏好并在真实方向到达后建立key和夹紧索引`() {
        val pending = MetroRealtimeState(directionIndex = 1).beginRequest()
        assertEquals(1, pending.directionIndex)
        assertEquals(1, pending.selectedDirectionIndex)
        assertNull(pending.selectedDirectionKey)
        assertNull(pending.direction)
        val completed = pending.complete(frame("A", sourceA))
        assertEquals(1, completed.directionIndex)
        assertEquals(MetroDirectionKey(codes.reversed()), completed.selectedDirectionKey)
        assertEquals(completed.selectedDirectionKey, completed.direction!!.directionKey)

        val tooLarge = MetroRealtimeState(directionIndex = 99).beginRequest().complete(frame("A", sourceA))
        assertEquals(1, tooLarge.directionIndex)
        assertEquals(tooLarge.selectedDirectionKey, tooLarge.direction!!.directionKey)
        val negative = MetroRealtimeState(directionIndex = -1).beginRequest().complete(frame("A", sourceA))
        assertEquals(0, negative.directionIndex)
        assertEquals(negative.selectedDirectionKey, negative.direction!!.directionKey)
    }

    @Test fun `其他站或冲突来源的trains不能得到Ready或Empty`() {
        val polluted = frame("B", sourceB).let { snapshot -> snapshot.copy(directions = snapshot.directions.map { direction ->
            direction.copy(trains = direction.trains.map { it.copy(source = sourceA) })
        }) }
        val completed = MetroRealtimeState().selectStation("B", "乙站").complete(polluted)
        assertTrue(completed.etaState is MetroEtaState.Unavailable)
        assertTrue(completed.direction!!.trains.isEmpty())
        assertTrue(completed.direction!!.positions.isNotEmpty())
        val emptyWithWrongSource = polluted.copy(directions = polluted.directions.map { it.copy(trains = emptyList(), source = sourceA) })
        assertTrue(completed.complete(emptyWithWrongSource).etaState is MetroEtaState.Unavailable)
    }

    @Test fun `未知或异时域采样不会由墙钟或请求时间冒充新鲜位置`() {
        val noElapsed = sourceA.copy(receivedAtElapsedMillis = null)
        val state = MetroRealtimeState(frame = frame("A", noElapsed))
        assertNull(state.positionsAt(5_000).ageMillis)
        assertTrue(state.positionsAt(5_000).isStale)
        val future = MetroRealtimeState(frame = frame("A", sourceA))
        assertNull(future.positionsAt(999).ageMillis)
        assertTrue(future.positionsAt(999).isStale)
    }

    @Test fun `切站等待与失败投影都保留被隐藏的待发身份和原因`() {
        val pending = listOf(164, 344, 644).map {
            MetroTrain(it, "A", "甲站", 0.0, 1, 0, "", sourceA, scaleKnown = false)
        }
        val original = frame("A", sourceA).let { snapshot ->
            snapshot.copy(directions = listOf(snapshot.directions.first().copy(trains = pending, positionTrains = pending)))
        }
        val initial = MetroRealtimeState(frame = original)
        val states = listOf(initial.selectStation("B", "乙站"),
            initial.beginRequest().complete(MetroRealtime(false, "超时")))
        states.forEach { state ->
            val projected = state.displayFrame!!.directions.single()
            assertSame(original, state.frame)
            assertEquals(pending, projected.allIdentifiedTrains)
            assertEquals(3, projected.allIdentifiedTrains.size)
            assertEquals(listOf(pending.first()), projected.positions)
            assertEquals(2, projected.positionSuppressions().size)
            assertTrue(projected.positionSuppressions().all { it.reason == MetroPositionSuppressionReason.SCHEDULED_DEPARTURE })
            assertTrue(projected.trains.isEmpty())
        }
    }

    private fun frame(station: String, source: MetroObservationSource): MetroRealtime {
        fun direction(codes: List<String>) = MetroDirection(
            origin = codes.first(), destination = codes.last(), startTime = "06:00", endTime = "23:00", delayMinutes = 0,
            stationCodes = codes, source = source,
            trains = listOf(MetroTrain(90, codes[1], codes[1], 0.5, 1, 0, "", source = source))
        )
        return MetroRealtime(
            online = true, note = "实时", lineCode = "1", stationCode = station, stationName = "$station 站",
            source = source, sources = listOf(source), directions = listOf(direction(codes), direction(codes.reversed()))
        )
    }
}
