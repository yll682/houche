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
    val cityResolved: Boolean = false, val permissionDenied: Boolean = false, val hasMetro: Boolean = false
)
class HomeViewModel(val graph: AppGraph, val city: City) : StateViewModel<HomeData>(HomeData()) {
    private val mutex = Mutex()
    private var metroJob: Job? = null
    init {
        refreshMetro()
        viewModelScope.launch {
            attempt { combine(graph.prefs.favoriteLines, graph.prefs.favoriteStations) { lines, stations -> lines to stations }.collect { (lines, stations) ->
                val value = data.copy(favoriteLines = lines, favoriteStations = stations)
                mutable.value = when (val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); else -> UiState.Success(value) }
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
    fun locate() = action {
        if (!mutex.tryLock()) return@action
        try {
            mutable.value = UiState.Loading(data)
            val position = graph.location.locate()
            val repo = graph.repository()
            val resolution = repo.resolveCity(graph.cities, position.lat, position.lng, city.key)
            if (resolution.located) graph.setLocatedCity(resolution.city)
            if (graph.prefs.currentCityKey.first() == null && resolution.located && resolution.city.key != city.key) {
                graph.prefs.setCurrentCity(resolution.city.key)
                return@action
            }
            update(data.copy(position = position, permissionDenied = false, cityResolved = resolution.located && resolution.city.key == city.key))
            val stops = repo.nearby(city, position.lat, position.lng)
            update(data.copy(stations = stops, arrivals = emptyMap(), liveErrors = emptyMap()), stops.isEmpty())
            refreshLiveUnlocked()
        } finally { mutex.unlock() }
    }
    suspend fun refreshLive() {
        if (!mutex.tryLock()) return
        try { attempt { refreshLiveUnlocked() } } finally { mutex.unlock() }
    }
    private suspend fun refreshLiveUnlocked() = coroutineScope {
        val repo = graph.repository()
        data.stations.take(3).mapIndexed { index, station -> async {
            try {
                val arrivals = repo.stationBuses(city, station)
                update(data.copy(arrivals = data.arrivals + (index to arrivals), liveErrors = data.liveErrors - index))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.util.Log.e("Houche", "附近站点实时查询失败", error)
                update(data.copy(liveErrors = data.liveErrors + (index to (error as? BusException)?.message)))
            }
        } }.awaitAll()
        Unit
    }
}
