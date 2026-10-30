package com.yll682.houche.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 状态色只在少数需要固定语义的地方使用；主界面强调色统一取 colorScheme。
val ArrivedGreen = Color(0xFF0B7A5B)
val MovingBlue = Color(0xFF2F6BD8)
val AlertOrange = Color(0xFFB3261E)

// 浅色：暖白纸面 + 纯白卡片 + 细描边。不用灰色填充压出层次，靠描边和留白分层。
val LightColors = lightColorScheme(
    primary = Color(0xFF0B7A5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF1E7),
    onPrimaryContainer = Color(0xFF06281C),
    secondary = Color(0xFF47584F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4EDE7),
    onSecondaryContainer = Color(0xFF16241D),
    tertiary = Color(0xFF2F6BD8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2EAFB),
    onTertiaryContainer = Color(0xFF0A2450),
    background = Color(0xFFF4F6F4),
    onBackground = Color(0xFF101B17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101B17),
    surfaceVariant = Color(0xFFEDF1EE),
    onSurfaceVariant = Color(0xFF55655F),
    outline = Color(0xFF97A69F),
    outlineVariant = Color(0xFFE3E9E5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF5F1410),
    inverseSurface = Color(0xFF1B2621),
    inverseOnSurface = Color(0xFFEDF3EF),
    inversePrimary = Color(0xFF5BE0B4),
    surfaceTint = Color(0xFF0B7A5B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCE3DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFA),
    surfaceContainer = Color(0xFFF4F6F4),
    surfaceContainerHigh = Color(0xFFEDF1EE),
    surfaceContainerHighest = Color(0xFFE6ECE8)
)

// 深色：近黑墨绿底 + 微亮卡片，描边比浅色更明显。
val DarkColors = darkColorScheme(
    primary = Color(0xFF5BE0B4),
    onPrimary = Color(0xFF04291D),
    primaryContainer = Color(0xFF123A2C),
    onPrimaryContainer = Color(0xFFB9F5DF),
    secondary = Color(0xFFB6C7BC),
    onSecondary = Color(0xFF1E2E26),
    secondaryContainer = Color(0xFF2C3D34),
    onSecondaryContainer = Color(0xFFD2E4D9),
    tertiary = Color(0xFF8AB4FF),
    onTertiary = Color(0xFF0A2450),
    tertiaryContainer = Color(0xFF16305C),
    onTertiaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF0B100E),
    onBackground = Color(0xFFE4EDE8),
    surface = Color(0xFF141C19),
    onSurface = Color(0xFFE4EDE8),
    surfaceVariant = Color(0xFF1E2724),
    onSurfaceVariant = Color(0xFF9DADA6),
    outline = Color(0xFF6E7D77),
    outlineVariant = Color(0xFF28332E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5C1A15),
    errorContainer = Color(0xFF3B1512),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4EDE8),
    inverseOnSurface = Color(0xFF1B2621),
    inversePrimary = Color(0xFF0B7A5B),
    surfaceTint = Color(0xFF5BE0B4),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2C3833),
    surfaceDim = Color(0xFF0B100E),
    surfaceContainerLowest = Color(0xFF080D0B),
    surfaceContainerLow = Color(0xFF111815),
    surfaceContainer = Color(0xFF161F1B),
    surfaceContainerHigh = Color(0xFF1E2724),
    surfaceContainerHighest = Color(0xFF28332E)
)