package com.yll682.houche.ui.screens.vehicle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.RouteStation
import com.yll682.houche.core.Vehicle
import com.yll682.houche.core.VehicleShareLink
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.CardSurface
import com.yll682.houche.ui.components.Hairline
import com.yll682.houche.ui.components.HeaderIcon
import com.yll682.houche.ui.components.MetaPill
import com.yll682.houche.ui.components.Page
import com.yll682.houche.ui.components.PullToRefresh
import com.yll682.houche.ui.components.RouteStrip
import com.yll682.houche.ui.components.SectionHeader
import com.yll682.houche.ui.components.TelegramSheet
import com.yll682.houche.ui.components.TelegramQuint
import com.yll682.houche.ui.components.TelegramText
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
    selectVehicle: (String) -> Unit = {},
    selectAlight: (RouteStation) -> Unit = {},
    clearAlight: () -> Unit = {},
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
    // 点站 → 选下车提醒站；操作区的「下车提醒站」→ 打开站表选择。
    var sheetStop by remember { mutableStateOf<RouteStation?>(null) }
    var sheetPicker by remember { mutableStateOf(false) }
    Page(data?.plate ?: stringResource(R.string.vehicle_title), back, modifier, actions = {
        Box(Modifier.touch(refreshLabel)) {
            HeaderIcon(Icons.Outlined.Refresh, refreshLabel, refresh)
        }
    }) { padding ->
        PullToRefresh(state is UiState.Loading, refresh) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                // 与站序、线路、设置页统一：左右 12dp 内边距，卡片间距 10dp。
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                feedback(state, hasContent = true) { refresh() }
                if (data != null) {
                    item { VehicleHeader(data) }
                    if (data.route.stations.isNotEmpty()) {
                        item {
                            VehicleMapCard(
                                data,
                                onStationClick = { stop -> sheetStop = stop },
                                onVehicleClick = { vehicle -> selectVehicle(vehicle.plate) }
                            )
                        }
                    }
                    if (data.alight != null || data.boarding != null) item { RemainingCard(data) }
                    item {
                        VehicleActions(
                            data,
                            toggleTracking,
                            notify,
                            share,
                            openAlight = { sheetPicker = true }
                        )
                    }
                    item { SectionHeader(stringResource(R.string.vehicle_list)) }
                    if (vehicles.isEmpty()) item {
                        Text(
                            stringResource(R.string.vehicle_no_vehicles),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    itemsIndexed(vehicles, key = { index, vehicle -> "${vehicle.plate}-$index" }) { _, vehicle ->
                        VehicleRow(vehicle, data.boarding, vehicle.plate == data.plate) { selectVehicle(vehicle.plate) }
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
    sheetStop?.let { stop ->
        val remaining = remainingStops(stop, data?.vehicle)
        val stops = when {
            remaining == null -> stringResource(R.string.vehicle_map_stops_unknown)
            remaining < 0 -> stringResource(R.string.vehicle_map_passed)
            remaining == 0 -> stringResource(R.string.vehicle_map_arrived)
            else -> stringResource(R.string.vehicle_map_stops_away, remaining, stop.name)
        }
        TelegramSheet(dismiss = { sheetStop = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stop.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(stops, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(
                    { selectAlight(stop); sheetStop = null },
                    Modifier.fillMaxWidth().height(48.dp).touch(stringResource(R.string.vehicle_map_alight_set)),
                    shape = Capsule
                ) { Text(stringResource(R.string.vehicle_map_alight_set)) }
                if (data?.alight != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        { clearAlight(); sheetStop = null },
                        Modifier.fillMaxWidth().height(48.dp).touch(stringResource(R.string.vehicle_map_alight_clear)),
                        shape = Capsule
                    ) { Text(stringResource(R.string.vehicle_map_alight_clear)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (sheetPicker && data != null) {
        TelegramSheet(dismiss = { sheetPicker = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.vehicle_map_alight_picker), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.vehicle_map_alight_picker_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (data.alight != null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.vehicle_map_alight_title) + " · " + data.alight.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            { clearAlight(); sheetPicker = false },
                            Modifier.touch(stringResource(R.string.vehicle_map_alight_clear))
                        ) { Text(stringResource(R.string.vehicle_map_alight_clear)) }
                    }
                    Hairline()
                }
                data.route.stations.forEach { stop ->
                    val picked = stop == data.alight
                    Row(
                        Modifier.fillMaxWidth().clickable { selectAlight(stop); sheetPicker = false }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stop.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (picked) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(16.dp))
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
            val current = data.vehicle
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) {
                    Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(18.dp),
                        tint = if (current.positionState == "at-stop") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
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

/**
 * 站点图卡片：这辆车现在在站点图的哪一站一目了然，同时就地设定下车提醒站。
 * 用共享的 [RouteStrip]：高亮＝下车提醒站，焦点车＝当前这辆车。
 */
@Composable
private fun VehicleMapCard(
    data: VehicleData,
    onStationClick: (RouteStation) -> Unit,
    onVehicleClick: (Vehicle) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    CardSurface {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vehicle_map_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                Text(data.plate.ifBlank { stringResource(R.string.no_plate) },
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            // 上游两套接口一个给当前站、一个给下一站：哪个有值就写哪个，两个都没有就不写这一行。
            val current = data.vehicle?.currentStation?.takeIf { it.isNotBlank() }
            val next = data.vehicle?.nextStation?.takeIf { it.isNotBlank() }
            if (current != null || next != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        current?.let { stringResource(R.string.vehicle_map_current, it) },
                        next?.let { stringResource(R.string.vehicle_map_next, it) }
                    ).joinToString(" · "),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            RouteStrip(
                route = data.route,
                vehicles = data.vehicles,
                highlight = data.alight,
                focusedPlate = data.plate,
                onStationClick = onStationClick,
                onVehicleClick = onVehicleClick
            )
        }
    }
}

@Composable
private fun RemainingCard(data: VehicleData) {
    // 有下车提醒站就按它算，没有才退回乘车站。
    val stop = data.alight ?: data.boarding ?: return
    val remaining = remainingStops(stop, data.vehicle)
    val color = when {
        remaining == null -> MaterialTheme.colorScheme.onSurfaceVariant
        remaining <= 0 -> if (isSystemInDarkTheme()) Color(0xFF35C79A) else ArrivedGreen
        remaining == 1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    CardSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 标题、站名、状态文案都走 TelegramText：换字时逐字形替换，不再整行硬切。
            TelegramText(
                stringResource(if (data.alight != null) R.string.vehicle_map_alight_title else R.string.vehicle_boarding),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TelegramText(stop.name, style = MaterialTheme.typography.titleLarge)
            TelegramText(stringResource(if (data.offline) R.string.vehicle_last_position else R.string.vehicle_remaining),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (remaining == null) {
                TelegramText(stringResource(R.string.vehicle_stops_unknown),
                    style = MaterialTheme.typography.headlineMedium, color = color)
            } else if (remaining <= 0) {
                // 到站与过站用一句话说清，比让用户读「0 站」再自己推断更直接。
                TelegramText(
                    stringResource(when {
                        remaining < 0 -> R.string.vehicle_passed
                        data.vehicle?.positionState == "between" -> R.string.arriving_soon
                        else -> R.string.arrived
                    }),
                    style = MaterialTheme.typography.headlineLarge,
                    color = color
                )
            } else {
                RemainingStops(remaining.coerceAtLeast(0), color)
            }
            // 只显示这辆车的上游短标签，不按站数估算分钟；上游没给就整行不出现，不写「未知」占位。
            data.arrival?.timeText?.trim()?.takeIf { it.isNotEmpty() && it.length <= 6 }?.let { time ->
                Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/**
 * 剩余站数：大数字滚动 + 单位「站」。
 *
 * 数字用 TelegramText（数字会上下滚动过渡，仓库既有积木）。
 * 单位仍是普通文字：Canvas 报不出基线，所以按两行文字实测的基线差补一个下边距，
 * 让「站」落在数字的基线上，而不是被行底对齐拖到中间。
 */
@Composable
private fun RemainingStops(stops: Int, color: Color) {
    val number = stops.toString()
    val unit = stringResource(R.string.vehicle_stop_unit)
    val numberStyle = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum")
    val unitStyle = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val unitBottom = with(density) {
        val numberLayout = measurer.measure(AnnotatedString(number), numberStyle, maxLines = 1, overflow = TextOverflow.Clip)
        val unitLayout = measurer.measure(AnnotatedString(unit), unitStyle, maxLines = 1, overflow = TextOverflow.Clip)
        ((numberLayout.size.height - numberLayout.firstBaseline) -
            (unitLayout.size.height - unitLayout.firstBaseline)).toDp()
    }
    Row(verticalAlignment = Alignment.Bottom) {
        TelegramText(number, style = numberStyle, color = color, duration = 240, amplitude = .85f)
        Text(unit, Modifier.padding(start = 6.dp, bottom = unitBottom),
            style = unitStyle, color = color)
    }
}

@Composable
private fun VehicleActions(
    data: VehicleData,
    toggleTracking: () -> Unit,
    notify: () -> Unit,
    share: (String, String) -> Unit,
    openAlight: () -> Unit
) {
    val trackingLabel = stringResource(if (data.tracking) R.string.vehicle_map_track_stop else R.string.vehicle_map_track_start)
    val notifyLabel = stringResource(R.string.vehicle_notify)
    val shareLabel = stringResource(R.string.vehicle_share)
    val shareTitle = stringResource(R.string.vehicle_share_title)
    val station = vehicleStation(data.vehicle) ?: stringResource(R.string.unknown_position)
    // 分享出去的是 https 链接：聊天软件能识别成链接，服务器再决定要不要跳进 App。
    val shareLink = VehicleShareLink.buildWebLink(
        cityCode = data.cityCode,
        lineId = data.route.id.takeIf { it.isNotBlank() },
        lineName = data.route.name,
        direction = data.route.direction,
        plate = data.plate
    )
    val shareText = stringResource(R.string.vehicle_share_text_link, data.plate, data.route.name, station, shareLink)
    // 乘车站与下车提醒站都能作为跟踪目标，任一存在即可开启跟踪。
    val canTrack = data.boarding != null || data.alight != null
    val alightLabel = data.alight?.name ?: stringResource(R.string.vehicle_map_alight_unset)
    // 跟踪开启后文案与图标一起变成主色：一眼看得出「已经开着了」。
    val trackingLabelColor by animateColorAsState(
        when {
            !canTrack -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
            data.tracking -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        tween(200, easing = TelegramQuint),
        label = "tracking-label-color"
    )
    CardSurface {
        ActionRow(
            Icons.Outlined.NotificationsActive, trackingLabel, canTrack, toggleTracking,
            trailing = null,
            labelColor = trackingLabelColor,
            animatedLabel = true,
            trailingContent = { TrackingTrailingIcon(data.tracking) }
        )
        Hairline(Modifier.padding(start = 56.dp))
        ActionRow(Icons.Outlined.Flag, stringResource(R.string.vehicle_map_alight_title), data.route.stations.isNotEmpty(), openAlight, Icons.Outlined.ChevronRight, alightLabel,
            animatedValue = true)
        Hairline(Modifier.padding(start = 56.dp))
        ActionRow(Icons.Outlined.Notifications, notifyLabel, true, notify, Icons.Outlined.ChevronRight)
        Hairline(Modifier.padding(start = 56.dp))
        ActionRow(Icons.Outlined.Share, shareLabel, true, { share(shareText, shareTitle) }, Icons.Outlined.ChevronRight)
    }
    // 「后台跟踪中」胶囊：淡入 + 轻微上移出现，消失时淡出，不要突然蹦出来。
    AnimatedVisibility(
        visible = data.backgroundActive,
        enter = fadeIn(tween(150)) +
            slideInVertically(tween(200, easing = TelegramQuint)) { it / 6 } +
            expandVertically(tween(200, easing = TelegramQuint)),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(180, easing = TelegramQuint))
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MetaPill(stringResource(R.string.vehicle_map_background_active))
        }
    }
    Text(
        stringResource(
            when {
                data.alight != null -> R.string.vehicle_map_scope_alight
                data.boarding != null -> R.string.vehicle_tracking_scope
                else -> R.string.vehicle_map_choose_alight
            },
            data.alight?.name.orEmpty()
        ),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: ImageVector?,
    value: String? = null,
    labelColor: Color? = null,
    animatedLabel: Boolean = false,
    animatedValue: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
    val textColor = labelColor
        ?: if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
    Row(
        Modifier.fillMaxWidth().touch(label).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        if (animatedLabel) {
            // 文案换字也要平滑：TelegramText 逐字形替换，「跟踪这辆车 ↔ 停止跟踪」不再硬切。
            Box(Modifier.weight(1f)) {
                TelegramText(label, style = MaterialTheme.typography.bodyLarge, color = textColor)
            }
        } else {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = textColor)
        }
        if (value != null) {
            if (animatedValue) {
                // 值也是硬切的一处：站名换成 TelegramText，逐字形替换。
                // weight(fill = false) 只占一份空间：站名再长也不会把行尾的箭头挤没。
                Box(Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                    TelegramText(value, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(value, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailingContent != null) {
            trailingContent()
        } else if (trailing != null) {
            Icon(trailing, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 跟踪开关的尾部图标：勾 ↔ 箭头之间做过渡，不再硬切。
 *
 * 勾从 0.6 缩到 1 并淡入，箭头同步淡出缩到 0.6；颜色一起过渡到 primary，
 * 选中态一眼能认出来。缓动用仓库既有的 TelegramQuint，时长对齐时刻表页的折叠动画。
 */
@Composable
private fun TrackingTrailingIcon(tracking: Boolean) {
    val progress by animateFloatAsState(
        if (tracking) 1f else 0f,
        tween(200, easing = TelegramQuint),
        label = "tracking-icon"
    )
    val tint by animateColorAsState(
        if (tracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(200, easing = TelegramQuint),
        label = "tracking-icon-tint"
    )
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.ChevronRight, null,
            Modifier.size(22.dp).graphicsLayer {
                alpha = 1f - progress
                scaleX = 1f - .4f * progress
                scaleY = 1f - .4f * progress
            },
            tint = tint
        )
        Icon(
            Icons.Outlined.Check, null,
            Modifier.size(22.dp).graphicsLayer {
                alpha = progress
                scaleX = .6f + .4f * progress
                scaleY = .6f + .4f * progress
            },
            tint = tint
        )
    }
}

@Composable
private fun VehicleRow(vehicle: Vehicle, boarding: RouteStation?, selected: Boolean, select: () -> Unit) {
    val remaining = remainingStops(boarding, vehicle)
    val running = vehicle.positionState != "at-stop"
    val tint = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    CardSurface(onClick = select) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 当前这辆车靠左侧 3dp 竖条识别：小字号下只换字重看不出来。
            if (selected) Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.primary))
            Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(18.dp), tint = tint)
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
        remaining == 0 && vehicle?.positionState == "between" -> R.string.arriving_soon
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
