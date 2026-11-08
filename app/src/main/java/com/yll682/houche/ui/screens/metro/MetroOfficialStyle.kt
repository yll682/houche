package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.core.MetroLine

/**
 * 官方「列车位置」页的配色。数值全部来自 `notes/spec-official-train-position.md`
 * （官方截图 1080×2340、density 3.0 的逐像素实测）。
 *
 * 只服务 `ui/screens/metro/` 里的展示代码，不放 theme 全局令牌——公交页、首页的
 * `Color.kt` / `Type.kt` / `Shape.kt` 一个字节都不动。
 */
internal object Official {
    // 页面 / 卡片 / 文字
    val Page = Color(0xFFF1F1F1)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF333333)
    val SubInk = Color(0xFF666666)
    val IconInk = Color(0xFF888888)
    val Hairline = Color(0xFFE5E5E5)

    // 品牌靛蓝：官方只把它用在「ETA 块 / 选中下划线 / 选中站名 / 车标 / 第N班」，
    // 和线路色（徽标、主线、圆环、站号）是两套角色，不混用。
    val Indigo = Color(0xFF304097)
    val IndigoNumber = Color(0xFF3E2688)
    val IndigoLabel = Color(0xFF3B2A78)
    val IndigoChevron = Color(0xFF6157EB)
    val IndigoStation = Color(0xFF314093)
    val IndigoTrainLabel = Color(0xFF3E2686)

    val EtaBackground = Color(0xFFECF2FF)
    val Disclaimer = Color(0xFFC88C8C)

    // 车标：官方是带渐变和内阴影的位图资源，抠不到，按实测两端色自绘。
    val TrainGradientStart = Color(0xFF383095)
    val TrainGradientEnd = Color(0xFF3648AE)
    /** 最前那班车右侧的方向三角（半透明靛蓝）。 */
    val TrainDirectionMark = Color(0xFFA7A9D8)
}

/**
 * 「官方 App 实测」线路色：`metro.json` 给的是线网图的印刷色值，和官方 App 界面上的
 * 实际取色差得很明显（2 号线 json `#52A441`、App 实测 `#4BB134`）。
 *
 * 只喂本页展示，`metro.json` 和全局 `Color.kt` 一个字节都不动；没实测过的线路
 * （4 / 6 号线）走 `metroColor` 回退到 json 色。
 */
private val OfficialLineMeasured = mapOf(
    "1" to Color(0xFFF4A966),
    "2" to Color(0xFF4BB134),
    "3" to Color(0xFF95D9E4)
)

/** 本页取线路色：命中实测表用实测色，否则回退 `metro.json` 里的线路色。 */
@Composable
internal fun officialLineColor(line: MetroLine): Color =
    OfficialLineMeasured[line.id] ?: metroColor(line.color)

/**
 * 线路色底上的文字 / 图标色：按 WCAG 相对亮度切，`luminance() ≥ 0.6` 用深墨 `#333`，
 * 否则白字。阈值 0.6 是按实测色定的——1 号线 `#F4A966`（0.49）、2 号线 `#4BB134`（0.34）
 * 仍走白字，3 号线 `#95D9E4`（0.62）转深墨；官方对浅色线路也是这套做法，
 * 白字压在 `#95D9E4` 上基本看不见。
 */
internal fun onLineColor(tint: Color): Color =
    if (tint.luminance() >= 0.6f) Official.Ink else Color.White

/** 官方卡片 10dp 圆角、无描边无阴影；本页专用，不替换全局 `CardShape`。 */
internal val OfficialCardShape = RoundedCornerShape(10.dp)

/** 线路徽标色块 32dp、圆角约 12.5px≈4dp。 */
internal val OfficialBadgeShape = RoundedCornerShape(4.dp)

/** ETA 蓝块圆角 13px≈4.3dp。 */
internal val OfficialEtaShape = RoundedCornerShape(4.dp)

/**
 * 行高按实测给，且不裁剪首末行——否则「主线中心到站名首字 ink 顶 48px」这类
 * 纵向锚点会被 Compose 的默认行高策略悄悄挪掉几个 dp。
 */
private val officialLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/** 官方全页 Regular（只有选中站名 Bold），这里显式压掉主题里的 Medium/SemiBold。 */
private fun official(
    size: TextUnit,
    color: Color,
    lineHeight: TextUnit = size * 1.4f,
    weight: FontWeight = FontWeight.Normal,
    spaced: Boolean = false
) = TextStyle(
    color = color,
    fontSize = size,
    lineHeight = lineHeight,
    fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = officialLineHeightStyle,
    letterSpacing = if (spaced) 0.2.sp else 0.sp
)

/** 本页专用字号档：19 / 15.8 / 14.3 / 13.3 / 11.5 / 11.3 sp（官方实测 px÷3）。 */
internal object OfficialType {
    /** 顶栏标题 19sp。 */
    val Title = official(19.sp, Official.Ink, 23.sp)

    /** 方向行起终点 15.8sp。 */
    val Direction = official(15.8.sp, Official.Ink, 22.sp)

    /** 「开往」胶囊里的字 11.5sp。 */
    val Capsule = official(11.5.sp, Official.SubInk, 15.sp)

    /** 「换向」文字 11.3sp。 */
    val SwapLabel = official(11.3.sp, Official.SubInk, 15.sp)

    /** 站名 14.3sp。 */
    val StationName = official(14.3.sp, Official.Ink, 20.sp)

    /** 站名右侧线路号徽标里的白字（约 12.5sp，Bold）。 */
    val StationBadge = official(12.5.sp, Color.White, 15.sp, FontWeight.Bold)

    /** 「刷新」文字 11.3sp。 */
    val RefreshLabel = official(11.3.sp, Official.SubInk, 15.sp)

    /** ETA 大数字 20sp，Regular 不加粗；行高取 20sp 让下方的「第N班预计到达」贴住实测位置。 */
    val EtaNumber = official(20.sp, Official.IndigoNumber, 16.sp)

    /** ETA「分钟」，与数字同基线。 */
    val EtaUnit = official(13.7.sp, Official.IndigoNumber, 16.sp)

    /** ETA「第N班预计到达」11.5sp。 */
    val EtaLabel = official(11.5.sp, Official.IndigoLabel, 15.sp)

    /** 竖排站名 14.3sp / 行距 61px＝20.3dp。 */
    val StripeName = official(14.3.sp, Official.SubInk, 20.3.sp)

    /** 选中站名：Bold + 靛蓝 + 行距 65px≈21.7dp。 */
    val StripeNameSelected = official(14.3.sp, Official.IndigoStation, 21.7.sp, FontWeight.Bold)

    /** 车标下方「第N班」11.5sp。 */
    val TrainLabel = official(11.5.sp, Official.IndigoTrainLabel, 15.sp)

    /** 免责说明 13.3sp / 行高 20.3dp，珊瑚红居中。 */
    val Disclaimer = official(13.3.sp, Official.Disclaimer, 20.3.sp)

    /** 站序列表的小标题（官方这页没有这段，沿用原有信息层级、颜色固定浅色）。 */
    val SectionLabel = official(12.sp, Official.SubInk, 16.sp)

    /** 站序列表的次要信息。 */
    val MetaLabel = official(11.sp, Official.SubInk, 15.sp)

    /** 站序列表的站名。 */
    val StopName = official(15.sp, Official.Ink, 21.sp)
}

// ---- 位置条几何常量（px ÷ 3 得 dp，全部来自规格第三节） ----

/** 格宽 P：站心间距 204.5px。 */
internal val StripCell = 68.2.dp

/** 主线粗 21px，实色、方角。 */
internal val StripTrack = 7.dp

/** 车标 62×62px。 */
internal val StripTrainSize = 20.7.dp

/** 车标中心悬在主线上方 111.5px。 */
internal val StripTrainLift = 37.2.dp

/** 选中站圆环 Ø62px / 环厚 6px / 白隙 12px / 芯 Ø26px。 */
internal val StripRingOuter = 20.7.dp
internal val StripRingWidth = 2.dp
internal val StripRingGap = 4.dp
internal val StripRingCore = 8.7.dp

/** 站间箭头 ink 34×21px：杆 21px 长、箭头顶 12px、总高＝主线粗。 */
internal val StripArrowRodStart = 4.3.dp
internal val StripArrowRodEnd = 2.7.dp
internal val StripArrowTip = 6.7.dp

/** 竖排站名行距：普通站 61px、选中站 65px；首字 ink 顶在主线下 48px。 */
internal val StripNameGap = 16.dp
internal val StripNameLine = 20.3.dp
internal val StripNameLineSelected = 21.7.dp

/** 位置条视口锚点：选中站落在视口 78% 处（官方截图实测 78.4%）。 */
internal const val StripAnchorRatio = 0.78f

/** 官方车标落在两站之间 0.46 处（0.5 是段中点），显示层补这个相位差。 */
internal const val StripTrainPhase = 0.04f
