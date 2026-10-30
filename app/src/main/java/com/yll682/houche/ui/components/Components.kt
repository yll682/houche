@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yll682.houche.R
import com.yll682.houche.core.Arrival
import com.yll682.houche.core.LineSummary
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.BadgeShape
import com.yll682.houche.ui.theme.Capsule
import com.yll682.houche.ui.theme.CardShape
import kotlinx.coroutines.delay

/** 触控区不小于 48dp，并补上无障碍标签。 */
fun Modifier.touch(label: String) = this.heightIn(min = 48.dp).semantics { contentDescription = label }

@Composable fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    IconButton(onClick, Modifier.size(48.dp), enabled = enabled) { Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = .38f)) }
}

/** 1dp 发丝分隔线。 */
@Composable fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** 本应用唯一的卡片容器：纯 surface + 细描边，不用阴影和灰色填充。 */
@Composable fun CardSurface(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) Surface(onClick, modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surface, border = border) { Column(content = content) }
    else Surface(modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surface, border = border) { Column(content = content) }
}

/** 线路号徽章。数字等宽，长线路名会整体缩小到两行以内。 */
@Composable fun LineBadge(name: String, large: Boolean = false) {
    val style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    Box(
        Modifier
            .defaultMinSize(minWidth = if (large) 88.dp else 54.dp, minHeight = if (large) 76.dp else 34.dp)
            .clip(if (large) BadgeShape else MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .38f), if (large) BadgeShape else MaterialTheme.shapes.extraSmall)
            .padding(horizontal = if (large) 20.dp else 10.dp, vertical = if (large) 14.dp else 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(name, style = style, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun meaningful(text: String?): String? {
    val value = text?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.replace(" ", "").all { it == '-' || it == '—' || it == '一' }) return null
    return value
}

private fun arrivalLike(text: String) = text.contains("到站") || text.contains("进站") || text.contains("即将到达")

/**
 * 到站信息只保留一条主信息，副信息仅在确实补充新内容时出现。
 * 上游常同时给「已到站」和「即将到站」，这类重复必须合并，不能两行都说同一件事。
 */
@Composable fun arrivalParts(arrival: Arrival?): Pair<String, String?> {
    if (arrival == null) return stringResource(R.string.arrival_unknown) to null
    val status = meaningful(arrival.statusText)
    val time = meaningful(arrival.timeText)
    val stations = arrival.remainingStations
    val departure = meaningful(arrival.nextDeparture)

    if (arrival.waiting || (departure != null && (stations == null || stations <= 0))) {
        return (departure?.let { stringResource(R.string.planned, it) } ?: stringResource(R.string.waiting)) to null
    }
    if (stations != null && stations <= 0) return stringResource(R.string.arrived) to null
    if (stations != null) {
        val secondary = when {
            time != null && !arrivalLike(time) -> time
            meaningful(arrival.distanceText) != null -> meaningful(arrival.distanceText)
            else -> null
        }
        return stringResource(R.string.remaining, stations) to secondary
    }
    val primary = status ?: time ?: stringResource(R.string.arrival_unknown)
    val secondary = if (status != null && time != null && time != status && !(arrivalLike(status) && arrivalLike(time))) time else null
    return primary to secondary
}

@Composable fun arrivalLabel(arrival: Arrival?): String = arrivalParts(arrival).first

/** 右侧到站列：宽度固定，单行省略，避免把行高撑起来。 */
@Composable fun ArrivalText(arrival: Arrival?, large: Boolean = false) {
    val (primary, secondary) = arrivalParts(arrival)
    val emphasised = primary.contains("已到站") || primary.contains("即将") || primary.startsWith("1 站") || primary.startsWith("0 站")
    val color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.End) {
        Text(
            primary,
            style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        secondary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable fun distanceLabel(distance: Double?): String = when {
    distance == null -> stringResource(R.string.distance_unknown)
    distance >= 1000 -> stringResource(R.string.distance_km, distance / 1000)
    else -> stringResource(R.string.distance_m, distance.toInt())
}

@Composable fun DistanceChip(distance: Double?) {
    Surface(shape = Capsule, color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(
            distanceLabel(distance),
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 小号信息胶囊，用于站台数量、分组计数等辅助信息。 */
@Composable fun MetaPill(text: String, emphasised: Boolean = false) {
    Surface(
        shape = Capsule,
        color = if (emphasised) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (emphasised) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f)) else null
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasised) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable fun LoadingSkeleton() {
    val transition = rememberInfiniteTransition(label = "加载微光")
    val alpha by transition.animateFloat(.4f, .85f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "透明度")
    val loadingLabel = stringResource(R.string.loading)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = loadingLabel }.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) { index ->
            Box(
                Modifier.fillMaxWidth(if (index == 1) .68f else 1f)
                    .height(if (index == 0) 88.dp else 56.dp)
                    .clip(CardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha))
            )
        }
    }
}

/** 错误就地显示，不清空已有内容。 */
@Composable fun ErrorCard(message: String?, retry: () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .35f))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(
                message ?: stringResource(R.string.error_generic),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(retry, Modifier.touch(stringResource(R.string.retry))) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable fun EmptyState(title: String, subtitle: String? = null, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(Capsule).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.DirectionsBus, title, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        subtitle?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        action?.let {
            Spacer(Modifier.height(20.dp))
            Button(onClick, Modifier.touch(it), shape = Capsule, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)) { Text(it) }
        }
    }
}

/** 分组标题：小号字 + 计数 + 下方发丝线。 */
@Composable fun SectionHeader(title: String, count: Int? = null) {
    // 计数紧跟标题，避免右侧孤立的数字看起来像另一个条目。
    val text = if (count == null) title else "$title · $count"
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Hairline()
}

@Composable fun PullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize(), content = content)
}

@Composable fun <T> StateFeedback(state: UiState<T>, retry: () -> Unit) {
    when (state) {
        is UiState.Loading -> LoadingSkeleton()
        is UiState.Error -> ErrorCard(state.message, retry)
        else -> Unit
    }
}

/** 二级页面外壳：返回按钮 + 标题，底部一条发丝线。 */
@Composable fun Page(title: String, back: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), back, tint = MaterialTheme.colorScheme.onSurface)
                    Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    actions()
                }
                Hairline()
            }
        },
        content = content
    )
}

/** 单层分段控件：描边容器 + 选中项实心填充，不做胶囊套胶囊。 */
@Composable fun Segments(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(Capsule)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Capsule)
            .padding(3.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(Capsule)
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { select(index) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 线路行：徽章 + 开往方向 + 起点终点 + 右侧到站，行间由调用方补发丝线。 */
@Composable fun LineRow(line: LineSummary, arrival: Arrival? = null, onClick: () -> Unit) {
    val label = stringResource(R.string.to, line.to)
    Row(
        Modifier.fillMaxWidth().touch(line.name + label).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineBadge(line.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.from.isNotBlank() && line.from != line.to) {
                Text(
                    stringResource(R.string.endpoints, line.from, line.to),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (arrival != null) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.widthIn(min = 76.dp, max = 96.dp)) { ArrivalText(arrival) }
        }
    }
}

/** 前台每 20 秒刷新一次，切到后台自动停下。 */
@Composable fun PollEvery20Seconds(enabled: Boolean = true, refresh: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(refresh)
    LaunchedEffect(lifecycle, enabled) {
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { current(); delay(20_000) }
        }
    }
}

fun LazyListScope.feedback(state: UiState<*>, retry: () -> Unit) { item { StateFeedback(state, retry) } }