package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 假单调时钟验证缓存窗口；MockEngine 验证实际 HTTP 数、并发和取消边界。 */
class MetroRealtimeClientTest {
    private val stations = (1..32).map { "02%02d0000".format(it) }
    private val selected = stations[9]

    @Test fun `每批最多八次物理请求且两条线路合计并发也不超过二`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            client { request ->
                val count = requests.incrementAndGet()
                val simultaneous = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, simultaneous) }
                if (count == 2) entered.complete(Unit)
                try {
                    release.await()
                    respond(response(request.reference()))
                } finally {
                    active.decrementAndGet()
                }
            }.use { http ->
                val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L })
                // index 12 的所选站及两个邻站都不与固定锚点重合，正好八次。
                val first = async { remote.latestLine("02000000", stations, stations[12]) }
                val second = async { remote.latestLine("03000000", stations, stations[12]) }
                entered.await()
                assertEquals(2, requests.get())
                assertEquals(2, peak.get())
                release.complete(Unit)
                assertTrue(awaitAll(first, second).all { it.online })
                assertEquals(16, requests.get())
                assertEquals(2, peak.get())
            }
        }
    }

    @Test fun `换参考站复用完整同批且没有第九次请求或冒用别站ETA`() = runBlocking {
        val now = AtomicLong(1_000)
        val wall = AtomicLong(1_800_000_000_000)
        val requests = AtomicInteger()
        client { request ->
            requests.incrementAndGet()
            respond(response(request.reference(), trainCount = 3))
        }.use { http ->
            val remote = MetroRealtimeRemote(http, now::get, wall::get)
            val first = remote.latestLine("02000000", stations, selected)
            assertTrue(first.online)
            assertEquals(7, requests.get())
            assertTrue(first.directions.all { it.trains.size == 3 && it.arrivalsAvailable })
            assertTrue(first.directions.flatMap { it.trains }.all { it.source?.referenceStationCode == selected })
            assertEquals(1_000L, first.source!!.receivedAtElapsedMillis)
            assertEquals(wall.get(), first.source!!.receivedAtEpochMillis)
            assertEquals(1_800_000_000_000L, first.source!!.serverEpochMillis)

            now.addAndGet(19_999)
            wall.addAndGet(19_999)
            val cachedNeighbor = remote.latestLine("02000000", stations, stations[8])
            assertEquals(stations[8], cachedNeighbor.stationCode)
            assertTrue(cachedNeighbor.directions.all { it.arrivalsAvailable && it.trains.size == 3 })
            assertTrue(cachedNeighbor.directions.flatMap { it.trains }.all { it.source?.referenceStationCode == stations[8] })
            assertEquals(first.source!!.receivedAtEpochMillis, cachedNeighbor.source!!.receivedAtEpochMillis)

            val unsampled = remote.latestLine("02000000", stations, stations[12])
            assertTrue(unsampled.online)
            assertTrue(unsampled.directions.all { !it.arrivalsAvailable && it.trains.isEmpty() && it.positions.isNotEmpty() })
            assertEquals(first.directions.map { it.positions }, unsampled.directions.map { it.positions })
            assertEquals(first.source!!.receivedAtEpochMillis, unsampled.source!!.receivedAtEpochMillis)
            assertEquals(7, requests.get())
        }
    }

    @Test fun `TTL按单调起始时间到期且新批失败不会续命旧车`() = runBlocking {
        val now = AtomicLong(1_000)
        val wall = AtomicLong(1_800_000_000_000)
        val requests = AtomicInteger()
        var available = true
        client { request ->
            requests.incrementAndGet()
            if (!available) throw IOException("离线")
            respond(response(request.reference()))
        }.use { http ->
            val remote = MetroRealtimeRemote(http, now::get, wall::get)
            val first = remote.latestLine("02000000", stations, selected)
            assertTrue(first.directions.all { it.positions.isNotEmpty() })
            assertEquals(7, requests.get())
            // 本机墙钟任意跳变不影响 TTL，也不能改写缓存中的真实接收时间。
            wall.set(0)
            now.addAndGet(19_999)
            assertEquals(first.source, remote.latestLine("02000000", stations, selected).source)
            assertEquals(7, requests.get())
            available = false
            now.incrementAndGet()
            val failed = remote.latestLine("02000000", stations, selected)
            assertFalse(failed.online)
            assertTrue(failed.directions.isEmpty())
            assertEquals(14, requests.get())
            assertFalse(remote.latestLine("02000000", stations, stations[12]).online)
            assertEquals(14, requests.get())
            available = true
            now.addAndGet(20_000)
            assertTrue(remote.latestLine("02000000", stations, selected).online)
            assertEquals(21, requests.get())
        }
    }

    @Test fun `HTTP和socket失败每参考站只请求一次且保留其他站位置`() = runBlocking {
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        client { request ->
            val reference = request.reference()
            requests.computeIfAbsent(reference) { AtomicInteger() }.incrementAndGet()
            when (reference) {
                selected -> throw IOException("连接断开")
                stations[8] -> respond("{}", HttpStatusCode.TooManyRequests)
                stations[10] -> respond("{}", HttpStatusCode.ServiceUnavailable)
                else -> respond(response(reference))
            }
        }.use { http ->
            val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L })
            val result = remote.latestLine("02000000", stations, selected)
            assertTrue(result.online)
            assertEquals(7, requests.size)
            assertTrue(requests.values.all { it.get() == 1 })
            assertEquals(setOf(stations[0], stations[16], stations[23], stations[31]), result.sources.map { it.referenceStationCode }.toSet())
            assertTrue(result.directions.all { !it.arrivalsAvailable && it.trains.isEmpty() && it.positions.isNotEmpty() })
            remote.latestLine("02000000", stations, selected)
            assertTrue(requests.values.all { it.get() == 1 })
        }
    }

    @Test fun `批次截止只丢未完成站并保留成功本站的完整到站行`() = runBlocking {
        withTimeout(5_000) {
            val never = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            client { request ->
                requests.incrementAndGet()
                if (request.reference() != selected) never.await()
                respond(response(request.reference(), trainCount = 3))
            }.use { http ->
                val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L }, lineTimeoutMillis = 500)
                val result = remote.latestLine("02000000", stations, selected)
                assertTrue(result.online)
                assertEquals(setOf(selected), result.sources.map { it.referenceStationCode }.toSet())
                assertTrue(result.directions.all { it.arrivalsAvailable && it.trains.size == 3 && it.positions.size == 3 })
                assertTrue(requests.get() <= METRO_REALTIME_MAX_REFERENCES)
                val sent = requests.get()
                assertTrue(remote.latestLine("02000000", stations, selected).online)
                assertEquals(sent, requests.get())
            }
        }
    }

    @Test fun `重叠批次共享在途且取消一个调用方不取消另一个`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val cancelled = AtomicInteger()
            client { request ->
                if (requests.incrementAndGet() == 2) entered.complete(Unit)
                try {
                    release.await()
                    respond(response(request.reference()))
                } catch (error: CancellationException) {
                    cancelled.incrementAndGet()
                    throw error
                }
            }.use { http ->
                val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L })
                val first = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    remote.latestLine("02000000", stations, selected)
                }
                entered.await()
                // 相同 dispatcher + UNDISPATCHED 保证返回 async 前已加入共享等待。
                val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    remote.latestLine("02000000", stations, stations[8])
                }
                first.cancelAndJoin()
                assertTrue(first.isCancelled)
                assertEquals(0, cancelled.get())
                release.complete(Unit)
                val result = second.await()
                assertTrue(result.online)
                assertEquals(stations[8], result.stationCode)
                assertTrue(result.directions.all { it.arrivalsAvailable })
                assertEquals(7, requests.get())
                assertEquals(0, cancelled.get())
            }
        }
    }

    @Test fun `最后等待者取消会终止HTTP且窗口内重进不额外发送`() = runBlocking {
        withTimeout(5_000) {
            val now = AtomicLong(1_000)
            val entered = CompletableDeferred<Unit>()
            val cancelledBoth = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val cancelled = AtomicInteger()
            client { request ->
                if (requests.incrementAndGet() == 2) entered.complete(Unit)
                try {
                    release.await()
                    respond(response(request.reference()))
                } catch (error: CancellationException) {
                    if (cancelled.incrementAndGet() == 2) cancelledBoth.complete(Unit)
                    throw error
                }
            }.use { http ->
                val remote = MetroRealtimeRemote(http, now::get)
                val call = async { remote.latestLine("02000000", stations, selected) }
                entered.await()
                call.cancelAndJoin()
                assertTrue(call.isCancelled)
                cancelledBoth.await()
                assertFalse(remote.latestLine("02000000", stations, stations[12]).online)
                assertEquals(2, requests.get())
                now.addAndGet(20_000)
                release.complete(Unit)
                assertTrue(remote.latestLine("02000000", stations, selected).online)
                assertEquals(9, requests.get())
            }
        }
    }

    @Test fun `没有有效线路站码不发送网络请求`() = runBlocking {
        client { error("无效输入不应访问网络") }.use { http ->
            val remote = MetroRealtimeRemote(http, elapsedRealtimeMillis = { 0L })
            assertFalse(remote.latestLine("", stations, selected).online)
            assertFalse(remote.latestLine("02000000", listOf("", " "), selected).online)
        }
    }

    @Test fun `错峰批次在t20只发两次并等待t23再发其余六次`() = runBlocking {
        withTimeout(5_000) {
            val now = AtomicLong(0)
            val firstTwo = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val waits = Channel<Long>(Channel.UNLIMITED)
            val releaseBudget = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val physicalStarts = ConcurrentLinkedQueue<Long>()
            client { request ->
                physicalStarts.add(now.get())
                val sent = requests.incrementAndGet()
                if (sent == 2) firstTwo.complete(Unit)
                if (sent <= 2) releaseFirst.await()
                respond(response(request.reference()))
            }.use { http ->
                val remote = MetroRealtimeRemote(http, now::get, budgetDelay = { duration ->
                    waits.send(duration)
                    releaseBudget.await()
                })
                val first = async { remote.latestLine("02000000", stations, stations[12]) }
                firstTwo.await()
                now.set(3_000)
                releaseFirst.complete(Unit)
                assertTrue(first.await().online)
                assertEquals(8, requests.get())
                assertEquals(2, physicalStarts.count { it == 0L })
                assertEquals(6, physicalStarts.count { it == 3_000L })

                now.set(20_000)
                val second = async { remote.latestLine("02000000", stations, stations[12]) }
                assertEquals(3_000L, waits.receive())
                assertEquals(3_000L, waits.receive())
                assertEquals(10, requests.get())
                now.set(23_000)
                releaseBudget.complete(Unit)
                assertTrue(second.await().online)
                assertEquals(16, requests.get())
                val starts = physicalStarts.toList()
                starts.forEach { time ->
                    assertTrue("时刻 $time 的滚动窗口：$starts", starts.count { it > time - 20_000 && it <= time } <= 8)
                }
            }
        }
    }

    @Test fun `预算等待可取消并归还全局并发许可供别线继续`() = runBlocking {
        withTimeout(5_000) {
            val now = AtomicLong(0)
            val firstTwo = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val budgetWaits = Channel<Unit>(Channel.UNLIMITED)
            val cancelledWaits = Channel<Unit>(Channel.UNLIMITED)
            val never = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            client { request ->
                val sent = requests.incrementAndGet()
                if (sent == 2) firstTwo.complete(Unit)
                if (sent <= 2) releaseFirst.await()
                respond(response(request.reference()))
            }.use { http ->
                val remote = MetroRealtimeRemote(http, now::get, budgetDelay = {
                    budgetWaits.send(Unit)
                    try {
                        never.await()
                    } catch (error: CancellationException) {
                        cancelledWaits.trySend(Unit)
                        throw error
                    }
                })
                val first = async { remote.latestLine("02000000", stations, stations[12]) }
                firstTwo.await()
                now.set(3_000)
                releaseFirst.complete(Unit)
                first.await()
                now.set(20_000)
                val waiting = async { remote.latestLine("02000000", stations, stations[12]) }
                budgetWaits.receive()
                budgetWaits.receive()
                assertEquals(10, requests.get())
                waiting.cancelAndJoin()
                cancelledWaits.receive()
                cancelledWaits.receive()
                assertTrue(remote.latestLine("03000000", stations, stations[12]).online)
                assertEquals(18, requests.get())
            }
        }
    }

    @Test fun `parser保留来源而缺失或非法比例不能参与同车近似匹配`() {
        val remote = MetroRealtimeRemote()
        for ((scale, known) in listOf("0.3" to true, "0" to true, "1" to true, "null" to false, "-0.1" to false, "1.1" to false)) {
            val result = remote.parse(response(selected, scale = scale), "02000000", selected)
            assertEquals(selected, result.source!!.referenceStationCode)
            assertEquals(1_800_000_000_000L, result.source!!.serverEpochMillis)
            assertNull(result.source!!.receivedAtElapsedMillis)
            result.directions.forEach { direction ->
                assertEquals(result.source, direction.source)
                assertEquals(result.source, direction.trains.single().source)
                assertEquals(scale, known, direction.trains.single().scaleKnown)
            }
        }
    }

    private fun HttpRequestData.reference(): String = requireNotNull(url.parameters["stationCode"])

    private fun response(reference: String, trainCount: Int = 1, scale: String = "0.3"): String {
        val referenceIndex = stations.indexOf(reference)
        val directions = listOf(stations, stations.reversed()).joinToString(",") { order ->
            val stops = order.joinToString(",") { """{"stationCode":"$it","name":"站$it"}""" }
            val nextIndex = order.indexOf(reference)
            val trains = (0 until trainCount).joinToString(",") { index ->
                val next = order[(nextIndex - index).coerceAtLeast(0)]
                """{"arriveTime":${60 + referenceIndex + index * 60},"arriveNextStationCode":"$next","arriveNextStationName":"站$next","arriveNextStationScale":$scale,"arrivingFlag":1}"""
            }
            """{"stationCode":"$reference","stationName":"站$reference","transStartTime":"06:00","transEndTime":"23:00","wayStations":[$stops],"transTimes":[$trains]}"""
        }
        return MetroFixtures.envelope("""{"code":0,"ts":1800000000000,"data":[$directions]}""")
    }

    private fun client(reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(reply)) { install(HttpTimeout) }
}
