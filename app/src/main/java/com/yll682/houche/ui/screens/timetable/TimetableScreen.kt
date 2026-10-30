package com.yll682.houche.ui.screens.timetable

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun TimetableScreen(vm: TimetableViewModel, collapsed: Set<Int>, toggle: (Int) -> Unit, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    TimetableContent(state, vm.route, collapsed, toggle, back, { vm.load() })
}
