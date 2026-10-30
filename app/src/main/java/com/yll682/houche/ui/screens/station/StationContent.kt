@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.station

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.Capsule

@Composable fun StationContent(
    state: UiState<StationData>, back: () -> Unit, line: (LineSummary, Station) -> Unit,
    refresh: () -> Unit, platform: (Station) -> Unit, favorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data ?: return
    val stop = data.station
    Page(stop.name, back, modifier, actions = {
        ActionIcon(
            if (data.favorite) Icons.Default.Star else Icons.Default.StarBorder,
            stringResource(if (data.favorite) R.string.unfavorite else R.string.favorite),
            favorite,
            tint = if (data.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }) { padding ->
        PullToRefresh(state is UiState.Loading, refresh) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DistanceChip(stop.distance)
                        Spacer(Modifier.width(8.dp))
                        if (stop.lines.isNotEmpty()) MetaPill(stringResource(R.string.station_count, stop.lines.size))
                    }
                }
                feedback(state) { refresh() }
                val platforms = stop.platformNumbers.distinct()
                if (platforms.size > 1 || stop.platforms.size > 1) {
                    item { PlatformSelector(stop, platforms, platform) }
                }
                item { SectionHeader(stringResource(R.string.passing_lines), stop.lines.size) }
                if (stop.lines.isEmpty() && state !is UiState.Loading) {
                    item { EmptyState(stringResource(R.string.no_lines)) }
                } else {
                    item {
                        CardSurface {
                            stop.lines.forEachIndexed { index, summary ->
                                if (index > 0) Hairline()
                                val arrivals = data.arrivals.filter { it.name == summary.name && it.direction == summary.direction }
                                Column {
                                    LineRow(summary, arrivals.firstOrNull()) { line(summary, stop) }
                                    arrivals.drop(1).take(1).forEach { arrival ->
                                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                            Spacer(Modifier.weight(1f))
                                            ArrivalText(arrival)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.live_note),
                        Modifier.padding(top = 4.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 同名站台用「站台 1 / 站台 2」表示，不把上游内部编号暴露给用户。 */
@Composable private fun PlatformSelector(stop: Station, numbers: List<String>, platform: (Station) -> Unit) {
    val count = maxOf(numbers.size, stop.platforms.size)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            val target = when {
                numbers.size > index -> stop.copy(number = numbers[index])
                stop.platforms.size > index -> stop.platforms[index]
                else -> null
            }
            val selected = when {
                numbers.size > index -> stop.number == numbers[index]
                stop.platforms.size > index -> stop.lat == stop.platforms[index].lat && stop.lng == stop.platforms[index].lng
                else -> index == 0
            }
            val label = stringResource(R.string.platform_index, index + 1)
            Surface(
                onClick = { target?.let(platform) },
                modifier = Modifier.height(36.dp).touch(label),
                shape = Capsule,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}