package com.yll682.houche.core

import java.util.ArrayDeque
import kotlin.math.abs

internal const val METRO_FOREGROUND_MIN_INTERVAL_MILLIS = 2_000L
internal const val METRO_FOREGROUND_MAX_PER_WINDOW = 10
internal const val METRO_FOREGROUND_MINUTE_MILLIS = 60_000L
internal const val METRO_FOREGROUND_MAX_PER_MINUTE = 30

/** Deferred 没有发送 HTTP，调用方保持 Loading，等待后只重查当前选站。 */
sealed interface MetroRealtimeRequestResult {
    data class Completed(val realtime: MetroRealtime) : MetroRealtimeRequestResult
    data class Deferred(val retryAfterMillis: Long) : MetroRealtimeRequestResult
}

/** 仅在真正启动一次 HTTP 时记账；由 remote 的锁保护，等待和去重不消耗额度。 */
internal class MetroForegroundRequestBudget {
    private val starts = ArrayDeque<Long>()

    fun retryAfterMillis(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.first >= METRO_FOREGROUND_MINUTE_MILLIS) starts.removeFirst()
        val recent = starts.filter { now - it < METRO_REALTIME_WINDOW_MILLIS }
        return maxOf(
            starts.lastOrNull()?.let { METRO_FOREGROUND_MIN_INTERVAL_MILLIS - (now - it) } ?: 0L,
            if (recent.size >= METRO_FOREGROUND_MAX_PER_WINDOW) METRO_REALTIME_WINDOW_MILLIS - (now - recent.first()) else 0L,
            if (starts.size >= METRO_FOREGROUND_MAX_PER_MINUTE) METRO_FOREGROUND_MINUTE_MILLIS - (now - starts.first) else 0L
        ).coerceAtLeast(0L)
    }

    fun recordStart(now: Long) {
        check(retryAfterMillis(now) == 0L) { "本站请求尚未获得额度" }
        starts.addLast(now)
    }
}

/**
 * 本站新到站行叠加到已有位置，不把两个时间批次送回跨参考站 matcher。
 * 原位置 frame 的身份、来源和年龄不变。此返回值只是当前站的显示投影：
 * 只有原始观测精确相同才关联旧身份，其余本站行用自身真实位置作局部锚点。
 * 旧的可见位置遇到新锚点退让，投影数量受原批身份数约束，不宣称跨时新增了车辆。
 */
fun overlayMetroRealtimeArrivals(positions: MetroRealtime, arrivals: MetroRealtime): MetroRealtime {
    val sameLine = positions.lineCode.isBlank() || arrivals.lineCode == positions.lineCode
    val available = arrivals.online && sameLine
    fun stationRows(direction: MetroDirection): List<MetroTrain> = direction.trains.filter {
        val source = it.source ?: direction.source ?: arrivals.source
        source == null || source.referenceStationCode == arrivals.stationCode
    }.take(3)
    val directions = positions.directions.map { old ->
        val key = old.directionKey
        val current = if (available && key != null) arrivals.directions.singleOrNull { it.directionKey == key } else null
        val rows = current?.let(::stationRows).orEmpty()
        val original = old.allIdentifiedTrains
        val projected = old.copy(trains = rows)
        val anchors = rows.mapIndexed { index, row ->
            original.filter { projected.arrivalRowOrderOf(it) == index + 1 }.singleOrNull() ?: row
        }
        val retained = old.positionPresentations().map { it.train }.filter { candidate ->
            anchors.none { anchor ->
                anchor === candidate || abs(old.positionOf(anchor) - old.positionOf(candidate)) + 1e-9 < 1.0
            }
        }
        val projection = if (current == null) original else
            anchors + retained.take((maxOf(original.size, rows.size) - anchors.size).coerceAtLeast(0))
        old.copy(
            trains = rows,
            source = current?.source ?: arrivals.source ?: old.source,
            positionTrains = projection,
            arrivalsAvailable = current != null && current.arrivalsAvailable
        )
    }
    val positionKeys = positions.directions.mapNotNull { it.directionKey }.toSet()
    val additions = if (!available) emptyList() else arrivals.directions.mapNotNull { direction ->
        val key = direction.directionKey ?: return@mapNotNull null
        if (key in positionKeys || !direction.arrivalsAvailable ||
            arrivals.directions.count { it.directionKey == key } != 1) return@mapNotNull null
        val rows = stationRows(direction)
        // 旧批次没采到的方向只展示本站的真实局部观测，不借另一个方向的位置。
        direction.copy(trains = rows, positionTrains = rows, source = direction.source ?: arrivals.source)
    }
    return positions.copy(
        stationCode = arrivals.stationCode,
        stationName = arrivals.stationName,
        note = if (available) arrivals.note else arrivals.note.ifBlank { "本站到站暂不可用" },
        directions = directions + additions
    )
}
