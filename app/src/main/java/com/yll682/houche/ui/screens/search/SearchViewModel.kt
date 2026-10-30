package com.yll682.houche.ui.screens.search

import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchData(val query: String = "", val result: SearchResults = SearchResults(emptyList(), emptyList()), val history: List<String> = emptyList())
class SearchViewModel(private val graph: AppGraph, private val city: City) : StateViewModel<SearchData>(SearchData()) {
    private var job: Job? = null
    init { viewModelScope.launch { attempt { graph.prefs.recentSearches.collect { history ->
        val value = data.copy(history = history)
        mutable.value = when (val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); is UiState.Empty -> UiState.Empty(value); else -> UiState.Success(value) }
    } } } }
    fun query(text: String, immediate: Boolean = false) {
        job?.cancel()
        update(data.copy(query = text))
        if (text.isBlank()) return
        job = action {
            if (!immediate) delay(300)
            mutable.value = UiState.Loading(data)
            val result = graph.repository().search(city, text.trim())
            update(data.copy(result = result), result.lines.isEmpty() && result.stations.isEmpty())
        }
    }
    fun rememberQuery() = action { graph.prefs.addSearch(data.query) }
    fun remove(text: String) = action { graph.prefs.removeSearch(text) }
    fun clear() = action { graph.prefs.clearSearches() }
}
