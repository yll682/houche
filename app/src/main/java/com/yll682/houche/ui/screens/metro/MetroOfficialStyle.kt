package com.yll682.houche.ui.screens.metro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yll682.houche.core.MetroLine
import kotlin.math.ceil

/**
 * 官方「列车位置」页的配色。浅色那一列数值全部来自 `notes/spec-official-train-position.md`
 * （官方截图 1080×2340、density 3.0 的逐像素实测）。
 *
 * 官方 App 没有深色版，所以 [OfficialDark] 是**我们自己的设计**：版式、比例、色角色分工
 * 一个都不动，只把明暗翻过来——页底变近黑、卡片略亮、主/次文字反向、品牌靛蓝整体提亮到
 * 深底上可读的档位，线路色仍旧用 `metro.json` 的官方值（不随深浅模式换）。
 *
 * 只服务 `ui/screens/metro/` 里的展示代码，不放 theme 全局令牌——公交页、首页的
 * `Color.kt` / `Type.kt` / `Shape.kt` 一个字节都不动。深色取值与全局 `DarkColors`
 * （background `#0D1014`、surfaceContainer `#181D23`）对齐，视觉上和公交页同族。
 */
internal data class OfficialPalette(
    val IsDark: Boolean,
    /** 页面底色（官方 `#F1F1F1`）。 */
    val Page: Color,
    /** 顶栏 / 线路 tab 条 / 主卡片底色（官方纯白，无描边无阴影）。 */
    val Card: Color,
    /** 0.5dp 发丝线（官方 `#E5E5E5`）。 */
    val Hairline: Color,
    /** 主文字：标题、方向行起终点、站名、站序站名（官方 `#333333`）。 */
    val Ink: Color,
    /** 次文字：连接线、胶囊边框与字、「刷新」、未选中竖排站名（官方 `#666666`）。 */
    val SubInk: Color,
    /** 「换向」图标（官方 `#888888`）。 */
    val IconInk: Color,
    /** ETA 蓝块底（官方 `#ECF2FF`）。 */
    val EtaBackground: Color,
    /** ETA 大数字 + 「分钟」（官方 `#3E2688`）。 */
    val EtaNumber: Color,
    /** ETA「第N班预计到达」（官方 `#3B2A78`）。 */
    val EtaLabel: Color,
    /** tab 选中下划线（官方 `#304097`）。 */
    val TabIndicator: Color,
    /**
     * 官方返回 chevron（`#6157EB`）。1.7.3 的白顶栏用过；现在顶栏换回候车自己的设计
     * （[BrandTop]/[BrandBottom] + 白色 `ArrowBack`），这里只作为官方色表留档。
     */
    val Chevron: Color,
    /**
     * 顶栏品牌蓝渐变的上下两端。官方这页是白顶栏（1.7.3 抄的），但候车 1.7.2 及之前一直用
     * 「品牌蓝渐变 + 白色返回箭头 + 白色标题」，深色档与全局 `HeaderTopDark`/`HeaderBottomDark`
     * 同值（`ui/theme/Color.kt`），所以深浅两套都协调，不会出现白底白字。
     */
    val BrandTop: Color,
    val BrandBottom: Color,
    /** 品牌蓝底上的文字/图标（两套模式都是白）。 */
    val OnBrand: Color,
    /** 选中站的竖排站名（官方 `#314093`，Bold）。 */
    val StationSelected: Color,
    /** 车标下方「第N班」（官方 `#3E2686`）。 */
    val TrainLabel: Color,
    /** 免责说明（官方 `#C88C8C`，提示语气，不是错误红）。 */
    val Disclaimer: Color,
    val TrainGradientStart: Color,
    val TrainGradientEnd: Color,
    /** 车标里的白色列车图形（两套模式都是白，压在靛蓝渐变上）。 */
    val TrainGlyph: Color,
    /** 最前那班车右侧的方向三角（官方 `#A7A9D8`）。 */
    val TrainDirection: Color,
    /**
     * 位置条上「挖空」用的颜色：浅色是白卡，深色是深卡——官方箭头/圆环白隙是**卡片色挖空**，
     * 不是「白色」这个语义，所以深色下必须跟着卡片走，否则箭头发光。
     */
    val Cutout: Color,
    /** 线路色与卡片对比不足时的细描边（深色专用，见 [needsLineEdge]）。 */
    val LineEdge: Color,
    /** 站序列表轨道 alpha（已运营 / 未开通）。 */
    val ListTrackAlpha: Float,
    val ListTrackAlphaPending: Float,
    /** 站序列里小胶囊的填充 alpha（中性 / 线路色）。 */
    val PillFillAlpha: Float,
    val PillTintAlpha: Float
)

internal val OfficialLight = OfficialPalette(
    IsDark = false,
    Page = Color(0xFFF1F1F3),
    Card = Color(0xFFFFFFFF),
    Hairline = Color(0xFFE5E5E5),
    Ink = Color(0xFF1A1D21),
    SubInk = Color(0xFF808384),
    IconInk = Color(0xFF888888),
    EtaBackground = Color(0xFFE7F3FC),
    EtaNumber = Color(0xFF298ACF),
    EtaLabel = Color(0xFF298ACF),
    TabIndicator = Color(0xFF298ACF),
    Chevron = Color(0xFF298ACF),
    BrandTop = Color(0xFFFFFFFF),
    BrandBottom = Color(0xFFFFFFFF),
    OnBrand = Color.White,
    StationSelected = Color(0xFF298ACF),
    TrainLabel = Color(0xFF298ACF),
    Disclaimer = Color(0xFF808384),
    TrainGradientStart = Color(0xFF383095),
    TrainGradientEnd = Color(0xFF3648AE),
    TrainGlyph = Color.White,
    TrainDirection = Color(0xFFA7A9D8),
    Cutout = Color(0xFFFFFFFF),
    LineEdge = Color(0x00000000),
    ListTrackAlpha = .38f,
    ListTrackAlphaPending = .16f,
    PillFillAlpha = .07f,
    PillTintAlpha = .12f
)

/**
 * 深色（自研）：卡片/页底沿用全局深色档（`#181D23` / `#0D1014`，对比 1.12:1，
 * 与官方浅色「白卡 vs `#F1F1F1`」的 1.12:1 完全同强度——层次一样、但不用阴影）。
 * 品牌靛蓝四个角色逐个提亮到深底可读；线路色不动。
 */
internal val OfficialDark = OfficialPalette(
    IsDark = true,
    Page = Color(0xFF151E27),
    Card = Color(0xFF1D2733),
    Hairline = Color(0xFF17212B),
    Ink = Color(0xFFFFFFFF),
    SubInk = Color(0xFF7D8B99),
    IconInk = Color(0xFF7C8592),
    EtaBackground = Color(0xFF263E54),
    EtaNumber = Color(0xFF79C4FC),
    EtaLabel = Color(0xFF79C4FC),
    TabIndicator = Color(0xFF79C4FC),
    Chevron = Color(0xFF79C4FC),
    BrandTop = Color(0xFF1D2733),
    BrandBottom = Color(0xFF1D2733),
    OnBrand = Color.White,
    StationSelected = Color(0xFF79C4FC),
    TrainLabel = Color(0xFF79C4FC),
    Disclaimer = Color(0xFF7D8B99),
    TrainGradientStart = Color(0xFF5460D6),
    TrainGradientEnd = Color(0xFF5B67E0),
    TrainGlyph = Color.White,
    TrainDirection = Color(0xFFB9BBE4),
    Cutout = Color(0xFF1D2733),
    LineEdge = Color(0x66E6E9EE),
    ListTrackAlpha = .55f,
    ListTrackAlphaPending = .28f,
    PillFillAlpha = .14f,
    PillTintAlpha = .22f
)

/** 本页取调色板：唯一入口，composable 里不再出现裸 `Color(0xFF…)`。 */
@Composable
internal fun officialPalette(): OfficialPalette =
    if (isSystemInDarkTheme()) OfficialDark else OfficialLight

/**
 * 线路色：直接用 `metro.json` 的官方线网图色值（1 `#DA7028` / 2 `#52A441` / 3 `#52B5C8` /
 * 4 `#E50012` / 6 `#92308D`），深浅模式都不改色值——用户要的就是官方那一套。
 * 上一轮那张「官方 App 截图实测色」的覆盖表已删除。
 */
@Composable
internal fun officialLineColor(line: MetroLine): Color = metroColor(line.color)

/** `metro.json` 色值解析失败时的兜底色（沿用旧值，只是从 composable 收进调色板文件）。 */
internal val OfficialLineFallback = Color(0xFF1E6FF3)

/**
 * 线路色底上的文字色（**非徽标场景**专用）：`luminance() ≥ 0.20` 用近黑 [LineInk]，否则白字。
 * 0.20 是这套官方色的**对比度交叉点**（白字与 `#1A1A1A` 的对比度相等处 L≈0.245，
 * 取 0.20 让 1/2 号线这种中亮度色也走深字）。逐线实测：
 * 1 `#DA7028` 深字 5.13 / 白字 3.32；2 `#52A441` 深字 5.47 / 白字 3.11；
 * 3 `#52B5C8` 深字 7.14 / 白字 2.39；4 `#E50012` 白字 4.84 / 深字 2.61；
 * 6 `#92308D` 白字 6.93 / 深字 1.82。取高者，全表 ≥ 4.5:1。
 *
 * 线路色与深浅模式无关，所以这个结果也不分模式。
 *
 * **徽标内部不用它**（2026-11-08 决定）：官方三个徽标实测字色都是白（240,240,240），
 * 连浅绿、浅青的徽标官方也用白字，所以徽标字色写死在 [LineBadgeInk] 里，
 * 不再按底色亮度切深墨。这里只留给「浅色线路色上写普通文字」这类非徽标场景。
 */
internal fun onLineColor(tint: Color): Color =
    if (tint.luminance() >= LineInkThreshold) LineInk else Color.White

/** 线路色上的近黑文字（`#333` 压在官方色上只有 2.6–4.0，不够 4.5，故单独给一档）。 */
private val LineInk = Color(0xFF1A1A1A)

/** 白字 / 深字的交叉亮度阈值，见 [onLineColor]。 */
private const val LineInkThreshold = 0.20f

/** WCAG 相对对比度。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + .05f) / (minOf(la, lb) + .05f)
}

/**
 * 深色下线路色是否需要补一根细描边。
 *
 * 官方色里 6 号线 `#92308D`（L=0.101）压在深卡 `#181D23` 上只有 **2.45:1**，
 * 7dp 主线和小圆环会糊进底色——**不改色值**，另画 0.6dp 描边把轮廓提出来
 * （`#E6E9EE` 40% 叠在卡上 ⇒ 实测 `#6D7075`，对卡 3.41:1）。浅色模式一律不加。
 */
internal fun needsLineEdge(tint: Color, palette: OfficialPalette): Boolean =
    palette.IsDark && contrastRatio(tint, palette.Card) < 3f

/**
 * 站序列表（官方这页没有，候车自有控件）的线路色：深色下把线路色往白里提 35% 再降透明度用，
 * 免得 4/6 号线在深底上只剩一团黑。**位置条、徽标这些官方版式里的元素仍用原色值**，
 * 这条只作用于「非官方版式」的装饰件。
 */
internal fun listLineColor(tint: Color, palette: OfficialPalette): Color =
    if (palette.IsDark) lerp(tint, Color.White, .35f) else tint

/** 官方卡片 10dp 圆角、无描边无阴影；本页专用，不替换全局 `CardShape`。 */
internal val OfficialCardShape = RoundedCornerShape(16.dp)

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

/**
 * 本页专用字号档：19 / 15.8 / 14.3 / 13.3 / 11.5 / 11.3 sp（官方实测 px÷3）。
 *
 * 字号、行高、字重与明暗无关，只有颜色来自 [OfficialPalette]，所以样式集中在这里
 * 按当前调色板构造一次（见 [officialTypography]）。
 */
internal class OfficialTypography(palette: OfficialPalette) {
    /** 顶栏标题 19sp。 */
    val Title = official(19.sp, palette.Ink, 23.sp)

    /**
     * 品牌蓝顶栏上的标题：候车 1.7.2 用的是全局 `titleLarge`（19sp SemiBold），
     * 这里同字号同字重、颜色换成 [OfficialPalette.OnBrand]。
     */
    val BrandTitle = official(19.sp, palette.OnBrand, 26.sp, FontWeight.SemiBold)

    /** 方向行起终点 15.8sp。 */
    val Direction = official(15.8.sp, palette.Ink, 22.sp)

    /** 「开往」胶囊里的字 11.5sp。 */
    val Capsule = official(11.5.sp, palette.SubInk, 15.sp)

    /** 「换向」文字 11.3sp。 */
    val SwapLabel = official(11.3.sp, palette.SubInk, 15.sp)

    /** 站名 14.3sp。 */
    val StationName = official(14.3.sp, palette.Ink, 20.sp)

    /** 「刷新」文字 11.3sp。 */
    val RefreshLabel = official(11.3.sp, palette.SubInk, 15.sp)

    /** ETA 大数字 20sp，Regular 不加粗；行高取 20sp 让下方的「第N班预计到达」贴住实测位置。 */
    val EtaNumber = official(20.sp, palette.EtaNumber, 16.sp)

    /** ETA「分钟」，与数字同基线。 */
    val EtaUnit = official(13.7.sp, palette.EtaNumber, 16.sp)

    /** ETA「第N班预计到达」11.5sp。 */
    val EtaLabel = official(11.5.sp, palette.EtaLabel, 15.sp)

    /** 竖排站名 14.3sp / 行距 61px＝20.3dp。 */
    val StripeName = official(14.3.sp, palette.SubInk, 20.3.sp)

    /** 选中站名：Bold + 靛蓝 + 行距 65px≈21.7dp。 */
    val StripeNameSelected = official(14.3.sp, palette.StationSelected, 21.7.sp, FontWeight.Bold)

    /** 车标下方「第N班」11.5sp。 */
    val TrainLabel = official(11.5.sp, palette.TrainLabel, 15.sp)

    /**
     * 车标下方「已过」：与「第N班」**同字号同位置**，只把颜色换成次要文字色 [OfficialPalette.SubInk]
     * （浅色 `#666` 5.74:1、深色 `#9AA3AE` 6.5:1，都过 4.5:1）。不用错误红——过站是正常运营状态，
     * 不是故障。
     */
    val TrainLabelPassed = official(11.5.sp, palette.SubInk, 15.sp)

    /** 免责说明 13.3sp / 行高 20.3dp，珊瑚红居中。 */
    val Disclaimer = official(13.3.sp, palette.Disclaimer, 20.3.sp)

    /** 站序列表的小标题（官方这页没有这段，沿用原有信息层级）。 */
    val SectionLabel = official(14.sp, palette.TabIndicator, 20.sp)

    /** 站序列表的次要信息。 */
    val MetaLabel = official(13.sp, palette.SubInk, 18.sp)

    /** 站序列表的站名。 */
    val StopName = official(16.sp, palette.Ink, 24.sp)
}

/** 当前调色板对应的字号档；调色板不变就不重建。 */
@Composable
internal fun officialTypography(): OfficialTypography {
    val palette = officialPalette()
    return remember(palette) { OfficialTypography(palette) }
}

// ---- 位置条几何常量（px ÷ 3 得 dp，全部来自规格第三节；与明暗无关） ----

/**
 * 固定最小格宽：官方实测站心间距 204.5px＝68.2dp。
 *
 * 它只是一条**下限**，实际格宽由 [rememberStripCell] 按整条线测一次。
 */
internal val StripMinCell = 68.2.dp

/** 站名两侧各留的空白：官方格宽减一个汉字宽再对半（≈26.95dp）取下整，留 0.95dp 余量。 */
internal val StripNameInset = 26.dp

/** 站点命中列的最小宽度（44dp 触摸目标下限）。 */
internal val StripMinTapWidth = 44.dp

/** 车标列宽：车标 20.7dp、「第N班」约 34.5dp，取 40dp 让标签不被父级宽度裁掉。 */
internal val StripMarkerBox = 40.dp

/** 错层 lane 的高度下限：图标 20.7dp ＋ 标签行盒，单层至少 44dp 才好按。 */
internal val StripLaneMin = 44.dp

/** lane 与 lane 之间留的视觉间隙（规格：+4dp）。 */
internal val StripLaneGap = 4.dp

/** 竖排站名最多 8 行（一字一行），与位置条里 `Text(maxLines = 8)` 保持一致。 */
private const val StripNameMaxLines = 8

/**
 * 整条线的格宽 W：**按物理线路的完整站表量一次**，不是可见窗口、不是当前参考站、不是某个方向。
 *
 * 规格（Codex 第四轮「整条线格宽规格」）：
 * 1. 测量集＝`line.stations` 按站码去重后的全部站名，正反序是同一集合的排列；
 * 2. 每个站名按实际竖排「一字一行」分别测普通与选中 Bold 两种样式，取整线最大宽度 M；
 * 3. `W = max(固定最小格宽, M + 两侧内边距, 最小点击宽, 固定图标宽)`，测量项向上取整到整像素；
 * 4. 缓存 key 只含线路码、完整 (code,name) 集合、两种竖排样式与 density/fontScale
 *    —— **不含方向、参考站、viewport、位置批次**，所以换站/反向/滚动前后 W 逐像素不变。
 *
 * 测的是宽度不是字数：竖排名字的字数只决定高度（高度仍按本方向最长站名算）。
 */
@Composable
internal fun rememberStripCell(line: MetroLine): Dp {
    val type = officialTypography()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val table = remember(line.id, line.stations) {
        line.stations.map { station -> station.code.ifBlank { station.name } to station.name }
            .distinct()
            .sortedBy { station -> station.first }
    }
    return remember(measurer, table, type.StripeName, type.StripeNameSelected, density) {
        val widest = table.maxOfOrNull { (_, name) -> measuredVerticalWidth(measurer, name, type) } ?: 0
        with(density) {
            val measured = widest + (StripNameInset * 2).roundToPx()
            val floor = maxOf(measured, StripMinTapWidth.roundToPx(), StripMarkerBox.roundToPx())
            maxOf(StripMinCell, ceil(floor.toFloat()).toDp())
        }
    }
}

/** 一个站名竖排后（普通 + 选中 Bold）的墨迹宽度上限，单位 px。 */
private fun measuredVerticalWidth(
    measurer: TextMeasurer,
    name: String,
    type: OfficialTypography
): Int {
    val vertical = name.chunked(1).joinToString("\n")
    if (vertical.isEmpty()) return 0
    return maxOf(
        measurer.measure(AnnotatedString(vertical), style = type.StripeName, maxLines = StripNameMaxLines).size.width,
        measurer.measure(
            AnnotatedString(vertical), style = type.StripeNameSelected, maxLines = StripNameMaxLines
        ).size.width
    )
}

/** 主线粗 21px，实色、方角。 */
internal val StripTrack = 7.dp

/** 车标 62×62px。 */
internal val StripTrainSize = 20.7.dp

/** 车标中心悬在主线上方 111.5px。 */
internal val StripTrainLift = 37.2.dp

/**
 * 选中站圆环：基准图逐像素量得 Ø58px / 环厚 7px / 白隙 7px / 芯 Ø29px
 * （＝19.33 / 2.33 / 2.33 / 9.67dp）。
 */
internal val StripRingOuter = 19.33.dp
internal val StripRingWidth = 2.33.dp
internal val StripRingGap = 2.33.dp
internal val StripRingCore = 9.67.dp

/**
 * 站点箭头（基准图实测 ink 30×18px）：杆 21px 长 × 5px 粗、箭头 18px 高 × 9px 长。
 * 相对站心的偏移：杆起 −12.5px、杆止 +8.5px、箭尖 +17.5px。
 */
internal val StripArrowRodStart = 4.17.dp
internal val StripArrowRodEnd = 2.83.dp
internal val StripArrowTip = 5.83.dp

/** 箭杆粗细 5px＝1.67dp。 */
internal val StripArrowRod = 1.67.dp

/** 箭头高 18px＝6dp（比主线 7dp 窄一点，官方箭头两端各留 2px 线色）。 */
internal val StripArrowHead = 6.dp

/** 深色下线路色对比不足时的描边粗细。 */
internal val StripLineEdge = .6.dp

/** 竖排站名行距：普通站 61px、选中站 65px；首字 ink 顶在主线下 48px。 */
internal val StripNameGap = 16.dp
internal val StripNameLine = 20.3.dp
internal val StripNameLineSelected = 21.7.dp

/**
 * 站名最后一字到下方第一枚换乘徽标的间距。
 *
 * 基准图实测：站名最后一字**墨迹**底 → 徽标上沿 22px＝7.33dp。站名按行盒（20.3dp / 21.7dp）
 * 算高、行盒底比字墨迹底低约 3.9dp（[MetroTrainStrip] 的 nameInkInset），所以行盒底部到徽标
 * 取 4dp；渲染后实测徽标上沿正好落在墨迹下 22px。
 */
internal val StripTransferGap = 4.dp

/**
 * 换乘徽标色块边长：官方 58×58px＝19.33dp。
 *
 * 与共享徽标的小档（[SmallBadgeSize]）是同一个值——位置条换乘标注、列表里的
 * 「本站线路号」与「换乘」徽标全部同规格（19.3dp、圆角 2.3dp、白字）。
 */
internal val StripTransferBadge = SmallBadgeSize

/** 相邻两枚换乘徽标的间距：基准图 12px＝4dp。 */
internal val StripTransferBadgeGap = 4.dp

/** 位置条视口锚点：选中站落在视口 78% 处（官方截图实测 78.4%）。 */
internal const val StripAnchorRatio = 0.78f

/** 官方车标落在两站之间 0.46 处（0.5 是段中点），显示层补这个相位差。 */
internal const val StripTrainPhase = 0.04f
