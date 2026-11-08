package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.components.CardSurface
import com.yll682.houche.ui.components.MetaPill
import com.yll682.houche.ui.components.Segments
import com.yll682.houche.ui.theme.Capsule
import java.util.Calendar

/**
 * 实时到站卡片：官方 App 的「列车位置」页给的是下一班车与它在站序上的位置，
 * 这里保留同样的信息量，但把两个方向收进卡片里，跟线路、站序放在同一屏。
 */
@Composable
fun MetroRealtimeCard(
    realtime: MetroRealtime?,
    loading: Boolean,
    note: String,
    updatedAt: String,
    stationName: String,
    line: MetroLine,
    directionIndex: Int,
    onDirection: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = metroColor(line.color)
    CardSurface(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.metro_realtime),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (updatedAt.isNotBlank()) {
                    Text(
                        stringResource(R.string.metro_realtime_updated, updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    stationName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.metro_realtime_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            val directions = realtime?.directions.orEmpty()
            when {
                realtime == null && loading -> Text(
                    stringResource(R.string.metro_realtime_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                realtime == null -> Text(
                    stringResource(R.string.metro_realtime_failed, note.ifBlank { "未知原因" }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                directions.isEmpty() -> Text(
                    stringResource(R.string.metro_realtime_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    if (directions.size > 1) {
                        Segments(
                            directions.map { stringResource(R.string.metro_realtime_direction, it.destination) },
                            directionIndex.coerceIn(0, directions.lastIndex),
                            onDirection
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    MetroDirectionBlock(realtime, directions[directionIndex.coerceIn(0, directions.lastIndex)], line, tint, showTitle = directions.size <= 1)
                }
            }
            if (realtime != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.metro_realtime_tip),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 一个方向：运营状态、接下来三班车，以及列车在站序上的位置。 */
@Composable
private fun MetroDirectionBlock(
    realtime: MetroRealtime,
    direction: MetroDirection,
    line: MetroLine,
    tint: Color,
    showTitle: Boolean
) {
    val now = remember(direction) { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }
    val state = remember(direction, now) { metroServiceState(direction.startTime, direction.endTime, now) }
    Column {
        if (showTitle || direction.startTime.isNotBlank() || direction.endTime.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (showTitle) {
                    Text(
                        stringResource(R.string.metro_realtime_direction, direction.destination),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (direction.startTime.isNotBlank()) {
                    MetaPill(stringResource(R.string.first_time, direction.startTime), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                if (direction.endTime.isNotBlank()) {
                    MetaPill(stringResource(R.string.last_time, direction.endTime), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 有车就说明官方还在运营，此刻再按本机时钟说「未到首班」反而会误导。
        val status = if (direction.trains.isNotEmpty()) "" else when (state) {
            MetroServiceState.NO_SERVICE -> stringResource(R.string.metro_realtime_no_service)
            MetroServiceState.NOT_STARTED -> stringResource(R.string.metro_realtime_not_started, direction.startTime)
            MetroServiceState.FINISHED -> stringResource(R.string.metro_realtime_finished, direction.endTime)
            MetroServiceState.RUNNING, MetroServiceState.WAITING -> stringResource(R.string.metro_realtime_waiting)
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        direction.trains.forEach { train ->
            MetroTrainRow(realtime, direction, train)
        }
        if (direction.trains.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MetroTrainStrip(realtime, direction, line, tint)
        }
    }
}

/** 一班车：还剩几站、还要几分钟，下一站是哪一站。 */
@Composable
private fun MetroTrainRow(realtime: MetroRealtime, direction: MetroDirection, train: MetroTrain) {
    val stops = realtime.remainingStops(direction, train)
    val minutes = minutesUntil(train.arriveSeconds)
    // 下一站就是本站、而且车已经进站，才叫「即将到站」；还在上一段路上就报分钟数。
    val soon = stops == 0 && (train.arriving || train.arriveSeconds < 45)
    val label = when {
        soon -> stringResource(R.string.metro_realtime_arrive_soon)
        stops > 0 -> stringResource(R.string.metro_realtime_stops, stops)
        else -> stringResource(R.string.metro_realtime_minutes, minutes)
    }
    val accent = when {
        soon -> MaterialTheme.colorScheme.tertiary
        stops >= 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detail = buildList {
        if (stops > 0 && train.nextStationName.isNotBlank()) add(train.nextStationName)
        if (train.specialPositionDesc.isNotBlank()) add(train.specialPositionDesc)
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(Capsule).background(accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!soon) {
            Text(
                stringResource(R.string.metro_realtime_minutes_short, minutes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 列车位置：把官方给的「下一站 + 段内比例」画到站序上。
 * 比例是「离下一站还差多少」，所以车在下一站前一格的位置 = 下一站下标 - 比例。
 */
@Composable
private fun MetroTrainStrip(realtime: MetroRealtime, direction: MetroDirection, line: MetroLine, tint: Color) {
    val cells = direction.stationCodes
    if (cells.isEmpty()) return
    val names = remember(line) { line.liveStations.associate { it.code to it.name } }
    val scroll = rememberScrollState()
    val cell = 58.dp
    val width = cell * cells.size
    val reference = cells.indexOf(realtime.stationCode)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 滚动距离按像素算，不能拿 dp 直接喂 scrollTo；密度换算自己来，避免和 Dp 扩展混淆。
        val scale = LocalDensity.current.density
        LaunchedEffect(direction.destination, realtime.stationCode, maxWidth) {
            if (reference >= 0) {
                // 参考站与最近一班车取中点作锚，两边都留在视野里；没有车就居中参考站。
                val nearest = direction.trains.map { direction.positionOf(it) }.filter { it >= 0 }.maxOrNull()
                val anchor = if (nearest == null) reference.toDouble() else (nearest + reference) / 2
                val cellPx = 58f * scale
                val viewportPx = maxWidth.value * scale
                val centerPx = (anchor * cellPx + cellPx / 2f).toFloat()
                scroll.scrollTo((centerPx - viewportPx / 2f).toInt().coerceAtLeast(0))
            }
        }
        Column {
            Text(
                stringResource(R.string.metro_realtime_position),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.horizontalScroll(scroll)) {
                    Box(Modifier.width(width)) {
                        Row(Modifier.width(width)) {
                            cells.forEachIndexed { index, code ->
                                Column(Modifier.width(cell), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.height(18.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            if (index > 0) {
                                                Box(Modifier.weight(1f).height(2.dp).background(tint.copy(alpha = .32f)))
                                            }
                                            Box(Modifier.size(12.dp))
                                            if (index < cells.lastIndex) {
                                                Box(Modifier.weight(1f).height(2.dp).background(tint.copy(alpha = .32f)))
                                            }
                                        }
                                        val current = code == realtime.stationCode
                                        Box(
                                            Modifier.size(if (current) 12.dp else 9.dp).clip(Capsule)
                                                .background(MaterialTheme.colorScheme.surface)
                                                .border(2.dp, if (current) tint else tint.copy(alpha = .45f), Capsule)
                                        )
                                    }
                                    Text(
                                        names[code].orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (code == realtime.stationCode) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (code == realtime.stationCode) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    )
                                }
                            }
                        }
                        direction.trains.forEach { train ->
                            val offset = direction.positionOf(train)
                            if (offset < 0) return@forEach
                            val arriving = train.arriving || train.arriveSeconds < 45
                            val accent = if (arriving) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            Box(
                                Modifier.offset(x = (offset * 58).dp - 8.dp, y = 4.dp)
                                    .size(16.dp, 10.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(accent)
                                    .semantics { contentDescription = "" }
                            ) {
                                Box(
                                    Modifier.align(Alignment.Center).size(8.dp, 2.dp)
                                        .background(MaterialTheme.colorScheme.surface)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}