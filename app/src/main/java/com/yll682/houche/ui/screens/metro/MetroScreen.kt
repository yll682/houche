package com.yll682.houche.ui.screens.metro

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yll682.houche.data.LocationProvider
import com.yll682.houche.ui.components.PollEvery20Seconds
import kotlinx.coroutines.flow.Flow

@Composable fun MetroScreen(vm: MetroViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.data ?: MetroData()
    val context = LocalContext.current
    MetroEventEffects(vm.events)
    // 定位组件本身无状态，按页面上下文建一次；真正的「只问一次」由 LocateOnce 的 effect 保证。
    val locator = remember(context) { LocationProvider(context) }
    // 首屏：拿一次位置交给 VM 选最近的线路与站点。无权限/失败就交 null（同样消费那一次机会），
    // 页面保持默认线路，不弹窗、不重试。手动选过站之后数据层的 gate 会挡住迟到的位置。
    LocateOnce(locate = { locator.locate() }, apply = { location -> vm.applyInitialLocation(location) })
    // 实时到站按官方节奏持续刷新；页面退到后台就停，回来再问一次。
    PollEvery20Seconds(enabled = data.lines.isNotEmpty()) { vm.refreshRealtime() }
    MetroContent(
        state,
        select = { vm.select(it) },
        back = back,
        selectStation = { vm.selectStation(it) },
        selectDirection = { vm.selectDirection(it) },
        // 手动刷新沿用现有的实时轮询入口（二十秒批次复用策略不动，只是提前问一次）。
        refresh = { vm.refreshRealtime() },
        // 换乘徽标：一次提交完整目标（目标线 id + 目标线自己的站码）；false 时数据层保持当前选择。
        selectTransfer = { lineId, stationCode -> vm.selectTransfer(lineId, stationCode) }
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
