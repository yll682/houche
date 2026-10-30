@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.yll682.houche.ui.screens.city

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.yll682.houche.R
import com.yll682.houche.core.City
import com.yll682.houche.ui.components.*

@Composable fun CitySheet(cities: List<City>, located: City?, dismiss: () -> Unit, error: String? = null, failed: Boolean = false, retry: () -> Unit = {}, choose: (City) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var province by rememberSaveable { mutableStateOf<String?>(null) }
    val other = stringResource(R.string.other_province)
    val grouped = remember(cities, other) { cities.groupBy { it.province.ifBlank { other } } }
    val matching = remember(cities, query, province) { if (query.isNotBlank()) cities.filter { it.name.contains(query.trim(), true) || it.pinyin.contains(query.trim(), true) } else cities.filter { it.province.ifBlank { other } == province } }
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(province != null && query.isBlank()) { province = null }
        Page(province ?: stringResource(R.string.choose_city), { if (province != null && query.isBlank()) province = null else dismiss() }, actions = { ActionIcon(Icons.Default.Close, stringResource(R.string.close), dismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) item { ErrorCard(error, retry) }
                item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().touch(stringResource(R.string.city_search)), singleLine = true, label = { Text(stringResource(R.string.city_search)) }, leadingIcon = { Icon(Icons.Default.Search, stringResource(R.string.city_search)) }) }
                item {
                    if (located == null) ListItem(headlineContent = { Text(stringResource(R.string.location_not_available)) }, leadingContent = { Icon(Icons.Default.MyLocation, stringResource(R.string.located)) })
                    else { val label = stringResource(R.string.located_city, located.name); ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(Icons.Default.MyLocation, label) }, modifier = Modifier.touch(label).clickable { choose(located) }) }
                }
                stickyHeader { Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) { SectionHeader(if (query.isNotBlank()) stringResource(R.string.choose_city) else province ?: stringResource(R.string.provinces)) } }
                if (query.isBlank() && province == null) items(grouped.keys.toList()) { name -> ListItem(headlineContent = { Text(name) }, trailingContent = { Icon(Icons.Default.ChevronRight, name) }, modifier = Modifier.animateItem().touch(name).clickable { province = name }) }
                else {
                    if (matching.isEmpty()) item { EmptyState(stringResource(R.string.no_cities)) }
                    items(matching, key = { it.key }) { city ->
                        val color = MaterialTheme.colorScheme.primary
                        fun highlight(value: String) = buildAnnotatedString {
                            append(value)
                            val start = value.indexOf(query.trim(), ignoreCase = true)
                            if (query.isNotBlank() && start >= 0) addStyle(SpanStyle(color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), start, start + query.trim().length)
                        }
                        ListItem(headlineContent = { Text(highlight(city.name)) }, supportingContent = { Text(highlight(if (query.isNotBlank() && city.pinyin.contains(query.trim(), true)) city.pinyin else city.province)) }, modifier = Modifier.animateItem().touch(city.name).clickable { choose(city) })
                    }
                }
                item { Text(stringResource(R.string.city_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
