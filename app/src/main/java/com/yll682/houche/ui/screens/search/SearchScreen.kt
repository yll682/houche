package com.yll682.houche.ui.screens.search

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*

@Composable fun SearchScreen(vm: SearchViewModel, back: () -> Unit, line: (LineSummary) -> Unit, station: (Station) -> Unit, hideKeyboard: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    SearchContent(state, back, { vm.rememberQuery(); hideKeyboard(); line(it) }, { vm.rememberQuery(); hideKeyboard(); station(it) }, { vm.query(it) }, { vm.query(it, true) }, { vm.rememberQuery(); hideKeyboard() }, { vm.clear() }, { vm.remove(it) }, inputModifier = modifier)
}
