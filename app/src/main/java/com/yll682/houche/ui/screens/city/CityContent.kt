@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.city

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.City
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.CardShape

@Composable fun CityContent(
    state: CityData, dismiss: () -> Unit, retry: () -> Unit, choose: (City) -> Unit,
    queryChange: (String) -> Unit, provinceChange: (String?) -> Unit, modifier: Modifier = Modifier
) {
    val other = stringResource(R.string.other_province)
    val grouped = remember(state.cities) { state.cities.groupBy { it.province.ifBlank { "" } } }
    val provinces = remember(grouped) {
        grouped.keys.sortedWith(compareBy({ it.isEmpty() }, { it }))
    }
    val keyword = state.query.trim()
    val results = if (keyword.isBlank()) emptyList() else state.cities.filter {
        it.name.contains(keyword, ignoreCase = true) || it.pinyin.contains(keyword, ignoreCase = true)
    }
    val shown = when {
        keyword.isNotBlank() -> results
        state.province != null -> grouped[state.province].orEmpty()
        else -> emptyList()
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            HeaderSurface {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeaderIcon(Icons.Outlined.Close, stringResource(R.string.close), dismiss)
                    Text(stringResource(R.string.choose_city), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                HeaderSearchField(state.query, queryChange, stringResource(R.string.city_search), { queryChange("") },
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp))
            }

            LazyColumn(Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.error != null) item { ErrorCard(state.error, retry) }
                if (keyword.isBlank() && state.province == null && state.located != null) {
                    item {
                        Surface(onClick = { choose(state.located) }, modifier = Modifier.fillMaxWidth().touch(state.located.name),
                            shape = CardShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(state.located.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                if (keyword.isNotBlank()) {
                    if (results.isEmpty()) {
                        item { EmptyState(stringResource(R.string.no_cities)) }
                    } else {
                        item {
                            CardSurface {
                                results.take(60).forEachIndexed { index, city ->
                                    if (index > 0) Hairline()
                                    CityRow(city, keyword = keyword) { choose(city) }
                                }
                            }
                        }
                    }
                } else if (state.province == null) {
                    item { SectionHeader(stringResource(R.string.provinces), provinces.size) }
                    item {
                        CardSurface {
                            provinces.forEachIndexed { index, province ->
                                if (index > 0) Hairline()
                                Row(
                                    Modifier.fillMaxWidth().touch(province.ifBlank { other }).clickable { provinceChange(province) }.padding(horizontal = 16.dp, vertical = 15.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        province.ifBlank { other },
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        (grouped[province]?.size ?: 0).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).touch(stringResource(R.string.provinces)).clickable { provinceChange(null) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(state.province.ifBlank { other }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (shown.isEmpty()) {
                        item { EmptyState(stringResource(R.string.no_cities)) }
                    } else {
                        item {
                            CardSurface {
                                shown.forEachIndexed { index, city ->
                                    if (index > 0) Hairline()
                                    CityRow(city) { choose(city) }
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable private fun CityRow(city: City, keyword: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().touch(city.name).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(city.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (keyword.isBlank() && city.province.isNotBlank()) {
            Text(city.province, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}