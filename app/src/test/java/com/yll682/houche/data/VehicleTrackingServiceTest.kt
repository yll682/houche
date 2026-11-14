package com.yll682.houche.data

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.HoucheApp
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HoucheApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class 车辆跟踪服务测试 {
    private val 应用: Application = ApplicationProvider.getApplicationContext()
    private var 控制器: ServiceController<VehicleTrackingService>? = null

    @Before fun 清空测试目标和内存状态() {
        runBlocking {
            Prefs(应用).setTrackedVehicle(null)
            Prefs(应用).setVehicleAlightSelection(null)
            VehicleTracker.stopIfTargetMissing(应用) {}
        }
        VehicleTracker.serviceStopped()
    }

    @After fun 销毁服务并清理测试状态() {
        销毁服务()
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking {
            Prefs(应用).setTrackedVehicle(null)
            Prefs(应用).setVehicleAlightSelection(null)
            VehicleTracker.stopIfTargetMissing(应用) {}
        }
        VehicleTracker.serviceStopped()
    }

    @Test fun 创建后立即进入前台且无需创建任何页面() {
        val 服务 = 创建服务()
        val 影子 = shadowOf(服务)

        assertTrue(应用 is HoucheApp)
        assertSame(应用, 服务.application)
        assertFalse(影子.isStoppedBySelf)
        assertTrue(VehicleTracker.backgroundActive.value)
        assertEquals(VehicleTrackingService.NOTIFICATION_ID, 影子.lastForegroundNotificationId)
        val 通知 = requireNotNull(影子.lastForegroundNotification) { "服务创建后应立即提供前台通知" }
        assertEquals(Notification.CATEGORY_SERVICE, 通知.category)
        assertTrue(通知.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val 通道 = requireNotNull(应用.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(VehicleTrackingService.CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_LOW, 通道.importance)
        assertNull(shadowOf(应用).nextStartedActivity)
    }

    @Test fun 真实应用缺失目标时启动后自行停止并清除内存旧目标() {
        val 旧目标 = 完整跟踪目标()
        runBlocking {
            Prefs(应用).setTrackedVehicle(旧目标)
            assertTrue(VehicleTracker.restored(应用, 旧目标))
            Prefs(应用).setTrackedVehicle(null)
        }
        val 服务 = 创建服务()
        val 返回值 = 服务.onStartCommand(
            Intent(应用, VehicleTrackingService::class.java).setAction(VehicleTrackingService.ACTION_START),
            0, 1
        )

        assertEquals(Service.START_STICKY, 返回值)
        等待自行停止(服务)
        assertNull(VehicleTracker.active.value)
        assertNull(runBlocking { Prefs(应用).trackedVehicle.first() })
        assertNull(shadowOf(应用).nextStartedActivity)
    }

    @Test fun 系统重建传入空启动意图时从空持久化目标自行停止() {
        val 服务 = 创建服务()

        assertEquals(Service.START_STICKY, 服务.onStartCommand(null, 0, 2))
        等待自行停止(服务)
        assertNull(VehicleTracker.active.value)
        assertNull(runBlocking { Prefs(应用).trackedVehicle.first() })
        assertNull(shadowOf(应用).nextStartedActivity)
    }

    @Test fun 服务不提供绑定接口且不启动页面() {
        val 服务 = 创建服务()

        assertNull(服务.onBind(Intent(应用, VehicleTrackingService::class.java)))
        assertNull(服务.onBind(null))
        assertNull(shadowOf(应用).nextStartedActivity)
        assertTrue(shadowOf(应用).boundServiceConnections.isEmpty())
    }

    @Test fun 空目标自行停止后销毁会撤下前台通知并清理运行标记() {
        val 服务 = 创建服务()
        服务.onStartCommand(null, 0, 3)
        等待自行停止(服务)

        销毁服务()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(服务).isForegroundStopped)
        assertTrue(shadowOf(服务).notificationShouldRemoved)
        assertFalse(VehicleTracker.backgroundActive.value)
        assertNull(runBlocking { Prefs(应用).trackedVehicle.first() })
        assertNull(
            shadowOf(应用.getSystemService(NotificationManager::class.java))
                .getNotification(VehicleTrackingService.NOTIFICATION_ID)
        )
    }

    @Test fun 服务销毁保留已保存目标与提醒标记供后续恢复() {
        val 目标 = 完整跟踪目标()
        runBlocking { Prefs(应用).setTrackedVehicle(目标) }
        // 只走创建和销毁，不进入非空目标的车辆查询轮询。
        val 服务 = 创建服务()

        销毁服务()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(VehicleTracker.backgroundActive.value)
        assertTrue(shadowOf(服务).isForegroundStopped)
        assertEquals(目标, runBlocking { Prefs(应用).trackedVehicle.first() })
        assertNull(shadowOf(应用).nextStartedActivity)
    }

    @Test
    @Config(sdk = [34], application = Application::class)
    fun 应用图缺失时安全停止且不删除已保存目标() {
        val 目标 = 完整跟踪目标()
        runBlocking { Prefs(应用).setTrackedVehicle(目标) }
        val 服务 = 创建服务()

        assertEquals(Service.START_STICKY, 服务.onStartCommand(null, 0, 4))
        等待自行停止(服务)

        assertEquals(目标, runBlocking { Prefs(应用).trackedVehicle.first() })
        assertNull(shadowOf(应用).nextStartedActivity)
    }

    private fun 创建服务(): VehicleTrackingService {
        val 新控制器 = Robolectric.buildService(VehicleTrackingService::class.java)
        控制器 = 新控制器
        return 新控制器.create().get()
    }

    private fun 销毁服务() {
        控制器?.destroy()
        控制器 = null
    }

    private fun 等待自行停止(服务: VehicleTrackingService) {
        val 截止时间 = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (shadowOf(服务).isStoppedBySelf) return
            // DataStore 使用真实 IO 线程；推进主循环并给首次读取完成的机会。
            Thread.sleep(10)
        } while (System.nanoTime() < 截止时间)
        fail("车辆跟踪服务未在目标缺失或应用图缺失后自行停止")
    }

    private fun 完整跟踪目标(): TrackedVehicle {
        val 上车站 = RouteStation("上车站", 1)
        val 下车站 = RouteStation("下车站", 5)
        val 线路 = Route(
            name = "测试线路", id = "服务测试线路", direction = "1",
            stations = listOf(上车站, 下车站)
        )
        return TrackedVehicle(
            city = City("0", "北京市", "010"),
            lineName = 线路.name,
            plate = "京测0002",
            boardingName = 上车站.name,
            route = 线路,
            boarding = 上车站,
            alightName = 下车站.name,
            alightOrder = 下车站.order,
            sessionId = "服务测试会话",
            revision = 2,
            reminder = AlightReminderState(beforeOrder = 下车站.order)
        )
    }
}
