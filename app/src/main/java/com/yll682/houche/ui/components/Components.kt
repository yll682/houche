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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.theme.*
import kotlinx.coroutines.delay

fun Modifier.touch(label: String) = this.heightIn(min = 48.dp).semantics { contentDescription = label }

@Composable fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(icon, label) }
}
@Composable fun LineBadge(name: String, large: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
        Text(name, Modifier.padding(horizontal = 12.dp, vertical = if (large) 16.dp else 8.dp), style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
@Composable fun arrivalLabel(arrival: Arrival?): String = when {
    arrival == null -> stringResource(R.string.arrival_unknown)
    arrival.statusText.isNotBlank() && arrival.statusText.replace(" ", "") !in setOf("-", "--") -> arrival.statusText
    !arrival.nextDeparture.isNullOrBlank() -> stringResource(R.string.planned, arrival.nextDeparture)
    arrival.waiting -> stringResource(R.string.waiting)
    arrival.remainingStations == 0 -> stringResource(R.string.arrived)
    arrival.remainingStations != null -> stringResource(R.string.remaining, arrival.remainingStations)
    arrival.timeText.isNotBlank() -> arrival.timeText
    else -> stringResource(R.string.arrival_unknown)
}
@Composable fun ArrivalText(arrival: Arrival?, large: Boolean = false) {
    val color = when { arrival == null || arrival.waiting || (arrival.remainingStations == null && arrival.timeText.isBlank() && arrival.statusText.replace(" ", "") in setOf("", "-", "--")) || arrival.statusText.contains("等待") || arrival.statusText.contains("未发车") || arrival.statusText.contains("非运营") -> MaterialTheme.colorScheme.onSurfaceVariant; arrival.remainingStations == 0 || arrival.statusText.contains("已到") || arrival.statusText.contains("即将到") -> ArrivedGreen; else -> MovingBlue }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(Capsule).background(color))
        Column {
            val summary = arrivalLabel(arrival)
            val headline = if (large && !arrival?.timeText.isNullOrBlank()) arrival!!.timeText else summary
            val secondary = if (headline != summary) summary else arrival?.timeText?.takeIf { it.isNotBlank() && it != headline }
            Text(headline, style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.bodyMedium)
            secondary?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!arrival?.nextDeparture.isNullOrBlank()) {
                val planned = stringResource(R.string.planned, arrival?.nextDeparture.orEmpty())
                if (planned != headline && planned != secondary) Text(planned, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable fun distanceLabel(distance: Double?): String = when { distance == null -> stringResource(R.string.distance_unknown); distance >= 1000 -> stringResource(R.string.distance_km, distance / 1000); else -> stringResource(R.string.distance_m, distance.toInt()) }
@Composable fun DistanceChip(distance: Double?) {
    Surface(shape = Capsule, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text(distanceLabel(distance), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
}
@Composable fun LoadingSkeleton() {
    val transition = rememberInfiniteTransition(label = "加载微光")
    val alpha by transition.animateFloat(.35f, .75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "透明度")
    val loadingLabel = stringResource(R.string.loading)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = loadingLabel }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { index -> Box(Modifier.fillMaxWidth(if (index == 1) .65f else 1f).height(if (index == 0) 72.dp else 24.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))) }
    }
}
@Composable fun ErrorCard(message: String?, retry: () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(message ?: stringResource(R.string.error_generic), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(retry, Modifier.touch(stringResource(R.string.retry))) { Text(stringResource(R.string.retry)) }
        }
    }
}
@Composable fun EmptyState(title: String, subtitle: String? = null, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.DirectionsBus, title, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        action?.let { Button(onClick, Modifier.touch(it), shape = Capsule) { Text(it) } }
    }
}
@Composable fun SectionHeader(title: String, count: Int? = null) {
    Text(if (count == null) title else stringResource(R.string.section_count, title, count), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
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
@Composable fun Page(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), back) }, actions = actions) }, containerColor = MaterialTheme.colorScheme.background, content = content)
}
@Composable fun Segments(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label -> SegmentedButton(selected == index, { select(index) }, SegmentedButtonDefaults.itemShape(index, labels.size), Modifier.touch(label)) { Text(label) } }
    }
}
@Composable fun LineRow(line: LineSummary, arrival: Arrival? = null, onClick: () -> Unit) {
    val label = stringResource(R.string.to, line.to)
    Row(Modifier.fillMaxWidth().touch(line.name + label).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LineBadge(line.name)
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.titleMedium); if (line.from.isNotBlank()) Text(stringResource(R.string.endpoints, line.from, line.to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (arrival != null) Box(Modifier.widthIn(max = 140.dp)) { ArrivalText(arrival) }
    }
}
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
