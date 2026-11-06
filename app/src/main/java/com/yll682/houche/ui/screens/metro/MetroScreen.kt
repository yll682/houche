package com.yll682.houche.ui.screens.metro

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun MetroScreen(vm: MetroViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    MetroContent(state, { vm.select(it) }, back)
}
