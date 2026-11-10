package com.yll682.houche.ui.components

import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
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

/** M22 independently drawn contour interpolation; M23 parent long press owns the lens, no child menus. */
@Composable
fun TelegramNavigation(selected: Int, select: (Int)->Unit, modifier: Modifier=Modifier, backdrop: NavigationBackdrop?=null) {
    var width by remember { mutableFloatStateOf(1f) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var selectorHidden by remember { mutableStateOf(false) }
    var restoreSelector by remember { mutableStateOf<Job?>(null) }
    val gestureScope = rememberCoroutineScope()
    var touchX by remember { mutableFloatStateOf(0f) }
    var touchY by remember { mutableFloatStateOf(28f) }
    var preview by remember { mutableIntStateOf(selected) }
    val latestSelect=rememberUpdatedState(select)
    val latestSelection=rememberUpdatedState(selected)
    val haptic=LocalHapticFeedback.current
    val density=LocalDensity.current
    val target=if(dragging) touchX else ((width-8.dp.value*density.density)/3f)*(selected+.5f)+4.dp.value*density.density
    val center by animateFloatAsState(target,if(dragging) snap() else spring(dampingRatio=Spring.DampingRatioLowBouncy,stiffness=Spring.StiffnessMedium),label="lens-center")
    val pressure by animateFloatAsState(if(dragging)1f else 0f,tween(380,easing=TelegramQuint),label="lens-pressure")
    val shape=RoundedCornerShape(100.dp)
    val colors=MaterialTheme.colorScheme
    val labels=listOf("首页","收藏","设置")
    val gesture=Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            var last=down.position
            val longPress=withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis*.75f).toLong()) {
                var ended=false
                while(!ended) {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    val change=event.changes.firstOrNull { it.id==down.id }
                    if(change==null || !change.pressed || change.isConsumed || (change.position-down.position).getDistance()>viewConfiguration.touchSlop) ended=true
                    else last=change.position
                }
                true
            }
            if(longPress==null) {
                restoreSelector?.cancel(); selectorHidden = true
                dragging=true
                val cell=(size.width-8.dp.toPx())/3f
                fun update(position:Offset) {
                    touchX=position.x.coerceIn(4.dp.toPx()+cell*.5f,size.width-4.dp.toPx()-cell*.5f)
                    touchY=position.y
                    val next=((touchX-4.dp.toPx())/cell).toInt().coerceIn(0,2)
                    if(next!=preview) { preview=next;haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
                preview=latestSelection.value;update(last)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                var released=false;var cancelled=false
                try {
                    while(!released && !cancelled) {
                        val event=awaitPointerEvent(PointerEventPass.Initial)
                        val change=event.changes.firstOrNull { it.id==down.id }
                        if(change==null) cancelled=true
                        else if (!change.pressed && change.isConsumed) cancelled = true
                        else {
                            update(change.position);change.consume()
                            if(!change.pressed) released=true
                        }
                    }
                    if(released && !cancelled) latestSelect.value(preview)
                } finally {
                    dragging=false;preview=latestSelection.value
                    restoreSelector = gestureScope.launch { delay(450); selectorHidden=false }
                }
            }
        }
    }
    Box(modifier.widthIn(max=344.dp).fillMaxWidth().heightIn(min=56.dp)
        .onSizeChanged { width=it.width.toFloat() }
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
            val cell=(size.width-8.dp.toPx())/3f
            val cx=if(dragging)touchX else center
            drawRoundRect(colors.primary.copy(alpha=.11f+.035f*pressure),Offset(cx-cell/2,4.dp.toPx()),Size(cell,size.height-8.dp.toPx()),CornerRadius(28.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            labels.forEachIndexed { index,label ->
                val active=index==(if(dragging)preview else selected)
                val p by animateFloatAsState(if(active)1f else 0f,tween(320,easing=TelegramDecelerate),label="tab-contour-$index")
                val color=lerp(colors.onSurfaceVariant,colors.primary,p)
                Column(Modifier.weight(1f).heightIn(min=48.dp).selectable(active,interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,role=Role.Tab,onClick={latestSelect.value(index)})
                    .padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    NavigationGlyph(index,p,color)
                    Text(label,style=MaterialTheme.typography.labelSmall.copy(fontSize=11.sp,fontWeight=if(active)FontWeight.ExtraBold else FontWeight.Bold),color=color)
                }
            }
        }
    }
}

@Composable
internal fun NavigationGlyph(index:Int, progress:Float, color:Color) {
    // 2.0.x：Material 轮廓图标；选中过程中同一时长里交叉渐变成实心图标。
    val outlined=when(index){0->androidx.compose.material.icons.Icons.Outlined.Home;1->androidx.compose.material.icons.Icons.Outlined.StarBorder;else->androidx.compose.material.icons.Icons.Outlined.Settings}
    val filled=when(index){0->androidx.compose.material.icons.Icons.Filled.Home;1->androidx.compose.material.icons.Icons.Filled.Star;else->androidx.compose.material.icons.Icons.Filled.Settings}
    Box(Modifier.size(24.dp)) {
        androidx.compose.material3.Icon(outlined,null,Modifier.matchParentSize().graphicsLayer { alpha=1f-progress },tint=color)
        androidx.compose.material3.Icon(filled,null,Modifier.matchParentSize().graphicsLayer { alpha=progress },tint=color)
    }
}
private fun Offset(x:Int,y:Int)=Offset(x.toFloat(),y.toFloat())
