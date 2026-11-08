package com.yll682.houche.ui.screens.metro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroEtaState
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroRealtime
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 「列车位置」主卡片（官方白卡：10dp 圆角、无描边无阴影）。
 *
 * 从上到下就是官方那四行，顺序和缩进都按实测：
 * 方向行（左 15.3dp / 右 21dp）→ 站名+刷新行（左 25dp / 右 20dp）→ ETA 蓝块（左右 20dp）
 * → 位置条（左右 10dp，选中站落在视口 78%）。
 *
 * ETA 蓝块绑定 [etaState]（Loading / Empty / Ready / Unavailable），**容器永远在**：
 * 切站瞬间只是换成占位文案，位置条继续用上一批成功的观测。方向只解析一次，
 * 方向行、ETA 行、位置条三处用的是同一个方向。
 */
@Composable
fun MetroRealtimeCard(
    direction: MetroDirection?,
    directionCount: Int,
    stationCode: String,
    etaState: MetroEtaState,
    loading: Boolean,
    note: String,
    stationName: String,
    line: MetroLine,
    directionIndex: Int,
    onDirection: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSelectStation: (String) -> Unit = {},
    onTransfer: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // 本站第 k 行 → 第几班：与位置条**同一套**「到本站先后」编号（[arrivalOrdinals]）。
    // 补车（arrivalRowOrder 为 null，或已过本站）不参与本站 ETA 行；行序与位置条因此同向。
    val ordinals = remember(direction, stationCode) { direction?.arrivalOrdinals(stationCode) }
    val ordinalOfRow: (Int) -> Int? = remember(ordinals) { { row -> ordinals?.ordinalOfRow(row) } }
    // 一次成功批次都还没有时，位置条也要画得出来：只用线路自己的完整站表，不编造任何车。
    val stripDirection = direction ?: remember(line) { line.fallbackStripDirection() }
    // 深色卡比页底亮一档（1.12:1，与官方浅色白卡 vs #F1F1F1 同强度），仍然无描边无阴影。
    val palette = officialPalette()
    Column(modifier.fillMaxWidth().clip(OfficialCardShape).background(palette.Card)) {
        Spacer(Modifier.height(14.dp))
        MetroDirectionRow(
            origin = direction?.origin ?: line.from,
            destination = direction?.destination ?: line.to,
            canSwap = directionCount > 1,
            onSwap = {
                if (directionCount > 1) onDirection((directionIndex.coerceIn(0, directionCount - 1) + 1) % directionCount)
            }
        )
        Spacer(Modifier.height(12.2.dp))
        StationRow(stationName, stationCode, line, loading, onRefresh, onTransfer)
        Spacer(Modifier.height(13.6.dp))
        // ETA 容器永远在：Loading / Empty / Ready / Unavailable 各有自己的文案。
        MetroEtaBlock(etaState, ordinalOfRow)
        Spacer(Modifier.height(19.dp))
        stripDirection?.let { strip ->
            MetroTrainStrip(
                stationCode, strip, line,
                onSelectStation = onSelectStation,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        // 一次成功批次都没有时，ETA 块已用占位文案说明状态；这里只补失败细节。
        if (direction == null && !loading && note.isNotBlank()) MetroStatusLine(loading, note)
        Spacer(Modifier.height(10.6.dp))
    }
}

/**
 * 还没有任何成功批次时的位置条骨架：站序、站名、选中环照画，车一条都不画。
 * 用的是线路自己的完整站表（`liveStations`），不猜方向、不编造位置。
 */
private fun MetroLine.fallbackStripDirection(): MetroDirection? {
    val codes = liveStations.map { it.code }
    if (codes.isEmpty()) return null
    return MetroDirection(
        origin = from, destination = to, startTime = "", endTime = "", delayMinutes = 0,
        trains = emptyList(), stationCodes = codes, positionTrains = emptyList(), arrivalsAvailable = false
    )
}

/**
 * 站名行：站名 + 线路号圆徽标 +（有换乘目标时）换乘徽标 + 右侧「刷新」；所有元素同一条水平中线。
 *
 * 换乘徽标取的是**当前参考站自己**的 `transferTargets`：换成别的站，徽标跟着换。
 * 徽标底色是目标线路官方色（[TransferBadges]），所以「吕厝」这行会同时出现 1 号线橙和 2 号线绿。
 */
@Composable
internal fun StationRow(
    stationName: String, stationCode: String, line: MetroLine, loading: Boolean,
    onRefresh: () -> Unit, onTransfer: (String, String) -> Unit
) {
    val type = officialTypography()
    val targets = remember(line, stationCode) {
        line.stations.firstOrNull { it.code.isNotBlank() && it.code == stationCode }?.transferTargets.orEmpty()
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 25.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stationName.ifBlank { line.from },
            style = type.StationName,
            maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        MetroStationBadge(line.id, officialLineColor(line))
        if (targets.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            TransferBadges(targets, onSelect = { onTransfer(it.lineId, it.stationCode) })
        }
        Spacer(Modifier.weight(1f))
        RefreshButton(loading, onRefresh)
    }
}

/**
 * 一次完整旋转 700ms（任务书下限「不少于 600ms」）。
 * 用固定的角速度 360°/700ms 匀速转，收尾那一小段也按同一角速度补完，
 * 所以「停」只是转速到零，不是急停——不会看出减速痕迹。
 */
private const val RefreshTurnMillis = 700

/** 点下后至少转这么久：接口命中缓存几十毫秒就回来了，没有最短时长会「点了跟没点一样」。 */
private const val RefreshMinVisibleMillis = 600L

/** 兜底：请求卡住（例如迟到的响应被丢弃，`realtimeLoading` 一直为 true）最多转 8 秒。 */
private const val RefreshMaxMillis = 8_000L

/**
 * 「刷新」：图标 40×40px + 14px 间距 + 文字，点下图标就转，刷新结束平滑停在整圈。
 *
 * 进行中状态全部由 UI 层局部推导，**没有动 ViewModel**：
 * 点下立刻置 `refreshing = true`（不等数据层回写，点即有反馈），
 * 结束条件＝「`loading`（＝`data.realtimeLoading`）已归位」且已转满 [RefreshMinVisibleMillis]，
 * 或到 [RefreshMaxMillis] 兜底。`loading` 用 `rememberUpdatedState` 取最新值，
 * 免得长驻的 effect 读到点击那一刻的旧值。
 */
@Composable
private fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    val label = stringResource(R.string.refresh)
    val type = officialTypography()
    var refreshing by remember { mutableStateOf(false) }
    val angle = remember { Animatable(0f) }
    val isLoading by rememberUpdatedState(loading)

    // 旋转：转起来就一直整圈整圈地转；刷新结束后先把这一圈补完，再归零（整圈与 0 是同一张图，归零不可见）。
    LaunchedEffect(refreshing) {
        if (refreshing) {
            while (true) {
                val turn = ceil(angle.value / 360f) * 360f
                angle.animateTo(turn + 360f, tween(RefreshTurnMillis, easing = LinearEasing))
            }
        } else {
            val turn = ceil(angle.value / 360f) * 360f
            val remain = turn - angle.value
            if (remain > .5f) {
                // 收尾时长按剩余角度等比例给，角速度与旋转中完全一致。
                val millis = (RefreshTurnMillis * remain / 360f).roundToInt().coerceAtLeast(120)
                angle.animateTo(turn, tween(millis, easing = LinearEasing))
            }
            if (angle.value != 0f) angle.snapTo(0f)
        }
    }

    // 进行中状态的收尾：用帧时钟计时（与动画同一个时钟，Robolectric 里 advanceTimeBy 也能推进）。
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        var startNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (startNanos == 0L) startNanos = now
            val elapsed = (now - startNanos) / 1_000_000
            if (elapsed >= RefreshMaxMillis) break
            if (elapsed >= RefreshMinVisibleMillis && !isLoading) break
        }
        refreshing = false
    }

    Row(
        Modifier.clickable {
            if (!refreshing) refreshing = true
            onRefresh()
        }.semantics { contentDescription = label }
            .heightIn(min = 24.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroRefreshGlyph(angle = angle.value)
        Spacer(Modifier.width(4.7.dp))
        Text(label, style = type.RefreshLabel, maxLines = 1)
    }
}

/** 没有实时数据时占住 ETA/位置条的位置，文案沿用原有几种状态。 */
@Composable
private fun MetroStatusLine(loading: Boolean, note: String) {
    val text = when {
        loading -> stringResource(R.string.metro_realtime_loading)
        note.isNotBlank() -> stringResource(R.string.metro_realtime_failed, note)
        else -> stringResource(R.string.metro_realtime_waiting)
    }
    val palette = officialPalette()
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 25.dp, end = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = palette.SubInk
    )
}

/** 供外层判断方向用：没有实时数据时方向为 null，界面退回线路起终点。 */
internal fun MetroRealtime?.directionAt(index: Int): MetroDirection? =
    this?.directions?.getOrNull(index.coerceIn(0, (this.directions.lastIndex).coerceAtLeast(0)))
