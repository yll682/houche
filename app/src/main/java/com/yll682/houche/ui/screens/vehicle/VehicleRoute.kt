package com.yll682.houche.ui.screens.vehicle

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.R
import com.yll682.houche.data.VehicleTracker
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.PollEvery20Seconds

@Composable
fun VehicleRoute(vm: VehicleViewModel, back: () -> Unit, share: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SideEffect { vm.bindContext(context) }
    PollEvery20Seconds(enabled = state !is UiState.Loading && state !is UiState.Error) { vm.refreshLive() }
    val title = context.getString(R.string.vehicle_notify_title)
    val text = context.getString(R.string.vehicle_notify_test)
    // Android 13+ 未授权时系统会静默丢弃通知：先申请权限，授权成功后立刻补发测试提醒。
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VehicleTracker.notify(context, title, text)
    }
    val notify: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !VehicleTracker.canNotify(context)) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            VehicleTracker.notify(context, title, text)
        }
    }
    VehicleScreen(
        vm = vm,
        back = back,
        share = share,
        toggleTracking = {
            val data = vm.state.value.data
            if (data != null) {
                if (data.tracking) {
                    VehicleTracker.stop()
                    vm.stop()
                } else {
                    vm.toggleTracking()
                    VehicleTracker.start(context, vm.city, data.route.name, data.plate, data.boarding?.name)
                    vm.refresh()
                }
            }
        },
        notify = notify
    )
}