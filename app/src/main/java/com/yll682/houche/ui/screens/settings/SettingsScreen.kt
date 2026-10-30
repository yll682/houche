package com.yll682.houche.ui.screens.settings

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun SettingsScreen(vm: SettingsViewModel, back: () -> Unit, chooseCity: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsContent(state, back, chooseCity, { vm.clearCache() })
}
