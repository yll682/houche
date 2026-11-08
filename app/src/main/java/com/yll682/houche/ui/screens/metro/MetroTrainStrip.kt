package com.yll682.houche.ui.screens.metro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroRealtime
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.positionOf
import kotlin.math.abs
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
    onSelectStation: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cells = direction.stationCodes
    if (cells.isEmpty()) return
    val names = remember(line) { line.liveStations.associate { it.code to it.name } }
    // selectStation 只认 liveStations 里的站码，别的点上去是死目标，直接不给点击语义。
    val selectable = remember(line) { line.liveStations.map { it.code }.toSet() }
    // metro.json 的官方线路色（深浅模式都不改色值）；主线、箭头、选中站圆环共用一套。
    val tint = officialLineColor(line)
    val palette = officialPalette()
    val type = officialTypography()
    // 站间箭头与选中环的白隙是「卡片色挖空」，不是白色语义：深色下跟着卡片走。
    val cutout = palette.Cutout
    // 6 号线 #92308D 这类深线色压在深卡上只有 2.45:1，补一根 0.6dp 细描边提轮廓（不改色值）。
    val edge = needsLineEdge(tint, palette)
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
    // 站点命中区：上沿压在选中环下沿再让 6dp（车标那一行不参与点按，免得点车标误切参考站），
    // 下沿到整块底边。最矮的情况（单字站名）也有 50dp，满足 44dp 下限。
    val hitTop = TrackCenter - StripRingOuter / 2 - 6.dp
    val hitHeight = height - hitTop

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
                    if (edge) {
                        val w = StripLineEdge.toPx()
                        drawRect(
                            palette.LineEdge,
                            topLeft = Offset(0f, cy - track / 2f - w / 2f),
                            size = androidx.compose.ui.geometry.Size(size.width, track + w),
                            style = Stroke(width = w)
                        )
                    }
                    val arrowColor = cutout
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
                        if (edge) {
                            val w = StripLineEdge.toPx()
                            drawCircle(
                                palette.LineEdge,
                                StripRingOuter.toPx() / 2f + w / 2f,
                                Offset(cx, cy),
                                style = Stroke(width = w)
                            )
                        }
                        drawCircle(tint, StripRingOuter.toPx() / 2f, Offset(cx, cy))
                        drawCircle(cutout, (StripRingOuter / 2 - StripRingWidth).toPx(), Offset(cx, cy))
                        drawCircle(tint, StripRingCore.toPx() / 2f, Offset(cx, cy))
                    }
                }
                // 竖排站名：一字一行，居中于站心；选中站 Bold + 靛蓝 + 行距 65px。
                // 每个站点整列都是命中区（宽一格、从环下方到底边），点一下即切换参考站。
                cells.forEachIndexed { index, code ->
                    val name = names[code].orEmpty()
                    if (name.isBlank()) return@forEachIndexed
                    val active = index == selectedIndex
                    val enabled = code.isNotBlank() && code in selectable
                    Box(
                        Modifier.offset(x = StripCell * index, y = hitTop)
                            .width(StripCell).height(hitHeight)
                            .clip(StationHitShape)
                            .clickable(enabled = enabled, role = Role.Button) { onSelectStation(code) }
                            .clearAndSetSemantics {
                                contentDescription = name
                                selected = active
                            }
                    ) {
                        Text(
                            name.chunked(1).joinToString("\n"),
                            Modifier.offset(y = namesTop - hitTop).width(StripCell),
                            style = if (active) type.StripeNameSelected else type.StripeName,
                            textAlign = TextAlign.Center,
                            maxLines = 8
                        )
                    }
                }
                // 车标：方块图标 + 下方「第N班」；没有本站班序的（补位置的区间车）不写班号。
                // 用下标做 key：车没有稳定 ID，位置条的序列本身就是「第几班」，
                // 序列真的重排时位移跨度会超过 1.5 格，[rememberMarkerCenter] 会直接吸附。
                direction.positions.forEachIndexed { index, train ->
                    val position = direction.positionOf(train)
                    if (position < 0) return@forEachIndexed
                    key(index) {
                        val order = direction.arrivalOrderOf(train)
                        val fraction = (position - kotlin.math.floor(position)).toFloat()
                        val phase = if (fraction > 0.05f && fraction < 0.95f) StripTrainPhase else 0f
                        val centerX = rememberMarkerCenter(StripCell * (position.toFloat() + .5f - phase))
                        val label = if (order == null) "" else stringResource(R.string.metro_realtime_train_no, order)
                        Box(
                            Modifier.offset(x = centerX - MarkerBox / 2).width(MarkerBox)
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.TopCenter
                        ) {
                            TrainIcon(lead = order == 1, palette = palette)
                            if (label.isNotBlank()) {
                                Text(
                                    label,
                                    Modifier.align(Alignment.TopCenter)
                                        .offset(x = 2.dp, y = TrainLabelTop).width(40.dp),
                                    style = type.TrainLabel,
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
}

/** 车标上沿到 block 顶＝0；主线中心 47.5dp，站名 ink 顶 63.5dp（全部来自规格 3.5/3.6）。 */
private val TrackCenter = 47.5.dp

/** 「第N班」ink 顶在 block 顶下 22.3dp，行盒留 1.4dp 上内边距。 */
private val TrainLabelTop = 18.9.dp

/** 车标列宽：车标 20.7dp、「第N班」约 34.5dp，取 40dp 让标签不被父级宽度裁掉。 */
private val MarkerBox = 40.dp

/** 站点命中区的圆角（只裁水波纹，克制一点，不做花哨按压动效）。 */
private val StationHitShape = RoundedCornerShape(6.dp)

/** 车标位移过渡时长：数据更新时 400ms 滑过去，不整格硬跳。 */
private const val MarkerGlideMillis = 400

/** 一次位移超过这么多格就吸附（换方向/换站导致的整条重排，插值会从屏幕一头飞到另一头）。 */
private const val MarkerGlideMaxCells = 1.5f

/**
 * 车标横坐标：数据更新时用 [MarkerGlideMillis] 滑到新位置；跨度超过 [MarkerGlideMaxCells] 格直接吸附。
 *
 * 车标本身没有稳定 ID（`MetroTrain` 只有观测值），所以位移过渡按「同一层里同一个车标」处理：
 * 位置变了就滑过去，序列重排导致的大跨度由「吸附」兜住，不会出现横穿整条线的假位移。
 */
@Composable
private fun rememberMarkerCenter(target: Dp): Dp {
    val anim = remember { Animatable(target.value) }
    LaunchedEffect(target) {
        if (abs(target.value - anim.value) > StripCell.value * MarkerGlideMaxCells) {
            anim.snapTo(target.value)
        } else {
            anim.animateTo(target.value, tween(MarkerGlideMillis, easing = FastOutSlowInEasing))
        }
    }
    return anim.value.dp
}

/** 官方车标：62×62px 圆角方块、靛蓝渐变 + 白色列车图形，「第1班」多一个右侧方向三角。 */
@Composable
private fun TrainIcon(lead: Boolean, palette: OfficialPalette) {
    Box(Modifier.size(StripTrainSize)) {
        Box(
            Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(3.5.dp))
                .background(
                    Brush.linearGradient(
                        listOf(palette.TrainGradientStart, palette.TrainGradientEnd)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.DirectionsSubway, null,
                Modifier.size(15.5.dp),
                tint = palette.TrainGlyph.copy(alpha = .96f)
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
                drawPath(path, palette.TrainDirection)
            }
        }
    }
}

/** 位置条里单独一班车的显示位置，供测试与调试核对（站点坐标 → 像素中心）。 */
internal fun MetroDirection.stripCenterOf(train: MetroTrain): Float {
    val position = positionOf(train)
    return if (position < 0) -1f else position.toFloat()
}
