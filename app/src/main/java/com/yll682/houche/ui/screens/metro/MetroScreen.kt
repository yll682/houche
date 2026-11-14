package com.yll682.houche.ui.screens.metro

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yll682.houche.data.LocationProvider
import com.yll682.houche.ui.components.PollEvery20Seconds
import kotlinx.coroutines.flow.Flow

@Composable fun MetroScreen(vm: MetroViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: MetroData()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            vm.setRealtimeVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        vm.setRealtimeVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); vm.setRealtimeVisible(false) }
    }
    MetroEventEffects(vm.events)
    val locator = remember(context) { LocationProvider(context) }
    // Tab 离开组合也不取消首次定位；是否已经问过系统由同城 ViewModel 记住。
    LaunchedEffect(vm, locator) { vm.requestInitialLocation { locator.locate() } }
    // 实时到站按官方节奏持续刷新；页面退到后台就停，回来再问一次。
    PollEvery20Seconds(enabled = data.lines.isNotEmpty()) { vm.refreshRealtime() }
    MetroContent(
        state,
        select = { vm.select(it) },
        selectStation = { vm.selectStation(it) },
        selectDirection = { vm.selectDirection(it) },
        // 手动刷新沿用现有的实时轮询入口（二十秒批次复用策略不动，只是提前问一次）。
        refresh = { vm.refreshRealtime() },
        // 换乘徽标：一次提交完整目标（目标线 id + 目标线自己的站码）；false 时数据层保持当前选择。
        selectTransfer = { lineId, stationCode -> vm.selectTransfer(lineId, stationCode) },
        // 车标与目标站：位置条上的选中状态放在 VM 里，重组与刷新都不会丢。
        selectTrain = { markerKey, nextStationCode -> vm.selectTrain(markerKey, nextStationCode) },
        selectTargetStation = { code -> vm.selectTargetStation(code) }
    )
}

/** 仅在页面前台消费一次性失败事件；重组和轮询本身不会触发 Toast。 */
@Composable
internal fun MetroEventEffects(events: Flow<MetroEvent>) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(events, lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { event ->
                Toast.makeText(context, metroEventMessage(event), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
