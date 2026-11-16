package com.yll682.houche.ui.screens.metro

import android.app.Application
import android.os.SystemClock
import com.yll682.houche.core.METRO_REALTIME_WINDOW_MILLIS
import com.yll682.houche.core.MetroObservationSource
import com.yll682.houche.core.MetroRealtime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MetroRealtimeFreshnessTest {
    @Test fun `位置到达窗口边界即陈旧且不重算成功时间`() {
        val received = SystemClock.elapsedRealtime()
        val frame = observed(received)
        val data = MetroData(realtime = frame, realtimeUpdated = "12:03")
        assertEquals(0L, data.realtimeAgeMillis())
        assertEquals(0L, data.realtimeAgeMillis(received))
        assertFalse(data.realtimeStale)
        ShadowSystemClock.advanceBy(Duration.ofMillis(METRO_REALTIME_WINDOW_MILLIS - 1))
        assertEquals(METRO_REALTIME_WINDOW_MILLIS - 1, data.realtimeAgeMillis())
        assertFalse(data.realtimeStale)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertEquals(METRO_REALTIME_WINDOW_MILLIS, data.realtimeAgeMillis())
        assertTrue(data.realtimeStale)
        assertEquals("12:03", data.realtimeUpdated)
        assertSame(frame, data.realtime)
    }

    @Test fun `没有位置时钟未知及未来采样都保持陈旧`() {
        val now = SystemClock.elapsedRealtime()
        val cases = listOf(
            MetroData(),
            MetroData(realtime = MetroRealtime(online = true, note = "")),
            MetroData(realtime = observed(now + 1))
        )
        cases.forEach { data ->
            assertNull(data.realtimeAgeMillis(now))
            assertTrue(data.realtimeStale)
        }
    }

    @Test fun `未超时的位置在失败和非运营状态下仍陈旧`() {
        val received = SystemClock.elapsedRealtime()
        val data = MetroData(realtime = observed(received), realtimeUpdated = "12:03")
        assertFalse(data.realtimeStale)
        listOf(
            data.copy(realtimeUnavailable = true, realtimeNote = "上游超时"),
            data.copy(realtimeOutOfService = true)
        ).forEach { unavailable ->
            assertEquals(0L, unavailable.realtimeAgeMillis())
            assertTrue(unavailable.realtimeStale)
            assertEquals(data.realtimeUpdated, unavailable.realtimeUpdated)
        }
    }

    @Test fun `独立本站成功不能为旧位置批次续命`() {
        val received = SystemClock.elapsedRealtime()
        val positions = observed(received)
        ShadowSystemClock.advanceBy(Duration.ofMillis(METRO_REALTIME_WINDOW_MILLIS))
        val data = MetroData(
            realtimeLastSuccessfulFrame = positions,
            realtimeArrivalFrame = observed(SystemClock.elapsedRealtime()),
            realtimeUpdated = "12:04"
        )
        assertEquals(METRO_REALTIME_WINDOW_MILLIS, data.realtimeAgeMillis())
        assertTrue(data.realtimeStale)
        assertEquals("12:04", data.realtimeUpdated)
        assertEquals(1L, data.realtimeAgeMillis(received + 1))
        assertSame(positions, data.realtimeLastSuccessfulFrame)
    }

    private fun observed(received: Long) = MetroRealtime(
        online = true, note = "", stationCode = "01010000",
        source = MetroObservationSource("01010000", receivedAtElapsedMillis = received)
    )
}
