package com.yll682.houche.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable data class City(val key: String, val name: String, val code: String = "", val province: String = "", val pinyin: String = "") {
    val isXiamen: Boolean get() = code == "0592"
}
@Serializable data class LineSummary(val name: String, val direction: String, val from: String = "", val to: String = "", val stationOrder: Int? = null)
@Serializable data class Station(
    val name: String, val number: String? = null, val lat: Double? = null, val lng: Double? = null,
    val distance: Double? = null, val coordinateSystem: String = "unconfirmed",
    val platformNumbers: List<String> = emptyList(), val lines: List<LineSummary> = emptyList(),
    val sameNum: Int? = null, val platforms: List<Station> = emptyList(), val linesError: String? = null
)
@Serializable data class RouteStation(val name: String, val order: Int?, val id: String? = null, val lat: Double? = null, val lng: Double? = null)
@Serializable data class Route(
    val name: String, val id: String, val direction: String, val from: String = "", val to: String = "",
    val first: String = "", val last: String = "", val fareDescription: String = "", val stations: List<RouteStation> = emptyList(),
    val showTimetable: Boolean = false, val source: String = "", val coordinateSystem: String = "unconfirmed",
    val operatingPeriods: JsonElement = JsonNull, val path: JsonElement = JsonNull,
    val companies: JsonElement = JsonNull, val relatedRoutes: JsonElement = JsonNull
)
@Serializable data class Arrival(
    val remainingStations: Int? = null, val distance: Double? = null, val waiting: Boolean = false,
    val nextDeparture: String? = null, val to: String = "", val plate: String? = null,
    val statusText: String = "", val timeText: String = "", val distanceText: String = "",
    val name: String = "", val direction: String = "", val stationOrder: Int? = null,
    val platformNumber: String? = null, val platformLat: Double? = null, val platformLng: Double? = null
)
typealias StationBus = Arrival
@Serializable data class Vehicle(
    val plate: String, val order: Int?, val positionState: String, val reportedOrder: Int? = null,
    val inOrOut: String? = null, val nextStation: String? = null, val currentStation: String? = null,
    val dataTime: String? = null, val lat: Double? = null, val lng: Double? = null,
    val statusType: String? = null, val segmentDistance: Double? = null
)
@Serializable data class VehicleResults(
    val vehicles: List<Vehicle>, val notice: String, val arrivals: List<Arrival> = emptyList(),
    val hasReal: Int? = null, val runState: Int? = null, val nextDeparture: String? = null, val segments: JsonElement = JsonNull
)
@Serializable data class Timetable(val times: List<String>, val tips: String, val groups: JsonElement = JsonNull, val nextDeparture: String? = null, val special: String? = null)
@Serializable data class SearchResults(val lines: List<LineSummary>, val stations: List<Station>)
@Serializable data class SavedLine(val city: City, val line: LineSummary)
@Serializable data class SavedStation(val city: City, val station: Station)
@Serializable data class CityResolution(val city: City, val located: Boolean, val message: String = "", val error: String? = null)
