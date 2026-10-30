@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.line

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.*

@Composable fun LineContent(
    state: UiState<LineData>, city: City, panel: Int, fare: Boolean, selectPanel: (Int) -> Unit,
    toggleFare: () -> Unit, back: () -> Unit, timetable: (Route) -> Unit, favorite: () -> Unit,
    share: (String, String) -> Unit, retry: () -> Unit, reverse: () -> Unit,
    previewStation: (RouteStation) -> Unit, select: (RouteStation) -> Unit, closePreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data ?: LineData()
    val route = data.route
    val loading = state is UiState.Loading
    Page(route?.name ?: stringResource(R.string.lines), back, modifier, actions = {
        ActionIcon(
            if (data.favorite) Icons.Default.Star else Icons.Default.StarBorder,
            stringResource(if (data.favorite) R.string.unfavorite else R.string.favorite),
            favorite,
            tint = if (data.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        val text = route?.let { stringResource(R.string.share_text, city.name, it.name, it.to, it.first, it.last) }
        val shareTitle = stringResource(R.string.share)
        ActionIcon(Icons.Default.Share, shareTitle, { if (text != null) share(text, shareTitle) })
    }) { padding ->
        PullToRefresh(loading, retry) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                feedback(state) { retry() }
                if (route != null) {
                    item { RouteHeaderCard(route, loading, fare, toggleFare, timetable, reverse) }
                    item { BoardingCard(data) }
                    item { Segments(listOf(stringResource(R.string.stops), stringResource(R.string.vehicles)), panel, selectPanel) }
                    if (panel == 0) {
                        if (route.stations.isEmpty()) item { EmptyState(stringResource(R.string.no_route_stops)) }
                        itemsIndexed(route.stations, key = { index, stop -> "${stop.order}-${stop.name}-$index" }) { _, stop ->
                            StopRow(
                                stop = stop,
                                selected = data.selected,
                                vehicles = data.live?.vehicles.orEmpty().filter { it.order == stop.order },
                                onClick = { previewStation(stop) }
                            )
                        }
                    } else {
                        if (data.live?.vehicles.isNullOrEmpty()) {
                            item { EmptyState(stringResource(if (data.selected == null) R.string.select_boarding else R.string.no_vehicles)) }
                        }
                        itemsIndexed(data.live?.vehicles.orEmpty(), key = { index, vehicle -> "${vehicle.plate}-$index" }) { _, vehicle ->
                            VehicleCard(vehicle, data.selected)
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
    data.preview?.let { stop ->
        ModalBottomSheet(onDismissRequest = closePreview, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
                Text(stop.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.station_arrivals), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                when {
                    data.previewLoading -> LoadingSkeleton()
                    data.previewFailed -> ErrorCard(data.previewError) { previewStation(stop) }
                    data.previewArrivals.isEmpty() -> Text(stringResource(R.string.arrival_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> data.previewArrivals.take(4).forEach { arrival ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(arrival)
                            Spacer(Modifier.width(10.dp))
                            Text(arrival.plate.orEmpty().ifBlank { stringResource(R.string.vehicles) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            ArrivalText(arrival)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    { select(stop); closePreview() },
                    Modifier.fillMaxWidth().height(48.dp).touch(stringResource(R.string.set_boarding)),
                    shape = Capsule
                ) { Text(stringResource(R.string.set_boarding)) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable private fun StatusDot(arrival: Arrival) {
    val color = when {
        arrival.remainingStations != null && arrival.remainingStations!! <= 0 -> MaterialTheme.colorScheme.primary
        arrival.waiting -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.tertiary
    }
    Box(Modifier.size(8.dp).clip(Capsule).background(color))
}

/** 线路头卡：大号线路号 + 开往方向 + 首末班 + 票价与时刻表入口。 */
@Composable private fun RouteHeaderCard(
    route: Route, loading: Boolean, fare: Boolean, toggleFare: () -> Unit,
    timetable: (Route) -> Unit, reverse: () -> Unit
) {
    CardSurface {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(route.name, large = true)
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = reverse,
                    modifier = Modifier.height(36.dp).touch(stringResource(R.string.reverse)),
                    shape = Capsule,
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.reverse), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.to, route.to), style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                TimeMeta(stringResource(R.string.first_bus), route.first, Modifier.weight(1f))
                TimeMeta(stringResource(R.string.last_bus), route.last, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Hairline()
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(toggleFare, Modifier.touch(stringResource(R.string.fare)), contentPadding = PaddingValues(end = 16.dp)) {
                    Icon(Icons.Default.Info, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.fare), style = MaterialTheme.typography.labelLarge)
                }
                if (route.showTimetable) {
                    TextButton({ timetable(route) }, Modifier.touch(stringResource(R.string.timetable)), contentPadding = PaddingValues(0.dp), enabled = !loading) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.timetable), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (fare) {
                Text(
                    route.fareDescription.ifBlank { stringResource(R.string.fare_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable private fun TimeMeta(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value.ifBlank { stringResource(R.string.no_time) }, style = MaterialTheme.typography.titleMedium)
    }
}

/** 乘车站到站卡：整屏最该被一眼看到的信息。 */
@Composable private fun BoardingCard(data: LineData) {
    val selected = data.selected
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .3f))
    ) {
        Column(Modifier.padding(20.dp)) {
            if (selected == null) {
                Text(stringResource(R.string.select_boarding), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                Text(
                    stringResource(R.string.boarding, selected.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f)
                )
                Spacer(Modifier.height(8.dp))
                val arrival = data.arrivals.firstOrNull()
                val (primary, secondary) = arrivalParts(arrival)
                // 候车的人先看还差几分钟：有时间就把时间当主信息，站数退为补充。
                val stationCount = primary.contains("站") && !primary.contains("到站")
                val hero = if (stationCount && secondary != null) secondary else primary
                val sub = if (hero == secondary) primary else secondary
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(hero, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
                    if (sub != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            sub,
                            Modifier.padding(bottom = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f)
                        )
                    }
                }
                data.live?.nextDeparture?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.next_departure, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                }
                data.live?.notice?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
                }
            }
        }
    }
}

/** 时间轴单站：左侧连续轨道 + 站点圆点，右侧站名与车辆。 */
@Composable private fun StopRow(stop: RouteStation, selected: RouteStation?, vehicles: List<Vehicle>, onClick: () -> Unit) {
    val isSelected = selected?.name == stop.name && selected.order == stop.order
    val passed = selected?.order?.let { order -> (stop.order ?: order) < order } == true
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(40.dp).fillMaxHeight()) {
            Box(
                Modifier.align(Alignment.Center).width(1.5.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(22.dp).clip(Capsule)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Capsule),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stop.order?.toString() ?: "·",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            Modifier.weight(1f).touch(stop.name).clickable(onClick = onClick)
                .padding(end = 4.dp, top = 8.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stop.name,
                    Modifier.weight(1f),
                    style = if (isSelected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    color = if (passed && !isSelected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isSelected) {
                    Icon(Icons.Default.MyLocation, stringResource(R.string.current_stop), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (vehicles.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                vehicles.forEach { vehicle ->
                    Row(
                        Modifier.padding(bottom = 2.dp).clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(6.dp).clip(Capsule).background(
                                if (vehicle.positionState == "at-stop") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                R.string.vehicle_position,
                                vehicle.plate.ifBlank { stringResource(R.string.no_plate) },
                                stringResource(
                                    when (vehicle.positionState) {
                                        "at-stop" -> R.string.at_stop
                                        "between" -> R.string.between
                                        else -> R.string.unknown_position
                                    }
                                )
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun VehicleCard(vehicle: Vehicle, selected: RouteStation?) {
    val state = stringResource(
        when (vehicle.positionState) {
            "at-stop" -> R.string.at_stop
            "between" -> R.string.between
            else -> R.string.unknown_position
        }
    )
    CardSurface {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vehicle.plate.ifBlank { stringResource(R.string.no_plate) }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                MetaPill(state, vehicle.positionState == "at-stop")
            }
            val stopName = vehicle.currentStation ?: vehicle.nextStation
            if (!stopName.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(stopName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val boardOrder = selected?.order
            if (boardOrder != null && vehicle.order != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (vehicle.order > boardOrder) stringResource(R.string.passed) else stringResource(R.string.stops_away, boardOrder - vehicle.order),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            vehicle.segmentDistance?.let {
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.vehicle_distance, distanceLabel(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vehicle.dataTime?.let {
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.data_time, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}