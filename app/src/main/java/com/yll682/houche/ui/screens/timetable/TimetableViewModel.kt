package com.yll682.houche.ui.screens.timetable

import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.LocalTime

 data class Departure(val time: String, val notes: String = "", val status: Int? = null)
data class TimeGroup(val name: String, val current: Boolean, val times: List<Departure>)
data class TimetableData(val table: Timetable? = null, val groups: List<TimeGroup> = emptyList())
class TimetableViewModel(private val graph: AppGraph, private val city: City, val route: Route) : StateViewModel<TimetableData>(TimetableData()) {
    private var job: Job? = null
    init { load() }
    fun load() {
        job?.cancel()
        job = action {
            mutable.value = UiState.Loading(data)
            val table = graph.repository().timetable(city, route)
            val groups = withContext(Dispatchers.IO) { groups(table) }
            update(TimetableData(table, groups), groups.isEmpty())
        }
    }
    private fun groups(table: Timetable): List<TimeGroup> {
        fun JsonElement.field(key: String) = (this as? JsonObject)?.get(key)
        fun JsonElement.text(key: String) = (field(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val raw = table.groups as? JsonArray
        if (raw != null) return raw.map { group ->
            val times = (group.field("times") as? JsonArray).orEmpty().filter { it.text("isDelete") != "1" }.map { Departure(it.text("time"), listOf(it.text("noteText"), it.text("notes")).filter(String::isNotBlank).distinct().joinToString(" · "), it.text("status").toIntOrNull()) }
            TimeGroup(group.text("group"), group.text("isNow") == "1", times)
        }.filter { it.times.isNotEmpty() }
        val hour = LocalTime.now().hour
        return table.times.groupBy { it.substringBefore(':').toIntOrNull()?.div(2)?.times(2) ?: -1 }.map { (start, times) ->
            TimeGroup(if (start < 0) "" else "%02d:00–%02d:59".format(start, start + 1), hour in start..start + 1, times.map { Departure(it) })
        }
    }
}
