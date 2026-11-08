package com.yll682.houche.ui.screens.metro

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroRealtime

/**
 * 「列车位置」主卡片（官方白卡：10dp 圆角、无描边无阴影）。
 *
 * 从上到下就是官方那四行，顺序和缩进都按实测：
 * 方向行（左 15.3dp / 右 21dp）→ 站名+刷新行（左 25dp / 右 20dp）→ ETA 蓝块（左右 20dp）
 * → 位置条（左右 10dp，选中站落在视口 78%）。
 */
@Composable
fun MetroRealtimeCard(
    realtime: MetroRealtime?,
    loading: Boolean,
    note: String,
    stationName: String,
    line: MetroLine,
    directionIndex: Int,
    onDirection: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val directions = realtime?.directions.orEmpty()
    val direction = directions.getOrNull(directionIndex.coerceIn(0, directions.lastIndex.coerceAtLeast(0)))
    Column(modifier.fillMaxWidth().clip(OfficialCardShape).background(Official.Card)) {
        Spacer(Modifier.height(14.dp))
        MetroDirectionRow(
            origin = direction?.origin ?: line.from,
            destination = direction?.destination ?: line.to,
            canSwap = directions.size > 1,
            onSwap = { if (directions.isNotEmpty()) onDirection((directionIndex.coerceIn(0, directions.lastIndex) + 1) % directions.size) }
        )
        Spacer(Modifier.height(12.2.dp))
        StationRow(stationName, line, loading, onRefresh)
        Spacer(Modifier.height(13.6.dp))
        when {
            direction != null -> {
                MetroEtaBlock(direction.trains)
                Spacer(Modifier.height(19.dp))
                MetroTrainStrip(realtime!!, direction, line, Modifier.padding(horizontal = 10.dp))
            }
            else -> MetroStatusLine(loading, note)
        }
        Spacer(Modifier.height(10.6.dp))
    }
}

/** 站名行：站名 + 线路号圆徽标 + 右侧「刷新」；三个元素同一条水平中线。 */
@Composable
private fun StationRow(stationName: String, line: MetroLine, loading: Boolean, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 25.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stationName.ifBlank { line.from },
            style = OfficialType.StationName,
            maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        MetroStationBadge(line.id, officialLineColor(line))
        Spacer(Modifier.weight(1f))
        RefreshButton(loading, onRefresh)
    }
}

/** 「刷新」：图标 40×40px + 14px 间距 + 文字，点击时图标转起来作为反馈。 */
@Composable
private fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    val label = stringResource(R.string.refresh)
    val transition = rememberInfiniteTransition(label = "刷新")
    val angle by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart),
        label = "角度"
    )
    Row(
        Modifier.clickable { onRefresh() }.semantics { contentDescription = label }
            .heightIn(min = 24.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroRefreshGlyph(angle = if (loading) angle else 0f)
        Spacer(Modifier.width(4.7.dp))
        Text(label, style = OfficialType.RefreshLabel, maxLines = 1)
    }
}

/** 没有实时数据时占住 ETA/位置条的位置，文案沿用原有几种状态。 */
@Composable
private fun MetroStatusLine(loading: Boolean, note: String) {
    val text = when {
        loading -> stringResource(R.string.metro_realtime_loading)
        note.isNotBlank() -> stringResource(R.string.metro_realtime_failed, note)
        else -> stringResource(R.string.metro_realtime_waiting)
    }
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 25.dp, end = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Official.SubInk
    )
}

/** 供外层判断方向用：没有实时数据时方向为 null，界面退回线路起终点。 */
internal fun MetroRealtime?.directionAt(index: Int): MetroDirection? =
    this?.directions?.getOrNull(index.coerceIn(0, (this.directions.lastIndex).coerceAtLeast(0)))
