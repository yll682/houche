package com.yll682.houche.ui.screens.metro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.yll682.houche.core.MetroInitialLocation
import com.yll682.houche.data.Position
import kotlin.coroutines.cancellation.CancellationException

/**
 * 打开「列车位置」页时**只定位一次**，把首个结果交给 ViewModel。
 *
 * - effect 的键是 `Unit`：重组、回前台、旋转都不会重跑；一次页面实例只问系统一次位置。
 * - 成功 → `vm.applyInitialLocation(lat, lng, accuracy?.toDouble(), coordinateSystem)`
 *   （就是 `MetroInitialLocation` 那四个字段，走同一个入口）。
 * - 无权限 / 定位抛异常 → `vm.applyInitialLocation(null)`。**null 本身会消费掉 VM 里那一次机会**，
 *   所以这里没有「先传 null 再等真结果」的写法，也没有任何重试。
 * - 定位不是关键路径：拿不到就一直用默认线路/站，页面照常显示，不弹窗、不再请求权限。
 *
 * VM 侧的一次性 gate 负责「手动选择优先」和「合格位置早于站表时等第一份非空站表」，
 * UI 只需要保证「每个页面实例只交一次结果」。
 */
@Composable
internal fun LocateOnce(
    locate: suspend () -> Position,
    apply: (MetroInitialLocation?) -> Unit
) {
    // 回调每次重组都是新 lambda，用 rememberUpdatedState 取最新那个，免得 effect 读到旧引用。
    val latestApply = rememberUpdatedState(apply)
    LaunchedEffect(Unit) {
        val position = try {
            locate()
        } catch (cancel: CancellationException) {
            // 页面已经离开组合：不要把这次取消当成「定位失败」去消费 VM 的机会。
            throw cancel
        } catch (error: Exception) {
            null
        }
        latestApply.value(
            position?.let {
                MetroInitialLocation(it.lat, it.lng, it.accuracy?.toDouble(), it.coordinateSystem)
            }
        )
    }
}