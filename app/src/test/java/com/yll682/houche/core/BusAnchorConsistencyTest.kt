package com.yll682.houche.core

import com.yll682.houche.data.AlightReminderEvent
import com.yll682.houche.data.decideAlightReminder
import com.yll682.houche.data.remainingStopsToTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Bus当前站锚点一致性测试 {
    private fun fixture(name: String): JsonElement =
        requireNotNull(javaClass.getResourceAsStream("/bus-fixtures/$name"))
            .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }

    private val samples get() = fixture("vehicle-expectations.json").list("samples")

    private fun stops(sample: JsonElement): List<RouteStation> =
        fixture(sample.s("lineDetail")).field("data").list("stationList")
            .filter { it.s("upperOrDown") == sample.s("direction") }
            .map { RouteStation(it.s("name"), it.i("stationOrder"), it.field("id").text()) }

    private data class 观测(val raw: JsonElement, val vehicle: Vehicle, val target: RouteStation)

    @Test fun `多轮真实同车在原始站序不变时停车行驶和进出码抖动都不改变站差`() {
        val groups = samples.flatMap { sample ->
            val stations = stops(sample)
            fixture(sample.s("file")).list("data").map { raw ->
                val key = listOf(sample.s("line"), sample.s("direction"), raw.s("plateNo"), raw.i("stationOrder").toString())
                key to 观测(raw, parseXiamenVehicle(raw, stations), stations.last())
            }
        }.groupBy({ it.first }, { it.second })
        var mixedSpeedGroups = 0
        var mixedCodeGroups = 0
        for ((key, rows) in groups) {
            val reported = key.last().toInt()
            assertEquals(key.toString(), setOf(reported), rows.map { it.vehicle.currentStationOrder }.toSet())
            val expectedRemaining = rows.first().target.order!! - reported
            assertEquals(key.toString(), setOf(expectedRemaining),
                rows.map { remainingStopsToTarget(it.target, it.vehicle) }.toSet())
            if (rows.any { it.raw.d("speed") == 0.0 } && rows.any { it.raw.d("speed")!! > 0 }) {
                mixedSpeedGroups++
                assertEquals(setOf(reported, reported + 1), rows.map { it.vehicle.order }.toSet())
                assertEquals(setOf("at-stop", "between"), rows.map { it.vehicle.positionState }.toSet())
            }
            if (rows.map { it.raw.s("inOrOut") }.toSet().size > 1) mixedCodeGroups++
        }
        assertTrue("必须覆盖真实停走切换，不能仅比较同一响应的复制", mixedSpeedGroups > 0)
        assertTrue("必须覆盖真实进出码变化", mixedCodeGroups > 0)
    }

    @Test fun `仓库排序用当前站差使同车停走切换不跨入已过站分组`() {
        // 取真实 91 路 order=4 的车辆，内存派生停走对照，不改夹具。
        val sample = samples.first { it.s("file") == "xiamen-91路-d2-ref1-busList-20261114T043246218Z-6.json" }
        val stations = stops(sample)
        val raw = fixture(sample.s("file")).list("data").single { it.s("plateNo") == "闽D17399D" }.jsonObject
        val moving = parseXiamenVehicle(JsonObject(raw + ("speed" to JsonPrimitive(150))), stations)
        val stopped = parseXiamenVehicle(JsonObject(raw + ("speed" to JsonPrimitive(0))), stations)
        val behind = Vehicle("后方对照车", 3, "at-stop", reportedOrder = 3)
        assertEquals(5, moving.order)
        assertEquals(4, stopped.order)
        assertEquals(listOf(moving.plate, behind.plate), sortVehicles(listOf(behind, moving), 4).map { it.plate })
        assertEquals(listOf(stopped.plate, behind.plate), sortVehicles(listOf(behind, stopped), 4).map { it.plate })
    }

    // 原夹具没有 reportedOrder=36 的车辆；以下仅派生内存边界输入，不改写或冒充真实响应。
    private fun loopCases(): List<Pair<JsonElement, List<RouteStation>>> =
        samples.filter { it.s("line") == "803路" }
            .distinctBy { it.s("lineDetail") to it.s("direction") }
            .map { sample -> fixture(sample.s("file")).list("data").first() to stops(sample) }

    private fun atLoopEnd(raw: JsonElement, speed: Int, next: JsonElement = JsonPrimitive("一农公交场站")) =
        JsonObject(raw.jsonObject + mapOf(
            "stationOrder" to JsonPrimitive(36), "speed" to JsonPrimitive(speed), "nextStationName" to next
        ))

    @Test fun `八零三路双向真实站表末站行驶时前方锚点唯一回绕首站`() {
        assertEquals(12, loopCases().size)
        for ((raw, stations) in loopCases()) {
            assertEquals(36, stations.size)
            assertEquals("一农公交场站", stations.single { it.order == 1 }.name)
            assertEquals("一农公交场站", stations.single { it.order == 36 }.name)
            val vehicle = parseXiamenVehicle(atLoopEnd(raw, 150), stations.reversed())
            assertEquals("between", vehicle.positionState)
            assertEquals(1, vehicle.order)
            assertEquals(36, vehicle.reportedOrder)
            assertEquals(36, vehicle.currentStationOrder)
            assertEquals("一农公交场站", vehicle.nextStation)
            assertEquals(1, resolveVehicleStation(vehicle, stations)?.order)
            assertEquals(1, resolveVehicleStation(vehicle.copy(currentStation = "一农公交场站"), stations)?.order)
            assertNull(resolveVehicleStation(vehicle.copy(currentStation = "错误起点"), stations))
            assertEquals(0, remainingStopsToTarget(stations.single { it.order == 36 }, vehicle))
            val decision = decideAlightReminder(0, 36, atStop = false, vehicle = vehicle, stations = stations)
            assertEquals(AlightReminderEvent.PASSED, decision.event)
            assertTrue(decision.state.stopped)
            // 本轮站差仍是有符号减法，不擅自把首站目标当作下一圈目标。
            assertEquals(-35, remainingStopsToTarget(stations.single { it.order == 1 }, vehicle))
        }
    }

    @Test fun `八零三路末站零速仍画在第三十六站并能发送已到站`() {
        for ((raw, stations) in loopCases()) {
            val vehicle = parseXiamenVehicle(atLoopEnd(raw, 0), stations)
            assertEquals("at-stop", vehicle.positionState)
            assertEquals(36, vehicle.order)
            assertEquals(36, vehicle.currentStationOrder)
            assertEquals("一农公交场站", vehicle.nextStation)
            assertEquals(36, resolveVehicleStation(vehicle, stations)?.order)
            assertNull(resolveVehicleStation(vehicle.copy(nextStation = "错误下一站"), stations))
            assertNull(resolveVehicleStation(vehicle.copy(reportedOrder = null), stations))
            val decision = decideAlightReminder(0, 36, atStop = true, vehicle = vehicle)
            assertEquals(AlightReminderEvent.ARRIVED, decision.event)
            assertTrue(decision.state.stopped)
        }
    }

    @Test fun `环线末站未明确提供首站名或速度无效时仍未知`() {
        val (raw, stations) = loopCases().first()
        for (speed in listOf(0, 150)) {
            for (name in listOf(JsonNull, JsonPrimitive(""), JsonPrimitive("其他站"), JsonObject(emptyMap()))) {
                val vehicle = parseXiamenVehicle(atLoopEnd(raw, speed, name), stations)
                assertEquals("unknown", vehicle.positionState)
                assertNull(vehicle.order)
                assertEquals(36, vehicle.currentStationOrder)
            }
        }
        val noSpeed = JsonObject(atLoopEnd(raw, 150).jsonObject - "speed")
        assertEquals("unknown", parseXiamenVehicle(noSpeed, stations).positionState)
        assertEquals("unknown", parseXiamenVehicle(atLoopEnd(raw, -1), stations).positionState)
    }

    @Test fun `非环线首末异名即使下一站声称首站也不能回绕`() {
        for (sample in samples.filter { it.s("line") == "91路" }.distinctBy { it.s("direction") }) {
            val stations = stops(sample)
            assertNotEquals(stations.first().name, stations.last().name)
            val raw = fixture(sample.s("file")).list("data").first().jsonObject
            for (speed in listOf(0, 150)) {
                val boundary = JsonObject(raw + mapOf(
                    "stationOrder" to JsonPrimitive(stations.last().order!!),
                    "speed" to JsonPrimitive(speed), "nextStationName" to JsonPrimitive(stations.first().name)
                ))
                val vehicle = parseXiamenVehicle(boundary, stations)
                assertEquals("unknown", vehicle.positionState)
                assertNull(vehicle.order)
            }
        }
    }

    @Test fun `环线端点重复站序缺失端点或中途同名造成歧义时不猜`() {
        val (raw, stations) = loopCases().first()
        val boundary = atLoopEnd(raw, 150)
        for (invalid in listOf(
            stations + stations.first(), stations + stations.last(),
            stations.drop(1), stations.dropLast(1),
            stations.map { if (it.order == 10) it.copy(name = "一农公交场站") else it },
            stations.map { if (it.order == 36) it.copy(name = "其他场站") else it }
        )) {
            val vehicle = parseXiamenVehicle(boundary, invalid)
            assertEquals("unknown", vehicle.positionState)
            assertNull(vehicle.order)
            assertEquals(36, vehicle.reportedOrder)
        }
    }

    @Test fun `只有明确末站原始序号可消歧回绕而缺失或越界不能靠同名定位`() {
        val (raw, stations) = loopCases().first()
        for (order in listOf(JsonNull, JsonPrimitive(0), JsonPrimitive(35), JsonPrimitive(37))) {
            val fields = JsonObject(atLoopEnd(raw, 150).jsonObject + ("stationOrder" to order))
            if (order == JsonPrimitive(35)) {
                // 35 -> 36 是普通相邻区间，不得误回绕到第 1 站。
                assertEquals(36, parseXiamenVehicle(fields, stations).order)
            } else {
                assertEquals("unknown", parseXiamenVehicle(fields, stations).positionState)
                assertNull(parseXiamenVehicle(fields, stations).order)
            }
        }
    }

    @Test fun `真实三条缺失下一站名仍由唯一相邻站补全且当前站保持三十五`() {
        var count = 0
        for (sample in samples) for (raw in fixture(sample.s("file")).list("data")) {
            if (raw.field("nextStationName") != JsonNull) continue
            count++
            val vehicle = parseXiamenVehicle(raw, stops(sample))
            assertEquals(35, vehicle.currentStationOrder)
            assertEquals(36, vehicle.order)
            assertEquals("between", vehicle.positionState)
            assertEquals("一农公交场站", vehicle.nextStation)
        }
        assertEquals(3, count)
    }

    @Test fun `当前站属性从原有字段派生不改变车辆序列化结构`() {
        val vehicle = Vehicle("测试车", 5, "between", reportedOrder = 4)
        val encoded = Json.encodeToString(vehicle)
        assertFalse(Json.parseToJsonElement(encoded).jsonObject.containsKey("currentStationOrder"))
        val restored = Json.decodeFromString<Vehicle>(encoded)
        assertEquals(vehicle, restored)
        assertEquals(4, restored.currentStationOrder)
        assertEquals(5, restored.order)
        assertEquals(4, Json.decodeFromString<Vehicle>("""{"plate":"旧缓存车","order":4,"positionState":"between"}""").currentStationOrder)
    }
}
