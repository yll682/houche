package com.yll682.houche.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.data.Position
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

 data class HomeData(
    val stations: List<Station> = emptyList(), val arrivals: Map<Int, List<Arrival>> = emptyMap(),
    val liveErrors: Map<Int, String?> = emptyMap(), val position: Position? = null,
    val favoriteLines: List<SavedLine> = emptyList(), val favoriteStations: List<SavedStation> = emptyList(),
    val cityResolved: Boolean = false, val permissionDenied: Boolean = false, val hasMetro: Boolean = false,
    val liveStates: Map<Int, StationLiveState> = emptyMap()
)
class HomeViewModel(
    val graph: AppGraph,
    val city: City,
    private val repositoryProvider: suspend () -> BusRepository = graph::repository,
    private val locatePosition: suspend () -> Position = graph.location::locate
) : StateViewModel<HomeData>(HomeData()) {
    private val mutex = Mutex()
    private var metroJob: Job? = null
    private var locateJob: Job? = null
    private var liveGeneration = 0L
    private val liveLoader = StationArrivalsLoader(query = { currentCity, station -> repositoryProvider().stationArrivals(currentCity, station) })
    init {
        refreshMetro()
        viewModelScope.launch {
            attempt { combine(graph.prefs.favoriteLines, graph.prefs.favoriteStations) { lines, stations -> lines to stations }.collect { (lines, stations) ->
                val value = data.copy(favoriteLines = lines, favoriteStations = stations)
                mutable.value = when (val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); is UiState.Empty -> old.copy(data = value); is UiState.Success -> old.copy(data = value) }
            } }
        }
    }

    /** 入口只依赖城市索引；站表或在线请求失败不应让入口消失，索引读取失败可重试。 */
    fun refreshMetro() {
        if (metroJob?.isActive == true) return
        metroJob = viewModelScope.launch {
            repeat(2) { attempt ->
                try {
                    val hasMetro = graph.metro.supports(city)
                    // 挂起读取之后再取当前状态，保留期间产生的定位、收藏和错误状态。
                    val old = mutable.value
                    val value = checkNotNull(old.data).copy(hasMetro = hasMetro)
                    mutable.value = when (old) {
                        is UiState.Error -> old.copy(data = value)
                        is UiState.Loading -> old.copy(data = value)
                        is UiState.Empty -> old.copy(data = value)
                        is UiState.Success -> old.copy(data = value)
                    }
                    return@launch
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    android.util.Log.w("Houche", "首页地铁城市索引读取失败", error)
                    if (attempt == 0) delay(250)
                }
            }
        }
    }
    fun denied() { update(data.copy(permissionDenied = true)) }
    fun locate(): Job {
        val generation = ++liveGeneration
        locateJob?.cancel()
        return viewModelScope.launch {
            try {
                mutable.value = UiState.Loading(data)
                val position = locatePosition()
                currentCoroutineContext().ensureActive()
                if (generation != liveGeneration) return@launch
                val repo = repositoryProvider()
                val resolution = repo.resolveCity(graph.cities, position.lat, position.lng, city.key)
                currentCoroutineContext().ensureActive()
                if (generation != liveGeneration) return@launch
                if (resolution.located) graph.setLocatedCity(resolution.city)
                if (graph.prefs.currentCityKey.first() == null && resolution.located && resolution.city.key != city.key) {
                    graph.prefs.setCurrentCity(resolution.city.key)
                    return@launch
                }
                update(data.copy(position = position, permissionDenied = false, cityResolved = resolution.located && resolution.city.key == city.key))
                val stops = repo.nearby(city, position.lat, position.lng)
                currentCoroutineContext().ensureActive()
                if (generation != liveGeneration) return@launch
                update(data.copy(stations = stops, arrivals = emptyMap(), liveErrors = emptyMap(),
                    liveStates = stops.indices.associateWith { StationLiveState.Loading() }), stops.isEmpty())
                refreshLiveUnlocked(generation)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == liveGeneration) report(error)
            }
        }.also { locateJob = it }
    }
    suspend fun refreshLive() {
        if (locateJob?.isActive == true || !mutex.tryLock()) return
        val generation = ++liveGeneration
        try { refreshLiveUnlocked(generation) } finally { mutex.unlock() }
    }
    private suspend fun refreshLiveUnlocked(generation: Long) {
        val stations = data.stations.toList()
        val previous = data.arrivals
        update(data.copy(liveStates = stations.indices.associateWith { StationLiveState.Loading(previous[it].orEmpty()) },
            liveErrors = emptyMap()), stations.isEmpty())
        liveLoader.loadAll(city, stations, previous) { index, result ->
            // 新定位可在旧刷新尚未完成时替换列表；旧下标不能写入新站点。
            if (generation != liveGeneration || data.stations.getOrNull(index) != stations[index]) return@loadAll
            val current = data
            val resolved = result.station.copy(distance = result.station.distance ?: stations[index].distance)
            val updatedStations = current.stations.toMutableList().also { it[index] = resolved }
            update(current.copy(stations = updatedStations,
                arrivals = current.arrivals + (index to result.state.arrivals),
                liveErrors = result.state.message?.let { current.liveErrors + (index to it) } ?: (current.liveErrors - index),
                liveStates = current.liveStates + (index to result.state)), updatedStations.isEmpty())
        }
    }
}
