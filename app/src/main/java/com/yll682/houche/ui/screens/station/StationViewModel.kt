package com.yll682.houche.ui.screens.station

import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

data class StationData(val station: Station, val arrivals: List<Arrival> = emptyList(), val favorite: Boolean = false)
class StationViewModel(private val graph: AppGraph, val city: City, initial: Station) : StateViewModel<StationData>(StationData(initial)) {
    private var job: Job? = null
    private val mutex = Mutex()
    init {
        load(initial)
        viewModelScope.launch { attempt { graph.prefs.favoriteStations.collect { list ->
            val value = data.copy(favorite = list.any { it.city.key == city.key && same(it.station, data.station) })
            mutable.value = when (val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); else -> UiState.Success(value) }
        } } }
    }
    private fun same(a: Station, b: Station) = (a.number ?: a.name) == (b.number ?: b.name) && a.lat == b.lat && a.lng == b.lng
    fun load(station: Station = data.station) {
        job?.cancel()
        job = action {
            mutable.value = UiState.Loading(data)
            val resolved = graph.repository().stationDetail(city, station.name, station.number, station.lat, station.lng)
            val detail = resolved.copy(distance = resolved.distance ?: station.distance)
            update(StationData(detail, favorite = graph.prefs.favoriteStations.first().any { it.city.key == city.key && same(it.station, detail) }), detail.lines.isEmpty())
            refreshLive()
        }
    }
    suspend fun refreshLive() {
        if (mutable.value is UiState.Loading || !mutex.tryLock()) return
        val station = data.station
        try { attempt {
            val arrivals = graph.repository().stationBuses(city, station)
            if (data.station == station) update(data.copy(arrivals = arrivals), station.lines.isEmpty())
        } } finally { mutex.unlock() }
    }
    fun favorite() = action { graph.prefs.toggleStation(SavedStation(city, data.station)) }
}
