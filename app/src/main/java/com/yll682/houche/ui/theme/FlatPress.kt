package com.yll682.houche.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch

/**
 * 点按反馈：按下时**整个可点区域**均匀变色（80ms 淡入，松手 150ms 淡出），没有从触点扩散的圆形涟漪。
 * 仿 Telegram 列表的 selector：贴边矩形、不带扩散。2.0.6 起整个 App 用它；
 * 圆形的图标按钮（IconButton）仍走 Material 的无界涟漪。
 */
internal class FlatPressIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color)

    override fun equals(other: Any?) = other is FlatPressIndication && other.color == color
    override fun hashCode() = color.hashCode()

    private class Node(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
        private val alpha = Animatable(0f)
        private var pressCount = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> { pressCount++; launch { alpha.animateTo(1f, tween(80)) } }
                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            pressCount = (pressCount - 1).coerceAtLeast(0)
                            if (pressCount == 0) launch { alpha.animateTo(0f, tween(150)) }
                        }
                    }
                }
            }
            coroutineScope.launch { snapshotFlowInvalidate() }
        }

        private suspend fun snapshotFlowInvalidate() {
            androidx.compose.runtime.snapshotFlow { alpha.value }.collect { invalidateDraw() }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val a = alpha.value
            if (a > 0.001f) drawRect(color.copy(alpha = color.alpha * a))
        }
    }
}

/** 整个 App 的默认点按反馈：onSurface 6%（约等于 Telegram 列表选中色的深度）。 */
@Composable
internal fun rememberFlatPressIndication(): IndicationNodeFactory {
    val base = MaterialTheme.colorScheme.onSurface
    return remember(base) { FlatPressIndication(base.copy(alpha = 0.06f)) }
}
