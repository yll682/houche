package com.yll682.houche.ui.screens.metro

import com.yll682.houche.core.METRO_OPERATING_ZONE
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroRealtimeState
import com.yll682.houche.core.MetroStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * 本站 ETA 的状态优先级与非运营时段：
 * **非运营时段 > 真失败 > 确实没车 > 加载中**。
 *
 * 非运营时段是终态（等运营时间自然恢复，不重试）；真失败按 1/2/4/8/8 秒退避重试
 * （见 `MetroRealtimeRetryControllerTest`）；确实没车是「成功但没有列车」的结论，
 * 不能再退回「正在获取」；只有既没有成功结论也没有失败时才是加载中。
 */
class MetroEtaStatePriorityTest {
    private fun at(text: String): Instant =
        LocalDateTime.parse(text).atZone(METRO_OPERATING_ZONE).toInstant()

    /** 4 号线集美软件园北：首班 08:00、末班 20:00。 */
    private val line4 = MetroLine(
        id = "4", name = "地铁四号线", color = "#002D72",
        from = "集美软件园北", to = "前浯",
        first = "08:00", last = "20:00",
        stations = listOf(
            MetroStation(1, "集美软件园北", code = "04010000", first = "08:00", last = "20:00")
        )
    )

    /** 成功响应明确「没有到站列车」。 */
    private val emptyFrame = MetroRealtime(
        online = true, note = "实时", stationCode = "04010000", stationName = "集美软件园北",
        directions = listOf(
            MetroDirection("集美软件园北", "前浯", "08:00", "20:00", 0, emptyList(), listOf("04010000", "04020000"))
        )
    )

    private fun metroData(line: MetroLine = line4, requestId: Long = 0L) = MetroData(
        lines = listOf(line), selected = line.id, updated = "2026-11-08",
        realtimeStation = "04010000", realtimeStationName = "集美软件园北",
        realtimeRequestId = requestId
    )

    @Test
    fun `非运营时段压过真失败与加载中`() {
        val state = MetroRealtimeState(
            stationCode = "04010000", stationName = "集美软件园北",
            loading = true, unavailableNote = "HTTP 503", outOfService = true
        )
        assertTrue(state.etaState is MetroEtaState.OutOfService)
        assertEquals("04010000", state.etaState.stationCode)
    }

    @Test
    fun `真失败压过加载中`() {
        val state = MetroRealtimeState(
            stationCode = "04010000", loading = true, unavailableNote = "HTTP 503"
        )
        assertTrue(state.etaState is MetroEtaState.Unavailable)
        assertEquals("HTTP 503", (state.etaState as MetroEtaState.Unavailable).note)
    }

    @Test
    fun `确实没车是成功结论不再退回正在获取`() {
        val state = MetroRealtimeState(frame = emptyFrame)
        assertTrue(state.etaState is MetroEtaState.Empty)
    }

    @Test
    fun `没有任何本站成功结论时才是正在获取`() {
        val state = MetroRealtimeState(stationCode = "04010000", loading = true)
        assertTrue(state.etaState is MetroEtaState.Loading)
        // 没有成功结论时不能编造采样时刻。
        assertNull(state.etaState.lastSuccessAt)
    }

    @Test
    fun `请求在途时以正在获取为准回来后才落回确实没车`() {
        // 实现口径（第十轮结果报告「Loading优先」）：轮询/手刷在途时先显示「正在获取」，
        // 请求回来才落回「暂无到站列车」；确实没车与加载中同时成立时以加载中为准。
        val state = MetroRealtimeState(frame = emptyFrame, stationCode = "04010000", loading = true)
        assertTrue(state.etaState is MetroEtaState.Loading)
    }

    @Test
    fun `非运营时段换成官方文案并清掉请求上下文`() {
        val closed = metroData(requestId = 7L).withOperatingHours(at("2026-11-08T07:19:00"))
        assertTrue(closed.realtimeOutOfService)
        assertTrue(closed.etaState is MetroEtaState.OutOfService)
        assertEquals(
            "非运营时段，暂时无法提供列车到站查询功能",
            metroEtaMessage(closed.etaState)
        )
        // 关门后没有任何在途请求上下文：不会再发请求。
        assertEquals(0L, closed.realtimeRequestId)
        // 迟到的响应也不能把状态改回去（非运营时段不重试、不回写）。
        val request = MetroRealtimeRequest(7L, "4", "04000000", "04010000", listOf("04010000"))
        val after = closed.completeRealtimeStationRequest(request, emptyFrame, "07:19")
        assertSame(closed, after)
        assertTrue(after.etaState is MetroEtaState.OutOfService)
    }

    @Test
    fun `到点开门后不再是非运营状态`() {
        val closed = metroData().withOperatingHours(at("2026-11-08T07:19:00"))
        val open = closed.withOperatingHours(at("2026-11-08T08:00:00"))
        assertFalse(open.realtimeOutOfService)
        assertTrue(open.etaState is MetroEtaState.Loading)
    }

    @Test
    fun `首末班未知时继续请求不得断言非运营`() {
        val unknown = line4.copy(
            first = "", last = "",
            stations = listOf(MetroStation(1, "集美软件园北", code = "04010000"))
        )
        val data = metroData(line = unknown, requestId = 7L).withOperatingHours(at("2026-11-08T07:19:00"))
        assertFalse("未知不是非运营", data.realtimeOutOfService)
        assertFalse(data.etaState is MetroEtaState.OutOfService)
        // 请求上下文保留：在途请求继续跑，不会被运营判定误伤。
        assertEquals(7L, data.realtimeRequestId)
    }
}