package com.yll682.houche.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * 运营时段判定：按厦门站表的首末班（分钟精度、Asia/Taipei）判窗。
 *
 * 用户现场是 4 号线集美软件园北（首 08:00 末 20:00）在 07:19 看到的「正在获取」；
 * 官方同一时刻给的是「非运营时段，暂时无法提供列车到站查询功能」。
 * 首末班字段缺失或非法时必须判为未知——未知不能拿来断言非运营，只能继续请求。
 */
class MetroOperatingHoursTest {
    private fun at(text: String): Instant =
        LocalDateTime.parse(text).atZone(METRO_OPERATING_ZONE).toInstant()

    /** 4 号线集美软件园北：首班 08:00、末班 20:00（站表与线路级一致）。 */
    private val line4 = MetroLine(
        id = "4", name = "地铁四号线", color = "#002D72",
        from = "集美软件园北", to = "前浯",
        first = "08:00", last = "20:00",
        stations = listOf(
            MetroStation(1, "集美软件园北", code = "04010000", first = "08:00", last = "20:00")
        )
    )

    @Test
    fun `4号线首班前非运营首班起运营末班分钟含末班整分后关闭`() {
        val station = line4.stations.single()
        val before = metroOperatingWindow(line4, station, at("2026-11-08T07:19:00"))
        assertEquals(MetroOperatingStatus.OutOfService, before.status)
        // 下一个变化点就是今天首班，页面据此等待到点自动恢复。
        assertEquals(at("2026-11-08T08:00:00"), before.nextChangeAt)
        assertEquals(MetroOperatingStatus.InService, metroOperatingWindow(line4, station, at("2026-11-08T08:00:00")).status)
        assertEquals(MetroOperatingStatus.InService, metroOperatingWindow(line4, station, at("2026-11-08T20:00:59")).status)
        assertEquals(MetroOperatingStatus.OutOfService, metroOperatingWindow(line4, station, at("2026-11-08T20:01:00")).status)
    }

    /** 2 号线跨夜：首班 06:00、末班次日 00:07，今日凌晨仍属于昨夜的运营窗口。 */
    @Test
    fun `2号线跨夜站在末班次日之前仍算运营之后关闭`() {
        val night = MetroLine(
            id = "2", name = "地铁二号线", color = "#009639",
            from = "天竺山", to = "五缘湾",
            first = "06:00", last = "次日00:07",
            stations = listOf(
                MetroStation(1, "天竺山", code = "02010000", first = "06:00", last = "次日00:07")
            )
        )
        val station = night.stations.single()
        assertEquals(MetroOperatingStatus.InService, metroOperatingWindow(night, station, at("2026-11-08T00:05:00")).status)
        assertEquals(MetroOperatingStatus.OutOfService, metroOperatingWindow(night, station, at("2026-11-08T00:08:00")).status)
    }

    /** 不写「次日」但末班早于首班，同样按跨夜处理。 */
    @Test
    fun `末班早于首班不写次日也按跨夜窗口判定`() {
        val night = line4.copy(
            id = "2", name = "地铁二号线", first = "06:00", last = "00:07",
            stations = listOf(MetroStation(1, "天竺山", code = "02010000", first = "06:00", last = "00:07"))
        )
        val station = night.stations.single()
        assertEquals(MetroOperatingStatus.InService, metroOperatingWindow(night, station, at("2026-11-08T00:05:00")).status)
        assertEquals(MetroOperatingStatus.OutOfService, metroOperatingWindow(night, station, at("2026-11-08T00:08:00")).status)
    }

    @Test
    fun `站级首末班为空时逐项回退线路级`() {
        val bare = MetroStation(1, "集美软件园北", code = "04010000")
        assertEquals(MetroOperatingTimes("08:00", "20:00"), metroStationOperatingTimes(line4, bare))
        // 只有首班为空时也只回退首班，另一项仍用站级自己的值。
        val mixed = MetroStation(2, "后溪", code = "04020000", first = "08:02", last = "")
        assertEquals(MetroOperatingTimes("08:02", "20:00"), metroStationOperatingTimes(line4, mixed))
        assertEquals(
            MetroOperatingStatus.OutOfService,
            metroOperatingWindow(line4, bare, at("2026-11-08T07:19:00")).status
        )
    }

    @Test
    fun `站级与线路级都为空时判为未知不能据此断言非运营`() {
        val unknown = line4.copy(
            first = "", last = "",
            stations = listOf(MetroStation(1, "集美软件园北", code = "04010000"))
        )
        val station = unknown.stations.single()
        val window = metroOperatingWindow(unknown, station, at("2026-11-08T07:19:00"))
        assertEquals(MetroOperatingStatus.Unknown, window.status)
        assertNull("未知没有可等待的变化点", window.nextChangeAt)
    }

    @Test
    fun `非法时间同样判为未知`() {
        val broken = line4.copy(
            first = "8:00", last = "20:00",
            stations = listOf(MetroStation(1, "集美软件园北", code = "04010000"))
        )
        assertEquals(
            MetroOperatingStatus.Unknown,
            metroOperatingWindow(broken, broken.stations.single(), at("2026-11-08T07:19:00")).status
        )
    }
}