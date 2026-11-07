@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
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
import com.yll682.houche.ui.theme.FieldShape
import com.yll682.houche.ui.theme.ArrivedGreen
import com.yll682.houche.ui.theme.HeaderBottom
import com.yll682.houche.ui.theme.HeaderBottomDark
import com.yll682.houche.ui.theme.HeaderTop
import com.yll682.houche.ui.theme.HeaderTopDark
import kotlinx.coroutines.delay

/** 触控区不小于 48dp，并补上无障碍标签。 */
fun Modifier.touch(label: String) = this.heightIn(min = 48.dp).semantics { contentDescription = label }

/** 顶栏渐变：与首页同一套蓝色，深色模式压暗一档，白色图标始终有足够对比。 */
@Composable fun headerGradient(): Brush = Brush.verticalGradient(
    if (isSystemInDarkTheme()) listOf(HeaderTopDark, HeaderBottomDark) else listOf(HeaderTop, HeaderBottom)
)

/** 顶栏容器：蓝色渐变背景 + 状态栏留白，内容由调用方决定放标题行还是搜索框。 */
@Composable fun HeaderSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(headerGradient()).statusBarsPadding(), content = content)
}

/** 顶栏里的白色图标按钮，只在渐变背景上使用。 */
@Composable fun HeaderIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick, Modifier.size(48.dp), enabled = enabled) {
        Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = .4f))
    }
}

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
@Composable fun arrivalColor(arrival: Arrival?): Color = when {
    arrival == null || arrival.waiting -> MaterialTheme.colorScheme.outline
    arrival.remainingStations?.let { it <= 0 } == true || arrival.statusText == stringResource(R.string.arrived) ->
        if (isSystemInDarkTheme()) Color(0xFF35C79A) else ArrivedGreen
    arrival.remainingStations == 1 || arrival.statusText.contains(stringResource(R.string.arriving_soon)) || arrival.timeText.contains(stringResource(R.string.arriving_soon)) -> MaterialTheme.colorScheme.tertiary
    arrival.remainingStations != null -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

@Composable fun ArrivalText(arrival: Arrival?, large: Boolean = false) {
    val (primary, secondary) = arrivalParts(arrival)
    val color = arrivalColor(arrival)
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
    Text(
        distanceLabel(distance),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

/** 小号信息胶囊，用于站台数量、分组计数等辅助信息。 */
@Composable fun MetaPill(text: String, emphasised: Boolean = false, tint: Color? = null) {
    Surface(
        shape = Capsule,
        color = tint?.copy(alpha = .10f) ?: if (emphasised) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tint ?: if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable fun LoadingSkeleton(cards: Int = 3) {
    val transition = rememberInfiniteTransition(label = "加载微光")
    val alpha by transition.animateFloat(.4f, .9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "透明度")
    val loadingLabel = stringResource(R.string.loading)
    val bar = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = loadingLabel }.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 骨架照真实卡片画：白底 + 细描边 + 内部文字条，数量由调用方给。
        // 首页给 5 张把首屏填满，否则定位中会露出大片空白。
        repeat(cards) { index ->
            Column(
                Modifier.fillMaxWidth().height(if (index == 0) 92.dp else 138.dp).clip(CardShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(Modifier.fillMaxWidth(if (index == 0) .38f else .54f).height(16.dp).clip(Capsule).background(bar))
                Box(Modifier.fillMaxWidth(.92f).height(13.dp).clip(Capsule).background(bar))
                Box(Modifier.fillMaxWidth(.88f).height(13.dp).clip(Capsule).background(bar))
                if (index > 0) Box(Modifier.fillMaxWidth(.7f).height(13.dp).clip(Capsule).background(bar))
            }
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
                stringResource(R.string.error_generic),
                Modifier.weight(1f).semantics { contentDescription = message.orEmpty() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(retry, Modifier.touch(stringResource(R.string.retry))) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable fun EmptyState(title: String, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(Capsule).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.DirectionsBus, title, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        action?.let {
            Spacer(Modifier.height(20.dp))
            Button(onClick, Modifier.touch(it), shape = Capsule, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)) { Text(it) }
        }
    }
}

/** 计数紧跟标题，保持辅助层级。 */
@Composable fun SectionHeader(title: String, count: Int? = null, suffix: String? = null) {
    // 计数紧跟标题，避免右侧孤立的数字看起来像另一个条目。
    val base = if (count == null) title else stringResource(R.string.group_count, title, count)
    val text = if (suffix.isNullOrBlank()) base else "$base  $suffix"
    Text(
        text,
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable fun PullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize(), content = content)
}

@Composable fun <T> StateFeedback(state: UiState<T>, cards: Int = 3, retry: () -> Unit) {
    when (state) {
        is UiState.Loading -> LoadingSkeleton(cards)
        is UiState.Error -> ErrorCard(state.message, retry)
        else -> Unit
    }
}

/** 二级页面外壳：蓝色渐变顶栏 + 返回按钮 + 白色标题，与首页同一种家族感。 */
@Composable fun Page(title: String, back: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HeaderSurface {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(start = 2.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), back)
                    Text(
                        title,
                        Modifier.weight(1f).padding(start = 2.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    actions()
                }
            }
        },
        content = content
    )
}

@Composable fun Segments(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(36.dp).clip(Capsule).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Capsule))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                Box(Modifier.weight(1f).height(48.dp).clickable(role = Role.Tab) { select(index) }
                    .semantics { contentDescription = label; this.selected = active }, contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 3.dp).height(30.dp).clip(Capsule)
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 线路行：徽章 + 开往方向 + 起点终点 + 右侧到站，行间由调用方补发丝线。 */
@Composable fun LineRow(line: LineSummary, arrival: Arrival? = null, extraArrivals: List<Arrival> = emptyList(), onClick: () -> Unit) {
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
            Column(Modifier.widthIn(min = 76.dp, max = 96.dp), horizontalAlignment = Alignment.End) {
                ArrivalText(arrival)
                extraArrivals.take(1).forEach { ArrivalText(it) }
            }
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

fun LazyListScope.feedback(state: UiState<*>, cards: Int = 3, retry: () -> Unit) {
    if (state is UiState.Loading || state is UiState.Error) item { StateFeedback(state, cards, retry) }
}

@Composable fun HeaderSearchField(
    value: String, change: (String) -> Unit, hint: String, clear: () -> Unit,
    modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Surface(shape = FieldShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().height(44.dp)) {}
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(value, change, Modifier.weight(1f).height(48.dp).wrapContentHeight()
                .semantics { contentDescription = hint }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                decorationBox = { field ->
                    Box { if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
                })
            if (value.isNotEmpty()) ActionIcon(Icons.Default.Close, stringResource(R.string.clear), clear)
            else Spacer(Modifier.width(12.dp))
        }
    }
}
