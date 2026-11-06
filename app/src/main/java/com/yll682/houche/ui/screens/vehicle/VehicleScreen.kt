package com.yll682.houche.ui.screens.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun VehicleScreen(
    vm: VehicleViewModel,
    back: () -> Unit,
    share: (String, String) -> Unit,
    toggleTracking: () -> Unit,
    notify: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    VehicleContent(state, back, share, toggleTracking, notify, { vm.refresh() })
}
