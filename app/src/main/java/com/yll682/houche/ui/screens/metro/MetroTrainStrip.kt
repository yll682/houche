package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.positionOf
import kotlin.math.roundToInt

/**
 * 列车位置条（规格 2.6 / 第三节几何模型）。
 *
 * 坐标全部由格宽 P＝68.2dp 推出：站 i 的站心 `x = (i + 0.5)·P`，
 * 车 `x = (p + 0.5)·P`（p 来自 core 的 `positionOf`，整数表示站点、i−0.5 表示前一段），
 * 视口锚点＝选中站落在视口 78%（官方实测 78.4%）。
 *
 * 与旧版的差别（全部是「照抄」要求）：主线 7dp 实色方角、普通站点没有圆点只有白色箭头、
 * 选中站是三同心圆环 Ø20.7/Ø16.7/Ø8.7、车标 20.7dp 方块且「第N班」在图标下方。
 */
@Composable
internal fun MetroTrainStrip(
    realtime: MetroRealtime,
    direction: MetroDirection,
    line: MetroLine,
    modifier: Modifier = Modifier
) {
    val cells = direction.stationCodes
    if (cells.isEmpty()) return
    val names = remember(line) { line.liveStations.associate { it.code to it.name } }
    // 官方 App 实测线路色（未实测的线路回退 metro.json）；主线、箭头、选中站圆环共用一套。
    val tint = officialLineColor(line)
    // 叠在线路色上的白色图形（箭头 / 圆环白隙）在浅色线路上按亮度转深墨。
    val onTint = onLineColor(tint)
    val scroll = rememberScrollState()
    val scale = LocalDensity.current.density
    val selectedIndex = cells.indexOf(realtime.stationCode)

    // 站名块高度按本方向最长的站名算（选中站行距比普通站大 4px，要分开算），
    // 滚动时卡片高度不会跟着名字长短跳。
    val nameInkInset = 3.9.dp
    val namesTop = TrackCenter + StripNameGap - nameInkInset
    val namesHeight = cells.maxOfOrNull { code ->
        val chars = (names[code]?.length ?: 0).coerceIn(1, 8)
        val line = if (code == realtime.stationCode) StripNameLineSelected else StripNameLine
        line * chars
    } ?: StripNameLine
    val height = namesTop + namesHeight + 1.5.dp

    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val viewport = maxWidth
        val contentWidth = StripCell * cells.size
        // 锚点只跟站序/参考站/视口/格宽走；列车轮询变化刻意不进 key，免得抢用户滚动。
        LaunchedEffect(cells, realtime.stationCode, viewport, contentWidth) {
            val anchorDp = when {
                selectedIndex >= 0 -> StripCell * (selectedIndex + .5f)
                else -> direction.positions.map { direction.positionOf(it) }.filter { it >= 0 }
                    .maxOrNull()?.let { StripCell * (it.toFloat() + .5f) }
            }
            if (anchorDp != null) {
                val maxPx = ((contentWidth - viewport).value * scale).roundToInt().coerceAtLeast(0)
                val target = ((anchorDp.value - viewport.value * StripAnchorRatio) * scale)
                    .roundToInt().coerceIn(0, maxPx)
                scroll.scrollTo(target)
            }
        }
        Row(Modifier.horizontalScroll(scroll)) {
            Box(Modifier.width(contentWidth).height(height)) {
                // 主线 + 站间白色箭头 + 选中站圆环，同一张画布上按站心逐个画。
                Canvas(Modifier.width(contentWidth).height(StripRingOuter).offset(y = TrackCenter - StripRingOuter / 2)) {
                    val cy = size.height / 2f
                    val track = StripTrack.toPx()
                    drawRect(tint, topLeft = Offset(0f, cy - track / 2f), size = androidx.compose.ui.geometry.Size(size.width, track))
                    val arrowColor = onTint
                    cells.forEachIndexed { index, _ ->
                        val x = StripCell.toPx() * (index + .5f)
                        // 箭杆 21×7px（7×2.33dp），箭头实心三角 12×21px，尖朝行驶方向。
                        val rodHalf = 1.17.dp.toPx()
                        val rodStart = x - StripArrowRodStart.toPx()
                        val rodEnd = x + StripArrowRodEnd.toPx()
                        drawRect(arrowColor, topLeft = Offset(rodStart, cy - rodHalf), size = androidx.compose.ui.geometry.Size(rodEnd - rodStart, rodHalf * 2))
                        val head = Path().apply {
                            moveTo(rodEnd, cy - track / 2f)
                            lineTo(x + StripArrowTip.toPx(), cy)
                            lineTo(rodEnd, cy + track / 2f)
                            close()
                        }
                        drawPath(head, arrowColor)
                    }
                    if (selectedIndex >= 0) {
                        val cx = StripCell.toPx() * (selectedIndex + .5f)
                        drawCircle(tint, StripRingOuter.toPx() / 2f, Offset(cx, cy))
                        drawCircle(onTint, (StripRingOuter / 2 - StripRingWidth).toPx(), Offset(cx, cy))
                        drawCircle(tint, StripRingCore.toPx() / 2f, Offset(cx, cy))
                    }
                }
                // 竖排站名：一字一行，居中于站心；选中站 Bold + 靛蓝 + 行距 65px。
                cells.forEachIndexed { index, code ->
                    val name = names[code].orEmpty()
                    if (name.isBlank()) return@forEachIndexed
                    val active = index == selectedIndex
                    Text(
                        name.chunked(1).joinToString("\n"),
                        Modifier.offset(x = StripCell * index, y = namesTop).width(StripCell),
                        style = if (active) OfficialType.StripeNameSelected else OfficialType.StripeName,
                        textAlign = TextAlign.Center,
                        maxLines = 8
                    )
                }
                // 车标：方块图标 + 下方「第N班」；没有本站班序的（补位置的区间车）不写班号。
                direction.positions.forEach { train ->
                    val position = direction.positionOf(train)
                    if (position < 0) return@forEach
                    val order = direction.arrivalOrderOf(train)
                    val fraction = (position - kotlin.math.floor(position)).toFloat()
                    val phase = if (fraction > 0.05f && fraction < 0.95f) StripTrainPhase else 0f
                    val centerX = StripCell * (position.toFloat() + .5f - phase)
                    val label = if (order == null) "" else stringResource(R.string.metro_realtime_train_no, order)
                    Box(
                        Modifier.offset(x = centerX - MarkerBox / 2).width(MarkerBox)
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        TrainIcon(lead = order == 1)
                        if (label.isNotBlank()) {
                            Text(
                                label,
                                Modifier.align(Alignment.TopCenter)
                                    .offset(x = 2.dp, y = TrainLabelTop).width(40.dp),
                                style = OfficialType.TrainLabel,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 车标上沿到 block 顶＝0；主线中心 47.5dp，站名 ink 顶 63.5dp（全部来自规格 3.5/3.6）。 */
private val TrackCenter = 47.5.dp

/** 「第N班」ink 顶在 block 顶下 22.3dp，行盒留 1.4dp 上内边距。 */
private val TrainLabelTop = 18.9.dp

/** 车标列宽：车标 20.7dp、「第N班」约 34.5dp，取 40dp 让标签不被父级宽度裁掉。 */
private val MarkerBox = 40.dp

/** 官方车标：62×62px 圆角方块、靛蓝渐变 + 白色列车图形，「第1班」多一个右侧方向三角。 */
@Composable
private fun TrainIcon(lead: Boolean) {
    Box(Modifier.size(StripTrainSize)) {
        Box(
            Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(3.5.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Official.TrainGradientStart, Official.TrainGradientEnd)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.DirectionsSubway, null,
                Modifier.size(15.5.dp),
                tint = Color.White.copy(alpha = .96f)
            )
        }
        if (lead) {
            Canvas(Modifier.align(Alignment.CenterEnd).offset(x = 4.dp).size(4.dp, 6.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, Official.TrainDirectionMark)
            }
        }
    }
}

/** 位置条里单独一班车的显示位置，供测试与调试核对（站点坐标 → 像素中心）。 */
internal fun MetroDirection.stripCenterOf(train: MetroTrain): Float {
    val position = positionOf(train)
    return if (position < 0) -1f else position.toFloat()
}
