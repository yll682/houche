package com.yll682.houche.data

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VehicleNotificationAvailabilityTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun enableNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test fun permissionAppAndChannelEnabledAllowReminders() {
        assertTrue(VehicleTracker.canSendReminder(app))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("houche_track").importance)
    }

    @Test fun deniedPermissionBlocksRemindersEvenWhenAppAndChannelEnabled() {
        assertTrue(VehicleTracker.canSendReminder(app))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(NotificationManagerCompat.from(app).areNotificationsEnabled())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("houche_track").importance)
        assertFalse(VehicleTracker.canSendReminder(app))
        assertFalse(VehicleTracker.notify(app, "title", "text"))
        assertNull(shadowOf(manager).getNotification(682))

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(VehicleTracker.canSendReminder(app))
    }

    @Test fun appSwitchBlocksRemindersAndIsReadAgainWhenEnabled() {
        assertTrue(VehicleTracker.canNotify(app))
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(VehicleTracker.canSendReminder(app))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("houche_track").importance)
        assertFalse(VehicleTracker.notify(app, "title", "text"))
        assertNull(shadowOf(manager).getNotification(682))

        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(VehicleTracker.canSendReminder(app))
    }

    @Test fun disabledReminderChannelBlocksEvenWithPermissionAndAppEnabled() {
        manager.createNotificationChannel(
            NotificationChannel("houche_track", "test", NotificationManager.IMPORTANCE_NONE)
        )

        assertTrue(VehicleTracker.canNotify(app))
        assertTrue(NotificationManagerCompat.from(app).areNotificationsEnabled())
        assertFalse(VehicleTracker.canSendReminder(app))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("houche_track").importance)
        assertFalse(VehicleTracker.notify(app, "title", "text"))
        assertNull(shadowOf(manager).getNotification(682))
    }
}
