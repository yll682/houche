package com.yll682.houche.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Capsule = RoundedCornerShape(999.dp)

// 参照官方 App：卡片 12dp、徽章 8dp、输入框 999dp（搜索框为胶囊）。
val CardShape = RoundedCornerShape(16.dp)
val BadgeShape = RoundedCornerShape(8.dp)
val FieldShape = RoundedCornerShape(10.dp)

val HoucheShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = BadgeShape,
    medium = RoundedCornerShape(10.dp),
    large = CardShape,
    extraLarge = RoundedCornerShape(18.dp)
)