@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.line

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val vehicles = data.live?.vehicles.orEmpty().sortedWith(compareBy<Vehicle> { it.order ?: Int.MAX_VALUE }.thenBy { it.plate })
    Page(route?.name ?: stringResource(R.string.lines), back, modifier, actions = {
        HeaderIcon(if (data.favorite) Icons.Default.Star else Icons.Default.StarBorder,
            stringResource(if (data.favorite) R.string.unfavorite else R.string.favorite), favorite)
        val text = route?.let { stringResource(R.string.share_text, city.name, it.name, it.to, it.first, it.last) }
        val shareTitle = stringResource(R.string.share)
        HeaderIcon(Icons.Default.Share, shareTitle, { if (text != null) share(text, shareTitle) })
    }) { padding ->
        PullToRefresh(loading, retry) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                feedback(state) { retry() }
                if (route != null) {
                    item { RouteHeaderCard(route, data.live?.nextDeparture, loading, fare, toggleFare, timetable, reverse) }
                    item { LiveCard(route, data, vehicles, previewStation) }
                    item { Segments(listOf(stringResource(R.string.stops), stringResource(R.string.vehicles)), panel, selectPanel) }
                    if (panel == 0) {
                        if (route.stations.isEmpty()) item { EmptyState(stringResource(R.string.no_route_stops)) }
                        itemsIndexed(route.stations, key = { index, stop -> "${stop.order}-${stop.name}-$index" }) { _, stop ->
                            StopRow(stop, data.selected, vehicles.filter { it.order != null && it.order == stop.order }) { previewStation(stop) }
                        }
                    } else {
                        if (vehicles.isEmpty()) item { EmptyState(stringResource(R.string.no_vehicles)) }
                        itemsIndexed(vehicles, key = { index, vehicle -> "${vehicle.plate}-$index" }) { _, vehicle -> VehicleCard(vehicle, data.selected) }
                    }
                }
            }
        }
    }
    data.preview?.let { stop ->
        ModalBottomSheet(onDismissRequest = closePreview, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
                Text(stop.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                when {
                    data.previewLoading -> LoadingSkeleton()
                    data.previewFailed -> ErrorCard(data.previewError) { previewStation(stop) }
                    data.previewArrivals.isEmpty() -> Text(stringResource(R.string.arrival_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> data.previewArrivals.take(4).forEach { arrival ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(arrivalColor(arrival))
                            Spacer(Modifier.width(10.dp))
                            Text(arrival.plate.orEmpty().ifBlank { stringResource(R.string.no_plate) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            ArrivalText(arrival)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (data.selected == stop) {
                    OutlinedButton({}, Modifier.fillMaxWidth().height(48.dp).touch(stringResource(R.string.boarding_selected)), enabled = false, shape = Capsule) {
                        Text(stringResource(R.string.boarding_selected))
                    }
                } else {
                    FilledTonalButton({ select(stop); closePreview() }, Modifier.fillMaxWidth().height(48.dp).touch(stringResource(R.string.set_boarding)), shape = Capsule) {
                        Text(stringResource(R.string.set_boarding))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable private fun RouteHeaderCard(
    route: Route, nextDeparture: String?, loading: Boolean, fare: Boolean, toggleFare: () -> Unit,
    timetable: (Route) -> Unit, reverse: () -> Unit
) {
    CardSurface {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(route.from, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.AutoMirrored.Filled.ArrowRightAlt, null, Modifier.padding(horizontal = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(route.to, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(reverse, Modifier.size(48.dp)) {
                    Icon(Icons.Default.SwapHoriz, stringResource(R.string.reverse), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ServicePill(stringResource(R.string.first_time, route.first.ifBlank { stringResource(R.string.no_time) }), MaterialTheme.colorScheme.primary, .10f)
                Spacer(Modifier.width(6.dp))
                ServicePill(stringResource(R.string.last_time, route.last.ifBlank { stringResource(R.string.no_time) }), MaterialTheme.colorScheme.tertiary, .12f)
                Spacer(Modifier.weight(1f))
                if (route.showTimetable) {
                    TextButton({ timetable(route) }, Modifier.touch(stringResource(R.string.timetable)), enabled = !loading, contentPadding = PaddingValues(start = 6.dp)) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.timetable), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (route.fareDescription.isNotBlank() || !nextDeparture.isNullOrBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (route.fareDescription.isNotBlank()) {
                        Text(route.fareDescription,
                            Modifier.weight(1f).touch(stringResource(R.string.fare)).clickable(onClick = toggleFare).wrapContentHeight(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (fare) 4 else 1, overflow = TextOverflow.Ellipsis)
                    } else Spacer(Modifier.weight(1f))
                    if (!nextDeparture.isNullOrBlank()) {
                        Text(stringResource(R.string.origin_departure, nextDeparture), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable private fun ServicePill(text: String, color: Color, alpha: Float) {
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = alpha)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable private fun LiveCard(route: Route, data: LineData, vehicles: List<Vehicle>, previewStation: (RouteStation) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    Surface(Modifier.fillMaxWidth(), shape = CardShape,
        color = if (dark) colors.primaryContainer.copy(alpha = .45f) else colors.primary.copy(alpha = .06f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = .16f))) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                if (data.selected != null) {
                    Text(stringResource(R.string.boarding, data.selected.name), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    Spacer(Modifier.height(6.dp))
                    val arrival = data.arrivals.firstOrNull()
                    val (hero, sub) = arrivalParts(arrival)
                    Row {
                        Text(hero, Modifier.alignByBaseline(), style = MaterialTheme.typography.displayMedium, color = arrivalColor(arrival), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub != null) Text(sub, Modifier.padding(start = 10.dp).alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.all_vehicles), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        if (vehicles.isEmpty()) ColorDot(colors.outline) else MetaPill(stringResource(R.string.vehicle_count, vehicles.size))
                    }
                    vehicles.firstOrNull()?.let { vehicle ->
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val name = vehicle.currentStation ?: vehicle.nextStation
                            val plate = vehicle.plate.ifBlank { stringResource(R.string.no_plate) }
                            Text(if (name.isNullOrBlank()) plate else stringResource(R.string.vehicle_position, plate, name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            VehicleState(vehicle)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            RouteStrip(route, data.selected, vehicles, previewStation)
        }
    }
}

@Composable private fun RouteStrip(route: Route, selected: RouteStation?, vehicles: List<Vehicle>, preview: (RouteStation) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(route.name, route.direction) {
        val target = selected ?: vehicles.firstOrNull()?.order?.let { order -> route.stations.firstOrNull { it.order == order } }
        // 留一列余量：途中车辆要画在「两站之间」，贴左边缘会被裁掉半个图标。
        val index = (route.stations.indexOf(target) - 1).coerceAtLeast(0)
        if (route.stations.isNotEmpty()) listState.scrollToItem(index)
    }
    LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 8.dp)) {
        itemsIndexed(route.stations) { index, stop ->
            val active = stop == selected
            val buses = vehicles.filter { it.order != null && it.order == stop.order }
            val primary = MaterialTheme.colorScheme.primary
            val passed = selected?.order?.let { (stop.order ?: it) < it } == true
            val description = if (buses.isEmpty()) stop.name else stringResource(R.string.stop_vehicles_accessibility, stop.name, buses.size)
            Column(Modifier.width(64.dp).heightIn(min = 136.dp).clickable(role = Role.Button) { preview(stop) }
                .semantics(mergeDescendants = true) { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
                // 车的位置就是状态：到站画在该站圆点正上方，途中向左偏移半格，落在两站之间。
                Box(Modifier.height(24.dp).fillMaxWidth().clearAndSetSemantics {}) {
                    buses.firstOrNull()?.let { bus ->
                        val between = bus.positionState != "at-stop"
                        Row(
                            Modifier.align(Alignment.Center).offset(x = if (between) (-32).dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DirectionsBus, null, Modifier.size(18.dp), tint = primary)
                            if (buses.size > 1) Text(buses.size.toString(), style = MaterialTheme.typography.labelSmall, color = primary)
                        }
                    }
                }
                Text(stop.order?.toString().orEmpty(), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall, color = if (active) primary else MaterialTheme.colorScheme.outline)
                Box(Modifier.fillMaxWidth().height(22.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                    Box(Modifier.align(if (index == 0) Alignment.CenterEnd else Alignment.CenterStart)
                        .fillMaxWidth(if (index == 0 || index == route.stations.lastIndex) .5f else 1f).height(3.dp).background(primary.copy(alpha = .30f)))
                    Box(Modifier.size(12.dp).clip(Capsule).background(if (active) primary else MaterialTheme.colorScheme.surface)
                        .border(if (buses.isNotEmpty()) 2.dp else 1.5.dp, primary.copy(alpha = if (active || buses.isNotEmpty()) 1f else .35f), Capsule), contentAlignment = Alignment.Center) {
                        if (active) Box(Modifier.size(4.dp).clip(Capsule).background(Color.White))
                    }
                }
                Text(stop.name.chunked(1).joinToString("\n"), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp), textAlign = TextAlign.Center,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) primary else if (passed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable private fun StopRow(stop: RouteStation, selected: RouteStation?, vehicles: List<Vehicle>, onClick: () -> Unit) {
    val active = stop == selected
    val passed = selected?.order?.let { (stop.order ?: it) < it } == true
    val description = if (active) stringResource(R.string.selected_stop_accessibility, stop.name) else stop.name
    val vehicleDescription = if (vehicles.isNotEmpty()) stringResource(R.string.stop_vehicles_accessibility, description, vehicles.size) else description
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = vehicleDescription }) {
        Box(Modifier.matchParentSize().clearAndSetSemantics {}) {
            Box(Modifier.width(40.dp).fillMaxHeight()) {
                Box(Modifier.align(Alignment.Center).width(1.5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(22.dp).clip(Capsule)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Capsule), contentAlignment = Alignment.Center) {
                    Text(stop.order?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = if (active) Color.White else MaterialTheme.colorScheme.outline)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 40.dp, top = 6.dp, bottom = 8.dp, end = 4.dp)) {
            Text(stop.name, style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.primary else if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            vehicles.forEach { vehicle ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(if (vehicle.positionState == "at-stop") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(vehicle.plate.ifBlank { stringResource(R.string.no_plate) }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun ColorDot(color: Color) { Box(Modifier.size(6.dp).clip(Capsule).background(color)) }

@Composable private fun VehicleState(vehicle: Vehicle) {
    val label = stringResource(when (vehicle.positionState) { "at-stop" -> R.string.arrived; "between" -> R.string.running; else -> R.string.unknown_position })
    val color = when (vehicle.positionState) { "at-stop" -> MaterialTheme.colorScheme.primary; "between" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.outline }
    MetaPill(label, vehicle.positionState == "at-stop", color)
}

@Composable private fun VehicleCard(vehicle: Vehicle, selected: RouteStation?) {
    CardSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vehicle.plate.ifBlank { stringResource(R.string.no_plate) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                VehicleState(vehicle)
            }
            (vehicle.currentStation ?: vehicle.nextStation)?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            val boardOrder = selected?.order
            if (boardOrder != null && vehicle.order != null) {
                val count = if (vehicle.order > boardOrder) stringResource(R.string.passed) else stringResource(R.string.stops_away, boardOrder - vehicle.order)
                Text(vehicle.segmentDistance?.let { stringResource(R.string.vehicle_position, count, distanceLabel(it)) } ?: count,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vehicle.dataTime?.let { Text(stringResource(R.string.data_time, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}
