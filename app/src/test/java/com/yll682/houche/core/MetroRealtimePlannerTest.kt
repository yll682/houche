package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

class MetroRealtimePlannerTest {
    @Test fun `三十二站固定五锚点加所选和邻站且预算不超过八次`() {
        val stations = (0 until 32).map(Int::toString)
        val plan = planMetroRealtimeReferences(stations, "9")
        assertEquals(stations, plan.stationCodes)
        assertEquals(listOf("9", "8", "10", "0", "16", "23", "31"), plan.referenceStationCodes)
        assertEquals("9", plan.selectedStationCode)
        assertEquals(7, plan.referenceStationCodes.size)
        val indices = plan.referenceStationCodes.map(stations::indexOf).sorted()
        assertTrue(indices.zipWithNext().all { (left, right) -> right - left <= 8 })
    }

    @Test fun `换参考站时固定锚点保持稳定而所选锚点不重复请求`() {
        val stations = (0 until 32).map(Int::toString)
        val first = planMetroRealtimeReferences(stations, "5")
        val changed = planMetroRealtimeReferences(stations, "12")
        assertEquals(first.referenceStationCodes.drop(3), changed.referenceStationCodes.drop(3))
        val selectedAnchor = planMetroRealtimeReferences(stations, "16")
        assertEquals("16", selectedAnchor.referenceStationCodes.first())
        assertEquals(7, selectedAnchor.referenceStationCodes.size)
        assertEquals(7, selectedAnchor.referenceStationCodes.distinct().size)
    }

    @Test fun `短线路最多八站全部取一次并去空码重复码`() {
        val plan = planMetroRealtimeReferences(listOf("", "a", "b", "a", " ", "c"), "b")
        assertEquals(listOf("a", "b", "c"), plan.stationCodes)
        assertEquals(listOf("b", "a", "c"), plan.referenceStationCodes)
        assertEquals(8, planMetroRealtimeReferences((1..8).map(Int::toString), "4").referenceStationCodes.size)
        assertEquals(listOf("a"), planMetroRealtimeReferences(listOf("a"), "a").referenceStationCodes)
    }

    @Test fun `无有效所选站时回首站而空线路不发请求`() {
        val fallback = planMetroRealtimeReferences(listOf("a", "b"), "unknown")
        assertEquals("a", fallback.selectedStationCode)
        assertEquals(listOf("a", "b"), fallback.referenceStationCodes)
        assertTrue(planMetroRealtimeReferences(listOf("", " "), "a").referenceStationCodes.isEmpty())
    }

    @Test fun `任意长度线路锚点不越界且始终保留两端和所选站`() {
        for (count in 1..200) {
            val stations = (0 until count).map(Int::toString)
            val selected = stations[count / 2]
            val plan = planMetroRealtimeReferences(stations, selected)
            assertTrue(plan.referenceStationCodes.size <= METRO_REALTIME_MAX_REFERENCES)
            assertEquals(plan.referenceStationCodes.distinct(), plan.referenceStationCodes)
            assertTrue(plan.referenceStationCodes.all { it in stations })
            assertTrue(stations.first() in plan.referenceStationCodes)
            assertTrue(stations.last() in plan.referenceStationCodes)
            assertEquals(selected, plan.referenceStationCodes.first())
        }
    }
}
