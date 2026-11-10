package com.yll682.houche.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 状态色只在少数需要固定语义的地方使用；主界面强调色统一取 colorScheme。
// 到站语义按官方 App 的配色：即将到站用橙红，途中有站数用蓝，等待/未知用灰。
val ArrivedGreen = Color(0xFF0B7A5B)
val MovingBlue = Color(0xFF298ACF)
val AlertOrange = Color(0xFFFF5B2B)

// 顶栏渐变：官方首页是自上而下的蓝色渐变。
val HeaderTop = Color(0xFFFFFFFF)
val HeaderBottom = Color(0xFFFFFFFF)

// 深色模式顶栏：同样的蓝色调压暗两档，保证白色图标与标题的对比度。
val HeaderTopDark = Color(0xFF1D2733)
val HeaderBottomDark = Color(0xFF1D2733)

// 浅色：白卡片 + 浅灰页底，主色取官方 App 的蓝色。
val LightColors = lightColorScheme(
    primary = Color(0xFF298ACF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7F3FC),
    onPrimaryContainer = Color(0xFF298ACF),
    secondary = Color(0xFF5A6472),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFF2F6),
    onSecondaryContainer = Color(0xFF1C2430),
    tertiary = Color(0xFFFF5B2B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEDE7),
    onTertiaryContainer = Color(0xFF5C1B00),
    background = Color(0xFFF1F1F3),
    onBackground = Color(0xFF1A1D21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D21),
    surfaceVariant = Color(0xFFF2F4F7),
    onSurfaceVariant = Color(0xFF808384),
    outline = Color(0xFFC3C9D2),
    outlineVariant = Color(0xFFD9D9D9),
    error = Color(0xFFCC2929),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF5F1410),
    inverseSurface = Color(0xFF22272E),
    inverseOnSurface = Color(0xFFEFF1F4),
    inversePrimary = Color(0xFF9CC2FF),
    surfaceTint = Color(0xFF298ACF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E6EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF1F1F3),
    surfaceContainerHigh = Color(0xFFEFF2F6),
    surfaceContainerHighest = Color(0xFFE9EDF2)
)

// 深色：近黑蓝灰底 + 微亮卡片，描边比浅色更明显。
val DarkColors = darkColorScheme(
    primary = Color(0xFF79C4FC),
    onPrimary = Color(0xFF00295F),
    primaryContainer = Color(0xFF263E54),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFFB6BEC9),
    onSecondary = Color(0xFF1E252E),
    secondaryContainer = Color(0xFF2A323D),
    onSecondaryContainer = Color(0xFFD6DDE6),
    tertiary = Color(0xFFFF9C7A),
    onTertiary = Color(0xFF541A00),
    tertiaryContainer = Color(0xFF6B2400),
    onTertiaryContainer = Color(0xFFFFDBD0),
    background = Color(0xFF151E27),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1D2733),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E242B),
    onSurfaceVariant = Color(0xFF7D8B99),
    outline = Color(0xFF6B7480),
    outlineVariant = Color(0xFF17212B),
    error = Color(0xFFEE686F),
    onError = Color(0xFF5C1A15),
    errorContainer = Color(0xFF3B1512),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF1B2026),
    inversePrimary = Color(0xFF298ACF),
    surfaceTint = Color(0xFF79C4FC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A323A),
    surfaceDim = Color(0xFF151E27),
    surfaceContainerLowest = Color(0xFF07090C),
    surfaceContainerLow = Color(0xFF12161B),
    surfaceContainer = Color(0xFF1D2733),
    surfaceContainerHigh = Color(0xFF1E242B),
    surfaceContainerHighest = Color(0xFF17212B)
)
