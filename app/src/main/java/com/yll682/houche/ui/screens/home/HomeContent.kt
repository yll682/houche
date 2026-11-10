@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.home

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.Capsule
import com.yll682.houche.ui.theme.HeaderBottom
import com.yll682.houche.ui.theme.HeaderTop

/** Telegram-style shared chrome, compact transport rows, and persistent primary destinations. */
@Composable fun HomeContent(
    state: UiState<HomeData>, city: City, granted: Boolean, segment: Int, selectSegment: (Int) -> Unit,
    locate: () -> Unit, search: () -> Unit, cities: () -> Unit,
    line: (City, LineSummary, Station?) -> Unit, station: (City, Station) -> Unit,
    modifier: Modifier = Modifier,
    metro: ((String?) -> Unit)? = null,
    settingsContent: @Composable () -> Unit = {}
) {
    val data = state.data ?: HomeData()
    val loading = state is UiState.Loading
    val nearbyScroll = rememberLazyListState()
    val favoriteScroll = rememberLazyListState()
    val tabStates = rememberSaveableStateHolder()
    val backdropLayer = rememberGraphicsLayer()
    val backdrop = remember { NavigationBackdrop(backdropLayer) }
    val scope = rememberCoroutineScope()
    var settingsTop by remember { mutableIntStateOf(0) }
    val currentSegment = rememberUpdatedState(segment)
    val changeSegment = rememberUpdatedState(selectSegment)
    fun destination(index: Int) {
        if (index == currentSegment.value) scope.launch {
            when(index) { 0 -> nearbyScroll.animateScrollToItem(0); 1 -> favoriteScroll.animateScrollToItem(0); else -> settingsTop++ }
        } else changeSegment.value(index)
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HomeHeader(city, cities, locate, search, searchable = segment != 2, refreshable = segment != 2,
            metro = if (data.hasMetro && segment == 0) metro else null, segment = segment) }
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
       Box(Modifier.fillMaxSize().navigationBackdrop(backdrop)) {
        if (segment == 2) {
            Box(Modifier.fillMaxSize().padding(bottom = 80.dp).navigationBarsPadding()) {
                CompositionLocalProvider(LocalSettingsReselect provides settingsTop) { tabStates.SaveableStateProvider("settings") { settingsContent() } }
            }
        } else {
        PullToRefresh(loading, { locate() }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = if (segment == 0) nearbyScroll else favoriteScroll,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (segment == 0) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.NearMe, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            // 定位结论只给一句：正在定位 / 已定位 / 已按定位城市查询 / 未识别回退。
                            val status = when {
                                loading -> R.string.locating
                                data.position == null -> R.string.nearby
                                data.position.fromLastKnown -> R.string.last_location
                                data.cityResolved -> R.string.location_city
                                else -> R.string.location_fallback
                            }
                            Text(
                                stringResource(status),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (data.position == null) {
                                Text(
                                    stringResource(if (granted && data.permissionDenied) R.string.open_settings else R.string.enable_location),
                                    Modifier.touch(stringResource(R.string.enable_location)).clickable { locate() }.padding(horizontal = 4.dp).wrapContentHeight(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (!granted || data.permissionDenied || (data.position == null && !loading)) {
                        item { LocationCard(data.permissionDenied, locate) }
                    }
                    feedback(state, cards = 5, hasContent = data.stations.isNotEmpty()) { locate() }
                    if (data.stations.isEmpty() && state is UiState.Empty) {
                        item { EmptyState(stringResource(R.string.no_stations), action = stringResource(R.string.search_action), onClick = search) }
                    }
                    itemsIndexed(data.stations, key = { _, stop -> "${stop.number}-${stop.name}-${stop.lat}-${stop.lng}" }) { index, stop ->
                        StationCard(
                            station = stop,
                            arrivals = data.arrivals[index].orEmpty(),
                            error = data.liveErrors[index],
                            collapsed = index >= 3,
                            onOpen = { station(city, stop) },
                            onLine = { summary -> line(city, summary, stop) }
                        )
                    }
                } else {
                    feedback(state, cards = 4, hasContent = data.favoriteLines.isNotEmpty() || data.favoriteStations.isNotEmpty()) { locate() }
                    if (data.favoriteLines.isEmpty() && data.favoriteStations.isEmpty()) {
                        item { EmptyState(stringResource(R.string.no_favorites), action = stringResource(R.string.search_action), onClick = search) }
                    }
                    if (data.favoriteLines.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.favorite_lines), data.favoriteLines.size) }
                        item {
                            CardSurface {
                                data.favoriteLines.forEachIndexed { index, saved ->
                                    if (index > 0) Hairline()
                                    Column {
                                        Text(
                                            saved.city.name,
                                            Modifier.padding(start = 16.dp, top = 10.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        LineRow(saved.line) { line(saved.city, saved.line, null) }
                                    }
                                }
                            }
                        }
                    }
                    if (data.favoriteStations.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.favorite_stations), data.favoriteStations.size) }
                        item {
                            CardSurface {
                                data.favoriteStations.forEachIndexed { index, saved ->
                                    if (index > 0) Hairline()
                                    Row(
                                        Modifier.fillMaxWidth().touch(saved.station.name).clickable { station(saved.city, saved.station) }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(saved.station.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(saved.city.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(stringResource(R.string.station_count, saved.station.lines.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
       }
       Box(Modifier.align(Alignment.BottomCenter)) { HomeBottomBar(segment, ::destination, backdrop) }
      }
    }
}

@Composable private fun HomeHeader(city: City, cities: () -> Unit, locate: () -> Unit, search: () -> Unit, searchable: Boolean = true, refreshable: Boolean = true, metro: ((String?) -> Unit)? = null, segment: Int = 0) {
    HeaderSurface {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(when (segment) { 1 -> R.string.favorites; 2 -> R.string.settings; else -> R.string.app_name }),
                Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(onClick = cities, modifier = Modifier.widthIn(max = 144.dp), shape = Capsule, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(city.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (metro != null) HeaderIcon(Icons.Outlined.Subway, stringResource(R.string.metro), { metro(null) })
            if (refreshable) HeaderIcon(Icons.Outlined.MyLocation, stringResource(R.string.relocate), locate)
        }
        if (searchable) {
            Surface(onClick = search, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).height(40.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.search_hint), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun HomeBottomBar(segment: Int, selectSegment: (Int) -> Unit, backdrop: NavigationBackdrop) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        TelegramNavigation(segment, selectSegment, backdrop = backdrop)
    }
}

@Composable private fun LocationCard(denied: Boolean, locate: () -> Unit) {
    CardSurface {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(Capsule).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            val label = stringResource(if (denied) R.string.open_settings else R.string.allow_location)
            Button(locate, Modifier.touch(label), shape = Capsule, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) { Text(label) }
        }
    }
}

/** 站点卡片：站名 + 距离一行，线路逐行，右侧到站信息按官方配色分级。 */
@Composable private fun StationCard(
    station: Station, arrivals: List<Arrival>, error: String?, collapsed: Boolean,
    onOpen: () -> Unit, onLine: (LineSummary) -> Unit
) {
    val platforms = station.sameNum ?: station.platformNumbers.size
    CardSurface {
        Row(
            Modifier.fillMaxWidth().touch(station.name).clickable(onClick = onOpen).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 站点是站牌，公交图标留给车辆本身，别在站名旁画一辆车。
            Icon(Icons.Outlined.Signpost, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (platforms > 1) {
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.platform_count, platforms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            DistanceChip(station.distance)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
        if (station.lines.isNotEmpty()) {
            Hairline()
            // 只露出最近的三条线：按「到本站还剩几站」从近到远挑，没有实时数据的垫底。
            nearbyLines(station.lines, arrivals).forEachIndexed { index, summary ->
                if (index > 0) Hairline()
                StationLineRow(summary, arrivals.firstOrNull { it.name == summary.name && it.direction == summary.direction }) { onLine(summary) }
            }
        }
        station.linesError?.let {
            Hairline()
            Box(Modifier.padding(12.dp)) { ErrorCard(it, onOpen) }
        }
        error?.let {
            Hairline()
            Box(Modifier.padding(12.dp)) { ErrorCard(it, onOpen) }
        }
    }
}

/** 官方样式的线路行：左侧线路号加粗 + 开往方向，右侧彩色到站结论 + 距离。 */
@Composable private fun StationLineRow(line: LineSummary, arrival: Arrival?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().touch(line.name).clickable(onClick = onClick)
            .heightIn(min = 70.dp).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineBadge(line.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.to, line.to), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.from.isNotBlank() && line.from != line.to) {
                Text(stringResource(R.string.from_stop, line.from), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        ArrivalText(arrival)
    }
}

/** 首页附近站点卡片默认展示的线路：按「到本站还剩几站」从近到远取前三条。 */
internal fun nearbyLines(lines: List<LineSummary>, arrivals: List<Arrival>): List<LineSummary> =
    orderLinesByNear(lines, arrivals).take(3)
