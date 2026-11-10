package com.yll682.houche.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*
import com.yll682.houche.ui.screens.settings.SettingsBody
import com.yll682.houche.ui.screens.settings.SettingsViewModel

@Composable fun HomeScreen(
    vm: HomeViewModel, settingsVm: SettingsViewModel, granted: Boolean,
    segment: Int, selectSegment: (Int) -> Unit, locate: () -> Unit,
    search: () -> Unit, cities: () -> Unit, line: (City, LineSummary, Station?) -> Unit,
    station: (City, Station) -> Unit, metroContent: @Composable () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    HomeContent(
        state = state,
        city = vm.city,
        granted = granted,
        segment = segment,
        selectSegment = selectSegment,
        locate = locate,
        search = search,
        cities = cities,
        line = line,
        station = station,
        metroContent = metroContent,
        refresh = { scope.launch { vm.refreshLive() } },
        settingsContent = {
            SettingsBody(
                settingsState,
                chooseCity = cities,
                clearCache = { settingsVm.clearCache() },
                checkUpdate = { settingsVm.checkUpdate() },
                // 下载交给系统浏览器：应用自己不做 APK 安装，也不持有任何凭证。
                download = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            )
        }
    )
}
