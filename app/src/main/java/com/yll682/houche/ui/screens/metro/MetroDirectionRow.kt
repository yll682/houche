package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yll682.houche.R

/**
 * 方向行（规格 2.3）：`起点 ——(开往)→ 终点` ＋右侧「换向」图标和文字。
 *
 * 官方的连接线、胶囊边框、实心三角都是 `#666`；「换向」图标 `#888`。
 * 左右缩进按**新基准图**（`official-line1-train-position.jpg`）实测：起终点文字墨迹左沿
 * 107px、右侧「换向」墨迹右沿 987px ⇒ 卡片左 24.7dp、卡片右 21dp（官方本来就不对称，照抄）。
 * 旧基准图（`official-train-position.jpg`）同一行是 78px / 986px；两图只差这一处左侧缩进，
 * 按本轮指定的新基准图取值。
 */
@Composable
internal fun MetroDirectionRow(
    origin: String,
    destination: String,
    canSwap: Boolean,
    onSwap: () -> Unit
) {
    val swapLabel = stringResource(R.string.metro_position_swap)
    val palette = officialPalette()
    val type = officialTypography()
    Row(
        Modifier.fillMaxWidth().padding(start = 24.7.dp, end = 21.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            origin,
            style = type.Direction,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Connector(palette.SubInk)
        // 「开往」胶囊：116×49px＝38.7×16.3dp，边框 4px≈1.33dp，全圆角，填充透明。
        Box(
            Modifier.size(38.7.dp, 16.3.dp)
                .border(1.33.dp, palette.SubInk, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.metro_position_open_to), style = type.Capsule, maxLines = 1)
        }
        ArrowHead(palette.SubInk)
        Spacer(Modifier.width(3.5.dp))
        Text(
            destination,
            style = type.Direction,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        // 「换向」：图标 16.3×13dp + 4dp 间距 + 文字 11.3sp，整块可点。
        Row(
            Modifier
                .then(if (canSwap) Modifier.clickable { onSwap() } else Modifier)
                .semantics { contentDescription = swapLabel }
                .heightIn(min = 24.dp)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroSwapGlyph(palette.IconInk)
            Spacer(Modifier.width(4.dp))
            Text(swapLabel, style = type.SwapLabel, maxLines = 1)
        }
    }
}

/** 起点与胶囊之间的连接线：9dp 长、1.7dp 粗。 */
@Composable
private fun Connector(color: Color) {
    Canvas(Modifier.width(9.dp).height(16.3.dp)) {
        val stroke = 1.7.dp.toPx()
        drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), stroke)
    }
}

/** 胶囊右侧：3dp 连接线 + 7×4dp 实心三角，尖朝终点。 */
@Composable
private fun ArrowHead(color: Color) {
    Canvas(Modifier.width(10.dp).height(7.dp)) {
        val stroke = 1.7.dp.toPx()
        val lineEnd = 3.dp.toPx()
        val cy = size.height / 2f
        drawLine(color, Offset(0f, cy), Offset(lineEnd, cy), stroke)
        val head = Path().apply {
            moveTo(lineEnd, 0f)
            lineTo(size.width, cy)
            lineTo(lineEnd, size.height)
            close()
        }
        drawPath(head, color)
    }
}
