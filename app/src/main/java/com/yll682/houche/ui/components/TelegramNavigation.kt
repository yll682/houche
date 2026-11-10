package com.yll682.houche.ui.components

import android.os.Build
import android.graphics.Shader
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.yll682.houche.ui.HomeTabSpec
import kotlinx.coroutines.*
import kotlin.math.*

val LocalSettingsReselect=staticCompositionLocalOf { 0 }

/** Records only content behind the navigation, never recursively records the glass itself. */
class NavigationBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}
fun Modifier.navigationBackdrop(backdrop: NavigationBackdrop): Modifier = onGloballyPositioned { backdrop.origin=it.positionInRoot() }
    .drawWithContent {
        if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            backdrop.layer.record { this@drawWithContent.drawContent() }; drawContent()
        } else drawContent()
    }

/** Independently drawn contour interpolation; horizontal dragging owns the lens. */
@Composable
fun TelegramNavigation(selected: Int, select: (Int)->Unit, tabs: List<HomeTabSpec>, modifier: Modifier=Modifier, backdrop: NavigationBackdrop?=null) {
    BoxWithConstraints(modifier.widthIn(max=344.dp).fillMaxWidth(),propagateMinConstraints=true) {
        // 先应用底栏宽度约束，首次组合即可用最终像素尺寸定位，无需等测量回调后补画。
        val width=constraints.maxWidth.toFloat()
        val inset=with(LocalDensity.current) { 4.dp.toPx() }
        val cell=(width-inset*2)/tabs.size
        var rootOrigin by remember { mutableStateOf(Offset.Zero) }
        var dragging by remember { mutableStateOf(false) }
        val gestureScope = rememberCoroutineScope()
        var touchX by remember { mutableFloatStateOf(0f) }
        var touchY by remember { mutableFloatStateOf(28f) }
        var preview by remember { mutableIntStateOf(selected) }
        val latestSelect=rememberUpdatedState(select)
        val latestSelection=rememberUpdatedState(selected)
        val haptic=LocalHapticFeedback.current
        // 选中位置、透镜宽度与拖拽命中共用实际页数，避免四页导航仍按三格偏移。
        val target=cell*(selected+.5f)+inset
        // 尺寸变化直接按新几何落位，保留 Tab 切换与拖动结束时的弹簧。
        val center=remember(width,cell) { Animatable(target) }
        var settleRequest by remember { mutableIntStateOf(0) }
        LaunchedEffect(center,target,dragging,settleRequest) {
            if(!dragging) {
                center.animateTo(target,spring(dampingRatio=Spring.DampingRatioLowBouncy,stiffness=Spring.StiffnessMedium))
            }
        }
        val pressure by animateFloatAsState(if(dragging)1f else 0f,tween(380,easing=TelegramQuint),label="lens-pressure")
        val shape=RoundedCornerShape(100.dp)
        val colors=MaterialTheme.colorScheme
        // 页数或尺寸改变时重建手势，防止拖拽继续使用旧的格宽、动画对象和索引边界。
        val gesture=Modifier.pointerInput(tabs.size,width,cell) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                if(down.isConsumed) return@awaitEachGesture
                fun update(position:Offset) {
                    touchX=position.x.coerceIn(inset+cell*.5f,width-inset-cell*.5f)
                    touchY=position.y
                    val x=touchX
                    gestureScope.launch(start=CoroutineStart.UNDISPATCHED) { center.snapTo(x) }
                    val next=((touchX-inset)/cell).toInt().coerceIn(0,tabs.size-1)
                    if(next!=preview) { preview=next;haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
                var dragged=false
                try {
                    while(true) {
                        val event=awaitPointerEvent(PointerEventPass.Initial)
                        val change=event.changes.firstOrNull { it.id==down.id }
                        if(change==null || change.isConsumed) break
                        if(!change.pressed) {
                            if(dragged) {
                                update(change.position);change.consume()
                                latestSelect.value(preview)
                            }
                            break
                        }
                        if(!dragged) {
                            val delta=change.position-down.position
                            if(delta.getDistance()<=viewConfiguration.touchSlop) {
                                val finalEvent=awaitPointerEvent(PointerEventPass.Final)
                                if(finalEvent.changes.firstOrNull { it.id==down.id }?.isConsumed!=false) break
                                continue
                            }
                            if(abs(delta.x)<=abs(delta.y)) break
                            dragged=true;dragging=true;preview=latestSelection.value
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        update(change.position);change.consume()
                    }
                } finally {
                    if(dragged) {
                        dragging=false;preview=latestSelection.value
                        // 即使一次事件批次内就取消拖动、selected 没变，也要回弹到选中格。
                        settleRequest++
                    }
                }
            }
        }
        Box(Modifier.heightIn(min=56.dp)
            .onGloballyPositioned { rootOrigin=it.positionInRoot() }
            .graphicsLayer {
                scaleX=1f+.019f*pressure;scaleY=scaleX
                val nx=((touchX/width)-.5f)*2f
                val ny=((touchY/(56.dp.toPx()))-.5f)*2f
                val r=sqrt(nx*nx+ny*ny);val mapped=if(r>0f)1.5f/(r+.5f) else 1f
                transformOrigin=TransformOrigin((.5f+nx*mapped*.5f).coerceIn(0f,1f),(.5f+ny*mapped*1.5f).coerceIn(0f,1f))
            }.clip(shape).then(gesture)
            .background(colors.surface.copy(alpha=if(Build.VERSION.SDK_INT>=31 && backdrop!=null).4f else .95f))
            .border(.5.dp,colors.outlineVariant.copy(alpha=.35f),shape)) {
            if(backdrop!=null && Build.VERSION.SDK_INT>=31) {
                Canvas(Modifier.matchParentSize().graphicsLayer {
                    renderEffect=android.graphics.RenderEffect.createBlurEffect(16.dp.toPx(),16.dp.toPx(),Shader.TileMode.CLAMP).asComposeRenderEffect()
                }) {
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) translate(backdrop.origin.x-rootOrigin.x,backdrop.origin.y-rootOrigin.y) { drawLayer(backdrop.layer) }
                }
                Box(Modifier.matchParentSize().background(colors.surface.copy(alpha=.76f)))
            }
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(colors.primary.copy(alpha=.11f+.035f*pressure),Offset(center.value-cell/2,inset),Size(cell,size.height-inset*2),CornerRadius(28.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                tabs.forEachIndexed { index,spec ->
                    val active=index==(if(dragging)preview else selected)
                    val p by animateFloatAsState(if(active)1f else 0f,tween(320,easing=TelegramDecelerate),label="tab-contour-$index")
                    val color=lerp(colors.onSurfaceVariant,colors.primary,p)
                    Column(Modifier.weight(1f).heightIn(min=48.dp).selectable(active,interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,role=Role.Tab,onClick={latestSelect.value(index)})
                        .padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        NavigationGlyph(spec,p,color)
                        Text(spec.label,style=MaterialTheme.typography.labelSmall.copy(fontSize=11.sp,fontWeight=if(active)FontWeight.ExtraBold else FontWeight.Bold),color=color)
                    }
                }
            }
        }
    }
}

@Composable
internal fun NavigationGlyph(spec:HomeTabSpec, progress:Float, color:Color) {
    // 图标随页面定义传入，避免插入地铁页后索引错配；沿用同一进度保留轮廓到实心的交叉淡入。
    Box(Modifier.size(24.dp)) {
        androidx.compose.material3.Icon(spec.outlined,null,Modifier.matchParentSize().graphicsLayer { alpha=1f-progress },tint=color)
        androidx.compose.material3.Icon(spec.filled,null,Modifier.matchParentSize().graphicsLayer { alpha=progress },tint=color)
    }
}
private fun Offset(x:Int,y:Int)=Offset(x.toFloat(),y.toFloat())
