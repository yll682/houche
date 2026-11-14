package com.yll682.houche.data

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.yll682.houche.MainActivity
import com.yll682.houche.HoucheApp
import com.yll682.houche.R
import com.yll682.houche.core.City
import com.yll682.houche.core.Route
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.core.resolveXiamenLoopStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TrackedVehicle(
    val city: City,
    val lineName: String,
    val plate: String,
    val boardingName: String?,
    val route: Route,
    val boarding: RouteStation? = null,
    val alightName: String? = null,
    val alightOrder: Int? = null,
    val sessionId: String = UUID.randomUUID().toString(),
    val revision: Int = 0,
    val reminder: AlightReminderState = AlightReminderState()
) {
    /** 重名站只按站序恢复；站表不完整时保留用户原来的名字和站序，不取第一个同名站。 */
    val alight: RouteStation?
        get() = alightName?.let { name ->
            route.stations.firstOrNull { alightOrder != null && it.order == alightOrder }
                ?: RouteStation(name, alightOrder)
        }

    fun matches(city: City, route: Route, plate: String, boarding: RouteStation?): Boolean =
        this.city.key == city.key && this.route.id == route.id &&
            this.route.name == route.name && this.route.direction == route.direction &&
            this.plate == plate && this.boarding?.order == boarding?.order &&
            boardingName == boarding?.name

    internal fun sameConfiguration(other: TrackedVehicle): Boolean =
        copy(reminder = other.reminder) == other
}

/** 未开启跟踪也保存选站；它不代表后台服务正在运行。 */
@Serializable
data class VehicleAlightSelection(
    val cityKey: String,
    val routeId: String,
    val direction: String,
    val plate: String,
    val station: RouteStation
)

@Serializable
data class AlightReminderState(
    val beforeOrder: Int? = null,
    val arrivedOrder: Int? = null,
    val stopped: Boolean = false
)

internal enum class AlightReminderEvent { NONE, BEFORE, ARRIVED, PASSED }

internal data class AlightReminderDecision(
    val event: AlightReminderEvent,
    val state: AlightReminderState
)

/**
 * 两次提醒分别去重；已经结束的行程不会因位置回跳而重新提醒。
 * 零站差且停靠才 ARRIVED；零站差但已驶离则 PASSED 并停止，不补发到站提醒。
 * 驶离须由当前站=目标站且 between 的前方绘图锚点=目标+1 证明；
 * 回绕至 1 还须同方向站表和下一站名证实首末同名，不能只凭两个序号猜闭环。
 * 未校准路径只有前方锚点、或 unknown 没有绘图锚点时，不能声称已经驶离。
 */
internal fun decideAlightReminder(
    remaining: Int?,
    targetOrder: Int?,
    previous: AlightReminderState = AlightReminderState(),
    atStop: Boolean = true,
    vehicle: Vehicle? = null,
    stations: List<RouteStation> = emptyList()
): AlightReminderDecision {
    if (previous.stopped || remaining == null || targetOrder == null || targetOrder <= 0) {
        return AlightReminderDecision(AlightReminderEvent.NONE, previous)
    }
    val departedTarget = vehicle?.positionState == "between" && vehicle.currentStationOrder == targetOrder &&
        (vehicle.order?.toLong() == targetOrder.toLong() + 1 ||
            (vehicle.order == 1 && resolveXiamenLoopStart(targetOrder, vehicle.nextStation, stations) != null))
    val stoppedAtTarget = vehicle?.let { it.positionState == "at-stop" } ?: atStop
    return when {
        remaining < 0 || (remaining == 0 && departedTarget) ->
            AlightReminderDecision(AlightReminderEvent.PASSED, previous.copy(stopped = true))
        remaining == 0 && stoppedAtTarget && previous.arrivedOrder != targetOrder ->
            AlightReminderDecision(
                AlightReminderEvent.ARRIVED,
                previous.copy(arrivedOrder = targetOrder, stopped = true)
            )
        remaining == 1 && previous.beforeOrder != targetOrder ->
            AlightReminderDecision(AlightReminderEvent.BEFORE, previous.copy(beforeOrder = targetOrder))
        else -> AlightReminderDecision(AlightReminderEvent.NONE, previous)
    }
}

/** 当前站锚点与目标的有符号站差；位置状态只用于是否停靠/驶离，不决定能否计数。 */
internal fun remainingStopsToTarget(target: RouteStation?, vehicle: Vehicle?): Int? {
    val destination = target?.order?.takeIf { it > 0 } ?: return null
    val current = vehicle?.currentStationOrder ?: return null
    return destination - current
}

/** 应用级串行命令入口；页面退出不会取消已经开始的保存或停止操作。只有服务发送下车提醒。 */
object VehicleTracker {
    private const val CHANNEL_ID = "houche_track"
    private const val NOTIFICATION_ID = 682
    private val mutableActive = MutableStateFlow<TrackedVehicle?>(null)
    val active: StateFlow<TrackedVehicle?> = mutableActive.asStateFlow()
    private val mutableBackgroundActive = MutableStateFlow(false)
    val backgroundActive: StateFlow<Boolean> = mutableBackgroundActive.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private var initializedApp: Application? = null

    /** Prefs 随 AppGraph 创建；只在 Activity 可见后恢复，避免从后台冷启动 FGS。 */
    internal fun initialize(context: Context) {
        val app = context.applicationContext as? HoucheApp ?: return
        if (initializedApp === app) return
        initializedApp = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { restore(app) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun restore(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                commands.withLock {
                    val target = Prefs(appContext).trackedVehicle.first()
                    mutableActive.value = target
                    if (target != null && !mutableBackgroundActive.value) startService(appContext)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("Houche", "恢复车辆跟踪失败，保留目标等待下次进入应用", error)
            }
        }
    }

    suspend fun start(context: Context, target: TrackedVehicle) = scope.async {
        commands.withLock {
            val appContext = context.applicationContext
            val prefs = Prefs(appContext)
            val previous = prefs.trackedVehicle.first()
            prefs.setTrackedVehicle(target)
            mutableActive.value = target
            try {
                startService(appContext)
            } catch (error: Exception) {
                prefs.compareAndSetTrackedVehicle(target, previous)
                mutableActive.value = previous
                throw error
            }
        }
    }.await()

    suspend fun stop(
        context: Context,
        expected: TrackedVehicle? = null,
        expectedSessionId: String? = null
    ) = scope.async {
        commands.withLock {
            val prefs = Prefs(context)
            val current = prefs.trackedVehicle.first()
            if (expected != null && (current == null || !current.sameConfiguration(expected))) return@withLock
            if (expectedSessionId != null && current?.sessionId != expectedSessionId) return@withLock
            prefs.setTrackedVehicle(null)
            mutableActive.value = null
            context.applicationContext.stopService(Intent(context.applicationContext, VehicleTrackingService::class.java))
        }
    }.await()

    suspend fun selectAlight(context: Context, target: TrackedVehicle, station: RouteStation?) = scope.async {
        commands.withLock {
            val prefs = Prefs(context)
            prefs.setVehicleAlightSelection(station?.let {
                VehicleAlightSelection(target.city.key, target.route.id, target.route.direction, target.plate, it)
            })
            val current = prefs.trackedVehicle.first() ?: return@withLock
            if (!current.matches(target.city, target.route, target.plate, target.boarding)) return@withLock
            if (station == null) {
                // 清除已设的下车站就是结束本次跟踪；从未设站的普通跟踪仍可独立启动。
                prefs.setTrackedVehicle(null)
                mutableActive.value = null
                context.applicationContext.stopService(Intent(context.applicationContext, VehicleTrackingService::class.java))
            } else if (current.alightOrder != station.order || current.alightName != station.name) {
                val updated = current.copy(
                    alightName = station.name,
                    alightOrder = station.order,
                    revision = current.revision + 1,
                    reminder = AlightReminderState()
                )
                prefs.setTrackedVehicle(updated)
                mutableActive.value = updated
                // 服务订阅同一个 DataStore；变更会取消旧轮询并立即查询新目标。
            }
        }
    }.await()

    private fun startService(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, VehicleTrackingService::class.java).setAction(VehicleTrackingService.ACTION_START)
        )
    }

    internal fun serviceStarted() { mutableBackgroundActive.value = true }
    internal fun serviceStopped() { mutableBackgroundActive.value = false }
    internal suspend fun restored(context: Context, target: TrackedVehicle): Boolean = commands.withLock {
        val current = Prefs(context).trackedVehicle.first()
        if (current == null || !current.sameConfiguration(target)) return@withLock false
        mutableActive.value = current
        true
    }

    internal suspend fun stopIfTargetMissing(context: Context, stop: () -> Unit) = commands.withLock {
        // DataStore 的旧 null 发射不能停止刚开启的新目标。
        if (Prefs(context).trackedVehicle.first() == null) {
            mutableActive.value = null
            stop()
        }
    }

    /** 先持久化消费标记再通知，保证进程重启不重发；旧请求不能消费新选站的提醒。 */
    internal suspend fun consume(context: Context, expected: TrackedVehicle, vehicle: Vehicle): Boolean =
        commands.withLock {
            val prefs = Prefs(context)
            val current = prefs.trackedVehicle.first() ?: return@withLock false
            if (!current.sameConfiguration(expected)) return@withLock false
            if (current.reminder.stopped) {
                prefs.compareAndSetTrackedVehicle(current, null)
                mutableActive.value = null
                return@withLock true
            }
            val decision = decideAlightReminder(
                remainingStopsToTarget(current.alight, vehicle),
                current.alightOrder,
                current.reminder,
                atStop = vehicle.positionState == "at-stop",
                vehicle = vehicle,
                stations = current.route.stations
            )
            if (decision.event == AlightReminderEvent.NONE) return@withLock false
            if (decision.event != AlightReminderEvent.PASSED && !canSendReminder(context)) return@withLock false
            val consumed = current.copy(reminder = decision.state)
            if (!prefs.compareAndSetTrackedVehicle(current, consumed)) return@withLock false
            mutableActive.value = consumed
            val delivered = when (decision.event) {
                AlightReminderEvent.BEFORE -> notify(
                    context, context.getString(R.string.vehicle_alight_notification_title),
                    context.getString(R.string.vehicle_alight_one_stop, current.alightName)
                )
                AlightReminderEvent.ARRIVED -> notify(
                    context, context.getString(R.string.vehicle_alight_notification_title),
                    context.getString(R.string.vehicle_alight_arrived, current.alightName)
                )
                else -> true
            }
            if (!delivered) {
                // 授权可能恰在检查后被撤回；未提交的提醒不算已发，下一次有效位置再判断。
                prefs.compareAndSetTrackedVehicle(consumed, current)
                mutableActive.value = current
                return@withLock false
            }
            if (decision.state.stopped) {
                prefs.compareAndSetTrackedVehicle(consumed, null)
                mutableActive.value = null
            }
            decision.state.stopped
        }

    /** Android 13+ 的通知需要运行时授权，未授权时系统会静默丢弃，界面据此决定是否先申请。 */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context.applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel(context: Context): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.vehicle_notification_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        return manager
    }

    private fun canSendReminder(context: Context): Boolean {
        val manager = createChannel(context.applicationContext)
        return canNotify(context) && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun notify(context: Context, title: String, text: String): Boolean {
        val appContext = context.applicationContext
        val manager = createChannel(appContext)
        // 权限由宿主申请；未授权时不调用系统通知接口。
        if (!canSendReminder(appContext)) return false
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bus)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
