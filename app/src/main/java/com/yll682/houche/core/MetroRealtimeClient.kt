package com.yll682.houche.core

import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.logging.Level

private const val METRO_REALTIME_PATH = "pub/transTime/getTransTimes"

/** 有限参考站的整批实时快照；同线路二十秒内共用同一批，不跨批续接旧位置。 */
class MetroRealtimeRemote(
    private val http: HttpClient = BusNetwork.client,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val lineTimeoutMillis: Long = MetroGateway.TIMEOUT_MILLIS,
    private val budgetDelay: suspend (Long) -> Unit = { delay(it) }
) {
    private data class LineFrame(val startedAt: Long, val snapshots: List<MetroRealtime>)
    private class LineRequest(val startedAt: Long, val plan: MetroRealtimePlan) {
        lateinit var task: Deferred<LineFrame>
        var waiters = 0
        var frame: LineFrame? = null
        val completed = mutableMapOf<String, MetroRealtime>()
    }

    private val lock = Any()
    private val lines = mutableMapOf<String, LineRequest>()
    private val starts = mutableMapOf<String, ArrayDeque<Long>>()
    // 独立任务只在仍有调用方等待时存活；最后一个调用方取消会向 HTTP 传播取消。
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(METRO_REALTIME_MAX_CONCURRENCY)

    /** 单站兼容接口，保留原有一次临时故障重试；页面刷新使用 latestLine 的严格总预算。 */
    suspend fun latest(lineCode: String, stationCode: String = ""): MetroRealtime = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(MetroGateway.TIMEOUT_MILLIS) { request(lineCode, stationCode) }
            ?: unavailable("地铁实时到站请求超时", lineCode, stationCode)
        logger.log(Level.FINE, result.note)
        result
    }

    /**
     * 一批最多八个物理请求、并发最多二；失败不重试，整批八秒截止后保留已成功的参考站。
     * 同线路换站只重选这批数据里的 ETA，未采到的站明确无 ETA，不额外发第九个请求。
     */
    suspend fun latestLine(lineCode: String, stationCodes: List<String>, stationCode: String): MetroRealtime =
        withContext(Dispatchers.IO) {
            val plan = planMetroRealtimeReferences(stationCodes, stationCode)
            if (lineCode.isBlank() || plan.referenceStationCodes.isEmpty()) {
                return@withContext unavailable("线路没有可查询的参考站", lineCode, plan.selectedStationCode)
            }
            val frame = lineFrame(lineCode, plan)
            // 等待共享任务期间也可能跨过 TTL；过期结果绝不重新标成新的一批。
            if (!fresh(frame.startedAt, elapsedRealtimeMillis())) {
                return@withContext unavailable("地铁实时快照已过期", lineCode, plan.selectedStationCode)
            }
            mergeMetroRealtime(frame.snapshots, plan.selectedStationCode, lineCode)
        }

    private fun fresh(startedAt: Long, now: Long): Boolean = now - startedAt in 0 until METRO_REALTIME_WINDOW_MILLIS

    private suspend fun lineFrame(lineCode: String, plan: MetroRealtimePlan): LineFrame {
        val entry = synchronized(lock) {
            val now = elapsedRealtimeMillis()
            val previous = lines[lineCode]
            // 完整同批复用，不把旧参考站和新参考站拼成看似同时观测的车队。
            if (previous != null && fresh(previous.startedAt, now)) {
                previous.frame?.let { return it }
                previous.waiters++
                previous
            } else {
                LineRequest(now, plan).also { created ->
                    created.waiters = 1
                    created.task = requests.async(start = CoroutineStart.LAZY) { collectFrame(lineCode, created) }
                    lines[lineCode] = created
                }
            }
        }
        try {
            entry.task.start()
            return entry.task.await()
        } finally {
            synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.task.isCompleted) {
                    // 取消也占用本窗口，避免反复离开/进入页面突破每线八次的预算。
                    entry.frame = frameOf(lineCode, entry, "请求已取消")
                    entry.task.cancel()
                }
            }
        }
    }

    private suspend fun collectFrame(lineCode: String, entry: LineRequest): LineFrame {
        withTimeoutOrNull(lineTimeoutMillis) {
            coroutineScope {
                entry.plan.referenceStationCodes.map { reference ->
                    // 依计划顺序先抢 permit，确保慢网时本站 ETA 不会排在广域锚点之后。
                    async(start = CoroutineStart.UNDISPATCHED) {
                        val result = permits.withPermit {
                            awaitBudget(lineCode)
                            val response = request(lineCode, reference, retry = false)
                            currentCoroutineContext().ensureActive()
                            received(response)
                        }
                        synchronized(lock) { entry.completed[reference] = result }
                    }
                }.awaitAll()
            }
        }
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            frameOf(lineCode, entry, "地铁实时到站请求超时").also { entry.frame = it }
        }
    }

    /** 每个物理请求发出前占位；错峰批次也必须遵守任意滚动二十秒内至多八次。 */
    private suspend fun awaitBudget(lineCode: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val waitMillis = synchronized(lock) {
                val now = elapsedRealtimeMillis()
                val sent = starts.getOrPut(lineCode) { ArrayDeque() }
                while (sent.isNotEmpty() && now - sent.first >= METRO_REALTIME_WINDOW_MILLIS) sent.removeFirst()
                if (sent.size < METRO_REALTIME_MAX_REFERENCES) {
                    sent.addLast(now)
                    0L
                } else {
                    (METRO_REALTIME_WINDOW_MILLIS - (now - sent.first)).coerceAtLeast(1)
                }
            }
            if (waitMillis == 0L) return
            // 不持锁等待；取消会退出等待并归还外层 permit，不会偷偷补发请求。
            budgetDelay(waitMillis)
        }
    }

    /** 仅在持锁时读取；未完成的站以失败占位，已经成功的站不会被整批超时抹掉。 */
    private fun frameOf(lineCode: String, entry: LineRequest, missingNote: String): LineFrame = LineFrame(
        entry.startedAt,
        entry.plan.referenceStationCodes.map { reference ->
            entry.completed[reference] ?: unavailable(missingNote, lineCode, reference)
        }
    )

    private fun received(result: MetroRealtime): MetroRealtime {
        if (!result.online) return result
        val elapsed = elapsedRealtimeMillis()
        val epoch = wallClockMillis()
        fun stamp(source: MetroObservationSource?) = source?.copy(
            receivedAtElapsedMillis = elapsed,
            receivedAtEpochMillis = epoch
        )
        return result.copy(
            source = stamp(result.source),
            directions = result.directions.map { direction ->
                direction.copy(
                    source = stamp(direction.source),
                    trains = direction.trains.map { it.copy(source = stamp(it.source)) }
                )
            }
        )
    }

    private suspend fun request(lineCode: String, stationCode: String, retry: Boolean = true): MetroRealtime {
        val query = buildMap {
            put("lineCode", lineCode)
            if (stationCode.isNotEmpty()) put("stationCode", stationCode)
        }
        // 仅 legacy 单站接口允许重试；latestLine 显式关闭，失败也计入滚动请求预算。
        for (attempt in 0..(if (retry) 1 else 0)) {
            try {
                val response = MetroGateway.post(http, METRO_REALTIME_PATH, query)
                val status = response.status.value
                if (status !in 200..299) {
                    if (retry && attempt == 0 && (status == 429 || status >= 500)) continue
                    return unavailable("地铁实时接口返回 HTTP $status", lineCode, stationCode)
                }
                return parse(MetroGateway.responseText(response), lineCode, stationCode)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (retry && attempt == 0 && (error is IOException || error is HttpRequestTimeoutException)) continue
                logger.log(Level.FINE, "地铁实时到站请求失败", error)
                return unavailable("地铁实时到站请求失败：${error.javaClass.simpleName}", lineCode, stationCode)
            }
        }
        error("无法到达的请求分支")
    }

    /** 外层可能只有密文；内层必须显式成功，有方向但暂时没有列车仍是有效结果。 */
    internal fun parse(text: String, lineCode: String = "", stationCode: String = ""): MetroRealtime {
        val outer = runCatching { BusNetwork.json.parseToJsonElement(text) }.getOrNull()
            ?: return unavailable("响应不是合法 JSON", lineCode, stationCode)
        if (outer is JsonObject && "code" in outer && outer.i("code") != 0) return rejected(outer, "网关拒绝", lineCode, stationCode)
        val encrypted = outer.s("responseData")
        if (encrypted.isBlank()) return unavailable("响应没有数据体", lineCode, stationCode)
        val plain = MetroGateway.decrypt(encrypted) ?: return unavailable("响应数据解密失败", lineCode, stationCode)
        val inner = runCatching { BusNetwork.json.parseToJsonElement(plain) }.getOrNull()
            ?: return unavailable("解密后的数据不是合法 JSON", lineCode, stationCode)
        if (inner.i("code") != 0) return rejected(inner, "实时接口报错", lineCode, stationCode)
        val entries = inner.list("data")
        val serverTime = inner.s("ts").toLongOrNull()
        val directions = entries.mapNotNull { parseDirection(it, stationCode, serverTime) }
        if (directions.isEmpty()) return unavailable("接口未返回可识别的行车方向", lineCode, stationCode)
        val station = entries.firstOrNull { it.s("stationCode").isNotBlank() }
        return MetroRealtime(
            online = true,
            note = "实时到站 ${directions.size} 个方向",
            lineCode = lineCode,
            stationCode = station?.s("stationCode") ?: stationCode,
            stationName = station?.s("stationName").orEmpty(),
            directions = directions,
            source = directions.firstOrNull()?.source
        )
    }

    private fun parseDirection(entry: JsonElement, stationCode: String, serverTime: Long?): MetroDirection? {
        val stations = entry.list("wayStations")
        val origin = stations.firstOrNull()?.s("name").orEmpty()
        val destination = stations.lastOrNull()?.s("name").orEmpty()
        if (origin.isBlank() || destination.isBlank()) return null
        val source = MetroObservationSource(entry.s("stationCode").ifBlank { stationCode }, serverEpochMillis = serverTime)
        return MetroDirection(
            origin = origin,
            destination = destination,
            startTime = entry.s("transStartTime"),
            endTime = entry.s("transEndTime"),
            delayMinutes = entry.i("arriveTimeDelay") ?: 0,
            trains = entry.list("transTimes").mapNotNull { parseTrain(it, source) },
            // 缺码时保留空位，否则删除站点会改变剩余站数的下标差。
            stationCodes = stations.map { it.s("stationCode") },
            source = source
        )
    }

    private fun parseTrain(entry: JsonElement, source: MetroObservationSource): MetroTrain? {
        val seconds = entry.i("arriveTime") ?: return null
        val scale = entry.d("arriveNextStationScale")
        return MetroTrain(
            arriveSeconds = seconds,
            nextStationCode = entry.s("arriveNextStationCode"),
            nextStationName = entry.s("arriveNextStationName"),
            scale = scale ?: 0.0,
            arrivingFlag = entry.i("arrivingFlag"),
            specialPosition = entry.i("specialPosition") ?: 0,
            specialPositionDesc = entry.s("specialPositionDesc"),
            source = source,
            scaleKnown = scale != null && scale in 0.0..1.0
        )
    }

    private fun rejected(root: JsonElement, layer: String, lineCode: String, stationCode: String): MetroRealtime = unavailable(
        "$layer：" + (root.i("code")?.let { "$it ${root.s("msg").ifBlank { "未知错误" }}" } ?: "响应状态码无效"),
        lineCode,
        stationCode
    )

    private fun unavailable(note: String, lineCode: String, stationCode: String): MetroRealtime =
        MetroRealtime(online = false, note = note, lineCode = lineCode, stationCode = stationCode)
}
