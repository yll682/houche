@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.station

import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
    refresh: () -> Unit, platform: (Int) -> Unit, favorite: () -> Unit,
    modifier: Modifier = Modifier,
    openMetro: (String) -> Unit = {}
) {
    val data = state.data ?: return
    val stop = data.station
    Page(stringResource(R.string.stations), back, modifier, actions = {
        HeaderIcon(
            if (data.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
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
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Place, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(stop.name, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DistanceChip(stop.distance)
                                    val platforms = data.platforms.size
                                    if (platforms > 1) {
                                        Spacer(Modifier.width(10.dp))
                                        MetaPill(stringResource(R.string.platform_count, platforms))
                                    }
                                }
                            }
                        }
                    }
                }
                feedback(state) { refresh() }
                if (data.liveState is StationLiveState.Partial && data.liveState.message != null) {
                    item { Text(data.liveState.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                if (data.platforms.size > 1) {
                    item { PlatformSelector(data.platforms.size, data.selectedPlatform, platform) }
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
                                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
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
                            // 行序跟到站走：按「到本站还剩几站」从近到远，没有实时信息的线路按线路号垫底。
                            val rows = stationLineRows(stop.lines, data.arrivals)
                            rows.forEachIndexed { index, (summary, arrivals) ->
                                if (index > 0) Hairline()
                                LineRow(summary, arrivals.firstOrNull(), arrivals.drop(1)) { line(summary, stop) }
                            }
                        }
                    }
                }

            }
        }
    }
}

/**
 * 站点页「经过线路」的行序：每条线路取自己的到站记录，按「还剩几站」从近到远排；
 * 没有实时记录的线路整条垫底并按线路号自然序排。
 */
internal fun stationLineRows(lines: List<LineSummary>, arrivals: List<Arrival>): List<Pair<LineSummary, List<Arrival>>> =
    orderLinesByNear(lines, arrivals).map { summary ->
        summary to arrivals.filter { it.name == summary.name && it.direction == summary.direction }
            .sortedWith(nearArrivalComparator)
    }

/** 同名站台用「站台 1 / 站台 2」表示，不把上游内部编号暴露给用户。 */
@Composable private fun PlatformSelector(count: Int, selectedPlatform: Int, platform: (Int) -> Unit) {
    CapsuleTabs(List(count) { stringResource(R.string.platform_index, it + 1) }, selectedPlatform, platform, padding = PaddingValues(0.dp))
}
