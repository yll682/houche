package com.yll682.houche.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class 车辆跟踪持久化测试 {
    private val 应用: Application = ApplicationProvider.getApplicationContext()
    private val 偏好 get() = Prefs(应用)

    @Before fun 清空测试目标和选站() = runBlocking {
        偏好.setTrackedVehicle(null)
        偏好.setVehicleAlightSelection(null)
    }

    @After fun 清理测试目标和选站() = runBlocking {
        偏好.setTrackedVehicle(null)
        偏好.setVehicleAlightSelection(null)
    }

    @Test fun 新偏好实例恢复完整目标并按站序选择后一个同名站() = runBlocking {
        val 目标 = 完整跟踪目标()
        偏好.setTrackedVehicle(目标)

        val 恢复 = Prefs(应用).trackedVehicle.first()!!
        assertEquals(目标, 恢复)
        assertEquals(2, 恢复.route.stations.count { it.name == "同名站" })
        assertEquals(恢复.route.stations.last(), 恢复.alight)
        assertEquals(5, 恢复.alight!!.order)
        assertEquals(AlightReminderState(beforeOrder = 5), 恢复.reminder)
        assertEquals(
            AlightReminderEvent.NONE,
            decideAlightReminder(1, 恢复.alightOrder, 恢复.reminder).event
        )
    }

    @Test fun 到站和停止标记跨偏好实例恢复且不再提醒() = runBlocking {
        val 目标 = 完整跟踪目标().copy(
            reminder = AlightReminderState(beforeOrder = 5, arrivedOrder = 5, stopped = true)
        )
        偏好.setTrackedVehicle(目标)

        val 恢复 = Prefs(应用).trackedVehicle.first()!!
        assertEquals(目标, 恢复)
        for (站差 in listOf(2, 1, 0, -1, null)) {
            val 结果 = decideAlightReminder(站差, 恢复.alightOrder, 恢复.reminder)
            assertEquals(AlightReminderEvent.NONE, 结果.event)
            assertEquals(目标.reminder, 结果.state)
        }
    }

    @Test fun 过站终止标记跨偏好实例恢复而不伪造到站标记() = runBlocking {
        val 目标 = 完整跟踪目标().copy(
            reminder = AlightReminderState(beforeOrder = 5, stopped = true)
        )
        偏好.setTrackedVehicle(目标)

        val 恢复 = Prefs(应用).trackedVehicle.first()!!
        assertEquals(目标.reminder, 恢复.reminder)
        assertNull(恢复.reminder.arrivedOrder)
        assertEquals(AlightReminderEvent.NONE, decideAlightReminder(1, 5, 恢复.reminder).event)
    }

    @Test fun 站表缺失目标时保留原站序而不误选首个同名站() = runBlocking {
        val 完整 = 完整跟踪目标()
        val 目标 = 完整.copy(route = 完整.route.copy(stations = 完整.route.stations.filterNot { it.order == 5 }))
        偏好.setTrackedVehicle(目标)

        val 恢复 = Prefs(应用).trackedVehicle.first()!!
        assertEquals(目标, 恢复)
        assertEquals(RouteStation("同名站", 5), 恢复.alight)
        assertEquals(2, 恢复.route.stations.first { it.name == "同名站" }.order)
    }

    @Test fun 目标站序未知时保留名字且不取首个同名站序() = runBlocking {
        val 目标 = 完整跟踪目标().copy(alightOrder = null, reminder = AlightReminderState())
        偏好.setTrackedVehicle(目标)

        val 恢复 = Prefs(应用).trackedVehicle.first()!!
        assertEquals(目标, 恢复)
        assertEquals(RouteStation("同名站", null), 恢复.alight)
    }

    @Test fun 当前快照可原子消费提醒并从新偏好实例读回() = runBlocking {
        val 当前 = 完整跟踪目标().copy(reminder = AlightReminderState())
        val 已消费 = 当前.copy(reminder = AlightReminderState(beforeOrder = 5))
        偏好.setTrackedVehicle(当前)

        assertTrue(Prefs(应用).compareAndSetTrackedVehicle(当前, 已消费))
        assertEquals(已消费, Prefs(应用).trackedVehicle.first())
    }

    @Test fun 旧会话不能覆盖相同车辆的新会话() = runBlocking {
        val 旧会话 = 完整跟踪目标()
        val 新会话 = 旧会话.copy(sessionId = "新会话")
        偏好.setTrackedVehicle(新会话)

        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(
            旧会话, 旧会话.copy(reminder = AlightReminderState(arrivedOrder = 5, stopped = true))
        ))
        assertEquals(新会话, Prefs(应用).trackedVehicle.first())
    }

    @Test fun 旧会话不能清理相同车辆的新会话() = runBlocking {
        val 旧会话 = 完整跟踪目标()
        val 新会话 = 旧会话.copy(sessionId = "新会话")
        偏好.setTrackedVehicle(新会话)

        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(旧会话, null))
        assertEquals(新会话, Prefs(应用).trackedVehicle.first())
    }

    @Test fun 同会话旧版本不能覆盖或清理新版本() = runBlocking {
        val 旧版本 = 完整跟踪目标()
        val 新版本 = 旧版本.copy(revision = 旧版本.revision + 1)
        偏好.setTrackedVehicle(新版本)

        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(旧版本, 旧版本.copy(reminder = AlightReminderState())))
        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(旧版本, null))
        assertEquals(新版本, Prefs(应用).trackedVehicle.first())
    }

    @Test fun 同会话切换重名站后旧请求不能恢复原目标() = runBlocking {
        val 旧目标 = 完整跟踪目标()
        val 新目标 = 旧目标.copy(
            alightOrder = 2, revision = 旧目标.revision + 1, reminder = AlightReminderState()
        )
        偏好.setTrackedVehicle(新目标)

        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(旧目标, 旧目标))
        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(旧目标, null))
        val 恢复 = Prefs(应用).trackedVehicle.first()!!
        assertEquals(新目标, 恢复)
        assertEquals(2, 恢复.alight!!.order)
    }

    @Test fun 提醒已消费后旧快照不能再次写入或清除() = runBlocking {
        val 原目标 = 完整跟踪目标().copy(reminder = AlightReminderState())
        val 已消费 = 原目标.copy(reminder = AlightReminderState(beforeOrder = 5))
        偏好.setTrackedVehicle(原目标)
        assertTrue(偏好.compareAndSetTrackedVehicle(原目标, 已消费))

        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(原目标, 原目标))
        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(原目标, null))
        assertEquals(已消费, Prefs(应用).trackedVehicle.first())
    }

    @Test fun 空快照仅能创建空目标且当前快照能够清理() = runBlocking {
        val 目标 = 完整跟踪目标()

        assertTrue(偏好.compareAndSetTrackedVehicle(null, 目标))
        assertFalse(Prefs(应用).compareAndSetTrackedVehicle(null, 目标.copy(sessionId = "另一会话")))
        assertEquals(目标, Prefs(应用).trackedVehicle.first())
        assertTrue(Prefs(应用).compareAndSetTrackedVehicle(目标, null))
        assertNull(Prefs(应用).trackedVehicle.first())
    }

    @Test fun 未开启跟踪也能保存选站并切换到另一同名站序() = runBlocking {
        val 目标 = 完整跟踪目标()
        val 第一选择 = VehicleAlightSelection(
            目标.city.key, 目标.route.id, 目标.route.direction, 目标.plate,
            目标.route.stations.first { it.name == "同名站" }
        )
        偏好.setVehicleAlightSelection(第一选择)
        assertEquals(第一选择, Prefs(应用).vehicleAlightSelection.first())
        assertNull(Prefs(应用).trackedVehicle.first())

        val 第二选择 = 第一选择.copy(station = 目标.route.stations.last())
        Prefs(应用).setVehicleAlightSelection(第二选择)
        assertEquals(第二选择, Prefs(应用).vehicleAlightSelection.first())
        assertEquals(5, Prefs(应用).vehicleAlightSelection.first()!!.station.order)
        assertNull(Prefs(应用).trackedVehicle.first())
    }

    @Test fun 清理跟踪目标不删除单独保存的选站() = runBlocking {
        val 目标 = 完整跟踪目标()
        val 选择 = VehicleAlightSelection(
            目标.city.key, 目标.route.id, 目标.route.direction, 目标.plate, 目标.alight!!
        )
        偏好.setTrackedVehicle(目标)
        偏好.setVehicleAlightSelection(选择)

        assertTrue(Prefs(应用).compareAndSetTrackedVehicle(目标, null))
        assertNull(Prefs(应用).trackedVehicle.first())
        assertEquals(选择, Prefs(应用).vehicleAlightSelection.first())
    }

    @Test fun 选站可以清除且不影响已保存的跟踪目标() = runBlocking {
        val 目标 = 完整跟踪目标()
        偏好.setTrackedVehicle(目标)
        偏好.setVehicleAlightSelection(VehicleAlightSelection(
            目标.city.key, 目标.route.id, 目标.route.direction, 目标.plate, 目标.alight!!
        ))

        Prefs(应用).setVehicleAlightSelection(null)
        assertNull(Prefs(应用).vehicleAlightSelection.first())
        assertEquals(目标, Prefs(应用).trackedVehicle.first())
    }

    private fun 完整跟踪目标(): TrackedVehicle {
        val 上车站 = RouteStation("上车站", 1, "站台一", 39.90, 116.30)
        val 下车站 = RouteStation("同名站", 5, "站台五", 39.94, 116.34)
        val 线路 = Route(
            name = "测试环线", id = "测试线路", direction = "1",
            from = "上车站", to = "同名站", first = "06:00", last = "22:00",
            fareDescription = "两元",
            stations = listOf(上车站, RouteStation("同名站", 2, "站台二", 39.91, 116.31), 下车站),
            showTimetable = true, source = "测试数据", coordinateSystem = "gcj02"
        )
        return TrackedVehicle(
            city = City("0", "北京市", "010", "北京市", "beijing"),
            lineName = 线路.name,
            plate = "京测0001",
            boardingName = 上车站.name,
            route = 线路,
            boarding = 上车站,
            alightName = 下车站.name,
            alightOrder = 下车站.order,
            sessionId = "持久化测试会话",
            revision = 3,
            reminder = AlightReminderState(beforeOrder = 下车站.order)
        )
    }
}
