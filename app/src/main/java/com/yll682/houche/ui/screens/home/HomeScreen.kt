package com.yll682.houche.ui.screens.home

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*

@Composable fun HomeScreen(vm: HomeViewModel, granted: Boolean, segment: Int, selectSegment: (Int) -> Unit, locate: () -> Unit, search: () -> Unit, cities: () -> Unit, settings: () -> Unit, line: (City, LineSummary, Station?) -> Unit, station: (City, Station) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    HomeContent(state, vm.city, granted, segment, selectSegment, locate, search, cities, settings, line, station)
}
