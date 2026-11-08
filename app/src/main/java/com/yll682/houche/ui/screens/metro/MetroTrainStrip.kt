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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroDirection
import com.yll682.houche.core.MetroLine
import com.yll682.houche.core.MetroTrain
import com.yll682.houche.core.positionOf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 列车位置条（规格 2.6 / 第三节几何模型）。
 *
 * 格宽 W 由 [rememberStripCell] **按整条线量一次**（站名竖排 + 普通/Bold 两种样式的最大宽度），
 * 与方向、参考站、viewport、位置批次都无关：站心 `x = (i + 0.5)·W`，
 * 车 `x = (p + 0.5)·W`（p 来自 core 的 `positionOf`，整数表示站点、i−0.5 表示前一段），
 * 视口锚点＝选中站落在视口 78%（官方实测 78.4%）。
 *
 * 车标按 `positionPresentations()` 逐条画，班号由 [stationOrdinals] 投影成**到本站先后**：
 * 第 1 班 = 最先到本站那班，已过本站的车不给班号但**照画车标**、标签写「已过」
 * （标签绝对不能消失）。`stackIndex`（core 给的，仍按全线进度序）决定垂直 lane；
 * 消除碰撞只靠错层，**不改 p、不横向挪站**；lane 高度见 [rememberStripLaneHeight]。
 *
 * 与旧版的差别（全部是「照抄」要求）：主线 7dp 实色方角、普通站点没有圆点只有白色箭头、
 * 选中站是三同心圆环 Ø20.7/Ø16.7/Ø8.7、车标 20.7dp 方块且「第N班」在图标下方。
 */
@Composable
internal fun MetroTrainStrip(
    stationCode: String,
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
    val selectedIndex = cells.indexOf(stationCode)

    // 整线测一次的格宽：切站/换向/滚动都不重测（key 里没有它们）。
    val cell = rememberStripCell(line)
    // 位置批次里的全部记录（含已过本站的、位置未知的），错层 lane 仍按 core 的 stackIndex 算；
    // 阈值用默认 1.0 站（保守覆盖同格与半格），只影响 lane，不删记录。
    val presentations = remember(direction) { direction.positionPresentations() }
    // 「到本站先后」投影：第 1 班 = 最先到本站那班；已过本站的车标保留、标签写「已过」。
    val ordinals = remember(direction, stationCode) { direction.stationOrdinals(stationCode) }
    val lanes = presentations.maxOfOrNull { maxOf(it.stackSize, it.stackIndex + 1) }?.coerceAtLeast(1) ?: 1
    // 标签样张取「最大班号」：字号与「已过」相同，行高只跟字号走，不因班号位数变化。
    val laneHeight = rememberStripLaneHeight(
        type.TrainLabel,
        stringResource(R.string.metro_realtime_train_no, ordinals.maxOrdinal.coerceAtLeast(1))
    )
    // 上层 lane 预留的空间：轨道、站名、命中列整体同步下移，最上层不会被裁。
    val stackRoom = laneHeight * (lanes - 1)

    // 站名块高度按本方向最长的站名算（选中站行距比普通站大 4px，要分开算），
    // 滚动时卡片高度不会跟着名字长短跳。
    val nameInkInset = 3.9.dp
    val namesTop = TrackCenter + StripNameGap - nameInkInset
    val namesHeight = cells.maxOfOrNull { code ->
        val chars = (names[code]?.length ?: 0).coerceIn(1, 8)
        val lineHeight = if (code == stationCode) StripNameLineSelected else StripNameLine
        lineHeight * chars
    } ?: StripNameLine
    val height = stackRoom + namesTop + namesHeight + 1.5.dp
    // 站点命中区：上沿压在选中环下沿再让 6dp（车标那一行不参与点按，免得点车标误切参考站），
    // 下沿到整块底边。最矮的情况（单字站名）也有 50dp，满足 44dp 下限。
    val hitTop = stackRoom + TrackCenter - StripRingOuter / 2 - 6.dp
    val hitHeight = height - hitTop

    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val viewport = maxWidth
        val contentWidth = cell * cells.size
        // 锚点只跟站序/参考站/视口/格宽走；列车轮询变化刻意不进 key，免得抢用户滚动。
        LaunchedEffect(cells, stationCode, viewport, contentWidth) {
            val anchorDp = when {
                selectedIndex >= 0 -> cell * (selectedIndex + .5f)
                else -> presentations.map { it.stationPosition }.filter { it >= 0 }
                    .maxOrNull()?.let { cell * (it.toFloat() + .5f) }
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
                Canvas(
                    Modifier.width(contentWidth).height(StripRingOuter)
                        .offset(y = stackRoom + TrackCenter - StripRingOuter / 2)
                ) {
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
                        val x = cell.toPx() * (index + .5f)
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
                        val cx = cell.toPx() * (selectedIndex + .5f)
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
                        Modifier.offset(x = cell * index, y = hitTop)
                            .width(cell).height(hitHeight)
                            .clip(StationHitShape)
                            .clickable(enabled = enabled, role = Role.Button) { onSelectStation(code) }
                            .clearAndSetSemantics {
                                contentDescription = name
                                selected = active
                            }
                    ) {
                        Text(
                            name.chunked(1).joinToString("\n"),
                            Modifier.offset(y = stackRoom + namesTop - hitTop).width(cell),
                            style = if (active) type.StripeNameSelected else type.StripeName,
                            textAlign = TextAlign.Center,
                            maxLines = 8
                        )
                    }
                }
                // 车标：方块图标 + 下方标签。未到站写「第N班」（编号按**到本站先后**，第 4、5 班有就画）；
                // 已过本站的车**照样画车标**，标签用同样字号写「已过」、颜色用次要文字色（不用错误红）——
                // 用户最烦「车标/标签莫名其妙消失」，所以这里绝不因为没班号跳过。
                // 垂直方向按 stackIndex 分 lane：lane 0 贴主线，往上依次是 lane 1、2…，
                // 同一 cluster 的车不会叠在一起；只错层，不改 p、不横向挪站。
                // key 用 core 的全线进度序（同一批观测内唯一，只做动画身份、不显示）：序列重排时位移
                // 跨度会超过 1.5 格，[rememberMarkerCenter] 会直接吸附。
                ordinals.trains.forEach { train ->
                    val presentation = train.presentation
                    key(train.markerKey) {
                        val fraction = (train.stationPosition - floor(train.stationPosition)).toFloat()
                        val phase = if (fraction > 0.05f && fraction < 0.95f) StripTrainPhase else 0f
                        val centerX = rememberMarkerCenter(
                            cell * (train.stationPosition.toFloat() + .5f - phase), cell
                        )
                        val ordinal = train.ordinal
                        val label = if (ordinal != null) {
                            stringResource(R.string.metro_realtime_train_no, ordinal)
                        } else {
                            stringResource(R.string.metro_realtime_train_passed)
                        }
                        Box(
                            Modifier.offset(
                                x = centerX - StripMarkerBox / 2,
                                y = stackRoom - laneHeight * presentation.stackIndex
                            ).width(StripMarkerBox)
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.TopCenter
                        ) {
                            TrainIcon(lead = ordinal == 1, palette = palette)
                            Text(
                                label,
                                Modifier.align(Alignment.TopCenter)
                                    .offset(x = 2.dp, y = TrainLabelTop).width(StripMarkerBox),
                                style = if (ordinal != null) type.TrainLabel else type.TrainLabelPassed,
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

/**
 * 错层 lane 的高度：`L = max(44dp, ceil(max(图标高, 标签顶+实测标签高) + 4dp))`。
 *
 * 标签高度必须实测（字号随 fontScale 放大后要重算），不能用「行高×1」估。
 * 一层 lane 的可见内容始终是「图标 20.7dp + 下方标签」，44dp 保证 lane 之间不挤。
 */
@Composable
private fun rememberStripLaneHeight(labelStyle: TextStyle, sample: String): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, labelStyle, sample, density) {
        val labelPx = measurer.measure(AnnotatedString(sample), style = labelStyle, maxLines = 1).size.height
        with(density) {
            val band = maxOf(StripTrainSize.roundToPx(), TrainLabelTop.roundToPx() + labelPx)
            maxOf(StripLaneMin, ceil((band + StripLaneGap.roundToPx()).toFloat()).toDp())
        }
    }
}

/** 车标上沿到 lane 顶＝0；主线中心 47.5dp，站名 ink 顶 63.5dp（全部来自规格 3.5/3.6）。 */
private val TrackCenter = 47.5.dp

/** 「第N班」ink 顶在 lane 顶下 22.3dp，行盒留 1.4dp 上内边距。 */
private val TrainLabelTop = 18.9.dp

/** 站点命中区的圆角（只裁水波纹，克制一点，不做花哨按压动效）。 */
private val StationHitShape = RoundedCornerShape(6.dp)

/** 车标位移过渡时长：数据更新时 400ms 滑过去，不整格硬跳。 */
private const val MarkerGlideMillis = 400

/** 一次位移超过这么多格就吸附（换方向/换站导致的整条重排，插值会从屏幕一头飞到另一头）。 */
private const val MarkerGlideMaxCells = 1.5f

/**
 * 车标横坐标：数据更新时用 [MarkerGlideMillis] 滑到新位置；跨度超过 [MarkerGlideMaxCells] 格直接吸附。
 *
 * 车标本身没有稳定 ID（`MetroTrain` 只有观测值），所以位移过渡按「同一条 presentation」处理：
 * 位置变了就滑过去，序列重排导致的大跨度由「吸附」兜住，不会出现横穿整条线的假位移。
 */
@Composable
private fun rememberMarkerCenter(target: Dp, cell: Dp): Dp {
    val anim = remember { Animatable(target.value) }
    LaunchedEffect(target) {
        if (abs(target.value - anim.value) > cell.value * MarkerGlideMaxCells) {
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
