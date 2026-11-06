@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.yll682.houche.ui.screens.search

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.screens.metro.metroColor
import com.yll682.houche.ui.theme.Capsule

@Composable fun SearchContent(
    state: UiState<SearchData>, back: () -> Unit, line: (LineSummary) -> Unit, station: (Station) -> Unit,
    query: (String) -> Unit, search: (String) -> Unit, submit: () -> Unit, clear: () -> Unit,
    remove: (String) -> Unit, modifier: Modifier = Modifier, inputModifier: Modifier = Modifier,
    metro: (String) -> Unit = {}
) {
    val data = state.data ?: SearchData()
    val typed = data.query.isNotBlank()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HeaderSurface {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), back)
                HeaderSearchField(data.query, query, stringResource(R.string.search_hint), { query("") },
                    inputModifier.weight(1f), KeyboardOptions(imeAction = ImeAction.Search), KeyboardActions(onSearch = { submit() }))
                TextButton(back, Modifier.touch(stringResource(R.string.cancel)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            feedback(state) { submit() }
            if (!typed) {
                if (data.history.isNotEmpty()) {
                    item {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            data.history.forEach { keyword ->
                                Surface(shape = Capsule, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    Row(Modifier.widthIn(max = 320.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(keyword, Modifier.weight(1f, fill = false).widthIn(min = 48.dp).touch(keyword).clickable { search(keyword) }.padding(start = 12.dp).wrapContentHeight(),
                                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        ActionIcon(Icons.Default.Close, stringResource(R.string.delete_search), { remove(keyword) })
                                    }
                                }
                            }
                            // 「清空」与关键词 chip 同一形态，只是文字更轻，避免两种药丸挤在一行。
                            Surface(onClick = clear, modifier = Modifier.touch(stringResource(R.string.clear)), shape = Capsule,
                                color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Text(stringResource(R.string.clear), Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else item { EmptyState(stringResource(R.string.search_hint)) }
            } else {
                val lines = data.result.lines
                val stations = data.result.stations
                if (lines.isEmpty() && stations.isEmpty() && data.metro.isEmpty() && state !is UiState.Loading) {
                    item { EmptyState(stringResource(R.string.search_empty)) }
                }
                if (data.metro.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.metro), data.metro.size) }
                    item {
                        CardSurface {
                            data.metro.forEachIndexed { index, item ->
                                if (index > 0) Hairline()
                                MetroSearchRow(item) { metro(item.id) }
                            }
                        }
                    }
                }
                if (lines.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.lines), lines.size) }
                    item {
                        CardSurface {
                            lines.forEachIndexed { index, summary ->
                                if (index > 0) Hairline()
                                LineRow(summary) { line(summary) }
                            }
                        }
                    }
                }
                if (stations.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.stations), stations.size) }
                    item {
                        CardSurface {
                            stations.forEachIndexed { index, stop ->
                                if (index > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().touch(stop.name).clickable { station(stop) }.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(34.dp).clip(Capsule).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(stop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (stop.lines.isNotEmpty()) {
                                            Text(stringResource(R.string.station_count, stop.lines.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 地铁搜索结果：线路色点代替线路牌，副行给始终点与站数，点击直接进地铁页。 */
@Composable private fun MetroSearchRow(line: MetroLine, onClick: () -> Unit) {
    val tint = metroColor(line.color)
    val ends = stringResource(R.string.metro_ends, line.from, line.to)
    Row(
        Modifier.fillMaxWidth().touch(stringResource(R.string.metro_stop_transfer, line.name, ends))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(Capsule).background(tint.copy(alpha = .14f)),
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(10.dp).clip(Capsule).background(tint)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.metro_search_meta, ends, line.liveStations.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
