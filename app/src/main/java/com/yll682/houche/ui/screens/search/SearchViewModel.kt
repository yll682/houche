package com.yll682.houche.ui.screens.search

import androidx.lifecycle.viewModelScope
import com.yll682.houche.AppGraph
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** metro 与公交结果分开存：线上搜不到地铁，地铁是内置资料，两者不能混在一个列表里。 */
data class SearchData(
    val query: String = "",
    val result: SearchResults = SearchResults(emptyList(), emptyList()),
    val history: List<String> = emptyList(),
    val metro: List<MetroLine> = emptyList()
)
class SearchViewModel(private val graph: AppGraph, private val city: City,
    private val searchRequest: suspend (String) -> SearchResults = { graph.repository().search(city, it) },
    private val localRequest: suspend (String) -> List<MetroLine> = { graph.metro.lines(city, it) }
) : StateViewModel<SearchData>(SearchData()) {
    private var job: Job? = null
    private var generation = 0L
    private val historyMutex = Mutex()
    init { viewModelScope.launch { attempt { graph.prefs.recentSearches.collect { history ->
        val value = data.copy(history = history)
        mutable.value = when (val old = mutable.value) { is UiState.Error -> old.copy(data = value); is UiState.Loading -> old.copy(data = value); is UiState.Empty -> UiState.Empty(value); else -> UiState.Success(value) }
    } } } }
    fun query(text: String, immediate: Boolean = false) {
        val request = ++generation
        job?.cancel()
        update(data.copy(query = text, result = SearchResults(emptyList(), emptyList()), metro = emptyList()))
        if (text.isBlank()) return
        job = action {
            val local = try { localRequest(text) } catch (error: Exception) {
                if (error is CancellationException) throw error
                emptyList()
            }
            if (request != generation || !currentCoroutineContext().isActive) return@action
            update(data.copy(metro = local))
            if (!immediate) delay(300) // Existing data-source debounce; not a Telegram global constant.
            if (request != generation) return@action
            mutable.value = UiState.Loading(data)
            val result = try { searchRequest(text.trim()) } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation) mutable.value = UiState.Error(error.message, data)
                return@action
            }
            if (request != generation || !currentCoroutineContext().isActive) return@action
            update(data.copy(result = result, metro = local), result.lines.isEmpty() && result.stations.isEmpty() && local.isEmpty())
        }
    }
    fun rememberQuery() = action { graph.prefs.addSearch(data.query) }
    fun remove(text: String, feedback: (((() -> Unit)) -> Unit)? = null) = removeHistory(text, feedback)
    fun clear(feedback: (((() -> Unit)) -> Unit)? = null) = removeHistory(null, feedback)
    private fun removeHistory(keyword: String?, feedback: (((() -> Unit)) -> Unit)?) = action {
      historyMutex.withLock {
        val snapshot = graph.prefs.removeSearchesWithSnapshot(keyword)
        update(data.copy(history = data.history.filterNot { it in snapshot.removed }))
        if (snapshot.removed.isNotEmpty()) feedback?.invoke {
            val restore = graph.restoreSearches(snapshot)
            action { restore.await() }
        }
      }
    }
}
