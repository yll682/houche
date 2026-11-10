package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 线路徽标：4dp 圆角方块 + 单个线路号，底色 = [tint]（线路自己的官方色），字色一律白。
 *
 * 与 2.0.x 一致：方块里只有数字（没有竖排「号线」），字号 = 边长 × 0.65（sp），SemiBold，单行。
 * 底色必须由调用方传入线路色（`officialLineColor(line)`），**不要**在这里取主题色——
 * 2.0.4 的补丁恰好就是把这里的底色改成了主题蓝，导致每条线长得一样。
 */
@Composable
internal fun MetroLineBadge(
    block: Dp,
    digit: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.size(block).clip(RoundedCornerShape(LineBadgeCorner)).background(tint),
        contentAlignment = Alignment.Center
    ) {
        Text(
            digit,
            style = TextStyle(
                color = LineBadgeInk,
                fontSize = (block.value * 0.65f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
            ),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

/** 徽标边长（顶栏旧 tab 用的 32dp 档）。 */
internal val BadgeSize = 32.dp

/** 小徽标边长：站名行、换乘徽标、位置条换乘标注都用这一档（19.3dp）。 */
internal val SmallBadgeSize = 19.3.dp

/** 徽标圆角。 */
internal val LineBadgeCorner = 4.dp

/** 徽标里的字色：一律白（用户 2026-11-11 确认，与 2.0.8 一致）。 */
internal val LineBadgeInk = Color.White
