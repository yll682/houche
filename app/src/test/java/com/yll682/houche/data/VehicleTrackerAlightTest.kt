package com.yll682.houche.data

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VehicleTrackerAlightTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = Prefs(app)
    private val stop = RouteStation("下车站", 5)
    private val target = TrackedVehicle(
        city = City("0", "北京市", "010"),
        lineName = "测试线路",
        plate = "京测0004",
        boardingName = "上车站",
        route = Route("测试线路", "清除选站测试", "1", stations = listOf(RouteStation("上车站", 1), stop)),
        boarding = RouteStation("上车站", 1),
        alightName = stop.name,
        alightOrder = stop.order,
        sessionId = "清除选站会话",
        revision = 3,
        reminder = AlightReminderState(beforeOrder = 5, arrivedOrder = 5, stopped = true)
    )

    @Before fun reset() = cleanup()
    @After fun cleanup() = runBlocking {
        prefs.setTrackedVehicle(null)
        prefs.setVehicleAlightSelection(null)
        VehicleTracker.stopIfTargetMissing(app) {}
        VehicleTracker.serviceStopped()
    }

    @Test fun clearingAlightPreservesSessionBoardingAndForegroundService() {
        save(target)
        command { VehicleTracker.selectAlight(app, target, null) }
        val expected = cleared(target)

        assertEquals(expected, runBlocking { Prefs(app).trackedVehicle.first() })
        assertEquals(expected, VehicleTracker.active.value)
        assertNull(runBlocking { Prefs(app).vehicleAlightSelection.first() })
        assertTrue(VehicleTracker.backgroundActive.value)
        assertNull(shadowOf(app).nextStoppedService)
        assertNull(shadowOf(app).nextStartedService)

        runBlocking {
            assertFalse(prefs.compareAndSetTrackedVehicle(
                expected.copy(revision = target.revision),
                target.copy(reminder = AlightReminderState(beforeOrder = 5))
            ))
            assertFalse(prefs.compareAndSetTrackedVehicle(target, target.copy(reminder = AlightReminderState())))
            assertFalse(VehicleTracker.restored(app, target))
            for (order in listOf(4, 5, 6)) {
                val vehicle = Vehicle(target.plate, order, "at-stop")
                assertFalse(VehicleTracker.consume(app, target, vehicle))
                assertFalse(VehicleTracker.consume(app, expected, vehicle))
                assertEquals(expected, prefs.trackedVehicle.first())
                assertEquals(expected, VehicleTracker.active.value)
            }
        }
        assertTrue(VehicleTracker.backgroundActive.value)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test fun clearingAlightKeepsRealForegroundServiceAndNotification() {
        save(target)
        val controller = Robolectric.buildService(VehicleTrackingService::class.java).create()
        try {
            val service = shadowOf(controller.get())
            val notification = requireNotNull(service.lastForegroundNotification)
            assertTrue(VehicleTracker.backgroundActive.value)

            command { VehicleTracker.selectAlight(app, target, null) }

            assertEquals(cleared(target), runBlocking { prefs.trackedVehicle.first() })
            assertEquals(cleared(target), VehicleTracker.active.value)
            assertTrue(VehicleTracker.backgroundActive.value)
            assertFalse(service.isStoppedBySelf)
            assertFalse(service.isForegroundStopped)
            assertEquals(VehicleTrackingService.NOTIFICATION_ID, service.lastForegroundNotificationId)
            assertSame(notification, service.lastForegroundNotification)
            assertNull(shadowOf(app).nextStoppedService)
        } finally {
            controller.destroy()
        }
    }

    @Test fun clearingOnlyAlightTargetKeepsTrackingWithoutBoardingStation() {
        val onlyAlight = target.copy(boarding = null, boardingName = null)
        save(onlyAlight)

        command { VehicleTracker.selectAlight(app, onlyAlight, null) }

        assertEquals(cleared(onlyAlight), runBlocking { prefs.trackedVehicle.first() })
        assertEquals(cleared(onlyAlight), VehicleTracker.active.value)
        assertNull(runBlocking { prefs.vehicleAlightSelection.first() })
        assertTrue(VehicleTracker.backgroundActive.value)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test fun clearingSavedSelectionWithoutTrackingDoesNotStartService() {
        runBlocking { prefs.setVehicleAlightSelection(selection(target)) }

        command { VehicleTracker.selectAlight(app, target, null) }

        assertNull(runBlocking { prefs.vehicleAlightSelection.first() })
        assertNull(runBlocking { prefs.trackedVehicle.first() })
        assertNull(VehicleTracker.active.value)
        assertFalse(VehicleTracker.backgroundActive.value)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test fun clearingAnotherVehicleSelectionPreservesActiveTargetAndReminder() {
        val other = target.copy(plate = "京测0005")
        save(target)
        runBlocking { prefs.setVehicleAlightSelection(selection(other)) }

        command { VehicleTracker.selectAlight(app, other, null) }

        assertNull(runBlocking { prefs.vehicleAlightSelection.first() })
        assertEquals(target, runBlocking { prefs.trackedVehicle.first() })
        assertEquals(target, VehicleTracker.active.value)
        assertTrue(VehicleTracker.backgroundActive.value)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test fun clearingActiveAlightDoesNotDeleteAnotherVehicleSelection() {
        val otherSelection = selection(target.copy(plate = "京测0005"))
        save(target)
        runBlocking { prefs.setVehicleAlightSelection(otherSelection) }

        command { VehicleTracker.selectAlight(app, target, null) }

        assertEquals(otherSelection, runBlocking { prefs.vehicleAlightSelection.first() })
        assertEquals(cleared(target), runBlocking { prefs.trackedVehicle.first() })
        assertEquals(cleared(target), VehicleTracker.active.value)
        assertTrue(VehicleTracker.backgroundActive.value)
        assertNull(shadowOf(app).nextStoppedService)
    }

    private fun save(value: TrackedVehicle) = runBlocking {
        prefs.setTrackedVehicle(value)
        prefs.setVehicleAlightSelection(selection(value))
        assertTrue(VehicleTracker.restored(app, value))
        VehicleTracker.serviceStarted()
    }

    private fun selection(value: TrackedVehicle) = VehicleAlightSelection(
        value.city.key, value.route.id, value.route.direction, value.plate, stop
    )

    private fun cleared(value: TrackedVehicle) = value.copy(
        alightName = null, alightOrder = null, revision = value.revision + 1, reminder = AlightReminderState()
    )

    private fun command(block: suspend () -> Unit) {
        val result = CompletableFuture.supplyAsync { runBlocking { block() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!result.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!result.isDone) Thread.sleep(10)
        }
        assertTrue("等待清除下车站超时", result.isDone)
        result.get(1, TimeUnit.SECONDS)
    }
}
