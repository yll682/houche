package com.yll682.houche.ui.screens.station

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*

@Composable fun StationRoute(vm: StationViewModel, back: () -> Unit, line: (LineSummary, Station) -> Unit, metro: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    PollEvery20Seconds(state !is UiState.Loading && state !is UiState.Error) { vm.refreshLive() }
    StationScreen(vm, back, line, metro)
}
