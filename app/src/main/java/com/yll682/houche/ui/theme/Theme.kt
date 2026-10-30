package com.yll682.houche.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun HoucheTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    // 品牌配色固定，不使用动态壁纸色。
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = HoucheTypography, shapes = HoucheShapes, content = content)
}
