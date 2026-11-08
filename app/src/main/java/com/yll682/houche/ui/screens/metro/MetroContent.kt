@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroStation
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.EmptyState
import com.yll682.houche.ui.components.LoadingSkeleton
import com.yll682.houche.ui.theme.Capsule

/** 地图配色里给的是十六进制串，解析失败就退回主题色，不要因为一个色值把页面搞崩。 */
@Composable fun metroColor(hex: String): Color =
    remember(hex) {
        runCatching { Color(android.graphics.Color.parseColor(hex)) }
            .getOrElse { OfficialLineFallback }
    }

/**
 * 地铁「列车位置」页：照官方厦门地铁 App 的版式做（见 `notes/spec-official-train-position.md`）。
 *
 * 骨架：白顶栏（居中标题 + 细 chevron）→ 线路 tab 条（色块徽标 + 方形下划线）→
 * 白卡片（方向行 / 站名+刷新 / ETA 蓝块 / 位置条）→ 卡片外居中免责说明 → 站序列表。
 *
 * 站序列表官方这页没有，但候车用它在换参考站（`selectStation` 的唯一入口），
 * 删掉就没有切站能力了，所以保留在免责说明下面，样式沿用原有控件不动。
 */
@Composable
fun MetroContent(
    state: UiState<MetroData>, select: (String) -> Unit, back: () -> Unit,
    selectStation: (String) -> Unit = {}, selectDirection: (Int) -> Unit = {},
    refresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val data = state.data ?: MetroData()
    // 站序跟随所选方向（始发在前），与实时卡片的「开往 XX」讲同一个方向。
    val line = data.orderedLine
    val heading = data.line
    val palette = officialPalette()
    val type = officialTypography()
    Column(modifier.fillMaxSize().background(palette.Page)) {
        MetroTopBar(stringResource(R.string.metro_realtime_position), back)
        MetroLineTabs(data.lines, data.selected, select)
        if (line == null || heading == null) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                if (state is UiState.Loading) LoadingSkeleton(cards = 2)
                else EmptyState(stringResource(R.string.metro_empty))
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                MetroRealtimeCard(
                    realtime = data.realtime,
                    loading = data.realtimeLoading,
                    note = data.realtimeNote,
                    stationName = data.realtimeStationName.ifBlank { line.stations.firstOrNull()?.name.orEmpty() },
                    line = heading,
                    directionIndex = data.directionIndex,
                    onDirection = selectDirection,
                    onRefresh = refresh,
                    onSelectStation = selectStation,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp)
                )
            }
            item {
                // 免责说明在卡片外面，居中、珊瑚红——官方这页就是这样（规格 2.7）。
                Text(
                    stringResource(R.string.metro_realtime_tip),
                    Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 15.3.dp),
                    style = type.Disclaimer,
                    textAlign = TextAlign.Center
                )
            }
            item {
                // 官方这页没有站序列表，这段是候车切换参考站的唯一入口，沿用原信息层级；
                // 颜色全走本页调色板，深色下跟着翻（官方没有这一块，深色取值是我们自己定的）。
                val pending = line.pendingCount == line.stations.size && line.pendingCount > 0
                Text(
                    listOfNotNull(
                        stringResource(R.string.group_count, stringResource(R.string.metro_stations), line.stations.size),
                        stringResource(R.string.metro_not_open).takeIf { pending }
                    ).joinToString("  "),
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp),
                    style = type.SectionLabel
                )
            }
            itemsIndexed(line.stations, key = { index, stop -> "$index-${stop.name}" }) { index, stop ->
                MetroStopRow(
                    stop, index, line.stations.size, officialLineColor(heading), !stop.opened,
                    selected = stop.code.isNotBlank() && stop.code == data.realtimeStation
                ) { selectStation(stop.code) }
            }
            if (data.updated.isNotBlank()) {
                item {
                    Text(
                        stringResource(R.string.metro_updated, data.updated),
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
                        style = type.MetaLabel
                    )
                }
            }
        }
    }
}

/** 站序行里的小胶囊（换乘线路 / 参考站）：填充 alpha 与线路色一起跟着明暗模式走。 */
@Composable private fun StopPill(text: String, tint: Color? = null) {
    val palette = officialPalette()
    val type = officialTypography()
    val color = tint?.let { listLineColor(it, palette) } ?: palette.SubInk
    val fill = if (tint == null) palette.PillFillAlpha else palette.PillTintAlpha
    Box(
        Modifier.background(color.copy(alpha = fill), Capsule)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = type.MetaLabel.copy(color = color), maxLines = 1)
    }
}

/** 竖向站序：左侧连续轨道，站名下方显示首末班与换乘（本次照抄范围之外，保持原样）。 */
@Composable private fun MetroStopRow(
    stop: MetroStation, index: Int, total: Int, tint: Color, pending: Boolean,
    selected: Boolean = false, onClick: () -> Unit = {}
) {
    val palette = officialPalette()
    val type = officialTypography()
    val transferText = stop.transfer.joinToString(" / ")
    val stationLabel = if (stop.transfer.isEmpty()) stop.name else
        stringResource(R.string.metro_stop_transfer, stop.name, transferText)
    val firstTime = if (stop.first.isNotBlank()) stringResource(R.string.first_time, stop.first) else ""
    val lastTime = if (stop.last.isNotBlank()) stringResource(R.string.last_time, stop.last) else ""
    val pendingText = if (pending) stringResource(R.string.metro_not_open) else ""
    val selectedText = if (selected) stringResource(R.string.metro_realtime_station) else ""
    val label = listOf(stationLabel, firstTime, lastTime, pendingText, selectedText).filter { it.isNotBlank() }.joinToString("，")
    val first = index == 0
    val last = index == total - 1
    // 未开通的站点用淡色，视觉上能和已运营的分开；深色下线路色先提亮再降透明，
    // 否则 4/6 号线在近黑页底上只剩一团黑（位置条等官方版式元素不受影响）。
    val railTint = listLineColor(tint, palette)
    val track = railTint.copy(alpha = if (pending) palette.ListTrackAlphaPending else palette.ListTrackAlpha)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 52.dp)
        .clickable(enabled = stop.code.isNotBlank()) { onClick() }
        .clearAndSetSemantics { contentDescription = label }) {
        Column(Modifier.width(34.dp).fillMaxHeight().clearAndSetSemantics {}) {
            // 上段只画到圆点中心，下段由 weight 撑到底，圆点带底色盖住接缝 → 轨道是连的。
            if (!first) Box(Modifier.align(Alignment.CenterHorizontally).width(2.dp).height(13.dp).background(track))
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = if (first) 8.dp else 0.dp)
                    .size(if (stop.transfer.isEmpty()) 10.dp else 13.dp).clip(Capsule)
                    .background(palette.Page)
                    .border(2.dp, if (pending) railTint.copy(alpha = .45f) else railTint, Capsule)
            )
            if (!last) Box(Modifier.align(Alignment.CenterHorizontally).weight(1f).width(2.dp).background(track))
        }
        Column(Modifier.weight(1f).padding(end = 4.dp, bottom = 12.dp)) {
            Text(
                stop.name,
                style = type.StopName,
                color = if (pending) palette.SubInk else palette.Ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (firstTime.isNotEmpty() || lastTime.isNotEmpty() || stop.transfer.isNotEmpty() || selected) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 逐站时间按接口原文展示，空值不补造；次日时间与窄屏允许自然换行。
                    if (firstTime.isNotEmpty()) Text(firstTime, style = type.MetaLabel)
                    if (lastTime.isNotEmpty()) Text(lastTime, style = type.MetaLabel)
                    stop.transfer.forEach { tag -> StopPill(tag) }
                    if (selected) StopPill(stringResource(R.string.metro_realtime_station), tint)
                }
            }
        }
    }
}
