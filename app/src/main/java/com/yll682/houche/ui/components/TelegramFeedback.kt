package com.yll682.houche.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Each operation has exactly one terminal state, including replacement and navigation detach. */
class UndoBulletin(val id:Long,val message:String,private val restore:()->Unit,private val finalize:()->Unit={}) {
    private val finished=AtomicBoolean(false)
    fun undo() { if(finished.compareAndSet(false,true)) restore() }
    fun commit() { if(finished.compareAndSet(false,true)) finalize() }
}
@Stable class BulletinCenter {
    var current by mutableStateOf<UndoBulletin?>(null);private set
    private var nextId=0L
    fun show(message:String,undo:()->Unit={},commit:()->Unit={}) { current?.commit();current=UndoBulletin(++nextId,message,undo,commit) }
    fun undo(id:Long) { current?.takeIf { it.id==id }?.let { it.undo();current=null } }
    fun dismiss() { current?.commit();current=null }
}
val LocalBulletinCenter=staticCompositionLocalOf { BulletinCenter() }

/** M25 spring stiffness/damping; avoids IME and navigation through host padding. */
@Composable fun BulletinHost(center:BulletinCenter, modifier:Modifier=Modifier) {
    val current=center.current
    var displayed by remember { mutableStateOf<UndoBulletin?>(null) }
    SideEffect { if (current != null) displayed = current }
    val accessibility=LocalAccessibilityManager.current
    LaunchedEffect(current?.id) {
        if(current!=null) { delay(accessibility?.calculateRecommendedTimeoutMillis(5000L,containsText=true,containsIcons=true,containsControls=true)?:5000L);if(center.current?.id==current.id)center.dismiss() }
    }
    AnimatedVisibility(current!=null,modifier=modifier,enter=slideInVertically(spring(dampingRatio=.8f,stiffness=400f)){it},exit=slideOutVertically(spring(dampingRatio=.8f,stiffness=400f)){it}) {
        val item=current ?: displayed ?:return@AnimatedVisibility
        Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.inverseSurface,shadowElevation=3.dp) {
            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(current?.message?:item.message,Modifier.weight(1f).padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.inverseOnSurface)
                TextButton({ center.undo(center.current?.id?:item.id) }, enabled = current != null) { Text("撤销",color=MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

internal fun swipeArmed(offset:Float,width:Float)=width>0f && abs(offset)>=width*.45f

/** M19: direction slop arbitration is owned by detectHorizontalDragGestures; vertical scrolling wins.
 * Cross-threshold haptic, 300ms radial reveal and 170ms glyph bounce use separate channels.
 */
@Composable fun SwipeHistoryRow(key:String,onDelete:()->Unit,modifier:Modifier=Modifier,content:@Composable ()->Unit) {
    var width by remember(key) { mutableFloatStateOf(1f) }
    val offset=remember(key) { Animatable(0f) }
    var fingerOffset by remember(key) { mutableFloatStateOf(0f) }
    var dragging by remember(key) { mutableStateOf(false) }
    var armed by remember(key) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val haptic=LocalHapticFeedback.current
    val latestDelete=rememberUpdatedState(onDelete)
    val reveal by animateFloatAsState(if(armed)1f else 0f,tween(300,easing=FastOutLinearInEasing),label="swipe-reveal")
    val bounce by animateFloatAsState(if(armed)1f else 0f,tween(170,easing=Easing { android.view.animation.OvershootInterpolator(1.5f).getInterpolation(it) }),label="swipe-glyph")
    val x=if(dragging)fingerOffset else offset.value
    val error=MaterialTheme.colorScheme.error
    Box(modifier.onSizeChanged { width=it.width.toFloat() }.clipToBounds()
        .semantics { customActions = listOf(CustomAccessibilityAction("删除搜索记录") { latestDelete.value(); true }) }
        .pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            fun move(change: PointerInputChange) {
                fingerOffset = (change.position.x - down.position.x).coerceIn(-width, 0f)
                velocity.addPosition(change.uptimeMillis, change.position)
                val next = swipeArmed(fingerOffset, width)
                if (next != armed) { armed = next; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                change.consume()
            }
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                if (change.position.x < down.position.x) {
                    dragging = true; scope.launch { offset.stop() }; move(change)
                }
            }
            if (start != null) {
                val released = horizontalDrag(start.id) { move(it) }
                val speed = velocity.calculateVelocity().x
                val commit = released && (swipeArmed(fingerOffset, width) || (fingerOffset < -viewConfiguration.touchSlop && speed < -3500f))
                dragging = false
                if (commit) {
                    latestDelete.value()
                    scope.launch { offset.snapTo(fingerOffset); offset.animateTo(-width, tween(180, easing = TelegramDecelerate)) }
                } else {
                    armed = false
                    scope.launch { offset.snapTo(fingerOffset); offset.animateTo(0f, tween(200, easing = TelegramDecelerate)) }
                }
            }
        }
    }) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(error.copy(alpha=.65f))
            drawCircle(error,radius=size.maxDimension*reveal,center=Offset(size.width-28.dp.toPx(),size.height/2))
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end=16.dp).graphicsLayer { scaleX=.85f+.15f*bounce;scaleY=scaleX }) {
            Icon(Icons.Outlined.DeleteOutline,null,tint=Color.White)
        }
        Box(Modifier.offset { IntOffset(x.roundToInt(),0) }) { content() }
    }
}
