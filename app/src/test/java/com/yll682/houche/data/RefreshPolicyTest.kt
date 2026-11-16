package com.yll682.houche.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RefreshPolicyTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("houche_refresh", Context.MODE_PRIVATE)

    @Before fun initialize() {
        prefs.edit().clear().commit()
        RefreshPolicy.init(app)
    }

    @After fun reset() {
        prefs.edit().clear().commit()
        RefreshPolicy.init(app)
    }

    @Test fun optionsMatchTheCacheWindowAndManualMode() {
        assertEquals(listOf(0, 15, 20, 30, 60, 120), RefreshPolicy.options)
    }

    @Test fun manualOnlyPersistsAndHasNoFiniteDelay() {
        RefreshPolicy.set(0)
        assertEquals(0, prefs.getInt("seconds", -1))
        assertEquals(Long.MAX_VALUE, RefreshPolicy.millis())
        RefreshPolicy.init(app)
        assertEquals(0, RefreshPolicy.seconds)
        assertEquals(Long.MAX_VALUE, RefreshPolicy.millis())
    }

    @Test fun validRangeIncludesBothEndpoints() {
        for (seconds in listOf(0, 1, 15, 120)) {
            RefreshPolicy.set(seconds)
            RefreshPolicy.init(app)
            assertEquals(seconds, RefreshPolicy.seconds)
            assertEquals(if (seconds == 0) Long.MAX_VALUE else seconds * 1000L, RefreshPolicy.millis())
        }
    }

    @Test fun invalidUpdatesKeepTheSelectedInterval() {
        RefreshPolicy.set(30)
        for (seconds in listOf(-1, 121, Int.MIN_VALUE, Int.MAX_VALUE)) {
            RefreshPolicy.set(seconds)
            assertEquals(30, RefreshPolicy.seconds)
            assertEquals(30, prefs.getInt("seconds", -1))
        }
    }

    @Test fun invalidStoredValuesUseTheDefault() {
        for (seconds in listOf(-1, 121, Int.MIN_VALUE, Int.MAX_VALUE)) {
            prefs.edit().putInt("seconds", seconds).commit()
            RefreshPolicy.init(app)
            assertEquals(RefreshPolicy.DEFAULT_SECONDS, RefreshPolicy.seconds)
            assertEquals(15_000L, RefreshPolicy.millis())
        }
    }
}
