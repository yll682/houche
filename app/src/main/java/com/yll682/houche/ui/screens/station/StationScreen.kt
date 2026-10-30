package com.yll682.houche.ui.screens.station

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*

@Composable fun StationScreen(vm: StationViewModel, back: () -> Unit, line: (LineSummary, Station) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: return
    val stop = data.station
    PollEvery20Seconds(state !is UiState.Loading && state !is UiState.Error) { vm.refreshLive() }
    Page(stop.name, back, actions = { ActionIcon(if (data.favorite) Icons.Default.Star else Icons.Default.StarOutline, stringResource(if (data.favorite) R.string.unfavorite else R.string.favorite)) { vm.favorite() } }) { padding ->
        PullToRefresh(state is UiState.Loading, { vm.load() }) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { DistanceChip(stop.distance) }
                feedback(state) { vm.load() }
                if (stop.platformNumbers.size > 1 || stop.platforms.size > 1) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(stop.platformNumbers.distinct()) { number -> val label = stringResource(R.string.platform, number); FilterChip(stop.number == number, { vm.load(stop.copy(number = number)) }, label = { Text(label) }, modifier = Modifier.touch(label)) }
                        itemsIndexed(stop.platforms) { index, platform -> val label = stringResource(R.string.platform_index, index + 1); FilterChip(stop.lat == platform.lat && stop.lng == platform.lng, { vm.load(platform) }, label = { Text(label) }, modifier = Modifier.touch(label)) }
                    }
                }
                item { SectionHeader(stringResource(R.string.passing_lines), stop.lines.size) }
                items(stop.lines) { summary ->
                    Card(modifier = Modifier.animateItem(), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(12.dp)) {
                            val arrivals = data.arrivals.filter { it.name == summary.name && it.direction == summary.direction }
                            LineRow(summary, arrivals.firstOrNull()) { line(summary, stop) }
                            if (arrivals.isEmpty()) ArrivalText(null)
                            arrivals.drop(1).take(1).forEach { ArrivalText(it) }
                        }
                    }
                }
                if (stop.lines.isEmpty() && state !is UiState.Loading) item { EmptyState(stringResource(R.string.no_lines)) }
                item { Text(stringResource(R.string.live_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
