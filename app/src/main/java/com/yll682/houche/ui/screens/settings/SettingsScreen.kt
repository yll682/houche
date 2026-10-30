package com.yll682.houche.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yll682.houche.AppGraph
import com.yll682.houche.BuildConfig
import com.yll682.houche.R
import com.yll682.houche.core.City
import com.yll682.houche.ui.components.*
import kotlinx.coroutines.launch

@Composable fun SettingsScreen(graph: AppGraph, city: City, back: () -> Unit, chooseCity: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val cleared = stringResource(R.string.cache_cleared)
    val generic = stringResource(R.string.error_generic)
    fun clearCache() { scope.launch { try { graph.repository().clearCache(); snackbar.showSnackbar(cleared) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; android.util.Log.e("Houche", "清缓存失败", e); message = (e as? com.yll682.houche.core.BusException)?.message ?: generic; failed = true } } }
    Page(stringResource(R.string.settings), back) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ListItem(headlineContent = { Text(stringResource(R.string.default_city)) }, supportingContent = { Text(city.name) }, modifier = Modifier.touch(stringResource(R.string.default_city)).clickable(onClick = chooseCity)) }
                item { ListItem(headlineContent = { Text(stringResource(R.string.clear_cache)) }, modifier = Modifier.touch(stringResource(R.string.clear_cache)).clickable { clearCache() }) }
                if (failed) item { ErrorCard(message) { failed = false; clearCache() } }
                item { SectionHeader(stringResource(R.string.about)); Text(stringResource(R.string.about_text)) }
                item { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
    }
}
