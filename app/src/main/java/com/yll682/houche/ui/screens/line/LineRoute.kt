@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.line

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.*
import com.yll682.houche.ui.theme.*

@Composable fun LineRoute(vm: LineViewModel, back: () -> Unit, timetable: (Route) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: LineData()
    val context = LocalContext.current
    var panel by rememberSaveable { mutableIntStateOf(0) }
    var fare by rememberSaveable { mutableStateOf(false) }
    PollEvery20Seconds(data.selected != null && state !is UiState.Loading && state !is UiState.Error) { vm.refreshLive() }
    LineScreen(vm, panel, fare, { panel = it }, { fare = !fare }, back, timetable,
        { text, title -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, title)) })
}
