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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*

/**
 * 设置内容本体：不再有自己的顶栏，由首页的第三个 Tab 承载。
 * checkUpdate / download 由上层接线，默认空实现保证既有调用方仍能编译。
 */
@Composable fun SettingsBody(
    state: UiState<SettingsData>, chooseCity: () -> Unit, clearCache: () -> Unit,
    modifier: Modifier = Modifier,
    checkUpdate: () -> Unit = {}, download: (String) -> Unit = {}
) {
    val data = state.data ?: return
    val update = data.update
    val release = update.release
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
            Hairline()
            SettingRow(
                icon = Icons.Default.SystemUpdate,
                title = stringResource(R.string.update_check),
                value = when {
                    update.checking -> stringResource(R.string.update_checking)
                    update.error != null -> stringResource(R.string.update_failed)
                    release == null -> stringResource(R.string.update_check_action)
                    release != null && update.hasUpdate -> release.versionName
                    else -> stringResource(R.string.update_up_to_date)
                },
                onClick = { if (!update.checking) checkUpdate() }
            )
            if (update.hasUpdate && release != null) UpdateDetail(release = release, download = download)
        }
        CardSurface {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (data.metroNote.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.metro_source, data.metroNote),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (data.metroSource.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.metro_data_source, data.metroSource),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.version, data.versionName, data.versionCode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 有新版本时在「检查更新」这一行下面展开：版本说明最多 4 行 + 下载按钮。 */
@Composable private fun UpdateDetail(release: com.yll682.houche.core.RemoteRelease, download: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, bottom = 12.dp)) {
        if (release.notes.isNotEmpty()) {
            Text(
                release.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
        }
        if (release.downloadUrl.isNotEmpty()) {
            Button(
                onClick = { download(release.downloadUrl) },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.update_download), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).touch(title).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
