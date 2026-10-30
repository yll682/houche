package com.yll682.houche.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Capsule = RoundedCornerShape(999.dp)

// 卡片 18dp、徽章 10dp、输入框 14dp —— 全站只用这三档，避免圆角乱飘。
val CardShape = RoundedCornerShape(18.dp)
val BadgeShape = RoundedCornerShape(10.dp)
val FieldShape = RoundedCornerShape(14.dp)

val HoucheShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = BadgeShape,
    medium = FieldShape,
    large = CardShape,
    extraLarge = RoundedCornerShape(26.dp)
)