@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroStation
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.Capsule

/** 地图配色里给的是十六进制串，解析失败就退回主题色，不要因为一个色值把页面搞崩。 */
@Composable fun metroColor(hex: String): Color =
    remember(hex) {
        runCatching { Color(android.graphics.Color.parseColor(hex)) }
            .getOrElse { Color(0xFF1E6FF3) }
    }

@Composable fun MetroContent(
    state: UiState<MetroData>, select: (String) -> Unit, back: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data ?: MetroData()
    val line = data.line
    Page(line?.name ?: stringResource(R.string.metro), back, modifier) { padding ->
        if (line == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                if (state is UiState.Loading) LoadingSkeleton(cards = 2)
                else EmptyState(stringResource(R.string.metro_empty))
            }
            return@Page
        }
        val tint = metroColor(line.color)
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Top
        ) {
            item {
                Box(Modifier.padding(bottom = 10.dp)) {
                    MetroHeader(line, data.lines, data.selected, data.online, select, tint)
                }
            }
            item {
                Box(Modifier.padding(bottom = 10.dp)) {
                    SectionHeader(
                        stringResource(R.string.metro_stations),
                        line.liveStations.size,
                        // 整条线尚未运营时给短标签，同时保留全部站序。
                        if (line.pendingCount == line.liveStations.size && line.pendingCount > 0)
                            stringResource(R.string.metro_not_open) else null
                    )
                }
            }
            itemsIndexed(line.liveStations, key = { index, stop -> "$index-${stop.name}" }) { index, stop ->
                MetroStopRow(stop, index, line.liveStations.size, tint, !stop.opened)
            }
            if (data.updated.isNotBlank()) {
                item {
                    Text(
                        stringResource(R.string.metro_updated, data.updated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable private fun MetroHeader(
    line: MetroLine, lines: List<MetroLine>, selected: String, online: Boolean,
    select: (String) -> Unit, tint: Color
) {
    CardSurface {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(Capsule).background(tint))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.metro_ends, line.from, line.to), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // 只有官方接口真的返回了站序才显示；请求失败时不出现任何提示。
                if (online) {
                    Spacer(Modifier.width(8.dp))
                    MetaPill(stringResource(R.string.metro_online), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (line.first.isNotBlank() || line.last.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaPill(stringResource(R.string.first_time, line.first.ifBlank { stringResource(R.string.no_time) }), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    MetaPill(stringResource(R.string.last_time, line.last.ifBlank { stringResource(R.string.no_time) }), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (lines.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lines.forEach { item ->
                        val active = item.id == selected
                        val itemTint = metroColor(item.color)
                        // 用 Box + clickable 而不是 Surface(onClick)：后者有 48dp 最小触控高度，胶囊会被撑大。
                        Box(
                            Modifier.height(34.dp).clip(Capsule)
                                .background(if (active) itemTint.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { select(item.id) }
                                .semantics { contentDescription = item.name }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) itemTint else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 竖向站序：左侧连续轨道，站名下方显示首末班与换乘。 */
@Composable private fun MetroStopRow(stop: MetroStation, index: Int, total: Int, tint: Color, pending: Boolean) {
    val transferText = stop.transfer.joinToString(" / ")
    val stationLabel = if (stop.transfer.isEmpty()) stop.name else
        stringResource(R.string.metro_stop_transfer, stop.name, transferText)
    val firstTime = if (stop.first.isNotBlank()) stringResource(R.string.first_time, stop.first) else ""
    val lastTime = if (stop.last.isNotBlank()) stringResource(R.string.last_time, stop.last) else ""
    val pendingText = if (pending) stringResource(R.string.metro_not_open) else ""
    val label = listOf(stationLabel, firstTime, lastTime, pendingText).filter { it.isNotBlank() }.joinToString("，")
    val first = index == 0
    val last = index == total - 1
    // 未开通的站点用淡色，视觉上能和已运营的分开
    val track = tint.copy(alpha = if (pending) .16f else .38f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 52.dp)
        .clearAndSetSemantics { contentDescription = label }) {
        Column(Modifier.width(34.dp).fillMaxHeight().clearAndSetSemantics {}) {
            // 上段只画到圆点中心，下段由 weight 撑到底，圆点带底色盖住接缝 → 轨道是连的。
            if (!first) Box(Modifier.align(Alignment.CenterHorizontally).width(2.dp).height(13.dp).background(track))
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = if (first) 8.dp else 0.dp)
                    .size(if (stop.transfer.isEmpty()) 10.dp else 13.dp).clip(Capsule)
                    .background(MaterialTheme.colorScheme.background)
                    .border(2.dp, if (pending) tint.copy(alpha = .45f) else tint, Capsule)
            )
            if (!last) Box(Modifier.align(Alignment.CenterHorizontally).weight(1f).width(2.dp).background(track))
        }
        Column(Modifier.weight(1f).padding(end = 4.dp, bottom = 12.dp)) {
            Text(
                stop.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (firstTime.isNotEmpty() || lastTime.isNotEmpty() || stop.transfer.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 逐站时间按接口原文展示，空值不补造；次日时间与窄屏允许自然换行。
                    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
                    if (firstTime.isNotEmpty()) Text(firstTime, style = MaterialTheme.typography.labelSmall, color = metaColor)
                    if (lastTime.isNotEmpty()) Text(lastTime, style = MaterialTheme.typography.labelSmall, color = metaColor)
                    stop.transfer.forEach { tag -> MetaPill(tag) }
                }
            }
        }
    }
}
