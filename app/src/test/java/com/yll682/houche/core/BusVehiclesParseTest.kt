package com.yll682.houche.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 仅测试规范化模型的定位策略，以下 Vehicle / RouteStation 均为人工构造的应用模型。
 * 此处覆盖定位边界；真实上游响应的逐车解析见 Bus真实车辆夹具测试。
 */
class Bus车辆解析测试 {
    private val stops = listOf(
        RouteStation("起点", 1),
        RouteStation("中间站", 2),
        RouteStation("终点", 3)
    )

    @Test fun `已规范化站序可以直接定位且不依赖列表下标`() {
        assertEquals(stops[1], resolveVehicleStation(Vehicle("测试车", 2, "at-stop"), stops.reversed()))
        assertNull(resolveVehicleStation(Vehicle("测试车", 0, "at-stop"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", 4, "at-stop"), stops))
    }

    @Test fun `站序与角色相符的站名共同确认位置`() {
        assertEquals(stops[1], resolveVehicleStation(
            Vehicle("测试车", 2, "at-stop", currentStation = "中间站", nextStation = "终点"), stops))
        assertEquals(stops[2], resolveVehicleStation(
            Vehicle("测试车", 3, "between", currentStation = "中间站", nextStation = "终点"), stops))
    }

    @Test fun `只有缺失站序可由角色相符的唯一站名兜底而越界保持未知`() {
        assertEquals(stops[1], resolveVehicleStation(Vehicle("测试车", null, "at-stop", currentStation = "中间站"), stops))
        assertEquals(stops[2], resolveVehicleStation(Vehicle("测试车", null, "between", nextStation = "终点"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", 99, "between", nextStation = "终点"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", null, "at-stop", currentStation = "不存在"), stops))
    }

    @Test fun `停靠状态不能用下一站冒充当前站`() {
        assertNull(resolveVehicleStation(Vehicle("测试车", null, "at-stop", nextStation = "终点"), stops))
        assertEquals(stops[1], resolveVehicleStation(Vehicle("测试车", 2, "at-stop", nextStation = "终点"), stops))
    }

    @Test fun `区间状态不能用刚离开的站名冒充前方站`() {
        assertNull(resolveVehicleStation(Vehicle("测试车", null, "between", currentStation = "中间站"), stops))
        assertEquals(stops[2], resolveVehicleStation(Vehicle("测试车", 3, "between", currentStation = "中间站"), stops))
    }

    @Test fun `站名与站序冲突时保持未知而不自动平移一站`() {
        assertNull(resolveVehicleStation(Vehicle("测试车", 2, "between", nextStation = "终点"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", 2, "at-stop", currentStation = "终点"), stops))
    }

    @Test fun `停靠时下一站校验后继而途中当前站校验前驱`() {
        assertNull(resolveVehicleStation(Vehicle("测试车", 2, "at-stop", currentStation = "中间站", nextStation = "中间站"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", 3, "between", currentStation = "起点", nextStation = "终点"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", 3, "at-stop", currentStation = "终点", nextStation = "起点"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", 1, "between", currentStation = "终点", nextStation = "起点"), stops))
    }

    @Test fun `同方向重名站可由站序消歧但不能仅凭名字选择第一个`() {
        val repeated = listOf(RouteStation("同名站", 1), RouteStation("途中", 2), RouteStation("同名站", 3))
        assertEquals(repeated[2], resolveVehicleStation(Vehicle("测试车", 3, "at-stop", currentStation = "同名站"), repeated))
        assertNull(resolveVehicleStation(Vehicle("测试车", null, "at-stop", currentStation = "同名站"), repeated))
    }

    @Test fun `重复站序即使有唯一站名也保持未知`() {
        val duplicated = listOf(RouteStation("甲站", 2), RouteStation("乙站", 2))
        assertNull(resolveVehicleStation(Vehicle("测试车", 2, "at-stop", currentStation = "甲站"), duplicated))
        assertNull(resolveVehicleStation(Vehicle("测试车", null, "at-stop", currentStation = "甲站"), duplicated))
    }

    @Test fun `空白名称不影响有效站序且只裁剪名称首尾空白`() {
        assertEquals(stops[1], resolveVehicleStation(Vehicle("测试车", 2, "at-stop", currentStation = " "), stops))
        assertEquals(stops[1], resolveVehicleStation(Vehicle("测试车", null, "at-stop", currentStation = " 中间站 "), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", null, "at-stop", currentStation = "中 间站"), stops))
    }

    @Test fun `未知状态空站表和无有效站序的站表不能推断位置`() {
        assertNull(resolveVehicleStation(Vehicle("测试车", 2, "unknown", currentStation = "中间站"), stops))
        assertNull(resolveVehicleStation(Vehicle("测试车", 2, "at-stop"), emptyList()))
        assertNull(resolveVehicleStation(Vehicle("测试车", null, "at-stop", currentStation = "中间站"),
            listOf(RouteStation("中间站", null), RouteStation("中间站", 0))))
    }
}
