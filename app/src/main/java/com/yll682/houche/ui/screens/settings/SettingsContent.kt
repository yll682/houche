@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.yll682.houche.ui.theme.Capsule
import com.yll682.houche.R
import com.yll682.houche.data.RefreshPolicy
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
    val scroll = rememberScrollState()
    val reselect = LocalSettingsReselect.current
    LaunchedEffect(reselect) { if (reselect > 0) scroll.animateScrollTo(0) }
    var pickInterval by remember { mutableStateOf(false) }
    if (pickInterval) IntervalSheet { pickInterval = false }
    Column(
        modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(Capsule).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(38.dp), tint = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.version, data.versionName, data.versionCode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardSurface {
            SettingRow(
                icon = Icons.Outlined.LocationCity,
                title = stringResource(R.string.default_city),
                value = data.city.name,
                onClick = chooseCity
            )
            Hairline()
            SettingRow(
                icon = Icons.Outlined.CleaningServices,
                title = stringResource(R.string.clear_cache),
                value = if (data.cleared) stringResource(R.string.cache_cleared) else null,
                onClick = clearCache
            )
            Hairline()
            SettingRow(
                icon = Icons.Outlined.DirectionsBus,
                title = stringResource(R.string.refresh_interval),
                value = intervalLabel(RefreshPolicy.seconds),
                onClick = { pickInterval = true }
            )
        }
        CardSurface {
            SettingRow(
                icon = Icons.Outlined.SystemUpdate,
                title = stringResource(R.string.update_check),
                value = when {
                    update.checking -> stringResource(R.string.update_checking)
                    update.error != null -> stringResource(R.string.update_failed)
                    release == null -> stringResource(R.string.update_check_action)
                    release != null && update.hasUpdate -> release.versionName
                    else -> stringResource(R.string.update_up_to_date)
                },
                onClick = { if (!update.checking) checkUpdate() }, loading = update.checking
            )
            if (update.hasUpdate && release != null) UpdateDetail(release = release, download = download)
        }
        CardSurface {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.about), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_text).replace("每 20 秒", "每 ${RefreshPolicy.seconds} 秒"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String?, onClick: () -> Unit, loading: Boolean = false) {
    val iconColor = when (icon) {
        Icons.Outlined.LocationCity -> Color(0xFF54A5DB)
        Icons.Outlined.CleaningServices -> Color(0xFF9B73DB)
        else -> Color(0xFF65B76C)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(iconColor), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value?.let {
            Box(Modifier.widthIn(max = 140.dp)) { TelegramSettingValue(it, loading) }
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
    }
}

private fun intervalLabel(seconds: Int): String = if (seconds >= 60) "${seconds / 60} 分钟" else "$seconds 秒"

/** 「自动刷新间隔」选择面板：当前项带「当前」和对勾，点一项即生效并关闭。 */
@Composable private fun IntervalSheet(dismiss: () -> Unit) {
    TelegramSheet(dismiss = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.refresh_interval_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            RefreshPolicy.options.forEach { value ->
                val current = value == RefreshPolicy.seconds
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { RefreshPolicy.set(value); dismiss() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(intervalLabel(value), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (current) {
                        Text(stringResource(R.string.refresh_interval_current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
