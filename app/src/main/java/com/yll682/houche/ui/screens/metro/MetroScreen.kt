package com.yll682.houche.ui.screens.metro

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.ui.components.PollEvery20Seconds

@Composable fun MetroScreen(vm: MetroViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: MetroData()
    // 实时到站按官方节奏持续刷新；页面退到后台就停，回来再问一次。
    PollEvery20Seconds(enabled = data.lines.isNotEmpty()) { vm.refreshRealtime() }
    MetroContent(
        state,
        select = { vm.select(it) },
        back = back,
        selectStation = { vm.selectStation(it) },
        selectDirection = { vm.selectDirection(it) }
    )
}