package com.yll682.houche.ui.screens.line

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.Route
import com.yll682.houche.core.Vehicle

@Composable fun LineScreen(vm: LineViewModel, panel: Int, fare: Boolean, selectPanel: (Int) -> Unit, toggleFare: () -> Unit, back: () -> Unit, timetable: (Route) -> Unit, share: (String, String) -> Unit, openVehicle: (Route, Vehicle) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LineContent(state, vm.city, panel, fare, selectPanel, toggleFare, back, timetable,
        { vm.favorite() }, share, { vm.retry() }, { vm.load(true) }, { vm.preview(it) }, { vm.select(it); vm.closePreview() }, { vm.closePreview() },
        openVehicle = { vehicle -> state.data?.route?.let { openVehicle(it, vehicle) } })
}
