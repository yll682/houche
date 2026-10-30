package com.yll682.houche.ui.screens.timetable

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.ui.components.*

@Composable fun TimetableRoute(vm: TimetableViewModel, back: () -> Unit) {
    var collapsed by rememberSaveable { mutableStateOf(setOf<Int>()) }
    TimetableScreen(vm, collapsed, { index -> collapsed = if (index in collapsed) collapsed - index else collapsed + index }, back)
}
