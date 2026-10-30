package com.yll682.houche.ui.theme

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

val ArrivedGreen = Color(0xFF12805C)
val MovingBlue = Color(0xFF2563C9)
val AlertOrange = Color(0xFFC2410C)
val LightColors = lightColorScheme(
    primary = Color(0xFF0A7A62), onPrimary = Color.White, primaryContainer = Color(0xFFC0F0DE), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4E635B), onSecondary = Color.White, secondaryContainer = Color(0xFFD1E8DC), onSecondaryContainer = Color(0xFF0B2018),
    tertiary = Color(0xFF3E6373), onTertiary = Color.White, tertiaryContainer = Color(0xFFC2E8FA), onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF6F8F7), onBackground = Color(0xFF12211D), surface = Color.White, onSurface = Color(0xFF12211D),
    surfaceVariant = Color(0xFFE3E9E7), onSurfaceVariant = Color(0xFF5A6B66), outline = Color(0xFF72847D), outlineVariant = Color(0xFFE3E9E7),
    error = AlertOrange, onError = Color.White, errorContainer = Color(0xFFFFDBCE), onErrorContainer = Color(0xFF351000),
    inverseSurface = Color(0xFF29332F), inverseOnSurface = Color(0xFFEDF3EF), inversePrimary = Color(0xFF4ED8B4),
    surfaceTint = Color(0xFF0A7A62), scrim = Color.Black,
    surfaceBright = Color.White, surfaceDim = Color(0xFFD6DED9), surfaceContainer = Color(0xFFEDF2EF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F8F7), surfaceContainerHigh = Color(0xFFE7EEEA), surfaceContainerHighest = Color(0xFFE0E7E3)
)
val DarkColors = darkColorScheme(
    primary = Color(0xFF4ED8B4), onPrimary = Color(0xFF00201A), primaryContainer = Color(0xFF00513F), onPrimaryContainer = Color(0xFFC0F0DE),
    secondary = Color(0xFFB5CCC0), onSecondary = Color(0xFF20362C), secondaryContainer = Color(0xFF374D42), onSecondaryContainer = Color(0xFFD1E8DC),
    tertiary = Color(0xFFA6CDDE), onTertiary = Color(0xFF073543), tertiaryContainer = Color(0xFF254C5A), onTertiaryContainer = Color(0xFFC2E8FA),
    background = Color(0xFF0E1512), onBackground = Color(0xFFE0EAE4), surface = Color(0xFF16201C), onSurface = Color(0xFFE0EAE4),
    surfaceVariant = Color(0xFF3D4943), onSurfaceVariant = Color(0xFFB9C9C0), outline = Color(0xFF899C91), outlineVariant = Color(0xFF3D4943),
    error = Color(0xFFFFB596), onError = Color(0xFF562000), errorContainer = Color(0xFF7A2D00), onErrorContainer = Color(0xFFFFDBCE),
    inverseSurface = Color(0xFFE0EAE4), inverseOnSurface = Color(0xFF29332F), inversePrimary = Color(0xFF0A7A62),
    surfaceTint = Color(0xFF4ED8B4), scrim = Color.Black,
    surfaceBright = Color(0xFF34403A), surfaceDim = Color(0xFF0E1512), surfaceContainer = Color(0xFF1B2620),
    surfaceContainerLowest = Color(0xFF09100C), surfaceContainerLow = Color(0xFF16201C), surfaceContainerHigh = Color(0xFF253029), surfaceContainerHighest = Color(0xFF303B34)
)
