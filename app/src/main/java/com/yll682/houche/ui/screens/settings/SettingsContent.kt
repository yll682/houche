@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.CardShape

@Composable fun SettingsContent(
    state: UiState<SettingsData>, back: () -> Unit, chooseCity: () -> Unit, clearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = state.data ?: return
    Page(stringResource(R.string.settings), back, modifier) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardSurface {
                SettingRow(
                    icon = Icons.Default.LocationCity,
                    title = stringResource(R.string.default_city),
                    value = data.city.name,
                    onClick = chooseCity
                )
                Hairline()
                SettingRow(
                    icon = Icons.Default.CleaningServices,
                    title = stringResource(R.string.clear_cache),
                    value = if (data.cleared) stringResource(R.string.cache_cleared) else null,
                    onClick = clearCache
                )
            }
            SectionHeader(stringResource(R.string.about))
            CardSurface {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.version, data.versionName, data.versionCode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().touch(title).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}