package com.yll682.houche.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 状态色只在少数需要固定语义的地方使用；主界面强调色统一取 colorScheme。
// 到站语义按官方 App 的配色：即将到站用橙红，途中有站数用蓝，等待/未知用灰。
val ArrivedGreen = Color(0xFF0B7A5B)
val MovingBlue = Color(0xFF1E6FF3)
val AlertOrange = Color(0xFFFF5B2B)

// 顶栏渐变：官方首页是自上而下的蓝色渐变。
val HeaderTop = Color(0xFF2A7BF7)
val HeaderBottom = Color(0xFF1B62DF)

// 深色模式顶栏：同样的蓝色调压暗两档，保证白色图标与标题的对比度。
val HeaderTopDark = Color(0xFF2A4C82)
val HeaderBottomDark = Color(0xFF172F55)

// 浅色：白卡片 + 浅灰页底，主色取官方 App 的蓝色。
val LightColors = lightColorScheme(
    primary = Color(0xFF1E6FF3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F1FE),
    onPrimaryContainer = Color(0xFF0A2C63),
    secondary = Color(0xFF5A6472),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFF2F6),
    onSecondaryContainer = Color(0xFF1C2430),
    tertiary = Color(0xFFFF5B2B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEDE7),
    onTertiaryContainer = Color(0xFF5C1B00),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF171A1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A1E),
    surfaceVariant = Color(0xFFF2F4F7),
    onSurfaceVariant = Color(0xFF8A9099),
    outline = Color(0xFFC3C9D2),
    outlineVariant = Color(0xFFEDEFF3),
    error = Color(0xFFD93025),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF5F1410),
    inverseSurface = Color(0xFF22272E),
    inverseOnSurface = Color(0xFFEFF1F4),
    inversePrimary = Color(0xFF9CC2FF),
    surfaceTint = Color(0xFF1E6FF3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E6EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF4F5F7),
    surfaceContainerHigh = Color(0xFFEFF2F6),
    surfaceContainerHighest = Color(0xFFE9EDF2)
)

// 深色：近黑蓝灰底 + 微亮卡片，描边比浅色更明显。
val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    onPrimary = Color(0xFF00295F),
    primaryContainer = Color(0xFF14345F),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFFB6BEC9),
    onSecondary = Color(0xFF1E252E),
    secondaryContainer = Color(0xFF2A323D),
    onSecondaryContainer = Color(0xFFD6DDE6),
    tertiary = Color(0xFFFF9C7A),
    onTertiary = Color(0xFF541A00),
    tertiaryContainer = Color(0xFF6B2400),
    onTertiaryContainer = Color(0xFFFFDBD0),
    background = Color(0xFF0D1014),
    onBackground = Color(0xFFE6E9EE),
    surface = Color(0xFF161A1F),
    onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF1E242B),
    onSurfaceVariant = Color(0xFF9AA3AE),
    outline = Color(0xFF6B7480),
    outlineVariant = Color(0xFF262D35),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5C1A15),
    errorContainer = Color(0xFF3B1512),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6E9EE),
    inverseOnSurface = Color(0xFF1B2026),
    inversePrimary = Color(0xFF1E6FF3),
    surfaceTint = Color(0xFF7FB2FF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A323A),
    surfaceDim = Color(0xFF0D1014),
    surfaceContainerLowest = Color(0xFF07090C),
    surfaceContainerLow = Color(0xFF12161B),
    surfaceContainer = Color(0xFF181D23),
    surfaceContainerHigh = Color(0xFF1E242B),
    surfaceContainerHighest = Color(0xFF262D35)
)
