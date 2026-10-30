@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.*
import com.yll682.houche.R
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.Capsule

@Composable fun HomeScreen(vm: HomeViewModel, search: () -> Unit, cities: () -> Unit, settings: () -> Unit, line: (City, LineSummary, Station?) -> Unit, station: (City, Station) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: HomeData()
    val context = LocalContext.current
    var segment by rememberSaveableState(0)
    var granted by remember { mutableStateOf(false) }
    fun permission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
        if (granted) vm.locate() else vm.denied()
    }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val now = permission()
        if (now && (!granted || data.position == null)) vm.locate()
        granted = now
    }
    fun locate() {
        if (permission()) vm.locate()
        else if (data.permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    PollEvery20Seconds(data.stations.isNotEmpty()) { vm.refreshLive() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            TextButton(cities, Modifier.touch(stringResource(R.string.choose_city))) { Text(vm.city.name); Icon(Icons.Default.ExpandMore, stringResource(R.string.choose_city)) }
        } }, actions = { ActionIcon(Icons.Default.Settings, stringResource(R.string.settings), settings) }, scrollBehavior = scroll, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surface))
    }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        PullToRefresh(state is UiState.Loading, { locate() }) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Surface(onClick = search, modifier = Modifier.fillMaxWidth().touch(stringResource(R.string.search_hint)), shape = Capsule, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Default.Search, stringResource(R.string.search_label)); Text(stringResource(R.string.search_hint)) }
                } }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (state is UiState.Loading) R.string.locating else if (data.position != null) R.string.located else R.string.nearby), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    ActionIcon(Icons.Default.Refresh, stringResource(R.string.refresh)) { locate() }
                    val locationAction = stringResource(if (data.position == null) R.string.enable_location else R.string.switch_city)
                    TextButton({ if (data.position == null) locate() else cities() }, Modifier.touch(locationAction)) { Text(locationAction) }
                } }
                item { Segments(listOf(stringResource(R.string.nearby), stringResource(R.string.favorites)), segment) { segment = it } }
                if (segment == 0) {
                    if (!granted || data.permissionDenied || data.position == null && state !is UiState.Loading) item {
                        Card(shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.MyLocation, stringResource(R.string.permission_note), tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(if (data.permissionDenied) R.string.location_denied else R.string.location_explain))
                                val label = stringResource(if (data.permissionDenied) R.string.open_settings else R.string.allow_location)
                                Button({
                                    locate()
                                }, Modifier.touch(label), shape = Capsule) { Text(label) }
                            }
                        }
                    }
                    feedback(state) { locate() }
                    if (data.position != null) item { Text(stringResource(if (data.position.fromLastKnown) R.string.last_location else if (data.cityResolved) R.string.location_city else R.string.location_fallback), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (data.stations.isEmpty() && state is UiState.Empty) item { EmptyState(stringResource(R.string.no_stations), action = stringResource(R.string.search_action), onClick = search) }
                    itemsIndexed(data.stations) { index, stop ->
                        Card(modifier = Modifier.animateItem(), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth().touch(stop.name).clickable { station(vm.city, stop) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(stop.name, style = MaterialTheme.typography.titleMedium); val count = stop.sameNum ?: stop.platformNumbers.size; if (count > 1) Text(stringResource(R.string.platform_count, count), style = MaterialTheme.typography.bodySmall) }
                                    DistanceChip(stop.distance)
                                }
                                stop.lines.take(3).forEach { summary ->
                                    val arrival = data.arrivals[index]?.firstOrNull { it.name == summary.name && it.direction == summary.direction }
                                    LineRow(summary, arrival) { line(vm.city, summary, stop) }
                                }
                                if (index >= 3 || stop.lines.isEmpty()) TextButton({ station(vm.city, stop) }, Modifier.touch(stringResource(R.string.click_view))) { Text(stringResource(R.string.click_view)) }
                                if (index in data.liveErrors) ErrorCard(data.liveErrors[index]) { locate() }
                                stop.linesError?.let { ErrorCard(it) { locate() } }
                            }
                        }
                    }
                } else {
                    feedback(state) { locate() }
                    if (data.favoriteLines.isEmpty() && data.favoriteStations.isEmpty()) item { EmptyState(stringResource(R.string.no_favorites), stringResource(R.string.favorite_tip), stringResource(R.string.search_action), search) }
                    item { SectionHeader(stringResource(R.string.favorite_lines), data.favoriteLines.size) }
                    items(data.favoriteLines) { saved -> Column(Modifier.animateItem()) { Text(saved.city.name, style = MaterialTheme.typography.labelMedium); LineRow(saved.line) { line(saved.city, saved.line, null) } } }
                    item { SectionHeader(stringResource(R.string.favorite_stations), data.favoriteStations.size) }
                    items(data.favoriteStations) { saved -> ListItem(headlineContent = { Text(saved.station.name) }, supportingContent = { Text(saved.city.name) }, modifier = Modifier.animateItem().touch(saved.station.name).clickable { station(saved.city, saved.station) }) }
                }
            }
        }
    }
}
@Composable private fun rememberSaveableState(initial: Int) = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initial) }
