package com.yll682.houche

import com.yll682.houche.core.Route
import com.yll682.houche.core.VehicleShareTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Vehicle深链路由测试 {
    private val target = VehicleShareTarget("0592", "line-91", "91路", "2", "闽D28810")
    private val route = Route("91路", "line-91", "2")

    @Test fun `优先按编号与方向匹配完整线路而不依赖旧名称`() = runBlocking<Unit> {
        val result = resolveVehicleShareRoute(target.copy(lineName = "旧名称"), listOf(route)) { _, _ ->
            error("已有编号匹配时不应再查名称")
        }
        assertSame(route, result)
    }

    @Test fun `没有已知编号时复用线路页名称方向入口并校验编号`() = runBlocking<Unit> {
        var requested: Pair<String, String>? = null
        val result = resolveVehicleShareRoute(target, emptyList()) { name, direction ->
            requested = name to direction
            route
        }
        assertEquals("91路" to "2", requested)
        assertSame(route, result)
    }

    @Test fun `缺编号按名称与方向查且不会要求车辆在线`() = runBlocking<Unit> {
        val result = resolveVehicleShareRoute(target.copy(lineId = null), emptyList()) { _, _ -> route }
        assertSame(route, result)
        assertTrue(result!!.stations.isEmpty())
    }

    @Test fun `编号不匹配时不能打开同名的另一条线路`() = runBlocking<Unit> {
        assertNull(resolveVehicleShareRoute(target, emptyList()) { _, _ -> route.copy(id = "other") })
    }

    @Test fun `已有编号的另一方向不能代替链接指定方向`() = runBlocking<Unit> {
        var queries = 0
        assertSame(route, resolveVehicleShareRoute(target, listOf(route.copy(direction = "1"))) { _, _ ->
            queries++
            route
        })
        assertEquals(1, queries)
    }

    @Test fun `上游返回错误方向时不能打开车辆页`() = runBlocking<Unit> {
        assertNull(resolveVehicleShareRoute(target, emptyList()) { _, _ -> route.copy(direction = "1") })
    }

    @Test fun `无编号时上游返回另一名称视为没找到`() = runBlocking<Unit> {
        assertNull(resolveVehicleShareRoute(target.copy(lineId = null), emptyList()) { _, _ -> route.copy(name = "19路") })
    }

    @Test fun `上游返回空线路资料视为没找到`() = runBlocking<Unit> {
        assertNull(resolveVehicleShareRoute(target, emptyList()) { _, _ -> route.copy(name = "") })
    }

    @Test fun `无方向不猜单程也不发请求`() = runBlocking<Unit> {
        for (direction in listOf("", "0", "3")) {
            assertNull(resolveVehicleShareRoute(target.copy(direction = direction), listOf(route)) { _, _ ->
                error("缺失或非法方向不应查询")
            })
        }
    }

    @Test fun `无名称且无已知编号时不能把编号当线路名查询`() = runBlocking<Unit> {
        assertNull(resolveVehicleShareRoute(target.copy(lineName = ""), emptyList()) { _, _ ->
            error("不能把编号当作名称")
        })
    }

    @Test fun `查询网络错误传递给调用方显示失败提示`() = runBlocking<Unit> {
        val failure = IOException("断网")
        try {
            resolveVehicleShareRoute(target, emptyList()) { _, _ -> throw failure }
            fail("应保留网络错误")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
    }

    @Test fun `查询取消继续传播以阻止过期链接导航`() = runBlocking<Unit> {
        val cancel = CancellationException("新链接已接管")
        try {
            resolveVehicleShareRoute(target, emptyList()) { _, _ -> throw cancel }
            fail("应保留取消")
        } catch (error: CancellationException) {
            assertSame(cancel, error)
        }
    }
}
