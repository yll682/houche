package com.yll682.houche.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** 真实生产控制器的虚拟时钟测试，不依赖 Android 或 kotlinx-coroutines-test。 */
class MetroRealtimeRetryControllerTest {
    @Test fun `无须手刷即可按1 2 4 8 8秒重试并在成功后停止`() = runBlocking {
        val clock = ManualClock()
        val calls = mutableListOf<Long>()
        val results = mutableListOf<MetroEtaState>()
        var state = MetroRealtimeState(stationCode = "A")
        val controller = MetroRealtimeRetryController<String>(
            scope = this,
            request = {
                calls += clock.now
                completed(if (calls.size <= 5) MetroRealtime(false, "超时 ${calls.size}") else success("A"))
            },
            onLoading = { state = state.beginRequest() },
            onResult = { _, result -> state = state.complete(result); results += state.etaState },
            elapsedRealtimeMillis = { clock.now }, waitMillis = clock::wait
        )
        val job = controller.refresh("A")
        assertEquals(listOf(0L), calls)
        assertTrue(state.etaState is MetroEtaState.Loading)
        for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L)) {
            clock.advance(wait)
            settle()
        }
        job.join()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 23_000L), calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), clock.waits)
        assertEquals(5, results.count { it is MetroEtaState.Unavailable })
        assertTrue(state.etaState is MetroEtaState.Empty)
        clock.advance(60_000)
        settle()
        assertEquals(6, calls.size)
    }

    @Test fun `预算未发HTTP始终Loading且不消耗失败退避档位`() = runBlocking {
        val clock = ManualClock()
        var calls = 0
        val failures = mutableListOf<String>()
        var state = MetroRealtimeState(stationCode = "A")
        val controller = MetroRealtimeRetryController<String>(
            scope = this,
            request = {
                when (++calls) {
                    1 -> MetroRealtimeRequestResult.Deferred(2_500)
                    2 -> completed(MetroRealtime(false, "HTTP 500"))
                    3 -> MetroRealtimeRequestResult.Deferred(500)
                    4 -> completed(MetroRealtime(false, "JSON失败"))
                    else -> completed(success("A"))
                }
            },
            onLoading = { state = state.beginRequest() },
            onResult = { _, result ->
                state = state.complete(result)
                if (!result.online) failures += result.note
            },
            elapsedRealtimeMillis = { clock.now }, waitMillis = clock::wait
        )
        val job = controller.refresh("A")
        assertTrue(state.etaState is MetroEtaState.Loading)
        assertTrue(failures.isEmpty())
        for (wait in listOf(2_500L, 1_000L, 500L, 2_000L)) {
            clock.advance(wait)
            settle()
        }
        job.join()
        assertEquals(listOf(2_500L, 1_000L, 500L, 2_000L), clock.waits)
        assertEquals(listOf("HTTP 500", "JSON失败"), failures)
        assertEquals(5, calls)
    }

    @Test fun `同目标轮询和手刷共用单条请求退避链且成功后允许重新刷新`() = runBlocking {
        val clock = ManualClock()
        val response = CompletableDeferred<MetroRealtimeRequestResult>()
        var calls = 0
        val controller = MetroRealtimeRetryController<Query>(
            scope = this,
            request = { calls++; if (calls == 1) response.await() else completed(success(it.station)) },
            onLoading = {}, onResult = { _, _ -> },
            sameTarget = { first, second -> first.station == second.station },
            elapsedRealtimeMillis = { clock.now }, waitMillis = clock::wait
        )
        val first = controller.refresh(Query("A", 1))
        var initialSelection = MetroInitialSelection.start()
        repeat(20) {
            // 有效手动意图先消费定位机会，再按同目标复用；复用不能跳过手动 gate。
            initialSelection = initialSelection.manualSelection()
            assertSame(first, controller.refresh(Query("A", it + 2)))
        }
        assertEquals(MetroInitialSelectionPhase.Consumed, initialSelection.phase)
        val lateLocation = initialSelection.receive(MetroInitialLocation(24.5, 118.1, 10.0, "GCJ02"), emptyList())
        assertNull(lateLocation.station)
        assertEquals(MetroInitialSelectionPhase.Consumed, lateLocation.state.phase)
        assertEquals(1, calls)
        assertTrue(clock.waits.isEmpty())
        response.complete(completed(MetroRealtime(false, "超时")))
        settle()
        assertEquals(listOf(1_000L), clock.waits)
        clock.advance(1_000)
        settle()
        first.join()
        val next = controller.refresh(Query("A", 30))
        next.join()
        assertNotSame(first, next)
        assertEquals(3, calls)
    }

    @Test fun `八秒退避中手刷唤醒同链但仍遵守额度且不会留下旧定时器`() = runBlocking {
        val clock = ManualClock()
        val calls = mutableListOf<Long>()
        val results = mutableListOf<MetroRealtime>()
        val controller = MetroRealtimeRetryController<String>(
            scope = this,
            request = {
                calls += clock.now
                when (calls.size) {
                    in 1..4 -> completed(MetroRealtime(false, "超时"))
                    5 -> MetroRealtimeRequestResult.Deferred(2_000)
                    else -> completed(success("A"))
                }
            },
            onLoading = {}, onResult = { _, result -> results += result },
            elapsedRealtimeMillis = { clock.now }, waitMillis = clock::wait
        )
        val original = controller.refresh("A")
        for (wait in listOf(1_000L, 2_000L, 4_000L)) {
            clock.advance(wait)
            settle()
        }
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), calls)
        assertEquals(1, clock.activeWaits)
        clock.advance(500)
        repeat(20) { assertSame(original, controller.refresh("A")) }
        settle()
        assertEquals(7_500L, calls.last())
        assertEquals(5, calls.size)
        assertEquals(1, clock.activeWaits)
        // 当前是额度等待，连按既不能绕过两秒，也不能建立额外 HTTP/退避任务。
        repeat(20) { assertSame(original, controller.refresh("A")) }
        clock.advance(1_999)
        settle()
        assertEquals(5, calls.size)
        clock.advance(1)
        settle()
        original.join()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 7_500L, 9_500L), calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 2_000L), clock.waits)
        assertEquals(0, clock.activeWaits)
        clock.advance(20_000)
        settle()
        assertEquals(6, calls.size)
        assertEquals(4, results.count { !it.online })
    }

    @Test fun `换目标取消退避且不接受忽略取消的旧响应`() = runBlocking {
        val clock = ManualClock()
        val oldResponse = CompletableDeferred<MetroRealtime>()
        val received = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val controller = MetroRealtimeRetryController<String>(
            scope = this,
            request = { station ->
                calls += station
                if (station == "A") completed(withContext(NonCancellable) { oldResponse.await() })
                else completed(if (station == "B") MetroRealtime(false, "超时") else success(station))
            },
            onLoading = {}, onResult = { station, _ -> received += station },
            elapsedRealtimeMillis = { clock.now }, waitMillis = clock::wait
        )
        val a = controller.refresh("A")
        val b = controller.refresh("B")
        controller.refresh("C").join()
        oldResponse.complete(success("A"))
        a.join()
        b.join()
        clock.advance(60_000)
        settle()
        assertEquals(listOf("A", "B", "C"), calls)
        assertEquals(listOf("B", "C"), received)
        assertTrue(a.isCancelled)
        assertTrue(b.isCancelled)
    }

    @Test fun `拥有者销毁会取消重试等待且不会再发请求`() = runBlocking {
        val clock = ManualClock()
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        var calls = 0
        val controller = MetroRealtimeRetryController<String>(
            scope = owner, request = { calls++; completed(MetroRealtime(false, "超时")) },
            onLoading = {}, onResult = { _, _ -> },
            elapsedRealtimeMillis = { clock.now }, waitMillis = clock::wait
        )
        val job = controller.refresh("A")
        owner.cancel()
        job.join()
        clock.advance(10_000)
        settle()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun `切站失败及退避不借用旧站ETA也不更新原采样时钟`() = runBlocking {
        val clock = ManualClock()
        val source = MetroObservationSource("A", receivedAtElapsedMillis = 0, receivedAtEpochMillis = 1_800_000_000_000)
        val original = success("A").copy(source = source, directions = success("A").directions.map { it.copy(source = source) })
        var state = MetroRealtimeState(frame = original).selectStation("B", "乙站")
        val failed = mutableListOf<MetroEtaState>()
        val controller = MetroRealtimeRetryController<String>(
            scope = this, request = { completed(MetroRealtime(false, "超时")) },
            onLoading = { state = state.beginRequest() },
            onResult = { _, result -> state = state.completeStation(result); failed += state.etaState },
            elapsedRealtimeMillis = { clock.now }, waitMillis = clock::wait
        )
        val job = controller.refresh("B")
        assertEquals(MetroEtaState.Loading("B"), state.etaState)
        assertEquals(MetroEtaState.Unavailable("B", "超时"), failed.single())
        assertSame(original, state.frame)
        assertEquals(source, state.lastSuccessAt)
        clock.advance(1_000)
        settle()
        assertEquals(MetroEtaState.Loading("B"), state.etaState)
        assertEquals(1_000L, state.positionsAt(clock.now).ageMillis)
        assertTrue(state.displayFrame!!.directions.all { it.trains.isEmpty() })
        controller.cancel()
        job.join()
    }

    private suspend fun settle() { repeat(6) { yield() } }

    private data class Query(val station: String, val sequence: Int)
    private fun completed(value: MetroRealtime) = MetroRealtimeRequestResult.Completed(value)
    private fun success(station: String) = MetroRealtime(true, "实时", stationCode = station,
        directions = listOf(MetroDirection("甲", "乙", "06:00", "23:00", 0, emptyList(), listOf("A", "B"))))

    private class ManualClock {
        var now = 0L
            private set
        val waits = mutableListOf<Long>()
        private data class Sleep(val at: Long, val gate: CompletableDeferred<Unit>)
        private val sleepers = mutableListOf<Sleep>()
        val activeWaits: Int get() = sleepers.size
        suspend fun wait(millis: Long) {
            waits += millis
            val gate = CompletableDeferred<Unit>()
            val sleep = Sleep(now + millis, gate)
            sleepers += sleep
            try { gate.await() } finally { sleepers.remove(sleep) }
        }
        fun advance(millis: Long) {
            now += millis
            val ready = sleepers.filter { it.at <= now }
            sleepers.removeAll(ready.toSet())
            ready.forEach { it.gate.complete(Unit) }
        }
    }
}
