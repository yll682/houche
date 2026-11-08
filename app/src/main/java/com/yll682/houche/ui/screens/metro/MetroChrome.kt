package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.R
import com.yll682.houche.core.MetroLine
import com.yll682.houche.ui.components.HeaderIcon
import kotlin.math.cos
import kotlin.math.sin

/**
 * 顶栏：候车 1.7.2 及之前的设计——品牌蓝渐变底 + 白色返回箭头 + 居中标题。
 *
 * 1.7.3 照官方换成白底 `#6157EB` 细 chevron 后用户明确要求换回来，所以这里的结构逐条复用
 * 1.7.2 的 `Page` 顶栏（`ui/components/Components.kt`）：`HeaderSurface` 那层同款蓝渐变、
 * 56dp 行高、`statusBarsPadding()`，返回键直接用当时那个 `HeaderIcon`——它就是
 * 48dp 触控区 + 24dp 白色 `Icons.AutoMirrored.Filled.ArrowBack` + `R.string.back` 的
 * contentDescription，无障碍标签和点击区都不用重写。
 *
 * 只有标题位置按本次要求居中（1.7.2 的 `Page` 是 `weight(1f)` 左对齐贴箭头），文案沿用
 * 现在的「列车位置」；渐变取色走 [OfficialPalette]，深色模式自动换成压暗一档的品牌蓝，
 * 白箭头白标题在两套模式上都是 4.5:1 以上。
 */
@Composable
internal fun MetroTopBar(title: String, back: () -> Unit) {
    val backLabel = stringResource(R.string.back)
    val palette = officialPalette()
    val type = officialTypography()
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(palette.BrandTop, palette.BrandBottom)))
            .statusBarsPadding()
    ) {
        // 1.7.2 的顶栏行：56dp、左 2dp / 右 6dp 内边距、垂直居中。
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            // 返回键＝1.7.2 的 HeaderIcon：48dp 触控区、24dp 白色 ArrowBack、无障碍标签。
            Box(Modifier.align(Alignment.CenterStart).padding(start = 2.dp, end = 6.dp)) {
                HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, backLabel, back)
            }
            Text(
                title,
                Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                style = type.BrandTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 线路切换条（规格 2.2）：白底 53dp、96×96px 圆角色块徽标、中心距 130dp、
 * 选中态用底部 120×12px 方角下划线（`#304097`）表达，而不是给徽标加底色。
 *
 * 中心距 130dp 是官方实测值；线路多于三条时按同一节奏横向滚动，能塞下就整体居中。
 */
@Composable
internal fun MetroLineTabs(lines: List<MetroLine>, selected: String, select: (String) -> Unit) {
    if (lines.isEmpty()) return
    val scroll = rememberScrollState()
    val palette = officialPalette()
    BoxWithConstraints(Modifier.fillMaxWidth().height(53.dp).background(palette.Card)) {
        // 官方三条线时中心距 130dp（列宽 40dp + 间隔 90dp）。候车内置 5 条线（1/2/3/4/6），
        // 130dp 节奏在 360/411dp 屏上放不下：能按官方节奏放下就照抄，放不下才压缩间隔保全部可见。
        val fit = if (lines.size > 1)
            (maxWidth - 40.dp * lines.size - 16.dp) / (lines.size - 1) else 90.dp
        val gap = minOf(90.dp, fit.coerceAtLeast(24.dp))
        val content = 40.dp * lines.size + gap * (lines.size - 1).coerceAtLeast(0)
        val pad = ((maxWidth - content) / 2).coerceAtLeast(0.dp)
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll).padding(start = pad, end = pad),
            verticalAlignment = Alignment.Top
        ) {
            lines.forEachIndexed { index, line ->
                if (index > 0) Spacer(Modifier.width(gap))
                LineTab(line, line.id == selected) { select(line.id) }
            }
        }
    }
}

/** 单个线路 tab：色块 + 选中下划线；整体 40dp 宽以便下划线比色块左右各宽 12px。 */
@Composable
private fun LineTab(line: MetroLine, active: Boolean, onClick: () -> Unit) {
    val palette = officialPalette()
    Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.7.dp))
        LineBadge(line, Modifier.clickable { onClick() }.semantics { contentDescription = line.name })
        Spacer(Modifier.height(4.dp))
        // 选中下划线：120×12px 方角，贴条底部（下划线底 = tab 条底）。
        Box(
            Modifier.width(40.dp).height(4.dp)
                .background(if (active) palette.TabIndicator else Color.Transparent)
        )
    }
}

// ---- 线路徽标几何：官方 96×96px 色块换算成 dp；数字与「号线」按**实测墨迹**摆放 ----
//
// 官方位置条站名下方那枚换乘徽标是**同一个徽标的缩小版**（基准图实测 58×58px 色块），
// 所以这里把整套锚点做成「相对 32dp 基准块按比例缩放」（[BadgeMetrics]）：32dp 的 tab 徽标
// 与 19.3dp 的换乘徽标共用一份测量 + 一份绘制代码，视觉语言逐像素同源。

/** 色块边长 32dp（官方 96×96px）；换乘徽标用 [StripTransferBadge]。 */
internal val BadgeSize = 32.dp

/** 32dp 基准块的圆角 12.5px≈4dp；其它尺寸按块边长等比。 */
private const val BadgeCornerBase = 4f

/** 32dp 基准块的数字字号 32sp；其它尺寸按块边长等比。 */
private const val BadgeDigitSizeBase = 32f

/** 32dp 基准块的「号线」字号 12sp；其它尺寸按块边长等比。 */
private const val BadgeSmallSizeBase = 12f

/**
 * 数字**墨迹**左缘的基准位置（现状值）。官方数字墨迹左 12px＝4dp；候车这边是「布局原点
 * 3.4dp ＋ 该字自带左侧留白带」，1/2/3/4/6 分别是 6.07 / 4.73 / 4.73 / 4.07 / 5.40dp。
 * 摆位时钉住的是墨迹左缘，所以压缩字宽不会把这个留白改小。
 */
private const val BadgeDigitInkLeftBaseBase = 3.4f

/** 「号线」竖排的墨迹右缘距色块右 8px≈2.7dp（官方实测），与现状完全一致。 */
private const val BadgeSmallInkRightInsetBase = 2.7f

/** 「号」「线」两行基线间距 41px≈13.7dp；首行基线在色块中线偏上 1.2dp（官方实测）。 */
private const val BadgeSmallStepYBase = 13.7f
private const val BadgeSmallBaselineShiftBase = 1.2f

/** 数字墨迹底距色块底 16px≈5.3dp（官方实测，与现状一致）。 */
private const val BadgeDigitInkBottomInsetBase = 5.3f

/** 数字墨迹与「号线」墨迹之间的最小间隙：两块包围盒实测必须 ≥ 它，否则继续收字宽。 */
private const val BadgeMinInkGapBase = 1f

/** 数字墨迹与「号线」墨迹之间的最小间隙（32dp 基准块），供测试引用。 */
internal val BadgeMinInkGap = 1.dp

/**
 * 一枚线路徽标的几何，全部按 32dp 基准块等比缩放。
 *
 * `scale = block / 32dp`：官方换乘徽标 58×58px（19.33dp）＝ 32dp 块的 0.604 倍，
 * 圆角 7px、数字墨迹左 2.4dp、号线墨迹右 1.6dp、行距 8.3dp 都正好是基准值 × 0.604
 * （基准图逐块量过），所以不需要第二套常量。
 */
internal class BadgeMetrics(val block: Dp) {
    /** 相对 32dp 基准块的缩放比。 */
    val scale: Float = block.value / BadgeSize.value

    /** 色块圆角。 */
    val corner: Dp = (BadgeCornerBase * scale).dp

    /** 数字字号（32dp 块＝32sp）。 */
    val digitSize: TextUnit = (BadgeDigitSizeBase * scale).sp

    /** 「号线」字号（32dp 块＝12sp）。 */
    val smallSize: TextUnit = (BadgeSmallSizeBase * scale).sp

    val digitInkLeftBase: Dp = (BadgeDigitInkLeftBaseBase * scale).dp
    val smallInkRightInset: Dp = (BadgeSmallInkRightInsetBase * scale).dp
    val smallStepY: Dp = (BadgeSmallStepYBase * scale).dp
    val smallBaselineShift: Dp = (BadgeSmallBaselineShiftBase * scale).dp
    val digitInkBottomInset: Dp = (BadgeDigitInkBottomInsetBase * scale).dp
    val minInkGap: Dp = (BadgeMinInkGapBase * scale).dp

    companion object {
        /** 32dp tab 徽标。 */
        val Standard = BadgeMetrics(BadgeSize)

        /** 取某个尺寸的几何；32dp 复用同一个实例（tab 徽标不新分配）。 */
        fun of(block: Dp): BadgeMetrics = if (block == BadgeSize) Standard else BadgeMetrics(block)
    }
}

/**
 * 数字的水平压缩上限 0.74。
 *
 * 根因：官方位图里数字墨迹宽 22/36/37px（1/2/3 号线），而 32sp 默认字体是 27/47/44px——
 * 墨迹**高度**一样（69~71px），宽度多 20%~30%。照抄「数字墨迹左 4dp、号线墨迹右 2.7dp」
 * 两处锚点后，中间只剩 13dp 出头，放不下 15.7dp 的「2」，于是 1.7.3 版式里数字直接压到
 * 「号线」上（1 号线的「1」最窄，所以只有它看着正常）。
 *
 * 这里只收水平轴（`TextGeometricTransform`），字号、行高、墨迹高度都不改，也就是**没有**
 * 把字缩小；收完数字墨迹宽约 7~12.5dp，回到官方位图的比例。实际比例由 [badgeInkOf] 按
 * 量到的墨迹算，这个常量只是上限。
 */
private const val BadgeDigitMaxScaleX = .74f

/** 压缩下限，保证异常数据（例如两位线路号）也不会被压成一条线。 */
private const val BadgeDigitMinScaleX = .45f

/** 一个墨迹盒，单位 px，原点在色块左上角。 */
internal class InkBox(val left: Float, val right: Float, val top: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 徽标内两块的实测排版结果（单位 px，原点色块左上角）。
 *
 * [digitPenX] / [smallPenX] 是两块文字的绘制原点 x，[digitBox] / [smallBox] 是量出来的墨迹盒。
 */
internal class BadgeInk(
    val digitPenX: Float,
    val digitScaleX: Float,
    val digitBox: InkBox,
    val smallPenX: Float,
    val smallBox: InkBox
) {
    /** 数字墨迹右缘到「号线」墨迹左缘的间隙（px）。 */
    val gap: Float get() = smallBox.left - digitBox.right
}

/**
 * 按真实墨迹算徽标排版（纯几何 + `Paint.getTextBounds`，不画东西）。
 *
 * 步骤：
 * 1. 量数字墨迹（`scaleX = 1`）与「号」「线」墨迹，得到「号线」竖排的墨迹左缘；
 * 2. 数字墨迹左缘钉在现状位置、竖排墨迹右缘钉在现状位置，算出中间还能放多宽的数字；
 * 3. 需要的宽度超了就按比例收数字的水平轴（最多收到 [BadgeDigitMaxScaleX]），
 *    再重量一次墨迹，得到实际间隙——这个间隙一定 ≥ [BadgeMinInkGap]。
 *
 * 纵向沿用官方实测锚点（数字墨迹底 5.3dp、竖排首行基线 中线-1.2dp、行距 13.7dp），
 * 所以墨迹盒的 top/bottom 也一并给出，供测试和文档引用。
 */
internal fun badgeInkOf(
    digit: String,
    density: Density,
    metrics: BadgeMetrics = BadgeMetrics.Standard
): BadgeInk {
 	fun bounds(text: String, sizePx: Float, scaleX: Float): android.graphics.Rect {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.textSize = sizePx
        paint.textScaleX = scaleX
        val rect = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, rect)
        return rect
    }

    fun advance(text: String, sizePx: Float): Float {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.textSize = sizePx
        return paint.measureText(text)
    }

    val blockPx = with(density) { metrics.block.toPx() }
    val digitSizePx = with(density) { metrics.digitSize.toPx() }
    val smallSizePx = with(density) { metrics.smallSize.toPx() }
    val digitInkLeft = with(density) { metrics.digitInkLeftBase.toPx() } + bounds(digit, digitSizePx, 1f).left
    val minGap = with(density) { metrics.minInkGap.toPx() }

    // 「号线」：一个原点画两行，墨迹右缘贴着官方 2.7dp 内边距。
    val smallPenX = blockPx - with(density) { metrics.smallInkRightInset.toPx() } - advance("号", smallSizePx)
    val upper = bounds("号", smallSizePx, 1f)
    val lower = bounds("线", smallSizePx, 1f)
    val smallInkLeft = smallPenX + minOf(upper.left, lower.left)
    val smallInkRight = smallPenX + maxOf(upper.right, lower.right)

    // 数字能占多宽：从墨迹左缘到「号线」墨迹左缘，中间留够最小间隙。
    val raw = bounds(digit, digitSizePx, 1f)
    val allowed = (smallInkLeft - minGap - digitInkLeft).coerceAtLeast(0f)
    val fitScaleX = if (raw.width() <= 0f) 1f else allowed / raw.width()
    var scaleX = minOf(BadgeDigitMaxScaleX, fitScaleX).coerceIn(BadgeDigitMinScaleX, 1f)
    var scaled = bounds(digit, digitSizePx, scaleX)
    // 字宽是按整像素量的，线性估算会有半像素误差；这里按实测量到的间隙再收一次，
    // 直到「数字墨迹右缘 + 1dp ≤ 号线墨迹左缘」真的成立（正常数据一轮就收敛）。
    repeat(4) {
        val gapNow = smallInkLeft - (digitInkLeft - scaled.left + scaled.right)
        if (gapNow >= minGap || scaled.width() <= 0f || scaleX <= BadgeDigitMinScaleX) return@repeat
        val needed = (scaled.width() - (minGap - gapNow)).coerceAtLeast(1f)
        scaleX = (scaleX * needed / scaled.width()).coerceAtLeast(BadgeDigitMinScaleX)
        scaled = bounds(digit, digitSizePx, scaleX)
    }
    val digitPenX = digitInkLeft - scaled.left
    // 纵向：数字墨迹底在 32-5.3dp；竖排首行基线在中线-1.2dp、第二行再下 13.7dp。
    val digitBaseline = blockPx - with(density) { metrics.digitInkBottomInset.toPx() }
    val smallBaseline = blockPx / 2f - with(density) { metrics.smallBaselineShift.toPx() }
    return BadgeInk(
        digitPenX = digitPenX,
        digitScaleX = scaleX,
        digitBox = InkBox(
            left = digitPenX + scaled.left,
            right = digitPenX + scaled.right,
            top = digitBaseline + scaled.top,
            bottom = digitBaseline + scaled.bottom
        ),
        smallPenX = smallPenX,
        smallBox = InkBox(
            left = smallInkLeft,
            right = smallInkRight,
            top = smallBaseline + upper.top,
            bottom = smallBaseline + with(density) { metrics.smallStepY.toPx() } + lower.bottom
        )
    )
}

/** 线路 tab 上的徽标：32dp 色块 + 本线号。 */
@Composable
private fun LineBadge(line: MetroLine, modifier: Modifier = Modifier) {
    val tint = officialLineColor(line)
    // 官方色里 1/2/3 号线（#DA7028 / #52A441 / #52B5C8）偏亮，数字和「号线」走深墨；
    // 4/6 号线（#E50012 / #92308D）仍走白字。判据是亮度阈值，见 onLineColor()。
    val digit = remember(line) { line.id.takeIf { it.isNotBlank() } ?: line.name.filter { it.isDigit() }.take(1) }
    MetroLineBadge(BadgeSize, digit, tint, onLineColor(tint), modifier)
}

/**
 * 线路徽标：左侧大数字、右侧竖排「号线」，色块圆角矩形、墨迹按 [badgeInkOf] 实测摆放。
 *
 * 官方是位图徽标，所以这里不按固定偏移硬摆，而是按量出来的墨迹摆：数字墨迹左缘、
 * 竖排墨迹右缘各自钉在原位，中间保证 ≥1dp 间隙（见 [BadgeMinInkGap]），数字一宽就压到
 * 「号线」上的老问题就是这么消掉的。
 *
 * 尺寸由 [block] 决定，所有锚点按 [BadgeMetrics] 等比缩放：tab 徽标是 32dp 基准，
 * 位置条站名下方的换乘徽标用 [StripTransferBadge]（19.33dp＝官方 58×58px），
 * 两者是**同一个徽标的两档尺寸**，不是两套画法。
 */
@Composable
internal fun MetroLineBadge(
    block: Dp,
    digit: String,
    tint: Color,
    ink: Color,
    modifier: Modifier = Modifier
) {
    val metrics = remember(block) { BadgeMetrics.of(block) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(digit, metrics, density) { badgeInkOf(digit, density, metrics) }
    val bigStyle = TextStyle(
        fontSize = metrics.digitSize,
        lineHeight = metrics.digitSize * 1.0625f,
        color = ink,
        // 只收水平轴：字号/行高不变，墨迹高度还是基准比例。
        textGeometricTransform = TextGeometricTransform(scaleX = layout.digitScaleX)
    )
    val smallStyle = TextStyle(
        fontSize = metrics.smallSize,
        lineHeight = metrics.smallSize * 1.1667f,
        color = ink
    )
    val digits = remember(measurer, digit, bigStyle) { measurer.measure(AnnotatedString(digit), style = bigStyle) }
    val upper = remember(measurer, smallStyle) { measurer.measure(AnnotatedString("号"), style = smallStyle) }
    val lower = remember(measurer, smallStyle) { measurer.measure(AnnotatedString("线"), style = smallStyle) }
    Canvas(modifier.size(block)) {
        drawRoundRect(tint, cornerRadius = CornerRadius(metrics.corner.toPx()))
        // 数字：绘制原点由墨迹盒反算，墨迹底落在 块高-5.3dp（等比）。
        drawText(
            digits,
            topLeft = Offset(layout.digitPenX, size.height - metrics.digitInkBottomInset.toPx() - digits.getLineBaseline(0))
        )
        // 「号」「线」竖排：一个原点两行，墨迹右缘距色块右 2.7dp（等比），行距 13.7dp（等比）。
        val upperBaseline = size.height / 2f - metrics.smallBaselineShift.toPx()
        drawText(upper, topLeft = Offset(layout.smallPenX, upperBaseline - upper.getLineBaseline(0)))
        drawText(lower, topLeft = Offset(layout.smallPenX, upperBaseline + metrics.smallStepY.toPx() - lower.getLineBaseline(0)))
    }
}

/** 「换向」图标（规格 2.3）：上箭头朝右、下箭头朝左，两条 1.3dp 细线，ink 16.3×13dp。 */
@Composable
internal fun MetroSwapGlyph(color: Color? = null, modifier: Modifier = Modifier) {
    val ink = color ?: officialPalette().IconInk
    Canvas(modifier.size(16.3.dp, 13.dp)) {
        val stroke = 1.3.dp.toPx()
        val head = 3.2.dp.toPx()
        val topY = 3.2.dp.toPx()
        val bottomY = size.height - 3.2.dp.toPx()
        // 上：横线朝右，箭头在右端
        drawLine(ink, Offset(0f, topY), Offset(size.width, topY), stroke, StrokeCap.Butt)
        drawLine(ink, Offset(size.width - head, topY - head), Offset(size.width, topY), stroke, StrokeCap.Butt)
        drawLine(ink, Offset(size.width, topY), Offset(size.width - head, topY + head), stroke, StrokeCap.Butt)
        // 下：横线朝左，箭头在左端
        drawLine(ink, Offset(0f, bottomY), Offset(size.width, bottomY), stroke, StrokeCap.Butt)
        drawLine(ink, Offset(head, bottomY - head), Offset(0f, bottomY), stroke, StrokeCap.Butt)
        drawLine(ink, Offset(0f, bottomY), Offset(head, bottomY + head), stroke, StrokeCap.Butt)
    }
}

/**
 * 刷新图标（规格 2.4）：开口圆环 + 顺时针三角箭头，线宽 4px≈1.3dp，ink 40×40px＝13.3dp。
 *
 * 这不是 Material 的 `Icons.Filled.Refresh`，一直是自绘的 Canvas（`drawArc` + 三角 Path）；
 * 旧版「看不到箭头」的原因是三角太小且贴着圆弧画：底宽 4.8dp、长仅 2dp，还落在半径 6dp 上，
 * 缩放后就是圆弧末端一撮毛刺。现在：
 *  - 圆环半径收到 4.7dp，把画布边角让给箭头（Canvas 会裁，箭头不能出界）；
 *  - 三角做成实心，底宽 3.7dp（≈3× 线宽）、长 3.0dp，底边垂直压在圆弧末端、尖端朝顺时针切向；
 *  - 缺口 65°（270°→335°，右上象限），箭头就在缺口起点，缺口方向＝旋转方向，一眼读得出顺时针。
 */
@Composable
internal fun MetroRefreshGlyph(
    color: Color? = null,
    angle: Float = 0f,
    modifier: Modifier = Modifier
) {
    val ink = color ?: officialPalette().SubInk
    Canvas(modifier.size(13.3.dp)) {
        rotate(angle) {
            val stroke = 1.3.dp.toPx()
            val radius = 4.7.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            // 从 335°（＝-25°，右上）顺时针扫 295° 到 270°（正上方），缺口落在 270°~335°。
            drawArc(
                color = ink,
                startAngle = -25f,
                sweepAngle = 295f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            // 箭头压在圆弧末端（正上方），沿顺时针切线朝右——方向和缺口一致。
            val endRad = Math.toRadians(270.0)
            val end = Offset(
                center.x + (radius * cos(endRad)).toFloat(),
                center.y + (radius * sin(endRad)).toFloat()
            )
            val dir = Offset(-sin(endRad).toFloat(), cos(endRad).toFloat())
            val normal = Offset(-dir.y, dir.x)
            val headLen = 3.dp.toPx()
            val headHalf = 1.85.dp.toPx()
            val path = Path().apply {
                moveTo(end.x + dir.x * headLen, end.y + dir.y * headLen)
                lineTo(end.x + normal.x * headHalf, end.y + normal.y * headHalf)
                lineTo(end.x - normal.x * headHalf, end.y - normal.y * headHalf)
                close()
            }
            drawPath(path, ink)
        }
    }
}

/** 站名右侧的线路号圆徽标（Ø40px＝13.3dp，线路色底、字色按亮度自动切白/深墨）。 */
@Composable
internal fun MetroStationBadge(text: String, tint: Color, modifier: Modifier = Modifier) {
    val type = officialTypography()
    Box(
        modifier.size(13.3.dp).background(tint, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = type.StationBadge.copy(color = onLineColor(tint)), maxLines = 1)
    }
}
