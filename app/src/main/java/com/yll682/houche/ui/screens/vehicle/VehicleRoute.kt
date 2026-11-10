package com.yll682.houche.ui.screens.vehicle

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yll682.houche.data.VehicleTracker
import com.yll682.houche.ui.UiState
import com.yll682.houche.ui.components.PollEvery20Seconds

@Composable
fun VehicleRoute(vm: VehicleViewModel, back: () -> Unit, share: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SideEffect { vm.bindContext(context) }
    PollEvery20Seconds(enabled = state !is UiState.Loading && state !is UiState.Error) { vm.refreshLive() }
    // 点「开启到站提醒」：Android 13+ 未授权通知时先申请，授权后再开始；<13 直接开始。
    fun startTracking() {
        val data = vm.state.value.data ?: return
        vm.toggleTracking()
        VehicleTracker.start(context, vm.city, data.route.name, data.plate, data.boarding?.name)
        vm.refresh()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startTracking()
    }
    // 「通知设置」：打开系统里本应用的通知设置页（不再发测试通知）。
    val notify: () -> Unit = {
        context.startActivity(
            Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !VehicleTracker.canNotify(context)) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startTracking()
                }
            }
        },
        notify = notify
    )
}