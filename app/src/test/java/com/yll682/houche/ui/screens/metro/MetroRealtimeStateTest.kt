package com.yll682.houche.ui.screens.metro

import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroFixtures
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroObservationSource
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRealtimeRemote
import com.yll682.houche.core.MetroStation
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.directionKey
import org.junit.Assert.*
import org.junit.Test

/** 只验证请求上下文和数据归并，不启动 Android、协程或网络。 */
class MetroRealtimeStateTest {
    private val firstLine = MetroLine(
        id = "1", name = "地铁一号线", apiCode = "01000000",
        stations = listOf(MetroStation(1, "镇海路", code = "01010000"), MetroStation(2, "中山公园", code = "01020000"))
    )
    private val secondLine = MetroLine(
        id = "2", name = "地铁二号线", apiCode = "02000000",
        stations = listOf(MetroStation(1, "马青路", code = "02090000"), MetroStation(2, "海沧行政中心", code = "02100000"))
    )
    private val initial = MetroData(lines = listOf(firstLine, secondLine), selected = "1")

    @Test fun `请求固定发起时的线路和默认参考站`() {
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        assertEquals("1", request.lineId)
        assertEquals("01000000", request.lineCode)
        assertEquals("01010000", request.stationCode)
        assertEquals(listOf("01010000", "01020000"), request.stationCodes)
        assertEquals(1L, pending.realtimeRequestId)
        assertTrue(pending.realtimeLoading)
        assertNull(MetroData().beginRealtimeRequest(2))
        assertNull(initial.copy(lines = listOf(firstLine.copy(apiCode = "", stations = emptyList()))).beginRealtimeRequest(2))
    }

    @Test fun `首次未查询和仅有其他站位置时都等本站而非报失败`() {
        assertEquals(MetroEtaState.Loading("01010000"), initial.etaState)
        val source = MetroObservationSource("01020000", receivedAtElapsedMillis = 1_000)
        val partial = success().copy(source = source, directions = success().directions.map {
            it.copy(source = source, arrivalsAvailable = false)
        })
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        val positions = pending.completeRealtimePositions(request.lineId, request.lineCode, partial)
        assertEquals(request.id, positions.realtimeRequestId)
        assertTrue(positions.realtimeLoading)
        assertFalse(positions.realtimeUnavailable)
        assertEquals(MetroEtaState.Loading("01010000"), positions.etaState)
    }

    @Test fun `前台本站成功立即Ready而迟到位置批次不改本站ETA和独立时钟`() {
        val arrivalsSource = MetroObservationSource("01010000", receivedAtElapsedMillis = 2_000, receivedAtEpochMillis = 1_800_000_002_000)
        val arrival = MetroTrain(50, "01010000", "镇海路", 0.6, 1, 0, "", source = arrivalsSource)
        val station = success().copy(source = arrivalsSource, directions = success().directions.map {
            it.copy(source = arrivalsSource, trains = listOf(arrival))
        })
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        val completed = pending.completeRealtimeStationRequest(request, station, "09:25")
        assertTrue(completed.etaState is MetroEtaState.Ready)
        assertFalse(completed.realtimeLoading)
        assertEquals(listOf(arrival), (completed.etaState as MetroEtaState.Ready).trains)
        assertEquals("09:25", completed.realtimeUpdated)

        val positionSource = MetroObservationSource("01020000", receivedAtElapsedMillis = 1_000, receivedAtEpochMillis = 1_800_000_001_000)
        val batch = success().copy(source = positionSource, sources = listOf(positionSource), directions = success().directions.map {
            it.copy(trains = emptyList(), source = positionSource, arrivalsAvailable = false)
        })
        val positions = completed.completeRealtimePositions(request.lineId, request.lineCode, batch)
        assertSame(batch, positions.realtimeLastSuccessfulFrame)
        assertEquals(arrivalsSource, positions.etaState.lastSuccessAt)
        assertEquals(positionSource, positions.realtimeState.lastSuccessAt)
        assertEquals(4_000L, positions.realtimeState.positionsAt(5_000).ageMillis)
        assertEquals(listOf(arrival), (positions.etaState as MetroEtaState.Ready).trains)
        assertEquals("09:25", positions.realtimeUpdated)
    }

    @Test fun `旧位置存在时前台本站成功只更新ETA且不会把旧位置时间续命`() {
        val positionsSource = MetroObservationSource("01010000", receivedAtElapsedMillis = 1_000, receivedAtEpochMillis = 1_800_000_001_000)
        val arrivalsSource = positionsSource.copy(receivedAtElapsedMillis = 61_000, receivedAtEpochMillis = 1_800_000_061_000)
        val oldPositions = success().copy(source = positionsSource)
        val before = initial.copy(realtime = oldPositions, realtimeStation = "01010000", realtimeUpdated = "09:24")
        val (pending, request) = before.beginRealtimeRequest(1)!!
        val station = success().copy(source = arrivalsSource, directions = success().directions.map { it.copy(source = arrivalsSource) })
        val completed = pending.completeRealtimeStationRequest(request, station, "09:25")
        assertSame(oldPositions, completed.realtimeLastSuccessfulFrame)
        assertEquals(arrivalsSource, completed.etaState.lastSuccessAt)
        assertEquals(60_000L, completed.realtimeState.positionsAt(61_000).ageMillis)
        assertTrue(completed.realtimeState.positionsAt(61_000).isStale)
    }

    @Test fun `本站补回旧位置批缺失方向后可选且再次部分位置批不会夹回旧方向`() {
        val positionSource = MetroObservationSource("01010000", receivedAtElapsedMillis = 1_000)
        val arrivalsSource = MetroObservationSource("01010000", receivedAtElapsedMillis = 4_000)
        val original = success().copy(source = positionSource, directions = success().directions.take(1))
        val before = initial.copy(realtime = original, realtimeStation = "01010000")
        val (pending, request) = before.beginRealtimeRequest(1)!!
        val current = success().copy(source = arrivalsSource, directions = success().directions.map { direction ->
            direction.copy(source = arrivalsSource, trains = listOf(MetroTrain(70, "01010000", "镇海路", 0.4, 1, 0, "", arrivalsSource)))
        })
        val completed = pending.completeRealtimeStationRequest(request, current, "09:25")
        assertSame(original, completed.realtimeLastSuccessfulFrame)
        assertEquals(2, completed.realtime!!.directions.size)
        val reverse = completed.selectRealtimeDirection(1)
        assertEquals(current.directions[1].directionKey, reverse.selectedDirectionKey)
        assertEquals(1, reverse.directionIndex)
        assertTrue(reverse.etaState is MetroEtaState.Ready)
        assertEquals(arrivalsSource, reverse.etaState.lastSuccessAt)
        assertEquals(positionSource, reverse.realtimeState.lastSuccessAt)

        val partialAgain = reverse.completeRealtimePositions(request.lineId, request.lineCode, original)
        assertEquals(reverse.selectedDirectionKey, partialAgain.selectedDirectionKey)
        assertEquals(1, partialAgain.directionIndex)
        assertEquals(reverse.direction!!.stationCodes, partialAgain.direction!!.stationCodes)
        assertEquals(reverse.etaState, partialAgain.etaState)
        assertSame(original, partialAgain.realtimeLastSuccessfulFrame)
        assertEquals(6_000L, partialAgain.realtimeState.positionsAt(7_000).ageMillis)
    }

    @Test fun `同站Loading重新选中不作废在途上下文且切站清除旧站错误`() {
        val (pending, request) = initial.selectRealtimeStation("01010000").beginRealtimeRequest(1)!!
        assertSame(pending, pending.selectRealtimeStation("01010000"))
        assertTrue(pending.hasRealtimeTarget("1", "01010000"))
        assertSame(pending, pending.selectRealtimeTarget("1", "01010000"))
        assertFalse(pending.hasRealtimeTarget("1", "不存在的站码"))
        assertEquals(request.id, pending.selectRealtimeStation("01010000").realtimeRequestId)
        val failed = pending.completeRealtimeStationRequest(request, MetroRealtime(false, "旧站超时"), "")
        val switched = failed.selectRealtimeStation("01020000")
        assertFalse(switched.realtimeUnavailable)
        assertEquals(MetroEtaState.Loading("01020000"), switched.etaState)
    }

    @Test fun `前台响应不能把用户已选站替换为其他参考站且换线后旧位置失效`() {
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        assertSame(pending, pending.completeRealtimeStationRequest(request, success("01020000", "中山公园"), "09:25"))
        val changed = pending.selectRealtimeTarget("2", "02090000")
        assertSame(changed, changed.completeRealtimePositions(request.lineId, request.lineCode, success()))
        assertSame(changed, changed.completeRealtimeStationRequest(request, success(), "09:25"))
        assertEquals(MetroEtaState.Loading("02090000"), changed.etaState)
    }

    @Test fun `请求失败保留旧位置批次并明确ETA不可用且不改成功时间和参考站`() {
        val before = initial.copy(
            realtime = success(), realtimeStation = "01010000", realtimeStationName = "镇海路", realtimeUpdated = "09:24"
        )
        val (pending, request) = before.beginRealtimeRequest(1)!!
        val failed = pending.completeRealtimeRequest(
            request, MetroRealtime(false, "地铁实时到站请求超时", stationCode = "01020000", stationName = "错误响应站"), "09:25"
        )
        assertNotNull(failed.realtime)
        assertEquals(before.realtime, failed.realtimeLastSuccessfulFrame)
        assertTrue(failed.etaState is MetroEtaState.Unavailable)
        assertFalse(failed.realtimeLoading)
        assertEquals("地铁实时到站请求超时", failed.realtimeNote)
        assertEquals("09:24", failed.realtimeUpdated)
        assertEquals("01010000", failed.realtimeStation)
        assertEquals("镇海路", failed.realtimeStationName)
    }

    @Test fun `首次请求失败不生成成功更新时间`() {
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        val failed = pending.completeRealtimeRequest(request, MetroRealtime(false, "解密失败"), "09:25")
        assertNull(failed.realtime)
        assertEquals("", failed.realtimeUpdated)
        assertEquals("解密失败", failed.realtimeNote)
    }

    @Test fun `成功空车仍保留方向和本次成功时间`() {
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        val completed = pending.completeRealtimeRequest(request, success(), "09:25")
        val realtime = completed.realtime!!
        assertTrue(realtime.online)
        assertEquals(2, realtime.directions.size)
        assertTrue(realtime.directions.all { it.trains.isEmpty() })
        assertFalse(completed.realtimeLoading)
        assertEquals("09:25", completed.realtimeUpdated)
    }

    @Test fun `同线路同站重叠请求以后发起的结果为准`() {
        val (firstPending, first) = initial.beginRealtimeRequest(1)!!
        val (secondPending, second) = firstPending.beginRealtimeRequest(2)!!
        val latest = secondPending.completeRealtimeRequest(second, success().copy(note = "最新"), "09:26")
        assertSame(latest, latest.completeRealtimeRequest(first, success().copy(note = "旧成功"), "09:25"))
        assertSame(latest, latest.completeRealtimeRequest(first, MetroRealtime(false, "旧失败"), "09:25"))
        assertEquals("最新", latest.realtimeNote)
        assertEquals("09:26", latest.realtimeUpdated)
    }

    @Test fun `最新请求失败后迟到的旧成功也不能恢复过期列车`() {
        val (firstPending, first) = initial.copy(realtimeUpdated = "09:24").beginRealtimeRequest(1)!!
        val (secondPending, second) = firstPending.beginRealtimeRequest(2)!!
        val failed = secondPending.completeRealtimeRequest(second, MetroRealtime(false, "最新请求失败"), "09:26")
        assertSame(failed, failed.completeRealtimeRequest(first, success(), "09:25"))
        assertNull(failed.realtime)
        assertEquals("09:24", failed.realtimeUpdated)
    }

    @Test fun `换线再换回后第一条线路的旧响应仍失效`() {
        val (firstPending, first) = initial.beginRealtimeRequest(1)!!
        val (secondPending, second) = firstPending.copy(selected = "2", realtimeRequestId = 0).beginRealtimeRequest(2)!!
        val (thirdPending, third) = secondPending.copy(selected = "1", realtimeRequestId = 0).beginRealtimeRequest(3)!!
        val latest = thirdPending.completeRealtimeRequest(third, success().copy(note = "返回一号线后的请求"), "09:27")
        assertSame(latest, latest.completeRealtimeRequest(first, success(), "09:25"))
        assertSame(latest, latest.completeRealtimeRequest(second, success("02090000", "马青路"), "09:26"))
        assertEquals("1", latest.selected)
        assertEquals("返回一号线后的请求", latest.realtimeNote)
    }

    @Test fun `换站后原站请求不能覆盖新站结果`() {
        val (firstPending, first) = initial.beginRealtimeRequest(1)!!
        val (secondPending, second) = firstPending.copy(
            realtimeStation = "01020000", realtimeStationName = "中山公园", realtimeRequestId = 0
        ).beginRealtimeRequest(2)!!
        val latest = secondPending.completeRealtimeRequest(second, success("01020000", "中山公园"), "09:26")
        assertSame(latest, latest.completeRealtimeRequest(first, success(), "09:25"))
        assertEquals("01020000", latest.realtimeStation)
        assertEquals("中山公园", latest.realtimeStationName)
    }

    @Test fun `换线或换站在新请求发出前也会作废旧请求`() {
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        val changedLine = pending.copy(selected = "2")
        assertSame(changedLine, changedLine.completeRealtimeRequest(request, success(), "09:25"))
        val changedStation = pending.copy(realtimeStation = "01020000", realtimeRequestId = 0)
        assertSame(changedStation, changedStation.completeRealtimeRequest(request, success(), "09:25"))
        // 即使其他调用方忘记清序号，固定参考站/线路码上下文也会挡住迟到响应。
        val changedStationWithSameId = pending.copy(realtimeStation = "01020000")
        assertSame(changedStationWithSameId, changedStationWithSameId.completeRealtimeRequest(request, success(), "09:25"))
        val changedCodeWithSameId = pending.copy(lines = listOf(firstLine.copy(apiCode = "09000000")))
        assertSame(changedCodeWithSameId, changedCodeWithSameId.completeRealtimeRequest(request, success(), "09:25"))
    }

    @Test fun `本站失败时保留其他站位置且不会把它们当成本站ETA或改参考站`() {
        val source = MetroObservationSource("01020000", receivedAtElapsedMillis = 1_000, receivedAtEpochMillis = 1_800_000_000_000)
        val observed = MetroTrain(120, "01010000", "镇海路", 0.3, 1, 0, "", source = source)
        val partialDirection = direction().copy(
            trains = emptyList(), positionTrains = listOf(observed), source = source, arrivalsAvailable = false
        )
        val partial = success().copy(directions = listOf(partialDirection), source = source, sources = listOf(source))
        val (pending, request) = initial.beginRealtimeRequest(1)!!
        val completed = pending.completeRealtimeRequest(request, partial, "09:25")
        assertEquals("01010000", completed.realtimeStation)
        assertEquals("镇海路", completed.realtimeStationName)
        assertEquals("09:25", completed.realtimeUpdated)
        assertFalse(completed.realtimeLoading)
        assertFalse(completed.direction!!.arrivalsAvailable)
        assertTrue(completed.direction!!.trains.isEmpty())
        assertEquals(listOf(observed), completed.direction!!.positions)
        assertEquals("01020000", completed.direction!!.positions.single().source!!.referenceStationCode)
    }

    @Test fun `同批重新选站不会伪造新采样时间且缺采样时间不覆盖已有时间`() {
        val before = initial.copy(realtimeStation = "01010000", realtimeUpdated = "09:24")
        val (pending, request) = before.beginRealtimeRequest(1)!!
        val cached = pending.completeRealtimeRequest(request, success(), "09:24")
        assertEquals("09:24", cached.realtimeUpdated)
        val (nextPending, nextRequest) = cached.beginRealtimeRequest(2)!!
        assertEquals("09:24", nextPending.completeRealtimeRequest(nextRequest, success(), "").realtimeUpdated)
    }

    @Test fun `反向切站序列保留位置站序采样和方向key并挡住中途站的迟到响应`() {
        val sourceA = MetroObservationSource("01010000", receivedAtElapsedMillis = 1_000, receivedAtEpochMillis = 1_800_000_001_000)
        val sourceB = MetroObservationSource("01020000", receivedAtElapsedMillis = 3_000, receivedAtEpochMillis = 1_800_000_003_000)
        fun observed(code: String, name: String, source: MetroObservationSource) = success(code, name).copy(
            source = source, sources = listOf(source), directions = success(code, name).directions.map { direction ->
                direction.copy(source = source, trains = listOf(MetroTrain(90, "01010000", "镇海路", 0.3, 1, 0, "", source)))
            }
        )
        val original = observed("01010000", "镇海路", sourceA)
        val before = initial.copy(
            realtime = original, realtimeStation = "01010000", realtimeStationName = "镇海路", realtimeUpdated = "09:24"
        ).selectRealtimeDirection(1)
        val (firstPending, firstRequest) = before.beginRealtimeRequest(1)!!
        val changed = firstPending.selectRealtimeStation("01020000")
        assertEquals(before.direction!!.directionKey, changed.selectedDirectionKey)
        assertEquals(before.orderedLine, changed.orderedLine)
        assertEquals(before.direction!!.positions, changed.direction!!.positions)
        assertEquals("09:24", changed.realtimeUpdated)
        assertEquals(sourceA, changed.realtimeState.lastSuccessAt)
        assertEquals(MetroEtaState.Loading("01020000"), changed.etaState)
        assertTrue(changed.direction!!.trains.isEmpty())
        assertSame(changed, changed.completeRealtimeRequest(firstRequest, original, "09:24"))
        val (pendingB, requestB) = changed.beginRealtimeRequest(2)!!
        val fromB = observed("01020000", "中山公园", sourceB).let { it.copy(directions = it.directions.reversed()) }
        val completedB = pendingB.completeRealtimeRequest(requestB, fromB, "09:25")
        assertEquals(0, completedB.directionIndex)
        assertEquals(before.selectedDirectionKey, completedB.selectedDirectionKey)
        assertEquals(completedB.realtime!!.directions[completedB.directionIndex], completedB.direction)
        assertTrue(completedB.etaState is MetroEtaState.Ready)
        val backToA = completedB.selectRealtimeStation("01010000")
        assertEquals(MetroEtaState.Loading("01010000"), backToA.etaState)
        assertEquals(before.orderedLine, backToA.orderedLine)
        assertSame(backToA, backToA.completeRealtimeRequest(requestB, fromB, "09:25"))
        assertSame(backToA, backToA.selectRealtimeStation("不存在"))
    }

    @Test fun `切站后失败保留成功frame而不会恢复旧站ETA或重置sample age`() {
        val source = MetroObservationSource("01010000", receivedAtElapsedMillis = 1_000, receivedAtEpochMillis = 1_800_000_001_000)
        val original = success().copy(source = source)
        val before = initial.copy(realtime = original, realtimeStation = "01010000", realtimeUpdated = "09:24")
        val (pending, request) = before.selectRealtimeStation("01020000").beginRealtimeRequest(1)!!
        val failed = pending.completeRealtimeRequest(request, MetroRealtime(false, "超时"), "09:30")
        assertEquals(original, failed.realtimeLastSuccessfulFrame)
        assertEquals("01020000", failed.realtime!!.stationCode)
        assertEquals(MetroEtaState.Unavailable("01020000", "超时"), failed.etaState)
        assertEquals("09:24", failed.realtimeUpdated)
        assertEquals(29_000L, failed.realtimeState.positionsAt(30_000).ageMillis)
        assertTrue(failed.realtimeState.positionsAt(30_000).isStale)
    }

    @Test fun `方向由两个减少到一个后标题与站序使用同一方向`() {
        val (pending, request) = initial.copy(realtime = success(), directionIndex = 1).beginRealtimeRequest(1)!!
        val reversed = direction().copy(origin = "中山公园", destination = "镇海路", stationCodes = listOf("01020000", "01010000"))
        val completed = pending.completeRealtimeRequest(request, success().copy(directions = listOf(reversed)), "09:25")
        assertEquals(0, completed.directionIndex)
        assertEquals(reversed, completed.direction)
        assertEquals(reversed.stationCodes, completed.orderedLine!!.stations.map { it.code })
    }

    @Test fun `方向仍存在时保留用户选择且非法索引与卡片一样夹紧`() {
        val (pending, request) = initial.copy(directionIndex = 1).beginRealtimeRequest(1)!!
        val completed = pending.completeRealtimeRequest(request, success(), "09:25")
        val realtime = completed.realtime!!
        assertEquals(1, completed.directionIndex)
        assertEquals(realtime.directions.last(), completed.direction)
        assertEquals(realtime.directions.first(), completed.copy(directionIndex = -1, selectedDirectionKey = null).direction)
        assertEquals(realtime.directions.last(), completed.copy(directionIndex = 99, selectedDirectionKey = null).direction)
        assertEquals(realtime.directions.last(), completed.copy(directionIndex = 0).direction)
        assertNull(completed.copy(realtime = success().copy(directions = emptyList()), realtimeLastSuccessfulFrame = null).direction)
    }

    @Test fun `服务端采用其他参考站时站码站名和实时对象一起更新`() {
        val (pending, request) = initial.copy(realtimeStation = "01010000", realtimeStationName = "镇海路").beginRealtimeRequest(1)!!
        val completed = pending.completeRealtimeRequest(request, success("01020000", "中山公园"), "09:25")
        assertEquals("01020000", completed.realtimeStation)
        assertEquals("中山公园", completed.realtimeStationName)
        val realtime = completed.realtime!!
        assertEquals(completed.realtimeStation, realtime.stationCode)
        assertEquals(completed.realtimeStationName, realtime.stationName)
    }

    @Test fun `响应缺站名时只按响应站码精确查找当前线路`() {
        val (pending, request) = initial.copy(realtimeStation = "01010000", realtimeStationName = "镇海路").beginRealtimeRequest(1)!!
        val completed = pending.completeRealtimeRequest(request, success("01020000", ""), "09:25")
        assertEquals("01020000", completed.realtimeStation)
        assertEquals("中山公园", completed.realtimeStationName)
        assertEquals("中山公园", completed.realtime!!.stationName)
        val unknown = pending.completeRealtimeRequest(request, success("不存在的站码", ""), "09:25")
        assertEquals("不存在的站码", unknown.realtimeStation)
        assertEquals("", unknown.realtimeStationName)
    }

    @Test fun `响应缺少参考站时按固定请求站码补全同一份实时对象`() {
        val (pending, request) = initial.copy(realtimeStation = "01020000").beginRealtimeRequest(1)!!
        val completed = pending.completeRealtimeRequest(request, success("", ""), "09:25")
        assertEquals("01020000", completed.realtimeStation)
        assertEquals("中山公园", completed.realtimeStationName)
        val realtime = completed.realtime!!
        assertEquals(completed.realtimeStation, realtime.stationCode)
        assertEquals(completed.realtimeStationName, realtime.stationName)
    }

    @Test fun `真实马青路响应只从选中方向取车且切换后站序随同变化`() {
        val result = MetroRealtimeRemote().parse(
            MetroFixtures.file("metro-round2/rt-02000000-095729-mq.json").readText(), "02000000", "02090000"
        )
        assertTrue(result.note, result.online)
        val towardTianzhu = result.directions.indexOfFirst { it.destination == "天竺山" }
        val towardWuyuan = result.directions.indexOfFirst { it.destination == "五缘湾" }
        assertTrue(towardTianzhu >= 0 && towardWuyuan >= 0)
        val value = initial.copy(selected = "2", realtime = result, directionIndex = towardTianzhu)
        val target = value.direction!!.trains.single { it.nextStationCode == "02090000" }
        assertEquals(82, target.arriveSeconds)
        assertEquals(listOf("02100000", "02090000"), value.orderedLine!!.stations.map { it.code })
        val switched = value.copy(directionIndex = towardWuyuan)
        assertEquals("五缘湾", switched.direction!!.destination)
        assertFalse(switched.direction!!.trains.contains(target))
        assertEquals(listOf("02090000", "02100000"), switched.orderedLine!!.stations.map { it.code })
        // 参考站缺失保留方向和位置，但不能再把原站 ETA 当成未知站的到站行。
        val missingReference = value.copy(realtime = result.copy(stationCode = "不在站序"))
        assertEquals(value.direction!!.stationCodes, missingReference.direction!!.stationCodes)
        assertEquals(value.direction!!.positions, missingReference.direction!!.positions)
        assertTrue(missingReference.direction!!.trains.isEmpty())
        assertTrue(missingReference.etaState is MetroEtaState.Loading)
    }

    private fun direction() = MetroDirection(
        origin = "镇海路", destination = "中山公园", startTime = "06:00", endTime = "23:00",
        delayMinutes = 0, trains = emptyList(), stationCodes = listOf("01010000", "01020000")
    )

    private fun success(code: String = "01010000", name: String = "镇海路") = MetroRealtime(
        online = true, note = "实时到站", lineCode = "01000000", stationCode = code, stationName = name,
        directions = listOf(direction(), direction().copy(
            origin = "中山公园", destination = "镇海路", stationCodes = listOf("01020000", "01010000")
        ))
    )
}
