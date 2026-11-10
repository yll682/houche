@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.core.*
import com.yll682.houche.ui.components.*

@Composable fun SearchRoute(
    vm: SearchViewModel, back: () -> Unit, line: (LineSummary) -> Unit, station: (Station) -> Unit,
    metro: (String) -> Unit
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(imeVisible) { keyboard?.hide() }
    LaunchedEffect(Unit) { delay(150); focus.requestFocus(); keyboard?.show() }
    SearchScreen(vm, { keyboard?.hide(); back() }, line, station, metro, { keyboard?.hide() }, Modifier.focusRequester(focus))
}
