package com.yll682.houche.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 实时查询的结果状态；没有完成的请求和查询失败都不能算作「没有到站资料」。 */
sealed interface StationLiveState {
    val arrivals: List<Arrival>
    val errors: List<String> get() = emptyList()
    val unreportedLines: List<StationArrivalGap> get() = emptyList()
    val message: String? get() = errors.takeIf { it.isNotEmpty() }?.joinToString("；")

    data class Loading(override val arrivals: List<Arrival> = emptyList()) : StationLiveState
    data class Ready(
        override val arrivals: List<Arrival>,
        override val unreportedLines: List<StationArrivalGap> = emptyList()
    ) : StationLiveState
    data object Empty : StationLiveState { override val arrivals: List<Arrival> = emptyList() }
    data class Partial(
        override val arrivals: List<Arrival>,
        override val errors: List<String>,
        override val unreportedLines: List<StationArrivalGap> = emptyList()
    ) : StationLiveState
    data class Failed(
        override val errors: List<String>,
        override val arrivals: List<Arrival> = emptyList(),
        override val unreportedLines: List<StationArrivalGap> = emptyList()
    ) : StationLiveState
}

data class StationLiveResult(val station: Station, val state: StationLiveState)

/** 首页和详情使用同一条解析路径；批量查询只限制并发数，不截断站点列表。 */
class StationArrivalsLoader(
    private val query: suspend (City, Station) -> StationArrivals,
    parallelism: Int = 3
) {
    private val permits = Semaphore(parallelism)

    suspend fun load(city: City, station: Station, previous: List<Arrival> = emptyList()): StationLiveResult {
        return try {
            val result = query(city, station)
            currentCoroutineContext().ensureActive()
            val state = when (result.status) {
                StationArrivalsStatus.READY -> StationLiveState.Ready(result.arrivals, result.unreportedLines)
                StationArrivalsStatus.EMPTY -> if (result.unreportedLines.isEmpty()) StationLiveState.Empty
                    else StationLiveState.Partial(result.arrivals, result.errors, result.unreportedLines)
                StationArrivalsStatus.PARTIAL -> StationLiveState.Partial(result.arrivals, result.errors, result.unreportedLines)
                StationArrivalsStatus.UNAVAILABLE -> StationLiveState.Failed(result.errors.ifEmpty { listOf("到站查询失败，请稍后重试") }, previous, result.unreportedLines)
            }
            StationLiveResult(result.station, state)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            StationLiveResult(station, StationLiveState.Failed(listOf((error as? BusException)?.message ?: "到站查询失败，请稍后重试"), previous))
        }
    }

    suspend fun loadAll(
        city: City,
        stations: List<Station>,
        previous: Map<Int, List<Arrival>> = emptyMap(),
        completed: suspend (Int, StationLiveResult) -> Unit
    ) = coroutineScope {
        stations.mapIndexed { index, station ->
            async {
                permits.withPermit {
                    val result = load(city, station, previous[index].orEmpty())
                    currentCoroutineContext().ensureActive()
                    completed(index, result)
                }
            }
        }.awaitAll()
        Unit
    }
}
