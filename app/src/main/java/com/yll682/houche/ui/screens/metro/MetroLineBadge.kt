package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **全页唯一的线路徽标**（2026-11-08 统一）：圆角方块 + 大数字 + 竖排「号线」。
 *
 * 四个使用点都走这一个组件、这一套几何：
 * 1. 顶栏线路 tab（[BadgeSize] 32dp）；
 * 2. 卡片站名行里的「本站线路号」（[SmallBadgeSize] 19.3dp）；
 * 3. 站序列表里的「换乘」徽标（[SmallBadgeSize] 19.3dp）；
 * 4. 位置条站名下方那排换乘标注（[SmallBadgeSize] 19.3dp）。
 *
 * 2/3 会在同一行里**并排**出现，所以它们必须同级同尺寸——这也是把两处旧组件
 * （13.3dp 圆形线路号 + 胶囊换乘）并到这里的唯一理由。
 *
 * ## 字色：一律白
 *
 * 官方三个徽标实测（`notes/refs/official-line1-train-position.jpg`，像素统计）字色都是
 * (240,240,240)＝白，**连浅绿、浅青的徽标官方也用白字**。所以这里把字色写死成
 * [LineBadgeInk]（白），不再按底色亮度在白/深墨之间切——底色保持 `metro.json` 的
 * 官方线色（1 #DA7028 / 2 #52A441 / 3 #52B5C8 / 4 #E50012 / 6 #92308D），深浅模式都不改。
 *
 * 亮度阈值那套逻辑（[onLineColor]）只留给**非徽标**场景（例如浅色线路色上写普通文字），
 * 徽标内部不再使用。
 */

/** tab 上的线路徽标边长：官方 96×96px＝32dp（已对齐，见 `notes/ui-badge-unify-2026-11-08.md`）。 */
internal val BadgeSize = 32.dp

/**
 * 小徽标边长：官方位置条换乘徽标 58×58px＝19.33dp（任务书取 19.3dp）。
 *
 * 列表里并排的「本站线路号」与「换乘」以及位置条换乘标注**都用这一档**，
 * 保证并排时是同一套规格、同一尺寸。
 */
internal val SmallBadgeSize = 19.3.dp

/** 徽标里的字色：一律白（官方三个徽标实测都是白字，浅色底也不例外）。 */
internal val LineBadgeInk = Color.White

/** 32dp 档圆角：官方 12.5px≈4dp。 */
private val BadgeCornerTab = 4.dp

/** 19.3dp 档圆角：官方 7px＝2.3dp。 */
private val BadgeCornerSmall = 2.3.dp

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
 * `scale = block / 32dp`：官方换乘徽标 58×58px（19.3dp）＝ 32dp 块的 0.604 倍，
 * 数字墨迹左 2.4dp、号线墨迹右 1.6dp、行距 8.3dp 都正好是基准值 × 0.604
 * （基准图逐块量过），所以不需要第二套常量。
 *
 * 圆角例外：官方 32dp 档圆角 12.5px＝4dp、19.3dp 档圆角 7px＝2.3dp，并不严格等比，
 * 所以这两档直接取实测值（档内再按边长等比）。
 */
internal class BadgeMetrics(val block: Dp) {
    /** 相对 32dp 基准块的缩放比。 */
    val scale: Float = block.value / BadgeSize.value

    /** 色块圆角（两档实测值）。 */
    val corner: Dp = if (block == BadgeSize) BadgeCornerTab
    else BadgeCornerSmall * (block.value / SmallBadgeSize.value)

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

/**
 * 线路徽标：左侧大数字、右侧竖排「号线」，色块圆角矩形、墨迹按 [badgeInkOf] 实测摆放。
 *
 * 官方是位图徽标，所以这里不按固定偏移硬摆，而是按量出来的墨迹摆：数字墨迹左缘、
 * 竖排墨迹右缘各自钉在原位，中间保证 ≥1dp 间隙（见 [BadgeMinInkGap]），数字一宽就压到
 * 「号线」上的老问题就是这么消掉的。
 *
 * 尺寸由 [block] 决定，所有锚点按 [BadgeMetrics] 等比缩放：tab 徽标是 32dp 基准，
 * 列表里的线路号徽标 / 换乘徽标与位置条换乘标注都是 19.3dp 那一档——
 * **同一个徽标的两档尺寸，不是两套画法**。
 *
 * 字色固定为 [LineBadgeInk]（白）：官方徽标实测就是白字，浅色底也不例外，
 * 组件本身不给「换深墨」留口子，避免以后又长出两套字色。
 */
@Composable
internal fun MetroLineBadge(
    block: Dp,
    digit: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val metrics = remember(block) { BadgeMetrics.of(block) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(digit, metrics, density) { badgeInkOf(digit, density, metrics) }
    val bigStyle = TextStyle(
        fontSize = metrics.digitSize,
        lineHeight = metrics.digitSize * 1.0625f,
        color = LineBadgeInk,
        // 只收水平轴：字号/行高不变，墨迹高度还是基准比例。
        textGeometricTransform = TextGeometricTransform(scaleX = layout.digitScaleX)
    )
    val smallStyle = TextStyle(
        fontSize = metrics.smallSize,
        lineHeight = metrics.smallSize * 1.1667f,
        color = LineBadgeInk
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