package com.yll682.houche.ui.screens.line

import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

 data class LineData(val route: Route? = null, val selected: RouteStation? = null, val live: VehicleResults? = null, val arrivals: List<Arrival> = emptyList(), val favorite: Boolean = false, val preview: RouteStation? = null, val previewArrivals: List<Arrival> = emptyList(), val previewLoading: Boolean = false, val previewError: String? = null, val previewFailed: Boolean = false)
class LineViewModel(private val graph: AppGraph, val city: City, private val summary: LineSummary, private val boarding: Station?) : StateViewModel<LineData>(LineData()) {
    private var direction = summary.direction.ifBlank { "1" }
    private var job: Job? = null
    private val mutex = Mutex()
    private var previewJob: Job? = null
    init {
        load()
        viewModelScope.launch { attempt { graph.prefs.favoriteLines.collect { list -> updateFavorite(list) } } }
    }
    private fun updateFavorite(list: List<SavedLine>) {
        val value = data.copy(favorite = list.any { it.city.key == city.key && it.line.name == summary.name && it.line.direction == direction })
        mutable.value = when(val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); else -> UiState.Success(value) }
    }
    fun load(reverse: Boolean = false) {
        job?.cancel()
        closePreview()
        if (reverse) direction = if (direction == "1") "2" else "1"
        job = action {
            mutable.value = UiState.Loading(data)
            val route = graph.repository().lineDetail(city, summary.name, direction)
            val selected = if (reverse) null else (data.selected?.let { old -> route.stations.firstOrNull { it.name == old.name } } ?: route.stations.firstOrNull { it.name == boarding?.name } ?: route.stations.firstOrNull { it.order == summary.stationOrder })
            update(LineData(route = route, selected = selected, favorite = graph.prefs.favoriteLines.first().any { it.city.key == city.key && it.line.name == summary.name && it.line.direction == direction }), route.stations.isEmpty())
            // 进页即取车辆：先选站才看车会让「车在哪」这件事多一步。
            refreshLive()
        }
    }
    fun select(station: RouteStation) {
        job?.cancel()
        // 车辆列表是全线的，选站只影响「还有几站」这类相对信息，不必清空。
        update(data.copy(selected = station, arrivals = emptyList()))
        job = action { refreshLive() }
    }
    suspend fun refreshLive() {
        val route = data.route ?: return
        if (route.stations.isEmpty()) return
        val station = data.selected
        if (!mutex.tryLock()) return
        try { attempt {
            val repo = graph.repository()
            coroutineScope {
                val vehicles = async { repo.vehicles(city, route, station) }
                val arrivals = async { if (station != null && city.isXiamen) repo.arrivals(city, route.name, route.direction, station.order ?: 0) else emptyList() }
                val live = vehicles.await()
                // 到站结论只对具体的乘车站成立：没选站时不拿终点站的到站资料充数。
                val estimates = when {
                    station == null -> emptyList()
                    city.isXiamen -> arrivals.await()
                    else -> live.arrivals
                }
                if (data.route == route && data.selected == station) update(data.copy(live = live, arrivals = estimates))
            }
        } } finally { mutex.unlock() }
    }
    fun refresh() = action { refreshLive() }
    fun retry() { if (data.route == null || data.route?.direction != direction) load() else refresh() }
    fun preview(station: RouteStation) {
        previewJob?.cancel()
        update(data.copy(preview = station, previewArrivals = emptyList(), previewLoading = true, previewFailed = false, previewError = null))
        previewJob = viewModelScope.launch {
            try {
                val route = data.route ?: return@launch
                val order = station.order ?: throw IllegalArgumentException()
                val arrivals = graph.repository().arrivals(city, route.name, route.direction, order)
                update(data.copy(previewArrivals = arrivals, previewLoading = false))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.util.Log.e("Houche", "站点到站预览失败", error)
                update(data.copy(previewLoading = false, previewFailed = true, previewError = (error as? BusException)?.message))
            }
        }
    }
    fun closePreview() { previewJob?.cancel(); update(data.copy(preview = null, previewLoading = false)) }

    fun favorite() = action {
        val route = data.route ?: return@action
        graph.prefs.toggleLine(SavedLine(city, LineSummary(route.name, route.direction, route.from, route.to, data.selected?.order)))
    }
}
