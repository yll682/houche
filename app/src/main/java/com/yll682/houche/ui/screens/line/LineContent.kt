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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.*
import kotlin.math.abs

@Composable fun LineContent(state: UiState<LineData>, city: City, panel: Int, fare: Boolean, selectPanel: (Int) -> Unit, toggleFare: () -> Unit, back: () -> Unit, timetable: (Route) -> Unit, favorite: () -> Unit, share: (String, String) -> Unit, retry: () -> Unit, reverse: () -> Unit, previewStation: (RouteStation) -> Unit, select: (RouteStation) -> Unit, closePreview: () -> Unit, modifier: Modifier = Modifier) {
    val data = state.data ?: LineData()
    val route = data.route
    val preview = data.preview
    Page(route?.name ?: stringResource(R.string.lines), back, modifier = modifier, actions = {
        ActionIcon(if (data.favorite) Icons.Default.Star else Icons.Default.StarOutline, stringResource(if (data.favorite) R.string.unfavorite else R.string.favorite)) { favorite() }
        val text = route?.let { stringResource(R.string.share_text, city.name, it.name, it.to, it.first, it.last) }
        val shareTitle = stringResource(R.string.share)
        ActionIcon(Icons.Default.Share, shareTitle) { if (text != null) share(text, shareTitle) }
    }) { padding ->
        PullToRefresh(state is UiState.Loading, retry) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                feedback(state) { retry() }
                if (route != null) {
                    item {
                        Card(shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.weight(1f)) { LineBadge(route.name, true) }
                                    TextButton(reverse, Modifier.touch(stringResource(R.string.reverse)), enabled = state !is UiState.Loading) { Icon(Icons.Default.SwapVert, stringResource(R.string.reverse)); Text(stringResource(R.string.reverse)) }
                                }
                                Text(stringResource(R.string.to, route.to), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.service_times, route.first.ifBlank { stringResource(R.string.no_time) }, route.last.ifBlank { stringResource(R.string.no_time) }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    TextButton(toggleFare, Modifier.touch(stringResource(R.string.collapse_fare))) { Icon(Icons.Default.Info, stringResource(R.string.fare)); Text(stringResource(R.string.fare)) }
                                    if (route.showTimetable) TextButton({ timetable(route) }, Modifier.touch(stringResource(R.string.timetable))) { Text(stringResource(R.string.timetable)) }
                                }
                                if (fare) Text(route.fareDescription.ifBlank { stringResource(R.string.fare_empty) })
                            }
                        }
                    }
                    item {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (data.selected == null) Text(stringResource(R.string.select_boarding), style = MaterialTheme.typography.titleMedium)
                                else {
                                    Text(stringResource(R.string.boarding, data.selected.name), style = MaterialTheme.typography.titleMedium)
                                    ArrivalText(data.arrivals.firstOrNull(), large = true)
                                    data.live?.nextDeparture?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.next_departure, it)) }
                                    data.live?.notice?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    item { Segments(listOf(stringResource(R.string.stops), stringResource(R.string.vehicles)), panel) { selectPanel(it) } }
                    if (panel == 0) {
                        if (route.stations.isEmpty()) item { EmptyState(stringResource(R.string.no_route_stops)) }
                        items(route.stations) { stop ->
                            val buses = data.live?.vehicles.orEmpty().filter { it.order == stop.order }
                            val passed = data.selected?.order?.let { selected -> (stop.order ?: selected) < selected } == true
                            Column(Modifier.animateItem().fillMaxWidth().touch(stop.name).clickable { previewStation(stop) }.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.width(2.dp).height(12.dp).background(MaterialTheme.colorScheme.outlineVariant))
                                        Surface(shape = Capsule, color = if (data.selected == stop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh) { Text(stop.order?.toString() ?: stringResource(R.string.order_unknown), Modifier.padding(8.dp)) }
                                        Box(Modifier.width(2.dp).height(12.dp).background(MaterialTheme.colorScheme.outlineVariant))
                                    }
                                    Text(stop.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                    if (data.selected == stop) Icon(Icons.Default.MyLocation, stringResource(R.string.current_stop), tint = MaterialTheme.colorScheme.primary)
                                }
                                buses.forEach { vehicle -> Row(Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.size(8.dp).clip(Capsule).background(if (vehicle.positionState == "at-stop") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary))
                                    Text(stringResource(R.string.vehicle_position, vehicle.plate.ifBlank { stringResource(R.string.no_plate) }, stringResource(if (vehicle.positionState == "at-stop") R.string.at_stop else if (vehicle.positionState == "between") R.string.between else R.string.unknown_position)), style = MaterialTheme.typography.bodySmall)
                                } }
                            }
                        }
                    } else {
                        if (data.live?.vehicles.isNullOrEmpty()) item { EmptyState(stringResource(if (data.selected == null) R.string.select_boarding else R.string.no_vehicles)) }
                        items(data.live?.vehicles.orEmpty()) { vehicle ->
                            Card(modifier = Modifier.animateItem(), shape = MaterialTheme.shapes.large) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(vehicle.plate.ifBlank { stringResource(R.string.no_plate) }, style = MaterialTheme.typography.titleMedium)
                                    val stop = vehicle.currentStation ?: route.stations.firstOrNull { it.order == vehicle.order }?.name ?: vehicle.nextStation.orEmpty()
                                    Text(stringResource(R.string.vehicle_position, stop, stringResource(when (vehicle.positionState) { "at-stop" -> R.string.at_stop; "between" -> R.string.between; else -> R.string.unknown_position })))
                                    val selected = data.selected?.order
                                    vehicle.order?.let { order -> if (selected != null) Text(stringResource(if (order > selected) R.string.passed else R.string.stops_away, abs(selected - order))) }
                                    vehicle.segmentDistance?.let { Text(stringResource(R.string.vehicle_distance, distanceLabel(it))) }
                                    vehicle.dataTime?.let { Text(stringResource(R.string.data_time, it), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.live_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    preview?.let { stop ->
        ModalBottomSheet(onDismissRequest = closePreview) {
            Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stop.name, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.station_arrivals), style = MaterialTheme.typography.labelLarge)
                if (data.previewLoading) LoadingSkeleton()
                else if (data.previewFailed) ErrorCard(data.previewError) { previewStation(stop) }
                else if (data.previewArrivals.isEmpty()) ArrivalText(null)
                else data.previewArrivals.take(3).forEach { ArrivalText(it) }
                Button({ select(stop) }, Modifier.fillMaxWidth().touch(stringResource(R.string.set_boarding)), shape = Capsule) { Text(stringResource(R.string.set_boarding)) }
            }
        }
    }
}
