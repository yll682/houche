package com.yll682.houche.ui.screens.metro

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

/** Text tabs preserve route color as a small, meaningful identifier. */
@Composable
internal fun MetroLineTabs(lines: List<MetroLine>, selected: String, select: (String) -> Unit) {
    if (lines.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        lines.forEach { line ->
            val active = line.id == selected
            val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.widthIn(min = 72.dp).selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = { select(line.id) }), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(officialLineColor(line)))
                    Text(line.name, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = tint)
                }
                Box(Modifier.width(38.dp).height(3.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (active) tint else Color.Transparent))
            }
        }
    }
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
