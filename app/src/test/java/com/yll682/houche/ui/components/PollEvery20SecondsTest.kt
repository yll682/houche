package com.yll682.houche.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.yll682.houche.data.RefreshPolicy
import com.yll682.houche.ui.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PollEvery20SecondsTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val published = MutableStateFlow<UiState<Int>>(UiState.Success(1))
    private val available = mutableStateOf(true)
    private val calls = mutableListOf<Long>()
    private val failure = UiState.Error("offline", 1)

    @After fun reset() {
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            RefreshPolicy.set(RefreshPolicy.DEFAULT_SECONDS)
        }
    }

    private fun show(seconds: Int = 15, response: suspend (Int) -> Boolean) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            RefreshPolicy.init(ApplicationProvider.getApplicationContext<Application>())
            RefreshPolicy.set(seconds)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state by published.collectAsStateWithLifecycle()
                PollEvery20Seconds(enabled = available.value && state.data != null) {
                    calls += compose.mainClock.currentTime
                    response(calls.size)
                }
            }
        }
        settle()
    }

    private fun publish(state: UiState<Int>): Boolean {
        published.value = state
        return state !is UiState.Error
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    private fun nextAfter(millis: Long) {
        val before = compose.runOnIdle { calls.toList() }
        val deadline = before.last() + millis + 64
        compose.mainClock.advanceTimeBy(deadline - compose.mainClock.currentTime)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(before.size + 1, calls.size)
            val elapsed = calls.last() - before.last()
            assertTrue("expected $millis ms plus composition frames, got $elapsed", elapsed in millis..millis + 64)
        }
    }

    @Test fun identicalErrorsKeepRetryingAndSuccessResetsImmediately() {
        show { publish(if (it in 1..3 || it == 5) failure else UiState.Success(1)) }
        compose.runOnIdle { assertEquals(1, calls.size); assertEquals(failure, published.value) }
        nextAfter(30_000)
        nextAfter(60_000)
        nextAfter(60_000)
        compose.runOnIdle { assertEquals(UiState.Success(1), published.value) }
        nextAfter(15_000)
        compose.runOnIdle { assertEquals(failure, published.value) }
        nextAfter(30_000)
        compose.runOnIdle { assertEquals(UiState.Success(1), published.value) }
    }

    @Test fun refreshResultsControlBackoffWithoutObservableStateChanges() {
        show { it !in 1..3 && it != 5 }
        compose.runOnIdle { assertEquals(1, calls.size); assertEquals(UiState.Success(1), published.value) }
        nextAfter(30_000)
        nextAfter(60_000)
        nextAfter(60_000)
        nextAfter(15_000)
        nextAfter(30_000)
        nextAfter(15_000)
        compose.runOnIdle { assertEquals(UiState.Success(1), published.value) }
    }

    @Test fun healthy120SecondsIsNotCappedButFailureIs() {
        show(seconds = 120) { publish(if (it == 2) failure else UiState.Success(1)) }
        nextAfter(120_000)
        nextAfter(60_000)
        nextAfter(120_000)
    }

    @Test fun changingIntervalClearsTheFailureCountAndCancelsTheOldWait() {
        show { publish(failure) }
        nextAfter(30_000)
        compose.mainClock.advanceTimeBy(10_000)
        compose.runOnUiThread { RefreshPolicy.set(20) }
        settle()
        compose.runOnIdle { assertEquals(3, calls.size) }
        nextAfter(40_000)
        nextAfter(60_000)
    }

    @Test fun manualOnlyDoesNotStartOrLeaveAPollingLoop() {
        show(seconds = 0) { publish(UiState.Success(1)) }
        compose.mainClock.advanceTimeBy(180_000)
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.runOnUiThread { RefreshPolicy.set(20) }
        settle()
        compose.runOnIdle { assertEquals(1, calls.size) }
        nextAfter(20_000)
        compose.runOnUiThread { RefreshPolicy.set(0) }
        settle()
        compose.mainClock.advanceTimeBy(180_000)
        compose.runOnIdle { assertEquals(2, calls.size) }
    }

    @Test fun missingTargetDisablesPollingWithoutUsingTheErrorAsASwitch() {
        available.value = false
        show { publish(failure) }
        compose.mainClock.advanceTimeBy(120_000)
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.runOnUiThread { available.value = true }
        settle()
        compose.runOnIdle {
            assertTrue(available.value && published.value.data != null)
            assertEquals(failure, published.value)
        }
        nextAfter(30_000)
        compose.runOnUiThread { available.value = false }
        settle()
        compose.mainClock.advanceTimeBy(120_000)
        compose.runOnIdle { assertEquals(2, calls.size) }
    }

    @Test fun lifecyclePausesPollingAndKeepsTheRememberedFailureCount() {
        show { publish(failure) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(120_000)
        compose.runOnIdle { assertEquals(1, calls.size) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        settle()
        compose.runOnIdle { assertEquals(2, calls.size) }
        nextAfter(60_000)
    }

    @Test fun suspendedRefreshFinishesBeforeItsResultIsSampledOrAnotherRequestStarts() {
        val result = CompletableDeferred<UiState<Int>>()
        show { publish(if (it == 1) result.await() else UiState.Success(1)) }
        compose.mainClock.advanceTimeBy(120_000)
        compose.runOnIdle { assertEquals(1, calls.size) }
        val completedAt = compose.mainClock.currentTime
        compose.runOnUiThread { result.complete(failure) }
        settle()
        compose.mainClock.advanceTimeBy(15_000)
        compose.runOnIdle { assertEquals(1, calls.size) }
        compose.mainClock.advanceTimeBy(15_064)
        compose.runOnIdle {
            assertEquals(2, calls.size)
            assertTrue(calls.last() - completedAt in 30_000L..30_064L)
        }
        nextAfter(15_000)
    }
}
