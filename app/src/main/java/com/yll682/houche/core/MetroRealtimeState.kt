package com.yll682.houche.core

/** 本站 ETA 的四种状态；位置观测不能代替本站的到站行。 */
sealed interface MetroEtaState {
    val stationCode: String
    /** 只属于本站的真实成功采样；换站等待时不会借用旧站的时间。 */
    val lastSuccessAt: MetroObservationSource?

    data class Loading(
        override val stationCode: String,
        override val lastSuccessAt: MetroObservationSource? = null
    ) : MetroEtaState

    data class Empty(
        override val stationCode: String,
        override val lastSuccessAt: MetroObservationSource? = null
    ) : MetroEtaState

    data class Ready(
        override val stationCode: String,
        val trains: List<MetroTrain>,
        override val lastSuccessAt: MetroObservationSource? = null
    ) : MetroEtaState

    data class Unavailable(
        override val stationCode: String,
        val note: String,
        override val lastSuccessAt: MetroObservationSource? = null
    ) : MetroEtaState
}

/** 位置批次的年龄只使用本机单调采样时钟；未知时间不会被当成新鲜位置。 */
data class MetroPositionState(
    val frame: MetroRealtime?,
    val lastSuccessAt: MetroObservationSource?,
    val ageMillis: Long?,
    val isStale: Boolean
)

/**
 * 可独立验证的切站/方向/ETA reducer。
 * frame 永远是上一次真实成功批次；切站、失败、缓存重选都不改写其来源或采样时刻。
 * displayFrame 仅为当前站投影，保留同一批 positions，摘除其他站或失败后的 ETA。
 */
data class MetroRealtimeState(
    val frame: MetroRealtime? = null,
    val stationCode: String = frame?.stationCode.orEmpty(),
    val stationName: String = frame?.stationName.orEmpty(),
    val loading: Boolean = false,
    val unavailableNote: String? = null,
    val selectedDirectionKey: MetroDirectionKey? = null,
    val directionIndex: Int = 0
) {
    val selectedDirectionIndex: Int get() {
        val directions = frame?.directions.orEmpty()
        // 首次响应前还没有可夹紧的方向集合，保留旧调用方的索引偏好。
        if (directions.isEmpty()) return directionIndex.coerceAtLeast(0)
        val byKey = selectedDirectionKey?.let { key -> directions.indexOfFirst { it.directionKey == key } }
        return byKey?.takeIf { it >= 0 } ?: directionIndex.coerceIn(0, directions.lastIndex.coerceAtLeast(0))
    }

    private val rawDirection: MetroDirection? get() = frame?.directions?.getOrNull(selectedDirectionIndex)

    /** 只取真实来源，不以请求完成、选站或错误发生时间续命。 */
    val lastSuccessAt: MetroObservationSource? get() = frame?.source
        ?: frame?.sources?.maxByOrNull { it.receivedAtElapsedMillis ?: Long.MIN_VALUE }
        ?: rawDirection?.source

    val displayFrame: MetroRealtime? get() = frame?.let { successful ->
        val directions = successful.directions.map { direction ->
            if (unavailableNote == null && hasStationArrivals(direction)) direction
            else direction.copy(trains = emptyList(), positionTrains = direction.positions, arrivalsAvailable = false)
        }
        if (successful.stationCode == stationCode && successful.stationName == stationName && directions == successful.directions) successful
        else successful.copy(stationCode = stationCode, stationName = stationName, directions = directions)
    }

    val direction: MetroDirection? get() = displayFrame?.directions?.getOrNull(selectedDirectionIndex)

    val etaState: MetroEtaState get() {
        val direction = rawDirection
        val sampledAt = direction?.takeIf(::hasStationArrivals)?.let { current ->
            (current.source ?: frame?.source)?.takeIf { it.referenceStationCode == stationCode }
        }
        return when {
            loading -> MetroEtaState.Loading(stationCode, sampledAt)
            unavailableNote != null -> MetroEtaState.Unavailable(stationCode, unavailableNote, sampledAt)
            direction == null || !hasStationArrivals(direction) -> MetroEtaState.Unavailable(stationCode, "本站到站暂不可用")
            direction.trains.isEmpty() -> MetroEtaState.Empty(stationCode, sampledAt)
            else -> MetroEtaState.Ready(stationCode, direction.trains, sampledAt)
        }
    }

    private fun hasStationArrivals(direction: MetroDirection): Boolean =
        frame?.stationCode == stationCode && direction.arrivalsAvailable &&
            (direction.source ?: frame?.source).let { it == null || it.referenceStationCode == stationCode } &&
            direction.trains.all { train ->
                val source = train.source ?: direction.source
                source == null || source.referenceStationCode == stationCode
            }

    fun positionsAt(nowElapsedMillis: Long): MetroPositionState {
        val sampledAt = lastSuccessAt
        val age = sampledAt?.receivedAtElapsedMillis?.let { received ->
            (nowElapsedMillis - received).takeIf { it >= 0 }
        }
        return MetroPositionState(
            frame = displayFrame,
            lastSuccessAt = sampledAt,
            ageMillis = age,
            isStale = unavailableNote != null || age == null || age >= METRO_REALTIME_WINDOW_MILLIS
        )
    }

    fun selectStation(code: String, name: String): MetroRealtimeState = copy(
        stationCode = code,
        stationName = name,
        loading = true,
        selectedDirectionKey = rawDirection?.directionKey ?: selectedDirectionKey,
        directionIndex = selectedDirectionIndex
    )

    fun selectDirection(index: Int): MetroRealtimeState {
        val directions = frame?.directions.orEmpty()
        val selected = index.coerceIn(0, directions.lastIndex.coerceAtLeast(0))
        return copy(directionIndex = selected, selectedDirectionKey = directions.getOrNull(selected)?.directionKey)
    }

    fun beginRequest(): MetroRealtimeState = copy(
        loading = true,
        selectedDirectionKey = rawDirection?.directionKey ?: selectedDirectionKey,
        directionIndex = selectedDirectionIndex
    )

    fun complete(result: MetroRealtime): MetroRealtimeState {
        if (!result.online) return copy(loading = false, unavailableNote = result.note.ifBlank { "本站到站暂不可用" })
        val previousKey = rawDirection?.directionKey ?: selectedDirectionKey
        val previousIndex = selectedDirectionIndex
        val station = result.stationCode.ifBlank { stationCode }
        val normalized = result.copy(stationCode = station, stationName = result.stationName.ifBlank { stationName.takeIf { station == stationCode }.orEmpty() })
        val selected = previousKey?.let { key -> normalized.directions.indexOfFirst { it.directionKey == key } }
            ?.takeIf { it >= 0 } ?: previousIndex.coerceIn(0, normalized.directions.lastIndex.coerceAtLeast(0))
        return copy(
            frame = normalized,
            stationCode = normalized.stationCode,
            stationName = normalized.stationName,
            loading = false,
            unavailableNote = null,
            selectedDirectionKey = normalized.directions.getOrNull(selected)?.directionKey,
            directionIndex = selected
        )
    }
}
