package com.yll682.houche.ui.screens.search

import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** metro 与公交结果分开存：线上搜不到地铁，地铁是内置资料，两者不能混在一个列表里。 */
data class SearchData(
    val query: String = "",
    val result: SearchResults = SearchResults(emptyList(), emptyList()),
    val history: List<String> = emptyList(),
    val metro: List<MetroLine> = emptyList()
)
class SearchViewModel(private val graph: AppGraph, private val city: City) : StateViewModel<SearchData>(SearchData()) {
    private var job: Job? = null
    init { viewModelScope.launch { attempt { graph.prefs.recentSearches.collect { history ->
        val value = data.copy(history = history)
        mutable.value = when (val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); is UiState.Empty -> UiState.Empty(value); else -> UiState.Success(value) }
    } } } }
    fun query(text: String, immediate: Boolean = false) {
        job?.cancel()
        update(data.copy(query = text, metro = emptyList()))
        if (text.isBlank()) return
        job = action {
            // 地铁资料在本地，和网络无关：先查地铁再等公交接口，输入「地铁」时能立刻看到结果。
            val local = runCatching { graph.metro.lines(city, text) }.getOrDefault(emptyList())
            update(data.copy(metro = local), empty = local.isEmpty() && data.result.lines.isEmpty() && data.result.stations.isEmpty())
            if (!immediate) delay(300)
            mutable.value = UiState.Loading(data)
            val result = runCatching { graph.repository().search(city, text.trim()) }
                .getOrElse { SearchResults(emptyList(), emptyList()) }
            update(
                data.copy(result = result, metro = local),
                result.lines.isEmpty() && result.stations.isEmpty() && local.isEmpty()
            )
        }
    }
    fun rememberQuery() = action { graph.prefs.addSearch(data.query) }
    fun remove(text: String) = action { graph.prefs.removeSearch(text) }
    fun clear() = action { graph.prefs.clearSearches() }
}
