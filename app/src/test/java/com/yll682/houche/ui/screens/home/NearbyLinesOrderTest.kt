package com.yll682.houche.ui.screens.home

import com.yll682.houche.core.Arrival
import com.yll682.houche.core.LineSummary
import org.junit.Assert.*
import org.junit.Test

/** 首页「附近站点」卡片里的线路列表：同样按「到本站还剩几站」从近到远。 */
class NearbyLinesOrderTest {
    private val lines = listOf(LineSummary("680路", "1"), LineSummary("807路", "1"), LineSummary("854路", "1"), LineSummary("859路", "1"))

    @Test fun `首页附近站点线路按站数从近到远`() {
        val arrivals = listOf(
            Arrival(name = "680路", direction = "1", remainingStations = 7),
            Arrival(name = "807路", direction = "1", remainingStations = 13, distance = 5200.0),
            Arrival(name = "854路", direction = "1", remainingStations = 2)
        )
        // 用户截图：正顺花园 807路 13 站曾排在 854路 2 站前面。
        assertEquals(listOf("854路", "680路", "807路"), nearbyLines(lines, arrivals).map { it.name })
    }

    @Test fun `首页只取最近三条且无实时数据的垫底`() {
        val arrivals = listOf(
            Arrival(name = "859路", direction = "1", remainingStations = 5),
            Arrival(name = "854路", direction = "1", remainingStations = 2),
            Arrival(name = "680路", direction = "1", waiting = true, nextDeparture = "17:20")
        )
        assertEquals(listOf("854路", "859路", "680路"), nearbyLines(lines, arrivals).map { it.name })
    }

    @Test fun `首页没有实时数据时按线路号自然序`() {
        assertEquals(listOf("680路", "807路", "854路"), nearbyLines(lines, emptyList()).map { it.name })
    }
}