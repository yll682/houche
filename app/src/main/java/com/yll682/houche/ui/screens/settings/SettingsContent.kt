package com.yll682.houche.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.ui.components.*

@Composable fun SettingsContent(state: com.yll682.houche.ui.UiState<SettingsData>, back: () -> Unit, chooseCity: () -> Unit, clearCache: () -> Unit, modifier: Modifier = Modifier) {
    val data = state.data ?: return
    val city = data.city
    Page(stringResource(R.string.settings), back, modifier = modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ListItem(headlineContent = { Text(stringResource(R.string.default_city)) }, supportingContent = { Text(city.name) }, modifier = Modifier.touch(stringResource(R.string.default_city)).clickable(onClick = chooseCity)) }
                item { ListItem(headlineContent = { Text(stringResource(R.string.clear_cache)) }, modifier = Modifier.touch(stringResource(R.string.clear_cache)).clickable { clearCache() }) }
                if (state is com.yll682.houche.ui.UiState.Error) item { ErrorCard(state.message, clearCache) }
                item { SectionHeader(stringResource(R.string.about)); Text(stringResource(R.string.about_text)) }
                item { Text(stringResource(R.string.version, data.versionName, data.versionCode), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (data.cleared) Snackbar(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(16.dp)) { Text(stringResource(R.string.cache_cleared)) }
        }
    }
}
