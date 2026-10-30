@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.home

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.Capsule
import com.yll682.houche.ui.theme.FieldShape

@Composable fun HomeContent(
    state: UiState<HomeData>, city: City, granted: Boolean, segment: Int, selectSegment: (Int) -> Unit,
    locate: () -> Unit, search: () -> Unit, cities: () -> Unit, settings: () -> Unit,
    line: (City, LineSummary, Station?) -> Unit, station: (City, Station) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data ?: HomeData()
    val loading = state is UiState.Loading
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, letterSpacing = 1.sp)
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        onClick = cities,
                        modifier = Modifier.height(34.dp).touch(stringResource(R.string.choose_city)),
                        shape = Capsule,
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(city.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    ActionIcon(Icons.Default.Settings, stringResource(R.string.settings), settings)
                }
                Hairline()
            }
        }
    ) { padding ->
        PullToRefresh(loading, { locate() }) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        onClick = search,
                        modifier = Modifier.fillMaxWidth().height(52.dp).touch(stringResource(R.string.search_hint)),
                        shape = FieldShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (loading) R.string.locating else if (data.position != null) R.string.located else R.string.nearby),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ActionIcon(Icons.Default.Refresh, stringResource(R.string.refresh), { locate() }, enabled = !loading)
                        val locationAction = stringResource(if (data.position == null) R.string.enable_location else R.string.switch_city)
                        TextButton(
                            { if (data.position == null) locate() else cities() },
                            Modifier.touch(locationAction),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text(locationAction, style = MaterialTheme.typography.labelLarge) }
                    }
                }
                item { Segments(listOf(stringResource(R.string.nearby), stringResource(R.string.favorites)), segment, selectSegment) }

                if (segment == 0) {
                    if (!granted || data.permissionDenied || (data.position == null && !loading)) {
                        item { LocationCard(data.permissionDenied, locate) }
                    }
                    feedback(state) { locate() }
                    if (data.position != null) {
                        item {
                            Text(
                                stringResource(if (data.position.fromLastKnown) R.string.last_location else if (data.cityResolved) R.string.location_city else R.string.location_fallback),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (data.stations.isEmpty() && state is UiState.Empty) {
                        item { EmptyState(stringResource(R.string.no_stations), action = stringResource(R.string.search_action), onClick = search) }
                    }
                    itemsIndexed(data.stations, key = { index, stop -> "$index-${stop.name}" }) { index, stop ->
                        StationCard(
                            station = stop,
                            arrivals = data.arrivals[index].orEmpty(),
                            error = data.liveErrors[index],
                            collapsed = index >= 3,
                            onOpen = { station(city, stop) },
                            onLine = { summary -> line(city, summary, stop) }
                        )
                    }
                } else {
                    feedback(state) { locate() }
                    if (data.favoriteLines.isEmpty() && data.favoriteStations.isEmpty()) {
                        item { EmptyState(stringResource(R.string.no_favorites), stringResource(R.string.favorite_tip), stringResource(R.string.search_action), search) }
                    }
                    if (data.favoriteLines.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.favorite_lines), data.favoriteLines.size) }
                        item {
                            CardSurface {
                                data.favoriteLines.forEachIndexed { index, saved ->
                                    if (index > 0) Hairline()
                                    Column {
                                        Text(
                                            saved.city.name,
                                            Modifier.padding(start = 16.dp, top = 10.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        LineRow(saved.line) { line(saved.city, saved.line, null) }
                                    }
                                }
                            }
                        }
                    }
                    if (data.favoriteStations.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.favorite_stations), data.favoriteStations.size) }
                        item {
                            CardSurface {
                                data.favoriteStations.forEachIndexed { index, saved ->
                                    if (index > 0) Hairline()
                                    Row(
                                        Modifier.fillMaxWidth().touch(saved.station.name).clickable { station(saved.city, saved.station) }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(saved.station.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(saved.city.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(stringResource(R.string.station_count, saved.station.lines.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LocationCard(denied: Boolean, locate: () -> Unit) {
    CardSurface {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(Capsule).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (denied) R.string.location_denied else R.string.location_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            val label = stringResource(if (denied) R.string.open_settings else R.string.allow_location)
            Button(locate, Modifier.touch(label), shape = Capsule, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) { Text(label) }
        }
    }
}

/** 附近站点卡片：站名/距离一行，线路逐行，行间发丝线。 */
@Composable private fun StationCard(
    station: Station, arrivals: List<Arrival>, error: String?, collapsed: Boolean,
    onOpen: () -> Unit, onLine: (LineSummary) -> Unit
) {
    val platforms = station.sameNum ?: station.platformNumbers.size
    CardSurface {
        Row(
            Modifier.fillMaxWidth().touch(station.name).clickable(onClick = onOpen).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (platforms > 1) {
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.platform_count, platforms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            DistanceChip(station.distance)
        }
        if (station.lines.isNotEmpty()) {
            Hairline()
            station.lines.take(3).forEachIndexed { index, summary ->
                if (index > 0) Hairline()
                LineRow(summary, arrivals.firstOrNull { it.name == summary.name && it.direction == summary.direction }) { onLine(summary) }
            }
            if (collapsed || station.lines.isEmpty()) {
                Hairline()
                TextButton(
                    onOpen,
                    Modifier.fillMaxWidth().touch(stringResource(R.string.click_view)),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) { Text(stringResource(R.string.click_view), style = MaterialTheme.typography.labelLarge) }
            }
        }
        station.linesError?.let {
            Hairline()
            Box(Modifier.padding(12.dp)) { ErrorCard(it, onOpen) }
        }
        error?.let {
            Hairline()
            Box(Modifier.padding(12.dp)) { ErrorCard(it, onOpen) }
        }
    }
}