@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.yll682.houche.ui.screens.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.*
import com.yll682.houche.core.*
import com.yll682.houche.ui.*
import com.yll682.houche.ui.components.*

@Composable fun HomeRoute(vm: HomeViewModel, search: () -> Unit, cities: () -> Unit, settings: () -> Unit, line: (City, LineSummary, Station?) -> Unit, station: (City, Station) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: HomeData()
    val context = LocalContext.current
    var segment by rememberSaveableState(0)
    var granted by remember { mutableStateOf(false) }
    fun permission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
        if (granted) vm.locate() else vm.denied()
    }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val now = permission()
        if (now && (!granted || data.position == null)) vm.locate()
        granted = now
    }
    fun locate() {
        if (permission()) vm.locate()
        else if (data.permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    PollEvery20Seconds(data.stations.isNotEmpty()) { vm.refreshLive() }
    HomeScreen(vm, granted, segment, { segment = it }, ::locate, search, cities, settings, line, station)
}

@Composable private fun rememberSaveableState(initial: Int) = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initial) }
