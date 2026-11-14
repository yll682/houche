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
    // 用户从可见页面启动 FGS；Android 13+ 先申请提醒权限，拒绝后仍可仅跟踪。
    fun startTracking() {
        vm.startTracking()
        vm.refresh()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 拒绝提醒权限仍可跟踪；系统允许 FGS 运行，但不会展示普通到站通知。
        startTracking()
    }
    // 「通知设置」：打开系统里本应用的通知设置页（不再发测试通知）。
    val notify: () -> Unit = {
        context.startActivity(
            Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    VehicleContent(
        state = state,
        back = back,
        share = share,
        toggleTracking = {
            val data = vm.state.value.data
            if (data != null) {
                if (data.tracking) {
                    vm.stop()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !VehicleTracker.canNotify(context)) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startTracking()
                }
            }
        },
        notify = notify,
        refresh = { vm.refresh() },
        selectVehicle = { vm.selectVehicle(it) },
        selectAlight = { vm.selectAlight(it) },
        clearAlight = { vm.clearAlight() }
    )
}
