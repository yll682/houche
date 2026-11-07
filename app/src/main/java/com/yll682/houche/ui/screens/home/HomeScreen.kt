package com.yll682.houche.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*
import com.yll682.houche.ui.screens.settings.SettingsBody
import com.yll682.houche.ui.screens.settings.SettingsViewModel

@Composable fun HomeScreen(vm: HomeViewModel, settingsVm: SettingsViewModel, granted: Boolean, segment: Int, selectSegment: (Int) -> Unit, locate: () -> Unit, search: () -> Unit, cities: () -> Unit, line: (City, LineSummary, Station?) -> Unit, station: (City, Station) -> Unit, metro: (String?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    HomeContent(state, vm.city, granted, segment, selectSegment, locate, search, cities, line, station,
        metro = metro,
        settingsContent = {
            SettingsBody(
                settingsState,
                chooseCity = cities,
                clearCache = { settingsVm.clearCache() },
                checkUpdate = { settingsVm.checkUpdate() },
                // 下载交给系统浏览器：应用自己不做 APK 安装，也不持有任何凭证。
                download = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            )
        })
}
