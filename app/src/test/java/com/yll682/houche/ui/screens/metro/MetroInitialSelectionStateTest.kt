package com.yll682.houche.ui.screens.metro

import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroInitialLocation
import com.yll682.houche.core.MetroInitialSelection
import com.yll682.houche.core.MetroInitialSelectionPhase
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroNetwork
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroSnapshot
import com.yll682.houche.core.MetroStation
import com.yll682.houche.core.MetroTrain
import org.junit.Assert.*
import org.junit.Test

/** 只运行纯选择状态机和请求 reducer，不创建 Android 页面、定位服务或网络客户端。 */
class MetroInitialSelectionStateTest {
    private val firstLine = MetroLine(
        id = "1", name = "地铁一号线", apiCode = "01000000",
        stations = listOf(
            station(1, "甲", "01010000", 24.45, 118.10),
            station(2, "乙", "01020000", 24.46, 118.11)
        )
    )
    private val secondLine = MetroLine(
        id = "2", name = "地铁二号线", apiCode = "02000000",
        stations = listOf(
            station(1, "丙", "02010000", 24.50, 118.20),
            station(2, "丁", "02020000", 24.51, 118.21)
        )
    )
    private val lines = listOf(firstLine, secondLine)
    private val location = MetroInitialLocation(24.46, 118.11, 20.0, "GCJ02")
    private val initial = MetroData(lines = lines, selected = "1")

    @Test fun `首次合格定位只应用一次且再次定位或站表替换都不重选`() {
        val first = MetroInitialSelection.start().receive(location, lines)
        assertEquals("1", first.station!!.lineId)
        assertEquals("01020000", first.station!!.stationCode)
        assertEquals(MetroInitialSelectionPhase.Consumed, first.state.phase)
        assertNull(first.state.receive(location.copy(lat = 24.51, lng = 118.21), lines).station)
        assertNull(first.state.stationsLoaded(lines.reversed()).station)
    }

    @Test fun `无权限或失败传null也消费首次机会`() {
        val failed = MetroInitialSelection.start().receive(null, emptyList())
        assertEquals(MetroInitialSelectionPhase.Consumed, failed.state.phase)
        assertNull(failed.state.receive(location, lines).station)
        assertNull(failed.state.stationsLoaded(lines).station)
    }

    @Test fun `未知坐标系和不合格精度在无站表时立即消费而不等待`() {
        listOf(
            location.copy(coordinateSystem = "unconfirmed"),
            location.copy(coordinateSystem = "WGS84"),
            location.copy(lat = null),
            location.copy(accuracyMeters = null),
            location.copy(accuracyMeters = 1_001.0)
        ).forEach { invalid ->
            val rejected = MetroInitialSelection.start().receive(invalid, emptyList())
            assertEquals(MetroInitialSelectionPhase.Consumed, rejected.state.phase)
            assertNull(rejected.state.stationsLoaded(lines).station)
            assertNull(rejected.state.receive(location, lines).station)
        }
    }

    @Test fun `站表迟到兑现首次位置而不用后来的定位替换它`() {
        val waiting = MetroInitialSelection.start().receive(location, emptyList())
        assertEquals(MetroInitialSelectionPhase.AwaitingStations, waiting.state.phase)
        assertNull(waiting.station)
        val repeated = waiting.state.receive(location.copy(lat = 24.51, lng = 118.21), emptyList())
        val applied = repeated.state.stationsLoaded(lines)
        assertEquals("01020000", applied.station!!.stationCode)
        assertEquals(MetroInitialSelectionPhase.Consumed, applied.state.phase)
        assertNull(applied.state.stationsLoaded(lines.reversed()).station)
    }

    @Test fun `等待站表时的手动选择会丢弃待应用位置`() {
        val waiting = MetroInitialSelection.start().receive(location, emptyList()).state
        val manual = waiting.manualSelection()
        assertEquals(MetroInitialSelectionPhase.Consumed, manual.phase)
        assertNull(manual.stationsLoaded(lines).station)
        assertNull(manual.receive(location, lines).station)
    }

    @Test fun `手动选择当前项也挡住迟到定位`() {
        val manual = MetroInitialSelection.start().manualSelection()
        assertNull(manual.receive(location, lines).station)
    }

    @Test fun `显式线路入口优先且未知线路入口也不被GPS覆盖`() {
        listOf("2", "invalid-deep-link").forEach { explicit ->
            val gate = MetroInitialSelection.start(explicit)
            assertEquals(MetroInitialSelectionPhase.Consumed, gate.phase)
            assertNull(gate.receive(location, lines).station)
        }
        assertNotNull(MetroInitialSelection.start("").receive(location, lines).station)
    }

    @Test fun `未知站表坐标来源不选且在线换表后不会补选`() {
        val unknown = lines.map { line -> line.copy(stations = line.stations.map { it.copy(coordinateSystem = "unconfirmed") }) }
        val rejected = MetroInitialSelection.start().receive(location, unknown)
        assertNull(rejected.station)
        assertEquals(MetroInitialSelectionPhase.Consumed, rejected.state.phase)
        assertNull(rejected.state.stationsLoaded(lines).station)
    }

    @Test fun `原子换乘请求从目标站开始且清掉原线路批次`() {
        val previous = withSuccessfulFrame()
        val (pendingOld, oldRequest) = previous.beginRealtimeRequest(7)!!
        val transferred = pendingOld.selectRealtimeTarget("2", "02020000")
        assertEquals("2", transferred.selected)
        assertEquals("02020000", transferred.realtimeStation)
        assertEquals("丁", transferred.realtimeStationName)
        assertNull(transferred.realtime)
        assertNull(transferred.realtimeLastSuccessfulFrame)
        assertNull(transferred.selectedDirectionKey)
        assertEquals("", transferred.realtimeUpdated)
        assertEquals(0L, transferred.realtimeRequestId)
        assertEquals(MetroEtaState.Loading("02020000"), transferred.etaState)
        assertSame(transferred, transferred.completeRealtimeRequest(oldRequest, frame(), "10:01"))
        val (_, request) = transferred.beginRealtimeRequest(8)!!
        assertEquals("02000000", request.lineCode)
        assertEquals("02020000", request.stationCode)
        assertEquals(listOf("02010000", "02020000"), request.stationCodes)
    }

    @Test fun `不存在或不属于目标线路的换乘站不会改变选择与请求`() {
        val (pending, _) = withSuccessfulFrame().beginRealtimeRequest(7)!!
        assertSame(pending, pending.selectRealtimeTarget("missing", "02020000"))
        assertSame(pending, pending.selectRealtimeTarget("2", "01020000"))
        assertSame(pending, pending.selectRealtimeTarget("2", ""))
        val closed = pending.copy(lines = listOf(firstLine, secondLine.copy(opened = false)))
        assertSame(closed, closed.selectRealtimeTarget("2", "02020000"))
        val closedStation = pending.copy(lines = listOf(firstLine, secondLine.copy(
            stations = secondLine.stations.map { it.copy(opened = false) }
        )))
        assertSame(closedStation, closedStation.selectRealtimeTarget("2", "02020000"))
    }

    @Test fun `重复线路id的原子目标保持原状态且关闭的重复线路也不能消除歧义`() {
        val (pending, _) = withSuccessfulFrame().beginRealtimeRequest(7)!!
        for (duplicateOpened in listOf(true, false)) {
            val duplicate = secondLine.copy(name = "同id的另一线路记录", opened = duplicateOpened)
            val ambiguous = pending.copy(lines = lines + duplicate)
            assertSame(ambiguous, ambiguous.selectRealtimeTarget("2", "02020000"))
        }
    }

    @Test fun `同线重复站码的原子目标保持原状态且关闭的重复站也不能消除歧义`() {
        val (pending, _) = withSuccessfulFrame().beginRealtimeRequest(7)!!
        for (targetLine in lines) {
            val targetStation = targetLine.stations.last()
            for (duplicateOpened in listOf(true, false)) {
                val duplicate = targetStation.copy(order = 3, name = "同码另一站", lat = "25.0", opened = duplicateOpened)
                val ambiguousLine = targetLine.copy(stations = targetLine.stations + duplicate)
                val ambiguous = pending.copy(lines = lines.map { if (it.id == targetLine.id) ambiguousLine else it })
                assertSame(ambiguous, ambiguous.selectRealtimeTarget(targetLine.id, targetStation.code))
            }
        }
    }

    @Test fun `同线首次定位换站保留位置但不借旧站ETA`() {
        val previous = withSuccessfulFrame()
        val selected = previous.selectRealtimeTarget("1", "01020000")
        assertEquals("01020000", selected.realtimeStation)
        assertEquals(previous.realtimeLastSuccessfulFrame, selected.realtimeLastSuccessfulFrame)
        assertEquals(previous.direction!!.positions, selected.direction!!.positions)
        assertTrue(selected.direction!!.trains.isEmpty())
        assertEquals(MetroEtaState.Loading("01020000"), selected.etaState)
    }

    @Test fun `线上站表重排及改名按站码保留已选站和请求`() {
        val (previous, request) = withSuccessfulFrame().selectRealtimeStation("01020000").beginRealtimeRequest(7)!!
        val updatedLine = firstLine.copy(stations = firstLine.stations.reversed().mapIndexed { index, station ->
            station.copy(order = index + 1, name = if (station.code == "01020000") "乙新名" else station.name)
        })
        val online = previous.withMetroSnapshot(snapshot(listOf(secondLine, updatedLine)), preferred = "1")
        assertEquals("1", online.selected)
        assertEquals("01020000", online.realtimeStation)
        assertEquals("乙新名", online.realtimeStationName)
        assertEquals(7L, online.realtimeRequestId)
        assertEquals(previous.realtimeLastSuccessfulFrame, online.realtimeLastSuccessfulFrame)
        val completed = online.completeRealtimeRequest(request, frame().copy(stationCode = "01020000", stationName = "乙新名"), "10:02")
        assertEquals("01020000", completed.realtimeStation)
        assertEquals("乙新名", completed.realtimeStationName)
    }

    @Test fun `站表真实删除参考站才退回有效默认且作废旧请求和ETA`() {
        val (previous, request) = withSuccessfulFrame().selectRealtimeStation("01020000").beginRealtimeRequest(7)!!
        val online = previous.withMetroSnapshot(snapshot(listOf(firstLine.copy(stations = firstLine.stations.take(1)))), "1")
        assertEquals("1", online.selected)
        assertEquals("", online.realtimeStation)
        assertNull(online.realtime)
        assertEquals(0L, online.realtimeRequestId)
        assertSame(online, online.completeRealtimeRequest(request, frame(), "10:02"))
        assertEquals("01010000", online.beginRealtimeRequest(8)!!.second.stationCode)
    }

    @Test fun `站表真实删除已选线路才退回现有线路并清旧实时`() {
        val online = withSuccessfulFrame().withMetroSnapshot(snapshot(listOf(secondLine)), "1")
        assertEquals("2", online.selected)
        assertEquals("", online.realtimeStation)
        assertNull(online.realtime)
        assertNull(online.realtimeLastSuccessfulFrame)
        assertEquals(0L, online.realtimeRequestId)
        assertEquals("02010000", online.beginRealtimeRequest(8)!!.second.stationCode)
    }

    @Test fun `同id线路的API身份变化不保留旧位置或本站来源`() {
        val original = withSuccessfulFrame().let { it.copy(realtimeArrivalFrame = it.realtime) }
        val (pending, request) = original.beginRealtimeRequest(7)!!
        val online = pending.withMetroSnapshot(snapshot(listOf(firstLine.copy(apiCode = "09000000"), secondLine)), "1")
        assertNull(online.realtime)
        assertNull(online.realtimeLastSuccessfulFrame)
        assertNull(online.realtimeArrivalFrame)
        assertEquals(0L, online.realtimeRequestId)
        assertSame(online, online.completeRealtimeStationRequest(request, frame(), "10:02"))
        assertEquals("09000000", online.beginRealtimeRequest(8)!!.second.lineCode)
    }

    private fun station(order: Int, name: String, code: String, lat: Double, lng: Double) = MetroStation(
        order, name, code = code, lat = lat.toString(), lng = lng.toString(), coordinateSystem = "GCJ02"
    )

    private fun snapshot(lines: List<MetroLine>) = MetroSnapshot(MetroNetwork(lines = lines), online = true)

    private fun withSuccessfulFrame(): MetroData {
        val frame = frame()
        return initial.copy(realtime = frame, realtimeLastSuccessfulFrame = frame, realtimeStation = "01010000",
            realtimeStationName = "甲", realtimeUpdated = "10:00")
    }

    private fun frame() = MetroRealtime(
        online = true, note = "实时到站", lineCode = "01000000", stationCode = "01010000", stationName = "甲",
        directions = listOf(MetroDirection(
            "甲", "乙", "06:00", "23:00", 0,
            listOf(MetroTrain(90, "01020000", "乙", 0.2, 1, 0, "")),
            listOf("01010000", "01020000")
        ))
    )
}
