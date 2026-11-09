package com.yll682.houche.ui.screens.station

import com.yll682.houche.core.Arrival
import com.yll682.houche.core.LineSummary
import org.junit.Assert.*
import org.junit.Test

/**
 * 站点页「经过线路」的行序：按「到本站还剩几站」从近到远，
 * 没有实时记录的线路按线路号自然序垫底。
 */
class StationLineRowsOrderTest {
    private val lines = listOf(
        LineSummary("680路", "1"), LineSummary("854路", "1"), LineSummary("891路", "1"),
        LineSummary("832路", "1"), LineSummary("859路", "1"), LineSummary("805路", "1"),
        LineSummary("803路", "1"), LineSummary("702路", "1")
    )

    private fun mixed(): List<Arrival> = listOf(
        Arrival(name = "680路", direction = "1", remainingStations = 1),
        Arrival(name = "854路", direction = "1", remainingStations = 1),
        Arrival(name = "854路", direction = "1", remainingStations = 2),
        Arrival(name = "891路", direction = "1", remainingStations = 3),
        Arrival(name = "832路", direction = "1", remainingStations = 2),
        Arrival(name = "859路", direction = "1", remainingStations = 4, distance = 1800.0),
        Arrival(name = "805路", direction = "1", remainingStations = 5, distance = 2500.0),
        Arrival(name = "803路", direction = "1", waiting = true, nextDeparture = "07:30")
    )

    @Test fun `经过线路按站数从近到远`() {
        val rows = stationLineRows(lines, mixed())
        assertEquals(listOf("680路", "854路", "832路", "891路", "859路", "805路"), rows.map { it.first.name }.dropLast(2))
    }

    @Test fun `等待发车与无实时数据的线路垫底`() {
        val rows = stationLineRows(lines, mixed())
        // 803路 只有计划发车、702路 没有实时数据：都在有站数的行之后，702路 最后。
        assertEquals(listOf("803路", "702路"), rows.map { it.first.name }.takeLast(2))
        assertTrue(rows.last().second.isEmpty())
    }

    @Test fun `同一条线路的多辆车都在自己的行里且按站数排列`() {
        val rows = stationLineRows(lines, mixed())
        val row = rows.single { it.first.name == "854路" }
        assertEquals(listOf(1, 2), row.second.map { it.remainingStations })
    }

    @Test fun `没有实时数据时按线路号自然序垫底`() {
        val rows = stationLineRows(lines, emptyList())
        assertEquals(listOf("680路", "702路", "803路", "805路", "832路", "854路", "859路", "891路"), rows.map { it.first.name })
    }
}