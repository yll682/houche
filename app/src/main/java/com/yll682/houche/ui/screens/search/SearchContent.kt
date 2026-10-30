@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.search

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*

@Composable fun SearchContent(state: UiState<SearchData>, back: () -> Unit, line: (LineSummary) -> Unit, station: (Station) -> Unit, query: (String) -> Unit, search: (String) -> Unit, submit: () -> Unit, clear: () -> Unit, remove: (String) -> Unit, modifier: Modifier = Modifier, inputModifier: Modifier = Modifier) {
    val data = state.data ?: SearchData()
    Scaffold(modifier = modifier, topBar = { Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), back)
        OutlinedTextField(data.query, query, Modifier.weight(1f).touch(stringResource(R.string.search_label)).then(inputModifier), singleLine = true, label = { Text(stringResource(R.string.search_hint)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search(data.query); submit() }))
        TextButton(back, Modifier.touch(stringResource(R.string.cancel))) { Text(stringResource(R.string.cancel)) }
    } }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp)) {
            if (data.query.isBlank()) {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.recent_searches)) }; TextButton(clear, Modifier.touch(stringResource(R.string.clear))) { Text(stringResource(R.string.clear)) } } }
                items(data.history) { text ->
                    ListItem(headlineContent = { Text(text) }, leadingContent = { Icon(Icons.Default.History, stringResource(R.string.recent_searches)) }, trailingContent = { ActionIcon(Icons.Default.Close, stringResource(R.string.delete_search)) { remove(text) } }, modifier = Modifier.animateItem().touch(text).clickable { search(text) })
                }
            } else {
                feedback(state) { search(data.query) }
                if (state is UiState.Empty) item { EmptyState(stringResource(R.string.search_empty), stringResource(R.string.search_tip)) }
                item { SectionHeader(stringResource(R.string.lines), data.result.lines.size) }
                items(data.result.lines) { summary -> Box(Modifier.animateItem()) { LineRow(summary) { line(summary) } } }
                item { SectionHeader(stringResource(R.string.stations), data.result.stations.size) }
                items(data.result.stations) { stop -> ListItem(headlineContent = { Text(stop.name) }, supportingContent = { Text(if (stop.lines.isEmpty()) stringResource(R.string.click_view) else stringResource(R.string.station_count, stop.lines.size)) }, leadingContent = { Icon(Icons.Default.Place, stop.name) }, modifier = Modifier.animateItem().touch(stop.name).clickable { station(stop) }) }
            }
        }
    }
}
