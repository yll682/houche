package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
