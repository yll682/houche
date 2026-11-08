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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.components.CardSurface
import com.yll682.houche.ui.components.MetaPill
import com.yll682.houche.ui.components.Segments
import com.yll682.houche.ui.theme.Capsule
import java.util.Calendar
import kotlin.math.roundToInt

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
        if (showTitle) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.metro_realtime_direction, direction.destination),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 有车就说明官方还在运营，此刻再按本机时钟说「未到首班」反而会误导。
        val status = if (!direction.arrivalsAvailable) {
            stringResource(R.string.metro_realtime_failed, realtime.note)
        } else if (direction.trains.isNotEmpty()) "" else when (state) {
            MetroServiceState.NO_SERVICE -> stringResource(R.string.metro_realtime_no_service)
            MetroServiceState.NOT_STARTED -> stringResource(R.string.metro_realtime_not_started, direction.startTime)
            MetroServiceState.FINISHED -> stringResource(R.string.metro_realtime_finished, direction.endTime)
            MetroServiceState.RUNNING, MetroServiceState.WAITING -> stringResource(R.string.metro_realtime_waiting)
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        direction.trains.forEachIndexed { index, train ->
            MetroTrainRow(realtime, direction, train, index + 1)
        }
        if (direction.positions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MetroTrainStrip(realtime, direction, line, tint)
        }
    }
}

/** 一班车：官方语序「第 N 班」，主文案给站数/分钟，次要行给下一站与特殊位置。 */
@Composable
private fun MetroTrainRow(realtime: MetroRealtime, direction: MetroDirection, train: MetroTrain, order: Int) {
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
            Text(
                stringResource(R.string.metro_realtime_train_row, order),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

/** 车标宽 18dp（规格固定值）；位置一律「中心减半宽」推，不留左边缘的魔法数。 */
private val TrainMarkerWidth = 18.dp

/**
 * 「第 N 班」标签列宽：默认字号下就是 34dp——18dp 车标居中，两边各留 8dp，
 * 「第1班」三字一行放得下，不会再被挤成「第1」「班」两行让连线从字中间穿过。
 * 字号被放大时按最长的「第3班」实测跟着长，和格宽 W 同一套按实测定宽的思路。
 */
private val TrainLabelMin = 34.dp

/**
 * 全条唯一的格宽 W 的上下限：下限维持现有密度（也保证 18dp 车标与 34dp 标签列不互相压），
 * 上限防超大字号把整条撑到没法看。
 */
private val StripCellMin = 52.dp
private val StripCellMax = 96.dp
/** 竖排站名左右留的空隙，最宽那个字的笔画不贴相邻格。 */
private val StripNamePadding = 8.dp

/**
 * 实测格宽 W：竖排站名一个字一行，所以一格至少要放得下当前方向里最宽的那个字。
 * 这是条内所有几何量的唯一输入：站点/站名中心 C(i)=E+(i+.5)*W、车中心 E+(p+.5)*W、条宽 E*2+N*W。
 * 默认字号下实测约 19dp，低于下限，所以视觉密度与改前一致；只有字号被放得很大时 W 才会跟着长。
 */
@Composable
private fun rememberStripCellWidth(codes: List<String>, names: Map<String, String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, codes, names, style) {
        val widestPx = codes.maxOfOrNull { code ->
            names[code].orEmpty().chunked(1).ifEmpty { listOf("") }
                .maxOf { glyph -> measurer.measure(glyph, style = style, maxLines = 1).size.width }
        } ?: 0
        (with(density) { widestPx.toDp() } + StripNamePadding).coerceIn(StripCellMin, StripCellMax)
    }
}

/**
 * 实测「第 N 班」标签的槽位：默认字号下宽 34dp 起，字号放大时按最长的「第3班」跟着长。
 * 到站行最多三班，所以三就是这里要量的最长标签。
 * 高度也要按实测值占满一行：来自其他参考站的车没有本站班序、标签是空串，
 * 空文本只有半行高，不占满就会把它那一格的车标抬高几个 dp，和旁边的车标不在一条基线上。
 */
@Composable
private fun rememberTrainLabelSlot(): DpSize {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelSmall
    val sample = stringResource(R.string.metro_realtime_train_no, 3)
    return remember(measurer, style, sample, density) {
        val size = measurer.measure(sample, style = style, maxLines = 1).size
        with(density) { DpSize(size.width.toDp().coerceAtLeast(TrainLabelMin), size.height.toDp()) }
    }
}

/**
 * 列车位置：把官方给的「下一站 + flag」画到站序上（见 core `positionOf`）。
 *
 * 条内 dp 坐标只由 W 和端部留白 E 推：
 * - 站点/竖排站名中心 `C(i)=E+(i+.5)*W`，每格内部中心就是 W/2；
 * - 标签列宽 `T`（默认 34dp，见 `rememberTrainLabelWidth`），车标 18dp 居中放在这一列里，
 *   所以车中心 `Ctrain=E+(p+.5)*W` 仍与标签列中心重合，车标左边 `Ltrain=Ctrain-9dp`；
 * - 条宽 `E*2+N*W`，`E=max(0,(18-W)/2)` 只在格子比车标窄时兜住半车裁切，W>=18 时为 0；
 * - 锚点 `a=(r+pNear)/2`（无车取 r），锚点中心 `E+(a+.5)*W`，与车标同原点。
 * p 来自 `positionOf`，整数 i 表示第 i 站站点中心，本身不含这半个格的偏移——渲染层必须自己加。
 */
@Composable
private fun MetroTrainStrip(realtime: MetroRealtime, direction: MetroDirection, line: MetroLine, tint: Color) {
    val cells = direction.stationCodes
    if (cells.isEmpty()) return
    val names = remember(line) { line.liveStations.associate { it.code to it.name } }
    val nameStyle = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp)
    val cell = rememberStripCellWidth(cells, names, nameStyle)
    val label = rememberTrainLabelSlot()
    val edge = ((TrainMarkerWidth - cell) / 2).coerceAtLeast(0.dp)
    val width = cell * cells.size + edge * 2
    val scroll = rememberScrollState()
    val reference = cells.indexOf(realtime.stationCode)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 滚动距离按像素算，不能拿 dp 直接喂 scrollTo；密度换算自己来，避免和 Dp 扩展混淆。
        val scale = LocalDensity.current.density
        // 站序、参考站、格宽、端部留白、视口、密度任一变化都重算锚点；列车轮询变化刻意不在 key 里，
        // 免得每次刷新把用户手动滚到的位置抢回去（要「自动跟随」是另一个明确的产品约定，不在这里偷偷做）。
        LaunchedEffect(cells, realtime.stationCode, maxWidth, cell, edge, width, scale) {
            val nearest = direction.nearestPositionTo(realtime.stationCode)
                ?: direction.positions.map { direction.positionOf(it) }.filter { it >= 0 }.maxOrNull()
            val anchor = when {
                // 参考站与最前那班车取中点作锚，两边都留在视野里；没有车就居中参考站。
                reference >= 0 && nearest != null -> (reference + nearest) / 2
                reference >= 0 -> reference.toDouble()
                // 缺参考站但有有效车位时锚到最前那班：缺参考站不等于没车。
                nearest != null -> nearest
                else -> null
            }
            if (anchor != null) {
                val anchorDp = (edge + cell * (anchor + .5).toFloat()).value
                val viewportDp = maxWidth.value
                val maxScrollPx = ((width.value - viewportDp) * scale).roundToInt().coerceAtLeast(0)
                val targetPx = ((anchorDp - viewportDp / 2f) * scale).roundToInt().coerceIn(0, maxScrollPx)
                scroll.scrollTo(targetPx)
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
                        Row(Modifier.padding(horizontal = edge)) {
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
                                    // 站名竖排一字一行，与公交站点条同一套排法；再长的名字也不会被裁。
                                    Text(
                                        names[code].orEmpty().chunked(1).joinToString("\n"),
                                        style = nameStyle,
                                        color = if (code == realtime.stationCode) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (code == realtime.stationCode) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        direction.positions.forEach { train ->
                            val position = direction.positionOf(train)
                            if (position < 0) return@forEach
                            val arrivalOrder = direction.arrivalOrderOf(train)
                            val arriving = train.arriving || train.arriveSeconds < 45
                            val accent = if (arriving) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            Column(
                                Modifier.offset(
                                    x = edge + cell * (position + .5).toFloat() - label.width / 2,
                                    y = (-6).dp
                                ).width(label.width),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(Modifier.height(label.height), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (arrivalOrder == null) "" else stringResource(R.string.metro_realtime_train_no, arrivalOrder),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Box(
                                    Modifier.size(TrainMarkerWidth, 11.dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(accent)
                                ) {
                                    Box(
                                        Modifier.align(Alignment.Center).size(9.dp, 2.dp)
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
}
