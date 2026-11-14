package com.yll682.houche.data

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.R
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class 车辆提醒消费测试 {
    private val 应用: Application = ApplicationProvider.getApplicationContext()
    private val 通知管理器 get() = 应用.getSystemService(NotificationManager::class.java)
    private val 通知影子 get() = shadowOf(通知管理器)

    @Before fun 清空目标并授予通知权限() = runBlocking {
        Prefs(应用).setTrackedVehicle(null)
        VehicleTracker.stopIfTargetMissing(应用) {}
        shadowOf(应用).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        通知影子.setNotificationsEnabled(true)
        通知管理器.cancelAll()
    }

    @After fun 清理目标和通知() = runBlocking {
        Prefs(应用).setTrackedVehicle(null)
        VehicleTracker.stopIfTargetMissing(应用) {}
        通知管理器.cancelAll()
        shadowOf(应用).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun 授权后剩余一站持久化提前标记且重复消费不再通知() = runBlocking {
        val 目标 = 完整跟踪目标()
        保存并恢复(目标)
        assertTrue(VehicleTracker.canNotify(应用))

        // consume 的返回值表示是否终止跟踪；提前提醒应继续跟踪。
        assertFalse(VehicleTracker.consume(应用, 目标, 车辆(4)))
        val 已消费 = 目标.copy(reminder = AlightReminderState(beforeOrder = 5))
        assertEquals(已消费, Prefs(应用).trackedVehicle.first())
        assertEquals(已消费, VehicleTracker.active.value)
        断言提醒文字(应用.getString(R.string.vehicle_alight_one_stop, 目标.alightName))

        // 先移除现有通知，避免同编号覆盖掩盖了重复发送。
        通知管理器.cancel(682)
        assertFalse(VehicleTracker.consume(应用, 目标, 车辆(4)))
        assertNull(通知影子.getNotification(682))
        assertEquals(0, 通知影子.size())
        assertEquals(已消费, Prefs(应用).trackedVehicle.first())
        assertEquals(已消费, VehicleTracker.active.value)
    }

    @Test fun 停靠目标站发送到站通知并清理持久化及内存目标() = runBlocking {
        val 目标 = 完整跟踪目标().copy(reminder = AlightReminderState(beforeOrder = 5))
        保存并恢复(目标)

        assertTrue(VehicleTracker.consume(应用, 目标, 车辆(5)))
        断言提醒文字(应用.getString(R.string.vehicle_alight_arrived, 目标.alightName))
        assertNull(Prefs(应用).trackedVehicle.first())
        assertNull(VehicleTracker.active.value)

        通知管理器.cancel(682)
        assertFalse(VehicleTracker.consume(应用, 目标, 车辆(5)))
        assertNull(通知影子.getNotification(682))
        assertNull(Prefs(应用).trackedVehicle.first())
    }

    @Test fun 途中站差为零不发送到站通知也不清理目标或提前标记() = runBlocking {
        val 目标 = 完整跟踪目标().copy(reminder = AlightReminderState(beforeOrder = 5))
        保存并恢复(目标)

        assertFalse(VehicleTracker.consume(应用, 目标, 车辆(5, "between")))
        assertNull(通知影子.getNotification(682))
        assertEquals(目标, Prefs(应用).trackedVehicle.first())
        assertEquals(目标, VehicleTracker.active.value)

        assertTrue(VehicleTracker.consume(应用, 目标, 车辆(5, "at-stop")))
        断言提醒文字(应用.getString(R.string.vehicle_alight_arrived, 目标.alightName))
        assertNull(Prefs(应用).trackedVehicle.first())
    }

    @Test fun 未设置下车站时经过上车站和后续站都不提醒或清理() = runBlocking {
        val 目标 = 完整跟踪目标().copy(alightName = null, alightOrder = null)
        保存并恢复(目标)

        for (站序 in listOf(1, 4, 5, 6)) {
            assertFalse(VehicleTracker.consume(应用, 目标, 车辆(站序)))
            assertEquals(目标, Prefs(应用).trackedVehicle.first())
            assertEquals(目标, VehicleTracker.active.value)
            assertNull(通知影子.getNotification(682))
        }
    }

    @Test fun 拒绝通知权限不消费提前标记且授权后同一位置可以重试() = runBlocking {
        val 目标 = 完整跟踪目标()
        保存并恢复(目标)
        shadowOf(应用).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(VehicleTracker.canNotify(应用))

        assertFalse(VehicleTracker.consume(应用, 目标, 车辆(4)))
        assertNull(通知影子.getNotification(682))
        assertEquals(目标, Prefs(应用).trackedVehicle.first())
        assertEquals(AlightReminderState(), Prefs(应用).trackedVehicle.first()!!.reminder)
        assertEquals(目标, VehicleTracker.active.value)

        shadowOf(应用).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(VehicleTracker.consume(应用, 目标, 车辆(4)))
        assertEquals(AlightReminderState(beforeOrder = 5), Prefs(应用).trackedVehicle.first()!!.reminder)
        断言提醒文字(应用.getString(R.string.vehicle_alight_one_stop, 目标.alightName))
    }

    @Test fun 拒绝通知权限不消费到站标记也不删除目标() = runBlocking {
        val 目标 = 完整跟踪目标().copy(reminder = AlightReminderState(beforeOrder = 5))
        保存并恢复(目标)
        shadowOf(应用).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(VehicleTracker.canNotify(应用))

        assertFalse(VehicleTracker.consume(应用, 目标, 车辆(5)))
        assertNull(通知影子.getNotification(682))
        assertEquals(目标, Prefs(应用).trackedVehicle.first())
        assertNull(Prefs(应用).trackedVehicle.first()!!.reminder.arrivedOrder)
        assertEquals(目标, VehicleTracker.active.value)

        shadowOf(应用).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(VehicleTracker.consume(应用, 目标, 车辆(5)))
        断言提醒文字(应用.getString(R.string.vehicle_alight_arrived, 目标.alightName))
        assertNull(Prefs(应用).trackedVehicle.first())
        assertNull(VehicleTracker.active.value)
    }

    @Test fun 旧会话的提前到站和过站结果都不能修改新会话() = runBlocking {
        val 旧目标 = 完整跟踪目标()
        val 新目标 = 旧目标.copy(sessionId = "新的消费测试会话")
        保存并恢复(新目标)

        for (站序 in listOf(4, 5, 6)) {
            assertFalse(VehicleTracker.consume(应用, 旧目标, 车辆(站序)))
            assertEquals(新目标, Prefs(应用).trackedVehicle.first())
            assertEquals(新目标, VehicleTracker.active.value)
            assertNull(通知影子.getNotification(682))
        }
    }

    @Test fun 同会话旧版本的提前到站和过站结果都不能修改新版本() = runBlocking {
        val 旧目标 = 完整跟踪目标()
        val 新目标 = 旧目标.copy(revision = 旧目标.revision + 1)
        保存并恢复(新目标)

        for (站序 in listOf(4, 5, 6)) {
            assertFalse(VehicleTracker.consume(应用, 旧目标, 车辆(站序)))
            assertEquals(新目标, Prefs(应用).trackedVehicle.first())
            assertEquals(新目标, VehicleTracker.active.value)
            assertNull(通知影子.getNotification(682))
        }
    }

    @Test fun 未知位置和缺失站序都不消费已有标记() = runBlocking {
        val 目标 = 完整跟踪目标().copy(reminder = AlightReminderState(beforeOrder = 5))
        保存并恢复(目标)

        for (车辆 in listOf(车辆(5, "unknown"), 车辆(null), 车辆(0), 车辆(-1))) {
            assertFalse(VehicleTracker.consume(应用, 目标, 车辆))
            assertEquals(目标, Prefs(应用).trackedVehicle.first())
            assertEquals(目标, VehicleTracker.active.value)
            assertNull(通知影子.getNotification(682))
        }
    }

    @Test fun 已过目标站即使拒绝通知权限也终止跟踪且不补发通知() = runBlocking {
        val 目标 = 完整跟踪目标().copy(reminder = AlightReminderState(beforeOrder = 5))
        保存并恢复(目标)
        shadowOf(应用).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(VehicleTracker.consume(应用, 目标, 车辆(6)))
        assertNull(Prefs(应用).trackedVehicle.first())
        assertNull(VehicleTracker.active.value)
        assertNull(通知影子.getNotification(682))
    }

    @Test fun 当前站等于目标且已驶离时立即过站清理且恢复不补发到站通知() = runBlocking {
        val 目标 = 完整跟踪目标().copy(reminder = AlightReminderState(beforeOrder = 5))
        保存并恢复(目标)
        shadowOf(应用).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val 已驶离 = Vehicle("京测0003", 6, "between", reportedOrder = 5)
        assertEquals(0, remainingStopsToTarget(目标.alight, 已驶离))

        assertTrue(VehicleTracker.consume(应用, 目标, 已驶离))
        assertNull(Prefs(应用).trackedVehicle.first())
        assertNull(VehicleTracker.active.value)
        assertNull(通知影子.getNotification(682))
        assertFalse(VehicleTracker.consume(应用, 目标, 已驶离))
        assertNull(通知影子.getNotification(682))
    }

    @Test fun 绘图锚点已到目标但当前站仍差一站时只发提前提醒() = runBlocking {
        val 目标 = 完整跟踪目标()
        保存并恢复(目标)
        val 行驶中 = Vehicle("京测0003", 5, "between", reportedOrder = 4)
        assertEquals(1, remainingStopsToTarget(目标.alight, 行驶中))

        assertFalse(VehicleTracker.consume(应用, 目标, 行驶中))
        断言提醒文字(应用.getString(R.string.vehicle_alight_one_stop, 目标.alightName))
        assertEquals(AlightReminderState(beforeOrder = 5), Prefs(应用).trackedVehicle.first()!!.reminder)

        通知管理器.cancel(682)
        val 恢复 = requireNotNull(Prefs(应用).trackedVehicle.first())
        assertTrue(VehicleTracker.restored(应用, 恢复))
        assertFalse(VehicleTracker.consume(应用, 恢复, 行驶中))
        assertNull(通知影子.getNotification(682))
        assertTrue(VehicleTracker.consume(应用, 恢复, 行驶中.copy(order = 5, reportedOrder = 5, positionState = "at-stop")))
        断言提醒文字(应用.getString(R.string.vehicle_alight_arrived, 目标.alightName))
    }

    @Test fun 位置未知但有效原始站序尚差一站时仍可发提前提醒() = runBlocking {
        val 目标 = 完整跟踪目标()
        保存并恢复(目标)
        val 未知绘图 = Vehicle("京测0003", null, "unknown", reportedOrder = 4)
        assertEquals(1, remainingStopsToTarget(目标.alight, 未知绘图))
        assertFalse(VehicleTracker.consume(应用, 目标, 未知绘图))
        断言提醒文字(应用.getString(R.string.vehicle_alight_one_stop, 目标.alightName))
        assertEquals(AlightReminderState(beforeOrder = 5), Prefs(应用).trackedVehicle.first()!!.reminder)
    }

    @Test fun 旧通知会话请求停止时保留新会话目标和运行服务() {
        val 旧目标 = 完整跟踪目标()
        val 新目标 = 旧目标.copy(
            sessionId = "新启动的跟踪会话",
            reminder = AlightReminderState(beforeOrder = 5)
        )
        runBlocking { 保存并恢复(新目标) }
        assertNull(shadowOf(应用).nextStoppedService)

        按会话停止并等待(旧目标.sessionId)

        assertEquals(新目标, runBlocking { Prefs(应用).trackedVehicle.first() })
        assertEquals(新目标, VehicleTracker.active.value)
        assertNull(shadowOf(应用).nextStoppedService)
    }

    @Test fun 匹配通知会话请求停止时清空目标并停止跟踪服务() {
        val 目标 = 完整跟踪目标().copy(reminder = AlightReminderState(beforeOrder = 5))
        runBlocking { 保存并恢复(目标) }
        assertNull(shadowOf(应用).nextStoppedService)

        按会话停止并等待(目标.sessionId)

        assertNull(runBlocking { Prefs(应用).trackedVehicle.first() })
        assertNull(VehicleTracker.active.value)
        val 停止意图 = requireNotNull(shadowOf(应用).nextStoppedService) { "应请求停止车辆跟踪服务" }
        assertEquals(VehicleTrackingService::class.java.name, 停止意图.component?.className)
    }

    private fun 按会话停止并等待(会话编号: String) {
        // stop 在应用的 Main scope 中执行；后台等待结果，主测试线程持续推进主循环。
        val 完成结果 = CompletableFuture.supplyAsync {
            runBlocking { VehicleTracker.stop(应用, expectedSessionId = 会话编号) }
        }
        val 截止时间 = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!完成结果.isDone && System.nanoTime() < 截止时间) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!完成结果.isDone) Thread.sleep(10)
        }
        assertTrue("等待按会话停止车辆跟踪超时", 完成结果.isDone)
        完成结果.get(1, TimeUnit.SECONDS)
    }

    private suspend fun 保存并恢复(目标: TrackedVehicle) {
        Prefs(应用).setTrackedVehicle(目标)
        assertTrue(VehicleTracker.restored(应用, 目标))
    }

    private fun 断言提醒文字(预期正文: String) {
        val 通知 = requireNotNull(通知影子.getNotification(682)) { "应发布编号 682 的下车提醒" }
        assertEquals(1, 通知影子.size())
        assertEquals("houche_track", 通知.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, 通知.category)
        assertEquals(
            应用.getString(R.string.vehicle_alight_notification_title),
            通知.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
        assertEquals(预期正文, 通知.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    private fun 车辆(站序: Int?, 位置: String = "at-stop") =
        Vehicle(plate = "京测0003", order = 站序, positionState = 位置)

    private fun 完整跟踪目标(): TrackedVehicle {
        val 上车站 = RouteStation("上车站", 1)
        val 下车站 = RouteStation("下车站", 5)
        val 线路 = Route(
            name = "消费测试线路", id = "消费测试线路", direction = "1",
            stations = listOf(上车站, 下车站)
        )
        return TrackedVehicle(
            city = City("0", "北京市", "010"),
            lineName = 线路.name,
            plate = "京测0003",
            boardingName = 上车站.name,
            route = 线路,
            boarding = 上车站,
            alightName = 下车站.name,
            alightOrder = 下车站.order,
            sessionId = "提醒消费测试会话",
            revision = 3,
            reminder = AlightReminderState()
        )
    }
}
