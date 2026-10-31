@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.home

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
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

/**
 * 首页版式参照厦门公交官方 App：蓝色渐变顶栏 + 胶囊搜索框 + 站点卡片。
 * 到站信息按官方配色分级：即将到站橙红、有站数蓝色、等待发车灰色。
 * 搜索只有一个入口（顶栏胶囊框），宫格与底部导航不重复承载同一动作。
 */
@Composable fun HomeContent(
    state: UiState<HomeData>, city: City, granted: Boolean, segment: Int, selectSegment: (Int) -> Unit,
    locate: () -> Unit, search: () -> Unit, cities: () -> Unit, settings: () -> Unit,
    line: (City, LineSummary, Station?) -> Unit, station: (City, Station) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data ?: HomeData()
    val loading = state is UiState.Loading
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HomeHeader(city, cities, locate, search) },
        bottomBar = { HomeBottomBar(segment, selectSegment, settings) }
    ) { padding ->
        PullToRefresh(loading, { locate() }) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (segment == 0) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsNone, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
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
                    feedback(state) { locate() }
                    if (data.stations.isEmpty() && state is UiState.Empty) {
                        item { EmptyState(stringResource(R.string.no_stations), action = stringResource(R.string.search_action), onClick = search) }
                    }
                    itemsIndexed(data.stations, key = { index, stop -> "$index-${stop.name}" }) { index, stop ->
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
                    feedback(state) { locate() }
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
                                        Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
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

/** 蓝色渐变顶栏：城市选择 + 居中标题 + 刷新，下方是白色胶囊搜索框。 */
@Composable private fun HomeHeader(city: City, cities: () -> Unit, locate: () -> Unit, search: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(HeaderTop, HeaderBottom)))
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = cities,
                modifier = Modifier.height(32.dp).touch(stringResource(R.string.choose_city)),
                shape = Capsule,
                color = Color.White.copy(alpha = .18f)
            ) {
                Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(city.name, style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsBus, null, Modifier.size(20.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = Color.White, letterSpacing = 1.sp)
            }
            IconButton(locate, Modifier.size(48.dp)) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh), tint = Color.White) }
        }
        Surface(
            onClick = search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).height(44.dp)
                .touch(stringResource(R.string.search_hint)),
            shape = Capsule,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.search_hint),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 底部导航：首页 / 收藏 / 设置，搜索由顶栏胶囊框承担，不在这里重复。 */
@Composable private fun HomeBottomBar(segment: Int, selectSegment: (Int) -> Unit, settings: () -> Unit) {
    Column {
        Hairline()
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            NavigationBarItem(
                selected = segment == 0,
                onClick = { selectSegment(0) },
                icon = { Icon(Icons.Default.Home, null) },
                label = { Text(stringResource(R.string.home)) },
                colors = flatNavColors()
            )
            NavigationBarItem(
                selected = segment == 1,
                onClick = { selectSegment(1) },
                icon = { Icon(Icons.Default.Star, null) },
                label = { Text(stringResource(R.string.favorites)) },
                colors = flatNavColors()
            )
            NavigationBarItem(
                selected = false,
                onClick = settings,
                icon = { Icon(Icons.Default.Settings, null) },
                label = { Text(stringResource(R.string.settings)) },
                colors = flatNavColors()
            )
        }
    }
}

@Composable private fun flatNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorColor = Color.Transparent
)

@Composable private fun LocationCard(denied: Boolean, locate: () -> Unit) {
    CardSurface {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(Capsule).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
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
            Icon(Icons.Default.Signpost, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
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
            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
        if (station.lines.isNotEmpty()) {
            Hairline()
            station.lines.take(3).forEachIndexed { index, summary ->
                if (index > 0) Hairline()
                StationLineRow(summary, arrivals.firstOrNull { it.name == summary.name && it.direction == summary.direction }) { onLine(summary) }
            }
            if (collapsed) {
                Hairline()
                TextButton(
                    onOpen,
                    Modifier.fillMaxWidth().touch(stringResource(R.string.passing_lines)),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) { Text(stringResource(R.string.passing_lines), style = MaterialTheme.typography.labelLarge) }
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
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 官方样式：第一行「线路号 + 开往终点」，第二行只补充起点，避免终点被说两遍。
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.to, line.to),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (line.from.isNotBlank() && line.from != line.to) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.from_stop, line.from),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        ArrivalText(arrival)
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
