package com.yll682.houche.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StationArrivalsLoaderTest {
    private val city = City("11", "厦门市", "0592")
    private val stop = Station("温头溪", "3")
    private val arrival = Arrival(name = "M26", direction = "1", remainingStations = 2)

    @Test fun `八个站点全部查询且最多并发三个一个失败不打断其他站`() = runBlocking {
        withTimeout(5_000) {
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val started = AtomicInteger()
            val firstBatch = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stations = (1..8).map { Station("站点$it", "$it") }
            val results = mutableMapOf<Int, StationLiveResult>()
            val loader = StationArrivalsLoader(query = { _, station ->
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                if (started.incrementAndGet() == 3) firstBatch.complete(Unit)
                try {
                    release.await()
                    if (station.number == "5") throw BusException(BusException.Kind.NETWORK, "第五站网络失败")
                    StationArrivals(station, listOf(arrival), StationArrivalsStatus.READY)
                } finally { active.decrementAndGet() }
            })
            val load = async { loader.loadAll(city, stations) { index, result -> results[index] = result } }
            firstBatch.await()
            assertEquals(3, started.get())
            assertTrue(results.isEmpty())
            release.complete(Unit)
            load.await()
            assertEquals(3, peak.get())
            assertEquals(8, started.get())
            assertEquals(stations.indices.toSet(), results.keys)
            assertTrue(results.getValue(4).state is StationLiveState.Failed)
            assertEquals("第五站网络失败", results.getValue(4).state.message)
            assertTrue(results.filterKeys { it != 4 }.values.all { it.state is StationLiveState.Ready })
        }
    }

    @Test fun `合法空结果部分缺报和失败分别保留明确状态`() = runBlocking {
        val gap = StationArrivalGap(LineSummary("M18", "1"))
        val cases = listOf(
            StationArrivals(stop, listOf(arrival), StationArrivalsStatus.READY) to StationLiveState.Ready::class.java,
            StationArrivals(stop, emptyList(), StationArrivalsStatus.EMPTY) to StationLiveState.Empty::class.java,
            StationArrivals(stop, listOf(arrival), StationArrivalsStatus.PARTIAL, listOf("部分线路缺报"), listOf(gap)) to StationLiveState.Partial::class.java,
            StationArrivals(stop, emptyList(), StationArrivalsStatus.EMPTY, unreportedLines = listOf(gap)) to StationLiveState.Partial::class.java,
            StationArrivals(stop, emptyList(), StationArrivalsStatus.PARTIAL, listOf("另一站台失败")) to StationLiveState.Partial::class.java,
            StationArrivals(stop, emptyList(), StationArrivalsStatus.UNAVAILABLE, listOf("上游失败")) to StationLiveState.Failed::class.java
        )
        for ((result, stateClass) in cases) {
            val loaded = StationArrivalsLoader(query = { _, _ -> result }).load(city, stop)
            assertEquals(stateClass, loaded.state.javaClass)
            assertEquals(result.arrivals, loaded.state.arrivals)
            if (loaded.state is StationLiveState.Partial && result.unreportedLines.isNotEmpty()) {
                assertEquals(listOf(gap), loaded.state.unreportedLines)
            }
        }
    }

    @Test fun `刷新失败保留上次到站而成功的空结果清除旧资料`() = runBlocking {
        val failed = StationArrivalsLoader(query = { _, _ -> throw BusException(BusException.Kind.NETWORK, "离线") })
            .load(city, stop, listOf(arrival)).state
        assertTrue(failed is StationLiveState.Failed)
        assertEquals(listOf(arrival), failed.arrivals)
        val unavailable = StationArrivalsLoader(query = { _, _ -> StationArrivals(stop, emptyList(), StationArrivalsStatus.UNAVAILABLE) })
            .load(city, stop, listOf(arrival)).state
        assertTrue(unavailable is StationLiveState.Failed)
        assertEquals(listOf(arrival), unavailable.arrivals)
        val empty = StationArrivalsLoader(query = { _, _ -> StationArrivals(stop, emptyList(), StationArrivalsStatus.EMPTY) })
            .load(city, stop, listOf(arrival)).state
        assertSame(StationLiveState.Empty, empty)
        assertTrue(empty.arrivals.isEmpty())
    }

    @Test fun `一个站点完成后立即交付无需等同批慢请求`() = runBlocking {
        withTimeout(5_000) {
            val release = CompletableDeferred<Unit>()
            val fastDelivered = CompletableDeferred<Unit>()
            val slowStarted = CompletableDeferred<Unit>()
            val loader = StationArrivalsLoader(query = { _, station ->
                if (station.number == "slow") {
                    slowStarted.complete(Unit)
                    release.await()
                }
                StationArrivals(station, listOf(arrival), StationArrivalsStatus.READY)
            })
            val load = async {
                loader.loadAll(city, listOf(stop, stop.copy(number = "slow"))) { index, _ ->
                    if (index == 0) fastDelivered.complete(Unit)
                }
            }
            fastDelivered.await()
            slowStarted.await()
            assertFalse(load.isCompleted)
            release.complete(Unit)
            load.await()
        }
    }

    @Test fun `取消后即使上游不响应取消也不交付迟到结果`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var delivered = false
            val loader = StationArrivalsLoader(query = { _, _ ->
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    StationArrivals(stop, listOf(arrival), StationArrivalsStatus.READY)
                }
            })
            val load = async { loader.loadAll(city, listOf(stop)) { _, _ -> delivered = true } }
            started.await()
            load.cancel()
            release.complete(Unit)
            load.cancelAndJoin()
            assertFalse(delivered)
            try { load.await(); fail("取消应向调用者传播") } catch (_: CancellationException) { }
        }
    }
}
