package com.yll682.houche.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BusCacheStatsTest {
    private val city = City("11", "厦门市", "0592")
    private val response = """{"success":true,"code":0,"data":{"id":1,"name":"测试线路","stationNo":"A","stationList":[]}}"""
    private fun client(reply: suspend () -> String = { response }) = HttpClient(MockEngine {
        respond(reply(), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    })
    private fun repository(http: HttpClient, now: AtomicLong = AtomicLong()) =
        BusRepository(XmbusClient("0123456789abcdef0123456789abcdef", http), ZsgjClient(http), now::get)

    @Test fun `空缓存年龄与清除数量为零容量固定512`() {
        client().use { http ->
            val repo = repository(http, AtomicLong(-9_000_000_000L))
            assertEquals(CacheStats(0, 0L, 512), repo.stats())
            assertEquals(0, repo.cacheEntries.value)
            assertEquals(0, repo.clearCache())
            assertEquals(CacheStats(0, 0L, 512), repo.stats())
        }
    }

    @Test fun `命中不增加计数或续期年龄按纳秒差转换为毫秒`() = runBlocking {
        val calls = AtomicInteger()
        val now = AtomicLong(-9_000_000_000L)
        client { calls.incrementAndGet(); response }.use { http ->
            val repo = repository(http, now)
            val first = repo.lineDetail(city, "1路", "1")
            assertEquals(CacheStats(1, 0L, 512), repo.stats())
            now.addAndGet(2_000_999_999L)
            repo.lineDetail(city, "2路", "1")
            now.addAndGet(1_000_000_000L)
            assertEquals(first, repo.lineDetail(city, "1路", "1"))
            val snapshot = repo.stats()
            assertEquals(CacheStats(2, 3_000L, 512), snapshot)
            assertEquals(2, repo.cacheEntries.value)
            assertEquals(2, calls.get())
            now.incrementAndGet()
            assertEquals(CacheStats(2, 3_001L, 512), repo.stats())
            assertEquals(CacheStats(2, 3_000L, 512), snapshot)
            assertEquals(2, repo.clearCache())
            assertEquals(0, repo.cacheEntries.value)
            assertEquals(CacheStats(0, 0L, 512), repo.stats())
            assertEquals(0, repo.clearCache())
        }
    }

    @Test fun `单调纳秒钟跨越Long边界仍得到正确年龄`() = runBlocking {
        val now = AtomicLong(Long.MAX_VALUE - 500_000L)
        client().use { http ->
            val repo = repository(http, now)
            repo.lineDetail(city, "1路", "1")
            now.addAndGet(1_999_999L)
            assertEquals(CacheStats(1, 1L, 512), repo.stats())
            now.incrementAndGet()
            assertEquals(CacheStats(1, 2L, 512), repo.stats())
        }
    }

    @Test fun `实时条目在15秒边界替换后计数不增加年龄重置`() = runBlocking {
        val calls = AtomicInteger()
        val now = AtomicLong()
        client { calls.incrementAndGet(); response }.use { http ->
            val repo = repository(http, now)
            repo.lineDetail(city, "1路", "1")
            now.set(14_999_999_999L)
            repo.lineDetail(city, "1路", "1")
            assertEquals(1, calls.get())
            assertEquals(CacheStats(1, 14_999L, 512), repo.stats())
            now.incrementAndGet()
            repo.lineDetail(city, "1路", "1")
            assertEquals(2, calls.get())
            assertEquals(CacheStats(1, 0L, 512), repo.stats())
            assertEquals(1, repo.cacheEntries.value)
        }
    }

    @Test fun `站点资料仍按300秒边界替换且统计不会清理缓存`() = runBlocking {
        val calls = AtomicInteger()
        val now = AtomicLong()
        client { calls.incrementAndGet(); response }.use { http ->
            val repo = repository(http, now)
            repo.stationDetail(city, "测试站", "A")
            now.set(299_999_999_999L)
            assertEquals(CacheStats(1, 299_999L, 512), repo.stats())
            repo.stationDetail(city, "测试站", "A")
            assertEquals(1, calls.get())
            now.incrementAndGet()
            assertEquals(CacheStats(1, 300_000L, 512), repo.stats())
            repo.stationDetail(city, "测试站", "A")
            assertEquals(2, calls.get())
            assertEquals(CacheStats(1, 0L, 512), repo.stats())
        }
    }

    @Test fun `容量淘汰遵循LRU且读取统计不改变顺序`() = runBlocking {
        val calls = AtomicInteger()
        val now = AtomicLong()
        client { calls.incrementAndGet(); response }.use { http ->
            val repo = repository(http, now)
            repeat(512) { index ->
                now.set(index * 1_000_000L)
                repo.lineDetail(city, "$index", "1")
            }
            now.set(600_000_000L)
            repo.lineDetail(city, "0", "1")
            assertEquals(CacheStats(512, 600L, 512), repo.stats())
            now.set(601_000_000L)
            repo.lineDetail(city, "512", "1")
            assertEquals(CacheStats(512, 601L, 512), repo.stats())
            assertEquals(512, repo.cacheEntries.value)
            assertEquals(513, calls.get())
            repo.lineDetail(city, "0", "1")
            assertEquals(513, calls.get())
            repo.lineDetail(city, "1", "1")
            assertEquals(514, calls.get())
            assertEquals(CacheStats(512, 601L, 512), repo.stats())
            assertEquals(512, repo.clearCache())
            assertEquals(CacheStats(0, 0L, 512), repo.stats())
        }
    }

    @Test fun `进行中请求不计数且年龄从完成时开始`() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val now = AtomicLong()
            client {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                response
            }.use { http ->
                val repo = repository(http, now)
                val first = async { repo.lineDetail(city, "1路", "1") }
                entered.await()
                val second = async { repo.lineDetail(city, "1路", "1") }
                now.set(7_000_000_000L)
                assertEquals(CacheStats(0, 0L, 512), repo.stats())
                assertEquals(0, repo.clearCache())
                release.complete(Unit)
                assertEquals(first.await(), second.await())
                assertEquals(1, calls.get())
                assertEquals(CacheStats(1, 0L, 512), repo.stats())
                now.addAndGet(1_000_000_000L)
                assertEquals(CacheStats(1, 1_000L, 512), repo.stats())
                assertEquals(1, repo.clearCache())
            }
        }
    }

    @Test fun `失败响应不占缓存条目`() = runBlocking {
        client { """{"success":false,"code":500}""" }.use { http ->
            val repo = repository(http)
            repeat(2) {
                try { repo.lineDetail(city, "1路", "1"); fail("失败响应不能作为成功返回") }
                catch (error: BusException) { assertEquals(BusException.Kind.UPSTREAM, error.kind) }
                assertEquals(CacheStats(0, 0L, 512), repo.stats())
                assertEquals(0, repo.cacheEntries.value)
                assertEquals(0, repo.clearCache())
            }
        }
    }

    @Test fun `并发写入统计与清除不丢计数`() = runBlocking {
        withTimeout(10_000) {
            val calls = AtomicInteger()
            val cleared = AtomicInteger()
            client { calls.incrementAndGet(); response }.use { http ->
                val repo = repository(http)
                val start = CompletableDeferred<Unit>()
                val writers = (0..3).map { worker -> async(Dispatchers.Default) {
                    start.await()
                    repeat(100) { repo.lineDetail(city, "$worker-$it", "1") }
                } }
                val cleaner = launch(Dispatchers.Default) {
                    start.await()
                    repeat(100) { cleared.addAndGet(repo.clearCache()); yield() }
                }
                val reader = launch(Dispatchers.Default) {
                    start.await()
                    repeat(500) {
                        val snapshot = repo.stats()
                        assertTrue(snapshot.entries in 0..400)
                        assertEquals(0L, snapshot.oldestAgeMillis)
                        assertEquals(512, snapshot.capacity)
                        yield()
                    }
                }
                start.complete(Unit)
                writers.awaitAll()
                cleaner.join()
                reader.join()
                assertEquals(400, calls.get())
                assertEquals(repo.stats().entries, repo.cacheEntries.value)
                cleared.addAndGet(repo.clearCache())
                assertEquals(400, cleared.get())
                assertEquals(CacheStats(0, 0L, 512), repo.stats())
                assertEquals(0, repo.cacheEntries.value)
                assertEquals(0, repo.clearCache())
            }
        }
    }
}
