package com.yll682.houche.core

/** 本站 ETA 的五种状态；位置观测不能代替本站的到站行。 */
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

    data class OutOfService(
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
 * frame 是真实成功的位置批次，arrivalFrame 是独立的本站响应，两者各有采样时刻。
 * displayFrame 仅为当前站投影，摘除其他站或失败后的 ETA；本站请求不能为旧位置续命。
 */
data class MetroRealtimeState(
    val frame: MetroRealtime? = null,
    val stationCode: String = frame?.stationCode.orEmpty(),
    val stationName: String = frame?.stationName.orEmpty(),
    val loading: Boolean = false,
    val unavailableNote: String? = null,
    val selectedDirectionKey: MetroDirectionKey? = null,
    val directionIndex: Int = 0,
    val arrivalFrame: MetroRealtime? = null,
    val outOfService: Boolean = false
) {
    val selectedDirectionIndex: Int get() {
        val directions = combinedFrame?.directions.orEmpty()
        // 首次响应前还没有可夹紧的方向集合，保留旧调用方的索引偏好。
        if (directions.isEmpty()) return directionIndex.coerceAtLeast(0)
        val byKey = selectedDirectionKey?.let { key -> directions.indexOfFirst { it.directionKey == key } }
        return byKey?.takeIf { it >= 0 } ?: directionIndex.coerceIn(0, directions.lastIndex.coerceAtLeast(0))
    }

    private val combinedFrame: MetroRealtime? = frame?.let { positions ->
        arrivalFrame?.let { arrivals -> overlayMetroRealtimeArrivals(positions, arrivals) } ?: positions
    } ?: arrivalFrame

    private val rawDirection: MetroDirection? get() = combinedFrame?.directions?.getOrNull(selectedDirectionIndex)

    /** 只取真实来源，不以请求完成、选站或错误发生时间续命。 */
    val lastSuccessAt: MetroObservationSource? get() = frame?.source
        ?: frame?.sources?.maxByOrNull { it.receivedAtElapsedMillis ?: Long.MIN_VALUE }
        ?: frame?.directions?.getOrNull(selectedDirectionIndex)?.source

    val displayFrame: MetroRealtime? get() = combinedFrame?.let { successful ->
        val directions = successful.directions.map { direction ->
            if (!outOfService && unavailableNote == null && hasStationArrivals(direction)) direction
            else direction.copy(trains = emptyList(), positionTrains = direction.allIdentifiedTrains, arrivalsAvailable = false)
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
            outOfService -> MetroEtaState.OutOfService(stationCode, sampledAt)
            unavailableNote != null -> MetroEtaState.Unavailable(stationCode, unavailableNote, sampledAt)
            loading -> MetroEtaState.Loading(stationCode, sampledAt)
            // 没查询、批次没覆盖本站、预算排队都没有发生 HTTP 失败，继续等待本站响应。
            direction == null || !hasStationArrivals(direction) -> MetroEtaState.Loading(stationCode)
            direction.trains.isEmpty() -> MetroEtaState.Empty(stationCode, sampledAt)
            else -> MetroEtaState.Ready(stationCode, direction.trains, sampledAt)
        }
    }

    private fun hasStationArrivals(direction: MetroDirection): Boolean =
        combinedFrame?.stationCode == stationCode && direction.arrivalsAvailable &&
            (direction.source ?: arrivalFrame?.source ?: frame?.source).let { it == null || it.referenceStationCode == stationCode } &&
            direction.trains.all { train ->
                val source = train.source ?: direction.source
                source == null || source.referenceStationCode == stationCode
            }

    fun positionsAt(nowElapsedMillis: Long, staleAfterMillis: Long = METRO_REALTIME_WINDOW_MILLIS): MetroPositionState {
        val sampledAt = lastSuccessAt
        val age = sampledAt?.receivedAtElapsedMillis?.let { received ->
            (nowElapsedMillis - received).takeIf { it >= 0 }
        }
        return MetroPositionState(
            frame = displayFrame,
            lastSuccessAt = sampledAt,
            ageMillis = age,
            isStale = outOfService || unavailableNote != null || age == null || age >= staleAfterMillis
        )
    }

    fun selectStation(code: String, name: String): MetroRealtimeState = copy(
        stationCode = code,
        stationName = name,
        loading = true,
        unavailableNote = null,
        outOfService = false,
        selectedDirectionKey = rawDirection?.directionKey ?: selectedDirectionKey,
        directionIndex = selectedDirectionIndex
    )

    fun selectDirection(index: Int): MetroRealtimeState {
        val directions = combinedFrame?.directions.orEmpty()
        val selected = index.coerceIn(0, directions.lastIndex.coerceAtLeast(0))
        return copy(directionIndex = selected, selectedDirectionKey = directions.getOrNull(selected)?.directionKey)
    }

    fun beginRequest(): MetroRealtimeState = copy(
        loading = unavailableNote == null && !outOfService,
        selectedDirectionKey = rawDirection?.directionKey ?: selectedDirectionKey,
        directionIndex = selectedDirectionIndex
    )

    /** 关门不改成功观测；开门先等待新响应，不能恢复昨日旧 ETA。 */
    fun setOutOfService(value: Boolean): MetroRealtimeState = if (value == outOfService) this else copy(
        outOfService = value,
        loading = !value,
        unavailableNote = null,
        arrivalFrame = null
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
            directionIndex = selected,
            arrivalFrame = null
        )
    }

    /** 本站结果立即进入 ETA；已有的位置批次及其年龄不等待、不被本站响应改写。 */
    fun completeStation(result: MetroRealtime): MetroRealtimeState {
        if (!result.online) return complete(result)
        val completed = complete(result)
        return completed.copy(frame = frame ?: completed.frame, arrivalFrame = completed.frame)
    }

    /** 广域位置批次不能结束本站等待、抹掉本站错误或覆盖刚成功的本站到站行。 */
    fun completePositions(result: MetroRealtime): MetroRealtimeState {
        if (!result.online) return this
        val previousKey = rawDirection?.directionKey ?: selectedDirectionKey
        // 即使此批覆盖本站，ETA 仍由独立本站请求负责，批次只提供位置。
        val arrivals = arrivalFrame ?: frame?.takeIf { !loading && unavailableNote == null }
        val combined = copy(frame = result, arrivalFrame = arrivals)
        val directions = combined.combinedFrame?.directions.orEmpty()
        val selected = previousKey?.let { key -> directions.indexOfFirst { it.directionKey == key } }
            ?.takeIf { it >= 0 } ?: selectedDirectionIndex.coerceIn(0, directions.lastIndex.coerceAtLeast(0))
        return combined.copy(selectedDirectionKey = directions.getOrNull(selected)?.directionKey, directionIndex = selected)
    }
}
