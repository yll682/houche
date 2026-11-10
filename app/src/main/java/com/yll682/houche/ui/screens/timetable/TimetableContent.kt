@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.timetable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import com.yll682.houche.ui.components.TelegramQuint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.Route
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.CardShape
import com.yll682.houche.ui.theme.Capsule

@Composable fun TimetableContent(
    state: UiState<TimetableData>, route: Route, collapsed: Set<Int>, toggle: (Int) -> Unit,
    back: () -> Unit, retry: () -> Unit, modifier: Modifier = Modifier
) {
    val data = state.data ?: TimetableData()
    Page(stringResource(R.string.timetable), back, modifier) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            feedback(state, hasContent = data.groups.isNotEmpty()) { retry() }
            item {
                CardSurface {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(route.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.to, route.to), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text(
                                stringResource(R.string.service_times, route.first.ifBlank { stringResource(R.string.no_time) }, route.last.ifBlank { stringResource(R.string.no_time) }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    data.table?.nextDeparture?.takeIf { it.isNotBlank() }?.let {
                        Hairline()
                        Text(stringResource(R.string.next_departure, it), Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (data.groups.isEmpty() && state !is UiState.Loading) {
                item { EmptyState(stringResource(R.string.no_timetable)) }
            }
            itemsIndexed(data.groups) { index, group ->
                val open = index !in collapsed
                GroupCard(
                    group = group,
                    open = open,
                    nextDeparture = data.table?.nextDeparture,
                    onToggle = { toggle(index) }
                )
            }
            data.table?.tips?.takeIf { it.isNotBlank() }?.let { tips ->
                item {
                    Text(
                        tips,
                        Modifier.padding(top = 4.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable private fun GroupCard(group: TimeGroup, open: Boolean, nextDeparture: String?, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "折叠箭头")
    CardSurface {
        Row(
            Modifier.fillMaxWidth().touch(group.name).clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (group.current) {
                Box(Modifier.width(3.dp).height(16.dp).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    group.name.ifBlank { stringResource(R.string.all_day) },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (group.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (group.current) {
                    Text(stringResource(R.string.current_period), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(R.string.departures, group.times.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ExpandMore, stringResource(R.string.expand_group), Modifier.size(20.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(tween(200, easing = TelegramQuint)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(200, easing = TelegramQuint)) + fadeOut(tween(120))
        ) {
            Column {
                Hairline()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                    val columns = ((maxWidth.value - 24f) / (fontScale * 72f)).toInt().coerceIn(2, 5)
                    Column(Modifier.padding(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.times.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { departure ->
                                    val highlight = (!nextDeparture.isNullOrBlank() && departure.time == nextDeparture) || (group.current && departure.status == 2)
                                    Surface(
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .08f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                departure.time,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = if (highlight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}