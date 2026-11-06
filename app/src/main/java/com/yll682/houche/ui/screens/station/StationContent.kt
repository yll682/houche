@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.station

import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
    modifier: Modifier = Modifier,
    openMetro: (String) -> Unit = {}
) {
    val data = state.data ?: return
    val stop = data.station
    Page(stringResource(R.string.stations), back, modifier, actions = {
        HeaderIcon(
            if (data.favorite) Icons.Default.Star else Icons.Default.StarBorder,
            stringResource(if (data.favorite) R.string.unfavorite else R.string.favorite),
            favorite
        )
    }) { padding ->
        PullToRefresh(state is UiState.Loading, refresh) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    // 站名独占一行，距离与站台数并排放在下面，右侧不再竖着堆两块信息。
                    CardSurface {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stop.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DistanceChip(stop.distance)
                                val platforms = maxOf(1, stop.platformNumbers.distinct().size, stop.platforms.size)
                                if (platforms > 1) {
                                    Spacer(Modifier.width(10.dp))
                                    MetaPill(stringResource(R.string.platform_count, platforms))
                                }
                            }
                        }
                    }
                }
                feedback(state) { refresh() }
                val platforms = stop.platformNumbers.distinct()
                if (platforms.size > 1 || stop.platforms.size > 1) {
                    item { PlatformSelector(stop, platforms, platform) }
                }
                if (data.metro.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.metro_transfer), data.metro.size) }
                    item {
                        CardSurface {
                            data.metro.forEachIndexed { index, calling ->
                                if (index > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().touch(calling.line.name).clickable { openMetro(calling.line.id) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(10.dp).clip(Capsule).background(Color(android.graphics.Color.parseColor(calling.line.color))))
                                    Spacer(Modifier.width(10.dp))
                                    Text(calling.line.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.to, calling.line.to), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
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
                                LineRow(summary, arrivals.firstOrNull(), arrivals.drop(1)) { line(summary, stop) }
                            }
                        }
                    }
                }

            }
        }
    }
}

/** 同名站台用「站台 1 / 站台 2」表示，不把上游内部编号暴露给用户。 */
@Composable private fun PlatformSelector(stop: Station, numbers: List<String>, platform: (Station) -> Unit) {
    val count = maxOf(numbers.size, stop.platforms.size)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(Modifier.height(48.dp).width(IntrinsicSize.Max), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(36.dp).clip(Capsule).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Capsule))
            Row {
                repeat(count) { index ->
                    val target = if (numbers.size > index) stop.copy(number = numbers[index]) else stop.platforms.getOrNull(index)
                    val active = if (numbers.size > index) stop.number == numbers[index]
                        else stop.platforms.getOrNull(index)?.let { stop.lat == it.lat && stop.lng == it.lng } == true
                    val label = stringResource(R.string.platform_index, index + 1)
                    Box(Modifier.height(48.dp).clickable(role = Role.Tab) { target?.let(platform) }
                        .semantics { contentDescription = label; selected = active }, contentAlignment = Alignment.Center) {
                        Box(Modifier.padding(horizontal = 3.dp).height(30.dp).clip(Capsule)
                            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent)
                            .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelLarge,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
