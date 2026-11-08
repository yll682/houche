package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

class MetroRealtimeRequestsTest {
    private val stations = (1..24).map { "01%02d0000".format(it) }
    private val selected = stations[7]
    private val unsampled = stations[9]
    private val line = "01000000"

    @Test fun `切到非原批参考站会立即真实查询并保留批次原时间`() = runBlocking {
        val now = AtomicLong(1_000)
        val sent = ConcurrentLinkedQueue<String>()
        client { request ->
            sent.add(request.reference())
            respond(fixture(request.reference()))
        }.use { http ->
            val remote = MetroRealtimeRemote(http, now::get)
            val positions = remote.latestLine(line, stations, selected)
            assertEquals(6, sent.size)
            assertFalse(sent.contains(unsampled))
            now.set(2_000)
            val station = completed(remote.latestStation(line, unsampled))
            assertTrue(station.online)
            assertEquals(7, sent.size)
            assertEquals(unsampled, sent.last())
            assertTrue(station.directions.all { it.arrivalsAvailable && it.trains.size == 3 })
            val projected = overlayMetroRealtimeArrivals(positions, station)
            assertSame(positions.source, projected.source)
            assertSame(positions.sources, projected.sources)
            assertEquals(1_000L, projected.source!!.receivedAtElapsedMillis)
            assertTrue(projected.directions.all { it.source!!.receivedAtElapsedMillis == 2_000L })
        }
    }

    @Test fun `自动批缓存内首次手刷发新POST而重复成功动作两秒内合并`() = runBlocking {
        val now = AtomicLong(0)
        val sent = AtomicInteger()
        client { request -> sent.incrementAndGet(); respond(fixture(request.reference())) }.use { http ->
            val remote = MetroRealtimeRemote(http, now::get)
            remote.latestLine(line, stations, selected)
            assertEquals(6, sent.get())
            val manual = completed(remote.latestStation(line, selected))
            assertEquals(7, sent.get())
            now.set(1_999)
            assertSame(manual, completed(remote.latestStation(line, selected)))
            assertEquals(7, sent.get())
            val waiting = remote.latestStation(line, unsampled) as MetroRealtimeRequestResult.Deferred
            assertEquals(1L, waiting.retryAfterMillis)
            now.set(2_000)
            assertTrue(completed(remote.latestStation(line, selected)).online)
            assertEquals(8, sent.get())
        }
    }

    @Test fun `同站在途只发一次且取消一位等待者不取消其他人`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = AtomicInteger()
            client { request ->
                sent.incrementAndGet()
                entered.complete(Unit)
                release.await()
                respond(fixture(request.reference()))
            }.use { http ->
                val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L })
                val first = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, selected) }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, selected) }
                first.cancelAndJoin()
                release.complete(Unit)
                assertTrue(completed(second.await()).online)
                assertEquals(1, sent.get())
            }
        }
    }

    @Test fun `本站与自动采样双向共享同站在途请求`() = runBlocking {
        for (foregroundFirst in listOf(false, true)) withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val selectedSent = AtomicInteger()
            client { request ->
                if (request.reference() == selected) {
                    selectedSent.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                }
                respond(fixture(request.reference()))
            }.use { http ->
                val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L }, requestDispatcher = Dispatchers.Unconfined)
                if (foregroundFirst) {
                    val foreground = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, selected) }
                    entered.await()
                    val batch = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { remote.latestLine(line, stations, selected) }
                    // 读取同一batch作为第二位等待者，确保collectFrame已启动且selected仍在途。
                    val observer = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { remote.latestLine(line, stations, selected) }
                    release.complete(Unit)
                    assertTrue(completed(foreground.await()).online)
                    assertTrue(batch.await().online)
                    assertTrue(observer.await().online)
                } else {
                    val batch = async { remote.latestLine(line, stations, selected) }
                    entered.await()
                    val foreground = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, selected) }
                    release.complete(Unit)
                    assertTrue(batch.await().online)
                    assertTrue(completed(foreground.await()).online)
                }
                assertEquals("foregroundFirst=$foregroundFirst", 1, selectedSent.get())
            }
        }
    }

    @Test fun `自动采样慢请求占一位时新本站立即使用预留位`() = runBlocking {
        withTimeout(5_000) {
            val autoEntered = CompletableDeferred<Unit>()
            val foregroundEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            client { request ->
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    if (request.reference() == unsampled) foregroundEntered.complete(Unit) else autoEntered.complete(Unit)
                    release.await()
                    respond(fixture(request.reference()))
                } finally { active.decrementAndGet() }
            }.use { http ->
                val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L })
                val batch = async { remote.latestLine(line, stations, selected) }
                autoEntered.await()
                val foreground = async { remote.latestStation(line, unsampled) }
                foregroundEntered.await()
                assertEquals(2, active.get())
                release.complete(Unit)
                assertTrue(completed(foreground.await()).online)
                assertTrue(batch.await().online)
                assertEquals(2, peak.get())
            }
        }
    }

    @Test fun `失败不隐式retry不回放旧失败且额度等待不是失败`() = runBlocking {
        val now = AtomicLong(0)
        val sent = AtomicInteger()
        client { request ->
            if (sent.incrementAndGet() == 1) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond(fixture(request.reference()))
        }.use { http ->
            val remote = MetroRealtimeRemote(http, now::get)
            val first = completed(remote.latestStation(line, selected))
            assertFalse(first.online)
            assertEquals(1, sent.get())
            now.set(1_000)
            assertEquals(1_000L, (remote.latestStation(line, selected) as MetroRealtimeRequestResult.Deferred).retryAfterMillis)
            assertEquals(1, sent.get())
            now.set(2_000)
            assertTrue(completed(remote.latestStation(line, selected)).online)
            assertEquals(2, sent.get())
        }
    }

    @Test fun `同站新自动请求失败后重试不能回放更早本站成功缓存`() = runBlocking {
        withTimeout(5_000) {
            val now = AtomicLong(0)
            val autoEntered = CompletableDeferred<Unit>()
            val releaseFailure = CompletableDeferred<Unit>()
            val selectedSent = AtomicInteger()
            client { request ->
                if (request.reference() == selected && selectedSent.incrementAndGet() == 2) {
                    autoEntered.complete(Unit)
                    releaseFailure.await()
                    respond("{}", HttpStatusCode.ServiceUnavailable)
                } else respond(fixture(request.reference()))
            }.use { http ->
                val remote = MetroRealtimeRemote(http, now::get)
                assertTrue(completed(remote.latestStation(line, selected)).online)
                now.set(100)
                val batch = async { remote.latestLine(line, stations, selected) }
                autoEntered.await()
                val joined = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, selected) }
                releaseFailure.complete(Unit)
                assertFalse(completed(joined.await()).online)
                assertTrue(batch.await().online)
                assertEquals(2, selectedSent.get())
                now.set(1_100)
                val retry = remote.latestStation(line, selected)
                assertTrue("新失败后不得回放t0成功：$retry", retry is MetroRealtimeRequestResult.Deferred)
                assertEquals(900L, (retry as MetroRealtimeRequestResult.Deferred).retryAfterMillis)
                assertEquals(2, selectedSent.get())
                now.set(2_000)
                val recovered = completed(remote.latestStation(line, selected))
                assertTrue(recovered.online)
                assertEquals(2_000L, recovered.source!!.receivedAtElapsedMillis)
                assertEquals(3, selectedSent.get())
            }
        }
    }

    @Test fun `响应站码不一致视为真实协议失败而非成功`() = runBlocking {
        client { respond(fixture(unsampled)) }.use { http ->
            val result = completed(MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L }).latestStation(line, selected))
            assertFalse(result.online)
            assertEquals(selected, result.stationCode)
            assertTrue(result.note.contains("站码"))
        }
    }

    @Test fun `空目标是调用前置错误而不是可退避的网络失败`() = runBlocking {
        client { error("空目标不得发送HTTP") }.use { http ->
            val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L })
            for ((lineCode, stationCode) in listOf("" to selected, line to " ")) {
                try {
                    remote.latestStation(lineCode, stationCode)
                    fail("空目标应在进入网络状态机前拒绝")
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun `假时钟连续点击只按真实网络启动计数并遵守二十秒与分钟上限`() = runBlocking {
        val now = AtomicLong(0)
        val starts = ConcurrentLinkedQueue<Long>()
        client { request -> starts.add(now.get()); respond(fixture(request.reference())) }.use { http ->
            val remote = MetroRealtimeRemote(http, now::get)
            for (time in 0L until 70_000L step 100L) {
                now.set(time)
                remote.latestStation(line, if ((time / 100) % 2L == 0L) selected else unsampled)
            }
            assertEquals(35, starts.size)
            val physical = starts.toList()
            assertTrue(physical.zipWithNext().all { (a, b) -> b - a >= 2_000L })
            for (time in physical) {
                assertTrue(physical.count { it > time - 20_000L && it <= time } <= 10)
                assertTrue(physical.count { it > time - 60_000L && it <= time } <= 30)
            }
        }
    }

    @Test fun `排队前取消不泄漏通道也不在以后补发旧站`() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = QueuedDispatcher()
            val sent = ConcurrentLinkedQueue<String>()
            client { request -> sent.add(request.reference()); respond(fixture(request.reference())) }.use { http ->
                val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L }, requestDispatcher = dispatcher)
                val old = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, selected) }
                // 先让caller finally把取消传给shared task，再执行尚未启动的IO任务。
                // 反过来drain会主动启动旧站HTTP，测到的是另一条取消边界。
                old.cancelAndJoin()
                assertTrue(sent.isEmpty())
                val current = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, unsampled) }
                dispatcher.drain()
                // MockEngine的响应可能在其dispatcher恢复，再驱动请求完成即可。
                while (!current.isCompleted) {
                    kotlinx.coroutines.yield()
                    dispatcher.drain()
                }
                val initial = current.await()
                val result = if (initial is MetroRealtimeRequestResult.Deferred) {
                    // shared任务完成取消前可暂占许可，但没有HTTP启动，不得消耗2s额度。
                    assertEquals(250L, initial.retryAfterMillis)
                    assertTrue(sent.isEmpty())
                    val retry = async(start = CoroutineStart.UNDISPATCHED) { remote.latestStation(line, unsampled) }
                    while (!retry.isCompleted) {
                        dispatcher.drain()
                        kotlinx.coroutines.yield()
                    }
                    retry.await()
                } else initial
                assertTrue(completed(result).online)
                assertEquals(0L, completed(result).source!!.receivedAtElapsedMillis)
                assertEquals(listOf(unsampled), sent.toList())
            }
        }
    }

    @Test fun `真实相隔六十秒ETA投影不改旧批次不放大身份且锚点使用真实新行`() {
        val remote = MetroRealtimeRemote()
        val references = listOf(7, 0, 6, 12, 18, 23).map(stations::get)
        val positions = mergeMetroRealtime(references.map { remote.parse(fixture(it), line, it) }, selected, line)
        val original = positions.copy()
        val arrivals = remote.parse(fixture(selected, "173330"), line, selected)
        val projected = overlayMetroRealtimeArrivals(positions, arrivals)
        assertEquals(original, positions)
        assertSame(positions.source, projected.source)
        assertSame(positions.sources, projected.sources)
        assertNotEquals(positions.source!!.serverEpochMillis, arrivals.source!!.serverEpochMillis)
        for ((old, direction) in positions.directions.zip(projected.directions)) {
            val fresh = arrivals.directions.single { it.directionKey == direction.directionKey }
            assertEquals(fresh.trains, direction.trains)
            assertTrue(direction.allIdentifiedTrains.size <= old.allIdentifiedTrains.size)
            val visible = direction.positionPresentations()
            assertTrue(visible.isNotEmpty())
            assertTrue(visible.all { it.ordinal <= old.allIdentifiedTrains.size })
            direction.trains.forEachIndexed { index, row ->
                assertEquals(1, direction.allIdentifiedTrains.count { direction.arrivalRowOrderOf(it) == index + 1 })
                assertTrue(direction.allIdentifiedTrains.any { it === row || it.matchedObservations.any { sample -> sample.source == row.source } })
            }
            val coordinates = visible.map { it.stationPosition }.sorted()
            assertTrue(coordinates.zipWithNext().all { (a, b) -> b - a + 1e-9 >= 1.0 })
        }
    }

    @Test fun `旧批次只有单方向时本站新双向响应补齐真实局部方向`() {
        val remote = MetroRealtimeRemote()
        val old = remote.parse(fixture(selected), line, selected)
        val partial = mergeMetroRealtime(listOf(old.copy(directions = old.directions.take(1))), selected, line)
        val fresh = remote.parse(fixture(selected, "173330"), line, selected)
        val projected = overlayMetroRealtimeArrivals(partial, fresh)
        assertEquals(1, partial.directions.size)
        assertEquals(2, projected.directions.size)
        assertSame(partial.source, projected.source)
        assertSame(partial.sources, projected.sources)
        assertEquals(partial.directions.single().directionKey, projected.directions.first().directionKey)
        val added = projected.directions.last()
        val actual = fresh.directions.single { it.directionKey == added.directionKey }
        assertEquals(actual.trains, added.trains)
        assertEquals(actual.trains, added.allIdentifiedTrains)
        assertEquals(actual.source, added.source)
        assertTrue(added.arrivalsAvailable)
        assertTrue(added.positionPresentations().isNotEmpty())
    }

    private fun completed(result: MetroRealtimeRequestResult): MetroRealtime =
        (result as MetroRealtimeRequestResult.Completed).realtime

    private fun HttpRequestData.reference(): String = requireNotNull(url.parameters["stationCode"])

    private fun fixture(reference: String, tag: String = "173230"): String =
        requireNotNull(javaClass.classLoader!!.getResource("line1-multi/rt-01000000-$reference-$tag.json")).readText()

    private fun client(reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(reply)) { install(HttpTimeout) }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }
}
