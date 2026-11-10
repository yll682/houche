package com.yll682.houche.ui.screens.metro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R
import com.yll682.houche.core.MetroLine
import com.yll682.houche.ui.components.HeaderIcon
import kotlin.math.cos
import kotlin.math.sin

/** Shared title bar; transit graphics retain their data colors. */
@Composable
internal fun MetroTopBar(title: String, back: () -> Unit) {
    com.yll682.houche.ui.components.HeaderSurface {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), back)
            Text(title, Modifier.padding(start = 8.dp), style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 顶部线路切换：滑动胶囊（2.0.x 样式）。容器 16dp 圆角、白底、高 48dp；选中指示块 40dp 高、
 * 圆角 14dp、四周内缩 4dp，位置用 200ms CubicBezier(0.23,1,0.32,1) 滑动。
 *
 * 与 2.0.8 的唯一区别：指示块底色与选中字色取**当前线路的官方色**，不再是 App 主题蓝。
 * 指示块 = 线路色 12% 透明度；选中字 = 线路色（浅色线路在白底上自动压深一档，保证可读）。
 */
@Composable
internal fun MetroLineTabs(lines: List<MetroLine>, selected: String, select: (String) -> Unit) {
    if (lines.isEmpty()) return
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(lines, labelStyle) {
        lines.maxOf { measurer.measure(AnnotatedString(it.name), labelStyle, maxLines = 1).size.width }
    }
    val scroll = rememberScrollState()
    val selectedIndex = lines.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val cell = maxOf(with(density) { widest.toDp() } + 32.dp, 72.dp, maxWidth / lines.size)
        val indicatorX by animateDpAsState(cell * selectedIndex + 4.dp, tween(200, easing = TabEasing), label = "标签指示器")
        val tint = officialLineColor(lines[selectedIndex])
        LaunchedEffect(selectedIndex, cell) {
            val target = with(density) { (cell * selectedIndex).roundToPx() }
            val viewport = with(density) { maxWidth.roundToPx() }
            scroll.animateScrollTo((target - (viewport - with(density) { cell.roundToPx() }) / 2).coerceAtLeast(0))
        }
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(scroll)) {
            Box(Modifier.offset(x = indicatorX, y = 4.dp).size(cell - 8.dp, 40.dp)
                .clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.12f)))
            Row {
                lines.forEach { line ->
                    val active = line.id == selected
                    val textColor by animateColorAsState(
                        if (active) readableOnSurface(officialLineColor(line)) else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(200), label = "标签颜色"
                    )
                    Box(
                        Modifier.width(cell).height(48.dp)
                            .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = { select(line.id) }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(line.name, style = labelStyle, color = textColor, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val TabEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** 线路色用作文字时：浅色线路（亮度 > 0.25）在亮色主题的白底上压深 25%，深色主题不动。 */
@Composable
private fun readableOnSurface(line: Color): Color {
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (!darkTheme && line.luminance() > 0.25f) lerp(line, Color.Black, 0.25f) else line
}

/**
 * 线路 tab 上的徽标：32dp 共享徽标（[MetroLineBadge]）。
 *
 * 字色不再按底色亮度切——官方三个徽标实测都是白字（浅绿、浅青的徽标官方也用白字），
 * 所以字色写死在共享组件里（[LineBadgeInk]），这里只负责传底色与线号。
 */
@Composable
private fun LineBadge(line: MetroLine, modifier: Modifier = Modifier) {
    val tint = officialLineColor(line)
    val digit = remember(line) { line.id.takeIf { it.isNotBlank() } ?: line.name.filter { it.isDigit() }.take(1) }
    MetroLineBadge(BadgeSize, digit, tint, modifier)
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
