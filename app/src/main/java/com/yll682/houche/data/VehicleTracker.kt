package com.yll682.houche.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yll682.houche.MainActivity
import com.yll682.houche.R
import com.yll682.houche.core.City
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackedVehicle(
    val city: City,
    val lineName: String,
    val plate: String,
    val boardingName: String?
)

/** 只保存进程内的跟踪选择，实时查询由页面的前台轮询驱动。 */
object VehicleTracker {
    private const val CHANNEL_ID = "houche_track"
    private const val NOTIFICATION_ID = 682
    private val mutableActive = MutableStateFlow<TrackedVehicle?>(null)
    val active: StateFlow<TrackedVehicle?> = mutableActive.asStateFlow()

    fun start(context: Context, city: City, lineName: String, plate: String, boardingName: String?) {
        createChannel(context.applicationContext)
        mutableActive.value = TrackedVehicle(city, lineName, plate, boardingName)
    }

    fun stop() {
        mutableActive.value = null
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

    fun notify(context: Context, title: String, text: String) {
        val appContext = context.applicationContext
        val manager = createChannel(appContext)
        // 权限由宿主申请；未授权时不调用系统通知接口。
        if (!canNotify(appContext)) return
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
