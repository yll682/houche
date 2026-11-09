package com.yll682.houche.ui.screens.station

import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

data class StationData(
    val station: Station,
    val arrivals: List<Arrival> = emptyList(),
    val favorite: Boolean = false,
    val metro: List<MetroCalling> = emptyList(),
    val liveState: StationLiveState = StationLiveState.Loading(),
    val platforms: List<Station> = initialPlatforms(station),
    val selectedPlatform: Int = 0
)
private fun initialPlatforms(station: Station): List<Station> {
    val result = mutableListOf(station.copy(platforms = emptyList()))
    station.platformNumbers.filter { it.isNotBlank() && it != station.number }.distinct().forEach { number ->
        result += station.platforms.firstOrNull { it.number == number }
            ?: Station(station.name, number = number, coordinateSystem = station.coordinateSystem)
    }
    station.platforms.forEach { platform ->
        if (result.none { (platform.number != null && it.number == platform.number) ||
                (platform.number == null && it.lat == platform.lat && it.lng == platform.lng) }) result += platform.copy(platforms = emptyList())
    }
    return result
}
class StationViewModel(
    private val graph: AppGraph,
    val city: City,
    initial: Station,
    private val repositoryProvider: suspend () -> BusRepository = graph::repository
) : StateViewModel<StationData>(StationData(initial)) {
    private var job: Job? = null
    private val mutex = Mutex()
    private var liveGeneration = 0L
    private val liveLoader = StationArrivalsLoader(query = { currentCity, station -> repositoryProvider().stationPlatformArrivals(currentCity, station) })
    init {
        load(initial)
        viewModelScope.launch { attempt { graph.prefs.favoriteStations.collect { list ->
            val value = data.copy(favorite = list.any { it.city.key == city.key && same(it.station, data.station) })
            mutable.value = when (val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); is UiState.Empty -> old.copy(data = value); is UiState.Success -> old.copy(data = value) }
        } } }
    }
    private fun same(a: Station, b: Station) = (a.number ?: a.name) == (b.number ?: b.name) && a.lat == b.lat && a.lng == b.lng
    fun load(station: Station = data.station) {
        val sameStation = same(data.station, station)
        loadPlatform(station, if (sameStation) data.platforms else initialPlatforms(station), if (sameStation) data.selectedPlatform else 0)
    }
    fun selectPlatform(index: Int) {
        val target = data.platforms.getOrNull(index) ?: return
        if (index != data.selectedPlatform) loadPlatform(target, data.platforms, index)
    }
    private fun loadPlatform(station: Station, platforms: List<Station>, selected: Int) {
        val generation = ++liveGeneration
        job?.cancel()
        val previous = data.arrivals.takeIf { same(data.station, station) }.orEmpty()
        mutable.value = UiState.Loading(data.copy(station = station, arrivals = previous, platforms = platforms, selectedPlatform = selected,
            liveState = StationLiveState.Loading(previous),
            favorite = data.favorite && same(data.station, station),
            metro = data.metro.takeIf { data.station.name == station.name }.orEmpty()))
        job = viewModelScope.launch {
            try {
                val result = liveLoader.load(city, station, previous)
                if (generation != liveGeneration) return@launch
                val detail = result.station.copy(distance = result.station.distance ?: station.distance)
                // 站名命中地铁站时把换乘线路一起带出来，数据是内置的，不需要额外请求。
                val metro = graph.metro.callingAt(city, detail.name)
                val favorites = graph.prefs.favoriteStations.first()
                currentCoroutineContext().ensureActive()
                if (generation != liveGeneration) return@launch
                publish(StationData(detail, result.state.arrivals,
                    favorite = favorites.any { it.city.key == city.key && same(it.station, detail) },
                    metro = metro, liveState = result.state, platforms = mergePlatforms(platforms, selected, detail), selectedPlatform = selected))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == liveGeneration) {
                    val failed = StationLiveState.Failed(listOf((error as? BusException)?.message ?: "站点查询失败，请稍后重试"), previous)
                    publish(data.copy(arrivals = previous, liveState = failed))
                }
            }
        }
    }
    suspend fun refreshLive() {
        if (job?.isActive == true || !mutex.tryLock()) return
        val generation = ++liveGeneration
        val station = data.station
        val previous = data.arrivals
        mutable.value = when (val old = mutable.value) {
            is UiState.Error -> old.copy(data = data.copy(liveState = StationLiveState.Loading(previous)))
            is UiState.Loading -> old.copy(data = data.copy(liveState = StationLiveState.Loading(previous)))
            is UiState.Empty -> old.copy(data = data.copy(liveState = StationLiveState.Loading(previous)))
            is UiState.Success -> old.copy(data = data.copy(liveState = StationLiveState.Loading(previous)))
        }
        try {
            val result = liveLoader.load(city, station, previous)
            if (generation == liveGeneration && data.station == station) {
                val detail = result.station.copy(distance = result.station.distance ?: station.distance)
                publish(data.copy(station = detail, arrivals = result.state.arrivals, liveState = result.state,
                    platforms = mergePlatforms(data.platforms, data.selectedPlatform, detail)))
            }
        } finally { mutex.unlock() }
    }
    private fun publish(value: StationData) {
        val state = value.liveState
        if (state is StationLiveState.Failed) mutable.value = UiState.Error(state.message, value)
        else update(value, value.station.lines.isEmpty())
    }
    private fun mergePlatforms(existing: List<Station>, selected: Int, detail: Station): List<Station> {
        val platforms = existing.toMutableList()
        platforms[selected] = detail.copy(platforms = emptyList())
        val candidates = if (city.isXiamen) detail.platformNumbers.map { number ->
            detail.platforms.firstOrNull { it.number == number }
                ?: Station(detail.name, number = number, coordinateSystem = detail.coordinateSystem)
        } else detail.platforms
        candidates.forEach { candidate ->
            if (platforms.none { same(it, candidate) || (city.isXiamen && it.number == candidate.number) }) {
                platforms += candidate.copy(platforms = emptyList())
            }
        }
        return platforms
    }
    fun favorite() = action { graph.prefs.toggleStation(SavedStation(city, data.station)) }
}
