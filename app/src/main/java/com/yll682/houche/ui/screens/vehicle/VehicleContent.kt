package com.yll682.houche.ui.screens.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.BusGlyph
import com.yll682.houche.ui.components.CardSurface
import com.yll682.houche.ui.components.HeaderIcon
import com.yll682.houche.ui.components.MetaPill
import com.yll682.houche.ui.components.Page
import com.yll682.houche.ui.components.PullToRefresh
import com.yll682.houche.ui.components.SectionHeader
import com.yll682.houche.ui.components.feedback
import com.yll682.houche.ui.components.touch
import com.yll682.houche.ui.theme.ArrivedGreen
import com.yll682.houche.ui.theme.Capsule

@Composable
fun VehicleContent(
    state: UiState<VehicleData>,
    back: () -> Unit,
    share: (String, String) -> Unit,
    toggleTracking: () -> Unit,
    notify: () -> Unit,
    refresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data
    val refreshLabel = stringResource(R.string.vehicle_refresh)
    val vehicles = data?.vehicles.orEmpty().sortedWith(
        compareBy<Vehicle> {
            val remaining = remainingStops(data?.boarding, it)
            when { remaining == null -> 2; remaining <= 0 -> 1; else -> 0 }
        }.thenBy {
            val remaining = remainingStops(data?.boarding, it)
            if (remaining == null) Int.MAX_VALUE else kotlin.math.abs(remaining)
        }
    )
    Page(data?.plate ?: stringResource(R.string.vehicle_title), back, modifier, actions = {
        Box(Modifier.touch(refreshLabel)) {
            HeaderIcon(Icons.Default.Refresh, refreshLabel, refresh)
        }
    }) { padding ->
        PullToRefresh(state is UiState.Loading, refresh) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                // 与站序、线路、设置页统一：左右 12dp 内边距，卡片间距 10dp。
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                feedback(state) { refresh() }
                if (data != null) {
                    item { VehicleHeader(data) }
                    if (data.boarding != null) item { RemainingCard(data) }
                    item { VehicleActions(data, toggleTracking, notify, share) }
                    item { SectionHeader(stringResource(R.string.vehicle_list)) }
                    if (vehicles.isEmpty()) item {
                        Text(
                            stringResource(R.string.vehicle_no_vehicles),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    itemsIndexed(vehicles, key = { index, vehicle -> "${vehicle.plate}-$index" }) { _, vehicle ->
                        VehicleRow(vehicle, data.boarding, vehicle.plate == data.plate)
                    }
                    data.updatedAt?.let { time -> item {
                        Text(
                            stringResource(R.string.vehicle_updated, time),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } }
                }
            }
        }
    }
}

@Composable
private fun VehicleHeader(data: VehicleData) {
    CardSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(data.route.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.vehicle_to, data.route.to), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // 车辆状态统一用 BusGlyph 表示：停站画正脸，途中画侧身。
            val current = data.vehicle
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) {
                    BusGlyph(current.positionState != "at-stop", 18.dp,
                        if (current.positionState == "at-stop") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                }
                VehicleState(current)
                if (data.offline) MetaPill(stringResource(R.string.vehicle_offline), tint = MaterialTheme.colorScheme.error)
            }
            vehicleStation(data.vehicle)?.let { station ->
                Text(station, style = MaterialTheme.typography.titleMedium)
            }
            data.vehicle?.dataTime?.takeIf { it.isNotBlank() }?.let { time ->
                Text(stringResource(R.string.data_time, time), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RemainingCard(data: VehicleData) {
    val stop = data.boarding ?: return
    val remaining = remainingStops(stop, data.vehicle)
    val color = when {
        remaining == null -> MaterialTheme.colorScheme.onSurfaceVariant
        remaining <= 0 -> if (isSystemInDarkTheme()) Color(0xFF35C79A) else ArrivedGreen
        remaining == 1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    CardSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vehicle_boarding), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stop.name, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (data.offline) R.string.vehicle_last_position else R.string.vehicle_remaining),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (remaining == null) {
                Text(stringResource(R.string.vehicle_stops_unknown), style = MaterialTheme.typography.headlineMedium, color = color)
            } else if (remaining <= 0) {
                // 到站与过站用一句话说清，比让用户读「0 站」再自己推断更直接。
                Text(
                    stringResource(if (remaining == 0) R.string.arrived else R.string.vehicle_passed),
                    style = MaterialTheme.typography.headlineLarge,
                    color = color
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(remaining.coerceAtLeast(0).toString(), Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), color = color)
                    Text(stringResource(R.string.vehicle_stop_unit), Modifier.padding(start = 6.dp).alignByBaseline(),
                        style = MaterialTheme.typography.titleMedium, color = color)
                }
            }
            // 只显示这辆车的上游短标签，不按站数估算分钟；上游没给就整行不出现，不写「未知」占位。
            data.arrival?.timeText?.trim()?.takeIf { it.isNotEmpty() && it.length <= 6 }?.let { time ->
                Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun VehicleActions(data: VehicleData, toggleTracking: () -> Unit, notify: () -> Unit, share: (String, String) -> Unit) {
    val trackingLabel = stringResource(if (data.tracking) R.string.vehicle_tracking_cancel else R.string.vehicle_tracking_start)
    val notifyLabel = stringResource(R.string.vehicle_notify)
    val shareLabel = stringResource(R.string.vehicle_share)
    val shareTitle = stringResource(R.string.vehicle_share_title)
    val station = vehicleStation(data.vehicle) ?: stringResource(R.string.unknown_position)
    val shareText = stringResource(R.string.vehicle_share_text, data.plate, data.route.name, station)
    // 360dp 窄屏三按钮并排：文案压到 2~3 字，单行不折行，字号统一 labelLarge。
    val labelStyle = MaterialTheme.typography.labelLarge
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(toggleTracking, Modifier.weight(1f).touch(trackingLabel), shape = Capsule,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
            Text(trackingLabel, style = labelStyle, maxLines = 1, softWrap = false)
        }
        OutlinedButton(notify, Modifier.weight(1f).touch(notifyLabel), shape = Capsule,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
            Text(notifyLabel, style = labelStyle, maxLines = 1, softWrap = false)
        }
        OutlinedButton({ share(shareText, shareTitle) }, Modifier.weight(1f).touch(shareLabel), shape = Capsule,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
            Text(shareLabel, style = labelStyle, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun VehicleRow(vehicle: Vehicle, boarding: RouteStation?, selected: Boolean) {
    val remaining = remainingStops(boarding, vehicle)
    val running = vehicle.positionState != "at-stop"
    val tint = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    CardSurface {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 当前这辆车靠左侧 3dp 竖条识别：小字号下只换字重看不出来。
            if (selected) Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.primary))
            BusGlyph(running, 18.dp, tint)
            Text(vehicle.plate.ifBlank { stringResource(R.string.no_plate) }, Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VehicleState(vehicle, remaining)
                // 已经过站的只说一次：胶囊写「已过站」，这里就不再重复。
                when {
                    remaining == null -> Text(stringResource(R.string.vehicle_stops_unknown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    remaining > 0 -> Text(stringResource(R.string.vehicle_stops, remaining), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun VehicleState(vehicle: Vehicle?, remaining: Int? = null) {
    val label = stringResource(when {
        // 0 站是「刚好到站」，负数才是「已经开过去」。
        remaining != null && remaining < 0 -> R.string.vehicle_passed
        remaining == 0 || vehicle?.positionState == "at-stop" -> R.string.arrived
        vehicle?.positionState == "between" -> R.string.running
        else -> R.string.unknown_position
    })
    val color = when (vehicle?.positionState) {
        "at-stop" -> MaterialTheme.colorScheme.primary
        "between" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    MetaPill(label, tint = color)
}

private fun vehicleStation(vehicle: Vehicle?): String? = when (vehicle?.positionState) {
    "between" -> vehicle.nextStation?.takeIf { it.isNotBlank() } ?: vehicle.currentStation?.takeIf { it.isNotBlank() }
    else -> vehicle?.currentStation?.takeIf { it.isNotBlank() } ?: vehicle?.nextStation?.takeIf { it.isNotBlank() }
}
