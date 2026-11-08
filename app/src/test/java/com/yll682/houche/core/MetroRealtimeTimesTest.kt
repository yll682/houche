package com.yll682.houche.core

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class MetroRealtimeTimesTest {
    private fun stations(direction: Int) = BusNetwork.json.parseToJsonElement(
        MetroFixtures.file("line1-multi/rt-01000000-01080000-173230-plain.json").readText()
    ).list("data")[direction].list("wayStations")

    @Test fun `实时wayStations直接提供双向官方运行秒而长区间反向独立取revTimeCost`() {
        val down = parseMetroRunningTimeProfile(stations(0))!!
        val up = parseMetroRunningTimeProfile(stations(1))!!
        assertTrue(down.valid)
        assertTrue(up.valid)
        assertEquals(240, down.secondsAt("01140000")!! - down.secondsAt("01130000")!!)
        assertEquals(240, down.secondsAt("01150000")!! - down.secondsAt("01140000")!!)
        assertEquals(180, up.secondsAt("01140000")!! - up.secondsAt("01150000")!!)
        assertEquals(240, up.secondsAt("01130000")!! - up.secondsAt("01140000")!!)
        assertEquals(0, down.secondsAt("01010000"))
        assertEquals(0, up.secondsAt("01240000"))
        assertNull(up.secondsAt("UNKNOWN"))
    }

    @Test fun `吕厝两轮ETA与scale证实next是驶入端而非区间起点`() {
        val profile = parseMetroRunningTimeProfile(stations(0))!!
        val remote = MetroRealtimeRemote(elapsedRealtimeMillis = { 0L }, wallClockMillis = { 0L })
        val first = remote.parse(MetroFixtures.file("line1-multi/rt-01000000-01080000-173230.json").readText(), "01000000")
            .directions.first().trains.first()
        val later = remote.parse(MetroFixtures.file("line1-multi/rt-01000000-01080000-173330.json").readText(), "01000000")
            .directions.first().trains.first()
        assertEquals(118, first.arriveSeconds)
        assertEquals(57, later.arriveSeconds)
        assertEquals("01080000", later.nextStationCode)
        assertEquals(.51, later.scale, 0.0)
        assertNull(profile.physicalPositionSeconds(first)) // 0 为退化边界，认车只用 anchor。
        assertEquals(profile.secondsAt("01070000")!! + .51 * 120, profile.physicalPositionSeconds(later)!!, .001)
        assertEquals(58.8, profile.secondsAt("01080000")!! - profile.physicalPositionSeconds(later)!!, .001)
    }

    @Test fun `站链缺码乱序或区间时间缺失不以平均运行时间猜profile`() {
        val original = stations(0)
        assertNull(parseMetroRunningTimeProfile(emptyList()))
        val noTime = original.mapIndexed { index, station -> if (index == 12) JsonObject((station as JsonObject) - "timeCost") else station }
        assertNull(parseMetroRunningTimeProfile(noTime))
        val changed = original.toMutableList().apply { val value = this[1]; this[1] = this[2]; this[2] = value }
        assertNull(parseMetroRunningTimeProfile(changed))
        assertFalse(MetroRunningTimeProfile(listOf("A", "B"), listOf(0, 0)).valid)
        assertFalse(MetroRunningTimeProfile(listOf("A", "A"), listOf(0, 120)).valid)
        assertFalse(MetroRunningTimeProfile(listOf("A", "B"), listOf(0)).valid)
    }
}
