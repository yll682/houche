@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.city

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.*
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
import com.yll682.houche.ui.theme.FieldShape

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
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(Icons.Default.Close, stringResource(R.string.close), dismiss, tint = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.choose_city), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = queryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                singleLine = true,
                shape = FieldShape,
                placeholder = { Text(stringResource(R.string.city_search), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton({ queryChange("") }, Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            Spacer(Modifier.height(8.dp))
            Hairline()

            LazyColumn(Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.error != null) item { ErrorCard(state.error, retry) }
                if (keyword.isBlank() && state.province == null && state.located != null) {
                    item {
                        CardSurface(onClick = { choose(state.located) }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.located_city, state.located.name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
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
                                    Modifier.fillMaxWidth().touch(province).clickable { provinceChange(province) }.padding(horizontal = 16.dp, vertical = 15.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        province.ifBlank { other },
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        (grouped[province]?.size ?: 0).toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).touch(stringResource(R.string.provinces)).clickable { provinceChange(null) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                item {
                    Text(
                        stringResource(R.string.city_note),
                        Modifier.padding(top = 8.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
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