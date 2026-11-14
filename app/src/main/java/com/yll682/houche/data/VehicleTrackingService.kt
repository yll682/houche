package com.yll682.houche.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.yll682.houche.HoucheApp
import com.yll682.houche.MainActivity
import com.yll682.houche.R
import com.yll682.houche.core.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 单车前台跟踪。下车站和去重状态以 DataStore 为准，不依赖页面或启动 Intent 的存活。 */
class VehicleTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var foregroundReady = false
    private var lastText: String? = null
    private var lastVehicle: Vehicle? = null
    private var lastSession: String? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.vehicle_tracking_service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // 必须先进入前台，再异步读 DataStore 或等待 AppGraph；目标为空也不会超过提升期限。
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID,
                notification(getString(R.string.vehicle_tracking_service_restoring)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            foregroundReady = true
            VehicleTracker.serviceStarted()
        } catch (error: RuntimeException) {
            Log.e("Houche", "无法启动车辆跟踪前台服务，保留目标", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            scope.launch {
                if (sessionId != null) VehicleTracker.stop(applicationContext, expectedSessionId = sessionId)
                else VehicleTracker.stopIfTargetMissing(applicationContext) { stopSelf() }
            }
            // 迟到的旧通知操作不能改变新行程的粘性恢复策略；
            // 若它刚好新建了服务，下面仍从持久化启动有效的新行程。
        }
        if (polling == null) {
            polling = scope.launch {
                try {
                    val graph = (application as? HoucheApp)?.graph
                    if (graph == null) {
                        stopSelf()
                        return@launch
                    }
                    // 提醒标记自身的写入不重启轮询；换目标/切站/清除会取消旧请求。
                    graph.prefs.trackedVehicle.distinctUntilChanged { old, new ->
                        if (old == null || new == null) old == new else old.sameConfiguration(new)
                    }.collectLatest { target ->
                        if (target == null) {
                            VehicleTracker.stopIfTargetMissing(applicationContext) { stopSelf() }
                            return@collectLatest
                        }
                        if (!VehicleTracker.restored(applicationContext, target)) return@collectLatest
                        if (target.reminder.stopped) {
                            VehicleTracker.stop(applicationContext, target)
                            return@collectLatest
                        }
                        if (lastSession != target.sessionId) {
                            lastSession = target.sessionId
                            lastVehicle = null
                            lastText = null
                        }
                        updateNotification(target, offline = false)
                        var failed = false
                        while (currentCoroutineContext().isActive) {
                            try {
                                val live = graph.repository().vehicles(target.city, target.route, target.boarding)
                                currentCoroutineContext().ensureActive()
                                val vehicle = live.vehicles.firstOrNull { it.plate == target.plate }
                                val knownPosition = vehicle?.order?.let { it > 0 } == true &&
                                    vehicle.positionState in setOf("at-stop", "between")
                                if (vehicle != null && knownPosition) {
                                    lastVehicle = vehicle
                                    if (VehicleTracker.consume(applicationContext, target, vehicle)) {
                                        // 清理持久化已在同一命令内完成；null 订阅负责停止，避免误停刚开启的新行程。
                                        return@collectLatest
                                    }
                                }
                                updateNotification(target, offline = !knownPosition)
                                failed = false
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                // 连续失败只记一次日志；保留上次位置且不拿旧位置触发提醒。
                                if (!failed) Log.w("Houche", "车辆后台查询失败，下一轮重试", error)
                                failed = true
                                graph.retryInitialization()
                                updateNotification(target, offline = true)
                            }
                            delay(POLL_INTERVAL_MILLIS)
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e("Houche", "车辆跟踪设置读取失败，保留目标", error)
                    stopSelf()
                }
            }
        }
        // 系统回收后的空 Intent 使用同一持久化入口恢复。
        return START_STICKY
    }

    private fun updateNotification(target: TrackedVehicle, offline: Boolean) {
        val vehicle = lastVehicle
        val station = target.route.stations.firstOrNull {
            vehicle?.order != null && it.order == vehicle.order
        }?.name ?: if (vehicle?.positionState == "at-stop") vehicle.currentStation else vehicle?.nextStation
        val position = when {
            station == null -> getString(R.string.vehicle_tracking_position_unknown)
            vehicle?.positionState == "at-stop" -> getString(R.string.vehicle_tracking_at_stop, station)
            else -> getString(R.string.vehicle_tracking_towards_stop, station)
        }
        // 无下车站时只显示到乘车点的站差，不自动创建下车提醒。
        val destination = target.alight ?: target.boarding
        val remaining = remainingStopsToTarget(destination, vehicle)
        val distance = when {
            remaining == null -> getString(R.string.vehicle_stops_unknown)
            remaining < 0 -> getString(R.string.vehicle_passed)
            else -> getString(R.string.vehicle_tracking_remaining, destination?.name, remaining)
        }
        val text = getString(
            R.string.vehicle_tracking_service_text, target.lineName, target.plate, position, distance
        ).let { if (offline) getString(R.string.vehicle_tracking_last_position, it) else it }
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, target))
    }

    private fun notification(text: String, target: TrackedVehicle? = null): Notification {
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bus)
            .setContentTitle(getString(R.string.vehicle_tracking_service_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (target != null) {
            // Intent extras 不参与 PendingIntent 身份；data 也绑定会话，旧通知不会被更新成新任务。
            val stop = PendingIntent.getService(
                this, NOTIFICATION_ID,
                Intent(this, VehicleTrackingService::class.java).setAction(ACTION_STOP)
                    .setData(Uri.parse("houche://vehicle-tracking/${target.sessionId}"))
                    .putExtra(EXTRA_SESSION_ID, target.sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(null, getString(R.string.vehicle_tracking_service_stop), stop).build()
            )
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 dataSync 有后台时长预算。及时停止，保留目标供用户下次回到前台恢复。
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        VehicleTracker.serviceStopped()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        VehicleTracker.serviceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 系统回收不是用户取消，不能在这里删除持久化。
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yll682.houche.action.START_VEHICLE_TRACKING"
        const val ACTION_STOP = "com.yll682.houche.action.STOP_VEHICLE_TRACKING"
        internal const val EXTRA_SESSION_ID = "vehicle_tracking_session_id"
        internal const val CHANNEL_ID = "houche_track_service"
        internal const val NOTIFICATION_ID = 683
        private const val POLL_INTERVAL_MILLIS = 20_000L
    }
}
