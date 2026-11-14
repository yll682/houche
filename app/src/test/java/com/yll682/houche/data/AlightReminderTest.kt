package com.yll682.houche.data

import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import org.junit.Assert.*
import org.junit.Test

class 下车提醒测试 {
    private val 目标站序 = 5

    @Test fun 剩余两站不提醒也不改变状态() {
        val 结果 = decideAlightReminder(2, 目标站序)

        assertEquals(AlightReminderEvent.NONE, 结果.event)
        assertEquals(AlightReminderState(), 结果.state)
    }

    @Test fun 剩余一站只记录提前提醒() {
        val 结果 = decideAlightReminder(1, 目标站序)

        assertEquals(AlightReminderEvent.BEFORE, 结果.event)
        assertEquals(AlightReminderState(beforeOrder = 目标站序), 结果.state)
    }

    @Test fun 停靠目标站时记录到站并终止行程() {
        val 结果 = decideAlightReminder(0, 目标站序)

        assertEquals(AlightReminderEvent.ARRIVED, 结果.event)
        assertEquals(AlightReminderState(arrivedOrder = 目标站序, stopped = true), 结果.state)
    }

    @Test fun 已过一站时终止但不补发到站提醒() {
        val 结果 = decideAlightReminder(-1, 目标站序)

        assertEquals(AlightReminderEvent.PASSED, 结果.event)
        assertEquals(AlightReminderState(stopped = true), 结果.state)
    }

    @Test fun 离线站差为空时保留全部提醒标记() {
        val 原状态 = AlightReminderState(beforeOrder = 目标站序, arrivedOrder = 3)
        val 结果 = decideAlightReminder(null, 目标站序, 原状态)

        assertEquals(AlightReminderEvent.NONE, 结果.event)
        assertEquals(原状态, 结果.state)
    }

    @Test fun 目标站序缺失或非正数时所有站差均不触发提醒() {
        val 原状态 = AlightReminderState(beforeOrder = 3, arrivedOrder = 2)
        for (站序 in listOf(null, 0, -1)) {
            for (站差 in listOf(2, 1, 0, -1, null)) {
                val 结果 = decideAlightReminder(站差, 站序, 原状态)
                assertEquals("目标站序=$站序，站差=$站差", AlightReminderEvent.NONE, 结果.event)
                assertEquals(原状态, 结果.state)
            }
        }
    }

    @Test fun 同一目标的提前提醒只触发一次() {
        val 首次 = decideAlightReminder(1, 目标站序)
        val 再次 = decideAlightReminder(1, 目标站序, 首次.state)

        assertEquals(AlightReminderEvent.BEFORE, 首次.event)
        assertEquals(AlightReminderEvent.NONE, 再次.event)
        assertEquals(首次.state, 再次.state)
    }

    @Test fun 到站标记单独去重且保留提前标记() {
        val 原状态 = AlightReminderState(beforeOrder = 目标站序, arrivedOrder = 目标站序)
        val 结果 = decideAlightReminder(0, 目标站序, 原状态)

        assertEquals(AlightReminderEvent.NONE, 结果.event)
        assertEquals(原状态, 结果.state)
    }

    @Test fun 提前提醒后仍可独立触发一次到站提醒() {
        val 提前 = decideAlightReminder(1, 目标站序)
        val 到站 = decideAlightReminder(0, 目标站序, 提前.state)
        val 再次到站 = decideAlightReminder(0, 目标站序, 到站.state)

        assertEquals(AlightReminderEvent.BEFORE, 提前.event)
        assertEquals(AlightReminderEvent.ARRIVED, 到站.event)
        assertEquals(
            AlightReminderState(beforeOrder = 目标站序, arrivedOrder = 目标站序, stopped = true),
            到站.state
        )
        assertEquals(AlightReminderEvent.NONE, 再次到站.event)
        assertEquals(到站.state, 再次到站.state)
    }

    @Test fun 其他站序的提前标记不会误去重新目标() {
        val 原状态 = AlightReminderState(beforeOrder = 3, arrivedOrder = 2)
        val 结果 = decideAlightReminder(1, 目标站序, 原状态)

        assertEquals(AlightReminderEvent.BEFORE, 结果.event)
        assertEquals(原状态.copy(beforeOrder = 目标站序), 结果.state)
    }

    @Test fun 其他站序的到站标记不会误去重新目标() {
        val 原状态 = AlightReminderState(beforeOrder = 目标站序, arrivedOrder = 3)
        val 结果 = decideAlightReminder(0, 目标站序, 原状态)

        assertEquals(AlightReminderEvent.ARRIVED, 结果.event)
        assertEquals(原状态.copy(arrivedOrder = 目标站序, stopped = true), 结果.state)
    }

    @Test fun 过站终止时保留原有提醒标记() {
        val 原状态 = AlightReminderState(beforeOrder = 目标站序, arrivedOrder = 3)
        val 结果 = decideAlightReminder(-1, 目标站序, 原状态)

        assertEquals(AlightReminderEvent.PASSED, 结果.event)
        assertEquals(原状态.copy(stopped = true), 结果.state)
    }

    @Test fun 过站后位置回跳或离线都不能重启提醒() {
        val 过站 = decideAlightReminder(-1, 目标站序, AlightReminderState(beforeOrder = 目标站序))
        for (站差 in listOf(2, 1, 0, -1, null)) {
            val 结果 = decideAlightReminder(站差, 目标站序, 过站.state)
            assertEquals("过站后站差=$站差", AlightReminderEvent.NONE, 结果.event)
            assertEquals(过站.state, 结果.state)
        }
    }

    @Test fun 到站结束后位置回跳或离线都保留终止状态() {
        val 到站 = decideAlightReminder(0, 目标站序, AlightReminderState(beforeOrder = 目标站序))
        for (站差 in listOf(2, 1, 0, -1, null)) {
            val 结果 = decideAlightReminder(站差, 目标站序, 到站.state)
            assertEquals("到站后站差=$站差", AlightReminderEvent.NONE, 结果.event)
            assertEquals(到站.state, 结果.state)
        }
    }

    @Test fun 提前提醒后回跳和离线不会清除去重标记() {
        var 状态 = decideAlightReminder(1, 目标站序).state
        for (站差 in listOf(2, null, 1, 2, 1)) {
            val 结果 = decideAlightReminder(站差, 目标站序, 状态)
            assertEquals("回跳或离线后的站差=$站差", AlightReminderEvent.NONE, 结果.event)
            assertEquals(AlightReminderState(beforeOrder = 目标站序), 结果.state)
            状态 = 结果.state
        }
        assertEquals(AlightReminderEvent.ARRIVED, decideAlightReminder(0, 目标站序, 状态).event)
    }

    @Test fun 途中距离一站仍可提前提醒() {
        val 结果 = decideAlightReminder(1, 目标站序, atStop = false)

        assertEquals(AlightReminderEvent.BEFORE, 结果.event)
        assertEquals(AlightReminderState(beforeOrder = 目标站序), 结果.state)
    }

    @Test fun 未校准途中只有前方锚点不能证明驶离而应等实际停靠() {
        val 目标 = RouteStation("下车站", 目标站序)
        val 途中车辆 = Vehicle("测试车", 目标站序, "between")
        val 原状态 = AlightReminderState(beforeOrder = 目标站序)
        val 途中 = decideAlightReminder(
            remainingStopsToTarget(目标, 途中车辆), 目标.order, 原状态,
            atStop = 途中车辆.positionState == "at-stop", vehicle = 途中车辆
        )

        assertEquals(0, remainingStopsToTarget(目标, 途中车辆))
        assertEquals(AlightReminderEvent.NONE, 途中.event)
        assertEquals(原状态, 途中.state)

        val 停靠车辆 = 途中车辆.copy(positionState = "at-stop")
        val 停靠 = decideAlightReminder(
            remainingStopsToTarget(目标, 停靠车辆), 目标.order, 途中.state,
            atStop = 停靠车辆.positionState == "at-stop", vehicle = 停靠车辆
        )
        assertEquals(AlightReminderEvent.ARRIVED, 停靠.event)
        assertEquals(原状态.copy(arrivedOrder = 目标站序, stopped = true), 停靠.state)
    }

    @Test fun 停靠和途中都保留两站一站零站及过站的原始站差() {
        val 目标 = RouteStation("下车站", 目标站序)
        for (位置 in listOf("at-stop", "between")) {
            for ((当前站序, 预期站差) in listOf(3 to 2, 4 to 1, 5 to 0, 6 to -1)) {
                assertEquals(
                    "位置=$位置，当前站序=$当前站序",
                    预期站差,
                    remainingStopsToTarget(目标, Vehicle("测试车", 当前站序, 位置))
                )
            }
        }
    }

    @Test fun 目标或车辆缺失时站差未知() {
        assertNull(remainingStopsToTarget(null, Vehicle("测试车", 4, "at-stop")))
        assertNull(remainingStopsToTarget(RouteStation("下车站", 目标站序), null))
        assertNull(remainingStopsToTarget(null, null))
    }

    @Test fun 目标站序缺失或非正数时站差未知() {
        for (站序 in listOf(null, 0, -1)) {
            assertNull(remainingStopsToTarget(RouteStation("下车站", 站序), Vehicle("测试车", 4, "at-stop")))
        }
    }

    @Test fun 车辆站序缺失或非正数时站差未知() {
        for (站序 in listOf(null, 0, -1)) {
            for (位置 in listOf("at-stop", "between")) {
                assertNull(remainingStopsToTarget(RouteStation("下车站", 目标站序), Vehicle("测试车", 站序, 位置)))
            }
        }
    }

    @Test fun 未知位置有当前站序可以计算站差但零站差不能声称停靠或驶离() {
        val 原状态 = AlightReminderState(beforeOrder = 目标站序)
        for (位置 in listOf("unknown", "offline", "")) {
            val 站差 = remainingStopsToTarget(
                RouteStation("下车站", 目标站序), Vehicle("测试车", null, 位置, reportedOrder = 目标站序)
            )
            assertEquals(0, 站差)
            val 结果 = decideAlightReminder(站差, 目标站序, 原状态, atStop = false)
            assertEquals(AlightReminderEvent.NONE, 结果.event)
            assertEquals(原状态, 结果.state)
        }
    }

    @Test fun 站差优先原始当前站序且不使用下一站名称() {
        val 车辆 = Vehicle(
            plate = "测试车", order = 4, positionState = "between",
            reportedOrder = 99, nextStation = "下车站", currentStation = "其他站"
        )

        assertEquals(99, 车辆.currentStationOrder)
        assertEquals(-94, remainingStopsToTarget(RouteStation("下车站", 目标站序), 车辆))
    }

    @Test fun 重复站名按各自站序计算已过或未到() {
        val 车辆 = Vehicle("测试车", 4, "at-stop")

        assertEquals(-1, remainingStopsToTarget(RouteStation("同名站", 3), 车辆))
        assertEquals(1, remainingStopsToTarget(RouteStation("同名站", 5), 车辆))
    }

    @Test fun 厦门已驶离目标站即使站差为零也判过站并停止且不重复() {
        val 目标 = RouteStation("下车站", 目标站序)
        val 车辆 = Vehicle("测试车", 目标站序 + 1, "between", reportedOrder = 目标站序)
        val 原状态 = AlightReminderState(beforeOrder = 目标站序)
        assertEquals(0, remainingStopsToTarget(目标, 车辆))
        val 结果 = decideAlightReminder(0, 目标站序, 原状态, atStop = false, vehicle = 车辆)
        assertEquals(AlightReminderEvent.PASSED, 结果.event)
        assertEquals(原状态.copy(stopped = true), 结果.state)
        assertNull(结果.state.arrivedOrder)
        val 恢复 = 结果.state.copy()
        for (站差 in listOf(0, 1, -1, null)) {
            val 再次 = decideAlightReminder(站差, 目标站序, 恢复, atStop = false, vehicle = 车辆)
            assertEquals(AlightReminderEvent.NONE, 再次.event)
            assertEquals(恢复, 再次.state)
        }
    }

    @Test fun 同一原始站序从停靠切换为行驶不改变剩余站数() {
        val 目标 = RouteStation("下车站", 目标站序)
        val 停靠 = Vehicle("测试车", 4, "at-stop", reportedOrder = 4)
        val 途中 = 停靠.copy(order = 5, positionState = "between")
        val 未知 = 停靠.copy(order = null, positionState = "unknown")
        for (车辆 in listOf(停靠, 途中, 未知)) {
            assertEquals(4, 车辆.currentStationOrder)
            assertEquals(1, remainingStopsToTarget(目标, 车辆))
        }
    }

    @Test fun 原始当前站序非正时回退有效绘图锚点且都无效才未知() {
        for (原始 in listOf(null, 0, -1)) {
            val 车辆 = Vehicle("测试车", 4, "unknown", reportedOrder = 原始)
            assertEquals(4, 车辆.currentStationOrder)
            assertEquals(1, remainingStopsToTarget(RouteStation("下车站", 目标站序), 车辆))
            for (锚点 in listOf(null, 0, -1)) {
                assertNull(车辆.copy(order = 锚点).currentStationOrder)
                assertNull(remainingStopsToTarget(RouteStation("下车站", 目标站序), 车辆.copy(order = 锚点)))
            }
        }
    }

    @Test fun 零站差但未知状态或不相邻锚点不能推断驶离() {
        val 原状态 = AlightReminderState(beforeOrder = 目标站序)
        for (车辆 in listOf(
            Vehicle("测试车", 6, "unknown", reportedOrder = 5),
            Vehicle("测试车", null, "between", reportedOrder = 5),
            Vehicle("测试车", 7, "between", reportedOrder = 5)
        )) {
            val 结果 = decideAlightReminder(0, 目标站序, 原状态, atStop = false, vehicle = 车辆)
            assertEquals(AlightReminderEvent.NONE, 结果.event)
            assertEquals(原状态, 结果.state)
            assertEquals(AlightReminderEvent.NONE,
                decideAlightReminder(0, 目标站序, 原状态, vehicle = 车辆).event)
        }
    }

    @Test fun 环线末站已驶向首站则零站差也判驶离末站() {
        val 车辆 = Vehicle("环线车", 1, "between", reportedOrder = 36, nextStation = "一农公交场站")
        val 站表 = listOf(RouteStation("一农公交场站", 1), RouteStation("一农公交场站", 36))
        val 结果 = decideAlightReminder(0, 36, atStop = false, vehicle = 车辆, stations = 站表)
        assertEquals(AlightReminderEvent.PASSED, 结果.event)
        assertTrue(结果.state.stopped)
        assertNull(结果.state.arrivedOrder)
        for (无效站表 in listOf(emptyList(), 站表 + 站表.first(), 站表 + RouteStation("一农公交场站", 10))) {
            assertEquals(AlightReminderEvent.NONE,
                decideAlightReminder(0, 36, atStop = false, vehicle = 车辆, stations = 无效站表).event)
        }
    }
}
