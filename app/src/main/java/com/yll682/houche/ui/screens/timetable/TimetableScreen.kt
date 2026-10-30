package com.yll682.houche.ui.screens.timetable

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.R
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*

@Composable fun TimetableScreen(vm: TimetableViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: TimetableData()
    Page(stringResource(R.string.timetable), back) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Card(shape = MaterialTheme.shapes.large) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LineBadge(vm.route.name)
                Text(stringResource(R.string.to, vm.route.to), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.service_times, vm.route.first, vm.route.last))
                data.table?.nextDeparture?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.next_departure, it), color = MaterialTheme.colorScheme.primary) }
            } } }
            feedback(state) { vm.load() }
            if (state is UiState.Empty) item { EmptyState(stringResource(R.string.no_timetable)) }
            itemsIndexed(data.groups) { index, group ->
                var expanded by rememberSaveable(index, group.name) { mutableStateOf(true) }
                Card(modifier = Modifier.animateItem(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = if (group.current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        val title = group.name.ifBlank { stringResource(R.string.timetable) }
                        Row(Modifier.fillMaxWidth().touch(title).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); if (group.current) Text(stringResource(R.string.current_period), style = MaterialTheme.typography.labelMedium) }
                            Text(stringResource(R.string.departures, group.times.size))
                            Icon(Icons.Default.ExpandMore, stringResource(R.string.expand_group))
                        }
                        if (expanded) {
                            group.times.chunked(4).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { departure ->
                                        Surface(Modifier.weight(1f).padding(4.dp), shape = MaterialTheme.shapes.small, color = if (departure.status == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh) { Text(departure.time, Modifier.padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodyMedium) }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            group.times.filter { it.notes.isNotBlank() }.forEach { Text(stringResource(R.string.time_notes, it.time, it.notes), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            data.table?.tips?.takeIf { it.isNotBlank() }?.let { tips -> item { Text(tips, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
