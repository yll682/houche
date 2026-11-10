@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import com.yll682.houche.ui.components.TelegramSheet
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
import com.yll682.houche.core.MetroTransferTarget
import com.yll682.houche.core.metroStationOperatingTimes
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
 * 顶栏与底栏由首页层承载，这里保留线路 tab 条（色块徽标 + 方形下划线）→
 * 白卡片（方向行 / 站名+刷新 / ETA 蓝块 / 位置条）→ 卡片外居中免责说明 → 站序列表。
 *
 * 站序列表官方这页没有，但候车用它在换参考站（`selectStation` 的唯一入口），
 * 删掉就没有切站能力了，所以保留在免责说明下面，样式沿用原有控件不动。
 */
@Composable
fun MetroContent(
    state: UiState<MetroData>, select: (String) -> Unit,
    selectStation: (String) -> Unit = {}, selectDirection: (Int) -> Unit = {},
    refresh: () -> Unit = {},
    selectTransfer: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val data = state.data ?: MetroData()
    // 站序跟随所选方向（始发在前），与实时卡片的「开往 XX」讲同一个方向。
    val line = data.orderedLine
    val heading = data.line
    val palette = officialPalette()
    val type = officialTypography()
    var sheetStation by rememberSaveable { mutableStateOf<String?>(null) }
    // 换线后关掉面板。
    LaunchedEffect(data.selected) { sheetStation = null }
    val openSheet: (String) -> Unit = { code ->
        if (code.isNotBlank() && line?.stations?.any { it.code == code } == true) sheetStation = code
    }
    Column(modifier.fillMaxSize()) {
        MetroLineTabs(data.lines, data.selected, select)
        if (line == null || heading == null) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                if (state is UiState.Loading) LoadingSkeleton(cards = 2)
                else EmptyState(stringResource(R.string.metro_empty))
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("metro-stations"),
            contentPadding = PaddingValues(bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            item {
                MetroRealtimeCard(
                    direction = data.direction,
                    directionCount = data.realtime?.directions?.size ?: 0,
                    stationCode = data.realtimeStation.ifBlank { data.realtime?.stationCode.orEmpty() }
                        .ifBlank { line.stations.firstOrNull()?.code.orEmpty() },
                    etaState = data.etaState,
                    loading = data.realtimeLoading,
                    note = data.realtimeNote,
                    stationName = data.realtimeStationName.ifBlank { line.stations.firstOrNull()?.name.orEmpty() },
                    line = heading,
                    directionIndex = data.directionIndex,
                    onDirection = selectDirection,
                    onRefresh = refresh,
                    onSelectStation = openSheet,
                    onTransfer = selectTransfer,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
                )
            }
            item {
                // 免责说明在卡片外面，居中、珊瑚红——官方这页就是这样（规格 2.7）。
                Text(
                    stringResource(R.string.metro_realtime_tip),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    style = type.Disclaimer,
                    textAlign = TextAlign.Start
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
                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
                    style = type.SectionLabel
                )
            }
            itemsIndexed(line.stations, key = { index, stop -> "$index-${stop.name}" }) { index, stop ->
                val times = metroStationOperatingTimes(heading, stop)
                MetroStopRow(
                    stop.copy(first = times.first, last = times.last),
                    index, line.stations.size, officialLineColor(heading), !stop.opened,
                    selected = stop.code.isNotBlank() && stop.code == data.realtimeStation,
                    // 换乘徽标把完整目标（目标线 id + 目标线自己的站码）交给数据层，一次提交、一次刷新。
                    onTransfer = { target: MetroTransferTarget -> selectTransfer(target.lineId, target.stationCode) }
                ) { openSheet(stop.code) }
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
    sheetStation?.let { code ->
        val stop = line?.stations?.firstOrNull { it.code == code }
        if (stop != null && heading != null) {
            MetroStationSheet(
                stop = stop,
                destination = data.realtime?.directions?.getOrNull(data.directionIndex)?.destination?.takeIf { it.isNotBlank() }
                    ?: heading.to,
                times = metroStationOperatingTimes(heading, stop),
                isBoarding = code == data.realtimeStation,
                onConfirm = { selectStation(code); sheetStation = null },
                dismiss = { sheetStation = null }
            )
        }
    }
}

/** 点站点弹出的底部面板：站名、开往、首末班，确认后才把它设为乘车站。 */
@Composable
private fun MetroStationSheet(
    stop: MetroStation, destination: String, times: com.yll682.houche.core.MetroOperatingTimes,
    isBoarding: Boolean, onConfirm: () -> Unit, dismiss: () -> Unit
) {
    TelegramSheet(dismiss = dismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stop.name, style = MaterialTheme.typography.titleLarge)
            if (destination.isNotBlank()) {
                Text(stringResource(R.string.to, destination), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (times.first.isNotBlank() && times.last.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.service_times, times.first, times.last), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = onConfirm, enabled = !isBoarding, shape = Capsule,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("station-confirm")
            ) { Text(stringResource(if (isBoarding) R.string.metro_station_selected else R.string.metro_station_select)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 站序行里的小胶囊（旧资料里的换乘文案 / 参考站）：填充 alpha 与线路色一起跟着明暗模式走。
 *
 * 纯装饰：这段话已经在整行的 `contentDescription` 里，或由旁边的可点换乘徽标另讲一遍，
 * 所以自身不参与无障碍树，免得同一行被读两遍。
 */
@Composable private fun StopPill(text: String, tint: Color? = null) {
    val palette = officialPalette()
    val type = officialTypography()
    val color = tint?.let { listLineColor(it, palette) } ?: palette.SubInk
    val fill = if (tint == null) palette.PillFillAlpha else palette.PillTintAlpha
    Box(
        Modifier.background(color.copy(alpha = fill), Capsule)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = type.MetaLabel.copy(color = color), maxLines = 1)
    }
}

/**
 * 站序行下方那一排小元素（首末班 / 换乘徽标 / 「参考站」）统一撑到 32dp 高并垂直居中：
 * 换乘徽标的命中区就是 32dp，时间文字和旧胶囊跟着对齐，不会被顶到行首。
 */
@Composable private fun StopFlowItem(content: @Composable () -> Unit) {
    Box(Modifier.heightIn(min = TransferBadgeTapHeight), contentAlignment = Alignment.Center) { content() }
}

/**
 * 竖向站序：左侧连续轨道，站名下方显示首末班、换乘徽标与「参考站」。
 *
 * 有可导航换乘目标（`transferTargets`）的站画彩色徽标，点徽标直接跳目标线目标站；
 * 只有旧资料那串文案、推不出目标时才退回原来的中性胶囊。
 */
@Composable internal fun MetroStopRow(
    stop: MetroStation, index: Int, total: Int, tint: Color, pending: Boolean,
    selected: Boolean = false, onTransfer: (MetroTransferTarget) -> Unit = {}, onClick: () -> Unit = {}
) {
    val palette = officialPalette()
    val type = officialTypography()
    val transferText = stop.transfer.joinToString(" / ")
    val targets = stop.transferTargets
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
    val cardShape = RoundedCornerShape(
        topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
        bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(cardShape)
        .background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp)
        .padding(top = if (first) 12.dp else 0.dp, bottom = if (last) 12.dp else 0.dp)
        .height(IntrinsicSize.Min).heightIn(min = 64.dp)
        .clickable(enabled = stop.code.isNotBlank()) { onClick() }) {
        Column(Modifier.width(34.dp).fillMaxHeight().clearAndSetSemantics {}) {
            // 上段只画到圆点中心，下段由 weight 撑到底，圆点带底色盖住接缝 → 轨道是连的。
            if (!first) Box(Modifier.align(Alignment.CenterHorizontally).width(2.dp).height(13.dp).background(track))
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = if (first) 8.dp else 0.dp)
                    .size(if (stop.transfer.isEmpty()) 10.dp else 13.dp).clip(Capsule)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, if (pending) railTint.copy(alpha = .45f) else railTint, Capsule)
            )
            if (!last) Box(Modifier.align(Alignment.CenterHorizontally).weight(1f).width(2.dp).background(track))
        }
        Column(Modifier.weight(1f).padding(end = 4.dp, bottom = 12.dp)) {
            // 整行的一句话说明挂在站名上（原先挂在外层 Row）：外层还留着「选站」的点击语义，
            // 而换乘徽标要保留自己的点击节点，不能再被一个 clearAndSetSemantics 整片抹掉。
            Text(
                stop.name,
                Modifier.clearAndSetSemantics { contentDescription = label },
                style = type.StopName,
                color = if (pending) palette.SubInk else palette.Ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (firstTime.isNotEmpty() || lastTime.isNotEmpty() || stop.transfer.isNotEmpty() || targets.isNotEmpty() || selected) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 与运营状态判断共用首末班来源；次日时间与窄屏允许自然换行。
                    if (firstTime.isNotEmpty()) StopFlowItem {
                        Text(firstTime, Modifier.clearAndSetSemantics {}, style = type.MetaLabel)
                    }
                    if (lastTime.isNotEmpty()) StopFlowItem {
                        Text(lastTime, Modifier.clearAndSetSemantics {}, style = type.MetaLabel)
                    }
                    if (targets.isNotEmpty()) {
                        // 每个目标一枚小胶囊，底色＝目标线路官方色，点了直接跳那条线的那个站。
                        TransferBadges(targets, onSelect = onTransfer)
                    } else {
                        // 旧资料没有坐标/共站码，推不出可导航目标：保留原来那枚中性文案胶囊。
                        stop.transfer.forEach { tag -> StopFlowItem { StopPill(tag) } }
                    }
                    if (selected) StopFlowItem { StopPill(stringResource(R.string.metro_realtime_station), tint) }
                }
            }
        }
    }
}
