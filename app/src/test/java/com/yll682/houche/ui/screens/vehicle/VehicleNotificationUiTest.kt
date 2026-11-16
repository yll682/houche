package com.yll682.houche.ui.screens.vehicle

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.R
import com.yll682.houche.core.Route
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.LightColors
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp", application = Application::class)
class VehicleNotificationUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun enableNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test fun systemSwitchChangesColorOnEveryResumeAndBlockedRowStillOpensSettings() {
        var settings = 0
        show(notify = { settings++ })
        assertNotificationColor(LightColors.onSurface)

        resumeAfter { shadowOf(manager).setNotificationsEnabled(false) }
        assertNotificationColor(LightColors.error)
        compose.onNodeWithContentDescription(app.getString(R.string.vehicle_notify)).performClick()
        assertEquals(1, settings)

        resumeAfter { shadowOf(manager).setNotificationsEnabled(true) }
        assertNotificationColor(LightColors.onSurface)
    }

    @Test fun permissionChangesAreReadOnResume() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        assertNotificationColor(LightColors.error)

        resumeAfter { shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) }
        assertNotificationColor(LightColors.onSurface)

        resumeAfter { shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) }
        assertNotificationColor(LightColors.error)
    }

    @Test fun trackingWithoutAnyStationKeepsExplicitStopEnabled() {
        var stops = 0
        show(tracking = true, toggleTracking = { stops++ })

        compose.onNodeWithContentDescription(app.getString(R.string.vehicle_map_track_stop)).performClick()

        assertEquals(1, stops)
    }

    private fun show(tracking: Boolean = false, notify: () -> Unit = {}, toggleTracking: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme(colorScheme = LightColors) {
                    VehicleContent(
                        UiState.Success(VehicleData(Route("测试线路", "测试线路", "1"), "京测0006", null, tracking = tracking)),
                        back = {}, share = { _, _ -> }, toggleTracking = toggleTracking, notify = notify, refresh = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun resumeAfter(change: () -> Unit) {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle {
            change()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun assertNotificationColor(expected: androidx.compose.ui.graphics.Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(app.getString(R.string.vehicle_notify), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(expected, layouts.single().layoutInput.style.color)
    }
}
