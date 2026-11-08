package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

class MetroRealtimePlannerTest {
    @Test fun `三十二站固定五锚点加所选邻站后用空余预算补最大空洞`() {
        val stations = (0 until 32).map(Int::toString)
        val plan = planMetroRealtimeReferences(stations, "9")
        assertEquals(stations, plan.stationCodes)
        assertEquals(listOf("9", "8", "10", "0", "16", "23", "31", "4"), plan.referenceStationCodes)
        assertEquals("9", plan.selectedStationCode)
        assertEquals(8, plan.referenceStationCodes.size)
        assertEquals(listOf("4"), plan.gapFillStationCodes)
        assertEquals(8, plan.maxReferenceGapStations)
        assertEquals(24, plan.unqueriedStationCount)
        val indices = plan.referenceStationCodes.map(stations::indexOf).sorted()
        assertTrue(indices.zipWithNext().all { (left, right) -> right - left <= 8 })
    }

    @Test fun `换参考站时固定锚点保持稳定而所选锚点不重复请求`() {
        val stations = (0 until 32).map(Int::toString)
        val first = planMetroRealtimeReferences(stations, "5")
        val changed = planMetroRealtimeReferences(stations, "12")
        val anchors = listOf("0", "8", "16", "23", "31")
        assertTrue(first.referenceStationCodes.containsAll(anchors))
        assertTrue(changed.referenceStationCodes.containsAll(anchors))
        assertEquals(first.referenceStationCodes.drop(3).take(5), changed.referenceStationCodes.drop(3).take(5))
        val selectedAnchor = planMetroRealtimeReferences(stations, "16")
        assertEquals("16", selectedAnchor.referenceStationCodes.first())
        assertEquals(8, selectedAnchor.referenceStationCodes.size)
        assertEquals(8, selectedAnchor.referenceStationCodes.distinct().size)
        assertEquals(listOf("4"), selectedAnchor.gapFillStationCodes)
    }

    @Test fun `短线路最多八站全部取一次并去空码重复码`() {
        val plan = planMetroRealtimeReferences(listOf("", "a", "b", "a", " ", "c"), "b")
        assertEquals(listOf("a", "b", "c"), plan.stationCodes)
        assertEquals(listOf("b", "a", "c"), plan.referenceStationCodes)
        assertEquals(8, planMetroRealtimeReferences((1..8).map(Int::toString), "4").referenceStationCodes.size)
        assertEquals(listOf("a"), planMetroRealtimeReferences(listOf("a"), "a").referenceStationCodes)
        assertTrue(plan.gapFillStationCodes.isEmpty())
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
            assertEquals(minOf(count, METRO_REALTIME_MAX_REFERENCES), plan.referenceStationCodes.size)
            assertEquals(plan.referenceStationCodes.distinct(), plan.referenceStationCodes)
            assertTrue(plan.referenceStationCodes.all { it in stations })
            assertTrue(stations.first() in plan.referenceStationCodes)
            assertTrue(stations.last() in plan.referenceStationCodes)
            assertEquals(selected, plan.referenceStationCodes.first())
        }
    }

    @Test fun `三十二站全部所选站量化旧计划与补洞计划而不夸大覆盖`() {
        val stations = (0 until 32).map(Int::toString)
        val anchors = listOf("0", "8", "16", "23", "31")
        val oldRequestCounts = mutableMapOf<Int, Int>()
        var smallerMaxGap = 0
        var squaredGapReduction = 0
        stations.forEachIndexed { index, selected ->
            val old = (listOf(selected) + listOfNotNull(stations.getOrNull(index - 1), stations.getOrNull(index + 1)) + anchors).distinct()
            oldRequestCounts[old.size] = oldRequestCounts.getOrDefault(old.size, 0) + 1
            val plan = planMetroRealtimeReferences(stations, selected)
            val oldGaps = old.map(stations::indexOf).sorted().zipWithNext { left, right -> right - left }
            val newGaps = plan.referenceStationCodes.map(stations::indexOf).sorted().zipWithNext { left, right -> right - left }
            assertEquals(8, plan.referenceStationCodes.size)
            assertEquals(24, plan.unqueriedStationCount)
            assertTrue(plan.maxReferenceGapStations <= oldGaps.maxOrNull()!!)
            if (plan.maxReferenceGapStations < oldGaps.maxOrNull()!!) smallerMaxGap++
            squaredGapReduction += oldGaps.sumOf { it * it } - newGaps.sumOf { it * it }
        }
        assertEquals(mapOf(6 to 2, 7 to 11, 8 to 19), oldRequestCounts)
        assertEquals(3, smallerMaxGap)
        assertEquals(480, squaredGapReduction)
        // 这是请求站点覆盖的改进；24 个未查询站仍存在，不宣称已有第 5 个真实车辆位置。
    }
}
